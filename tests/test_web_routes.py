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

import pytest

pytest.importorskip(
    "fastapi.testclient",
    reason="Web route tests require FastAPI's test client.",
)
pytest.importorskip("httpx", reason="Web route tests require httpx.")

from fastapi.testclient import TestClient
from src.web.app import create_app

class WebAppRouteTests(unittest.TestCase):
    def test_create_run_route_accepts_deferred_import_without_model(self):
        with tempfile.TemporaryDirectory() as tmp:
            client = TestClient(create_app(WebRunService(tmp)))

            response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng.txt",
                    "novel_content_base64": base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
                    "characters": ["林黛玉"],
                    "auto_run": False,
                    "defer_run": True,
                },
            )

            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["status"], "draft")

    def test_delete_run_route_removes_group(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
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
                characters=["林黛玉"],
                defer_run=True,
            )
            client = TestClient(create_app(service))

            response = client.delete(f"/api/web/runs/{run['run_id']}")

            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["status"], "deleted")
            self.assertFalse((Path(tmp) / "runs" / run["run_id"]).exists())

    def test_relation_details_patch_updates_relation_and_conflicts(self):
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
                characters=["林黛玉", "贾宝玉"],
            )
            relations_text = "\n".join(
                [
                    "- novel_id: hongloumeng",
                    "## 林黛玉_贾宝玉",
                    "- trust: 8",
                    "- affection: 8",
                    "- hostility: 1",
                    "- relationship_type: 牵连",
                    "- typical_interaction: 试探",
                ]
            )
            service.ingest_relation_result(
                payload["run_id"],
                content_base64=base64.b64encode(relations_text.encode("utf-8")).decode(
                    "ascii"
                ),
                filename="hongloumeng_relations.md",
            )
            client = TestClient(create_app(service))
            patched = client.patch(
                f"/api/web/runs/{payload['run_id']}/relations/{'林黛玉_贾宝玉'}",
                json={
                    "hostility": 7,
                    "relationship_type": "拉扯",
                    "conflict_point": "真心反噬",
                },
            )
            self.assertEqual(patched.status_code, 200)
            data = patched.json()
            self.assertEqual(data["items"][0]["relationship_type"], "拉扯")
            self.assertGreaterEqual(data["conflict_count"], 1)

    def test_persona_review_route_accepts_extended_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
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
                characters=["林黛玉"],
            )
            service.ingest_character_result(
                run["run_id"],
                character="林黛玉",
                content_base64=base64.b64encode(
                    "- name: 林黛玉\n- novel_id: hongloumeng\n- core_identity: 贾府外来才女\n".encode(
                        "utf-8"
                    )
                ).decode("ascii"),
            )
            client = TestClient(create_app(service))

            response = client.put(
                f"/api/web/runs/{run['run_id']}/personas/林黛玉",
                json={
                    "identity_anchor": "我最看重真心，也最不肯委屈自己",
                    "signature_phrases": "也罢；你又来哄我",
                    "key_bonds": "贾宝玉；紫鹃；贾母",
                    "anger_style": "先收住声气，再把冷意压进话里。",
                    "review_source": "character_overview_autofill",
                    "review_note": "web_fallback",
                },
            )

            self.assertEqual(response.status_code, 200)
            fields = response.json()["fields"]
            self.assertIn("真心", fields["identity_anchor"])
            self.assertIn("贾母", fields["key_bonds"])
            self.assertIn("冷意", fields["anger_style"])
            run_payload = service.get_run(run["run_id"])
            review_event = next(
                item
                for item in reversed(run_payload["events"])
                if item.get("stage") == "persona_review_saved"
                and item.get("character") == "林黛玉"
            )
            self.assertEqual(
                review_event["review_source"], "character_overview_autofill"
            )
            self.assertEqual(review_event["review_note"], "web_fallback")

    def test_persona_field_autofill_route_returns_generated_value(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            run = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉初入贾府。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉"],
            )
            service.ingest_character_result(
                run["run_id"],
                character="林黛玉",
                content_base64=base64.b64encode(
                    "- name: 林黛玉\n- novel_id: 红楼梦\n- core_identity: 贾府外来才女\n".encode(
                        "utf-8"
                    )
                ).decode("ascii"),
            )
            fake_parts = Mock()
            fake_parts.llm.chat_completion = Mock(
                return_value={
                    "content": '{"status":"filled","value":"对真心极敏感，也极重自尊。","reason":"证据足够。"}'
                }
            )
            client = TestClient(create_app(service))

            with patch(
                "src.web.workflow.build_runtime_parts", return_value=fake_parts
            ), patch(
                "src.web.service_facades.artifacts.collect_persona_web_references",
                return_value=[
                    {
                        "title": "林黛玉人物分析",
                        "snippet": "林黛玉敏感而自尊极重，极重真情。",
                        "source": "Bing",
                        "query": "林黛玉 红楼梦 人物分析",
                    }
                ],
            ):
                response = client.post(
                    f"/api/web/runs/{run['run_id']}/personas/林黛玉/suggest-field",
                    json={"field": "identity_anchor"},
                )

            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["status"], "filled")
            self.assertIn("真心", response.json()["value"])

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
                    "max_tokens": 1200,
                },
            )
            self.assertEqual(saved.status_code, 200)
            self.assertTrue(saved.json()["configured"])
            self.assertEqual(saved.json()["max_tokens"], 1200)

            resaved = client.put(
                "/api/web/settings/model",
                json={
                    "provider": "openai-compatible",
                    "model": "deepseek-chat",
                    "base_url": "https://example.com/v1",
                    "api_key": "",
                    "max_tokens": 900,
                },
            )
            self.assertEqual(resaved.status_code, 200)
            self.assertTrue(resaved.json()["configured"])
            self.assertEqual(resaved.json()["max_tokens"], 900)

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
                    "novel_content_base64": base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
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
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                                "utf-8"
                            )
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
            first = sessions_response.json()["items"][0]
            self.assertIn("last_entry_preview", first)
            self.assertTrue(str(first["last_entry_preview"]).strip())

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
                    "novel_content_base64": base64.b64encode(
                        "镜中两人相见。".encode("utf-8")
                    ).decode("ascii"),
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
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                                "utf-8"
                            )
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
                    "novel_content_base64": base64.b64encode(
                        "镜中两人相见。".encode("utf-8")
                    ).decode("ascii"),
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

    def test_redistill_route_accepts_new_source_segment(self):
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
                    "novel_name": "hongloumeng-1.txt",
                    "novel_content_base64": base64.b64encode(
                        "第一章里林黛玉出场。".encode("utf-8")
                    ).decode("ascii"),
                    "characters": ["林黛玉"],
                },
            )
            payload = create_response.json()

            with patch.object(service, "_start_background_run") as start_background_run:
                response = client.post(
                    f"/api/web/runs/{payload['run_id']}/redistill",
                    json={
                        "characters": ["林黛玉", "薛宝钗"],
                        "novel_name": "hongloumeng-2.txt",
                        "novel_content_base64": base64.b64encode(
                            "第二章里宝钗登场。".encode("utf-8")
                        ).decode("ascii"),
                        "max_sentences": 120,
                        "max_chars": 50000,
                    },
                )

            self.assertEqual(response.status_code, 200)
            data = response.json()
            self.assertTrue(data["redistill"]["used_new_source"])
            self.assertIn("updates", data["novel_path"])
            self.assertEqual(data["novel_sources"][-1]["kind"], "incremental_update")
            self.assertGreater(data["novel_sources"][-1]["byte_size"], 0)
            self.assertGreater(data["novel_sources"][-1]["char_count"], 0)
            start_background_run.assert_called_once()

    def test_redistill_recommend_route_reads_latest_incremental_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            app = create_app(service)
            client = TestClient(app)
            create_response = client.post(
                "/api/web/runs",
                json={
                    "novel_name": "hongloumeng-1.txt",
                    "novel_content_base64": base64.b64encode(
                        "第一章里林黛玉出场。".encode("utf-8")
                    ).decode("ascii"),
                    "characters": ["林黛玉"],
                },
            )
            payload = create_response.json()

            with patch.object(service, "_start_background_run"):
                restarted = service.restart_run_distill(
                    payload["run_id"],
                    characters=["林黛玉", "薛宝钗"],
                    novel_name="hongloumeng-2.txt",
                    novel_content_base64=base64.b64encode(
                        (
                            "第二章里薛宝钗入府。"
                            "薛宝钗笑道：“早听过妹妹名声。”"
                            "林黛玉看了她一眼，没有立刻作声。"
                            "薛宝钗又缓缓说道：“若你不嫌，我愿陪你说会儿话。”"
                        ).encode("utf-8")
                    ).decode("ascii"),
                )

            response = client.post(
                f"/api/web/runs/{restarted['run_id']}/redistill/recommend",
                json={"character": "薛宝钗", "max_segments": 2},
            )

            self.assertEqual(response.status_code, 200)
            data = response.json()
            self.assertEqual(data["character"], "薛宝钗")
            self.assertTrue(str(data["source_name"]).endswith("hongloumeng-2.txt"))
            self.assertEqual(data["source_kind"], "incremental_update")
            self.assertTrue(data["segments"])
            self.assertLessEqual(len(data["segments"]), 2)

    def test_stop_run_route_marks_manifest(self):
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
            with patch.object(service, "_start_background_run"):
                create_response = client.post(
                    "/api/web/runs",
                    json={
                        "novel_name": "hongloumeng.txt",
                        "novel_content_base64": base64.b64encode(
                            "镜中两人相见。".encode("utf-8")
                        ).decode("ascii"),
                        "characters": ["林黛玉"],
                        "auto_run": True,
                    },
                )
            payload = create_response.json()

            stop_response = client.post(f"/api/web/runs/{payload['run_id']}/stop")

            self.assertEqual(stop_response.status_code, 200)
            data = stop_response.json()
            self.assertTrue(data["control"]["stop_requested"])
            self.assertEqual(data["summary"]["status_text"], "stop_requested")

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
                    "novel_content_base64": base64.b64encode(
                        "林黛玉见贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
                    "characters": ["林黛玉"],
                },
            )
            payload = create_response.json()
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            profile_dir = (
                run_dir / "artifacts" / "characters" / "hongloumeng" / "林黛玉"
            )
            profile_dir.mkdir(parents=True, exist_ok=True)
            (profile_dir / "PROFILE.generated.md").write_text(
                "- name: 林黛玉\n- core_identity: 才女\n", encoding="utf-8"
            )

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
                    "novel_content_base64": base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
                    "characters": ["林黛玉", "贾宝玉"],
                },
            )
            run = create_response.json()

            profile_text = (
                "- name: 林黛玉\n- novel_id: hongloumeng\n- core_identity: 才女\n"
            )
            character_response = client.post(
                f"/api/web/runs/{run['run_id']}/ingest/character",
                json={
                    "character": "林黛玉",
                    "content_base64": base64.b64encode(
                        profile_text.encode("utf-8")
                    ).decode("ascii"),
                    "filename": "PROFILE.generated.md",
                },
            )
            self.assertEqual(character_response.status_code, 200)
            self.assertEqual(
                character_response.json()["summary"]["characters_completed"], 1
            )
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
                    "content_base64": base64.b64encode(
                        relations_text.encode("utf-8")
                    ).decode("ascii"),
                    "filename": "hongloumeng_relations.md",
                },
            )
            self.assertEqual(relation_response.status_code, 200)
            self.assertEqual(
                relation_response.json()["summary"]["graph_status"], "complete"
            )

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
                    "novel_content_base64": base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
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
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                                "utf-8"
                            )
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
            self.assertEqual(
                prepare_response.json()["status"], "waiting_for_host_reply"
            )
            self.assertEqual(
                prepare_response.json()["pending_turn_summary"]["speaker"], "User"
            )

            ingest_response = client.post(
                f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}/ingest",
                json={"responses": [{"speaker": "林黛玉", "message": "今日风倒清。"}]},
            )
            self.assertEqual(ingest_response.status_code, 200)
            self.assertEqual(ingest_response.json()["status"], "ready")
            self.assertEqual(
                ingest_response.json()["transcript"][0]["role"], "director"
            )

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
                    "novel_content_base64": base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
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
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                                "utf-8"
                            )
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
                return_value={
                    "responses": [
                        {"speaker": "林黛玉", "message": "你既来了，先坐下说话。"}
                    ],
                    "generation_cache": {
                        "provider": "openai-compatible",
                        "model": "deepseek-chat",
                        "observed": True,
                        "input_tokens": 200,
                        "cache_read_tokens": 120,
                        "cache_write_tokens": 0,
                        "cache_miss_tokens": 80,
                        "attempt_count": 1,
                    },
                },
            ):
                reply_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}/reply",
                    json={"message": "你们先聊几句。", "message_kind": "narration"},
                )

            self.assertEqual(reply_response.status_code, 200)
            payload = reply_response.json()
            self.assertEqual(payload["status"], "ready")
            self.assertEqual(payload["transcript"][-1]["speaker"], "林黛玉")
            self.assertEqual(
                payload["generation_cache_stats"]["latest"]["hit_rate"], 0.6
            )
            self.assertEqual(
                payload["generation_cache_stats"]["session"]["cache_read_tokens"],
                120,
            )

    def test_dialogue_reply_route_can_suppress_transcript_message(self):
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
                    "novel_content_base64": base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
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
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                                "utf-8"
                            )
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
                        "participants": ["林黛玉", "贾宝玉"],
                        "controlled_character": "",
                        "self_profile": {},
                    },
                )
            session = session_response.json()

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "贾宝玉", "message": "那我先接一句。"}],
            ):
                reply_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}/reply",
                    json={
                        "message": "继续聊。",
                        "message_kind": "narration",
                        "suppress_transcript_message": True,
                    },
                )

            self.assertEqual(reply_response.status_code, 200)
            payload = reply_response.json()
            transcript = list(payload.get("transcript", []) or [])
            self.assertEqual(payload.get("status"), "ready")
            self.assertEqual(transcript[-1]["speaker"], "贾宝玉")
            self.assertFalse(
                any(
                    str(item.get("message", "")).strip() == "继续聊。"
                    for item in transcript
                )
            )

    def test_dialogue_suggest_route_returns_suggestion_without_mutating_session(self):
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
                    "novel_content_base64": base64.b64encode(
                        "林黛玉见了贾宝玉。".encode("utf-8")
                    ).decode("ascii"),
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
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                                "utf-8"
                            )
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
            initial_history = list(session["history"])

            with patch.object(
                WebRunService,
                "_generate_dialogue_suggestion",
                return_value="要不先让他们把刚才那句接下去？",
            ):
                suggest_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}/suggest",
                    json={"seed_text": "要不先让"},
                )

            self.assertEqual(suggest_response.status_code, 200)
            self.assertEqual(
                suggest_response.json()["suggestion"], "要不先让他们把刚才那句接下去？"
            )

            with patch.object(
                WebRunService,
                "_generate_dialogue_associations",
                return_value=[
                    {"label": "追问旧事", "direction": "顺着刚才的话追问旧事"},
                    {"label": "缓和气氛", "direction": "先回应对方的关心"},
                    {"label": "转向行动", "direction": "提议立刻去查线索"},
                ],
            ):
                association_response = client.post(
                    f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}/associations",
                    json={"option_count": 3},
                )

            self.assertEqual(association_response.status_code, 200)
            self.assertTrue(association_response.json()["show"])
            self.assertEqual(len(association_response.json()["options"]), 3)

            refreshed_session = client.get(
                f"/api/web/runs/{run['run_id']}/dialogue/sessions/{session['session_id']}"
            ).json()
            self.assertEqual(refreshed_session["history"], initial_history)
            self.assertEqual(refreshed_session["pending_turn_summary"], {})
            self.assertEqual(refreshed_session["status"], "ready")

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
                    "novel_content_base64": base64.b64encode(
                        "镜中两人相见。".encode("utf-8")
                    ).decode("ascii"),
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
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                                "utf-8"
                            )
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
                    "novel_content_base64": base64.b64encode(
                        "镜中两人相见。".encode("utf-8")
                    ).decode("ascii"),
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
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                                "utf-8"
                            )
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
                side_effect=lambda payload, retry_on_empty=False: [
                    {"role": "user", "content": "retry" if retry_on_empty else "first"}
                ],
            ), patch("src.web.workflow.build_runtime_parts") as build_parts:
                fake_parts = Mock()
                fake_parts.llm.chat_completion.side_effect = [
                    {"content": "", "raw": {}},
                    {
                        "content": '[{"speaker":"林黛玉","message":"你既开口了，我便回你一句。"}]',
                        "raw": {},
                    },
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
