from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from src.web.chat.scene_progress import derive_scene_progress_state, merge_scene_progress_state
from src.web.chat.scene_signals import infer_time_hint
from src.web.chat.state_utils import empty_session_state, set_session_scene_progress


class SceneProgressTests(unittest.TestCase):
    def test_explicit_scene_switch_resets_time_location_and_maturity(self):
        from src.web.chat.service import DialogueService

        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            persona_dir = root / "personas" / "甲"
            persona_dir.mkdir(parents=True)
            profile_path = persona_dir / "PROFILE.md"
            profile_path.write_text(
                "# PROFILE\n- name: 甲\n- core_identity: 测试角色\n",
                encoding="utf-8",
            )
            manifest = {
                "run_id": "run-scene-switch",
                "novel_id": "novel-1",
                "artifact_index": {
                    "characters": [
                        {
                            "name": "甲",
                            "profile_file": str(profile_path),
                            "persona_dir": str(persona_dir),
                        }
                    ]
                },
            }
            dialogue = DialogueService(root / "runs")
            created = dialogue.create_session(
                manifest,
                mode="observe",
                participants=["甲"],
                scene_profile={
                    "scene_card_id": "scene-night",
                    "title": "旧宅深夜",
                    "location": "旧宅",
                    "time_hint": "深夜",
                    "atmosphere": "压抑",
                },
            )
            session_path = dialogue._session_file(
                "run-scene-switch", created["session_id"]
            )
            raw = dialogue._read_json(session_path)
            old_progress = dialogue._session_scene_progress(raw)
            old_progress.update(
                {
                    "time_hint": "深夜",
                    "location": "旧宅",
                    "beat_maturity": 92,
                    "should_offer_scene_shift": True,
                }
            )
            dialogue._set_session_scene_progress(raw, old_progress)
            dialogue._set_session_event_signals(
                raw,
                dialogue._merge_event_signals_state(
                    raw,
                    [
                        {
                            "kind": "time_change",
                            "scope": "scene",
                            "actor": "场景提示",
                            "cue": "夜已深",
                            "source": "test",
                            "time_hint": "深夜",
                        },
                        {
                            "kind": "scene_transition",
                            "scope": "scene",
                            "actor": "场景提示",
                            "cue": "众人在旧宅",
                            "source": "test",
                            "location_hint": "旧宅",
                        },
                    ],
                ),
            )
            dialogue._write_json(session_path, raw)

            switched = dialogue.update_scene_card(
                "run-scene-switch",
                created["session_id"],
                scene_profile={
                    "scene_card_id": "scene-morning",
                    "title": "次日花园",
                    "location": "花园",
                    "time_hint": "清晨",
                    "atmosphere": "清亮",
                },
                transition_message="次日众人转到花园。",
            )

            self.assertEqual(switched["scene_progress"]["time_hint"], "清晨")
            self.assertEqual(switched["scene_progress"]["location"], "花园")
            self.assertEqual(switched["scene_progress"]["beat_maturity"], 0)
            self.assertFalse(switched["scene_progress"]["should_offer_scene_shift"])

            persisted = dialogue._read_json(session_path)
            derived = dialogue._derive_scene_progress_state(
                persisted, dialogue._serialize_transcript(persisted)
            )
            self.assertEqual(derived["time_hint"], "清晨")
            self.assertEqual(derived["location"], "花园")

    def test_character_future_or_duration_reference_does_not_move_scene_clock(self):
        transcript = [
            {
                "role": "character",
                "speaker": "袭人",
                "message": "明儿剥一晚上核桃，明晚再同你说。",
            }
        ]

        self.assertEqual(infer_time_hint(transcript), "")

    def test_character_current_time_cue_can_move_scene_clock(self):
        transcript = [
            {
                "role": "character",
                "speaker": "袭人",
                "message": "天都黑了，你还不回去？",
            }
        ]

        self.assertEqual(infer_time_hint(transcript), "晚上")

    def test_time_drift_only_consumes_new_scene_evidence_once(self):
        state = empty_session_state(1)
        set_session_scene_progress(
            state,
            {
                "present_participants": ["林黛玉", "贾宝玉"],
                "offstage_participants": [],
                "time_hint": "傍晚",
                "location": "花厅",
            },
            updated_at="2026-07-20T00:00:00Z",
        )
        history = [
            {
                "speaker": "场景提示",
                "message": "过了一会，廊下重新安静下来。",
                "ts": "2026-07-20T00:01:00Z",
            }
        ]
        session = {
            "participants": ["林黛玉", "贾宝玉"],
            "scene_card": {"time_hint": "傍晚"},
            "history": history,
            "state": state,
        }
        transcript = [
            {"role": "scene", "speaker": "场景提示", "message": history[0]["message"]}
        ]

        advanced = derive_scene_progress_state(session, transcript, state_version=1)
        self.assertEqual(advanced["time_hint"], "黄昏")

        set_session_scene_progress(
            state,
            advanced,
            updated_at="2026-07-20T00:02:00Z",
        )
        repeated = derive_scene_progress_state(session, transcript, state_version=1)
        self.assertEqual(repeated["time_hint"], "黄昏")

    def test_merge_keeps_departed_participant_offstage(self):
        state = empty_session_state(1)
        set_session_scene_progress(
            state,
            {
                "present_participants": ["林黛玉", "贾宝玉"],
                "offstage_participants": ["薛宝钗"],
                "time_hint": "夜里",
                "location": "花厅",
            },
            updated_at="2026-07-20T00:00:00Z",
        )
        session = {
            "participants": ["林黛玉", "贾宝玉", "薛宝钗"],
            "history": [
                {
                    "speaker": "场景提示",
                    "message": "薛宝钗先回房，只剩林黛玉和贾宝玉在花厅。",
                    "ts": "2026-07-20T00:00:00Z",
                }
            ],
            "state": state,
        }

        merged = merge_scene_progress_state(
            session,
            {
                "present_participants": ["林黛玉", "贾宝玉", "薛宝钗"],
                "offstage_participants": [],
            },
            transcript=[],
            state_version=1,
        )

        self.assertEqual(merged["present_participants"], ["林黛玉", "贾宝玉"])
        self.assertEqual(merged["offstage_participants"], ["薛宝钗"])

    def test_departure_event_can_create_scene_shift_pressure(self):
        state = empty_session_state(1)
        set_session_scene_progress(
            state,
            {
                "present_participants": ["林黛玉", "贾宝玉"],
                "offstage_participants": ["薛宝钗"],
                "time_hint": "夜里",
                "location": "花厅",
                "atmosphere_summary": "花厅安静下来",
            },
            updated_at="2026-07-20T00:00:00Z",
        )
        state["signals"] = {
            "recent": [
                {
                    "kind": "cast_exit",
                    "actor": "薛宝钗",
                    "cue": "薛宝钗离场",
                    "ts": "2026-07-20T00:00:00Z",
                }
            ],
            "by_type": {},
            "updated_at": "2026-07-20T00:00:00Z",
        }
        history = [
            {
                "speaker": "场景提示" if index == 0 else "林黛玉",
                "message": "薛宝钗先回房。" if index == 0 else f"这一拍继续推进{index}。",
                "ts": f"2026-07-20T00:00:0{index}Z",
            }
            for index in range(4)
        ]
        session = {
            "participants": ["林黛玉", "贾宝玉", "薛宝钗"],
            "history": history,
            "state": state,
        }
        transcript = [
            {"role": "scene", "message": item["message"]}
            for item in history
        ]

        derived = derive_scene_progress_state(
            session,
            transcript,
            state_version=1,
        )

        self.assertTrue(derived["should_offer_scene_shift"])
        self.assertIn("薛宝钗已经离场", derived["scene_shift_reason"])
        self.assertGreaterEqual(derived["beat_maturity"], 42)


if __name__ == "__main__":
    unittest.main()
