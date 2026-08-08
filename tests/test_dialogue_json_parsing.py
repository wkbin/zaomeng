from __future__ import annotations

import json
import unittest

from src.web.chat.helpers import build_dialogue_llm_messages, parse_dialogue_responses


class DialogueJsonParsingTests(unittest.TestCase):
    def test_parse_dialogue_responses_ignores_explanation_before_json(self) -> None:
        responses = parse_dialogue_responses(
            '我会按格式回答。\n[{"speaker":"甲","message":"我知道了。"}]',
            ["甲"],
        )
        self.assertEqual(responses, [{"speaker": "甲", "message": "我知道了。"}])

    def test_parse_dialogue_responses_ignores_thinking_and_code_fence(self) -> None:
        content = "<think>先判断人物关系。</think>\n```json\n[{\"speaker\":\"甲\",\"message\":\"继续。\"}]\n```"
        responses = parse_dialogue_responses(content, ["甲"])
        self.assertEqual(responses, [{"speaker": "甲", "message": "继续。"}])

    def test_parse_dialogue_responses_prefers_response_array_over_other_json(self) -> None:
        content = '{"analysis":"ignored"}\n[{"speaker":"甲","message":"括号 { 和 [ 都在台词里。"}]\n{"done":true}'
        responses = parse_dialogue_responses(content, ["甲"])
        self.assertEqual(responses[0]["message"], "括号 { 和 [ 都在台词里。")

    def test_parse_dialogue_responses_rejects_truncated_json(self) -> None:
        with self.assertRaisesRegex(ValueError, "not valid JSON"):
            parse_dialogue_responses('[{"speaker":"甲","message":"未完成"}', ["甲"])

    def test_parse_dialogue_responses_accepts_literal_newlines_in_messages(self) -> None:
        content = """[
  {
    "speaker": "祥子",
    "message": "你好
这是第二行"
  }
]"""
        responses = parse_dialogue_responses(content, ["祥子", "小福子"])
        self.assertEqual(len(responses), 1)
        self.assertEqual(responses[0]["speaker"], "祥子")
        self.assertIn("第二行", responses[0]["message"])

    def test_parse_dialogue_responses_keeps_inner_thought(self) -> None:
        responses = parse_dialogue_responses(
            '[{"speaker":"甲","message":"你走吧。","inner_thought":"别走。"}]',
            ["甲"],
        )
        self.assertEqual(
            responses,
            [{"speaker": "甲", "message": "你走吧。", "inner_thought": "别走。"}],
        )

    def test_parse_dialogue_responses_trims_inner_thought_to_50_chars(self) -> None:
        long_thought = "心" * 60
        responses = parse_dialogue_responses(
            json.dumps(
                [
                    {
                        "speaker": "甲",
                        "message": "你说。",
                        "inner_thought": long_thought,
                    }
                ],
                ensure_ascii=False,
            ),
            ["甲"],
        )
        self.assertLessEqual(len(responses[0]["inner_thought"]), 50)

    def test_build_dialogue_llm_messages_enables_inner_thought_contract(self) -> None:
        messages = build_dialogue_llm_messages(
            {
                "include_inner_thoughts": True,
                "mode": "observe",
                "input": {
                    "message_kind": "dialogue",
                    "participants": ["甲"],
                    "active_participants": ["甲"],
                },
                "host_action": {"response_limit_hint": 1},
            }
        )
        system_text = " ".join(
            str(message.get("content", ""))
            for message in messages
            if message.get("role") == "system"
        )
        self.assertIn("inner_thought", system_text)
        self.assertIn("第一人称", system_text)
        self.assertIn("没说出口", system_text)
        self.assertIn("50 字", system_text)

    def test_inner_thought_rule_lives_in_cache_static_system_message(self) -> None:
        base_payload = {
            "mode": "observe",
            "input": {
                "message_kind": "dialogue",
                "participants": ["甲"],
                "active_participants": ["甲"],
            },
            "host_action": {"response_limit_hint": 1},
        }
        enabled = build_dialogue_llm_messages(
            {**base_payload, "include_inner_thoughts": True}
        )
        disabled = build_dialogue_llm_messages(
            {**base_payload, "include_inner_thoughts": False}
        )

        enabled_static = next(
            message
            for message in enabled
            if message.get("cache_static") is True
        )
        disabled_static = next(
            message
            for message in disabled
            if message.get("cache_static") is True
        )
        self.assertIn("inner_thought", str(enabled_static.get("content", "")))
        self.assertNotIn("inner_thought", str(disabled_static.get("content", "")))


if __name__ == "__main__":
    unittest.main()
