from __future__ import annotations

import unittest

from src.web.chat.turn_memory import build_turn_memory_context, search_turn_memory_hits


class MemoryStoreStub:
    def __init__(self) -> None:
        self.query = ""

    def search_long_term_memory(self, session_id: str, query: str, top_k: int = 3):
        self.query = query
        return [
            {
                "text": "林黛玉此前答应把误会说开",
                "score": 0.87654,
                "speaker": "林黛玉",
                "target": "贾宝玉",
                "kind": "dialogue",
            },
            {"text": "", "score": 1.0},
        ]


class TurnMemoryTests(unittest.TestCase):
    def test_search_builds_contextual_query_and_normalizes_hits(self):
        store = MemoryStoreStub()

        hits = search_turn_memory_hits(
            store,
            session_id="dlg-1",
            speaker="林黛玉",
            message="今晚把话说清楚。",
            participants=["林黛玉", "贾宝玉"],
            active_participants=["贾宝玉"],
            scene_card={"title": "花厅夜话", "location": "花厅"},
            session_summary={"current_goal": "把误会说开"},
            scene_progress={"world_tension_summary": "两人仍在试探"},
        )

        self.assertIn("林黛玉", store.query)
        self.assertIn("花厅夜话", store.query)
        self.assertIn("把误会说开", store.query)
        self.assertIn("两人仍在试探", store.query)
        self.assertEqual(len(hits), 1)
        self.assertEqual(hits[0]["score"], 0.8765)

    def test_context_compacts_empty_and_zero_values(self):
        context = build_turn_memory_context(
            state_summary={
                "summary": "旧线摘要",
                "key_points": ["约定今晚见面", ""],
                "compressed_turns": 3,
                "recent_turns_kept": 0,
            },
            scene_progress={
                "location": "花厅",
                "present_participants": ["林黛玉", "贾宝玉"],
                "should_offer_scene_shift": False,
            },
            character_snapshots={
                "林黛玉": {"mood": "迟疑", "last_event": "回避了问题"},
                "": {"mood": "ignored"},
            },
            relation_delta={
                "林黛玉_贾宝玉": {"trust": 1, "hostility": 0, "last_event": ""},
            },
            event_signals=[{"kind": "relationship_shift", "cue": "信任回升"}],
            session_summary={"recap": "两人仍在交谈"},
            memory_hits=[{"text": "旧约定"}],
        )

        self.assertNotIn("recent_turns_kept", context["archived_summary"])
        self.assertNotIn("should_offer_scene_shift", context["scene_progress"])
        self.assertEqual(context["relation_delta"]["林黛玉_贾宝玉"], {"trust": 1})
        self.assertEqual(context["character_snapshots"]["林黛玉"]["mood"], "迟疑")


if __name__ == "__main__":
    unittest.main()
