from __future__ import annotations

import unittest

from src.web.chat.memory_summary import build_session_memory_summary


class MemorySummaryTests(unittest.TestCase):
    def test_builder_combines_scene_relation_and_semantic_context(self):
        session = {
            "mode": "insert",
            "participants": ["林黛玉", "贾宝玉", "薛宝钗"],
            "self_insert": {"display_name": "阿青", "scene_identity": "远客"},
            "scene_card": {
                "title": "花厅夜话",
                "location": "花厅",
                "atmosphere": "安静",
                "scene_drive": "把误会说开",
            },
            "history": [
                {"speaker": "林黛玉", "message": "这句话还没有说清。"},
                {"speaker": "贾宝玉", "message": "我答应今晚会说明白。"},
            ],
            "updated_at": "2026-07-20T00:00:00Z",
        }
        transcript = [
            {"role": "character", "speaker": "林黛玉", "message": "这句话还没有说清。"},
            {"role": "character", "speaker": "贾宝玉", "message": "我答应今晚会说明白。"},
        ]
        scene_progress = {
            "present_participants": ["林黛玉", "贾宝玉"],
            "offstage_participants": ["薛宝钗"],
            "time_hint": "夜深",
            "location": "花厅",
            "atmosphere_summary": "安静下来",
            "world_tension_summary": "误会仍未说开",
        }

        summary = build_session_memory_summary(
            session,
            transcript,
            scene_progress=scene_progress,
            relation_delta={"林黛玉_贾宝玉": {"trust": 1, "ambiguity": -1}},
            event_signals={"recent": []},
            semantic_hint="两人此前约定不再回避",
        )

        self.assertIn("阿青", summary["perspective"])
        self.assertIn("夜深", summary["perspective"])
        self.assertIn("薛宝钗", summary["cast"])
        self.assertIn("长期记忆", summary["relation_drift"])
        self.assertIn("信任+1", summary["relation_drift"])
        self.assertIn("花厅夜话", summary["scene_frame"])
        self.assertIn("今晚会说明白", summary["pending_commitments"])

    def test_builder_uses_carried_summary_before_new_history_exists(self):
        session = {
            "mode": "observe",
            "participants": ["林黛玉", "贾宝玉"],
            "history": [],
            "carried_memory_summary": {
                "recap": "上一幕两人刚刚和解",
                "cast": "林黛玉与贾宝玉仍在场",
                "relation_drift": "信任正在恢复",
                "world": "天已经亮了",
            },
        }

        summary = build_session_memory_summary(
            session,
            [],
            scene_progress={},
            relation_delta={},
            event_signals={},
        )

        self.assertTrue(summary["recap"].startswith("承接旧线："))
        self.assertEqual(summary["cast"], "林黛玉与贾宝玉仍在场")
        self.assertEqual(summary["relation_drift"], "信任正在恢复")
        self.assertEqual(summary["world"], "天已经亮了")


if __name__ == "__main__":
    unittest.main()
