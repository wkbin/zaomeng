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

class DialogueStateTests(unittest.TestCase):
    def test_build_turn_payload_includes_memory_context_and_trims_relation_excerpt(
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
                        (
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n"
                            f"- story_role: 关键人物\n- speech_style: 有自己的语气\n- stress_response: 越急越压着说\n"
                        ).encode("utf-8")
                    ).decode("ascii"),
                )

            manifest = service._require_manifest(run_id)
            relation_path = Path(tmp) / "relations.md"
            relation_path.write_text(
                "\n".join(
                    [
                        "无关铺垫 " * 80,
                        "## 林黛玉_贾宝玉",
                        "- trust: 8",
                        "- evidence: 林黛玉与贾宝玉彼此牵挂，却都不肯把话说透。",
                        "- conflict: 一句轻话也容易拧成心事。",
                        "别的人物关系 " * 120,
                    ]
                ),
                encoding="utf-8",
            )
            manifest["artifact_index"]["relation_graph"] = {
                "relations_file": str(relation_path)
            }
            (service.runs_root / run_id / "run_manifest.json").write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

            session = service.dialogue.create_session(
                manifest,
                mode="observe",
                participants=["林黛玉", "贾宝玉"],
                scene_profile={
                    "title": "花厅夜谈",
                    "location": "花厅",
                    "public_goal": "把误会说开",
                    "hidden_tension": "两人嘴硬心软",
                },
            )
            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session["session_id"])
            )
            raw_session["history"] = [
                {
                    "speaker": "林黛玉",
                    "message": "你不要把这句说得这样轻巧。",
                    "ts": "2026-05-12T00:00:00Z",
                },
                {
                    "speaker": "贾宝玉",
                    "message": "我明晚会回来把误会说开。",
                    "ts": "2026-05-12T00:00:01Z",
                },
            ]
            raw_session["state"] = {
                "memory": {
                    "summary": {
                        "summary": "两人前面已经因一句话生过闷气，但都还惦记对方。",
                        "key_points": [
                            "林黛玉嘴上轻冷，心里还在意。",
                            "贾宝玉想解释，却总把话说得更乱。",
                        ],
                        "compressed_turns": 18,
                        "recent_turns_kept": 24,
                    }
                }
            }
            service.dialogue._write_json(
                service.dialogue._session_file(run_id, session["session_id"]),
                raw_session,
            )
            store = service.dialogue._resolve_memory_store(run_id)
            assert store is not None
            store.append_long_term_memory(
                session["session_id"],
                "林黛玉 -> 贾宝玉: 先前那句轻慢话已经成了两人之间的小心结。",
                metadata={"speaker": "林黛玉", "target": "贾宝玉", "kind": "dialogue"},
            )
            store.append_long_term_memory(
                session["session_id"],
                "场景旧线：他们早就约好要把误会摊开说清，只是谁也不肯先服软。",
                metadata={"speaker": "场景提示", "kind": "summary"},
            )

            payload = service.dialogue._build_turn_payload(
                manifest,
                raw_session,
                turn_id="turn-test",
                message="宝玉，你先别急着解释。",
            )

            memory_context = payload.get("memory_context", {})
            self.assertTrue(memory_context.get("session_summary", {}).get("recap"))
            self.assertTrue(
                memory_context.get("session_summary", {}).get("recent_conflicts")
            )
            self.assertTrue(
                memory_context.get("session_summary", {}).get("current_goal")
            )
            self.assertTrue(
                memory_context.get("session_summary", {}).get("unresolved_threads")
            )
            self.assertTrue(
                memory_context.get("session_summary", {}).get("current_location")
            )
            self.assertTrue(
                memory_context.get("session_summary", {}).get("current_companions")
            )
            self.assertTrue(
                memory_context.get("session_summary", {}).get("pending_commitments")
            )
            self.assertEqual(
                memory_context.get("archived_summary", {}).get("compressed_turns"), 18
            )
            self.assertTrue(memory_context.get("retrieved_memories"))
            retrieved_text = " ".join(
                str(item.get("text", ""))
                for item in memory_context["retrieved_memories"]
            )
            self.assertIn("误会", retrieved_text)
            relation_excerpt = str(
                payload.get("relation_context", {}).get("relations_excerpt", "")
            )
            self.assertLess(len(relation_excerpt), 4000)
            self.assertIn("林黛玉_贾宝玉", relation_excerpt)

    def test_session_memory_summary_keeps_commitments_actions_and_major_beats(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            run = service.create_run(
                novel_name="demo.txt",
                novel_content_base64=base64.b64encode(
                    "甲见了乙。".encode("utf-8")
                ).decode("ascii"),
                characters=["甲", "乙"],
            )
            run_id = run["run_id"]
            for name in ("甲", "乙"):
                service.ingest_character_result(
                    run_id,
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: demo\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )
            manifest = service._require_manifest(run_id)
            session = service.dialogue.create_session(
                manifest,
                mode="observe",
                participants=["甲", "乙"],
                scene_profile={
                    "title": "雨夜回廊",
                    "location": "回廊",
                    "atmosphere": "压着话",
                    "scene_card_id": "scene-1",
                    "public_goal": "把误会摊开说清",
                    "hidden_tension": "乙其实并不信甲",
                },
            )
            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session["session_id"])
            )
            raw_session["history"] = [
                {
                    "speaker": "甲",
                    "message": "我明天会回来，把这件事亲自说清。",
                    "ts": "2026-05-12T00:00:00Z",
                },
                {
                    "speaker": "乙",
                    "message": "你不要再拿这种话来搪塞我。",
                    "ts": "2026-05-12T00:00:01Z",
                },
                {
                    "speaker": "甲",
                    "message": "（转身看向门外）我没有想躲。",
                    "ts": "2026-05-12T00:00:02Z",
                },
                {
                    "speaker": "场景提示",
                    "message": "雨声忽然压下来，回廊里安静得只剩呼吸。",
                    "ts": "2026-05-12T00:00:03Z",
                },
            ]
            raw_session["state"] = service.dialogue._empty_session_state()
            raw_session["state"]["signals"] = {
                "recent": [
                    {
                        "kind": "atmosphere_shift",
                        "cue": "雨声忽然压下来，回廊里安静得只剩呼吸。",
                    },
                ],
                "by_type": {},
                "updated_at": "2026-05-12T00:00:03Z",
            }
            summary = service.dialogue._build_session_memory_summary(
                run_id, raw_session, service.dialogue._serialize_transcript(raw_session)
            )

            self.assertIn("明天会回来", summary.get("recent_commitments", ""))
            self.assertIn("不要再拿这种话来搪塞我", summary.get("recent_conflicts", ""))
            self.assertIn("转身看向门外", summary.get("recent_actions", ""))
            self.assertIn("雨声忽然压下来", summary.get("major_beats", ""))
            self.assertIn("把误会摊开说清", summary.get("current_goal", ""))
            self.assertIn("甲还挂着", summary.get("unresolved_threads", ""))
            self.assertIn("雨夜回廊", summary.get("current_location", ""))
            self.assertIn("甲、乙", summary.get("current_companions", ""))
            self.assertIn("待完成承诺", summary.get("pending_commitments", ""))

    def test_dialogue_relation_delta_and_character_snapshot_are_session_isolated(self):
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
                        (
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: {name}人物\n"
                            f"- story_role: {name}位置\n- speech_style: {name}自有口气\n- stress_response: {name}越急越压住\n"
                        ).encode("utf-8")
                    ).decode("ascii"),
                )

            relation_path = Path(tmp) / "relations.md"
            original_relation_text = "\n".join(
                [
                    "# RELATION_GRAPH",
                    "",
                    "## 林黛玉_贾宝玉",
                    "- trust: 8",
                    "- affection: 9",
                    "- hostility: 1",
                    "- ambiguity: 3",
                ]
            )
            relation_path.write_text(original_relation_text, encoding="utf-8")
            manifest = service._require_manifest(run_id)
            manifest["artifact_index"]["relation_graph"] = {
                "relations_file": str(relation_path)
            }
            (service.runs_root / run_id / "run_manifest.json").write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

            session_one = service.dialogue.create_session(
                manifest, mode="observe", participants=["林黛玉", "贾宝玉"]
            )
            session_two = service.dialogue.create_session(
                manifest, mode="observe", participants=["林黛玉", "贾宝玉"]
            )

            pending_payload = {
                "session_id": session_one["session_id"],
                "input": {
                    "speaker": "林黛玉",
                    "participants": ["林黛玉", "贾宝玉"],
                    "active_participants": ["林黛玉", "贾宝玉"],
                },
            }
            service._evolve_relations_from_turn(
                run_id,
                pending_payload,
                [
                    {
                        "speaker": "贾宝玉",
                        "message": "谢谢你愿意陪我一起，我不是不在意你。",
                    }
                ],
            )

            raw_one = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session_one["session_id"])
            )
            raw_two = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session_two["session_id"])
            )

            delta = (
                raw_one.get("state", {})
                .get("relations", {})
                .get("delta", {})
                .get("林黛玉_贾宝玉", {})
            )
            self.assertEqual(delta.get("trust"), 1)
            self.assertEqual(delta.get("affection"), 1)
            self.assertEqual(delta.get("hostility"), -1)
            self.assertEqual(delta.get("last_actor"), "贾宝玉")
            self.assertEqual(delta.get("last_target"), "林黛玉")
            self.assertGreaterEqual(int(delta.get("momentum", 0) or 0), 1)
            snapshot = (
                raw_one.get("state", {})
                .get("characters", {})
                .get("snapshots", {})
                .get("贾宝玉", {})
            )
            self.assertEqual(snapshot.get("interaction_state"), "softening")
            self.assertEqual(snapshot.get("last_target"), "林黛玉")
            self.assertEqual(snapshot.get("present_state"), "onstage")
            self.assertTrue(bool(snapshot.get("updated_at", "")))

            self.assertEqual(
                raw_two.get("state", {}).get("relations", {}).get("delta", {}), {}
            )
            untouched_snapshot = (
                raw_two.get("state", {})
                .get("characters", {})
                .get("snapshots", {})
                .get("贾宝玉", {})
            )
            self.assertEqual(untouched_snapshot.get("present_state"), "onstage")
            self.assertFalse(bool(untouched_snapshot.get("interaction_state", "")))
            self.assertEqual(
                relation_path.read_text(encoding="utf-8"), original_relation_text
            )

    def test_build_turn_payload_includes_session_relation_delta_and_snapshots(self):
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
                        (
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: {name}人物\n"
                            f"- story_role: {name}位置\n- soul_goal: {name}想护住眼前人\n"
                            f"- speech_style: {name}自有口气\n- stress_response: {name}越急越压住\n"
                        ).encode("utf-8")
                    ).decode("ascii"),
                )
            manifest = service._require_manifest(run_id)
            session = service.dialogue.create_session(
                manifest,
                mode="observe",
                participants=["林黛玉", "贾宝玉"],
            )
            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session["session_id"])
            )
            raw_session["state"] = {
                **dict(raw_session.get("state", {}) or {}),
                "relations": {
                    "matrix": {
                        "林黛玉_贾宝玉": {
                            "trust": 8,
                            "affection": 8,
                            "hostility": 1,
                            "ambiguity": 3,
                        }
                    },
                    "delta": {
                        "林黛玉_贾宝玉": {
                            "trust": 1,
                            "affection": 1,
                            "last_event": "刚刚把话说软了下来。",
                            "evidence_lines": ["贾宝玉->林黛玉: 谢谢你愿意陪我一起。"],
                        }
                    },
                },
                "characters": {
                    "snapshots": {
                        "贾宝玉": {
                            "mood": "放松",
                            "interaction_state": "softening",
                            "focus": "林黛玉",
                            "last_target": "林黛玉",
                            "last_message": "谢谢你愿意陪我一起。",
                        }
                    }
                },
            }

            payload = service.dialogue._build_turn_payload(
                manifest,
                raw_session,
                turn_id="turn-session-delta",
                message="你继续说。",
            )

            memory_context = payload.get("memory_context", {})
            self.assertTrue(
                memory_context.get("relation_delta", {}).get("林黛玉_贾宝玉")
            )
            self.assertTrue(memory_context.get("character_snapshots", {}).get("贾宝玉"))
            relation_excerpt = str(
                payload.get("relation_context", {}).get("relations_excerpt", "")
            )
            self.assertIn("SESSION_RELATION_STATE", relation_excerpt)
            self.assertIn("session_delta", relation_excerpt)
            detail_map = {
                item["name"]: item for item in payload.get("persona_contexts", [])
            }
            self.assertEqual(
                detail_map["贾宝玉"]["session_snapshot"]["interaction_state"],
                "softening",
            )
            serialized = service.dialogue._serialize_session(run_id, raw_session)
            overview = dict(serialized.get("runtime_state_overview", {}) or {})
            self.assertTrue(bool(overview.get("relation_rows", [])))

    def test_dialogue_session_state_uses_canonical_grouped_schema(self):
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

            session = service.dialogue.create_session(
                service._require_manifest(run_id),
                mode="observe",
                participants=["林黛玉", "贾宝玉"],
            )
            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session["session_id"])
            )
            state = dict(raw_session.get("state", {}) or {})

            self.assertEqual(state.get("version"), 1)
            self.assertIn("scene", state)
            self.assertIn("presence", state)
            self.assertIn("progression", state)
            self.assertIn("relations", state)
            self.assertIn("characters", state)
            self.assertIn("signals", state)
            self.assertIn("memory", state)
            self.assertIn("atmosphere_summary", dict(state.get("scene", {}) or {}))
            self.assertIn("matrix", dict(state.get("relations", {}) or {}))
            self.assertIn("delta", dict(state.get("relations", {}) or {}))
            self.assertIn("snapshots", dict(state.get("characters", {}) or {}))
            self.assertIn("beat_maturity", dict(state.get("progression", {}) or {}))
            self.assertIn(
                "world_tension_summary", dict(state.get("progression", {}) or {})
            )
            overview = dict(session.get("runtime_state_overview", {}) or {})
            self.assertIn("present", overview)
            self.assertIn("offstage", overview)
            self.assertIn("pills", overview)
            self.assertIn("character_rows", overview)
            self.assertIn("relation_rows", overview)
            self.assertIn("event_rows", overview)
            self.assertIn("status_line", overview)
            self.assertIn("next_hint", overview)
            self.assertIn("current_location", overview)
            self.assertIn("current_companions", overview)
            self.assertIn("pending_commitments", overview)

    def test_dialogue_relation_state_llm_can_lightly_refine_session_delta(self):
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

            session = service.dialogue.create_session(
                service._require_manifest(run_id),
                mode="observe",
                participants=["林黛玉", "贾宝玉"],
            )
            pending_payload = {
                "session_id": session["session_id"],
                "input": {
                    "speaker": "林黛玉",
                    "participants": ["林黛玉", "贾宝玉"],
                    "active_participants": ["林黛玉", "贾宝玉"],
                },
            }
            with patch.object(
                service,
                "_generate_dialogue_relation_state",
                return_value={
                    "relation_delta": {
                        "林黛玉_贾宝玉": {
                            "trust": 2,
                            "affection": 1,
                            "last_event": "这次道谢让两人之间明显更松了一步。",
                        }
                    },
                    "character_snapshots": {
                        "贾宝玉": {
                            "mood": "放松",
                            "interaction_state": "softening",
                            "focus": "林黛玉",
                            "last_target": "林黛玉",
                        }
                    },
                },
            ):
                service._evolve_relations_from_turn(
                    run_id,
                    pending_payload,
                    [{"speaker": "贾宝玉", "message": "谢谢你愿意陪我一起。"}],
                )

            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session["session_id"])
            )
            delta = (
                raw_session.get("state", {})
                .get("relations", {})
                .get("delta", {})
                .get("林黛玉_贾宝玉", {})
            )
            self.assertEqual(delta.get("trust"), 2)
            self.assertEqual(delta.get("affection"), 1)
            self.assertIn("明显更松", str(delta.get("last_event", "")))
            snapshot = (
                raw_session.get("state", {})
                .get("characters", {})
                .get("snapshots", {})
                .get("贾宝玉", {})
            )
            self.assertEqual(snapshot.get("interaction_state"), "softening")

    def test_dialogue_fast_response_skips_noncritical_llm_refinements(self):
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
            session = service.dialogue.create_session(
                service._require_manifest(run["run_id"]),
                mode="act",
                participants=["林黛玉", "贾宝玉"],
                controlled_character="林黛玉",
            )

            with patch.object(
                service,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "贾宝玉", "message": "你慢慢说，我听着。"}],
            ), patch.object(
                service, "_generate_dialogue_relation_state"
            ) as relation_refinement, patch.object(
                service, "_generate_dialogue_scene_progress"
            ) as scene_refinement:
                replied = service.reply_dialogue_turn(
                    run["run_id"],
                    session_id=session["session_id"],
                    message="我有句话想问你。",
                    fast_response=True,
                )

            relation_refinement.assert_not_called()
            scene_refinement.assert_not_called()
            self.assertEqual(replied["status"], "ready")
            self.assertEqual(replied["transcript"][-1]["speaker"], "贾宝玉")
            self.assertIn("scene_progress", replied)

    def test_dialogue_event_signals_capture_scene_and_inline_action_categories(self):
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
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            session = service.dialogue.create_session(
                service._require_manifest(run_id),
                mode="observe",
                participants=["林黛玉", "贾宝玉", "薛宝钗"],
            )
            pending_payload = {
                "session_id": session["session_id"],
                "input": {
                    "speaker": "场景提示",
                    "message": "夜里雨更大了，众人转入花厅，薛宝钗先回房。",
                    "message_kind": "narration",
                    "participants": ["林黛玉", "贾宝玉", "薛宝钗"],
                    "active_participants": ["林黛玉", "贾宝玉", "薛宝钗"],
                },
            }
            with patch.object(
                service, "_generate_dialogue_relation_state", return_value={}
            ):
                service._evolve_relations_from_turn(
                    run_id,
                    pending_payload,
                    responses=[
                        {
                            "speaker": "林黛玉",
                            "message": "（低头笑了笑）那就进屋再说。",
                        },
                        {
                            "speaker": "贾宝玉",
                            "message": "屋里一下安静下来，我陪你进去。",
                        },
                    ],
                )

            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session["session_id"])
            )
            event_signals = dict(raw_session.get("state", {}).get("signals", {}) or {})
            recent = list(event_signals.get("recent", []) or [])
            kinds = {str(item.get("kind", "")).strip() for item in recent}
            overview = dict(
                service.dialogue._serialize_session(run_id, raw_session).get(
                    "runtime_state_overview", {}
                )
                or {}
            )
            event_rows = list(overview.get("event_rows", []) or [])

            self.assertIn("time_change", kinds)
            self.assertIn("environment_change", kinds)
            self.assertIn("scene_transition", kinds)
            self.assertIn("cast_exit", kinds)
            self.assertIn("micro_action", kinds)
            self.assertIn("atmosphere_shift", kinds)
            self.assertTrue(event_rows)

            micro_action = next(
                item
                for item in recent
                if str(item.get("kind", "")).strip() == "micro_action"
            )
            self.assertEqual(micro_action.get("actor"), "林黛玉")
            self.assertTrue(bool(micro_action.get("should_inline", False)))

            plot_payload = {
                "session_id": session["session_id"],
                "input": {
                    "speaker": "场景提示",
                    "message": "早晨让院外的人闯进来。",
                    "message_kind": "plot",
                    "participants": ["林黛玉", "贾宝玉", "薛宝钗"],
                    "active_participants": ["林黛玉", "贾宝玉", "薛宝钗"],
                },
            }
            with patch.object(
                service, "_generate_dialogue_relation_state", return_value={}
            ):
                service._evolve_relations_from_turn(
                    run_id,
                    plot_payload,
                    responses=[
                        {
                            "speaker": "场景提示",
                            "message": "院门猛地被撞开，来人举着一封急信。",
                        }
                    ],
                )
            plot_raw = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session["session_id"])
            )
            plot_events = list(
                dict(plot_raw.get("state", {}).get("signals", {}) or {}).get(
                    "recent", []
                )
                or []
            )
            self.assertFalse(
                any(
                    str(item.get("kind", "")).strip() == "time_change"
                    and str(item.get("time_hint", "")).strip() == "早晨"
                    for item in plot_events
                )
            )

    def test_build_turn_payload_prioritizes_active_personas_for_full_context(self):
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
                characters=["林黛玉", "贾宝玉", "薛宝钗", "王熙凤", "史湘云", "探春"],
            )
            run_id = run["run_id"]
            for name in ("林黛玉", "贾宝玉", "薛宝钗", "王熙凤", "史湘云", "探春"):
                service.ingest_character_result(
                    run_id,
                    character=name,
                    content_base64=base64.b64encode(
                        (
                            f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: {name}人物\n"
                            "- story_role: 核心角色\n- soul_goal: 各有心事\n- speech_style: 有自己的口气\n"
                            "- temperament_type: 有锋芒\n- social_mode: 见人见招\n- reward_logic: 先护住在意的人\n"
                            "- stress_response: 越急越收着说\n- key_bonds: 若干旧人\n"
                        ).encode("utf-8")
                    ).decode("ascii"),
                )
            manifest = service._require_manifest(run_id)
            session = service.dialogue.create_session(
                manifest,
                mode="observe",
                participants=["林黛玉", "贾宝玉", "薛宝钗", "王熙凤", "史湘云", "探春"],
            )
            raw_session = service.dialogue._read_json(
                service.dialogue._session_file(run_id, session["session_id"])
            )
            raw_session["history"] = [
                {
                    "speaker": "旁白",
                    "message": "薛宝钗告退回房，先离开了。",
                    "ts": "2026-05-12T00:00:00Z",
                },
                {
                    "speaker": "林黛玉",
                    "message": "那便先由我们说。",
                    "ts": "2026-05-12T00:00:01Z",
                },
                {
                    "speaker": "王熙凤",
                    "message": "你们慢慢说，我在旁边听着。",
                    "ts": "2026-05-12T00:00:02Z",
                },
            ]

            payload = service.dialogue._build_turn_payload(
                manifest,
                raw_session,
                turn_id="turn-active-persona",
                message="你们接着说。",
            )

            persona_contexts = payload["persona_contexts"]
            detail_map = {item["name"]: item for item in persona_contexts}
            self.assertEqual(detail_map["林黛玉"]["detail_level"], "full")
            self.assertEqual(detail_map["贾宝玉"]["detail_level"], "full")
            self.assertEqual(detail_map["王熙凤"]["detail_level"], "full")
            self.assertEqual(detail_map["史湘云"]["detail_level"], "full")
            self.assertEqual(detail_map["薛宝钗"]["detail_level"], "compact")
            self.assertEqual(detail_map["探春"]["detail_level"], "compact")
            self.assertIn("soul_goal", detail_map["林黛玉"]["profile"])
            self.assertNotIn("soul_goal", detail_map["探春"]["profile"])

    def test_dialogue_session_prepare_and_ingest(self):
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
            service.ingest_character_result(
                payload["run_id"],
                character="林黛玉",
                content_base64=base64.b64encode(
                    "- name: 林黛玉\n- novel_id: hongloumeng\n- core_identity: 才女\n- soul_goal: 守住真心\n".encode(
                        "utf-8"
                    )
                ).decode("ascii"),
            )
            service.ingest_character_result(
                payload["run_id"],
                character="贾宝玉",
                content_base64=base64.b64encode(
                    "- name: 贾宝玉\n- novel_id: hongloumeng\n- core_identity: 公子\n- soul_goal: 护住眼前人\n".encode(
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
                    mode="insert",
                    participants=["???", "???"],
                    self_profile={"display_name": "Self", "scene_identity": "Guest"},
                )
            prepared = service.prepare_dialogue_turn(
                payload["run_id"],
                session_id=session["session_id"],
                message="我刚进园子，想先和你们打个招呼。",
            )
            self.assertEqual(prepared["status"], "waiting_for_host_reply")
            self.assertIn("pending_turn_payload", prepared["file_urls"])
            self.assertEqual(
                prepared["session_card"]["self_insert"]["display_name"], "Self"
            )
            self.assertEqual(prepared["pending_turn_summary"]["speaker"], "Self")
            self.assertEqual(
                prepared["pending_turn_summary"]["message_kind"], "dialogue"
            )

            completed = service.ingest_dialogue_turn(
                payload["run_id"],
                session_id=session["session_id"],
                responses=[{"speaker": "林黛玉", "message": "你既来了，先坐下说话。"}],
            )
            self.assertEqual(completed["status"], "ready")
            self.assertEqual(len(completed["transcript"]), 3)
            self.assertEqual(completed["transcript"][0]["role"], "scene")
            self.assertEqual(completed["transcript"][1]["role"], "user")
            self.assertEqual(completed["transcript"][2]["role"], "character")
            memory_summary = completed.get("session_memory_summary", {})
            self.assertEqual(memory_summary.get("mode"), "insert")
            self.assertIn("最近一拍", memory_summary.get("recap", ""))
            self.assertIn("当前主要在场", memory_summary.get("cast", ""))
            self.assertTrue(memory_summary.get("relation_drift"))
            self.assertIn("你以", memory_summary.get("perspective", ""))
            self.assertTrue(memory_summary.get("world"))

    def test_dialogue_reply_uses_shared_long_term_memory_store(self):
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

            with patch.object(
                service,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session = service.create_dialogue_session(
                    run["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉"],
                )

            with patch.object(
                service,
                "_generate_dialogue_responses",
                return_value=[
                    {"speaker": "林黛玉", "message": "我们的目标还没改，你先别急。"}
                ],
            ):
                replied = service.reply_dialogue_turn(
                    run["run_id"],
                    session_id=session["session_id"],
                    message="那就继续往目标走。",
                    message_kind="narration",
                )

            config = service._build_runtime_config_for_run(
                run_dir=service.runs_root / run["run_id"]
            )
            parts = service._build_runtime_parts(config)
            hits = parts.session_store.search_long_term_memory(
                session["session_id"], "目标", top_k=5
            )

            self.assertTrue(hits)
            hit_texts = " ".join(str(item.get("text", "")) for item in hits)
            self.assertIn("目标", hit_texts)
            self.assertIn(
                "长期记忆", replied["session_memory_summary"]["relation_drift"]
            )
