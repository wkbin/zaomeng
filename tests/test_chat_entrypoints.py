from __future__ import annotations

import unittest
from unittest.mock import Mock

from src.core.exceptions import LLMRequestError
from src.web.chat.entrypoints import (
    create_dialogue_session_payload,
    reply_dialogue_turn_payload,
)


class ChatEntrypointWorkflowTests(unittest.TestCase):
    def test_reply_runs_completion_pipeline_in_order_and_forwards_cache(self):
        events: list[str] = []
        pending_payload = {"turn_id": "turn-1", "session_id": "dlg-1"}
        responses = [{"speaker": "A", "message": "reply"}]
        generation_cache = {"cache_read_tokens": 12}
        dialogue = Mock()
        dialogue.prepare_turn.return_value = {
            "pending_turn_summary": {"turn_id": "turn-1"}
        }

        def load_pending(run_id: str, session_id: str):
            events.append("load")
            self.assertEqual((run_id, session_id), ("run-1", "dlg-1"))
            return pending_payload

        def generate(run_id: str, payload: dict):
            events.append("generate")
            self.assertEqual((run_id, payload), ("run-1", pending_payload))
            return {
                "responses": responses,
                "generation_cache": generation_cache,
            }

        def evolve(run_id: str, payload: dict, generated_responses: list[dict]):
            events.append("evolve")
            self.assertEqual(
                (run_id, payload, generated_responses),
                ("run-1", pending_payload, responses),
            )

        def ingest(*args, **kwargs):
            events.append("ingest")
            return {"status": "ready"}

        def refresh(run_id: str, payload: dict):
            events.append("refresh")
            self.assertEqual((run_id, payload), ("run-1", {"status": "ready"}))
            return {**payload, "scene_progress_refreshed": True}

        dialogue.ingest_turn_responses.side_effect = ingest

        result = reply_dialogue_turn_payload(
            run_id="run-1",
            session_id="dlg-1",
            message="hello",
            message_kind="dialogue",
            manifest={"run_id": "run-1"},
            dialogue=dialogue,
            load_pending_turn_payload=load_pending,
            generate_dialogue_responses=generate,
            friendly_dialogue_llm_error=str,
            evolve_relations_from_turn=evolve,
            refresh_scene_progress=refresh,
        )

        self.assertEqual(
            events,
            ["load", "generate", "evolve", "ingest", "refresh"],
        )
        self.assertEqual(
            result,
            {"status": "ready", "scene_progress_refreshed": True},
        )
        dialogue.ingest_turn_responses.assert_called_once_with(
            "run-1",
            session_id="dlg-1",
            responses=responses,
            remember_turn_memory=True,
            generation_cache=generation_cache,
        )
        dialogue.abort_pending_turn.assert_not_called()

    def test_reply_translates_llm_error_then_aborts_expected_turn(self):
        dialogue = Mock()
        dialogue.prepare_turn.return_value = {
            "pending_turn_summary": {"turn_id": "turn-1"}
        }
        request_error = LLMRequestError("upstream detail")
        friendly_error = Mock(return_value="friendly message")

        with self.assertRaisesRegex(ValueError, "friendly message") as raised:
            reply_dialogue_turn_payload(
                run_id="run-1",
                session_id="dlg-1",
                message="hello",
                message_kind="dialogue",
                manifest={"run_id": "run-1"},
                dialogue=dialogue,
                load_pending_turn_payload=Mock(return_value={"turn_id": "turn-1"}),
                generate_dialogue_responses=Mock(side_effect=request_error),
                friendly_dialogue_llm_error=friendly_error,
                evolve_relations_from_turn=Mock(),
            )

        self.assertIs(raised.exception.__cause__, request_error)
        friendly_error.assert_called_once_with(request_error)
        dialogue.abort_pending_turn.assert_called_once_with(
            "run-1",
            "dlg-1",
            expected_turn_id="turn-1",
            reason="reply_failed",
        )
        dialogue.ingest_turn_responses.assert_not_called()

    def test_create_session_requires_two_participants_for_observe(self):
        dialogue = Mock()

        with self.assertRaisesRegex(ValueError, "At least two participants"):
            create_dialogue_session_payload(
                run_id="run-1",
                manifest={"run_id": "run-1"},
                dialogue=dialogue,
                mode="observe",
                participants=["?"],
                controlled_character="",
                scene_profile={},
                self_profile={},
                build_dialogue_opening_message=Mock(),
                load_pending_turn_payload=Mock(),
                generate_dialogue_responses=Mock(),
                friendly_dialogue_llm_error=Mock(),
                evolve_relations_from_turn=Mock(),
            )

        dialogue.create_session.assert_not_called()

    def test_create_session_requires_one_participant_for_insert(self):
        dialogue = Mock()

        with self.assertRaisesRegex(ValueError, "At least one participant"):
            create_dialogue_session_payload(
                run_id="run-1",
                manifest={"run_id": "run-1"},
                dialogue=dialogue,
                mode="insert",
                participants=[],
                controlled_character="",
                scene_profile={},
                self_profile={},
                build_dialogue_opening_message=Mock(),
                load_pending_turn_payload=Mock(),
                generate_dialogue_responses=Mock(),
                friendly_dialogue_llm_error=Mock(),
                evolve_relations_from_turn=Mock(),
            )

        dialogue.create_session.assert_not_called()

    def test_failed_opening_removes_new_session(self):
        dialogue = Mock()
        dialogue.create_session.return_value = {
            "session_id": "dlg-1",
            "participants": ["甲", "乙"],
        }
        dialogue.prepare_turn.return_value = {
            "pending_turn_summary": {"turn_id": "turn-1"}
        }

        with self.assertRaises(ValueError):
            create_dialogue_session_payload(
                run_id="run-1",
                manifest={"run_id": "run-1"},
                dialogue=dialogue,
                mode="observe",
                participants=["甲", "乙"],
                controlled_character="",
                scene_profile={},
                self_profile={},
                build_dialogue_opening_message=Mock(return_value="开场"),
                load_pending_turn_payload=Mock(return_value={"turn_id": "turn-1"}),
                generate_dialogue_responses=Mock(
                    side_effect=LLMRequestError("upstream detail")
                ),
                friendly_dialogue_llm_error=Mock(return_value="friendly message"),
                evolve_relations_from_turn=Mock(),
            )

        dialogue.delete_session.assert_called_once_with("run-1", "dlg-1")


if __name__ == "__main__":
    unittest.main()
