from __future__ import annotations

from copy import deepcopy
import json
import unittest

from src.web.chat.cache_stats import (
    empty_generation_cache_stats,
    record_generation_cache_observation,
    summarize_completion_results,
)
from src.web.chat.helpers import build_dialogue_llm_messages


class ChatCacheStatsTests(unittest.TestCase):
    def test_summarizes_cache_usage_across_generation_retries(self) -> None:
        summary = summarize_completion_results(
            [
                {
                    "provider": "openai",
                    "model": "gpt-test",
                    "cache_usage": {
                        "observable": True,
                        "input_tokens": 100,
                        "hit_tokens": 40,
                        "miss_tokens": 60,
                        "creation_tokens": 0,
                    },
                },
                {
                    "provider": "openai",
                    "model": "gpt-test",
                    "cache_usage": {
                        "observable": True,
                        "input_tokens": 50,
                        "hit_tokens": 0,
                        "miss_tokens": 50,
                        "creation_tokens": 0,
                    },
                },
            ]
        )

        self.assertTrue(summary["observed"])
        self.assertEqual(summary["status"], "hit")
        self.assertEqual(summary["input_tokens"], 150)
        self.assertEqual(summary["cache_read_tokens"], 40)
        self.assertEqual(summary["cache_miss_tokens"], 110)
        self.assertEqual(summary["hit_rate"], 0.266667)
        self.assertEqual(summary["attempt_count"], 2)

    def test_missing_provider_metrics_stays_unsupported(self) -> None:
        summary = summarize_completion_results(
            [{"provider": "ollama", "model": "local", "prompt_tokens": 80}]
        )

        self.assertFalse(summary["observed"])
        self.assertEqual(summary["status"], "unsupported")
        self.assertIsNone(summary["hit_rate"])

    def test_summarizes_tokens_latency_cost_and_parse_retries(self) -> None:
        summary = summarize_completion_results(
            [
                {
                    "provider": "openai",
                    "model": "gpt-test",
                    "prompt_tokens": 100,
                    "completion_tokens": 20,
                    "elapsed_time": 1.25,
                    "cost": 0.001,
                },
                {
                    "provider": "openai",
                    "model": "gpt-test",
                    "prompt_tokens": 60,
                    "completion_tokens": 15,
                    "elapsed_time": 0.75,
                    "cost": 0.002,
                },
            ]
        )

        self.assertEqual(summary["prompt_tokens"], 160)
        self.assertEqual(summary["completion_tokens"], 35)
        self.assertEqual(summary["total_tokens"], 195)
        self.assertEqual(summary["elapsed_seconds"], 2.0)
        self.assertEqual(summary["cost_usd"], 0.003)
        self.assertEqual(summary["attempt_count"], 2)
        self.assertEqual(summary["retry_count"], 1)

    def test_records_latest_turn_and_lifetime_session_totals(self) -> None:
        session = {"generation_cache_stats": empty_generation_cache_stats()}
        record_generation_cache_observation(
            session,
            {
                "provider": "unknown",
                "model": "unknown",
                "observed": False,
                "attempt_count": 1,
            },
            turn_id="turn-1",
            updated_at="2026-07-20T10:00:00Z",
        )
        record_generation_cache_observation(
            session,
            {
                "provider": "openai",
                "model": "gpt-test",
                "observed": True,
                "input_tokens": 200,
                "cache_read_tokens": 120,
                "cache_write_tokens": 0,
                "cache_miss_tokens": 80,
                "attempt_count": 1,
            },
            turn_id="turn-2",
            updated_at="2026-07-20T10:01:00Z",
        )

        stats = session["generation_cache_stats"]
        self.assertEqual(stats["latest"]["turn_id"], "turn-2")
        self.assertEqual(stats["latest"]["hit_rate"], 0.6)
        self.assertEqual(len(stats["turns"]), 2)
        self.assertEqual(stats["session"]["status"], "partial")
        self.assertEqual(stats["session"]["total_turns"], 2)
        self.assertEqual(stats["session"]["observed_turns"], 1)
        self.assertEqual(stats["session"]["hit_rate"], 0.6)

    def test_records_session_generation_totals_and_model_breakdown(self) -> None:
        session = {"generation_cache_stats": empty_generation_cache_stats()}
        record_generation_cache_observation(
            session,
            {
                "provider": "openai",
                "model": "gpt-a",
                "prompt_tokens": 100,
                "completion_tokens": 25,
                "total_tokens": 125,
                "elapsed_seconds": 1.5,
                "cost_usd": 0.004,
                "attempt_count": 2,
                "retry_count": 1,
            },
            turn_id="turn-1",
            updated_at="2026-07-20T10:00:00Z",
        )
        record_generation_cache_observation(
            session,
            {
                "provider": "openai",
                "model": "gpt-b",
                "prompt_tokens": 80,
                "completion_tokens": 20,
                "total_tokens": 100,
                "elapsed_seconds": 0.5,
                "cost_usd": 0.001,
                "attempt_count": 1,
            },
            turn_id="turn-2",
            updated_at="2026-07-20T10:01:00Z",
        )

        totals = session["generation_cache_stats"]["session"]
        self.assertEqual(totals["total_tokens"], 225)
        self.assertEqual(totals["elapsed_seconds"], 2.0)
        self.assertEqual(totals["average_elapsed_seconds"], 1.0)
        self.assertEqual(totals["cost_usd"], 0.005)
        self.assertEqual(totals["attempt_count"], 3)
        self.assertEqual(totals["retry_count"], 1)
        self.assertEqual(totals["models"], {"gpt-a": 2, "gpt-b": 1})

    def test_repeated_turn_id_does_not_double_count_session_totals(self) -> None:
        session = {"generation_cache_stats": empty_generation_cache_stats()}
        observation = {
            "provider": "openai",
            "model": "gpt-test",
            "observed": True,
            "input_tokens": 100,
            "cache_read_tokens": 40,
            "cache_write_tokens": 0,
            "cache_miss_tokens": 60,
            "prompt_tokens": 80,
            "completion_tokens": 20,
            "total_tokens": 100,
            "elapsed_seconds": 1.5,
            "cost_usd": 0.01,
            "attempt_count": 1,
        }
        record_generation_cache_observation(
            session, observation, turn_id="turn-1", updated_at="2026-07-20T10:00:00Z"
        )
        record_generation_cache_observation(
            session, observation, turn_id="turn-1", updated_at="2026-07-20T10:00:01Z"
        )

        stats = session["generation_cache_stats"]
        self.assertEqual(len(stats["turns"]), 1)
        self.assertEqual(stats["session"]["total_turns"], 1)
        self.assertEqual(stats["session"]["observed_turns"], 1)
        self.assertEqual(stats["session"]["prompt_tokens"], 80)
        self.assertEqual(stats["session"]["input_tokens"], 100)
        self.assertEqual(stats["session"]["cache_read_tokens"], 40)
        self.assertEqual(stats["session"]["attempt_count"], 1)
        self.assertEqual(stats["session"]["models"], {"gpt-test": 1})

    def test_dialogue_prompt_keeps_persona_in_stable_cache_prefix(self) -> None:
        payload = {
            "mode": "observe",
            "input": {
                "message_kind": "dialogue",
                "speaker": "User",
                "message": "你怎么不说话？",
                "participants": ["林黛玉"],
                "active_participants": ["林黛玉"],
            },
            "persona_contexts": [
                {
                    "name": "林黛玉",
                    "preview": {
                        "display_name": "林黛玉",
                        "speech_style": "含蓄清冷",
                    },
                    "profile": {
                        "core_identity": "寄居贾府的少女",
                        "speech_style": "含蓄清冷",
                        "preference_like": ["诗词"],
                    },
                    "session_snapshot": {"mood": "戒备"},
                }
            ],
            "history": [{"speaker": "User", "message": "先坐。"}],
            "memory_context": {"session_summary": {"recap": "刚刚入座"}},
            "relation_context": {"relations_excerpt": "仍有试探"},
            "scene_card": {"location": "潇湘馆"},
            "instructions": {
                "generation_goal": "保持人物一致。",
                "mode_rule": "角色继续当前场景。",
                "speaker_rule": "用户是观察者。",
                "response_style": "保持含蓄。",
                "scene_rule": "留在潇湘馆。",
                "progression_rule": "当前气氛安静。",
                "response_count_rule": "返回一条回复。",
            },
            "host_action": {
                "response_limit_hint": 1,
                "output_rule": "只返回角色回复。",
                "expected_output": [{"speaker": "角色名", "message": "回复"}],
            },
            "host_prompt_brief": "让林黛玉继续说话。",
        }
        changed = deepcopy(payload)
        changed["input"]["message"] = "外面下雨了。"
        changed["history"] = [{"speaker": "User", "message": "换个话题。"}]
        changed["memory_context"] = {"session_summary": {"recap": "窗外落雨"}}
        changed["instructions"]["progression_rule"] = "雨声越来越近。"
        changed["persona_contexts"][0]["session_snapshot"] = {"mood": "松弛"}

        first = build_dialogue_llm_messages(payload)
        second = build_dialogue_llm_messages(changed)

        self.assertEqual(len(first), 3)
        self.assertTrue(first[0]["cache_static"])
        self.assertEqual(first[0], second[0])
        self.assertIn("林黛玉", first[0]["content"])
        self.assertIn("含蓄清冷", first[0]["content"])
        self.assertNotEqual(first[1]["content"], second[1]["content"])
        self.assertNotEqual(first[2]["content"], second[2]["content"])
        dynamic_payload = json.loads(second[2]["content"])
        self.assertEqual(
            dynamic_payload["active_persona_state"][0]["session_snapshot"]["mood"],
            "松弛",
        )


if __name__ == "__main__":
    unittest.main()
