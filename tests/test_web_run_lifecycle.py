import base64
import json
import os
import shutil
import tempfile
import threading
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import Mock, patch

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

class RunLifecycleServiceTests(unittest.TestCase):
    def test_service_prefers_storage_root_env_when_explicit_root_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            storage_root = Path(tmp) / "custom-storage"
            with patch.dict(
                os.environ, {"ZAOMENG_STORAGE_DIR": str(storage_root)}, clear=False
            ):
                service = WebRunService()

            self.assertEqual(service.storage_root, storage_root)
            self.assertEqual(service.runs_root, storage_root / "runs")
            self.assertEqual(
                service.settings_path, storage_root / "model_settings.json"
            )
            self.assertTrue(service.runs_root.exists())

    def test_model_settings_must_be_configured_before_create_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            with self.assertRaisesRegex(ValueError, "Model is not configured yet."):
                service.create_run(
                    novel_name="hongloumeng.txt",
                    novel_content_base64=base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
                    characters=["林黛玉"],
                )

            settings = service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            self.assertTrue(settings["configured"])

    def test_deferred_run_can_be_created_before_model_configuration(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)

            run = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉"],
                defer_run=True,
            )

            self.assertEqual(run["locked_characters"], ["林黛玉"])
            self.assertEqual(run["status"], "draft")
            self.assertEqual(run["progress"]["stage"], "waiting_to_start")

            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            with patch.object(service, "_start_background_run") as start_background:
                restarted = service.restart_run_distill(
                    run["run_id"],
                    characters=["林黛玉"],
                )

            self.assertEqual(restarted["status"], "running")
            start_background.assert_called_once()

    def test_get_app_update_status_reports_available_update(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            with patch.object(
                service,
                "_discover_launcher_metadata",
                return_value={
                    "launcher_path": "/home/test/.local/bin/zaomeng",
                    "repo_slug": "wkbin/zaomeng",
                    "repo_ref": "main",
                },
            ), patch.object(
                service, "_read_local_app_version", return_value="20260508100000"
            ), patch.object(
                service,
                "_fetch_remote_app_version",
                return_value="20260510120000",
            ):
                status = service.get_app_update_status(force_check=True)

            self.assertTrue(status["supported"])
            self.assertTrue(status["update_available"])
            self.assertEqual(status["current_version"], "20260508100000")
            self.assertEqual(status["remote_version"], "20260510120000")

    def test_start_app_update_runs_in_background_and_marks_reload_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            with patch.object(
                service,
                "_discover_launcher_metadata",
                return_value={
                    "launcher_path": "/home/test/.local/bin/zaomeng",
                    "repo_slug": "wkbin/zaomeng",
                    "repo_ref": "main",
                },
            ), patch.object(
                service,
                "_read_local_app_version",
                side_effect=["20260508100000", "20260510120000"],
            ), patch.object(
                service,
                "_fetch_remote_app_version",
                side_effect=["20260510120000", "20260510120000"],
            ), patch(
                "src.web.service_facades.system_update.subprocess.run"
            ) as run_update:
                run_update.return_value = Mock(
                    returncode=0, stdout="updated", stderr=""
                )
                started = service.start_app_update()
                self.assertEqual(started["status"], "updating")
                self.assertIsNotNone(service._app_update_thread)
                service._app_update_thread.join(timeout=2)
                finished = service.get_app_update_status()

            self.assertEqual(finished["status"], "completed")
            self.assertTrue(finished["reload_required"])
            self.assertFalse(finished["update_available"])

    def test_get_app_update_status_ignores_non_utf8_launcher_candidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            fake_launcher = Path(tmp) / "zaomeng-binary"
            fake_launcher.write_bytes(b"\x00\xff\x00\xff")
            service._launcher_path_hint = str(fake_launcher)

            status = service.get_app_update_status(force_check=True)

            self.assertEqual(status["status"], "unsupported")
            self.assertFalse(status["supported"])
            self.assertEqual(status["launcher_path"], "")

    def test_create_run_builds_manifest_and_payloads(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。薛宝钗也在场。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉", "薛宝钗"],
            )

            self.assertEqual(payload["entrypoint"], "webui")
            self.assertEqual(payload["progress"]["stage"], "relation_payload_ready")
            self.assertEqual(
                payload["summary"]["status_text"], "waiting_for_host_generation"
            )
            self.assertEqual(
                payload["locked_characters"], ["林黛玉", "贾宝玉", "薛宝钗"]
            )
            self.assertEqual(payload["novel_sources"][0]["kind"], "initial")
            self.assertGreater(payload["novel_sources"][0]["byte_size"], 0)
            self.assertGreater(payload["novel_sources"][0]["char_count"], 0)
            self.assertIn("quality", payload)
            self.assertIn("excerpt_focus", payload["quality"])
            self.assertIn("chunking", payload["progress"])
            self.assertIn("chunking", payload["summary"])
            self.assertIn("chunking", payload["artifacts"])
            self.assertIn("distill", payload["artifacts"]["chunking"])
            self.assertIn("relation", payload["artifacts"]["chunking"])

            run_dir = Path(tmp) / "runs" / payload["run_id"]
            self.assertTrue((run_dir / "run_manifest.json").exists())
            self.assertTrue((run_dir / "payloads" / "distill_payload.json").exists())
            self.assertTrue((run_dir / "payloads" / "relation_payload.json").exists())
            self.assertIn("payload_distill", payload["file_urls"])
            self.assertIn("payload_relation", payload["file_urls"])

    def test_list_runs_skips_partially_written_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉"],
            )
            broken_dir = Path(tmp) / "runs" / "run-broken"
            broken_dir.mkdir(parents=True)
            (broken_dir / "run_manifest.json").write_text("{", encoding="utf-8")

            items = service.list_runs()

            self.assertEqual([item["run_id"] for item in items], [payload["run_id"]])

    def test_list_runs_tolerates_legacy_nested_payload_maps(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "王熙凤见了史湘云。晴雯与袭人也在场。".encode("utf-8")
                ).decode("ascii"),
                characters=["王熙凤", "史湘云", "晴雯", "袭人"],
            )
            manifest_path = Path(tmp) / "runs" / payload["run_id"] / "run_manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest.setdefault("artifacts", {}).setdefault("payloads", {})[
                "distill_characters"
            ] = {
                "王熙凤": r"D:\work2\Dreamforge\.zaomeng-web\runs\run-legacy\payloads\distill_王熙凤.json",
                "史湘云": r"D:\work2\Dreamforge\.zaomeng-web\runs\run-legacy\payloads\distill_史湘云.json",
                "晴雯": r"D:\work2\Dreamforge\.zaomeng-web\runs\run-legacy\payloads\distill_晴雯.json",
                "袭人": r"D:\work2\Dreamforge\.zaomeng-web\runs\run-legacy\payloads\distill_袭人.json",
            }
            manifest_path.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

            items = service.list_runs()

            self.assertEqual([item["run_id"] for item in items], [payload["run_id"]])
            self.assertIn("payload_distill", items[0]["file_urls"])
            self.assertNotIn("payload_distill_characters", items[0]["file_urls"])

    def test_create_run_auto_run_starts_background_pipeline(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            with patch.object(service, "_start_background_run") as start_background_run:
                payload = service.create_run(
                    novel_name="hongloumeng.txt",
                    novel_content_base64=base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
                    characters=["林黛玉", "贾宝玉"],
                    auto_run=True,
                )

            self.assertEqual(payload["status"], "running")
            self.assertEqual(payload["progress"]["stage"], "characters_locked")
            start_background_run.assert_called_once()

    def test_restart_run_distill_reuses_existing_novel_and_starts_background_pipeline(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉"],
            )
            with patch.object(service, "_start_background_run") as start_background_run:
                refreshed = service.restart_run_distill(
                    payload["run_id"],
                    characters=["林黛玉", "王熙凤"],
                    max_sentences=120,
                    max_chars=50000,
                )

            self.assertEqual(refreshed["status"], "running")
            self.assertEqual(refreshed["locked_characters"], ["林黛玉", "王熙凤"])
            self.assertEqual(refreshed["progress"]["stage"], "characters_locked")
            self.assertIn("增量蒸馏 2 人", refreshed["redistill"]["summary"])
            start_background_run.assert_called_once()

    def test_restart_run_distill_accepts_new_source_segment_for_incremental_update(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="hongloumeng-1.txt",
                novel_content_base64=base64.b64encode(
                    "第一章里林黛玉初见贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            persona_dir = (
                run_dir / "artifacts" / "characters" / "hongloumeng-1" / "林黛玉"
            )
            persona_dir.mkdir(parents=True, exist_ok=True)
            (persona_dir / "PROFILE.generated.md").write_text(
                "- name: 林黛玉\n", encoding="utf-8"
            )
            service.refresh_run(payload["run_id"])

            with patch.object(service, "_start_background_run") as start_background_run:
                refreshed = service.restart_run_distill(
                    payload["run_id"],
                    characters=["林黛玉", "薛宝钗"],
                    novel_name="hongloumeng-2.txt",
                    novel_content_base64=base64.b64encode(
                        "第二章里宝钗登场，黛玉再见宝玉。".encode("utf-8")
                    ).decode("ascii"),
                )

            self.assertTrue(refreshed["redistill"]["used_new_source"])
            self.assertEqual(refreshed["redistill"]["existing_characters"], ["林黛玉"])
            self.assertEqual(refreshed["redistill"]["new_characters"], ["薛宝钗"])
            self.assertEqual(
                refreshed["redistill"]["relation_characters"], ["林黛玉", "薛宝钗"]
            )
            self.assertIn("增量 1 人", refreshed["redistill"]["summary"])
            self.assertIn("updates", refreshed["novel_path"])
            self.assertEqual(
                refreshed["novel_sources"][-1]["kind"], "incremental_update"
            )
            self.assertGreater(refreshed["novel_sources"][-1]["byte_size"], 0)
            self.assertGreater(refreshed["novel_sources"][-1]["char_count"], 0)
            start_background_run.assert_called_once()

    def test_restart_run_distill_requeues_selected_existing_characters_when_reusing_source(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉先出场，后面宝钗还没来。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "薛宝钗"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            persona_dir = (
                run_dir / "artifacts" / "characters" / "hongloumeng" / "林黛玉"
            )
            persona_dir.mkdir(parents=True, exist_ok=True)
            (persona_dir / "PROFILE.generated.md").write_text(
                "- name: 林黛玉\n", encoding="utf-8"
            )
            service.refresh_run(payload["run_id"])

            with patch.object(service, "_start_background_run") as start_background_run:
                refreshed = service.restart_run_distill(
                    payload["run_id"],
                    characters=["林黛玉", "薛宝钗"],
                    max_sentences=120,
                    max_chars=50000,
                )

            self.assertFalse(refreshed["redistill"]["used_new_source"])
            self.assertEqual(refreshed["redistill"]["resume_completed_characters"], [])
            self.assertEqual(
                refreshed["redistill"]["pending_characters"], ["林黛玉", "薛宝钗"]
            )
            self.assertEqual(refreshed["progress"]["completed_characters"], [])
            self.assertEqual(refreshed["progress"]["completed_count"], 0)
            self.assertEqual(refreshed["summary"]["characters_completed"], 0)
            self.assertIn("增量蒸馏 2 人", refreshed["redistill"]["summary"])
            self.assertEqual(
                refreshed["capabilities"]["distill"]["outputs"]["update_mode"],
                "incremental",
            )
            start_background_run.assert_called_once()

    def test_restart_run_distill_requeues_single_existing_character_for_incremental_redistill(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉先出场，后面宝钗还没来。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "薛宝钗"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            persona_dir = (
                run_dir / "artifacts" / "characters" / "hongloumeng" / "林黛玉"
            )
            persona_dir.mkdir(parents=True, exist_ok=True)
            (persona_dir / "PROFILE.generated.md").write_text(
                "- name: 林黛玉\n- core_identity: 才女\n", encoding="utf-8"
            )
            service.refresh_run(payload["run_id"])

            with patch.object(service, "_start_background_run") as start_background_run:
                refreshed = service.restart_run_distill(
                    payload["run_id"],
                    characters=["林黛玉"],
                    max_sentences=120,
                    max_chars=50000,
                )

            self.assertEqual(refreshed["redistill"]["existing_characters"], ["林黛玉"])
            self.assertEqual(refreshed["redistill"]["pending_characters"], ["林黛玉"])
            self.assertEqual(refreshed["redistill"]["resume_completed_characters"], [])
            self.assertIn("增量蒸馏 1 人", refreshed["redistill"]["summary"])
            start_background_run.assert_called_once()

    def test_suggest_redistill_segments_returns_dialogue_heavy_windows_for_weak_profile(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    (
                        "林黛玉轻声道：“你怎么也在这里？”"
                        "贾宝玉笑道：“我正等你。”"
                        "林黛玉心想，这人说话轻浮，却又不全是假意。"
                        "林黛玉又问了两句，贾宝玉都接了话。"
                        "袭人远远看着，只觉两人气氛古怪。"
                    ).encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            persona_dir = (
                run_dir / "artifacts" / "characters" / "hongloumeng" / "林黛玉"
            )
            persona_dir.mkdir(parents=True, exist_ok=True)
            (persona_dir / "PROFILE.generated.md").write_text(
                "- name: 林黛玉\n- core_identity: 贾府外来才女\n- story_role: 女主角之一\n- speech_style:\n- key_bonds:\n",
                encoding="utf-8",
            )
            service.refresh_run(payload["run_id"])

            suggested = service.suggest_redistill_segments(
                payload["run_id"], "林黛玉", max_segments=2
            )

            self.assertEqual(suggested["character"], "林黛玉")
            self.assertEqual(suggested["source_name"], "hongloumeng.txt")
            self.assertEqual(suggested["source_kind"], "initial")
            self.assertIn("speech_style", suggested["weak_fields"])
            self.assertTrue(suggested["segments"])
            first = suggested["segments"][0]
            self.assertGreaterEqual(first["dialogue_hits"], 1)
            self.assertIn("speech_style", first["estimated_fields"])
            self.assertIn("对白密度较高", first["reason"])
            self.assertTrue(str(first["preview"]).strip())

    def test_suggest_redistill_segments_reads_latest_incremental_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="hongloumeng-1.txt",
                novel_content_base64=base64.b64encode(
                    "第一章里林黛玉出场。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉"],
            )

            with patch.object(service, "_start_background_run"):
                service.restart_run_distill(
                    payload["run_id"],
                    characters=["林黛玉", "薛宝钗"],
                    novel_name="hongloumeng-2.txt",
                    novel_content_base64=base64.b64encode(
                        (
                            "第二章里薛宝钗入府。"
                            "薛宝钗笑道：“早听过妹妹名声。”"
                            "林黛玉看了她一眼，没有立刻作声。"
                            "薛宝钗心想，先把话说软些。"
                        ).encode("utf-8")
                    ).decode("ascii"),
                )

            suggested = service.suggest_redistill_segments(
                payload["run_id"], "薛宝钗", max_segments=2
            )

            self.assertTrue(str(suggested["source_name"]).endswith("hongloumeng-2.txt"))
            self.assertEqual(suggested["source_kind"], "incremental_update")
            self.assertTrue(suggested["segments"])
            self.assertIn("speech_style", suggested["segments"][0]["estimated_fields"])

    def test_delete_run_group_removes_same_novel_runs_and_dialogue(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            first = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉"],
            )
            second = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "宝钗也在场。".encode("utf-8")
                ).decode("ascii"),
                characters=["薛宝钗"],
            )
            third = service.create_run(
                novel_name="sanguo.txt",
                novel_content_base64=base64.b64encode(
                    "刘备见关羽。".encode("utf-8")
                ).decode("ascii"),
                characters=["刘备"],
            )

            first_dialogue_dir = (
                Path(tmp) / "runs" / first["run_id"] / "dialogue" / "dlg-a"
            )
            second_dialogue_dir = (
                Path(tmp) / "runs" / second["run_id"] / "dialogue" / "dlg-b"
            )
            first_dialogue_dir.mkdir(parents=True, exist_ok=True)
            second_dialogue_dir.mkdir(parents=True, exist_ok=True)
            for run in (first, second, third):
                manifest_path = Path(tmp) / "runs" / run["run_id"] / "run_manifest.json"
                payload = json.loads(manifest_path.read_text(encoding="utf-8"))
                payload["status"] = "ready"
                manifest_path.write_text(
                    json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )

            payload = service.delete_run_group(first["run_id"])

            self.assertEqual(payload["status"], "deleted")
            self.assertEqual(payload["deleted_run_count"], 2)
            self.assertEqual(payload["deleted_session_count"], 2)
            self.assertFalse((Path(tmp) / "runs" / first["run_id"]).exists())
            self.assertFalse((Path(tmp) / "runs" / second["run_id"]).exists())
            self.assertTrue((Path(tmp) / "runs" / third["run_id"]).exists())

    def test_delete_run_group_rejects_running_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            with patch.object(service, "_start_background_run"):
                run = service.create_run(
                    novel_name="hongloumeng.txt",
                    novel_content_base64=base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
                    characters=["林黛玉"],
                    auto_run=True,
                )

            with self.assertRaisesRegex(ValueError, "暂时不能删除"):
                service.delete_run_group(run["run_id"])

    def test_stop_run_marks_manifest_and_blocks_non_running_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            with patch.object(service, "_start_background_run"):
                run = service.create_run(
                    novel_name="hongloumeng.txt",
                    novel_content_base64=base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
                    characters=["林黛玉"],
                    auto_run=True,
                )

            stopped = service.stop_run(run["run_id"])
            self.assertTrue(stopped["control"]["stop_requested"])
            self.assertEqual(stopped["summary"]["status_text"], "stop_requested")
            self.assertEqual(stopped["progress"]["stage"], "characters_locked")
            self.assertIn("正在收束当前步骤", stopped["progress"]["message"])

            manifest_path = Path(tmp) / "runs" / run["run_id"] / "run_manifest.json"
            payload = json.loads(manifest_path.read_text(encoding="utf-8"))
            payload["status"] = "ready"
            manifest_path.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "只有正在蒸馏的书卷才能停止"):
                service.stop_run(run["run_id"])

    def test_automatic_pipeline_returns_stopped_when_stop_requested(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            manifest_path = run_dir / "run_manifest.json"
            novel_path = run_dir / "input" / "hongloumeng.txt"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["control"]["stop_requested"] = True
            manifest["control"]["stop_requested_at"] = "2026-05-07T00:00:00Z"
            manifest_path.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

            with patch("src.web.workflow.build_runtime_parts") as build_parts:
                fake_parts = Mock()
                fake_parts.llm.chat_completion = Mock()
                build_parts.return_value = fake_parts
                result = service._run_automatic_pipeline(
                    manifest_path=manifest_path,
                    novel_path=novel_path,
                    locked_characters=["林黛玉"],
                    max_sentences=120,
                    max_chars=50000,
                )

            self.assertEqual(result["status"], "stopped")
            self.assertEqual(result["summary"]["status_text"], "stopped")
            self.assertEqual(result["progress"]["stage"], "stopped")
            self.assertTrue(result["control"]["stop_acknowledged_at"])

    def test_get_run_reconciles_detached_stop_requested_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run_dir = Path(tmp) / "runs" / "run-oldstop"
            run_dir.mkdir(parents=True, exist_ok=True)
            manifest_path = run_dir / "run_manifest.json"
            manifest_path.write_text(
                json.dumps(
                    {
                        "run_id": "run-oldstop",
                        "novel_id": "魔道祖师",
                        "status": "running",
                        "success": False,
                        "progress": {
                            "stage": "distilling",
                            "message": "已收到停止请求，正在收束当前步骤",
                            "current_character": "魏无羡",
                        },
                        "summary": {"status_text": "stop_requested"},
                        "control": {
                            "stop_requested": True,
                            "stop_requested_at": "2026-05-07T10:14:54.453675Z",
                        },
                        "timing": {
                            "started_at": "2026-05-07T10:03:50.967231Z",
                            "completed_at": "",
                            "failed_at": "",
                            "elapsed_seconds": 0.0,
                            "elapsed_text": "",
                        },
                        "events": [],
                    },
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )

            payload = service.get_run("run-oldstop")

            self.assertEqual(payload["status"], "stopped")
            self.assertEqual(payload["summary"]["status_text"], "stopped")
            self.assertEqual(payload["progress"]["stage"], "stopped")
            self.assertIn("魏无羡", payload["progress"]["message"])

    def test_write_json_preserves_stop_requested_from_existing_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run_id = "run-preserve-stop"
            run_dir = Path(tmp) / "runs" / run_id
            run_dir.mkdir(parents=True, exist_ok=True)
            manifest_path = run_dir / "run_manifest.json"
            manifest_path.write_text(
                json.dumps(
                    {
                        "run_id": run_id,
                        "status": "running",
                        "control": {
                            "stop_requested": True,
                            "stop_requested_at": "2026-05-11T10:00:00Z",
                            "stop_acknowledged_at": "",
                        },
                    },
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )

            stale_payload = {
                "run_id": run_id,
                "status": "running",
                "control": {
                    "stop_requested": False,
                    "stop_requested_at": "",
                    "stop_acknowledged_at": "",
                },
            }
            service._write_json(manifest_path, stale_payload)
            merged = json.loads(manifest_path.read_text(encoding="utf-8"))

            self.assertTrue(merged["control"]["stop_requested"])
            self.assertEqual(
                merged["control"]["stop_requested_at"], "2026-05-11T10:00:00Z"
            )

    def test_stop_run_updates_latest_manifest_snapshot(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            with patch.object(service, "_start_background_run"):
                run = service.create_run(
                    novel_name="hongloumeng.txt",
                    novel_content_base64=base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
                    characters=["林黛玉"],
                    auto_run=True,
                )

            manifest_path = Path(tmp) / "runs" / run["run_id"] / "run_manifest.json"
            payload = json.loads(manifest_path.read_text(encoding="utf-8"))
            payload.setdefault("progress", {})["current_character"] = "林黛玉"
            payload["updated_at"] = "2026-05-11T12:00:00Z"
            manifest_path.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

            stale_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            stale_manifest["progress"]["current_character"] = ""
            stale_manifest["updated_at"] = "2000-01-01T00:00:00Z"
            stale_manifest.pop("events", None)

            with patch(
                "src.web.service_facades.runs.stop_run_manifest",
                side_effect=lambda _manifest, *, utc_now: _manifest,
            ):
                with patch.object(
                    service, "_load_manifest", return_value=stale_manifest
                ):
                    stopped = service.stop_run(run["run_id"])

            self.assertEqual(stopped["progress"]["current_character"], "林黛玉")
            self.assertEqual(stopped["updated_at"], "2026-05-11T12:00:00Z")

    def test_update_manifest_uses_latest_file_snapshot_under_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run_id = "run-atomic-update"
            manifest_path = Path(tmp) / "runs" / run_id / "run_manifest.json"
            manifest_path.parent.mkdir(parents=True, exist_ok=True)
            manifest_path.write_text(
                json.dumps(
                    {
                        "run_id": run_id,
                        "status": "running",
                        "progress": {"current_character": "林黛玉"},
                        "updated_at": "2026-05-11T12:00:00Z",
                    },
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )

            done = threading.Event()
            result_holder: dict[str, Any] = {}

            def writer() -> None:
                def updater(current: dict[str, Any]) -> dict[str, Any]:
                    current.setdefault("control", {})["stop_requested"] = True
                    return current

                result_holder["payload"] = service._update_manifest(
                    manifest_path, updater
                )
                done.set()

            fresh_payload = {
                "run_id": run_id,
                "status": "running",
                "progress": {"current_character": "薛宝钗"},
                "updated_at": "2026-05-11T12:01:00Z",
            }
            with service._manifest_lock_context(manifest_path):
                worker = threading.Thread(target=writer)
                worker.start()
                manifest_path.write_text(
                    json.dumps(fresh_payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )

            done.wait(timeout=3)
            worker.join(timeout=3)
            self.assertFalse(worker.is_alive())

            persisted = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(persisted["progress"]["current_character"], "薛宝钗")
            self.assertEqual(persisted["updated_at"], "2026-05-11T12:01:00Z")
            self.assertTrue(
                bool(persisted.get("control", {}).get("stop_requested", False))
            )
            self.assertEqual(
                result_holder["payload"]["progress"]["current_character"], "薛宝钗"
            )

    def test_start_background_run_uses_latest_manifest_snapshot_under_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run_id = "run-background-atomic"
            run_dir = Path(tmp) / "runs" / run_id
            manifest_path = run_dir / "run_manifest.json"
            novel_path = run_dir / "input" / "novel.txt"
            novel_path.parent.mkdir(parents=True, exist_ok=True)
            novel_path.write_text("Alpha meets Beta.", encoding="utf-8")
            manifest_path.write_text(
                json.dumps(
                    {
                        "run_id": run_id,
                        "status": "running",
                        "progress": {"stage": "characters_locked", "message": "ready"},
                        "summary": {"status_text": "ready"},
                        "updated_at": "2026-05-11T12:00:00Z",
                    },
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )

            finished = threading.Event()

            def worker() -> None:
                with patch(
                    "src.web.service_facades.runtime_support.start_background_thread"
                ):
                    service._start_background_run(
                        manifest_path=manifest_path,
                        novel_path=novel_path,
                        locked_characters=["Alpha"],
                        max_sentences=120,
                        max_chars=50000,
                    )
                finished.set()

            with service._manifest_lock_context(manifest_path):
                thread = threading.Thread(target=worker)
                thread.start()
                manifest_path.write_text(
                    json.dumps(
                        {
                            "run_id": run_id,
                            "status": "running",
                            "progress": {
                                "stage": "characters_locked",
                                "message": "fresh",
                            },
                            "summary": {"status_text": "fresh"},
                            "latest_marker": "keep-me",
                            "updated_at": "2026-05-11T12:01:00Z",
                        },
                        ensure_ascii=False,
                        indent=2,
                    )
                    + "\n",
                    encoding="utf-8",
                )

            finished.wait(timeout=3)
            thread.join(timeout=3)
            self.assertFalse(thread.is_alive())

            persisted = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(persisted["latest_marker"], "keep-me")
            self.assertEqual(persisted["progress"]["stage"], "queued")
            self.assertEqual(
                persisted["summary"]["status_text"], "waiting_for_payloads"
            )
