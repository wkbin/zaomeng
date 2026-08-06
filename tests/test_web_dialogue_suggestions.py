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

class DialogueSuggestionTests(unittest.TestCase):
    def test_suggest_dialogue_turn_does_not_mutate_session_history(self):
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
                characters=["林黛玉", "贾宝玉"],
            )
            run_id = run["run_id"]
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    run_id,
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                service,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session = service.create_dialogue_session(
                    run_id,
                    mode="observe",
                    participants=["???", "???"],
                    controlled_character="",
                    self_profile={},
                )

            original_history = list(session["history"])

            with patch.object(
                service,
                "_generate_dialogue_suggestion",
                return_value="要不先让他们把刚才那句接下去？",
            ) as generate_suggestion:
                result = service.suggest_dialogue_turn(
                    run_id,
                    session_id=session["session_id"],
                    seed_text="要不先让",
                    direction="追问母亲生前之事",
                )

            self.assertEqual(result["suggestion"], "要不先让他们把刚才那句接下去？")
            self.assertEqual(
                generate_suggestion.call_args.args[1]["selected_direction"],
                "追问母亲生前之事",
            )
            with patch.object(
                service,
                "_generate_dialogue_associations",
                return_value=[
                    {
                        "label": "追问旧事",
                        "direction": "顺着刚才的话追问母亲生前的旧事",
                    },
                    {
                        "label": "缓和气氛",
                        "direction": "先回应对方的关心，让关系靠近一点",
                    },
                    {"label": "转向行动", "direction": "让角色提议立刻去查关键线索"},
                ],
            ) as generate_associations:
                associations = service.associate_dialogue_turn(
                    run_id,
                    session_id=session["session_id"],
                    option_count=3,
                )
            self.assertTrue(associations["show"])
            self.assertEqual(len(associations["options"]), 3)
            association_payload = generate_associations.call_args.args[1]
            self.assertEqual(
                association_payload["kind"], "zaomeng_dialogue_associations"
            )
            self.assertEqual(association_payload["instructions"]["option_count"], 3)
            refreshed_session = service.get_dialogue_session(
                run_id, session["session_id"]
            )
            self.assertEqual(refreshed_session["history"], original_history)
            self.assertEqual(refreshed_session["pending_turn_summary"], {})
            self.assertEqual(refreshed_session["status"], "ready")

    def test_dialogue_relative_to_run_dir_accepts_case_or_short_path_variants(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            run_dir = Path(tmp) / "runs" / "run-demo"
            nested = run_dir / "dialogue" / "dlg-1" / "turns" / "turn-1.payload.json"
            nested.parent.mkdir(parents=True, exist_ok=True)
            nested.write_text("{}", encoding="utf-8")

            relative = service.dialogue._relative_to_run_dir(
                nested, Path(str(run_dir).upper())
            )

            self.assertEqual(
                relative, Path("dialogue") / "dlg-1" / "turns" / "turn-1.payload.json"
            )

    def test_parse_dialogue_suggestion_rejects_meta_explanation(self):
        with self.assertRaisesRegex(
            ValueError, "explanation instead of a direct sendable line"
        ):
            parse_dialogue_suggestion(
                "我们作为“你”是误入此间的来客，当前场景是对方在生气，我们可以先安抚，再解释。"
            )

    def test_parse_dialogue_suggestion_rejects_generic_observe_wrapper(self):
        with self.assertRaisesRegex(
            ValueError, "explanation instead of a direct sendable line"
        ):
            parse_dialogue_suggestion("要不先让他们把刚才那句接下去？")

    def test_parse_dialogue_associations_normalizes_and_deduplicates_options(self):
        parsed = parse_dialogue_associations("""
            {
              "show": true,
              "options": [
                {"label": "追问旧事", "direction": "顺着刚才的话追问母亲生前的旧事"},
                {"label": "缓和气氛", "direction": "先回应对方的关心，让关系靠近一点", "anchor_speaker": "林黛玉", "anchor_quote": "你别再逞强了"},
                {"label": "追问旧事", "direction": "重复项不应保留"},
                {"label": "转向行动", "direction": "提议立刻去查关键线索"}
              ]
            }
            """)

        self.assertEqual(
            [item["label"] for item in parsed], ["追问旧事", "缓和气氛", "转向行动"]
        )
        self.assertEqual(parsed[1]["anchor_speaker"], "林黛玉")
        self.assertEqual(parsed[1]["anchor_quote"], "你别再逞强了")

    def test_association_parser_requires_sendable_suggestions_when_requested(self):
        with self.assertRaisesRegex(ValueError, "enough distinct"):
            parse_dialogue_associations(
                json.dumps(
                    {
                        "options": [
                            {"label": "追问旧事", "direction": "继续追问对方刚提到的旧事"},
                            {"label": "缓和气氛", "direction": "先回应对方的关心"},
                        ]
                    },
                    ensure_ascii=False,
                ),
                require_suggestions=True,
            )

    def test_dialogue_associations_retry_when_anchor_is_not_in_latest_reply(self):
        payload = {
            "mode": "act",
            "input": {"speaker": "史湘云", "participants": ["史湘云", "王熙凤"]},
            "latest_exchange": {
                "user_turn": {"speaker": "史湘云", "message": "林姐姐也来押个彩头。"},
                "replies": [
                    {
                        "speaker": "王熙凤",
                        "message": "我这一笼螃蟹管够，输了可得讲一篓子故事。",
                    }
                ],
            },
            "instructions": {"option_count": 2},
        }
        chat_completion = Mock(
            side_effect=[
                {
                    "content": json.dumps(
                        {
                            "options": [
                                {
                                    "label": "接下赌约",
                                    "direction": "拿银簪作抵押，接下赌约",
                                    "anchor_speaker": "王熙凤",
                                    "anchor_quote": "银簪子先押着",
                                },
                                {
                                    "label": "追问彩头",
                                    "direction": "追问彩头是什么",
                                    "anchor_speaker": "王熙凤",
                                    "anchor_quote": "螃蟹管够",
                                },
                            ]
                        },
                        ensure_ascii=False,
                    )
                },
                {
                    "content": json.dumps(
                        {
                            "options": [
                                {
                                    "label": "应下螃蟹局",
                                    "direction": "回应凤姐，爽快应下这场螃蟹局",
                                    "anchor_speaker": "王熙凤",
                                    "anchor_quote": "一笼螃蟹管够",
                                },
                                {
                                    "label": "拿故事还价",
                                    "direction": "围绕输后讲故事的条件与凤姐还价",
                                    "anchor_speaker": "王熙凤",
                                    "anchor_quote": "讲一篓子故事",
                                },
                            ]
                        },
                        ensure_ascii=False,
                    )
                },
            ]
        )

        options = generate_dialogue_associations(
            payload=payload,
            temperature=0.7,
            max_tokens=0,
            chat_completion=chat_completion,
            build_messages=lambda current, retry: build_dialogue_association_llm_messages(
                current, retry_on_empty=retry
            ),
            parse_associations=parse_dialogue_associations,
        )

        self.assertEqual(chat_completion.call_count, 2)
        self.assertEqual(
            [item["label"] for item in options], ["应下螃蟹局", "拿故事还价"]
        )

    def test_dialogue_associations_retry_when_direction_reinvites_recent_speaker(self):
        payload = {
            "mode": "act",
            "input": {
                "speaker": "史湘云",
                "participants": ["史湘云", "林黛玉", "贾宝玉"],
            },
            "history": [
                {
                    "speaker": "贾宝玉",
                    "message": "你要赌也成，若你赢了，糕归你。",
                }
            ],
            "latest_exchange": {
                "replies": [
                    {
                        "speaker": "林黛玉",
                        "message": "你便自个儿跟二哥哥赌去，我只管看热闹。",
                    }
                ],
                "present_participants": ["史湘云", "林黛玉", "贾宝玉"],
            },
            "instructions": {"option_count": 2},
        }
        chat_completion = Mock(
            side_effect=[
                {
                    "content": json.dumps(
                        {
                            "options": [
                                {
                                    "label": "拉宝玉来助阵",
                                    "direction": "转向贾宝玉，请他当裁判或一起加入赌约",
                                    "anchor_speaker": "林黛玉",
                                    "anchor_quote": "跟二哥哥赌去",
                                },
                                {
                                    "label": "回敬林姐姐",
                                    "direction": "顺着黛玉的话笑着回敬她",
                                    "anchor_speaker": "林黛玉",
                                    "anchor_quote": "我只管看热闹",
                                },
                            ]
                        },
                        ensure_ascii=False,
                    )
                },
                {
                    "content": json.dumps(
                        {
                            "options": [
                                {
                                    "label": "回应宝玉赌注",
                                    "direction": "接着回应宝玉已经提出的糕点赌注",
                                    "anchor_speaker": "林黛玉",
                                    "anchor_quote": "跟二哥哥赌去",
                                },
                                {
                                    "label": "回敬林姐姐",
                                    "direction": "顺着黛玉的话笑着回敬她",
                                    "anchor_speaker": "林黛玉",
                                    "anchor_quote": "我只管看热闹",
                                },
                            ]
                        },
                        ensure_ascii=False,
                    )
                },
            ]
        )

        options = generate_dialogue_associations(
            payload=payload,
            temperature=0.7,
            max_tokens=0,
            chat_completion=chat_completion,
            build_messages=lambda current, retry: build_dialogue_association_llm_messages(
                current, retry_on_empty=retry
            ),
            parse_associations=parse_dialogue_associations,
        )

        self.assertEqual(chat_completion.call_count, 2)
        self.assertEqual(options[0]["label"], "回应宝玉赌注")

    def test_parse_dialogue_associations_rejects_empty_model_decision(self):
        with self.assertRaisesRegex(ValueError, "enough distinct"):
            parse_dialogue_associations('{"show":false,"options":[]}')

    def test_parse_dialogue_associations_rejects_single_option(self):
        with self.assertRaisesRegex(ValueError, "enough distinct"):
            parse_dialogue_associations(
                '{"show":true,"options":[{"label":"追问旧事","direction":"追问旧事"}]}'
            )

    def test_parse_dialogue_suggestion_accepts_complete_multi_sentence_copy(self):
        content = (
            "我原想着这件事不必再提，可你既问到了这里，我也不愿再拿一句轻飘飘的话敷衍过去。"
            "当年的真相，我知道多少便会告诉你多少，只是你听完之后，别再说自己从未被人在意过。"
        )

        self.assertEqual(parse_dialogue_suggestion(content), content)

    def test_generate_dialogue_suggestion_retries_when_model_hits_token_limit(self):
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
            manifest = service._require_manifest(run["run_id"])
            session = service.dialogue.create_session(
                manifest,
                mode="act",
                participants=["林黛玉", "贾宝玉"],
                controlled_character="林黛玉",
                self_profile={},
            )

            with patch(
                "src.web.service_facades.dialogue.build_runtime_parts"
            ) as build_parts:
                fake_parts = Mock()
                fake_parts.llm.chat_completion.side_effect = [
                    {
                        "content": "我原想着这件事不必",
                        "finish_reason": "length",
                        "raw": {"choices": [{"finish_reason": "length"}]},
                    },
                    {
                        "content": "我原想着这件事不必再提，可你既问了，我便把知道的都告诉你。",
                        "finish_reason": "stop",
                        "raw": {"choices": [{"finish_reason": "stop"}]},
                    },
                ]
                build_parts.return_value = fake_parts

                result = service.suggest_dialogue_turn(
                    run["run_id"],
                    session_id=session["session_id"],
                    direction="坦白当年的真相",
                )

            self.assertTrue(result["suggestion"].endswith("。"))
            self.assertEqual(fake_parts.llm.chat_completion.call_count, 2)
            first_limit = fake_parts.llm.chat_completion.call_args_list[0].kwargs[
                "max_tokens"
            ]
            second_limit = fake_parts.llm.chat_completion.call_args_list[1].kwargs[
                "max_tokens"
            ]
            self.assertEqual(first_limit, 512)
            self.assertGreater(second_limit, first_limit)

    def test_generate_dialogue_suggestion_retries_after_meta_explanation(self):
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
                characters=["林黛玉", "贾宝玉"],
            )
            run_id = run["run_id"]
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    run_id,
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                service,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session = service.create_dialogue_session(
                    run_id,
                    mode="insert",
                    participants=["???", "???"],
                    controlled_character="",
                    self_profile={"display_name": "你", "scene_identity": "来客"},
                )

            with patch(
                "src.web.service_facades.dialogue.build_runtime_parts"
            ) as build_parts:
                fake_parts = Mock()
                fake_parts.llm.chat_completion.side_effect = [
                    {
                        "content": "我们作为“你”是误入此间的来客，当前场景是对方在生气，我们可以先安抚，再解释。",
                        "raw": {},
                    },
                    {"content": "别生气，我刚才那句不是在呛你。", "raw": {}},
                ]
                build_parts.return_value = fake_parts

                result = service.suggest_dialogue_turn(
                    run_id,
                    session_id=session["session_id"],
                    seed_text="我不是那个意思",
                )

            self.assertEqual(result["suggestion"], "别生气，我刚才那句不是在呛你。")
            self.assertEqual(fake_parts.llm.chat_completion.call_count, 2)

    def test_generate_dialogue_suggestion_retries_with_compact_payload_after_bad_request(
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
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            run_id = run["run_id"]
            service.ingest_character_result(
                run_id,
                character="林黛玉",
                content_base64=base64.b64encode(
                    (
                        "- name: 林黛玉\n- novel_id: hongloumeng\n- core_identity: 林府孤女\n"
                        "- story_role: 情感核心\n- speech_style: 轻冷带刺\n- temperament_type: 敏感自持\n"
                        "- stress_response: 越难受越把话说轻\n- key_bonds: 贾宝玉；贾母；紫鹃\n"
                    ).encode("utf-8")
                ).decode("ascii"),
            )
            service.ingest_character_result(
                run_id,
                character="贾宝玉",
                content_base64=base64.b64encode(
                    (
                        "- name: 贾宝玉\n- novel_id: hongloumeng\n- core_identity: 贾府公子\n"
                        "- story_role: 情感引线\n- speech_style: 软中带急\n- temperament_type: 多情敏感\n"
                        "- stress_response: 心急时话更碎\n- key_bonds: 林黛玉；薛宝钗；袭人\n"
                    ).encode("utf-8")
                ).decode("ascii"),
            )
            manifest = service._require_manifest(run_id)
            relation_path = Path(tmp) / "relations.md"
            relation_path.write_text("贾宝玉与林黛玉彼此牵挂。" * 400, encoding="utf-8")
            manifest["artifact_index"]["relation_graph"] = {
                "relations_file": str(relation_path)
            }
            (service.runs_root / run_id / "run_manifest.json").write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            session = service.dialogue.create_session(
                manifest,
                mode="insert",
                participants=["林黛玉", "贾宝玉"],
                controlled_character="",
                self_profile={
                    "display_name": "阿眠",
                    "scene_identity": "误入园中的来客",
                    "interaction_style": "先软后稳",
                    "core_identity": "不肯轻易交底的来客",
                    "story_role": "意外闯入的变量",
                    "soul_goal": "先站稳再谈真心",
                    "speech_style": "先轻后准，不把话说死",
                    "worldview": "热闹场面里，没说出口的话更要紧。" * 20,
                    "belief_anchor": "先护住自己，才谈得上护别人。",
                    "stress_response": "越紧越先把语气放轻。",
                    "key_bonds": "自己；眼前局势；少数值得信的人；还没看透的人",
                },
            )
            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session["session_id"])
            )
            raw_session["history"] = [
                {
                    "speaker": "林黛玉",
                    "message": f"第{i}句对话" * 20,
                    "ts": "2026-05-09T00:00:00Z",
                }
                for i in range(8)
            ]
            service.dialogue._write_json(
                service.dialogue._session_file(run_id, session["session_id"]),
                raw_session,
            )

            with patch(
                "src.web.service_facades.dialogue.build_runtime_parts"
            ) as build_parts:
                fake_parts = Mock()
                fake_parts.llm.chat_completion.side_effect = [
                    LLMRequestError(
                        "LLM 请求失败: 400 Bad Request | prompt is too long"
                    ),
                    {"content": "你别急，我不是来添乱的。", "raw": {}},
                ]
                build_parts.return_value = fake_parts

                result = service.suggest_dialogue_turn(
                    run_id,
                    session_id=session["session_id"],
                    seed_text="我不是那个意思，我只是",
                )

            self.assertEqual(result["suggestion"], "你别急，我不是来添乱的。")
            self.assertEqual(fake_parts.llm.chat_completion.call_count, 2)
            first_prompt = fake_parts.llm.chat_completion.call_args_list[0].args[0][1][
                "content"
            ]
            second_prompt = fake_parts.llm.chat_completion.call_args_list[1].args[0][1][
                "content"
            ]
            self.assertLess(len(second_prompt), len(first_prompt))
            self.assertIn("误入园中的来客", second_prompt)

    def test_build_suggestion_payload_keeps_controlled_character_full_persona_in_act_mode(
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
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉", "薛宝钗"],
            )
            run_id = run["run_id"]
            for name in ("林黛玉", "贾宝玉", "薛宝钗"):
                service.ingest_character_result(
                    run_id,
                    character=name,
                    content_base64=base64.b64encode(
                        (
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: {name}人物\n"
                            f"- story_role: {name}位置\n- soul_goal: {name}想护住当前局面\n"
                            f"- speech_style: {name}自有口气\n- temperament_type: {name}性情分明\n"
                            f"- social_mode: {name}看人下话\n- reward_logic: {name}有自己偏向\n"
                            f"- stress_response: {name}越急越压住\n- key_bonds: 旧人旧事\n"
                        ).encode("utf-8")
                    ).decode("ascii"),
                )
            manifest = service._require_manifest(run_id)
            session = service.dialogue.create_session(
                manifest,
                mode="act",
                participants=["林黛玉", "贾宝玉", "薛宝钗"],
                controlled_character="林黛玉",
            )

            payload = service.dialogue.build_suggestion_payload(
                manifest,
                session_id=session["session_id"],
                seed_text="你先听我说完。",
            )

            controlled = next(
                item for item in payload["persona_contexts"] if item["name"] == "林黛玉"
            )
            self.assertEqual(controlled["detail_level"], "full")
            self.assertEqual(
                payload["user_persona"]["source"], "controlled_character_persona"
            )
            self.assertEqual(
                payload["user_persona"]["profile"]["soul_goal"], "林黛玉想护住当前局面"
            )

    def test_compact_dialogue_suggestion_payload_trims_memory_context(self):
        payload = {
            "input": {"message": "我想说很多很多话" * 30},
            "history": [{"speaker": "林黛玉", "message": "旧对话" * 40}] * 6,
            "relation_context": {"relations_excerpt": "关系" * 1000},
            "memory_context": {
                "session_summary": {
                    "recap": "最近一拍" * 80,
                    "world": "情绪还绷着" * 80,
                    "current_goal": "把误会摊开说清" * 20,
                    "unresolved_threads": "甲还挂着要回来说清真相" * 20,
                    "current_location": "雨夜回廊 · 回廊 · 夜里" * 10,
                    "current_companions": "当前同行：甲、乙；暂未同场：丙" * 10,
                    "pending_commitments": "待完成承诺：甲明晚会回来把误会说清" * 10,
                },
                "archived_summary": {
                    "summary": "旧冲突摘要" * 120,
                    "key_points": [
                        "要点一" * 40,
                        "要点二" * 40,
                        "要点三" * 40,
                        "要点四" * 40,
                    ],
                    "compressed_turns": 48,
                },
                "retrieved_memories": [
                    {
                        "text": "命中的长期记忆" * 60,
                        "speaker": "林黛玉",
                        "target": "贾宝玉",
                        "kind": "dialogue",
                    },
                    {
                        "text": "第二条长期记忆" * 60,
                        "speaker": "贾宝玉",
                        "target": "林黛玉",
                        "kind": "dialogue",
                    },
                    {"text": "第三条长期记忆" * 60},
                ],
            },
            "persona_contexts": [],
            "user_persona": {},
        }

        compact = compact_dialogue_suggestion_payload(payload)

        compact_memory = compact.get("memory_context", {})
        self.assertTrue(compact_memory.get("session_summary", {}).get("recap"))
        self.assertTrue(compact_memory.get("session_summary", {}).get("current_goal"))
        self.assertTrue(
            compact_memory.get("session_summary", {}).get("unresolved_threads")
        )
        self.assertTrue(
            compact_memory.get("session_summary", {}).get("current_location")
        )
        self.assertTrue(
            compact_memory.get("session_summary", {}).get("current_companions")
        )
        self.assertTrue(
            compact_memory.get("session_summary", {}).get("pending_commitments")
        )
        self.assertLessEqual(
            len(compact_memory.get("archived_summary", {}).get("summary", "")), 181
        )
        self.assertLessEqual(len(compact_memory.get("retrieved_memories", [])), 2)

    def test_build_suggestion_payload_uses_self_insert_persona_in_insert_mode(self):
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
                characters=["林黛玉", "贾宝玉"],
            )
            run_id = run["run_id"]
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    run_id,
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n- speech_style: 各有口气\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )
            manifest = service._require_manifest(run_id)
            session = service.dialogue.create_session(
                manifest,
                mode="insert",
                participants=["林黛玉", "贾宝玉"],
                controlled_character="",
                self_profile={
                    "display_name": "阿眠",
                    "scene_identity": "误入园中的来客",
                    "interaction_style": "先软后稳",
                    "core_identity": "不肯轻易交底的来客",
                    "soul_goal": "先站稳再谈真心",
                    "speech_style": "先轻后准，不把话说死",
                    "worldview": "热闹场面里，没说出口的话更要紧",
                },
            )

            payload = service.dialogue.build_suggestion_payload(
                manifest,
                session_id=session["session_id"],
                seed_text="",
            )

            self.assertEqual(payload["user_persona"]["source"], "self_insert_profile")
            self.assertEqual(payload["user_persona"]["speaker"], "阿眠")
            self.assertEqual(
                payload["user_persona"]["profile"]["scene_identity"], "误入园中的来客"
            )
            self.assertEqual(
                payload["user_persona"]["profile"]["interaction_style"], "先软后稳"
            )
            self.assertEqual(
                payload["user_persona"]["profile"]["core_identity"],
                "不肯轻易交底的来客",
            )
            self.assertEqual(
                payload["user_persona"]["profile"]["soul_goal"], "先站稳再谈真心"
            )

    def test_build_dialogue_suggestion_messages_emphasize_self_insert_persona_priority(
        self,
    ):
        payload = {
            "mode": "insert",
            "input": {
                "speaker": "阿眠",
                "message": "我不是那个意思",
                "participants": ["林黛玉", "贾宝玉"],
            },
            "history": [{"speaker": "林黛玉", "message": "你这话倒轻巧。"}],
            "persona_contexts": [],
            "relation_context": {"relations_excerpt": ""},
            "instructions": {
                "generation_goal": "Draft one short, natural, directly sendable next user line that fits the current scene, relationships, and persona voices.",
                "mode_rule": "Draft the user's next line as the self-insert identity inside the scene.",
                "speaker_rule": "Treat the user message as spoken by 阿眠 who enters the scene as 误入园中的来客.",
                "response_style": "Prefer one concise line that sounds like the self-insert user speaking naturally in the scene, as final sendable wording.",
            },
            "host_action": {
                "expected_output": {"suggestion": "一句可直接发送的话"},
                "output_rule": "Keep it short, in-scene, directly sendable, and never explanatory.",
            },
            "host_prompt_brief": "Help the user speak as 阿眠 inside the current scene with one natural next line.",
            "user_persona": {
                "mode": "insert",
                "speaker": "阿眠",
                "source": "self_insert_profile",
                "must_follow": "Write as the self-insert user, keeping their full role card, identity, motives, and speaking flavor consistent.",
                "profile": {
                    "display_name": "阿眠",
                    "scene_identity": "误入园中的来客",
                    "interaction_style": "先软后稳",
                    "core_identity": "不肯轻易交底的来客",
                    "soul_goal": "先站稳再谈真心",
                    "speech_style": "先轻后准，不把话说死",
                    "worldview": "热闹场面里，没说出口的话更要紧",
                    "belief_anchor": "先护住自己，才谈得上护别人",
                },
            },
        }

        messages = WebRunService._build_dialogue_suggestion_llm_messages(payload)

        self.assertIn("不只参考上下文和别人刚才的回复", messages[0]["content"])
        self.assertIn(
            "优先服从 self-insert 的核心身份、故事位置、灵魂目标",
            messages[0]["content"],
        )

    def test_build_suggestion_payload_uses_controlled_character_persona_in_act_mode(
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
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            run_id = run["run_id"]
            service.ingest_character_result(
                run_id,
                character="贾宝玉",
                content_base64=base64.b64encode(
                    "- name: 贾宝玉\n- novel_id: hongloumeng\n- core_identity: 贾府公子\n- speech_style: 软中带刺\n- temperament_type: 多情敏感\n".encode(
                        "utf-8"
                    )
                ).decode("ascii"),
            )
            service.ingest_character_result(
                run_id,
                character="林黛玉",
                content_base64=base64.b64encode(
                    "- name: 林黛玉\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                        "utf-8"
                    )
                ).decode("ascii"),
            )
            manifest = service._require_manifest(run_id)
            session = service.dialogue.create_session(
                manifest,
                mode="act",
                participants=["贾宝玉", "林黛玉"],
                controlled_character="贾宝玉",
                self_profile={},
            )

            payload = service.dialogue.build_suggestion_payload(
                manifest,
                session_id=session["session_id"],
                seed_text="",
            )

            self.assertEqual(
                payload["user_persona"]["source"], "controlled_character_persona"
            )
            self.assertEqual(payload["user_persona"]["speaker"], "贾宝玉")
            self.assertEqual(
                payload["user_persona"]["profile"]["speech_style"], "软中带刺"
            )
            self.assertEqual(
                payload["user_persona"]["profile"]["temperament_type"], "多情敏感"
            )

    def test_build_suggestion_payload_uses_plot_push_observer_hint_in_observe_mode(
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
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            run_id = run["run_id"]
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    run_id,
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )
            manifest = service._require_manifest(run_id)
            session = service.dialogue.create_session(
                manifest,
                mode="observe",
                participants=["林黛玉", "贾宝玉"],
                controlled_character="",
                self_profile={},
            )

            payload = service.dialogue.build_suggestion_payload(
                manifest,
                session_id=session["session_id"],
                seed_text="",
            )

            self.assertEqual(payload["user_persona"]["source"], "observer_hint")
            self.assertEqual(
                payload["user_persona"]["profile"]["goal"], "push_plot_forward"
            )
            self.assertIn(
                "introduce a new action",
                payload["user_persona"]["profile"]["preferred_moves"],
            )
            self.assertTrue(payload["user_persona"]["profile"]["avoid_patterns"])
            self.assertIn(
                "pushes the plot forward", payload["instructions"]["response_style"]
            )

    def test_build_suggestion_payload_observe_mode_carries_scene_shift_pressure(self):
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
                characters=["林黛玉", "贾宝玉"],
            )
            run_id = run["run_id"]
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    run_id,
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )
            manifest = service._require_manifest(run_id)
            session = service.dialogue.create_session(
                manifest,
                mode="observe",
                participants=["林黛玉", "贾宝玉"],
                controlled_character="",
                self_profile={},
            )
            service.dialogue.update_scene_progress_state(
                run_id,
                session["session_id"],
                {
                    "location": "回廊",
                    "time_hint": "夜深",
                    "beat_maturity": 85,
                    "should_offer_scene_shift": True,
                    "scene_shift_reason": "雨势更大，再站在回廊里已经接不下去了",
                    "world_tension_summary": "两个人都知道下一句就该把局面带进新的地方",
                },
            )
            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session["session_id"])
            )
            raw_session["history"] = [
                {
                    "speaker": "林黛玉",
                    "message": "你总要把这句话说清。",
                    "ts": "2026-05-12T00:00:00Z",
                },
                {
                    "speaker": "贾宝玉",
                    "message": "我明明有话，却还是迟了一拍。",
                    "ts": "2026-05-12T00:00:01Z",
                },
            ]
            service.dialogue._write_json(
                service.dialogue._session_file(run_id, session["session_id"]),
                raw_session,
            )

            payload = service.dialogue.build_suggestion_payload(
                manifest,
                session_id=session["session_id"],
                seed_text="",
            )

            self.assertIn(
                "turn the scene into its next beat naturally",
                payload["user_persona"]["profile"]["preferred_moves"],
            )
            self.assertEqual(
                payload["user_persona"]["profile"]["scene_shift_reason"],
                "雨势更大，再站在回廊里已经接不下去了",
            )
            self.assertTrue(payload["user_persona"]["profile"]["anchor_lines"])
            joined_anchors = " ".join(
                payload["user_persona"]["profile"]["anchor_lines"]
            )
            self.assertIn("回廊", joined_anchors)
            self.assertIn(
                "naturally turns this scene into its next beat",
                payload["host_prompt_brief"],
            )
            self.assertIn("Current transition pressure", payload["host_prompt_brief"])

    def test_build_dialogue_suggestion_messages_use_scene_progress_for_observe_mode(
        self,
    ):
        payload = {
            "mode": "observe",
            "input": {
                "speaker": "User",
                "message": "",
                "participants": ["林黛玉", "贾宝玉"],
            },
            "persona_contexts": [],
            "user_persona": {
                "mode": "observe",
                "speaker": "User",
                "source": "observer_hint",
                "must_follow": "Write as a scene observer giving a short in-world nudge.",
                "profile": {
                    "goal": "push_plot_forward",
                    "preferred_moves": ["turn the scene into its next beat naturally"],
                    "anchor_lines": ["把误会摊开说清", "甲还挂着要回来说清真相"],
                },
            },
            "relation_context": {"relations_excerpt": ""},
            "history": [],
            "memory_context": {"scene_progress": {"offstage_participants": ["薛宝钗"]}},
            "scene_progress": {
                "time_hint": "夜深",
                "location": "回廊",
                "offstage_participants": ["薛宝钗"],
                "should_offer_scene_shift": True,
                "scene_shift_reason": "这幕已经够满，可以顺势切到花厅",
            },
            "instructions": {
                "generation_goal": "Draft one short, natural, directly sendable next user line that fits the current scene, relationships, and persona voices.",
                "mode_rule": "Draft the user's next line as a short scene-steering utterance.",
                "speaker_rule": "Treat the user message as a scene steering hint.",
                "response_style": "Prefer one short scene-driving prompt that pushes the plot forward immediately.",
                "scene_rule": "Keep the scene anchored.",
            },
            "host_action": {
                "expected_output": {"suggestion": "一句可直接发送的话"},
                "output_rule": "Keep it short, in-scene, directly sendable, and never explanatory.",
            },
            "host_prompt_brief": "Help the user guide 林黛玉, 贾宝玉 with one short prompt that naturally turns this scene into its next beat.",
            "scene_card": {},
        }

        messages = WebRunService._build_dialogue_suggestion_llm_messages(payload)

        self.assertIn("scene_progress", messages[1]["content"])
        self.assertIn("这一拍已经成熟、适合转场", messages[0]["content"])
        self.assertIn("offstage_participants", messages[0]["content"])
        self.assertIn("下一下已经发生了", messages[0]["content"])
        self.assertIn("要不先让他们把刚才那句接下去", messages[1]["content"])

    def test_build_dialogue_suggestion_messages_apply_selected_direction_as_intent(
        self,
    ):
        payload = {
            "mode": "act",
            "input": {
                "speaker": "林黛玉",
                "message": "",
                "participants": ["林黛玉", "贾宝玉"],
            },
            "selected_direction": "追问母亲生前之事",
            "persona_contexts": [],
            "user_persona": {"mode": "act", "speaker": "林黛玉"},
            "relation_context": {},
            "history": [],
            "memory_context": {},
            "scene_progress": {},
            "instructions": {},
            "host_action": {},
            "scene_card": {},
        }

        messages = WebRunService._build_dialogue_suggestion_llm_messages(payload)

        self.assertIn("selected_direction", messages[0]["content"])
        self.assertIn("追问母亲生前之事", messages[1]["content"])
