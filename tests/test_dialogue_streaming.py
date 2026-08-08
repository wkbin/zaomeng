from __future__ import annotations

import base64
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

from src.web.chat.reply_operations import ReplyOperationConflict, ReplyOperationStore
from src.web.chat.streaming import DialogueJsonDeltaProjector, encode_sse
from src.web.workflow import WebRunService


class DialogueStreamingTests(unittest.TestCase):
    def _create_dialogue(self, storage_root: str):
        service = WebRunService(storage_root)
        service.save_model_settings(
            provider="openai-compatible",
            model="test-model",
            base_url="https://example.com/v1",
            api_key="sk-test",
        )
        run = service.create_run(
            novel_name="story.txt",
            novel_content_base64=base64.b64encode(
                "甲遇见乙。".encode("utf-8")
            ).decode("ascii"),
            characters=["甲", "乙"],
        )
        for character in ("甲", "乙"):
            service.ingest_character_result(
                run["run_id"],
                character=character,
                content_base64=base64.b64encode(
                    f"- name: {character}\n- core_identity: 人物\n".encode("utf-8")
                ).decode("ascii"),
            )
        session = service.dialogue.create_session(
            service._require_manifest(run["run_id"]),
            mode="act",
            participants=["甲", "乙"],
            controlled_character="甲",
        )
        return service, run["run_id"], session["session_id"]

    def test_structured_json_is_projected_as_readable_deltas(self):
        projector = DialogueJsonDeltaProjector(chunk_size=4)

        first = projector.feed('{"responses":[{"speaker":"甲","message":"你好')
        second = projector.feed('，请坐。"}]}')

        self.assertEqual("".join(item["text"] for item in [*first, *second]), "你好，请坐。")
        self.assertTrue(all(item["speaker"] == "甲" for item in [*first, *second]))
        encoded = encode_sse("delta", second[-1])
        self.assertTrue(encoded.startswith("event: delta\n"))
        self.assertIn("data: {", encoded)

    def test_projector_accepts_message_before_speaker(self):
        projector = DialogueJsonDeltaProjector(chunk_size=4)

        first = projector.feed(
            '{"responses":[{"message":"先说出来，再标名字","speaker":"甲"}'
        )
        second = projector.feed(
            ',{"message":"括号 { 不应串到上一条","speaker":"乙"}]}'
        )
        events = [*first, *second]

        by_speaker = {
            speaker: "".join(
                item["text"] for item in events if item["speaker"] == speaker
            )
            for speaker in ("甲", "乙")
        }
        self.assertEqual(by_speaker["甲"], "先说出来，再标名字")
        self.assertEqual(by_speaker["乙"], "括号 { 不应串到上一条")

    def test_escaped_emoji_is_combined_and_sse_is_utf8_safe(self):
        projector = DialogueJsonDeltaProjector(chunk_size=4)

        first = projector.feed(
            '{"responses":[{"speaker":"甲","message":"笑了\\ud83d'
        )
        second = projector.feed('\\ude00"}]}')
        events = [*first, *second]

        self.assertEqual("".join(item["text"] for item in events), "笑了😀")
        encoded = encode_sse("delta", events[-1])
        self.assertIn("😀", encoded)
        encoded.encode("utf-8")

    def test_inner_thought_is_projected_as_stream_deltas(self):
        projector = DialogueJsonDeltaProjector(chunk_size=4)

        first = projector.feed(
            '{"responses":[{"speaker":"甲","message":"你走吧。","inner_thought":"别'
        )
        second = projector.feed('走。"}]}')
        events = [*first, *second]

        message_text = "".join(
            item["text"] for item in events if item.get("field") == "message"
        )
        inner_text = "".join(
            item["text"] for item in events if item.get("field") == "inner_thought"
        )
        self.assertEqual(message_text, "你走吧。")
        self.assertEqual(inner_text, "别走。")
        self.assertTrue(
            any(item.get("field") == "inner_thought" for item in events)
        )

    def test_reply_operation_store_rejects_key_reuse_for_another_message(self):
        with tempfile.TemporaryDirectory() as tmp:
            runs_root = Path(tmp) / "runs"
            session_dir = runs_root / "run-1" / "dialogue" / "session-1"
            (session_dir / "turns").mkdir(parents=True)
            store = ReplyOperationStore(runs_root)
            fingerprint = store.request_fingerprint(
                message="继续",
                message_kind="dialogue",
                suppress_transcript_message=False,
            )
            store.mark_pending(
                "run-1",
                "session-1",
                "operation-1",
                fingerprint=fingerprint,
                turn_id="turn-1",
            )

            other = store.request_fingerprint(
                message="换一句",
                message_kind="dialogue",
                suppress_transcript_message=False,
            )
            with self.assertRaises(ReplyOperationConflict):
                store.load(
                    "run-1",
                    "session-1",
                    "operation-1",
                    fingerprint=other,
                )

    def test_stream_projects_readable_delta_before_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)

            def generate(_run_id, _payload, *, on_delta=None, on_attempt=None):
                self.assertIsNotNone(on_attempt)
                self.assertIsNotNone(on_delta)
                on_attempt(0)
                on_reasoning = getattr(on_delta, "on_reasoning", None)
                self.assertTrue(callable(on_reasoning))
                on_reasoning("private reasoning")
                on_delta('{"responses":[{"speaker":"乙","message":"回复正在')
                on_delta('逐步显示。"}]}')
                return [{"speaker": "乙", "message": "回复正在逐步显示。"}]

            with patch.object(
                service,
                "_generate_dialogue_responses",
                side_effect=generate,
            ):
                events = list(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message="请回答。",
                        operation_id="operation-stream-delta",
                    )
                )

            event_names = [event for event, _payload in events]
            delta_indexes = [
                index for index, event in enumerate(event_names) if event == "delta"
            ]
            self.assertTrue(delta_indexes)
            self.assertLess(delta_indexes[0], event_names.index("complete"))
            readable_text = "".join(
                payload["text"] for event, payload in events if event == "delta"
            )
            self.assertEqual(readable_text, "回复正在逐步显示。")
            self.assertNotIn('"responses"', readable_text)
            reasoning_statuses = [
                payload
                for event, payload in events
                if event == "status" and payload.get("phase") == "reasoning"
            ]
            self.assertEqual(len(reasoning_statuses), 1)
            self.assertNotIn("private reasoning", str(reasoning_statuses))

    def test_forced_recovery_discards_a_late_model_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)
            generation_started = threading.Event()
            allow_generation_to_finish = threading.Event()
            events = []

            def generate(_run_id, _payload, *, on_delta=None, on_attempt=None):
                generation_started.set()
                self.assertTrue(allow_generation_to_finish.wait(timeout=5))
                return [{"speaker": "乙", "message": "stale reply"}]

            def consume_stream():
                events.extend(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message="continue",
                        operation_id="operation-cancelled-stream",
                        emit_deltas=False,
                    )
                )

            with patch.object(
                service,
                "_generate_dialogue_responses",
                side_effect=generate,
            ):
                consumer = threading.Thread(target=consume_stream)
                consumer.start()
                self.assertTrue(generation_started.wait(timeout=5))
                service.recover_dialogue_session(run_id, session_id, force=True)
                allow_generation_to_finish.set()
                consumer.join(timeout=5)

            self.assertFalse(consumer.is_alive())
            session = service.dialogue.get_session(run_id, session_id)
            self.assertNotIn(
                "stale reply",
                [item.get("message") for item in session.get("transcript", [])],
            )
            operation = service.reply_operations.load(
                run_id, session_id, "operation-cancelled-stream"
            )
            self.assertEqual(operation["status"], "failed")
            self.assertTrue(operation["failure"]["retryable"])
            self.assertTrue(any(event == "error" for event, _payload in events))

    def test_stream_emits_reset_before_retry_deltas_and_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)

            def generate(_run_id, _payload, *, on_delta=None, on_attempt=None):
                self.assertIsNotNone(on_attempt)
                self.assertIsNotNone(on_delta)
                on_attempt(0)
                on_delta('{"responses":[{"speaker":"乙","message":"旧回复。"}]}')
                on_attempt(1)
                on_delta('{"responses":[{"speaker":"乙","message":"最终回复。"}]}')
                return [{"speaker": "乙", "message": "最终回复。"}]

            with patch.object(
                service,
                "_generate_dialogue_responses",
                side_effect=generate,
            ):
                events = list(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message="换一种说法。",
                        operation_id="operation-stream-retry",
                    )
                )

            event_names = [event for event, _payload in events]
            reset_index = event_names.index("reset")
            final_delta_index = next(
                index
                for index, (event, payload) in enumerate(events)
                if index > reset_index
                and event == "delta"
                and payload.get("text") == "最终回复。"
            )
            self.assertLess(reset_index, final_delta_index)
            self.assertLess(final_delta_index, event_names.index("complete"))

    def test_stream_emits_model_reasoning_only_when_enabled(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)

            def generate(_run_id, _payload, *, on_delta=None, on_attempt=None):
                on_attempt(0)
                on_delta.on_reasoning("先判断人物关系。")
                on_delta('{"responses":[{"speaker":"乙","message":"回答。"}]}')
                return [{"speaker": "乙", "message": "回答。"}]

            with patch.object(
                service,
                "_generate_dialogue_responses",
                side_effect=generate,
            ):
                events = list(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message="请回答。",
                        include_model_reasoning=True,
                        operation_id="operation-stream-reasoning-visible",
                    )
                )

            reasoning = [
                payload
                for event, payload in events
                if event == "delta" and payload.get("field") == "model_reasoning"
            ]
            self.assertEqual([item["text"] for item in reasoning], ["先判断人物关系。"])
            self.assertEqual(reasoning[0]["speaker"], "模型推理")

    def test_stream_adopts_matching_pending_turn_without_sidecar(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)
            message = "接着说。"
            prepared = service.dialogue.prepare_turn(
                service._require_manifest(run_id),
                session_id=session_id,
                message=message,
            )
            turn_id = prepared["pending_turn_summary"]["turn_id"]

            with patch.object(
                service.dialogue,
                "prepare_turn",
                side_effect=AssertionError("matching pending turn must be reused"),
            ) as prepare, patch.object(
                service,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "乙", "message": "那就继续。"}],
            ) as generate:
                events = list(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message=message,
                        operation_id="operation-adopt-pending",
                    )
                )

            prepare.assert_not_called()
            generate.assert_called_once()
            self.assertEqual(events[-1][0], "complete")
            record = service.reply_operations.load(
                run_id,
                session_id,
                "operation-adopt-pending",
            )
            self.assertEqual(record["turn_id"], turn_id)
            transcript = events[-1][1]["session"]["transcript"]
            self.assertEqual(
                [item["message"] for item in transcript].count(message),
                1,
            )

    def test_stream_recovers_pending_session_from_result_checkpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)
            message = "记住这一句。"
            prepared = service.dialogue.prepare_turn(
                service._require_manifest(run_id),
                session_id=session_id,
                message=message,
            )
            turn_id = prepared["pending_turn_summary"]["turn_id"]
            session_path = service.dialogue._session_file(run_id, session_id)
            pending_session = service.dialogue._read_json(session_path)

            service.dialogue.ingest_turn_responses(
                run_id,
                session_id=session_id,
                responses=[{"speaker": "乙", "message": "我记住了。"}],
            )
            result_path = service.dialogue._turn_file(
                run_id,
                session_id,
                turn_id,
                "result",
            )
            self.assertTrue(result_path.is_file())
            service.dialogue._write_json(session_path, pending_session)

            fingerprint = service.reply_operations.request_fingerprint(
                message=message,
                message_kind="dialogue",
                suppress_transcript_message=False,
            )
            service.reply_operations.mark_pending(
                run_id,
                session_id,
                "operation-checkpoint-recovery",
                fingerprint=fingerprint,
                turn_id=turn_id,
            )

            with patch.object(
                service,
                "_generate_dialogue_responses",
                side_effect=AssertionError("checkpoint recovery must not regenerate"),
            ) as generate:
                events = list(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message=message,
                        operation_id="operation-checkpoint-recovery",
                    )
                )

            generate.assert_not_called()
            self.assertEqual(events[-1][0], "complete")
            self.assertTrue(events[-1][1]["replayed"])
            transcript = events[-1][1]["session"]["transcript"]
            self.assertEqual(
                [item["message"] for item in transcript],
                [message, "我记住了。"],
            )
            recovered = service.dialogue._read_json(session_path)
            self.assertFalse(recovered.get("pending_turn"))

    def test_completed_operation_replays_when_result_file_is_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)
            message = "只生成一次。"
            operation_id = "operation-result-missing"
            fingerprint = service.reply_operations.request_fingerprint(
                message=message,
                message_kind="dialogue",
                suppress_transcript_message=False,
            )
            with patch.object(
                service,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "乙", "message": "这是唯一一次回复。"}],
            ) as first_generate:
                first = list(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message=message,
                        operation_id=operation_id,
                    )
                )

            first_generate.assert_called_once()
            self.assertEqual(first[-1][0], "complete")
            record = service.reply_operations.load(
                run_id,
                session_id,
                operation_id,
                fingerprint=fingerprint,
            )
            self.assertEqual(record["status"], "completed")
            turn_id = record["turn_id"]
            result_path = service.dialogue._turn_file(
                run_id,
                session_id,
                turn_id,
                "result",
            )
            self.assertTrue(result_path.is_file())
            result_path.unlink()

            with patch.object(
                service,
                "_generate_dialogue_responses",
                side_effect=AssertionError("completed operation must not regenerate"),
            ) as replay_generate:
                replay = list(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message=message,
                        operation_id=operation_id,
                    )
                )

            replay_generate.assert_not_called()
            self.assertEqual(replay[-1][0], "complete")
            self.assertTrue(replay[-1][1]["replayed"])
            transcript = replay[-1][1]["session"]["transcript"]
            messages = [item["message"] for item in transcript]
            self.assertEqual(messages.count(message), 1)
            self.assertEqual(messages.count("这是唯一一次回复。"), 1)

    def test_closing_stream_after_delta_keeps_background_commit_replayable(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)
            message = "断开后继续完成。"
            operation_id = "operation-client-disconnect"
            allow_generation_to_finish = threading.Event()
            operation_completed = threading.Event()
            original_mark_completed = service.reply_operations.mark_completed

            def generate(_run_id, _payload, *, on_delta=None, on_attempt=None):
                self.assertIsNotNone(on_attempt)
                self.assertIsNotNone(on_delta)
                on_attempt(0)
                on_delta(
                    '{"responses":[{"speaker":"乙","message":"即使连接断开，'
                )
                if not allow_generation_to_finish.wait(timeout=5):
                    raise AssertionError("test did not release background generation")
                on_delta('我也会完成。"}]}')
                return [{"speaker": "乙", "message": "即使连接断开，我也会完成。"}]

            def mark_completed(*args, **kwargs):
                record = original_mark_completed(*args, **kwargs)
                operation_completed.set()
                return record

            with patch.object(
                service,
                "_generate_dialogue_responses",
                side_effect=generate,
            ) as generate_mock, patch.object(
                service.reply_operations,
                "mark_completed",
                side_effect=mark_completed,
            ):
                stream = service.stream_dialogue_reply_events(
                    run_id,
                    session_id=session_id,
                    message=message,
                    operation_id=operation_id,
                )
                observed_delta = None
                for event, payload in stream:
                    if event == "delta":
                        observed_delta = payload
                        break
                self.assertIsNotNone(observed_delta)
                stream.close()

                allow_generation_to_finish.set()
                self.assertTrue(
                    operation_completed.wait(timeout=5),
                    "background worker did not persist the completed operation",
                )
                replay = list(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message=message,
                        operation_id=operation_id,
                    )
                )

            self.assertEqual(generate_mock.call_count, 1)
            self.assertEqual(replay[-1][0], "complete")
            self.assertTrue(replay[-1][1]["replayed"])
            messages = [
                item["message"] for item in replay[-1][1]["session"]["transcript"]
            ]
            self.assertEqual(messages.count(message), 1)
            self.assertEqual(messages.count("即使连接断开，我也会完成。"), 1)

    def test_concurrent_streams_for_same_operation_generate_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)
            message = "并发发送。"
            operation_id = "operation-concurrent"
            start_barrier = threading.Barrier(3)
            model_started = threading.Event()
            losing_claim_observed = threading.Event()
            allow_generation_to_finish = threading.Event()
            original_claim = service.reply_operations.claim
            results = [None, None]
            failures = []

            def claim(*args, **kwargs):
                claimed = original_claim(*args, **kwargs)
                if not claimed:
                    losing_claim_observed.set()
                return claimed

            def generate(_run_id, _payload, *, on_delta=None, on_attempt=None):
                model_started.set()
                if not allow_generation_to_finish.wait(timeout=5):
                    raise AssertionError("test did not release concurrent generation")
                return [{"speaker": "乙", "message": "只调用一次模型。"}]

            def consume(index: int) -> None:
                try:
                    start_barrier.wait(timeout=5)
                    results[index] = list(
                        service.stream_dialogue_reply_events(
                            run_id,
                            session_id=session_id,
                            message=message,
                            operation_id=operation_id,
                        )
                    )
                except BaseException as exc:
                    failures.append(exc)

            with patch.object(
                service.reply_operations,
                "claim",
                side_effect=claim,
            ), patch.object(
                service,
                "_generate_dialogue_responses",
                side_effect=generate,
            ) as generate_mock:
                callers = [
                    threading.Thread(target=consume, args=(index,), daemon=True)
                    for index in range(2)
                ]
                for caller in callers:
                    caller.start()
                start_barrier.wait(timeout=5)
                self.assertTrue(model_started.wait(timeout=5))
                self.assertTrue(losing_claim_observed.wait(timeout=5))
                allow_generation_to_finish.set()
                for caller in callers:
                    caller.join(timeout=5)
                    self.assertFalse(caller.is_alive(), "concurrent stream did not finish")

            self.assertEqual(failures, [])
            self.assertEqual(generate_mock.call_count, 1)
            self.assertTrue(all(events for events in results))
            self.assertEqual([events[-1][0] for events in results], ["complete", "complete"])
            self.assertEqual(
                sorted(events[-1][1]["replayed"] for events in results),
                [False, True],
            )

    def test_different_operations_cannot_share_an_active_pending_turn(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)
            model_started = threading.Event()
            allow_generation_to_finish = threading.Event()
            first_events = []
            first_failures = []

            def generate(_run_id, _payload, *, on_delta=None, on_attempt=None):
                model_started.set()
                if not allow_generation_to_finish.wait(timeout=5):
                    raise AssertionError("test did not release the first generation")
                return [{"speaker": "乙", "message": "只属于第一次发送。"}]

            def consume_first() -> None:
                try:
                    first_events.extend(
                        service.stream_dialogue_reply_events(
                            run_id,
                            session_id=session_id,
                            message="相同内容。",
                            operation_id="operation-owner-a",
                        )
                    )
                except BaseException as exc:
                    first_failures.append(exc)

            with patch.object(
                service,
                "_generate_dialogue_responses",
                side_effect=generate,
            ) as generate_mock:
                first_thread = threading.Thread(target=consume_first, daemon=True)
                first_thread.start()
                self.assertTrue(model_started.wait(timeout=5))

                second_events = list(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message="相同内容。",
                        operation_id="operation-owner-b",
                    )
                )
                allow_generation_to_finish.set()
                first_thread.join(timeout=5)

            self.assertFalse(first_thread.is_alive())
            self.assertEqual(first_failures, [])
            self.assertEqual(generate_mock.call_count, 1)
            self.assertEqual(first_events[-1][0], "complete")
            self.assertEqual(second_events[-1][0], "error")
            self.assertFalse(second_events[-1][1]["retryable"])

    def test_recover_does_not_abort_an_active_background_reply(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)
            model_started = threading.Event()
            allow_generation_to_finish = threading.Event()
            stream_events = []
            stream_failures = []

            def generate(_run_id, _payload, *, on_delta=None, on_attempt=None):
                model_started.set()
                if not allow_generation_to_finish.wait(timeout=5):
                    raise AssertionError("test did not release background generation")
                return [{"speaker": "乙", "message": "后台回复已经落盘。"}]

            def consume_stream() -> None:
                try:
                    stream_events.extend(
                        service.stream_dialogue_reply_events(
                            run_id,
                            session_id=session_id,
                            message="连接已经断开。",
                            operation_id="operation-active-recovery",
                        )
                    )
                except BaseException as exc:
                    stream_failures.append(exc)

            with patch.object(
                service,
                "_generate_dialogue_responses",
                side_effect=generate,
            ) as generate_mock:
                stream_thread = threading.Thread(target=consume_stream, daemon=True)
                stream_thread.start()
                self.assertTrue(model_started.wait(timeout=5))

                recovered_while_active = service.recover_dialogue_session(
                    run_id,
                    session_id,
                )
                self.assertEqual(
                    recovered_while_active["status"],
                    "waiting_for_host_reply",
                )

                allow_generation_to_finish.set()
                stream_thread.join(timeout=5)
                replay = list(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message="连接已经断开。",
                        operation_id="operation-active-recovery",
                    )
                )

            self.assertFalse(stream_thread.is_alive())
            self.assertEqual(stream_failures, [])
            self.assertEqual(generate_mock.call_count, 1)
            self.assertEqual(stream_events[-1][0], "complete")
            self.assertEqual(replay[-1][0], "complete")
            self.assertTrue(replay[-1][1]["replayed"])
            messages = [item["message"] for item in replay[-1][1]["session"]["transcript"]]
            self.assertEqual(messages.count("后台回复已经落盘。"), 1)

    def test_stream_rejects_operation_id_reuse_for_different_message(self):
        with tempfile.TemporaryDirectory() as tmp:
            service, run_id, session_id = self._create_dialogue(tmp)
            operation_id = "operation-message-conflict"
            with patch.object(
                service,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "乙", "message": "第一次回复。"}],
            ) as generate:
                first = list(
                    service.stream_dialogue_reply_events(
                        run_id,
                        session_id=session_id,
                        message="第一条消息。",
                        operation_id=operation_id,
                    )
                )
                with self.assertRaises(ReplyOperationConflict):
                    list(
                        service.stream_dialogue_reply_events(
                            run_id,
                            session_id=session_id,
                            message="不同的第二条消息。",
                            operation_id=operation_id,
                        )
                    )

            self.assertEqual(first[-1][0], "complete")
            self.assertEqual(generate.call_count, 1)

    def test_replaying_same_operation_does_not_generate_or_append_twice(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="test-model",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            run = service.create_run(
                novel_name="story.txt",
                novel_content_base64=base64.b64encode("甲遇见乙。".encode()).decode(),
                characters=["甲", "乙"],
            )
            for character in ("甲", "乙"):
                service.ingest_character_result(
                    run["run_id"],
                    character=character,
                    content_base64=base64.b64encode(
                        f"- name: {character}\n- core_identity: 人物\n".encode()
                    ).decode(),
                )
            session = service.dialogue.create_session(
                service._require_manifest(run["run_id"]),
                mode="act",
                participants=["甲", "乙"],
                controlled_character="甲",
            )
            response = [{"speaker": "乙", "message": "我一直在这里等你。"}]
            with patch.object(
                service,
                "_generate_dialogue_responses",
                return_value=response,
            ) as generate:
                first = list(
                    service.stream_dialogue_reply_events(
                        run["run_id"],
                        session_id=session["session_id"],
                        message="你还在吗？",
                        operation_id="operation-stable",
                    )
                )
                second = list(
                    service.stream_dialogue_reply_events(
                        run["run_id"],
                        session_id=session["session_id"],
                        message="你还在吗？",
                        operation_id="operation-stable",
                    )
                )

            generate.assert_called_once()
            self.assertEqual(first[-1][0], "complete")
            self.assertEqual(second[-1][0], "complete")
            self.assertTrue(second[-1][1]["replayed"])
            final_session = second[-1][1]["session"]
            messages = [item["message"] for item in final_session["transcript"]]
            self.assertEqual(messages.count("你还在吗？"), 1)
            self.assertEqual(messages.count("我一直在这里等你。"), 1)

            memory_store = service._dialogue_memory_store_for_run(run["run_id"])
            memory_store.append_long_term_memory(
                session["session_id"],
                "乙: 很早以前我们在旧桥下约定过。",
                metadata={
                    "speaker": "乙",
                    "turn_id": "turn-archived",
                    "ts": "2025-01-01T00:00:00Z",
                },
            )
            search_results = service.search_dialogue_session(
                run["run_id"],
                session_id=session["session_id"],
                query="旧桥",
            )
            self.assertEqual(search_results[0]["message"], "很早以前我们在旧桥下约定过。")
            self.assertTrue(search_results[0]["archived"])
            self.assertEqual(search_results[0]["turn_id"], "turn-archived")


if __name__ == "__main__":
    unittest.main()
