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

class PersonaReviewServiceTests(unittest.TestCase):
    def test_persona_review_can_load_and_save_editable_profile(self):
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
                    "- identity_anchor: 真心与自尊都很重",
                    "- soul_goal: 守住真心",
                    "- worldview: 世情热闹，真心难得",
                    "- speech_style: 清冷带刺",
                    "- cadence: 先轻后冷",
                    "- signature_phrases: 也罢；我原知道",
                    "- typical_lines: 你也不用哄我；我原知道",
                    "- key_bonds: 贾宝玉；紫鹃",
                ]
            )
            service.ingest_character_result(
                payload["run_id"],
                character="林黛玉",
                content_base64=base64.b64encode(profile_text.encode("utf-8")).decode(
                    "ascii"
                ),
            )

            review = service.get_persona_review(payload["run_id"], "林黛玉")
            self.assertEqual(review["fields"]["core_identity"], "贾府外来才女")
            self.assertEqual(review["fields"]["identity_anchor"], "真心与自尊都很重")
            self.assertEqual(review["fields"]["signature_phrases"], "也罢；我原知道")

            saved = service.save_persona_review(
                payload["run_id"],
                "林黛玉",
                {
                    "core_identity": "自尊极重的外来才女",
                    "identity_anchor": "我最看重真心，也最不肯委屈自己",
                    "worldview": "人情再热闹，也比不过一颗真心。",
                    "restraint_threshold": "平日克制，唯独真心受损时会失控。",
                    "signature_phrases": "也罢；你又来哄我",
                    "typical_lines": "你也不用哄我；我心里自然明白",
                    "key_bonds": "贾宝玉；紫鹃；贾母",
                    "anger_style": "先收住声气，再把冷意压进话里。",
                },
            )
            self.assertEqual(saved["fields"]["core_identity"], "自尊极重的外来才女")
            self.assertIn("真心", saved["fields"]["worldview"])
            self.assertIn("真心", saved["fields"]["identity_anchor"])
            self.assertEqual(saved["fields"]["signature_phrases"], "也罢；你又来哄我")
            self.assertIn("贾母", saved["fields"]["key_bonds"])
            self.assertIn("冷意", saved["fields"]["anger_style"])

    def test_persona_review_save_records_review_event_metadata(self):
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
            service.ingest_character_result(
                payload["run_id"],
                character="林黛玉",
                content_base64=base64.b64encode(
                    "- name: 林黛玉\n- novel_id: hongloumeng\n- core_identity: 贾府外来才女\n- speech_style: 清冷带刺\n".encode(
                        "utf-8"
                    )
                ).decode("ascii"),
            )

            service.save_persona_review(
                payload["run_id"],
                "林黛玉",
                {
                    "core_identity": "自尊极重的外来才女",
                    "speech_style": "轻冷含刺，真心一动就更薄更快。",
                    "review_source": "character_overview_autofill",
                    "review_note": "model_knowledge",
                },
            )

            run = service.get_run(payload["run_id"])
            review_event = next(
                item
                for item in reversed(run["events"])
                if item.get("stage") == "persona_review_saved"
                and item.get("character") == "林黛玉"
            )
            self.assertEqual(
                review_event["review_source"], "character_overview_autofill"
            )
            self.assertEqual(review_event["review_note"], "model_knowledge")
            self.assertEqual(review_event["message"], "林黛玉 的人物补全已写回")
            self.assertEqual(
                review_event["changed_fields"], ["core_identity", "speech_style"]
            )

    def test_persona_field_autofill_uses_web_references_and_does_not_force_save(self):
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
                side_effect=[
                    {
                        "content": '{"status":"insufficient","value":"","reason":"我对这个角色的把握不够稳定。"}'
                    },
                    {
                        "content": '{"status":"filled","value":"对真心极敏感，也极重自尊。","reason":"多条人物分析都强调其真心与自尊。"}'
                    },
                ]
            )

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
                payload = service.suggest_persona_field(
                    run["run_id"], "林黛玉", "identity_anchor"
                )

            self.assertEqual(payload["status"], "filled")
            self.assertIn("真心", payload["value"])
            self.assertEqual(payload["source_mode"], "web_fallback")
            self.assertEqual(fake_parts.llm.chat_completion.call_count, 2)
            review = service.get_persona_review(run["run_id"], "林黛玉")
            self.assertEqual(review["fields"]["identity_anchor"], "")

    def test_persona_field_autofill_returns_insufficient_when_web_refs_missing(self):
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
                    "content": '{"status":"insufficient","value":"","reason":"我对这个角色的把握不够稳定。"}'
                }
            )

            with patch(
                "src.web.workflow.build_runtime_parts", return_value=fake_parts
            ), patch(
                "src.web.service_facades.artifacts.collect_persona_web_references",
                return_value=[],
            ):
                payload = service.suggest_persona_field(
                    run["run_id"], "林黛玉", "identity_anchor"
                )

            self.assertEqual(payload["status"], "insufficient")
            self.assertIn("把握不够稳定", payload["message"])
            self.assertEqual(payload["source_mode"], "none")
            fake_parts.llm.chat_completion.assert_called_once()

    def test_persona_field_autofill_prefers_model_knowledge_before_web_lookup(self):
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
                    "content": '{"status":"filled","value":"把真心和自尊看得极重。","reason":"经典角色知识稳定。"}'
                }
            )

            with patch(
                "src.web.workflow.build_runtime_parts", return_value=fake_parts
            ), patch(
                "src.web.service_facades.artifacts.collect_persona_web_references",
                side_effect=AssertionError(
                    "web fallback should not run when model knowledge succeeds"
                ),
            ):
                payload = service.suggest_persona_field(
                    run["run_id"], "林黛玉", "identity_anchor"
                )

            self.assertEqual(payload["status"], "filled")
            self.assertEqual(payload["source_mode"], "model_knowledge")
            self.assertIn("模型知识", payload["message"])
            fake_parts.llm.chat_completion.assert_called_once()

    def test_persona_field_autofill_accepts_plaintext_model_completion(self):
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
                return_value={"content": "把真心和自尊看得极重。"}
            )

            with patch(
                "src.web.workflow.build_runtime_parts", return_value=fake_parts
            ), patch(
                "src.web.service_facades.artifacts.collect_persona_web_references",
                side_effect=AssertionError(
                    "web fallback should not run when plaintext model knowledge succeeds"
                ),
            ):
                payload = service.suggest_persona_field(
                    run["run_id"], "林黛玉", "identity_anchor"
                )

            self.assertEqual(payload["status"], "filled")
            self.assertIn("真心", payload["value"])
            self.assertEqual(payload["source_mode"], "model_knowledge")

    def test_persona_field_autofill_retries_after_broken_brace_response(self):
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
                side_effect=[
                    {"content": "{"},
                    {"content": "把真心和自尊看得极重。"},
                ]
            )

            with patch(
                "src.web.workflow.build_runtime_parts", return_value=fake_parts
            ), patch(
                "src.web.service_facades.artifacts.collect_persona_web_references",
                side_effect=AssertionError(
                    "web fallback should not run when retry succeeds"
                ),
            ):
                payload = service.suggest_persona_field(
                    run["run_id"], "林黛玉", "identity_anchor"
                )

            self.assertEqual(payload["status"], "filled")
            self.assertIn("真心", payload["value"])
            self.assertEqual(payload["source_mode"], "model_knowledge")
            self.assertEqual(fake_parts.llm.chat_completion.call_count, 2)

    def test_persona_field_autofill_retries_after_broken_value_fragment_response(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            run = service.create_run(
                novel_name="modao.txt",
                novel_content_base64=base64.b64encode(
                    "江澄站在船头。".encode("utf-8")
                ).decode("ascii"),
                characters=["江澄"],
            )
            service.ingest_character_result(
                run["run_id"],
                character="江澄",
                content_base64=base64.b64encode(
                    "- name: 江澄\n- novel_id: 魔道祖师\n- core_identity: 云梦江氏宗主\n".encode(
                        "utf-8"
                    )
                ).decode("ascii"),
            )
            fake_parts = Mock()
            fake_parts.llm.chat_completion = Mock(
                side_effect=[
                    {
                        "content": '"value": "魏无羡（前师弟/宿敌）；江厌离（姐姐）；金凌（外甥）；蓝忘机（对立者/前'
                    },
                    {
                        "content": "魏无羡（前师弟/宿敌）；江厌离（姐姐/精神支柱）；金凌（外甥）；蓝忘机（对立者）。"
                    },
                ]
            )

            with patch(
                "src.web.workflow.build_runtime_parts", return_value=fake_parts
            ), patch(
                "src.web.service_facades.artifacts.collect_persona_web_references",
                side_effect=AssertionError(
                    "web fallback should not run when retry succeeds"
                ),
            ):
                payload = service.suggest_persona_field(
                    run["run_id"], "江澄", "key_bonds"
                )

            self.assertEqual(payload["status"], "filled")
            self.assertNotIn('"value":', payload["value"])
            self.assertIn("魏无羡", payload["value"])
            self.assertEqual(fake_parts.llm.chat_completion.call_count, 2)

    def test_persona_field_autofill_extracts_final_candidate_from_meta_reasoning(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            run = service.create_run(
                novel_name="modao.txt",
                novel_content_base64=base64.b64encode(
                    "江澄站在船头。".encode("utf-8")
                ).decode("ascii"),
                characters=["江澄"],
            )
            service.ingest_character_result(
                run["run_id"],
                character="江澄",
                content_base64=base64.b64encode(
                    "- name: 江澄\n- novel_id: 魔道祖师\n- core_identity: 云梦江氏宗主\n".encode(
                        "utf-8"
                    )
                ).decode("ascii"),
            )
            fake_parts = Mock()
            fake_parts.llm.chat_completion = Mock(
                return_value={
                    "content": "我们被要求为江澄这个角色补全“重要牵系”字段。我知道《魔道祖师》是墨香铜臭的作品。可以给出：魏无羡（师弟/宿敌）；金凌（外甥）；江厌离（亡姐）；虞紫鸢（亡母）；蓝忘机（对立者）。理由：我对这个角色比较熟悉。"
                }
            )

            with patch(
                "src.web.workflow.build_runtime_parts", return_value=fake_parts
            ), patch(
                "src.web.service_facades.artifacts.collect_persona_web_references",
                side_effect=AssertionError(
                    "web fallback should not run when extraction succeeds"
                ),
            ):
                payload = service.suggest_persona_field(
                    run["run_id"], "江澄", "key_bonds"
                )

            self.assertEqual(payload["status"], "filled")
            self.assertIn("魏无羡", payload["value"])
            self.assertNotIn("我们被要求", payload["value"])

    def test_persona_field_autofill_retries_when_meta_reasoning_has_no_final_answer(
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
            run = service.create_run(
                novel_name="modao.txt",
                novel_content_base64=base64.b64encode(
                    "江澄站在船头。".encode("utf-8")
                ).decode("ascii"),
                characters=["江澄"],
            )
            service.ingest_character_result(
                run["run_id"],
                character="江澄",
                content_base64=base64.b64encode(
                    "- name: 江澄\n- novel_id: 魔道祖师\n- core_identity: 云梦江氏宗主\n".encode(
                        "utf-8"
                    )
                ).decode("ascii"),
            )
            fake_parts = Mock()
            fake_parts.llm.chat_completion = Mock(
                side_effect=[
                    {
                        "content": "我们被要求补全江澄的重要牵系。我知道他和魏无羡、金凌、江厌离关系都很重要。我觉得需要提取最关键的那些。"
                    },
                    {
                        "content": "魏无羡（师弟/宿敌）；金凌（外甥）；江厌离（亡姐）；虞紫鸢（亡母）。"
                    },
                ]
            )

            with patch(
                "src.web.workflow.build_runtime_parts", return_value=fake_parts
            ), patch(
                "src.web.service_facades.artifacts.collect_persona_web_references",
                side_effect=AssertionError(
                    "web fallback should not run when retry succeeds"
                ),
            ):
                payload = service.suggest_persona_field(
                    run["run_id"], "江澄", "key_bonds"
                )

            self.assertEqual(payload["status"], "filled")
            self.assertIn("魏无羡", payload["value"])
            self.assertEqual(payload["source_mode"], "model_knowledge")
            self.assertEqual(fake_parts.llm.chat_completion.call_count, 2)

    def test_relation_details_list_exposes_evidence_lines(self):
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
                    "- relationship_type: 爱情",
                    "- typical_interaction: 试探里带着牵挂",
                    "- conflict_point: 真心太重，反而常被误伤",
                    "- evidence_lines: 初见时互相打量；试探里总藏着在意",
                ]
            )
            service.ingest_relation_result(
                payload["run_id"],
                content_base64=base64.b64encode(relations_text.encode("utf-8")).decode(
                    "ascii"
                ),
                filename="hongloumeng_relations.md",
            )

            details = service.list_relation_details(payload["run_id"])
            self.assertEqual(details["relation_count"], 1)
            self.assertEqual(details["items"][0]["relationship_type"], "爱情")
            self.assertTrue(details["items"][0]["evidence_lines"])
            self.assertTrue(
                (
                    Path(tmp)
                    / "runs"
                    / payload["run_id"]
                    / "artifacts"
                    / "relations"
                    / "hongloumeng_relations.html"
                ).exists()
            )
