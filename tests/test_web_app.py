import base64
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from src.core.exceptions import LLMRequestError
from src.web.workflow import WebRunService

try:
    from fastapi.testclient import TestClient
    from src.web.app import create_app
except Exception:  # pragma: no cover - optional test dependency guard
    TestClient = None
    create_app = None


class WebRunServiceTests(unittest.TestCase):
    def test_model_settings_must_be_configured_before_create_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            with self.assertRaisesRegex(ValueError, "Model is not configured yet."):
                service.create_run(
                    novel_name="hongloumeng.txt",
                    novel_content_base64=base64.b64encode("林黛玉见了贾宝玉。".encode("utf-8")).decode("ascii"),
                    characters=["林黛玉"],
                )

            settings = service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            self.assertTrue(settings["configured"])

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
                novel_content_base64=base64.b64encode("林黛玉见了贾宝玉。薛宝钗也在场。".encode("utf-8")).decode("ascii"),
                characters=["林黛玉", "贾宝玉", "薛宝钗"],
            )

            self.assertEqual(payload["entrypoint"], "webui")
            self.assertEqual(payload["progress"]["stage"], "relation_payload_ready")
            self.assertEqual(payload["summary"]["status_text"], "waiting_for_host_generation")
            self.assertEqual(payload["locked_characters"], ["林黛玉", "贾宝玉", "薛宝钗"])

            run_dir = Path(tmp) / "runs" / payload["run_id"]
            self.assertTrue((run_dir / "run_manifest.json").exists())
            self.assertTrue((run_dir / "payloads" / "distill_payload.json").exists())
            self.assertTrue((run_dir / "payloads" / "relation_payload.json").exists())
            self.assertIn("payload_distill", payload["file_urls"])
            self.assertIn("payload_relation", payload["file_urls"])

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
                    novel_content_base64=base64.b64encode("林黛玉见了贾宝玉。".encode("utf-8")).decode("ascii"),
                    characters=["林黛玉", "贾宝玉"],
                    auto_run=True,
                )

            self.assertEqual(payload["status"], "running")
            self.assertEqual(payload["progress"]["stage"], "characters_locked")
            start_background_run.assert_called_once()

    def test_restart_run_distill_reuses_existing_novel_and_starts_background_pipeline(self):
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
                novel_content_base64=base64.b64encode("林黛玉见了贾宝玉。".encode("utf-8")).decode("ascii"),
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
            self.assertIn("新增 2 人", refreshed["redistill"]["summary"])
            start_background_run.assert_called_once()

    def test_automatic_pipeline_records_second_pass_disabled_as_soft_event(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="novel.txt",
                novel_content_base64=base64.b64encode("Alpha meets Beta.".encode("utf-8")).decode("ascii"),
                characters=["Alpha"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            manifest_path = run_dir / "run_manifest.json"
            novel_path = run_dir / "input" / "novel.txt"

            class _FakePathProvider:
                def __init__(self, base_dir: Path) -> None:
                    self.base_dir = base_dir

                def characters_root(self, novel_id: str) -> Path:
                    path = self.base_dir / "artifacts" / "characters" / novel_id
                    path.mkdir(parents=True, exist_ok=True)
                    return path

                def relations_file(self, novel_id: str) -> Path:
                    path = self.base_dir / "artifacts" / "relations" / f"{novel_id}_relations.md"
                    path.parent.mkdir(parents=True, exist_ok=True)
                    return path

            fake_parts = Mock()
            fake_parts.path_provider = _FakePathProvider(run_dir)

            def _fake_distill(_novel_path: str, *, characters, output_dir, progress_callback):
                progress_callback("refining_character", {"character": characters[0]})
                progress_callback(
                    "second_pass_disabled",
                    {
                        "character": characters[0],
                        "reason": "当前模型供应商账号没有可用的 CodingPlan / 订阅权限，二次精修已自动跳过。",
                    },
                )
                progress_callback("character_done", {"character": characters[0]})
                character_dir = Path(output_dir) / characters[0]
                character_dir.mkdir(parents=True, exist_ok=True)
                (character_dir / "PROFILE.generated.md").write_text("- name: Alpha\n", encoding="utf-8")

            def _fake_extract(_novel_path: str, *, output_path, characters, progress_callback):
                progress_callback("rendering_graph", {})
                Path(output_path).write_text("## Alpha_Beta\n", encoding="utf-8")
                progress_callback("graph_done", {})

            fake_parts.distiller.distill.side_effect = _fake_distill
            fake_parts.extractor.extract.side_effect = _fake_extract

            with patch("src.web.workflow.build_runtime_parts", return_value=fake_parts):
                result = service._run_automatic_pipeline(
                    manifest_path=manifest_path,
                    novel_path=novel_path,
                    locked_characters=["Alpha"],
                    max_sentences=120,
                    max_chars=50000,
                )

            self.assertTrue(result["success"])
            second_pass_event = next(item for item in result["events"] if item["stage"] == "second_pass_disabled")
            self.assertIn("二次精修", second_pass_event["message"])

    def test_automatic_pipeline_uses_distinct_distill_stage_messages(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="novel.txt",
                novel_content_base64=base64.b64encode("Alpha meets Beta.".encode("utf-8")).decode("ascii"),
                characters=["Alpha"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            manifest_path = run_dir / "run_manifest.json"
            novel_path = run_dir / "input" / "novel.txt"

            class _FakePathProvider:
                def __init__(self, base_dir: Path) -> None:
                    self.base_dir = base_dir

                def characters_root(self, novel_id: str) -> Path:
                    path = self.base_dir / "artifacts" / "characters" / novel_id
                    path.mkdir(parents=True, exist_ok=True)
                    return path

                def relations_file(self, novel_id: str) -> Path:
                    path = self.base_dir / "artifacts" / "relations" / f"{novel_id}_relations.md"
                    path.parent.mkdir(parents=True, exist_ok=True)
                    return path

            fake_parts = Mock()
            fake_parts.path_provider = _FakePathProvider(run_dir)

            def _fake_distill(_novel_path: str, *, characters, output_dir, progress_callback):
                progress_callback("text_loaded", {"chunk_count": 3})
                progress_callback("characters_ready", {"total": 1, "characters": characters})
                progress_callback("drafting_character", {"character": characters[0]})
                progress_callback("refining_character", {"character": characters[0]})
                progress_callback("character_done", {"character": characters[0]})
                character_dir = Path(output_dir) / characters[0]
                character_dir.mkdir(parents=True, exist_ok=True)
                (character_dir / "PROFILE.generated.md").write_text("- name: Alpha\n", encoding="utf-8")

            def _fake_extract(_novel_path: str, *, output_path, characters, progress_callback):
                progress_callback("rendering_graph", {})
                Path(output_path).write_text("## Alpha_Beta\n", encoding="utf-8")
                progress_callback("graph_done", {})

            fake_parts.distiller.distill.side_effect = _fake_distill
            fake_parts.extractor.extract.side_effect = _fake_extract

            with patch("src.web.workflow.build_runtime_parts", return_value=fake_parts):
                result = service._run_automatic_pipeline(
                    manifest_path=manifest_path,
                    novel_path=novel_path,
                    locked_characters=["Alpha"],
                    max_sentences=120,
                    max_chars=50000,
                )

            messages = [item["message"] for item in result["events"]]
            self.assertIn("已载入小说文本", messages)
            self.assertIn("已锁定 1 个待蒸馏角色", messages)
            self.assertIn("正在提取 Alpha", messages)
            self.assertIn("正在精修 Alpha", messages)

    def test_refresh_run_discovers_character_cards_and_graph_outputs(self):
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
                novel_content_base64=base64.b64encode("林黛玉见了贾宝玉。薛宝钗也在场。".encode("utf-8")).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            characters_root = run_dir / "artifacts" / "characters" / "hongloumeng"
            dai_dir = characters_root / "林黛玉"
            dai_dir.mkdir(parents=True, exist_ok=True)
            (dai_dir / "PROFILE.generated.md").write_text(
                "\n".join(
                    [
                        "- name: 林黛玉",
                        "- core_identity: 贾府外来才女",
                        "- story_role: 情感核心",
                        "- soul_goal: 守住真心",
                        "- speech_style: 清冷带刺",
                        "- temperament_type: 敏感孤高",
                    ]
                ),
                encoding="utf-8",
            )
            relations_root = run_dir / "artifacts" / "relations"
            relations_root.mkdir(parents=True, exist_ok=True)
            (relations_root / "hongloumeng_relations.html").write_text("<html></html>", encoding="utf-8")
            (relations_root / "hongloumeng_relations.svg").write_text("<svg></svg>", encoding="utf-8")
            (relations_root / "hongloumeng_relations.mermaid.md").write_text("graph LR", encoding="utf-8")
            (relations_root / "hongloumeng_relations.md").write_text("## 林黛玉_贾宝玉", encoding="utf-8")

            refreshed = service.refresh_run(payload["run_id"])
            self.assertEqual(refreshed["summary"]["characters_completed"], 1)
            self.assertEqual(refreshed["summary"]["graph_status"], "complete")
            self.assertEqual(refreshed["artifact_index"]["characters"][0]["name"], "林黛玉")
            self.assertIn("graph_html", refreshed["file_urls"])
            self.assertIn("graph_svg", refreshed["file_urls"])

    def test_ingest_character_result_materializes_bundle(self):
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
                novel_content_base64=base64.b64encode("林黛玉见了贾宝玉。".encode("utf-8")).decode("ascii"),
                characters=["林黛玉"],
            )
            profile_text = "\n".join(
                [
                    "- name: 林黛玉",
                    "- novel_id: hongloumeng",
                    "- core_identity: 贾府外来才女",
                    "- story_role: 情感核心",
                    "- soul_goal: 守住真心",
                    "- speech_style: 清冷带刺",
                ]
            )
            refreshed = service.ingest_character_result(
                payload["run_id"],
                character="林黛玉",
                content_base64=base64.b64encode(profile_text.encode("utf-8")).decode("ascii"),
            )
            self.assertEqual(refreshed["summary"]["characters_completed"], 1)
            self.assertEqual(refreshed["artifact_index"]["characters"][0]["name"], "林黛玉")
            self.assertTrue(
                (Path(tmp) / "runs" / payload["run_id"] / "artifacts" / "characters" / "hongloumeng" / "林黛玉" / "SOUL.generated.md").exists()
            )

    def test_ingest_relation_result_exports_graph(self):
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
                novel_content_base64=base64.b64encode("林黛玉见了贾宝玉。".encode("utf-8")).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            relations_text = "\n".join(
                [
                    "- novel_id: hongloumeng",
                    "## 林黛玉_贾宝玉",
                    "- trust: 9",
                    "- affection: 10",
                    "- hostility: 1",
                    "- relation_change: 升温",
                    "- typical_interaction: 常以试探与关心交错",
                ]
            )
            refreshed = service.ingest_relation_result(
                payload["run_id"],
                content_base64=base64.b64encode(relations_text.encode("utf-8")).decode("ascii"),
                filename="hongloumeng_relations.md",
            )
            self.assertEqual(refreshed["summary"]["graph_status"], "complete")
            self.assertIn("graph_html", refreshed["file_urls"])
            self.assertTrue(
                (Path(tmp) / "runs" / payload["run_id"] / "artifacts" / "relations" / "hongloumeng_relations.html").exists()
            )

    def test_dialogue_session_prepare_and_ingest(self):
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
                novel_content_base64=base64.b64encode("林黛玉见了贾宝玉。".encode("utf-8")).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            service.ingest_character_result(
                payload["run_id"],
                character="林黛玉",
                content_base64=base64.b64encode(
                    "- name: 林黛玉\n- novel_id: hongloumeng\n- core_identity: 才女\n- soul_goal: 守住真心\n".encode("utf-8")
                ).decode("ascii"),
            )
            service.ingest_character_result(
                payload["run_id"],
                character="贾宝玉",
                content_base64=base64.b64encode(
                    "- name: 贾宝玉\n- novel_id: hongloumeng\n- core_identity: 公子\n- soul_goal: 护住眼前人\n".encode("utf-8")
                ).decode("ascii"),
            )

            with patch.object(
                service,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session = service.create_dialogue_session(
                    payload["run_id"],
                    mode="insert",
                    participants=["???", "???"],
                    self_profile={"display_name": "Self", "scene_identity": "Guest"},
                )
            prepared = service.prepare_dialogue_turn(
                payload["run_id"],
                session_id=session["session_id"],
                message="我刚进园子，想先和你们打个招呼。",
            )
            self.assertEqual(prepared["status"], "waiting_for_host_reply")
            self.assertIn("pending_turn_payload", prepared["file_urls"])
            self.assertEqual(prepared["session_card"]["self_insert"]["display_name"], "Self")
            self.assertEqual(prepared["pending_turn_summary"]["speaker"], "Self")

            completed = service.ingest_dialogue_turn(
                payload["run_id"],
                session_id=session["session_id"],
                responses=[{"speaker": "林黛玉", "message": "你既来了，先坐下说话。"}],
            )
            self.assertEqual(completed["status"], "ready")
            self.assertEqual(len(completed["transcript"]), 3)
            self.assertEqual(completed["transcript"][0]["role"], "scene")
            self.assertEqual(completed["transcript"][1]["role"], "user")
            self.assertEqual(completed["transcript"][2]["role"], "character")

@unittest.skipUnless(TestClient and create_app, "fastapi test client is unavailable")
class WebAppRouteTests(unittest.TestCase):
    def test_model_settings_route_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = create_app(WebRunService(tmp))
            client = TestClient(app)

            initial = client.get("/api/web/settings/model")
            self.assertEqual(initial.status_code, 200)
            self.assertFalse(initial.json()["configured"])

            saved = client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "sk-test",
                },
            )
            self.assertEqual(saved.status_code, 200)
            self.assertTrue(saved.json()["configured"])

    def test_recent_sessions_route_lists_created_sessions(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = create_app(WebRunService(tmp))
            client = TestClient(app)
            client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "sk-test",
                },
            )
            create_response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng.txt",
                    "novel_content_base64": base64.b64encode("林黛玉见了贾宝玉。".encode("utf-8")).decode("ascii"),
                    "characters": ["林黛玉", "贾宝玉"],
                },
            )
            run = create_response.json()
            for name in ("林黛玉", "贾宝玉"):
                client.post(
                    f"/api/web/runs/{run['run_id']}/ingest/character",
                    json={
                        "character": name,
                        "content_base64": base64.b64encode(
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode("utf-8")
                        ).decode("ascii"),
                    },
                )
            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions",
                    json={
                        "mode": "observe",
                        "participants": ["???", "???"],
                        "controlled_character": "",
                        "self_profile": {},
                    },
                )

            sessions_response = client.get("/api/web/sessions")
            self.assertEqual(sessions_response.status_code, 200)
            self.assertEqual(len(sessions_response.json()["items"]), 1)

    def test_delete_dialogue_session_route_removes_session(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = create_app(WebRunService(tmp))
            client = TestClient(app)
            client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "sk-test",
                },
            )
            create_response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng.txt",
                    "novel_content_base64": base64.b64encode("镜中两人相见。".encode("utf-8")).decode("ascii"),
                    "characters": ["林黛玉", "贾宝玉"],
                },
            )
            run = create_response.json()
            for name in ("林黛玉", "贾宝玉"):
                client.post(
                    f"/api/web/runs/{run['run_id']}/ingest/character",
                    json={
                        "character": name,
                        "content_base64": base64.b64encode(
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode("utf-8")
                        ).decode("ascii"),
                    },
                )
            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions",
                    json={
                        "mode": "observe",
                        "participants": ["???", "???"],
                        "controlled_character": "",
                        "self_profile": {},
                    },
                )
            session = session_response.json()

            delete_response = client.delete(
                f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}"
            )
            self.assertEqual(delete_response.status_code, 200)
            self.assertEqual(delete_response.json()["status"], "deleted")

            sessions_response = client.get("/api/web/sessions")
            self.assertEqual(sessions_response.status_code, 200)
            self.assertEqual(len(sessions_response.json()["items"]), 0)

    def test_create_run_and_fetch_manifest_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = create_app(WebRunService(tmp))
            client = TestClient(app)
            client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "sk-test",
                },
            )

            create_response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng.txt",
                    "novel_content_base64": base64.b64encode(
                        "林黛玉初见贾宝玉。贾宝玉也在看她。".encode("utf-8")
                    ).decode("ascii"),
                    "characters": ["林黛玉", "贾宝玉"],
                    "max_sentences": 120,
                    "max_chars": 50000,
                },
            )
            self.assertEqual(create_response.status_code, 200)
            payload = create_response.json()

            list_response = client.get("/api/web/runs")
            self.assertEqual(list_response.status_code, 200)
            self.assertEqual(len(list_response.json()["items"]), 1)

            manifest_response = client.get(payload["file_urls"]["manifest"])
            self.assertEqual(manifest_response.status_code, 200)
            self.assertIn('"run_id"', manifest_response.text)

    def test_redistill_route_restarts_existing_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            app = create_app(service)
            client = TestClient(app)
            client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "sk-test",
                },
            )
            create_response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng.txt",
                    "novel_content_base64": base64.b64encode("镜中两人相见。".encode("utf-8")).decode("ascii"),
                    "characters": ["林黛玉"],
                },
            )
            payload = create_response.json()

            with patch.object(service, "_start_background_run") as start_background_run:
                response = client.post(
                    f"/api/web/runs/{payload['run_id']}/redistill",
                    json={
                        "characters": ["林黛玉", "王熙凤"],
                        "max_sentences": 120,
                        "max_chars": 50000,
                    },
                )

            self.assertEqual(response.status_code, 200)
            data = response.json()
            self.assertEqual(data["locked_characters"], ["林黛玉", "王熙凤"])
            self.assertEqual(data["status"], "running")
            self.assertIn("redistill", data)
            start_background_run.assert_called_once()

    def test_refresh_route_updates_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = create_app(WebRunService(tmp))
            client = TestClient(app)
            client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "sk-test",
                },
            )
            create_response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng.txt",
                    "novel_content_base64": base64.b64encode("林黛玉见贾宝玉。".encode("utf-8")).decode("ascii"),
                    "characters": ["林黛玉"],
                },
            )
            payload = create_response.json()
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            profile_dir = run_dir / "artifacts" / "characters" / "hongloumeng" / "林黛玉"
            profile_dir.mkdir(parents=True, exist_ok=True)
            (profile_dir / "PROFILE.generated.md").write_text("- name: 林黛玉\n- core_identity: 才女\n", encoding="utf-8")

            refresh_response = client.post(f"/api/web/runs/{payload['run_id']}/refresh")
            self.assertEqual(refresh_response.status_code, 200)
            refreshed = refresh_response.json()
            self.assertEqual(refreshed["summary"]["characters_completed"], 1)

    def test_ingest_routes_update_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = create_app(WebRunService(tmp))
            client = TestClient(app)
            client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "sk-test",
                },
            )
            create_response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng.txt",
                    "novel_content_base64": base64.b64encode("林黛玉见了贾宝玉。".encode("utf-8")).decode("ascii"),
                    "characters": ["林黛玉", "贾宝玉"],
                },
            )
            run = create_response.json()

            profile_text = "- name: 林黛玉\n- novel_id: hongloumeng\n- core_identity: 才女\n"
            character_response = client.post(
                f"/api/web/runs/{run['run_id']}/ingest/character",
                json={
                    "character": "林黛玉",
                    "content_base64": base64.b64encode(profile_text.encode("utf-8")).decode("ascii"),
                    "filename": "PROFILE.generated.md",
                },
            )
            self.assertEqual(character_response.status_code, 200)
            self.assertEqual(character_response.json()["summary"]["characters_completed"], 1)
            self.assertIn("character_林黛玉", character_response.json()["file_urls"])

            relations_text = "\n".join(
                [
                    "- novel_id: hongloumeng",
                    "## 林黛玉_贾宝玉",
                    "- trust: 8",
                    "- affection: 9",
                    "- hostility: 1",
                ]
            )
            relation_response = client.post(
                f"/api/web/runs/{run['run_id']}/ingest/relation",
                json={
                    "content_base64": base64.b64encode(relations_text.encode("utf-8")).decode("ascii"),
                    "filename": "hongloumeng_relations.md",
                },
            )
            self.assertEqual(relation_response.status_code, 200)
            self.assertEqual(relation_response.json()["summary"]["graph_status"], "complete")

    def test_dialogue_routes_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = create_app(WebRunService(tmp))
            client = TestClient(app)
            client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "sk-test",
                },
            )
            create_response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng.txt",
                    "novel_content_base64": base64.b64encode("林黛玉见了贾宝玉。".encode("utf-8")).decode("ascii"),
                    "characters": ["林黛玉", "贾宝玉"],
                },
            )
            run = create_response.json()
            for name in ("林黛玉", "贾宝玉"):
                client.post(
                    f"/api/web/runs/{run['run_id']}/ingest/character",
                    json={
                        "character": name,
                        "content_base64": base64.b64encode(
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode("utf-8")
                        ).decode("ascii"),
                    },
                )

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions",
                    json={
                        "mode": "observe",
                        "participants": ["???", "???"],
                        "controlled_character": "",
                        "self_profile": {},
                    },
                )
            self.assertEqual(session_response.status_code, 200)
            session = session_response.json()

            prepare_response = client.post(
                f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}/prepare",
                json={"message": "两个人先聊起来吧。"},
            )
            self.assertEqual(prepare_response.status_code, 200)
            self.assertEqual(prepare_response.json()["status"], "waiting_for_host_reply")
            self.assertEqual(prepare_response.json()["pending_turn_summary"]["speaker"], "User")

            ingest_response = client.post(
                f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}/ingest",
                json={"responses": [{"speaker": "林黛玉", "message": "今日风倒清。"}]},
            )
            self.assertEqual(ingest_response.status_code, 200)
            self.assertEqual(ingest_response.json()["status"], "ready")
            self.assertEqual(ingest_response.json()["transcript"][0]["role"], "director")

    def test_dialogue_reply_route_generates_and_ingests(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            app = create_app(service)
            client = TestClient(app)
            client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "sk-test",
                },
            )
            create_response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng.txt",
                    "novel_content_base64": base64.b64encode("林黛玉见了贾宝玉。".encode("utf-8")).decode("ascii"),
                    "characters": ["林黛玉", "贾宝玉"],
                },
            )
            run = create_response.json()
            for name in ("林黛玉", "贾宝玉"):
                client.post(
                    f"/api/web/runs/{run['run_id']}/ingest/character",
                    json={
                        "character": name,
                        "content_base64": base64.b64encode(
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode("utf-8")
                        ).decode("ascii"),
                    },
                )
            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions",
                    json={
                        "mode": "observe",
                        "participants": ["???", "???"],
                        "controlled_character": "",
                        "self_profile": {},
                    },
                )
            session = session_response.json()

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "林黛玉", "message": "你既来了，先坐下说话。"}],
            ):
                reply_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}/reply",
                    json={"message": "你们先聊几句。"},
                )

            self.assertEqual(reply_response.status_code, 200)
            payload = reply_response.json()
            self.assertEqual(payload["status"], "ready")
            self.assertEqual(payload["transcript"][-1]["speaker"], "林黛玉")


    def test_dialogue_reply_route_returns_friendly_model_subscription_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            app = create_app(service)
            client = TestClient(app)
            client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "sk-test",
                },
            )
            create_response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng.txt",
                    "novel_content_base64": base64.b64encode("镜中两人相见。".encode("utf-8")).decode("ascii"),
                    "characters": ["林黛玉", "贾宝玉"],
                },
            )
            run = create_response.json()
            for name in ("林黛玉", "贾宝玉"):
                client.post(
                    f"/api/web/runs/{run['run_id']}/ingest/character",
                    json={
                        "character": name,
                        "content_base64": base64.b64encode(
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode("utf-8")
                        ).decode("ascii"),
                    },
                )
            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions",
                    json={
                        "mode": "observe",
                        "participants": ["???", "???"],
                        "controlled_character": "",
                        "self_profile": {},
                    },
                )
            session = session_response.json()

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                side_effect=LLMRequestError(
                    'LLM 请求失败: 400 Bad Request | {"error":{"code":"InvalidSubscription","message":"CodingPlan expired"}}'
                ),
            ):
                reply_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}/reply",
                    json={"message": "你们先聊几句。"},
                )

            self.assertEqual(reply_response.status_code, 400)
            self.assertIn("对话生成订阅权限", reply_response.json()["detail"])

    def test_dialogue_reply_retries_once_after_empty_reply(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            app = create_app(service)
            client = TestClient(app)
            client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "sk-test",
                },
            )
            create_response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng.txt",
                    "novel_content_base64": base64.b64encode("镜中两人相见。".encode("utf-8")).decode("ascii"),
                    "characters": ["林黛玉", "贾宝玉"],
                },
            )
            run = create_response.json()
            for name in ("林黛玉", "贾宝玉"):
                client.post(
                    f"/api/web/runs/{run['run_id']}/ingest/character",
                    json={
                        "character": name,
                        "content_base64": base64.b64encode(
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode("utf-8")
                        ).decode("ascii"),
                    },
                )
            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "????", "message": "????????????"}],
            ):
                session_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions",
                    json={
                        "mode": "observe",
                        "participants": ["???", "???"],
                        "controlled_character": "",
                        "self_profile": {},
                    },
                )
            session = session_response.json()

            with patch.object(
                WebRunService,
                "_build_dialogue_llm_messages",
                side_effect=lambda payload, retry_on_empty=False: [{"role": "user", "content": "retry" if retry_on_empty else "first"}],
            ), patch("src.web.workflow.build_runtime_parts") as build_parts:
                fake_parts = Mock()
                fake_parts.llm.chat_completion.side_effect = [
                    {"content": "", "raw": {}},
                    {"content": '[{"speaker":"林黛玉","message":"你既开口了，我便回你一句。"}]', "raw": {}},
                ]
                build_parts.return_value = fake_parts
                reply_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}/reply",
                    json={"message": "你好"},
                )

            self.assertEqual(reply_response.status_code, 200)
            payload = reply_response.json()
            self.assertEqual(payload["status"], "ready")
            self.assertEqual(payload["transcript"][-1]["speaker"], "林黛玉")


if __name__ == "__main__":
    unittest.main()
