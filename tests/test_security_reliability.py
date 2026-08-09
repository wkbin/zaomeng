from __future__ import annotations

import json
import logging
import os
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

try:
    from fastapi.testclient import TestClient
except (ImportError, RuntimeError):
    TestClient = None

from src.core.config import Config, clear_config_cache
from src.core.llm_client import LLMClient
from src.web.app import create_app
from src.web.chat.service import DialogueService
from src.web.path_safety import InvalidStorageIdentifier
from src.web.pipeline.background_runner import run_pipeline_safely
from src.web.pipeline.chunk_execution import _run_chunk_drafts
from src.web.workflow import WebRunService


class _StoppedError(Exception):
    pass


class SecurityReliabilityTests(unittest.TestCase):
    def tearDown(self) -> None:
        clear_config_cache()

    def test_dialogue_session_path_rejects_directory_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            dialogue = DialogueService(Path(tmp) / "runs")

            for run_id, session_id in (
                ("../outside", "dlg-safe"),
                ("run-safe", "../outside"),
                ("run-safe", "nested/session"),
                ("run.safe", "dlg-safe"),
            ):
                with self.subTest(run_id=run_id, session_id=session_id):
                    with self.assertRaises(InvalidStorageIdentifier):
                        dialogue._session_file(run_id, session_id)

    def test_dialogue_session_path_rejects_run_symlink_escape(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runs_root = root / "runs"
            outside = root / "outside"
            runs_root.mkdir()
            outside.mkdir()
            try:
                (runs_root / "run-safe").symlink_to(outside, target_is_directory=True)
            except OSError as exc:
                self.skipTest(f"Directory symlinks are unavailable: {exc}")
            dialogue = DialogueService(runs_root)

            with self.assertRaises(InvalidStorageIdentifier):
                dialogue._session_file("run-safe", "dlg-safe")

    def test_session_json_write_is_atomic(self):
        with tempfile.TemporaryDirectory() as tmp:
            dialogue = DialogueService(Path(tmp) / "runs")
            path = dialogue._session_file("run-safe", "dlg-safe")

            dialogue._write_json(path, {"session_id": "dlg-safe", "history": ["one"]})
            dialogue._write_json(path, {"session_id": "dlg-safe", "history": ["two"]})

            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["history"], ["two"])
            self.assertEqual(list(path.parent.glob(f".{path.name}.*.tmp")), [])
            self.assertIs(dialogue.session_lock("run-safe", "dlg-safe"), dialogue.session_lock("run-safe", "dlg-safe"))

    def test_concurrent_prepare_does_not_overwrite_pending_turn(self):
        with tempfile.TemporaryDirectory() as tmp:
            dialogue = DialogueService(Path(tmp) / "runs")
            run_id = "run-safe"
            session_id = "dlg-safe"
            dialogue._write_json(
                dialogue._session_file(run_id, session_id),
                {
                    "run_id": run_id,
                    "session_id": session_id,
                    "mode": "observe",
                    "participants": [],
                    "history": [],
                    "pending_turn": {},
                },
            )
            turn_payload = {
                "mode": "observe",
                "input": {"speaker": "User", "participants": [], "active_participants": []},
                "host_action": {"response_limit_hint": 1},
            }
            dialogue._build_turn_payload = lambda *_args, **_kwargs: dict(turn_payload)

            def prepare(message: str) -> str:
                try:
                    dialogue.prepare_turn(
                        {"run_id": run_id},
                        session_id=session_id,
                        message=message,
                    )
                    return "ok"
                except ValueError:
                    return "conflict"

            with ThreadPoolExecutor(max_workers=2) as executor:
                outcomes = list(executor.map(prepare, ["one", "two"]))

            self.assertCountEqual(outcomes, ["ok", "conflict"])
            stored = dialogue._read_json(dialogue._session_file(run_id, session_id))
            self.assertIn(stored["pending_turn"]["user_message"], {"one", "two"})

    def test_background_failure_callback_runs_and_thread_is_removed(self):
        active = {"run-safe": threading.current_thread()}
        failures: list[Exception] = []

        run_pipeline_safely(
            kwargs={"run_id": "run-safe"},
            run_pipeline=lambda **_kwargs: (_ for _ in ()).throw(RuntimeError("disk full")),
            active_run_threads=active,
            logger=logging.getLogger("test-background-runner"),
            on_failure=failures.append,
        )

        self.assertEqual(len(failures), 1)
        self.assertIsInstance(failures[0], RuntimeError)
        self.assertNotIn("run-safe", active)

    def test_background_failure_manifest_has_terminal_details(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run_id = "run-safe"
            manifest_path = service.runs_root / run_id / "run_manifest.json"
            service._write_json(
                manifest_path,
                {
                    "run_id": run_id,
                    "status": "running",
                    "success": False,
                    "timing": {"started_at": "2026-07-20T01:00:00+00:00", "failed_at": ""},
                    "progress": {"stage": "distilling", "current_character": "A"},
                    "events": [],
                },
            )

            service._record_background_pipeline_failure(
                {"manifest_path": manifest_path},
                RuntimeError("model unavailable"),
            )

            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(manifest["status"], "failed")
            self.assertEqual(manifest["progress"]["stage"], "failed")
            self.assertEqual(manifest["progress"]["error_type"], "RuntimeError")
            self.assertTrue(manifest["timing"]["failed_at"])
            self.assertEqual(manifest["events"][-1]["status"], "failed")

    def test_parallel_chunk_retry_only_repeats_failed_chunk(self):
        calls: dict[int, int] = {}

        def run_one(index, _entry):
            calls[index] = calls.get(index, 0) + 1
            if index == 2 and calls[index] == 1:
                raise RuntimeError("temporary")
            return {"index": index, "label": str(index), "content": f"result-{index}"}

        drafts = _run_chunk_drafts(
            run_one=run_one,
            chunk_entries=[{"label": "1"}, {"label": "2"}, {"label": "3"}],
            workers=3,
            thread_name_prefix="test-chunk",
            stopped_error_type=_StoppedError,
            fallback_warning=lambda _exc: None,
            before_each=lambda _index, _entry: None,
        )

        self.assertEqual(calls, {1: 1, 2: 2, 3: 1})
        self.assertEqual([item["content"] for item in drafts], ["result-1", "result-2", "result-3"])

    def test_model_api_key_is_kept_out_of_settings_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="test-model",
                base_url="https://example.test/v1",
                api_key="super-secret",
                max_tokens=1000,
            )

            settings_text = service.settings_path.read_text(encoding="utf-8")
            settings = json.loads(settings_text)
            self.assertNotIn("api_key", settings)
            self.assertNotIn("super-secret", settings_text)
            profiles = list(settings.get("profiles", []) or [])
            self.assertTrue(profiles)
            self.assertTrue(all("api_key" not in profile for profile in profiles))
            active_profile = next(
                profile
                for profile in profiles
                if profile.get("profile_id") == settings.get("active_profile_id")
            )
            self.assertEqual(active_profile["api_key_ref"], "model_api_key")
            self.assertEqual(service._load_model_settings_payload()["api_key"], "super-secret")
            secret_path = service.storage_root / "secrets" / "model_api_key"
            self.assertEqual(secret_path.read_text(encoding="utf-8").strip(), "super-secret")
            if os.name != "nt":
                self.assertEqual(secret_path.stat().st_mode & 0o777, 0o600)

    def test_cost_stats_are_batched_and_atomically_flushed_to_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            config_path = Path(tmp) / "config.yaml"
            config_path.write_text("llm:\n  provider: local-rule-engine\n", encoding="utf-8")
            client = LLMClient(Config(str(config_path)))
            client.COST_STATS_FLUSH_INTERVAL_SECONDS = 60.0

            client.record_usage(10, 5)
            self.assertFalse((Path(tmp) / "data" / "cost_stats.json").exists())

            client.flush_cost_stats()
            stats_path = Path(tmp) / "data" / "cost_stats.json"
            stats = json.loads(stats_path.read_text(encoding="utf-8"))
            self.assertEqual(stats["total_requests"], 1)
            self.assertEqual(stats["total_tokens"], 15)
            self.assertFalse((Path(tmp) / "data" / "cost_stats.md").exists())

    def test_frontend_uses_fragment_token_for_bearer_requests(self):
        repo_root = Path(__file__).resolve().parents[1]
        bootstrap = (repo_root / "src" / "web" / "static" / "js" / "bootstrap.js").read_text(encoding="utf-8")
        core = (repo_root / "src" / "web" / "static" / "js" / "core.js").read_text(encoding="utf-8")

        self.assertIn('authHash.get("token")', bootstrap)
        self.assertIn('sessionStorage.setItem("zaomeng_web_auth_token"', bootstrap)
        self.assertIn('headers.set("Authorization", `Bearer ${authToken}`)', core)
        self.assertIn("fetch(url, webAuthFetchOptions(options))", core)


@unittest.skipIf(TestClient is None, "fastapi test dependencies unavailable")
class WebAuthenticationTests(unittest.TestCase):
    def test_web_app_mounts_static_shell_by_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            client = TestClient(create_app(WebRunService(tmp)))

            self.assertEqual(client.get("/").status_code, 200)
            self.assertEqual(client.get("/web/index.html").status_code, 200)

    def test_api_only_app_does_not_mount_web_static_shell(self):
        with tempfile.TemporaryDirectory() as tmp:
            client = TestClient(create_app(WebRunService(tmp), serve_static=False))

            self.assertEqual(client.get("/api/web/health").status_code, 200)
            self.assertEqual(client.get("/").status_code, 404)
            self.assertEqual(client.get("/web/index.html").status_code, 404)

    def test_bearer_auth(self):
        with tempfile.TemporaryDirectory() as tmp:
            client = TestClient(create_app(WebRunService(tmp), auth_token="test-token"))

            self.assertEqual(client.get("/api/web/health").status_code, 200)
            self.assertEqual(client.get("/api/web/runs").status_code, 401)
            self.assertEqual(
                client.get("/api/web/runs", headers={"Authorization": "Bearer test-token"}).status_code,
                200,
            )

    def test_invalid_run_id_returns_bad_request(self):
        with tempfile.TemporaryDirectory() as tmp:
            client = TestClient(create_app(WebRunService(tmp)))
            response = client.get("/api/web/runs/run.invalid")

            self.assertEqual(response.status_code, 400)

    def test_remote_update_is_disabled_and_confirmation_is_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            disabled_client = TestClient(create_app(WebRunService(tmp), allow_app_update=False))
            disabled = disabled_client.post("/api/web/settings/update", json={"confirm": "update"})
            self.assertEqual(disabled.status_code, 403)

        with tempfile.TemporaryDirectory() as tmp:
            client = TestClient(create_app(WebRunService(tmp), allow_app_update=True))
            unconfirmed = client.post("/api/web/settings/update", json={"confirm": "not-update"})
            self.assertEqual(unconfirmed.status_code, 400)

