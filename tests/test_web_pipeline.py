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

class PipelineServiceTests(unittest.TestCase):
    def test_process_relation_graph_preserves_latest_relation_repairs_during_prepare(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run_id = "run-relation-prepare"
            run_dir = Path(tmp) / "runs" / run_id
            run_dir.mkdir(parents=True, exist_ok=True)
            manifest_path = run_dir / "run_manifest.json"
            payload_dir = run_dir / "payloads"
            payload_dir.mkdir(parents=True, exist_ok=True)
            novel_path = run_dir / "input" / "novel.txt"
            novel_path.parent.mkdir(parents=True, exist_ok=True)
            novel_path.write_text(
                "Alpha meets Beta. Alpha distrusts Gamma.", encoding="utf-8"
            )
            manifest_path.write_text(
                json.dumps(
                    {
                        "run_id": run_id,
                        "novel_id": "novel",
                        "quality": {
                            "excerpt_focus": {
                                "matched_characters": [],
                                "missing_characters": [],
                                "strategy": "",
                            },
                            "stage_presence": [],
                            "character_focus": {},
                            "profile_repairs": {"count": 0, "characters": []},
                            "relation_repairs": {
                                "count": 2,
                                "pairs": ["Alpha_Beta", "Alpha_Gamma"],
                            },
                        },
                    },
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )

            def write_json(path: Path, payload: dict[str, object]) -> None:
                path.write_text(
                    json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )

            def update_manifest(path: Path, updater):
                current = json.loads(path.read_text(encoding="utf-8"))
                updated = updater(dict(current))
                next_payload = current if updated is None else updated
                write_json(path, next_payload)
                return next_payload

            with self.assertRaisesRegex(RuntimeError, "stop after prepare"):
                process_relation_graph(
                    novel_path=novel_path,
                    graph_cast=["Alpha", "Beta"],
                    max_sentences=120,
                    max_chars=50000,
                    manifest_path=manifest_path,
                    payload_dir=payload_dir,
                    novel_id="novel",
                    parts=Mock(),
                    config=Mock(),
                    on_relation=lambda stage, payload: None,
                    assert_run_not_stopped=lambda *args, **kwargs: None,
                    write_json=write_json,
                    update_manifest=update_manifest,
                    build_quality_snapshot=service._build_quality_snapshot,
                    update_manifest_chunk_progress=update_manifest_chunk_progress,
                    generate_relation_markdown=lambda **kwargs: (_ for _ in ()).throw(
                        RuntimeError("stop after prepare")
                    ),
                    maybe_repair_generated_relations=lambda **kwargs: None,
                    load_relations_source=lambda path: {},
                    export_relations_source=lambda **kwargs: {},
                    utc_now=lambda: "2026-05-12T00:00:00Z",
                    relation_repairs_getter=lambda current: (
                        current.get("quality", {}) or {}
                    ).get("relation_repairs", {}),
                    quality_matched=set(),
                    quality_missing=set(),
                    quality_focus={},
                    profile_repair_characters=[],
                )

            persisted = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(persisted["quality"]["relation_repairs"]["count"], 2)
            self.assertEqual(
                persisted["quality"]["relation_repairs"]["pairs"],
                ["Alpha_Beta", "Alpha_Gamma"],
            )

    def test_ingest_character_result_uses_latest_manifest_snapshot_atomic(self):
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
            run_id = payload["run_id"]
            manifest_path = Path(tmp) / "runs" / run_id / "run_manifest.json"
            latest = json.loads(manifest_path.read_text(encoding="utf-8"))
            latest["ingest_external_marker"] = "keep-character"
            manifest_path.write_text(
                json.dumps(latest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

            profile_text = "\n".join(
                [
                    "- name: 林黛玉",
                    "- novel_id: hongloumeng",
                    "- core_identity: 贾府外来才女",
                    "- speech_style: 清冷带刺",
                ]
            )
            refreshed = service.ingest_character_result(
                run_id,
                character="林黛玉",
                content_base64=base64.b64encode(profile_text.encode("utf-8")).decode(
                    "ascii"
                ),
            )

            persisted = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(refreshed.get("ingest_external_marker"), "keep-character")
            self.assertEqual(persisted.get("ingest_external_marker"), "keep-character")

    def test_ingest_relation_result_uses_latest_manifest_snapshot_atomic(self):
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
            run_id = payload["run_id"]
            manifest_path = Path(tmp) / "runs" / run_id / "run_manifest.json"
            latest = json.loads(manifest_path.read_text(encoding="utf-8"))
            latest["ingest_external_marker"] = "keep-relation"
            manifest_path.write_text(
                json.dumps(latest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
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
                run_id,
                content_base64=base64.b64encode(relations_text.encode("utf-8")).decode(
                    "ascii"
                ),
                filename="hongloumeng_relations.md",
            )

            persisted = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(refreshed.get("ingest_external_marker"), "keep-relation")
            self.assertEqual(persisted.get("ingest_external_marker"), "keep-relation")

    def test_refresh_run_uses_latest_manifest_snapshot(self):
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
            run_id = payload["run_id"]
            manifest_path = Path(tmp) / "runs" / run_id / "run_manifest.json"

            latest = json.loads(manifest_path.read_text(encoding="utf-8"))
            latest["status"] = "running"
            latest["summary"] = {"status_text": "running"}
            latest["updated_at"] = "2026-05-12T08:00:00Z"
            manifest_path.write_text(
                json.dumps(latest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

            stale_payload = dict(latest)
            stale_payload["updated_at"] = "2000-01-01T00:00:00Z"
            stale_payload["summary"] = {"status_text": "stale"}

            with patch.object(service, "_load_manifest", return_value=stale_payload):
                refreshed = service.refresh_run(run_id)

            persisted = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertNotEqual(persisted["summary"]["status_text"], "stale")
            self.assertNotEqual(persisted["updated_at"], "2000-01-01T00:00:00Z")
            self.assertEqual(persisted["updated_at"], refreshed["updated_at"])

    def test_automatic_pipeline_finalize_uses_latest_manifest_snapshot(self):
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
                novel_content_base64=base64.b64encode(
                    "Alpha meets Beta.".encode("utf-8")
                ).decode("ascii"),
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
                    path = (
                        self.base_dir
                        / "artifacts"
                        / "relations"
                        / f"{novel_id}_relations.md"
                    )
                    path.parent.mkdir(parents=True, exist_ok=True)
                    return path

            fake_parts = Mock()
            fake_parts.path_provider = _FakePathProvider(run_dir)
            fake_parts.llm.chat_completion = Mock(
                side_effect=[
                    {
                        "content": "# PROFILE\n- name: Alpha\n- novel_id: novel\n- core_identity: 核心人物\n- soul_goal: 守住答案\n- speech_style: 先压低语气再落结论\n- cadence: 慢半拍后落点\n- signature_phrases: 先看清；别急着站位\n- typical_lines: 先看清再说；别急着站位\n- sentence_openers: 先；别急\n- sentence_endings: 再说；也罢\n- worldview: 先把局势看清，再决定站位。\n- belief_anchor: 关键时刻不能自乱阵脚。\n- moral_bottom_line: 不把同伴当代价随手抛掉。\n- restraint_threshold: 平时克制，底线被逼穿时才会失控。\n- stress_response: 压力越大越会先收声，再集中判断。\n"
                    },
                    {
                        "content": "# RELATION_GRAPH\n\n## Alpha_Beta\n- trust: 7\n- affection: 3\n- power_gap: 0\n- conflict_point: 立场试探\n- typical_interaction: 观察与回应\n- hidden_attitude: \n- relation_change: 固化\n- appellation_to_target: Beta\n- confidence: 7\n"
                    },
                ]
            )

            real_update_manifest = service._update_manifest
            injected_before_finalize = {"value": False}

            def wrapped_update_manifest(
                path: Path, updater, create_if_missing: bool = False
            ):
                if Path(path) == manifest_path and hasattr(updater, "__code__"):
                    names = set(getattr(updater.__code__, "co_names", ()))
                    if {
                        "_apply_finalize_success_update",
                        "_apply_finalize_success_without_graph_update",
                    } & names:
                        latest = json.loads(manifest_path.read_text(encoding="utf-8"))
                        latest["external_marker"] = "keep-me"
                        manifest_path.write_text(
                            json.dumps(latest, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8",
                        )
                        injected_before_finalize["value"] = True
                return real_update_manifest(
                    path, updater, create_if_missing=create_if_missing
                )

            with patch("src.web.workflow.build_runtime_parts", return_value=fake_parts):
                with patch.object(
                    service, "_maybe_repair_generated_profile", return_value=None
                ):
                    with patch.object(
                        service, "_maybe_repair_generated_relations", return_value=None
                    ):
                        with patch.object(
                            service,
                            "_update_manifest",
                            side_effect=wrapped_update_manifest,
                        ):
                            result = service._run_automatic_pipeline(
                                manifest_path=manifest_path,
                                novel_path=novel_path,
                                locked_characters=["Alpha"],
                                max_sentences=120,
                                max_chars=50000,
                            )

            self.assertTrue(result["success"])
            persisted = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertTrue(injected_before_finalize["value"])
            self.assertEqual(persisted.get("external_marker"), "keep-me")

    def test_automatic_pipeline_steps_use_latest_manifest_snapshot(self):
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
                novel_content_base64=base64.b64encode(
                    "Alpha meets Beta.".encode("utf-8")
                ).decode("ascii"),
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
                    path = (
                        self.base_dir
                        / "artifacts"
                        / "relations"
                        / f"{novel_id}_relations.md"
                    )
                    path.parent.mkdir(parents=True, exist_ok=True)
                    return path

            fake_parts = Mock()
            fake_parts.path_provider = _FakePathProvider(run_dir)
            fake_parts.llm.chat_completion = Mock(
                side_effect=[
                    {
                        "content": "# PROFILE\n- name: Alpha\n- novel_id: novel\n- core_identity: 核心人物\n- soul_goal: 守住答案\n- speech_style: 先压低语气再落结论\n- cadence: 慢半拍后落点\n- signature_phrases: 先看清；别急着站位\n- typical_lines: 先看清再说；别急着站位\n- sentence_openers: 先；别急\n- sentence_endings: 再说；也罢\n- worldview: 先把局势看清，再决定站位。\n- belief_anchor: 关键时刻不能自乱阵脚。\n- moral_bottom_line: 不把同伴当代价随手抛掉。\n- restraint_threshold: 平时克制，底线被逼穿时才会失控。\n- stress_response: 压力越大越会先收声，再集中判断。\n"
                    },
                    {
                        "content": "# RELATION_GRAPH\n\n## Alpha_Beta\n- trust: 7\n- affection: 3\n- power_gap: 0\n- conflict_point: 立场试探\n- typical_interaction: 观察与回应\n- hidden_attitude: \n- relation_change: 固化\n- appellation_to_target: Beta\n- confidence: 7\n"
                    },
                ]
            )

            real_update_manifest = service._update_manifest
            injected_before_step_update = {"value": False}
            update_call_count = {"value": 0}

            def wrapped_update_manifest(
                path: Path, updater, create_if_missing: bool = False
            ):
                if Path(path) == manifest_path:
                    update_call_count["value"] += 1
                    if (
                        update_call_count["value"] == 4
                        and not injected_before_step_update["value"]
                    ):
                        latest = json.loads(manifest_path.read_text(encoding="utf-8"))
                        latest["step_external_marker"] = "keep-step"
                        manifest_path.write_text(
                            json.dumps(latest, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8",
                        )
                        injected_before_step_update["value"] = True
                return real_update_manifest(
                    path, updater, create_if_missing=create_if_missing
                )

            with patch("src.web.workflow.build_runtime_parts", return_value=fake_parts):
                with patch.object(
                    service, "_maybe_repair_generated_profile", return_value=None
                ):
                    with patch.object(
                        service, "_maybe_repair_generated_relations", return_value=None
                    ):
                        with patch.object(
                            service,
                            "_update_manifest",
                            side_effect=wrapped_update_manifest,
                        ):
                            result = service._run_automatic_pipeline(
                                manifest_path=manifest_path,
                                novel_path=novel_path,
                                locked_characters=["Alpha"],
                                max_sentences=120,
                                max_chars=50000,
                            )

            self.assertTrue(result["success"])
            persisted = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertTrue(injected_before_step_update["value"])
            self.assertGreaterEqual(update_call_count["value"], 4)
            self.assertEqual(persisted.get("step_external_marker"), "keep-step")

    def test_automatic_pipeline_uses_union_cast_for_graph_on_redistill(self):
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
                    "林黛玉见了贾宝玉，王熙凤后至。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            manifest_path = run_dir / "run_manifest.json"
            novel_path = run_dir / "input" / "hongloumeng.txt"

            class _FakePathProvider:
                def __init__(self, base_dir: Path) -> None:
                    self.base_dir = base_dir

                def characters_root(self, novel_id: str) -> Path:
                    path = self.base_dir / "artifacts" / "characters" / novel_id
                    path.mkdir(parents=True, exist_ok=True)
                    return path

                def relations_file(self, novel_id: str) -> Path:
                    path = (
                        self.base_dir
                        / "artifacts"
                        / "relations"
                        / f"{novel_id}_relations.md"
                    )
                    path.parent.mkdir(parents=True, exist_ok=True)
                    return path

            fake_parts = Mock()
            fake_parts.path_provider = _FakePathProvider(run_dir)

            def fake_chat_completion(messages, **kwargs):
                prompt = messages[1]["content"]
                if "COMPLETION_TASK" in prompt:
                    return {
                        "content": "- faction_position: 贾府内务中枢\n- story_role: 场面控制者\n- stance_stability: 高\n- identity_anchor: 我先把场面收住\n- world_rule_fit: 高\n- background_imprint: 自幼熟悉权势人情\n- life_experience: 管家理事；周旋内外\n- trauma_scar: 证据不足\n- taboo_topics: 失势；失体面\n- forbidden_behaviors: 白白让人夺权\n- world_belong: 贾府内宅\n- rule_view: 规则是拿来稳场面的\n- plot_restriction: 家族体面与利益绑定\n- soul_goal: 守住手中的秩序与位置\n- hidden_desire: 证据不足\n- core_traits: 利落；强势；机变\n- temperament_type: 明快泼辣\n- values: 责任=8；智慧=8；忠诚=7\n- inner_conflict: 证据不足\n- self_cognition: 知道自己必须撑场\n- private_self: 证据不足\n- thinking_style: 先算局势再动手\n- cognitive_limits: 证据不足\n- decision_rules: 先稳场；后分利害\n- reward_logic: 有用者可拉拢\n- action_style: 先控场后施压\n- fear_triggers: 失势；失控\n- emotion_model: 面上稳，心里算\n- social_mode: 外热内硬\n- carry_style: 分层待人\n- others_impression: 精明强干\n- key_bonds: 贾府；家族秩序\n- appearance_feature: 证据不足\n- habit_action: 证据不足\n- preference_like: 场面稳妥\n- dislike_hate: 失序\n- interest_claim: 掌控局面\n- resource_dependence: 家族权势\n- trade_principle: 不做亏本交换\n- disguise_switch: 证据不足\n- ooc_redline: 不会轻易自乱阵脚\n- strengths: 控场；算账\n- weaknesses: 证据不足\n- arc_type: 证据不足\n- arc_blocker: 证据不足\n- arc_summary: 证据不足\n"
                    }
                if "RELATION_GRAPH" in prompt:
                    return {
                        "content": "# RELATION_GRAPH\n\n## 林黛玉_贾宝玉\n- trust: 8\n- affection: 9\n- power_gap: 0\n- conflict_point: 误会\n- typical_interaction: 试探与安抚\n- hidden_attitude: \n- relation_change: 升温\n- appellation_to_target: 宝玉\n- confidence: 8\n"
                    }
                return {
                    "content": "# PROFILE\n- name: 王熙凤\n- novel_id: hongloumeng\n- core_identity: 管家者\n- speech_style: 利落带锋芒\n- cadence: 快里带稳\n- signature_phrases: 我来收这个场；你且看着\n- typical_lines: 我来收这个场；你且看着\n- sentence_openers: 我来；你且\n- sentence_endings: 便是了；就成\n- worldview: 人情与权势都要算清。\n- belief_anchor: 场面和秩序不能乱。\n- moral_bottom_line: 不轻易让贾府失序。\n- restraint_threshold: 平时压得住，利益与脸面同时受损才会翻脸。\n- stress_response: 压力越大越会先稳住场面，再亮出锋芒。\n"
                }

            fake_parts.llm.chat_completion = Mock(side_effect=fake_chat_completion)
            fake_parts.extractor.extract = Mock()

            with patch("src.web.workflow.build_runtime_parts", return_value=fake_parts):
                service._run_automatic_pipeline(
                    manifest_path=manifest_path,
                    novel_path=novel_path,
                    locked_characters=["王熙凤"],
                    relation_characters=["林黛玉", "贾宝玉", "王熙凤"],
                    max_sentences=120,
                    max_chars=50000,
                )

            relation_messages = fake_parts.llm.chat_completion.call_args_list[-1].args[
                0
            ]
            self.assertIn("王熙凤", relation_messages[1]["content"])
            self.assertIn("贾宝玉", relation_messages[1]["content"])
            self.assertIn("林黛玉", relation_messages[1]["content"])

    def test_automatic_pipeline_redistills_selected_existing_characters_on_same_source_restart(
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
                    "林黛玉先出场，后来薛宝钗也来了。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "薛宝钗"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            manifest_path = run_dir / "run_manifest.json"
            novel_path = run_dir / "input" / "hongloumeng.txt"
            persona_dir = (
                run_dir / "artifacts" / "characters" / "hongloumeng" / "林黛玉"
            )
            persona_dir.mkdir(parents=True, exist_ok=True)
            (persona_dir / "PROFILE.generated.md").write_text(
                "# PROFILE\n- name: 林黛玉\n- novel_id: hongloumeng\n- core_identity: 才女\n",
                encoding="utf-8",
            )
            service.refresh_run(payload["run_id"])
            with patch.object(service, "_start_background_run"):
                restarted = service.restart_run_distill(
                    payload["run_id"],
                    characters=["林黛玉", "薛宝钗"],
                    max_sentences=120,
                    max_chars=50000,
                )

            class _FakePathProvider:
                def __init__(self, base_dir: Path) -> None:
                    self.base_dir = base_dir

                def characters_root(self, novel_id: str) -> Path:
                    path = self.base_dir / "artifacts" / "characters" / novel_id
                    path.mkdir(parents=True, exist_ok=True)
                    return path

                def relations_file(self, novel_id: str) -> Path:
                    path = (
                        self.base_dir
                        / "artifacts"
                        / "relations"
                        / f"{novel_id}_relations.md"
                    )
                    path.parent.mkdir(parents=True, exist_ok=True)
                    return path

            fake_parts = Mock()
            fake_parts.path_provider = _FakePathProvider(run_dir)

            def fake_chat_completion(messages, **kwargs):
                prompt = messages[1]["content"]
                if "RELATION_GRAPH" in prompt:
                    return {
                        "content": "# RELATION_GRAPH\n\n## 林黛玉_薛宝钗\n- trust: 7\n- affection: 6\n- power_gap: 0\n- conflict_point: 心事不明说\n- typical_interaction: 试探与照看\n- hidden_attitude: \n- relation_change: 固化\n- appellation_to_target: 宝钗\n- confidence: 7\n"
                    }
                if "- name: 林黛玉" in prompt:
                    return {
                        "content": "# PROFILE\n- name: 林黛玉\n- novel_id: hongloumeng\n- core_identity: 敏感清醒之人\n- soul_goal: 守住自尊与真心\n- speech_style: 清冷里带锋芒\n- cadence: 轻快后忽然收紧\n- signature_phrases: 我自有我的想法；也不必如此\n- typical_lines: 我自有我的想法；也不必如此\n- sentence_openers: 我；你们\n- sentence_endings: 罢了；也就如此\n- worldview: 真心比热闹更要紧。\n- belief_anchor: 情意不能拿来敷衍。\n- moral_bottom_line: 不肯拿真心去换体面。\n- restraint_threshold: 伤到心时会立刻冷下来。\n- stress_response: 越难过越先把话收窄。\n"
                    }
                return {
                    "content": "# PROFILE\n- name: 薛宝钗\n- novel_id: hongloumeng\n- core_identity: 端稳持重之人\n- soul_goal: 把局面稳住\n- speech_style: 温稳克制\n- cadence: 平整收束\n- signature_phrases: 先缓一缓；不妨再看\n- typical_lines: 先缓一缓；不妨再看\n- sentence_openers: 先；不妨\n- sentence_endings: 便好；也罢\n- worldview: 局势先稳，再谈情理。\n- belief_anchor: 分寸不能乱。\n- moral_bottom_line: 不把人逼到失面。\n- restraint_threshold: 平时极稳，被误伤真心时才会显出锋芒。\n- stress_response: 压力越大越会先稳语气，再调次序。\n"
                }

            fake_parts.llm.chat_completion = Mock(side_effect=fake_chat_completion)

            with patch("src.web.workflow.build_runtime_parts", return_value=fake_parts):
                result = service._run_automatic_pipeline(
                    manifest_path=manifest_path,
                    novel_path=novel_path,
                    locked_characters=restarted["locked_characters"],
                    relation_characters=restarted["redistill"]["relation_characters"],
                    max_sentences=120,
                    max_chars=50000,
                )

            self.assertTrue(result["success"])
            self.assertTrue((run_dir / "payloads" / "distill_林黛玉.json").exists())
            self.assertTrue((run_dir / "payloads" / "distill_薛宝钗.json").exists())
            first_payload = json.loads(
                (run_dir / "payloads" / "distill_林黛玉.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(first_payload["request"]["update_mode"], "incremental")
            self.assertIn("林黛玉", first_payload["request"]["existing_profiles"])
            self.assertEqual(result["summary"]["characters_completed"], 2)
            self.assertCountEqual(
                [item["name"] for item in result["artifact_index"]["characters"]],
                ["林黛玉", "薛宝钗"],
            )
            self.assertTrue(result["redistill"]["recent_changes"])
            first_change = result["redistill"]["recent_changes"][0]
            self.assertEqual(first_change["character"], "林黛玉")
            self.assertGreaterEqual(first_change["changed_count"], 1)
            self.assertIn("core_identity", first_change["changed_fields"])
            self.assertTrue(
                any(
                    item.get("stage") == "redistill_character_updated"
                    and item.get("character") == "林黛玉"
                    for item in result["events"]
                )
            )

    def test_automatic_pipeline_relation_graph_failure_does_not_fail_chat_ready_state(
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
                novel_name="novel.txt",
                novel_content_base64=base64.b64encode(
                    "Alpha meets Beta.".encode("utf-8")
                ).decode("ascii"),
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
                    path = (
                        self.base_dir
                        / "artifacts"
                        / "relations"
                        / f"{novel_id}_relations.md"
                    )
                    path.parent.mkdir(parents=True, exist_ok=True)
                    return path

            fake_parts = Mock()
            fake_parts.path_provider = _FakePathProvider(run_dir)

            def fake_chat_completion(messages, **kwargs):
                prompt = messages[1]["content"]
                if "RELATION_GRAPH" in prompt:
                    return {
                        "content": "# RELATION_GRAPH\n\n这里不是可解析的关系图正文。"
                    }
                return {
                    "content": "# PROFILE\n- name: Alpha\n- novel_id: novel\n- core_identity: 核心人物\n- soul_goal: 守住答案\n- speech_style: 先压低语气再落结论\n- cadence: 慢半拍后落点\n- signature_phrases: 先看清；别急着站位\n- typical_lines: 先看清再说；别急着站位\n- sentence_openers: 先；别急\n- sentence_endings: 再说；也罢\n- worldview: 先把局势看清，再决定站位。\n- belief_anchor: 关键时刻不能自乱阵脚。\n- moral_bottom_line: 不把同伴当代价随手抛掉。\n- restraint_threshold: 平时克制，底线被逼穿时才会失控。\n- stress_response: 压力越大越会先收声，再集中判断。\n"
                }

            fake_parts.llm.chat_completion = Mock(side_effect=fake_chat_completion)

            with patch("src.web.workflow.build_runtime_parts", return_value=fake_parts):
                result = service._run_automatic_pipeline(
                    manifest_path=manifest_path,
                    novel_path=novel_path,
                    locked_characters=["Alpha"],
                    max_sentences=120,
                    max_chars=50000,
                )

            self.assertTrue(result["success"])
            self.assertEqual(result["status"], "ready")
            self.assertEqual(result["summary"]["status_text"], "workflow_complete")
            self.assertEqual(result["summary"]["graph_status"], "failed")
            self.assertEqual(result["progress"]["graph_status"], "failed")
            self.assertIn("关系图谱生成失败", result["progress"]["message"])
            self.assertFalse(result["capabilities"]["export_graph"]["success"])
            self.assertTrue(
                (
                    run_dir
                    / "artifacts"
                    / "characters"
                    / "novel"
                    / "Alpha"
                    / "PROFILE.generated.md"
                ).exists()
            )

    def test_automatic_pipeline_uses_llm_generated_profiles_and_materializes_persona_bundle(
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
                novel_name="novel.txt",
                novel_content_base64=base64.b64encode(
                    "Alpha meets Beta.".encode("utf-8")
                ).decode("ascii"),
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
                    path = (
                        self.base_dir
                        / "artifacts"
                        / "relations"
                        / f"{novel_id}_relations.md"
                    )
                    path.parent.mkdir(parents=True, exist_ok=True)
                    return path

            fake_parts = Mock()
            fake_parts.path_provider = _FakePathProvider(run_dir)

            def fake_chat_completion(messages, **kwargs):
                prompt = messages[1]["content"]
                if "COMPLETION_TASK" in prompt:
                    return {
                        "content": "- faction_position: 自由行动者\n- story_role: 事件推进者\n- stance_stability: 高\n- identity_anchor: 先看清，再站位\n- world_rule_fit: 中低\n- background_imprint: 证据不足\n- life_experience: 与局势周旋\n- trauma_scar: 证据不足\n- taboo_topics: 证据不足\n- forbidden_behaviors: 不拿同伴垫后\n- world_belong: 灰区地带\n- rule_view: 规则先看是否值得守\n- plot_restriction: 证据不足\n- hidden_desire: 想把答案守住\n- core_traits: 冷静；谨慎\n- temperament_type: 收着锋芒\n- values: 智慧=8；责任=7；忠诚=7\n- inner_conflict: 证据不足\n- self_cognition: 知道自己得稳住判断\n- private_self: 证据不足\n- thinking_style: 先分析再表态\n- cognitive_limits: 证据不足\n- decision_rules: 先看清再站位；不为噪声改判断\n- reward_logic: 值得的人就护住\n- action_style: 先压住，再落手\n- fear_triggers: 误判局势\n- emotion_model: 外冷内稳\n- anger_style: 证据不足\n- joy_style: 证据不足\n- grievance_style: 证据不足\n- social_mode: 先观察，再靠近\n- carry_style: 证据不足\n- others_impression: 稳得住\n- key_bonds: 同伴\n- appearance_feature: 证据不足\n- habit_action: 证据不足\n- preference_like: 证据不足\n- dislike_hate: 噪声判断\n- interest_claim: 护住答案\n- resource_dependence: 判断空间\n- trade_principle: 不轻易交换底线\n- disguise_switch: 证据不足\n- ooc_redline: 不会把同伴当代价\n- strengths: 冷静；判断稳\n- weaknesses: 过度防备\n- arc_type: 证据不足\n- arc_blocker: 证据不足\n- arc_summary: 证据不足\n"
                    }
                if "RELATION_GRAPH" in prompt:
                    return {
                        "content": "# RELATION_GRAPH\n\n## Alpha_Beta\n- trust: 7\n- affection: 3\n- power_gap: 0\n- conflict_point: 立场试探\n- typical_interaction: 观察与回应\n- hidden_attitude: \n- relation_change: 固化\n- appellation_to_target: Beta\n- confidence: 7\n"
                    }
                return {
                    "content": "# PROFILE\n- name: Alpha\n- novel_id: novel\n- core_identity: 核心人物\n- soul_goal: 守住答案\n- speech_style: 先压低语气再落结论\n- cadence: 慢半拍后落点\n- signature_phrases: 先看清；别急着站位\n- typical_lines: 先看清再说；别急着站位\n- sentence_openers: 先；别急\n- sentence_endings: 再说；也罢\n- worldview: 先把局势看清，再决定站位。\n- belief_anchor: 关键时刻不能自乱阵脚。\n- moral_bottom_line: 不把同伴当代价随手抛掉。\n- restraint_threshold: 平时克制，底线被逼穿时才会失控。\n- stress_response: 压力越大越会先收声，再集中判断。\n"
                }

            fake_parts.llm.chat_completion = Mock(side_effect=fake_chat_completion)

            with patch("src.web.workflow.build_runtime_parts", return_value=fake_parts):
                result = service._run_automatic_pipeline(
                    manifest_path=manifest_path,
                    novel_path=novel_path,
                    locked_characters=["Alpha"],
                    max_sentences=120,
                    max_chars=50000,
                )

            self.assertTrue(result["success"])
            persona_dir = run_dir / "artifacts" / "characters" / "novel" / "Alpha"
            self.assertTrue((persona_dir / "PROFILE.generated.md").exists())
            self.assertTrue((persona_dir / "SOUL.generated.md").exists())
            self.assertTrue((run_dir / "payloads" / "distill_Alpha.json").exists())
            self.assertTrue(
                (run_dir / "payloads" / "relation_payload.auto.json").exists()
            )
            self.assertFalse(fake_parts.distiller.distill.called)
            distill_messages = fake_parts.llm.chat_completion.call_args_list[0].args[0]
            self.assertIn("PRIORITY_GUIDANCE", distill_messages[1]["content"])
            self.assertIn("EVIDENCE_STAGES", distill_messages[1]["content"])

    def test_automatic_pipeline_repairs_risky_profile_scalars_once(self):
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
                    "贾宝玉初入大观园。后来贾宝玉看破繁华。".encode("utf-8")
                ).decode("ascii"),
                characters=["贾宝玉"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            manifest_path = run_dir / "run_manifest.json"
            novel_path = run_dir / "input" / "hongloumeng.txt"

            class _FakePathProvider:
                def __init__(self, base_dir: Path) -> None:
                    self.base_dir = base_dir

                def characters_root(self, novel_id: str) -> Path:
                    path = self.base_dir / "artifacts" / "characters" / novel_id
                    path.mkdir(parents=True, exist_ok=True)
                    return path

                def relations_file(self, novel_id: str) -> Path:
                    path = (
                        self.base_dir
                        / "artifacts"
                        / "relations"
                        / f"{novel_id}_relations.md"
                    )
                    path.parent.mkdir(parents=True, exist_ok=True)
                    return path

            fake_parts = Mock()
            fake_parts.path_provider = _FakePathProvider(run_dir)

            def fake_chat_completion(messages, **kwargs):
                prompt = messages[1]["content"]
                if "COMPLETION_TASK" in prompt:
                    return {
                        "content": "- soul_goal: 守住真情与自我\n- hidden_desire: 证据不足\n- core_traits: 重情；敏感\n- temperament_type: 清醒又多情\n- values: 善良=8；自由=7；责任=6\n- inner_conflict: 真情与礼法冲突\n- self_cognition: 知道自己不愿顺着功名路走\n- private_self: 证据不足\n- thinking_style: 先凭真心，再看后果\n- cognitive_limits: 情绪重时易偏执\n- decision_rules: 真情优先；不拿真心换体面\n- reward_logic: 谁真心待我，我便真心回之\n- action_style: 先试探，再靠近\n- fear_triggers: 真心受损\n- emotion_model: 外软内执\n- social_mode: 亲疏分明\n- carry_style: 对亲近者更柔软\n- others_impression: 多情而不愿俗\n- key_bonds: 黛玉；家人\n- strengths: 共情；真诚\n- weaknesses: 情绪牵引重\n- speech_style: 软中带刺\n- typical_lines: 你也不用哄我；这有什么意思\n- cadence: 先轻再沉\n- signature_phrases: 你也不用；我偏\n- sentence_openers: 你也；我偏\n- sentence_endings: 罢了；也就如此\n- arc_type: 觉醒\n- arc_blocker: 礼法与家族压力\n- arc_summary: 从被裹挟到更认清自己所重\n"
                    }
                if "REPAIR_TASK" in prompt:
                    return {
                        "content": "# PROFILE\n- name: 贾宝玉\n- novel_id: hongloumeng\n- core_identity: 贾府公子\n- worldview: 人情比功名更重，真心不能拿来铺垫场面。\n- belief_anchor: 真情不可轻负\n- restraint_threshold: 平时压得住，唯独真心与自尊同时受损时会明显失控。\n- stress_response: 压力越大越会先把情绪压低，再用更冷的语气自护。\n"
                    }
                if "RELATION_GRAPH" in prompt:
                    return {
                        "content": "# RELATION_GRAPH\n\n## 贾宝玉_林黛玉\n- trust: 8\n- affection: 9\n- power_gap: 0\n- conflict_point: 真心太重时易生误会\n- typical_interaction: 试探与安抚\n- hidden_attitude: \n- relation_change: 升温\n- appellation_to_target: 黛玉\n- confidence: 8\n"
                    }
                return {
                    "content": "# PROFILE\n- name: 贾宝玉\n- novel_id: hongloumeng\n- core_identity: 贾府公子\n- worldview: 大家想着，宝玉却等不得了，也不等贾政的命，便说道：“旧诗有云：\n- belief_anchor: 真情不可轻负\n- restraint_threshold: 转过大厅，宝玉心里还自狐疑，只听墙角边一阵呵呵大笑。\n- stress_response: 平时压得住，真心受损时会失控\n"
                }

            fake_parts.llm.chat_completion = Mock(side_effect=fake_chat_completion)

            with patch("src.web.workflow.build_runtime_parts", return_value=fake_parts):
                result = service._run_automatic_pipeline(
                    manifest_path=manifest_path,
                    novel_path=novel_path,
                    locked_characters=["贾宝玉"],
                    max_sentences=120,
                    max_chars=50000,
                )

            self.assertTrue(result["success"])
            self.assertIn("elapsed_text", result["timing"])
            self.assertTrue(str(result["timing"]["elapsed_text"]).strip())
            self.assertEqual(
                result["summary"]["elapsed_text"], result["timing"]["elapsed_text"]
            )
            profile_path = (
                run_dir
                / "host_output"
                / "hongloumeng"
                / "贾宝玉"
                / "PROFILE.generated.md"
            )
            profile_text = profile_path.read_text(encoding="utf-8")
            self.assertIn("人情比功名更重", profile_text)
            self.assertNotIn("旧诗有云", profile_text)
            self.assertEqual(result["quality"]["profile_repairs"]["count"], 1)
            self.assertIn("贾宝玉", result["quality"]["profile_repairs"]["characters"])
            self.assertIn("chunking", result["progress"])
            self.assertIn("distill", result["progress"]["chunking"])
            self.assertEqual(
                result["progress"]["chunking"]["distill"]["status"], "complete"
            )
            self.assertIn("chunking", result["summary"])
            self.assertTrue(
                any(
                    "本次整理耗时" in str(item.get("message", ""))
                    for item in result.get("events", [])
                )
            )
            repair_messages = next(
                call.args[0]
                for call in fake_parts.llm.chat_completion.call_args_list
                if "REPAIR_TASK" in call.args[0][1]["content"]
            )
            self.assertIn("REPAIR_TASK", repair_messages[1]["content"])
            self.assertIn("剧情碎句", repair_messages[1]["content"])

    def test_automatic_pipeline_surface_field_sanitizer_drops_transient_patch_values(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            profile_path = Path(tmp) / "PROFILE.generated.md"
            profile_path.write_text(
                "# PROFILE\n- name: 甲\n- novel_id: demo\n- appearance_feature: 证据不足\n- habit_action: 证据不足\n",
                encoding="utf-8",
            )

            fake_parts = Mock()
            fake_parts.llm.chat_completion = Mock(
                return_value={
                    "content": "- appearance_feature: 只见他回头看了一眼，忽然转身就走\n- habit_action: 他说完就立刻转身离开\n"
                }
            )
            config = Mock(get=Mock(side_effect=lambda key, default=None: default))
            payload = {
                "prompt": "system",
                "references": {
                    "output_schema": "",
                    "style_differ": "",
                    "logic_constraint": "",
                    "validation_policy": "",
                },
                "request": {
                    "excerpt": "甲回头看了一眼。",
                    "excerpt_stages": {"start": "", "mid": "", "end": ""},
                },
                "meta": {"novel_id": "demo"},
            }

            repaired = service._maybe_repair_generated_profile(
                parts=fake_parts,
                config=config,
                payload=payload,
                character="甲",
                peer_characters=[],
                source_path=profile_path,
            )

            self.assertIsNotNone(repaired)
            self.assertIn("- appearance_feature: ", repaired)
            self.assertIn("- habit_action: ", repaired)
            self.assertNotIn("证据不足", repaired)
            self.assertNotIn("只见他回头看了一眼", repaired)
            self.assertNotIn("他说完就立刻转身离开", repaired)

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
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。薛宝钗也在场。".encode("utf-8")
                ).decode("ascii"),
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
            (relations_root / "hongloumeng_relations.html").write_text(
                "<html></html>", encoding="utf-8"
            )
            (relations_root / "hongloumeng_relations.svg").write_text(
                "<svg></svg>", encoding="utf-8"
            )
            (relations_root / "hongloumeng_relations.mermaid.md").write_text(
                "graph LR", encoding="utf-8"
            )
            (relations_root / "hongloumeng_relations.md").write_text(
                "## 林黛玉_贾宝玉", encoding="utf-8"
            )

            refreshed = service.refresh_run(payload["run_id"])
            self.assertEqual(refreshed["summary"]["characters_completed"], 1)
            self.assertEqual(refreshed["summary"]["graph_status"], "complete")
            self.assertEqual(
                refreshed["artifact_index"]["characters"][0]["name"], "林黛玉"
            )
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
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
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
                content_base64=base64.b64encode(profile_text.encode("utf-8")).decode(
                    "ascii"
                ),
            )
            self.assertEqual(refreshed["summary"]["characters_completed"], 1)
            self.assertEqual(
                refreshed["artifact_index"]["characters"][0]["name"], "林黛玉"
            )
            self.assertTrue(
                (
                    Path(tmp)
                    / "runs"
                    / payload["run_id"]
                    / "artifacts"
                    / "characters"
                    / "hongloumeng"
                    / "林黛玉"
                    / "SOUL.generated.md"
                ).exists()
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
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
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
                content_base64=base64.b64encode(relations_text.encode("utf-8")).decode(
                    "ascii"
                ),
                filename="hongloumeng_relations.md",
            )
            self.assertEqual(refreshed["summary"]["graph_status"], "complete")
            self.assertIn("graph_html", refreshed["file_urls"])
