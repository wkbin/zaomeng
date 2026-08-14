from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from src.web.chat.entrypoints import (
    continue_dialogue_scene_opening_payload,
    create_dialogue_session_payload,
    reply_dialogue_turn_payload,
)
from src.web.chat.runtime import load_pending_turn_payload


class PendingTurnRecoveryTests(unittest.TestCase):
    def _service_fixture(self, root: Path):
        from src.web.chat.service import DialogueService

        persona_dir = root / "personas" / "A"
        persona_dir.mkdir(parents=True, exist_ok=True)
        profile_path = persona_dir / "PROFILE.md"
        profile_path.write_text(
            "# PROFILE\n- name: A\n- core_identity: test character\n",
            encoding="utf-8",
        )
        manifest = {
            "run_id": "run-pending",
            "novel_id": "novel-1",
            "artifact_index": {
                "characters": [
                    {
                        "name": "A",
                        "profile_file": str(profile_path),
                        "persona_dir": str(persona_dir),
                    }
                ]
            },
        }
        return DialogueService(root / "runs"), manifest

    def test_prepare_rejects_fresh_pending_turn(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            dialogue, manifest = self._service_fixture(Path(tmpdir))
            session = dialogue.create_session(
                manifest, mode="observe", participants=["A"]
            )
            dialogue.prepare_turn(
                manifest, session_id=session["session_id"], message="first"
            )

            with self.assertRaises(ValueError):
                dialogue.prepare_turn(
                    manifest, session_id=session["session_id"], message="duplicate"
                )

    def test_prepare_replaces_timed_out_pending_turn_and_keeps_audit(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            dialogue, manifest = self._service_fixture(Path(tmpdir))
            session = dialogue.create_session(
                manifest, mode="observe", participants=["A"]
            )
            session_id = session["session_id"]
            first = dialogue.prepare_turn(
                manifest, session_id=session_id, message="first"
            )
            first_id = first["pending_turn_summary"]["turn_id"]
            session_path = dialogue._session_file("run-pending", session_id)
            raw = dialogue._read_json(session_path)
            raw["pending_turn"]["created_at"] = "2000-01-01T00:00:00Z"
            raw["pending_turn"]["payload_path"] = (
                f"/mnt/c/old/runs/run-pending/dialogue/{session_id}/turns/{first_id}.payload.json"
            )
            dialogue._write_json(session_path, raw)

            recovered = dialogue.prepare_turn(
                manifest, session_id=session_id, message="retry"
            )

            self.assertNotEqual(recovered["pending_turn_summary"]["turn_id"], first_id)
            self.assertEqual(recovered["aborted_turns"][-1]["turn_id"], first_id)
            self.assertEqual(recovered["aborted_turns"][-1]["reason"], "pending_timeout")

    def test_loader_falls_back_to_canonical_payload_for_cross_platform_path(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            runs_root = Path(tmpdir) / "runs"
            session_dir = runs_root / "run-1" / "dialogue" / "dlg-1"
            turn_dir = session_dir / "turns"
            turn_dir.mkdir(parents=True)
            (session_dir / "session.json").write_text(
                '{"pending_turn":{"turn_id":"turn-1","payload_path":"/mnt/c/old/turn-1.payload.json"}}',
                encoding="utf-8",
            )
            (turn_dir / "turn-1.payload.json").write_text(
                '{"turn_id":"turn-1","input":{"message":"hello"}}',
                encoding="utf-8",
            )

            def load_json(path: Path):
                if not path.exists():
                    return None
                import json

                return json.loads(path.read_text(encoding="utf-8"))

            payload = load_pending_turn_payload(
                runs_root=runs_root,
                run_id="run-1",
                session_id="dlg-1",
                load_json_file=load_json,
            )
            self.assertEqual(payload["turn_id"], "turn-1")

    def test_prepare_recovers_pending_turn_with_blank_path_and_missing_payload(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            dialogue, manifest = self._service_fixture(Path(tmpdir))
            session = dialogue.create_session(
                manifest, mode="observe", participants=["A"]
            )
            session_id = session["session_id"]
            prepared = dialogue.prepare_turn(
                manifest, session_id=session_id, message="first"
            )
            turn_id = prepared["pending_turn_summary"]["turn_id"]
            session_path = dialogue._session_file("run-pending", session_id)
            raw = dialogue._read_json(session_path)
            payload_path = Path(raw["pending_turn"]["payload_path"])
            payload_path.unlink()
            raw["pending_turn"]["payload_path"] = ""
            dialogue._write_json(session_path, raw)

            recovered = dialogue.prepare_turn(
                manifest, session_id=session_id, message="retry"
            )

            self.assertNotEqual(recovered["pending_turn_summary"]["turn_id"], turn_id)
            self.assertEqual(recovered["aborted_turns"][-1]["reason"], "payload_missing")

    def test_loader_never_reads_stored_payload_outside_the_session(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            runs_root = root / "runs"
            session_dir = runs_root / "run-1" / "dialogue" / "dlg-1"
            turn_dir = session_dir / "turns"
            turn_dir.mkdir(parents=True)
            outside_payload = root / "outside.payload.json"
            outside_payload.write_text('{"turn_id":"malicious"}', encoding="utf-8")
            (session_dir / "session.json").write_text(
                '{"pending_turn":{"turn_id":"turn-1","payload_path":"'
                + str(outside_payload).replace("\\", "\\\\")
                + '"}}',
                encoding="utf-8",
            )
            canonical = turn_dir / "turn-1.payload.json"
            canonical.write_text('{"turn_id":"turn-1"}', encoding="utf-8")
            reads: list[Path] = []

            def load_json(path: Path):
                reads.append(path)
                if not path.is_file():
                    return None
                import json

                return json.loads(path.read_text(encoding="utf-8"))

            payload = load_pending_turn_payload(
                runs_root=runs_root,
                run_id="run-1",
                session_id="dlg-1",
                load_json_file=load_json,
            )

            self.assertEqual(payload["turn_id"], "turn-1")
            self.assertNotIn(outside_payload, reads)

    def test_loader_rejects_turn_id_path_traversal_before_reading(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            runs_root = root / "runs"
            session_dir = runs_root / "run-1" / "dialogue" / "dlg-1"
            session_dir.mkdir(parents=True)
            outside_payload = root / "escape.payload.json"
            outside_payload.write_text('{"turn_id":"escape"}', encoding="utf-8")
            (session_dir / "session.json").write_text(
                '{"pending_turn":{"turn_id":"../../escape","payload_path":"'
                + str(outside_payload).replace("\\", "\\\\")
                + '"}}',
                encoding="utf-8",
            )
            reads: list[Path] = []

            def load_json(path: Path):
                reads.append(path)
                if not path.is_file():
                    return None
                import json

                return json.loads(path.read_text(encoding="utf-8"))

            with self.assertRaises(ValueError):
                load_pending_turn_payload(
                    runs_root=runs_root,
                    run_id="run-1",
                    session_id="dlg-1",
                    load_json_file=load_json,
                )

            self.assertNotIn(outside_payload, reads)

    def test_ingest_never_reads_stored_payload_outside_the_session(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            dialogue, manifest = self._service_fixture(root)
            session = dialogue.create_session(
                manifest, mode="observe", participants=["A"]
            )
            session_id = session["session_id"]
            prepared = dialogue.prepare_turn(
                manifest, session_id=session_id, message="hello"
            )
            session_path = dialogue._session_file("run-pending", session_id)
            raw = dialogue._read_json(session_path)
            canonical = Path(raw["pending_turn"]["payload_path"])
            canonical.unlink()
            outside_payload = root / "outside.payload.json"
            outside_payload.write_text(
                '{"turn_id":"outside","input":{"participants":["Mallory"]}}',
                encoding="utf-8",
            )
            raw["pending_turn"]["payload_path"] = str(outside_payload)
            dialogue._write_json(session_path, raw)
            original_read = dialogue._read_json
            reads: list[Path] = []

            def tracked_read(path: Path):
                reads.append(Path(path).resolve())
                return original_read(path)

            with patch.object(dialogue, "_read_json", side_effect=tracked_read):
                completed = dialogue.ingest_turn_responses(
                    "run-pending",
                    session_id=session_id,
                    responses=[{"speaker": "A", "message": "reply"}],
                )

            self.assertEqual(completed["status"], "ready")
            self.assertNotIn(outside_payload.resolve(), reads)
            self.assertEqual(
                completed["event_timeline"][-1]["turn_id"],
                prepared["pending_turn_summary"]["turn_id"],
            )

    def test_reply_failure_aborts_only_the_turn_it_prepared(self):
        dialogue = Mock()
        dialogue.prepare_turn.return_value = {
            "pending_turn_summary": {"turn_id": "turn-new"}
        }
        load_pending = Mock(return_value={"session_id": "dlg-1"})
        generate = Mock(side_effect=RuntimeError("model unavailable"))

        with self.assertRaisesRegex(RuntimeError, "model unavailable"):
            reply_dialogue_turn_payload(
                run_id="run-1",
                session_id="dlg-1",
                message="hello",
                message_kind="dialogue",
                manifest={"run_id": "run-1"},
                dialogue=dialogue,
                load_pending_turn_payload=load_pending,
                generate_dialogue_responses=generate,
                friendly_dialogue_llm_error=str,
                evolve_relations_from_turn=Mock(),
            )

        dialogue.abort_pending_turn.assert_called_once_with(
            "run-1",
            "dlg-1",
            expected_turn_id="turn-new",
            reason="reply_failed",
        )
        self.assertFalse(
            dialogue.prepare_turn.call_args.kwargs["_serialize_result"]
        )

    def test_create_session_opening_failure_aborts_the_prepared_turn(self):
        dialogue = Mock()
        dialogue.create_session.return_value = {"session_id": "dlg-created"}
        dialogue.prepare_turn.return_value = {
            "pending_turn_summary": {"turn_id": "turn-opening"}
        }

        with self.assertRaisesRegex(RuntimeError, "model unavailable"):
            create_dialogue_session_payload(
                run_id="run-1",
                manifest={"run_id": "run-1"},
                dialogue=dialogue,
                mode="observe",
                participants=["A", "B"],
                controlled_character="",
                scene_profile=None,
                self_profile=None,
                build_dialogue_opening_message=lambda _session: "opening",
                load_pending_turn_payload=Mock(return_value={"session_id": "dlg-created"}),
                generate_dialogue_responses=Mock(
                    side_effect=RuntimeError("model unavailable")
                ),
                friendly_dialogue_llm_error=str,
                evolve_relations_from_turn=Mock(),
            )

        dialogue.abort_pending_turn.assert_called_once_with(
            "run-1",
            "dlg-created",
            expected_turn_id="turn-opening",
            reason="opening_failed",
        )

    def test_scene_opening_failure_aborts_the_prepared_turn(self):
        dialogue = Mock()
        dialogue.prepare_turn.return_value = {
            "pending_turn_summary": {"turn_id": "turn-scene-opening"}
        }

        with self.assertRaisesRegex(RuntimeError, "model unavailable"):
            continue_dialogue_scene_opening_payload(
                run_id="run-1",
                session={"session_id": "dlg-existing"},
                manifest={"run_id": "run-1"},
                dialogue=dialogue,
                build_dialogue_opening_message=lambda _session: "opening",
                load_pending_turn_payload=Mock(return_value={"session_id": "dlg-existing"}),
                generate_dialogue_responses=Mock(
                    side_effect=RuntimeError("model unavailable")
                ),
                friendly_dialogue_llm_error=str,
                evolve_relations_from_turn=Mock(),
            )

        dialogue.abort_pending_turn.assert_called_once_with(
            "run-1",
            "dlg-existing",
            expected_turn_id="turn-scene-opening",
            reason="opening_failed",
        )

    def test_plot_direction_is_not_persisted_as_a_completed_scene_event(self):
        dialogue = Mock()
        dialogue.prepare_turn.return_value = {
            "pending_turn_summary": {"turn_id": "turn-plot"}
        }
        dialogue.ingest_turn_responses.return_value = {"status": "ready"}

        reply_dialogue_turn_payload(
            run_id="run-1",
            session_id="dlg-1",
            message="让门外的人现在闯进来。",
            message_kind="plot",
            manifest={"run_id": "run-1"},
            dialogue=dialogue,
            load_pending_turn_payload=Mock(return_value={"session_id": "dlg-1"}),
            generate_dialogue_responses=Mock(
                return_value=[{"speaker": "场景提示", "message": "门被撞开。"}]
            ),
            friendly_dialogue_llm_error=str,
            evolve_relations_from_turn=Mock(),
        )

        self.assertEqual(
            dialogue.prepare_turn.call_args.kwargs["transcript_message"], ""
        )


if __name__ == "__main__":
    unittest.main()
