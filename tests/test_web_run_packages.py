import base64
import io
import json
import os
import shutil
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from typing import Any
from unittest.mock import Mock, patch

import pytest

from src.core.exceptions import LLMRequestError
from src.web.chat.helpers import (
    _reorder_plot_push_responses,
    build_dialogue_association_llm_messages,
    build_dialogue_llm_messages,
    compact_dialogue_suggestion_payload,
    generate_dialogue_associations,
    generate_dialogue_responses,
    parse_dialogue_associations,
    parse_dialogue_responses,
    parse_dialogue_suggestion,
)
from src.web.pipeline import process_relation_graph, update_manifest_chunk_progress
from src.web.review.persona_completion import collect_persona_web_references
from src.web.workflow import WebRunService

pytest.importorskip(
    "fastapi.testclient",
    reason="Run-package route tests require FastAPI's test client.",
)
pytest.importorskip("httpx", reason="Run-package route tests require httpx.")

from fastapi.testclient import TestClient
from src.web.app import create_app

class RunPackageTests(unittest.TestCase):
    def _build_ready_run(self, service: WebRunService) -> dict[str, Any]:
        service.save_model_settings(
            provider="openai-compatible",
            model="deepseek-chat",
            base_url="https://example.com/v1",
            api_key="sk-test",
        )
        run = service.create_run(
            novel_name="hongloumeng.txt",
            novel_content_base64=base64.b64encode(
                "林黛玉见了贾宝玉。".encode("utf-8")
            ).decode("ascii"),
            characters=["林黛玉", "贾宝玉"],
        )
        for name in ("林黛玉", "贾宝玉"):
            service.ingest_character_result(
                run["run_id"],
                character=name,
                content_base64=base64.b64encode(
                    f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                        "utf-8"
                    )
                ).decode("ascii"),
            )
        manifest_path = service._manifest_path(run["run_id"])
        service._update_manifest(
            manifest_path,
            lambda current: {
                **current,
                "status": "ready",
                "success": True,
                "updated_at": "2026-05-13T00:00:00Z",
                "summary": {
                    **dict(current.get("summary", {}) or {}),
                    "status_text": "workflow_complete",
                },
            },
        )
        return service.get_run(run["run_id"])

    def test_export_and_import_run_package_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run = self._build_ready_run(service)

            exported = service.export_run_package(run["run_id"])
            self.assertTrue(Path(exported["path"]).exists())
            self.assertTrue(str(exported["filename"]).endswith(".zaomeng-run.zip"))

            encoded = base64.b64encode(Path(exported["path"]).read_bytes()).decode(
                "ascii"
            )
            imported = service.import_run_package(
                filename=exported["filename"],
                content_base64=encoded,
            )

            self.assertNotEqual(imported["run_id"], run["run_id"])
            self.assertEqual(imported["novel_id"], run["novel_id"])
            self.assertEqual(imported["status"], "ready")
            self.assertEqual(len(imported["artifact_index"]["characters"]), 2)
            self.assertTrue((Path(imported["webui"]["run_dir"]) / "dialogue").exists())

    def test_export_package_can_exclude_dialogue(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run = self._build_ready_run(service)
            run_dir = Path(run["webui"]["run_dir"])
            session_dir = run_dir / "dialogue" / "session-1"
            session_dir.mkdir(parents=True)
            (session_dir / "session.json").write_text(
                json.dumps(
                    {
                        "run_id": run["run_id"],
                        "session_id": "session-1",
                        "participants": ["林黛玉"],
                        "transcript": [{"speaker": "林黛玉", "message": "旧会话内容。"}],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )

            exported = service.export_run_package(
                run["run_id"],
                include_dialogue=False,
            )
            with zipfile.ZipFile(exported["path"]) as archive:
                names = archive.namelist()
                self.assertFalse(any(name.startswith("run/dialogue/") for name in names))
                manifest = json.loads(
                    archive.read("package_manifest.json").decode("utf-8")
                )
                self.assertFalse(manifest["includes_dialogue"])

    def test_export_package_can_include_dialogue_explicitly(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run = self._build_ready_run(service)
            run_dir = Path(run["webui"]["run_dir"])
            session_dir = run_dir / "dialogue" / "session-1"
            session_dir.mkdir(parents=True)
            (session_dir / "session.json").write_text(
                json.dumps(
                    {
                        "run_id": run["run_id"],
                        "session_id": "session-1",
                        "participants": ["林黛玉"],
                        "transcript": [{"speaker": "林黛玉", "message": "旧会话内容。"}],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )

            exported = service.export_run_package(
                run["run_id"],
                include_dialogue=True,
            )
            with zipfile.ZipFile(exported["path"]) as archive:
                names = archive.namelist()
                self.assertTrue(any(name.startswith("run/dialogue/") for name in names))
                manifest = json.loads(
                    archive.read("package_manifest.json").decode("utf-8")
                )
                self.assertTrue(manifest["includes_dialogue"])

    def test_export_does_not_require_permission_to_copy_file_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run = self._build_ready_run(service)

            with patch(
                "src.web.run_ops.packages.shutil.copystat",
                side_effect=PermissionError("SELinux denied metadata copy"),
            ) as copy_metadata:
                exported = service.export_run_package(run["run_id"])

            self.assertTrue(Path(exported["path"]).exists())
            copy_metadata.assert_not_called()

    def test_import_does_not_require_permission_to_copy_file_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run = self._build_ready_run(service)
            exported = service.export_run_package(run["run_id"])
            encoded = base64.b64encode(Path(exported["path"]).read_bytes()).decode(
                "ascii"
            )

            with patch(
                "src.web.run_ops.packages.shutil.copystat",
                side_effect=PermissionError("SELinux denied metadata copy"),
            ) as copy_metadata:
                imported = service.import_run_package(
                    filename=exported["filename"],
                    content_base64=encoded,
                )

            self.assertEqual(imported["novel_id"], run["novel_id"])
            self.assertTrue(Path(imported["webui"]["run_dir"]).exists())
            copy_metadata.assert_not_called()

    def test_failed_import_removes_partially_copied_run_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run = self._build_ready_run(service)
            exported = service.export_run_package(run["run_id"])
            encoded = base64.b64encode(Path(exported["path"]).read_bytes()).decode(
                "ascii"
            )
            imported_run_id = "run-import-failure"
            imported_run_dir = service.runs_root / imported_run_id
            copyfile = shutil.copyfile
            copied_file_count = 0

            def fail_during_copy(source: str, target: str) -> str:
                nonlocal copied_file_count
                copied_file_count += 1
                if copied_file_count == 2:
                    raise PermissionError("forced mid-copy failure")
                return copyfile(source, target)

            with (
                patch.object(service, "_new_run_id", return_value=imported_run_id),
                patch(
                    "src.web.run_ops.packages.shutil.copyfile",
                    side_effect=fail_during_copy,
                ),
            ):
                with self.assertRaisesRegex(PermissionError, "forced mid-copy failure"):
                    service.import_run_package(
                        filename=exported["filename"],
                        content_base64=encoded,
                    )

            self.assertEqual(copied_file_count, 2)
            self.assertFalse(imported_run_dir.exists())

    def test_list_and_clone_builtin_novels(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run = self._build_ready_run(service)
            exported = service.export_run_package(run["run_id"], builtin=True)
            builtin_path = service.builtin_novels_root / exported["filename"]
            shutil.copy2(exported["path"], builtin_path)

            items = service.list_builtin_novels()
            self.assertEqual(len(items), 1)
            self.assertEqual(items[0]["novel_id"], "hongloumeng")

            cloned = service.clone_builtin_novel(items[0]["package_id"])
            self.assertNotEqual(cloned["run_id"], run["run_id"])
            self.assertEqual(cloned["entrypoint"], "builtin")
            self.assertEqual(cloned["status"], "ready")

    def test_publish_run_as_builtin_copies_package_into_builtin_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run = self._build_ready_run(service)

            published = service.publish_run_as_builtin(run["run_id"])

            target = Path(published["package_path"])
            self.assertTrue(target.exists())
            self.assertEqual(target.parent, service.builtin_novels_root.resolve())
            items = service.list_builtin_novels()
            self.assertEqual(len(items), 1)
            self.assertEqual(items[0]["novel_id"], "hongloumeng")

    @unittest.skipIf(
        TestClient is None or create_app is None, "fastapi test client not installed"
    )
    def test_run_package_routes_support_builtin_list_clone_import_and_export(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            app = create_app(service)
            client = TestClient(app)
            run = self._build_ready_run(service)
            exported = service.export_run_package(run["run_id"], builtin=True)
            builtin_path = service.builtin_novels_root / exported["filename"]
            shutil.copy2(exported["path"], builtin_path)

            builtin_response = client.get("/api/web/builtin-novels")
            self.assertEqual(builtin_response.status_code, 200)
            items = builtin_response.json()["items"]
            self.assertEqual(len(items), 1)

            clone_response = client.post(
                f"/api/web/builtin-novels/{items[0]['package_id']}/clone"
            )
            self.assertEqual(clone_response.status_code, 200)
            self.assertEqual(clone_response.json()["entrypoint"], "builtin")

            export_response = client.get(f"/api/web/runs/{run['run_id']}/export")
            self.assertEqual(export_response.status_code, 200)
            self.assertEqual(export_response.headers["content-type"], "application/zip")

            import_response = client.post(
                "/api/web/runs/import",
                json={
                    "filename": exported["filename"],
                    "content_base64": base64.b64encode(
                        Path(exported["path"]).read_bytes()
                    ).decode("ascii"),
                },
            )
            self.assertEqual(import_response.status_code, 200)
            self.assertEqual(import_response.json()["novel_id"], "hongloumeng")

            publish_response = client.post(
                f"/api/web/runs/{run['run_id']}/publish-builtin"
            )
            self.assertEqual(publish_response.status_code, 200)
            self.assertTrue(Path(publish_response.json()["package_path"]).exists())

            run_dir = Path(run["webui"]["run_dir"])
            session_dir = run_dir / "dialogue" / "session-1"
            session_dir.mkdir(parents=True)
            (session_dir / "session.json").write_text(
                json.dumps(
                    {
                        "run_id": run["run_id"],
                        "session_id": "session-1",
                        "participants": ["林黛玉"],
                        "transcript": [{"speaker": "林黛玉", "message": "旧会话内容。"}],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )

            export_without_dialogue = client.get(
                f"/api/web/runs/{run['run_id']}/export",
                params={"include_dialogue": False},
            )
            self.assertEqual(export_without_dialogue.status_code, 200)
            with zipfile.ZipFile(io.BytesIO(export_without_dialogue.content)) as archive:
                self.assertFalse(
                    any(name.startswith("run/dialogue/") for name in archive.namelist())
                )

            share_without_dialogue = client.post(
                f"/api/web/runs/{run['run_id']}/share",
                json={"include_dialogue": False},
            )
            self.assertEqual(share_without_dialogue.status_code, 200)
            self.assertEqual(
                share_without_dialogue.headers["content-type"],
                "application/zip",
            )
            with zipfile.ZipFile(io.BytesIO(share_without_dialogue.content)) as archive:
                self.assertFalse(
                    any(name.startswith("run/dialogue/") for name in archive.namelist())
                )

            share_with_dialogue = client.post(
                f"/api/web/runs/{run['run_id']}/share",
                json={"include_dialogue": True},
            )
            self.assertEqual(share_with_dialogue.status_code, 200)
            with zipfile.ZipFile(io.BytesIO(share_with_dialogue.content)) as archive:
                self.assertTrue(
                    any(name.startswith("run/dialogue/") for name in archive.namelist())
                )
