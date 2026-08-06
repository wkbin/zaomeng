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

class DialogueTurnBehaviorTests(unittest.TestCase):
    def test_dialogue_prompt_prefers_inline_parenthetical_actions_over_standalone_narration(
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
                characters=["林黛玉", "贾宝玉"],
            )
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    payload["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            manifest = service._require_manifest(payload["run_id"])
            session = service.dialogue.create_session(
                manifest,
                mode="observe",
                participants=["林黛玉", "贾宝玉"],
            )
            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(payload["run_id"], session["session_id"])
            )
            turn_payload = service.dialogue._build_turn_payload(
                manifest,
                raw_session,
                turn_id="turn-inline-action",
                message="你们继续说。",
            )
            llm_messages = service._build_dialogue_llm_messages(
                turn_payload, retry_on_empty=False
            )
            system_prompt = llm_messages[0]["content"]

            self.assertIn("括号动作", system_prompt)
            self.assertIn("不要单独写成旁白或场景提示", system_prompt)

    def test_prepare_turn_narration_sets_scene_speaker_and_kind(self):
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
                characters=["林黛玉", "贾宝玉", "薛宝钗"],
            )
            for name in ("林黛玉", "贾宝玉", "薛宝钗"):
                service.ingest_character_result(
                    payload["run_id"],
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
                    payload["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉", "薛宝钗"],
                )

            prepared = service.prepare_dialogue_turn(
                payload["run_id"],
                session_id=session["session_id"],
                message="门外忽然传来脚步声，屋里人都静了一拍。",
                message_kind="narration",
            )
            pending = prepared.get("pending_turn_summary", {})
            self.assertEqual(pending.get("message_kind"), "narration")
            self.assertEqual(pending.get("speaker"), "场景提示")
            self.assertTrue(2 <= int(pending.get("response_limit_hint", 0)) <= 5)

    def test_prepare_turn_act_plot_prompt_prioritizes_other_cast_over_controlled_character(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="laoshe.txt",
                novel_content_base64=base64.b64encode("祥子娶了虎妞。".encode("utf-8")).decode("ascii"),
                characters=["祥子", "虎妞"],
            )
            for name in ("祥子", "虎妞"):
                service.ingest_character_result(
                    payload["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: laoshe\n- core_identity: 人物\n".encode("utf-8")
                    ).decode("ascii"),
                )

            manifest = service._require_manifest(payload["run_id"])
            session = service.dialogue.create_session(
                manifest,
                mode="act",
                participants=["祥子", "虎妞"],
                controlled_character="祥子",
            )
            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(payload["run_id"], session["session_id"])
            )
            turn_payload = service.dialogue._build_turn_payload(
                manifest,
                raw_session,
                turn_id="turn-act-plot",
                message="第二天一早，虎妞催祥子出门办事。",
                message_kind="plot",
                speaker_override="场景提示",
            )
            llm_messages = service._build_dialogue_llm_messages(turn_payload, retry_on_empty=False)
            system_prompt = "\n".join(
                message["content"]
                for message in llm_messages
                if message.get("role") == "system"
            )
            hints = turn_payload.get("responder_hints", [])

            self.assertIn("not by speaking as 祥子", system_prompt)
            self.assertIn("Other cast members must react", system_prompt)
            self.assertIn("must not be the only voice", system_prompt)
            self.assertIn("do not return only 祥子's line", system_prompt)
            self.assertIn("not as the final character reply", system_prompt)
            self.assertIn("PLOT_PROGRESSION_CONTRACT is mandatory", system_prompt)
            self.assertIn("must use speaker 场景提示 or 旁白", system_prompt)
            self.assertIn("Do not merely paraphrase", system_prompt)
            self.assertTrue(2 <= int(turn_payload["host_action"]["response_limit_hint"]) <= 4)
            self.assertEqual(hints[0]["name"], "祥子")
            self.assertEqual(hints[-1]["name"], "虎妞")
            self.assertEqual(hints[0]["priority"], "normal")
            self.assertEqual(hints[1]["priority"], "high")

    def test_prepare_turn_act_plot_prompt_handles_single_responder(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="laoshe.txt",
                novel_content_base64=base64.b64encode("祥子娶了虎妞。".encode("utf-8")).decode("ascii"),
                characters=["祥子", "虎妞"],
            )
            for name in ("祥子", "虎妞"):
                service.ingest_character_result(
                    payload["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: laoshe\n- core_identity: 人物\n".encode("utf-8")
                    ).decode("ascii"),
                )

            manifest = service._require_manifest(payload["run_id"])
            session = service.dialogue.create_session(
                manifest,
                mode="act",
                participants=["祥子", "虎妞"],
                controlled_character="祥子",
            )
            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(payload["run_id"], session["session_id"])
            )
            service.dialogue._set_session_scene_progress(
                raw_session,
                {
                    "present_participants": ["虎妞"],
                    "offstage_participants": ["祥子"],
                },
            )
            turn_payload = service.dialogue._build_turn_payload(
                manifest,
                raw_session,
                turn_id="turn-act-plot-single",
                message="第二天一早，虎妞催祥子出门办事。",
                message_kind="plot",
                speaker_override="场景提示",
            )
            response_rule = turn_payload["instructions"]["response_count_rule"]

            self.assertEqual(turn_payload["host_action"]["response_limit_hint"], 2)
            self.assertIn("exactly one concrete scene-level beat first", response_rule)
            self.assertIn("followed by 1-1 present-character reactions", response_rule)

    def test_reorder_plot_push_responses_moves_controlled_character_before_closing_line(self):
        payload = {
            "mode": "act",
            "input": {
                "message_kind": "plot",
                "controlled_character": "祥子",
            },
        }
        responses = [
            {"speaker": "虎妞", "message": "你先别磨叽。"},
            {"speaker": "祥子", "message": "我知道了。"},
        ]
        reordered = _reorder_plot_push_responses(responses, payload)
        self.assertEqual([item["speaker"] for item in reordered], ["祥子", "虎妞"])

        three_way = [
            {"speaker": "虎妞", "message": "走。"},
            {"speaker": "刘四", "message": "嗯。"},
            {"speaker": "祥子", "message": "好。"},
        ]
        reordered_three = _reorder_plot_push_responses(three_way, payload)
        self.assertEqual([item["speaker"] for item in reordered_three], ["虎妞", "祥子", "刘四"])

        with_scene_beat = [
            {"speaker": "虎妞", "message": "快走。"},
            {"speaker": "场景提示", "message": "院门忽然被人推开。"},
            {"speaker": "祥子", "message": "谁来了？"},
        ]
        reordered_with_scene = _reorder_plot_push_responses(with_scene_beat, payload)
        self.assertEqual(
            [item["speaker"] for item in reordered_with_scene],
            ["场景提示", "祥子", "虎妞"],
        )

    def test_plot_push_retries_when_first_reply_has_no_scene_event(self):
        payload = {
            "mode": "act",
            "input": {
                "message_kind": "plot",
                "controlled_character": "祥子",
            },
            "host_action": {"response_limit_hint": 3},
        }
        completion = Mock()
        completion.side_effect = [
            {"content": '[{"speaker":"虎妞","message":"你倒是快说话。"}]'},
            {
                "content": (
                    '[{"speaker":"场景提示","message":"院门忽然被撞开，刘四带着账本闯了进来。"},'
                    '{"speaker":"虎妞","message":"爹，你这是做什么？"}]'
                )
            },
        ]

        responses = generate_dialogue_responses(
            payload=payload,
            allowed_speakers=["祥子", "虎妞", "旁白", "场景提示"],
            temperature=0.2,
            max_tokens=500,
            chat_completion=completion,
            build_messages=lambda _payload, retry: [
                {"role": "user", "content": "retry" if retry else "first"}
            ],
            parse_responses=parse_dialogue_responses,
        )

        self.assertEqual(completion.call_count, 2)
        self.assertEqual(responses[0]["speaker"], "场景提示")
        self.assertIn("账本", responses[0]["message"])

    def test_truncated_reply_does_not_exceed_an_explicit_token_budget(self):
        payload = {"mode": "act", "input": {"message_kind": "dialogue"}}
        completion = Mock()
        completion.side_effect = [
            {
                "content": '[{"speaker":"祥子","message":"我今天拉了一整',
                "finish_reason": "length",
            },
            {"content": '[{"speaker":"祥子","message":"我今天拉了一整天车。"}]'},
        ]

        responses = generate_dialogue_responses(
            payload=payload,
            allowed_speakers=["祥子"],
            temperature=0.2,
            max_tokens=900,
            chat_completion=completion,
            build_messages=lambda _payload, retry: [
                {"role": "user", "content": "retry" if retry else "first"}
            ],
            parse_responses=parse_dialogue_responses,
        )

        self.assertEqual(completion.call_count, 2)
        self.assertEqual(responses[0]["message"], "我今天拉了一整天车。")
        first_budget = completion.call_args_list[0].args[2]
        second_budget = completion.call_args_list[1].args[2]
        self.assertEqual(first_budget, 900)
        self.assertEqual(second_budget, 900)

    def test_single_reply_uses_smaller_initial_token_budget(self):
        payload = {
            "mode": "act",
            "input": {"message_kind": "dialogue"},
            "host_action": {"response_limit_hint": 1},
        }
        completion = Mock(
            return_value={
                "content": '[{"speaker":"祥子","message":"好。"}]',
                "finish_reason": "stop",
            }
        )

        generate_dialogue_responses(
            payload=payload,
            allowed_speakers=["祥子"],
            temperature=0.2,
            max_tokens=0,
            chat_completion=completion,
            build_messages=lambda _payload, retry: [
                {"role": "user", "content": "retry" if retry else "first"}
            ],
            parse_responses=parse_dialogue_responses,
        )

        first_budget = completion.call_args_list[0].args[2]
        self.assertEqual(first_budget, 8192)

    def test_default_dialogue_budget_leaves_room_for_reasoning_before_json(self):
        payload = {"mode": "act", "input": {"message_kind": "dialogue"}}
        completion = Mock(
            return_value={
                "content": '[{"speaker":"祁子","message":"好。"}]',
                "finish_reason": "stop",
            }
        )

        generate_dialogue_responses(
            payload=payload,
            allowed_speakers=["祁子"],
            temperature=0.2,
            max_tokens=0,
            chat_completion=completion,
            build_messages=lambda _payload, _retry: [{"role": "user", "content": "reply"}],
            parse_responses=parse_dialogue_responses,
        )

        self.assertEqual(completion.call_args.args[2], 8192)

    def test_dialogue_retry_never_reduces_a_configured_token_budget(self):
        payload = {"mode": "act", "input": {"message_kind": "dialogue"}}
        completion = Mock(
            return_value={"content": "partial", "finish_reason": "length"}
        )

        with self.assertRaises(ValueError):
            generate_dialogue_responses(
                payload=payload,
                allowed_speakers=["祁子"],
                temperature=0.2,
                max_tokens=16000,
                chat_completion=completion,
                build_messages=lambda _payload, _retry: [{"role": "user", "content": "reply"}],
                parse_responses=parse_dialogue_responses,
            )

        self.assertEqual(
            [call.args[2] for call in completion.call_args_list], [16000, 16000]
        )

    def test_dialogue_respects_an_explicit_small_token_limit(self):
        payload = {
            "mode": "act",
            "input": {"message_kind": "dialogue"},
            "host_action": {"response_limit_hint": 1},
        }
        completion = Mock(
            return_value={"content": '[{"speaker":"祁子","message":"好。"}]'}
        )

        generate_dialogue_responses(
            payload=payload,
            allowed_speakers=["祁子"],
            temperature=0.2,
            max_tokens=2048,
            chat_completion=completion,
            build_messages=lambda _payload, _retry: [{"role": "user", "content": "reply"}],
            parse_responses=parse_dialogue_responses,
        )

        self.assertEqual(completion.call_args.args[2], 2048)

    def test_truncation_on_final_attempt_reports_the_token_limit(self):
        payload = {"mode": "act", "input": {"message_kind": "dialogue"}}
        completion = Mock(
            return_value={
                "content": '[{"speaker":"祥子","message":"我今天拉了一整',
                "finish_reason": "length",
            }
        )

        with self.assertRaises(ValueError) as ctx:
            generate_dialogue_responses(
                payload=payload,
                allowed_speakers=["祥子"],
                temperature=0.2,
                max_tokens=900,
                chat_completion=completion,
                build_messages=lambda _payload, retry: [
                    {"role": "user", "content": "retry" if retry else "first"}
                ],
                parse_responses=parse_dialogue_responses,
            )

        self.assertEqual(completion.call_count, 2)
        self.assertIn("max_tokens", str(ctx.exception))

    def test_decorated_speaker_names_map_back_to_the_allowed_roster(self):
        responses = parse_dialogue_responses(
            json.dumps(
                [
                    {"speaker": "祥子（车夫）", "message": "我拉车去了。"},
                    {"speaker": "虎妞:", "message": "你倒是快说话。"},
                    {"speaker": " 旁白 ", "message": "院里静了下来。"},
                ],
                ensure_ascii=False,
            ),
            ["祥子", "虎妞", "旁白", "场景提示"],
        )

        self.assertEqual(
            [item["speaker"] for item in responses],
            ["祥子", "虎妞", "旁白"],
        )

    def test_unknown_speakers_are_still_rejected(self):
        with self.assertRaises(ValueError):
            parse_dialogue_responses(
                json.dumps(
                    [{"speaker": "刘四", "message": "我可没答应。"}],
                    ensure_ascii=False,
                ),
                ["祥子", "虎妞"],
            )

    def test_relative_of_an_allowed_speaker_is_not_folded_into_that_speaker(self):
        """「虎妞的父亲」是另一个人，不能被归一化成「虎妞」。"""
        with self.assertRaises(ValueError):
            parse_dialogue_responses(
                json.dumps(
                    [{"speaker": "虎妞的父亲", "message": "我可没答应。"}],
                    ensure_ascii=False,
                ),
                ["虎妞", "祥子"],
            )

    def test_at_mention_only_targets_present_characters_and_retries_until_they_reply(self):
        payload = {
            "mode": "act",
            "input": {
                "message_kind": "dialogue",
                "controlled_character": "甲",
                "mention_targets": ["乙"],
            },
            "host_action": {"response_limit_hint": 2},
        }
        completion = Mock()
        completion.side_effect = [
            {"content": '[{"speaker":"丙","message":"我先说一句。"}]'},
            {"content": '[{"speaker":"乙","message":"你既然问我，我便直说。"}]'},
        ]

        responses = generate_dialogue_responses(
            payload=payload,
            allowed_speakers=["乙", "丙"],
            temperature=0.2,
            max_tokens=500,
            chat_completion=completion,
            build_messages=lambda _payload, retry: [
                {"role": "user", "content": "retry" if retry else "first"}
            ],
            parse_responses=parse_dialogue_responses,
        )

        self.assertEqual(completion.call_count, 2)
        self.assertEqual(responses[0]["speaker"], "乙")

    def test_turn_payload_keeps_only_in_scene_at_mentions(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            created = service.create_run(
                novel_name="mention.txt",
                novel_content_base64=base64.b64encode("甲乙丙同处一室。".encode("utf-8")).decode("ascii"),
                characters=["甲", "乙", "丙"],
            )
            for name in ("甲", "乙", "丙"):
                service.ingest_character_result(
                    created["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: mention\n- core_identity: 人物\n".encode("utf-8")
                    ).decode("ascii"),
                )
            manifest = service._require_manifest(created["run_id"])
            serialized = service.dialogue.create_session(
                manifest,
                mode="act",
                participants=["甲", "乙", "丙"],
                controlled_character="甲",
            )
            session = service.dialogue._read_json(
                service.dialogue._session_file(created["run_id"], serialized["session_id"])
            )
            service.dialogue._set_session_scene_progress(
                session,
                {
                    "present_participants": ["甲", "乙"],
                    "offstage_participants": ["丙"],
                },
            )

            turn_payload = service.dialogue._build_turn_payload(
                manifest,
                session,
                turn_id="turn-mention",
                message="@甲 先问问自己，@乙,你怎么看？@丙 也说一句。",
            )

        self.assertEqual(turn_payload["input"]["mention_targets"], ["乙"])
        self.assertEqual(turn_payload["speaker_plan"]["mention_targets"], ["乙"])
        self.assertIn("Every mentioned character", turn_payload["instructions"]["mention_rule"])
        llm_messages = build_dialogue_llm_messages(turn_payload)
        user_payload = json.loads(llm_messages[-1]["content"])
        self.assertEqual(user_payload["mention_targets"], ["乙"])

    def test_prepare_turn_filters_departed_participants_from_active_pool(self):
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
                characters=["林黛玉", "贾宝玉", "薛宝钗"],
            )
            for name in ("林黛玉", "贾宝玉", "薛宝钗"):
                service.ingest_character_result(
                    payload["run_id"],
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
                    payload["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉", "薛宝钗"],
                )

            service.prepare_dialogue_turn(
                payload["run_id"],
                session_id=session["session_id"],
                message="先铺一下场子。",
                message_kind="narration",
            )
            service.ingest_dialogue_turn(
                payload["run_id"],
                session_id=session["session_id"],
                responses=[
                    {"speaker": "旁白", "message": "薛宝钗告退回房，先离开了。"},
                    {"speaker": "林黛玉", "message": "那便先由我们说。"},
                ],
            )
            prepared = service.prepare_dialogue_turn(
                payload["run_id"],
                session_id=session["session_id"],
                message="你们接着说。",
            )
            active = prepared.get("pending_turn_summary", {}).get(
                "active_participants", []
            )
            self.assertIn("林黛玉", active)
            self.assertIn("贾宝玉", active)
            self.assertNotIn("薛宝钗", active)

    def test_ingest_turn_updates_scene_progress_and_future_active_participants(self):
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
                characters=["林黛玉", "贾宝玉", "薛宝钗"],
            )
            for name in ("林黛玉", "贾宝玉", "薛宝钗"):
                service.ingest_character_result(
                    payload["run_id"],
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
            ), patch.object(
                service, "_generate_dialogue_scene_progress", return_value={}
            ):
                session = service.create_dialogue_session(
                    payload["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉", "薛宝钗"],
                )

            service.prepare_dialogue_turn(
                payload["run_id"],
                session_id=session["session_id"],
                message="你们先去私人影院，我晚点回家。",
            )
            with patch.object(
                service,
                "_generate_dialogue_scene_progress",
                return_value={
                    "present_participants": ["林黛玉", "贾宝玉"],
                    "offstage_participants": ["薛宝钗"],
                    "time_hint": "夜里",
                    "location": "私人影院",
                    "progression_note": "地点已经转到私人影院，只剩林黛玉和贾宝玉同场，薛宝钗暂时留在家中。",
                    "should_offer_scene_shift": False,
                    "scene_shift_reason": "",
                },
            ):
                updated = service.ingest_dialogue_turn(
                    payload["run_id"],
                    session_id=session["session_id"],
                    responses=[
                        {"speaker": "林黛玉", "message": "那便只我们先过去。"},
                        {"speaker": "贾宝玉", "message": "我陪你一起。"},
                    ],
                )

            self.assertEqual(updated["scene_progress"]["location"], "私人影院")
            self.assertEqual(updated["scene_progress"]["time_hint"], "夜里")
            self.assertEqual(
                updated["scene_progress"]["present_participants"], ["林黛玉", "贾宝玉"]
            )
            self.assertEqual(
                updated["scene_progress"]["offstage_participants"], ["薛宝钗"]
            )
            self.assertTrue(updated["scene_progress"]["atmosphere_summary"])
            self.assertGreater(updated["scene_progress"]["beat_maturity"], 0)
            self.assertTrue(updated["scene_progress"]["world_tension_summary"])
            self.assertIn("夜里", updated["session_memory_summary"]["scene_frame"])
            self.assertIn("薛宝钗", updated["session_memory_summary"]["cast"])

            prepared = service.prepare_dialogue_turn(
                payload["run_id"],
                session_id=session["session_id"],
                message="你们继续看电影。",
            )
            active = prepared.get("pending_turn_summary", {}).get(
                "active_participants", []
            )
            self.assertEqual(active, ["林黛玉", "贾宝玉"])

    def test_scene_progress_can_flag_natural_scene_shift_after_longer_turn(self):
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
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    payload["run_id"],
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
            ), patch.object(
                service, "_generate_dialogue_scene_progress", return_value={}
            ):
                session = service.create_dialogue_session(
                    payload["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉"],
                )

            service.prepare_dialogue_turn(
                payload["run_id"],
                session_id=session["session_id"],
                message="这一幕差不多说开了。",
            )
            with patch.object(
                service,
                "_generate_dialogue_scene_progress",
                return_value={
                    "present_participants": ["林黛玉", "贾宝玉"],
                    "offstage_participants": [],
                    "time_hint": "夜深",
                    "location": "花厅",
                    "progression_note": "这一幕已经把话说透，适合顺势转入下一幕。",
                    "should_offer_scene_shift": True,
                    "scene_shift_reason": "情绪和信息都已经落定，适合自然切到下一幕。",
                },
            ):
                updated = service.ingest_dialogue_turn(
                    payload["run_id"],
                    session_id=session["session_id"],
                    responses=[
                        {"speaker": "林黛玉", "message": "那这句话便到这里。"},
                        {"speaker": "贾宝玉", "message": "我们也该换个地方再说。"},
                    ],
                )

            self.assertTrue(updated["scene_progress"]["should_offer_scene_shift"])
            self.assertIn("下一幕", updated["scene_progress"]["scene_shift_reason"])
            self.assertGreaterEqual(updated["scene_progress"]["beat_maturity"], 70)
            self.assertTrue(updated["scene_progress"]["world_tension_summary"])
            self.assertIn("转场提示", updated["session_memory_summary"]["scene_frame"])

    def test_scene_progress_keeps_offstage_cast_until_explicit_return(self):
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
                characters=["林黛玉", "贾宝玉", "薛宝钗"],
            )
            for name in ("林黛玉", "贾宝玉", "薛宝钗"):
                service.ingest_character_result(
                    payload["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )
            manifest = service._require_manifest(payload["run_id"])
            session = service.dialogue.create_session(
                manifest,
                mode="observe",
                participants=["林黛玉", "贾宝玉", "薛宝钗"],
            )
            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(payload["run_id"], session["session_id"])
            )
            raw_session["history"] = [
                {
                    "speaker": "场景提示",
                    "message": "薛宝钗先回房，只剩林黛玉和贾宝玉在花厅。",
                    "ts": "2026-05-12T00:00:00Z",
                },
                {
                    "speaker": "林黛玉",
                    "message": "我们先把这句话说完。",
                    "ts": "2026-05-12T00:00:01Z",
                },
            ]
            service.dialogue._set_session_scene_progress(
                raw_session,
                {
                    "present_participants": ["林黛玉", "贾宝玉"],
                    "offstage_participants": ["薛宝钗"],
                    "location": "花厅",
                    "time_hint": "夜里",
                },
            )

            merged = service.dialogue._merge_scene_progress_state(
                raw_session,
                {
                    "present_participants": ["林黛玉", "贾宝玉", "薛宝钗"],
                    "offstage_participants": [],
                    "location": "花厅",
                },
            )

            self.assertEqual(merged["present_participants"], ["林黛玉", "贾宝玉"])
            self.assertEqual(merged["offstage_participants"], ["薛宝钗"])

    def test_scene_progress_time_hint_moves_forward_without_regressing(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            session = {
                "participants": ["林黛玉", "贾宝玉"],
                "scene_card": {"time_hint": "傍晚"},
                "history": [
                    {
                        "speaker": "场景提示",
                        "message": "过了一会，灯都亮了。",
                        "ts": "2026-05-12T00:00:00Z",
                    },
                ],
                "state": service.dialogue._empty_session_state(),
            }
            service.dialogue._set_session_scene_progress(
                session,
                {
                    "present_participants": ["林黛玉", "贾宝玉"],
                    "offstage_participants": [],
                    "time_hint": "傍晚",
                    "location": "花厅",
                },
            )

            advanced = service.dialogue._merge_scene_progress_state(
                session, {"time_hint": ""}
            )
            self.assertEqual(advanced["time_hint"], "晚上")

            regressed = service.dialogue._merge_scene_progress_state(
                session,
                {"time_hint": "下午", "present_participants": ["林黛玉", "贾宝玉"]},
            )
            self.assertEqual(regressed["time_hint"], "晚上")

    def test_scene_progress_restores_offstage_cast_after_explicit_return(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            session = {
                "participants": ["林黛玉", "贾宝玉", "薛宝钗"],
                "history": [
                    {
                        "speaker": "场景提示",
                        "message": "薛宝钗先回房，只剩林黛玉和贾宝玉在花厅。",
                        "ts": "2026-05-12T00:00:00Z",
                    },
                    {
                        "speaker": "场景提示",
                        "message": "过了一会，薛宝钗推门进来，轻声问他们可说完了。",
                        "ts": "2026-05-12T00:01:00Z",
                    },
                ],
                "state": service.dialogue._empty_session_state(),
            }
            service.dialogue._set_session_scene_progress(
                session,
                {
                    "present_participants": ["林黛玉", "贾宝玉"],
                    "offstage_participants": ["薛宝钗"],
                    "location": "花厅",
                    "time_hint": "夜里",
                },
            )

            merged = service.dialogue._merge_scene_progress_state(
                session,
                {
                    "present_participants": ["林黛玉", "贾宝玉", "薛宝钗"],
                    "offstage_participants": [],
                    "location": "花厅",
                },
            )

            self.assertEqual(
                merged["present_participants"], ["林黛玉", "贾宝玉", "薛宝钗"]
            )
            self.assertEqual(merged["offstage_participants"], [])

    def test_scene_progress_offers_scene_shift_after_departure_reduces_cast(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            session = {
                "participants": ["林黛玉", "贾宝玉", "薛宝钗"],
                "history": [
                    {
                        "speaker": "场景提示",
                        "message": "薛宝钗先回房，只剩林黛玉与贾宝玉留在花厅。",
                        "ts": "2026-05-12T00:00:00Z",
                    },
                    {
                        "speaker": "林黛玉",
                        "message": "那便到这里吧。",
                        "ts": "2026-05-12T00:00:01Z",
                    },
                    {
                        "speaker": "贾宝玉",
                        "message": "我送你回去。",
                        "ts": "2026-05-12T00:00:02Z",
                    },
                    {
                        "speaker": "场景提示",
                        "message": "花厅里一下静了下来。",
                        "ts": "2026-05-12T00:00:03Z",
                    },
                ],
                "state": service.dialogue._empty_session_state(),
            }
            service.dialogue._set_session_event_signals(
                session,
                {
                    "recent": [
                        {
                            "kind": "cast_exit",
                            "scope": "scene",
                            "actor": "薛宝钗",
                            "target": "",
                            "cue": "薛宝钗离场",
                            "source": "runtime",
                            "should_inline": False,
                            "ts": "2026-05-12T00:00:00Z",
                        }
                    ],
                    "by_type": {},
                    "updated_at": "2026-05-12T00:00:00Z",
                },
            )
            service.dialogue._set_session_scene_progress(
                session,
                {
                    "present_participants": ["林黛玉", "贾宝玉"],
                    "offstage_participants": ["薛宝钗"],
                    "time_hint": "夜里",
                    "location": "花厅",
                    "progression_note": "",
                    "should_offer_scene_shift": False,
                    "scene_shift_reason": "",
                },
            )

            derived = service.dialogue._derive_scene_progress_state(
                session, service.dialogue._serialize_transcript(session)
            )

            self.assertTrue(derived["should_offer_scene_shift"])
            self.assertIn("薛宝钗已经离场", derived["scene_shift_reason"])
