from __future__ import annotations

from functools import wraps
import queue
import threading
import time
from typing import Any

from src.core.exceptions import LLMRequestError
import src.web.chat.scene_signals as _scene_signals
from src.web.chat.reply_operations import ReplyOperationConflict
from src.web.chat.streaming import DialogueJsonDeltaProjector
from src.web.chat import (
    associate_dialogue_turn_payload,
    build_dialogue_association_llm_messages,
    build_dialogue_consistency_review_messages,
    build_dialogue_director_llm_messages,
    build_dialogue_llm_messages,
    build_dialogue_opening_message,
    build_dialogue_relation_state_messages,
    build_dialogue_scene_progress_messages,
    build_dialogue_suggestion_llm_messages,
    compact_dialogue_suggestion_payload,
    continue_dialogue_scene_opening_payload,
    create_dialogue_session_payload,
    friendly_dialogue_llm_error,
    generate_dialogue_associations,
    generate_dialogue_associations_for_run,
    generate_dialogue_responses,
    generate_dialogue_responses_for_run,
    generate_dialogue_suggestion,
    generate_dialogue_suggestion_for_run,
    parse_dialogue_associations,
    parse_dialogue_consistency_review,
    parse_dialogue_director_options,
    parse_dialogue_responses,
    parse_dialogue_relation_state,
    parse_dialogue_scene_progress,
    parse_dialogue_suggestion,
    reply_dialogue_turn_payload,
    should_retry_suggestion_with_compact_payload,
)
from src.web.service_facades.scene_cards import SceneCardServiceMixin


def _with_dialogue_session_lock(method):
    @wraps(method)
    def locked(self, run_id: str, pending_payload: dict[str, Any], *args, **kwargs):
        session_id = str(pending_payload.get("session_id", "")).strip()
        if not session_id:
            return method(self, run_id, pending_payload, *args, **kwargs)
        with self.dialogue.session_lock(run_id, session_id):
            return method(self, run_id, pending_payload, *args, **kwargs)

    return locked


def build_runtime_parts(config: Any) -> Any:
    from src.web.workflow import WebRunService

    return WebRunService._build_runtime_parts(config)


class DialogueServiceMixin:
    def get_original_knowledge(self, run_id: str) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        manifest = self._require_manifest(run_id)
        current = self.dialogue.get_original_knowledge(run_id)
        if not current.get("entries"):
            current = self.dialogue.rebuild_original_knowledge(manifest)
        return current

    def rebuild_original_knowledge(self, run_id: str) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        return self.dialogue.rebuild_original_knowledge(manifest)

    def search_original_knowledge(
        self,
        run_id: str,
        *,
        query: str,
        participants: list[str],
        limit: int = 6,
    ) -> list[dict[str, Any]]:
        manifest = self._require_manifest(run_id)
        return self.dialogue.search_original_knowledge(
            manifest,
            query=query,
            participants=participants,
            limit=limit,
        )

    def update_original_knowledge_boundary(
        self,
        run_id: str,
        entry_id: str,
        *,
        visibility: str,
        knowers: list[str],
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        return self.dialogue.update_original_knowledge_boundary(
            run_id,
            entry_id,
            visibility=visibility,
            knowers=knowers,
        )

    def list_dialogue_sessions(self, run_id: str) -> list[dict[str, Any]]:
        self._ensure_run_exists(run_id)
        return self.dialogue.list_sessions(run_id)

    def update_dialogue_session_title(
        self, run_id: str, *, session_id: str, title: str
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        return self.dialogue.update_session_title(run_id, session_id, title=title)

    def create_dialogue_session(
        self,
        run_id: str,
        *,
        mode: str,
        participants: list[str],
        controlled_character: str = "",
        scene_card_id: str = "",
        scene_profile: dict[str, str] | None = None,
        self_card_id: str = "",
        self_profile: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        resolved_scene_profile = dict(scene_profile or {})
        if scene_card_id:
            try:
                card = self.get_scene_card(scene_card_id)
            except FileNotFoundError as exc:
                raise ValueError("所选场景卡不存在。") from exc
            resolved_scene_profile = {
                **dict(card.get("fields", {}) or {}),
                **resolved_scene_profile,
                "scene_card_id": str(card.get("card_id", "")).strip(),
            }
        resolved_self_profile = dict(self_profile or {})
        if mode == "insert" and self_card_id:
            try:
                card = self.get_self_card(self_card_id)
            except FileNotFoundError as exc:
                raise ValueError("所选角色卡不存在。") from exc
            resolved_self_profile = {
                **dict(card.get("fields", {}) or {}),
                **resolved_self_profile,
                "self_card_id": str(card.get("card_id", "")).strip(),
            }
        # Build the local source index before the user starts chatting so a first
        # reply never pays the one-time indexing cost. Failure remains non-fatal:
        # legacy runs without a readable source keep their existing chat behavior.
        try:
            if not self.dialogue.get_original_knowledge(run_id).get("entries"):
                self.dialogue.rebuild_original_knowledge(manifest)
        except (FileNotFoundError, OSError, UnicodeError, ValueError):
            pass
        return create_dialogue_session_payload(
            run_id=run_id,
            manifest=manifest,
            dialogue=self.dialogue,
            mode=mode,
            participants=participants,
            controlled_character=controlled_character,
            scene_profile=resolved_scene_profile,
            self_profile=resolved_self_profile,
            build_dialogue_opening_message=build_dialogue_opening_message,
            load_pending_turn_payload=self._load_pending_turn_payload,
            generate_dialogue_responses=self._generate_dialogue_responses,
            friendly_dialogue_llm_error=friendly_dialogue_llm_error,
            evolve_relations_from_turn=self._evolve_relations_from_turn,
            refresh_scene_progress=self._refresh_dialogue_scene_progress,
        )

    def get_dialogue_session(self, run_id: str, session_id: str) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        return self.dialogue.get_session(run_id, session_id)

    def search_dialogue_session(
        self,
        run_id: str,
        *,
        session_id: str,
        query: str,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        self._ensure_run_exists(run_id)
        return self.dialogue.search_session_transcript(
            run_id,
            session_id,
            query=query,
            limit=limit,
        )

    def recover_dialogue_session(
        self, run_id: str, session_id: str, *, force: bool = False
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        if self.reply_operations.has_active_session_operation(run_id, session_id):
            if not force:
                return self.dialogue.get_session(run_id, session_id)
            self.reply_operations.cancel_active_session_operations(
                run_id,
                session_id,
                message="Reply abandoned after the client left the conversation.",
            )
        return self.dialogue.abort_pending_turn(
            run_id,
            session_id,
            reason="client_forced_recovery" if force else "client_recovery",
        )

    def correct_latest_dialogue_turn(
        self, run_id: str, *, session_id: str
    ) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        branch, correction_context = self.dialogue.create_correction_branch(
            manifest, session_id
        )
        branch_id = str(branch.get("session_id", "")).strip()
        prepared = self.dialogue.prepare_turn(
            manifest,
            session_id=branch_id,
            message=str(correction_context.get("message", "")).strip(),
            message_kind=str(
                correction_context.get("message_kind", "dialogue")
            ).strip()
            or "dialogue",
        )
        expected_turn_id = str(
            dict(prepared.get("pending_turn_summary", {}) or {}).get("turn_id", "")
        ).strip()
        try:
            pending_payload = self._load_pending_turn_payload(run_id, branch_id)
            pending_payload["correction_context"] = correction_context
            try:
                generated = self._generate_dialogue_responses(run_id, pending_payload)
            except LLMRequestError as exc:
                raise ValueError(friendly_dialogue_llm_error(exc)) from exc
            if isinstance(generated, dict):
                responses = list(generated.get("responses", []) or [])
                generation_cache = generated.get("generation_cache")
            else:
                responses = list(generated or [])
                generation_cache = None
            self._evolve_relations_from_turn(
                run_id, pending_payload, responses, refine_with_llm=False
            )
            corrected = self.dialogue.ingest_turn_responses(
                run_id,
                session_id=branch_id,
                responses=responses,
                remember_turn_memory=True,
                generation_cache=(
                    dict(generation_cache)
                    if isinstance(generation_cache, dict)
                    else None
                ),
            )
            return self._refresh_dialogue_scene_progress(
                run_id, corrected, use_llm=False
            )
        except Exception:
            try:
                self.dialogue.abort_pending_turn(
                    run_id,
                    branch_id,
                    expected_turn_id=expected_turn_id,
                    reason="correction_failed",
                )
            except Exception:
                pass
            raise

    def deep_review_latest_dialogue_turn(
        self, run_id: str, *, session_id: str
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        payload = self.dialogue.build_consistency_review_payload(run_id, session_id)
        try:
            review = self._generate_dialogue_consistency_review(run_id, payload)
        except LLMRequestError as exc:
            raise ValueError(friendly_dialogue_llm_error(exc)) from exc
        return self.dialogue.apply_semantic_consistency_review(
            run_id,
            session_id=session_id,
            review=review,
            expected_turn_id=str(payload.get("turn_id", "")).strip(),
        )

    def branch_dialogue_session_from_scene(
        self, run_id: str, *, session_id: str, scene_index: int
    ) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        return self.dialogue.branch_session_from_scene(
            manifest,
            session_id,
            scene_index=scene_index,
        )

    def branch_dialogue_session_from_turn(
        self, run_id: str, *, session_id: str, turn_id: str
    ) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        return self.dialogue.branch_session_from_turn(
            manifest,
            session_id,
            turn_id=turn_id,
        )

    def update_dialogue_branch_metadata(
        self,
        run_id: str,
        *,
        session_id: str,
        label: str | None = None,
        is_mainline: bool | None = None,
        locked_event_ids: list[str] | None = None,
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        return self.dialogue.update_branch_metadata(
            run_id,
            session_id,
            label=label,
            is_mainline=is_mainline,
            locked_event_ids=locked_event_ids,
        )

    def set_dialogue_relation_lock(
        self,
        run_id: str,
        *,
        session_id: str,
        pair_key: str,
        locked: bool,
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        return self.dialogue.set_relation_lock(
            run_id,
            session_id,
            pair_key=pair_key,
            locked=locked,
        )

    def save_dialogue_memory(
        self,
        run_id: str,
        *,
        session_id: str,
        text: str,
        category: str,
        pinned: bool,
        enabled: bool,
        memory_id: str = "",
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        return self.dialogue.upsert_controlled_memory(
            run_id,
            session_id,
            text=text,
            category=category,
            pinned=pinned,
            enabled=enabled,
            memory_id=memory_id,
        )

    def delete_dialogue_memory(
        self, run_id: str, *, session_id: str, memory_id: str
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        return self.dialogue.delete_controlled_memory(
            run_id, session_id, memory_id=memory_id
        )

    def switch_dialogue_scene_card(
        self,
        run_id: str,
        *,
        session_id: str,
        scene_card_id: str = "",
        scene_profile: dict[str, str] | None = None,
        transition_message: str = "",
        auto_continue: bool = False,
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        resolved_scene_profile = dict(scene_profile or {})
        if scene_card_id:
            try:
                card = self.get_scene_card(scene_card_id)
            except FileNotFoundError as exc:
                raise ValueError("所选场景卡不存在。") from exc
            resolved_scene_profile = {
                **dict(card.get("fields", {}) or {}),
                **resolved_scene_profile,
                "scene_card_id": str(card.get("card_id", "")).strip(),
            }
        switched = self.dialogue.update_scene_card(
            run_id,
            session_id,
            scene_profile=resolved_scene_profile,
            transition_message=transition_message,
        )
        if not auto_continue:
            return switched
        manifest = self._require_manifest(run_id)
        return continue_dialogue_scene_opening_payload(
            run_id=run_id,
            session=switched,
            manifest=manifest,
            dialogue=self.dialogue,
            build_dialogue_opening_message=build_dialogue_opening_message,
            load_pending_turn_payload=self._load_pending_turn_payload,
            generate_dialogue_responses=self._generate_dialogue_responses,
            friendly_dialogue_llm_error=friendly_dialogue_llm_error,
            evolve_relations_from_turn=self._evolve_relations_from_turn,
            refresh_scene_progress=self._refresh_dialogue_scene_progress,
        )

    def recommend_dialogue_scene_card(
        self, run_id: str, *, session_id: str
    ) -> dict[str, Any]:
        return SceneCardServiceMixin.recommend_dialogue_scene_card(
            self, run_id, session_id=session_id
        )

    def delete_dialogue_session(self, run_id: str, session_id: str) -> None:
        self._ensure_run_exists(run_id)
        self.dialogue.delete_session(run_id, session_id)

    def prepare_dialogue_turn(
        self,
        run_id: str,
        *,
        session_id: str,
        message: str,
        message_kind: str = "dialogue",
        suppress_transcript_message: bool = False,
        include_inner_thoughts: bool = False,
    ) -> dict[str, Any]:
        include_inner_thoughts = bool(
            self.resolve_generation_enhancer_options(run_id, session_id).get(
                "include_inner_thoughts", False
            )
        )
        manifest = self._require_manifest(run_id)
        return self.dialogue.prepare_turn(
            manifest,
            session_id=session_id,
            message=message,
            message_kind=message_kind,
            transcript_message="" if suppress_transcript_message else None,
            include_inner_thoughts=include_inner_thoughts,
        )

    def reply_dialogue_turn(
        self,
        run_id: str,
        *,
        session_id: str,
        message: str,
        message_kind: str = "dialogue",
        suppress_transcript_message: bool = False,
        include_inner_thoughts: bool = False,
        fast_response: bool = False,
        operation_id: str = "",
    ) -> dict[str, Any]:
        if operation_id:
            for event, payload in self.stream_dialogue_reply_events(
                run_id,
                session_id=session_id,
                message=message,
                message_kind=message_kind,
                suppress_transcript_message=suppress_transcript_message,
                include_inner_thoughts=include_inner_thoughts,
                operation_id=operation_id,
                emit_deltas=False,
            ):
                if event == "complete":
                    return dict(payload.get("session", {}) or {})
                if event == "error":
                    raise ValueError(str(payload.get("message", "Reply generation failed.")))
            raise ValueError("Reply operation ended without a result.")

        include_inner_thoughts = bool(
            self.resolve_generation_enhancer_options(run_id, session_id).get(
                "include_inner_thoughts", False
            )
        )
        manifest = self._require_manifest(run_id)

        def evolve_relations(
            current_run_id: str,
            pending_payload: dict[str, Any],
            responses: list[dict[str, str]],
        ) -> None:
            self._evolve_relations_from_turn(
                current_run_id,
                pending_payload,
                responses,
                refine_with_llm=not fast_response,
            )

        def refresh_scene(
            current_run_id: str, session: dict[str, Any]
        ) -> dict[str, Any]:
            return self._refresh_dialogue_scene_progress(
                current_run_id,
                session,
                use_llm=not fast_response,
            )

        return reply_dialogue_turn_payload(
            run_id=run_id,
            session_id=session_id,
            message=message,
            message_kind=message_kind,
            suppress_transcript_message=suppress_transcript_message,
            include_inner_thoughts=include_inner_thoughts,
            manifest=manifest,
            dialogue=self.dialogue,
            load_pending_turn_payload=self._load_pending_turn_payload,
            generate_dialogue_responses=self._generate_dialogue_responses,
            friendly_dialogue_llm_error=friendly_dialogue_llm_error,
            evolve_relations_from_turn=evolve_relations,
            refresh_scene_progress=refresh_scene,
        )

    def stream_dialogue_reply_events(
        self,
        run_id: str,
        *,
        session_id: str,
        message: str,
        message_kind: str = "dialogue",
        suppress_transcript_message: bool = False,
        include_inner_thoughts: bool = False,
        include_model_reasoning: bool = False,
        operation_id: str,
        emit_deltas: bool = True,
    ):
        include_inner_thoughts = bool(
            self.resolve_generation_enhancer_options(run_id, session_id).get(
                "include_inner_thoughts", False
            )
        )
        manifest = self._require_manifest(run_id)
        normalized_kind = str(message_kind or "dialogue").strip().lower() or "dialogue"
        is_plot_push = normalized_kind in {"plot", "plot_push", "advance"}
        fingerprint = self.reply_operations.request_fingerprint(
            message=message,
            message_kind=normalized_kind,
            suppress_transcript_message=suppress_transcript_message,
            include_inner_thoughts=include_inner_thoughts,
        )

        def reconciled_session(current: dict[str, Any]) -> dict[str, Any] | None:
            recorded_turn_id = str(current.get("turn_id", "")).strip()
            if not recorded_turn_id:
                return None
            return self.dialogue.reconcile_turn_result(
                run_id,
                session_id,
                turn_id=recorded_turn_id,
            )

        record = self.reply_operations.load(
            run_id,
            session_id,
            operation_id,
            fingerprint=fingerprint,
        )
        completed_session = reconciled_session(record) if record else None
        if completed_session is not None:
            if record.get("status") != "completed":
                record = self.reply_operations.mark_completed(
                    run_id,
                    session_id,
                    operation_id,
                    fingerprint=fingerprint,
                    turn_id=str(record.get("turn_id", "")).strip(),
                )
            yield "complete", {
                "session": completed_session,
                "replayed": True,
                "operation_id": operation_id,
            }
            return
        if record.get("status") == "completed":
            yield "error", {
                "message": "本地回复记录不完整，已停止自动重试以避免重复生成。",
                "retryable": False,
                "operation_id": operation_id,
            }
            return

        cancellation = self.reply_operations.claim(run_id, session_id, operation_id)
        if cancellation is None:
            yield "status", {
                "phase": "waiting",
                "message": "正在等待原来的生成完成…",
                "operation_id": operation_id,
            }
            deadline = time.monotonic() + 5 * 60
            next_heartbeat = time.monotonic() + 4
            while time.monotonic() < deadline:
                current = self.reply_operations.load(
                    run_id,
                    session_id,
                    operation_id,
                    fingerprint=fingerprint,
                )
                completed_session = reconciled_session(current) if current else None
                if completed_session is not None:
                    if current.get("status") != "completed":
                        current = self.reply_operations.mark_completed(
                            run_id,
                            session_id,
                            operation_id,
                            fingerprint=fingerprint,
                            turn_id=str(current.get("turn_id", "")).strip(),
                        )
                    yield "complete", {
                        "session": completed_session,
                        "replayed": True,
                        "operation_id": operation_id,
                    }
                    return
                if current.get("status") == "completed":
                    yield "error", {
                        "message": "本地回复记录不完整，已停止自动重试以避免重复生成。",
                        "retryable": False,
                        "operation_id": operation_id,
                    }
                    return
                if current.get("status") == "failed":
                    failure = dict(current.get("failure", {}) or {})
                    yield "error", {
                        "message": str(failure.get("message", "回复生成失败。")),
                        "retryable": bool(failure.get("retryable", True)),
                        "operation_id": operation_id,
                    }
                    return
                now = time.monotonic()
                if now >= next_heartbeat:
                    yield "status", {
                        "phase": "waiting",
                        "message": "原来的回复仍在生成，本机正在继续等待…",
                        "operation_id": operation_id,
                    }
                    next_heartbeat = now + 4
                time.sleep(0.25)
            yield "error", {
                "message": "等待原生成超时，可稍后用同一次发送重试。",
                "retryable": True,
                "operation_id": operation_id,
            }
            return

        turn_id = ""
        release_in_worker = False
        try:
            session = self.dialogue.get_session(run_id, session_id)
            record = self.reply_operations.load(
                run_id,
                session_id,
                operation_id,
                fingerprint=fingerprint,
            )
            if not record:
                record = self.reply_operations.mark_pending(
                    run_id,
                    session_id,
                    operation_id,
                    fingerprint=fingerprint,
                    turn_id="",
                )
            pending_turn = dict(session.get("pending_turn_summary", {}) or {})
            recorded_turn_id = str(record.get("turn_id", "")).strip()
            pending_turn_id = str(pending_turn.get("turn_id", "")).strip()

            completed_session = reconciled_session(record)
            if completed_session is not None:
                if record.get("status") != "completed":
                    record = self.reply_operations.mark_completed(
                        run_id,
                        session_id,
                        operation_id,
                        fingerprint=fingerprint,
                        turn_id=str(record.get("turn_id", "")).strip(),
                    )
                yield "complete", {
                    "session": completed_session,
                    "replayed": True,
                    "operation_id": operation_id,
                }
                return
            if record.get("status") == "completed":
                yield "error", {
                    "message": "本地回复记录不完整，已停止自动重试以避免重复生成。",
                    "retryable": False,
                    "operation_id": operation_id,
                }
                return
            pending_matches_request = (
                bool(pending_turn_id)
                and str(pending_turn.get("message", "")).strip() == str(message).strip()
                and str(pending_turn.get("message_kind", "")).strip().lower()
                == normalized_kind
            )
            if pending_matches_request and self.reply_operations.find_pending_owner(
                run_id,
                session_id,
                fingerprint=fingerprint,
                turn_id=pending_turn_id,
                excluding_operation_id=operation_id,
            ):
                raise ReplyOperationConflict(
                    "当前待处理回复属于另一发送，请等待原请求完成。"
                )
            if recorded_turn_id and recorded_turn_id == pending_turn_id:
                turn_id = recorded_turn_id
            elif not recorded_turn_id and pending_matches_request:
                turn_id = pending_turn_id
                record = self.reply_operations.mark_pending(
                    run_id,
                    session_id,
                    operation_id,
                    fingerprint=fingerprint,
                    turn_id=turn_id,
                )
            else:
                speaker_override = (
                    "场景提示"
                    if normalized_kind in {"narration", "plot", "plot_push", "advance"}
                    else ""
                )
                prepared = self.dialogue.prepare_turn(
                    manifest,
                    session_id=session_id,
                    message=message,
                    message_kind=normalized_kind,
                    speaker_override=speaker_override,
                    transcript_message=(
                        "" if suppress_transcript_message or is_plot_push else None
                    ),
                    include_inner_thoughts=include_inner_thoughts,
                    _serialize_result=False,
                )
                turn_id = str(
                    dict(prepared.get("pending_turn_summary", {}) or {}).get("turn_id", "")
                ).strip()
                self.reply_operations.mark_pending(
                    run_id,
                    session_id,
                    operation_id,
                    fingerprint=fingerprint,
                    turn_id=turn_id,
                )

            yield "status", {
                "phase": "generating",
                "message": "人物正在组织回应…",
                "operation_id": operation_id,
                "turn_id": turn_id,
            }
            pending_payload = self._load_pending_turn_payload(run_id, session_id)
            stream_queue: queue.Queue[tuple[str, Any]] = queue.Queue()
            reasoning_started = threading.Event()

            def ensure_not_cancelled() -> None:
                if cancellation.is_set():
                    raise InterruptedError("Reply operation was cancelled.")

            def emit_raw_delta(delta: str) -> None:
                ensure_not_cancelled()
                stream_queue.put(("raw_delta", delta))

            def emit_reasoning_activity(_delta: str) -> None:
                ensure_not_cancelled()
                if not reasoning_started.is_set():
                    reasoning_started.set()
                    stream_queue.put(("reasoning", None))
                if include_model_reasoning and _delta:
                    stream_queue.put(("raw_reasoning", _delta))

            def check_cancelled_delta(_delta: str) -> None:
                ensure_not_cancelled()

            def emit_attempt(index: int) -> None:
                ensure_not_cancelled()
                if emit_deltas:
                    stream_queue.put(("attempt", index))

            # LLMClient keeps reasoning text out of visible deltas, but recognizes
            # this optional hook so the UI can acknowledge model activity early.
            generation_delta_callback = (
                emit_raw_delta if emit_deltas else check_cancelled_delta
            )
            setattr(
                generation_delta_callback,
                "on_reasoning",
                emit_reasoning_activity if emit_deltas else check_cancelled_delta,
            )

            def generate_and_commit() -> None:
                try:
                    try:
                        generated = self._generate_dialogue_responses(
                            run_id,
                            pending_payload,
                            on_delta=generation_delta_callback,
                            on_attempt=emit_attempt,
                        )
                    except LLMRequestError as exc:
                        raise ValueError(friendly_dialogue_llm_error(exc)) from exc
                    if isinstance(generated, dict) and isinstance(
                        generated.get("responses"), list
                    ):
                        responses = list(generated.get("responses", []) or [])
                        generation_cache = generated.get("generation_cache")
                    else:
                        responses = list(generated or [])
                        generation_cache = None

                    ensure_not_cancelled()
                    ingest_kwargs: dict[str, Any] = {
                        "session_id": session_id,
                        "responses": responses,
                        "remember_turn_memory": True,
                    }
                    if isinstance(generation_cache, dict):
                        ingest_kwargs["generation_cache"] = generation_cache
                    completed = self.dialogue.ingest_turn_responses(
                        run_id,
                        **ingest_kwargs,
                    )
                    self._evolve_relations_from_turn(
                        run_id,
                        pending_payload,
                        responses,
                        refine_with_llm=False,
                    )
                    completed = self.dialogue.get_session(run_id, session_id)
                    completed = self._refresh_dialogue_scene_progress(
                        run_id,
                        completed,
                        use_llm=False,
                    )
                    self.reply_operations.mark_completed(
                        run_id,
                        session_id,
                        operation_id,
                        fingerprint=fingerprint,
                        turn_id=turn_id,
                        session_updated_at=str(completed.get("updated_at", "")).strip(),
                    )
                    stream_queue.put(
                        (
                            "complete",
                            {"session": completed, "responses": responses},
                        )
                    )
                except Exception as exc:
                    if cancellation.is_set():
                        stream_queue.put(
                            (
                                "error",
                                {
                                    "message": "Reply operation was cancelled.",
                                    "retryable": True,
                                },
                            )
                        )
                        return
                    retryable = not isinstance(exc, ReplyOperationConflict)
                    if turn_id:
                        try:
                            self.dialogue.abort_pending_turn(
                                run_id,
                                session_id,
                                expected_turn_id=turn_id,
                                reason="reply_failed",
                            )
                        except Exception:
                            pass
                    try:
                        self.reply_operations.mark_failed(
                            run_id,
                            session_id,
                            operation_id,
                            fingerprint=fingerprint,
                            turn_id=turn_id,
                            message=str(exc) or "回复生成失败。",
                            retryable=retryable,
                        )
                    except Exception:
                        pass
                    stream_queue.put(
                        (
                            "error",
                            {
                                "message": str(exc) or "回复生成失败。",
                                "retryable": retryable,
                            },
                        )
                    )
                finally:
                    self.reply_operations.release(
                        run_id,
                        session_id,
                        operation_id,
                        cancellation=cancellation,
                    )

            worker = threading.Thread(
                target=generate_and_commit,
                name=f"dialogue-reply-{operation_id[:12]}",
                daemon=True,
            )
            worker.start()
            release_in_worker = True
            projector = DialogueJsonDeltaProjector(chunk_size=24)
            projected_any = False
            next_heartbeat = time.monotonic() + 4

            while True:
                try:
                    event_kind, event_payload = stream_queue.get(timeout=1.0)
                except queue.Empty:
                    now = time.monotonic()
                    if now >= next_heartbeat:
                        yield "status", {
                            "phase": "generating",
                            "message": "人物仍在组织回应…",
                            "operation_id": operation_id,
                            "turn_id": turn_id,
                        }
                        next_heartbeat = now + 4
                    continue

                if event_kind == "attempt":
                    projector.reset()
                    projected_any = False
                    if int(event_payload or 0) > 0:
                        yield "reset", {
                            "message": "正在重新整理回复格式…",
                            "operation_id": operation_id,
                        }
                    continue
                if event_kind == "reasoning":
                    yield "status", {
                        "phase": "reasoning",
                        "message": "模型正在思考并组织回应…",
                        "operation_id": operation_id,
                        "turn_id": turn_id,
                    }
                    continue
                if event_kind == "raw_reasoning":
                    yield "delta", {
                        "index": -1,
                        "speaker": "模型推理",
                        "role": "reasoning",
                        "field": "model_reasoning",
                        "text": str(event_payload or ""),
                        "operation_id": operation_id,
                    }
                    continue
                if event_kind == "raw_delta":
                    for delta in projector.feed(str(event_payload or "")):
                        projected_any = True
                        yield "delta", {
                            **delta,
                            "operation_id": operation_id,
                        }
                    continue
                if event_kind == "error":
                    yield "error", {
                        **dict(event_payload or {}),
                        "operation_id": operation_id,
                    }
                    return
                if event_kind == "complete":
                    final_payload = dict(event_payload or {})
                    responses = list(final_payload.get("responses", []) or [])
                    if emit_deltas and not projected_any:
                        for index, response in enumerate(responses):
                            speaker = str(response.get("speaker", "")).strip()
                            text = str(response.get("message", "")).strip()
                            role = (
                                "scene"
                                if speaker in {"旁白", "场景提示"}
                                else "assistant"
                            )
                            for offset in range(0, len(text), 24):
                                yield "delta", {
                                    "index": index,
                                    "speaker": speaker,
                                    "role": role,
                                    "text": text[offset : offset + 24],
                                    "operation_id": operation_id,
                                }
                    yield "complete", {
                        "session": dict(final_payload.get("session", {}) or {}),
                        "replayed": False,
                        "operation_id": operation_id,
                    }
                    return
        except Exception as exc:
            if release_in_worker:
                yield "error", {
                    "message": str(exc) or "流式连接处理失败，可安全核对本地结果。",
                    "retryable": True,
                    "operation_id": operation_id,
                }
                return
            retryable = not isinstance(exc, ReplyOperationConflict)
            if turn_id:
                try:
                    self.dialogue.abort_pending_turn(
                        run_id,
                        session_id,
                        expected_turn_id=turn_id,
                        reason="reply_failed",
                    )
                except Exception:
                    pass
            self.reply_operations.mark_failed(
                run_id,
                session_id,
                operation_id,
                fingerprint=fingerprint,
                turn_id=turn_id,
                message=str(exc) or "回复生成失败。",
                retryable=retryable,
            )
            yield "error", {
                "message": str(exc) or "回复生成失败。",
                "retryable": retryable,
                "operation_id": operation_id,
            }
        finally:
            if not release_in_worker:
                self.reply_operations.release(
                    run_id,
                    session_id,
                    operation_id,
                    cancellation=cancellation,
                )

    def suggest_dialogue_turn(
        self,
        run_id: str,
        *,
        session_id: str,
        seed_text: str = "",
        direction: str = "",
    ) -> dict[str, str]:
        self._require_manifest(run_id)
        try:
            result = self.plugins.invoke_chat_action(
                "com.zaomeng.ai-association",
                "suggest-turn",
                {
                    "run_id": run_id,
                    "session_id": session_id,
                    "seed_text": seed_text,
                    "direction": direction,
                },
            )
        except LLMRequestError as exc:
            raise ValueError(friendly_dialogue_llm_error(exc)) from exc
        suggestion = str(result.get("suggestion", "")).strip()
        if not suggestion:
            raise ValueError("AI 联想插件没有返回可用的草稿。")
        return {"suggestion": suggestion}

    def associate_dialogue_turn(
        self,
        run_id: str,
        *,
        session_id: str,
        option_count: int = 3,
    ) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        return associate_dialogue_turn_payload(
            run_id=run_id,
            session_id=session_id,
            option_count=option_count,
            manifest=manifest,
            dialogue=self.dialogue,
            generate_dialogue_associations=self._generate_dialogue_associations,
            friendly_dialogue_llm_error=friendly_dialogue_llm_error,
        )

    def direct_dialogue_turn(
        self,
        run_id: str,
        *,
        session_id: str,
        goal: str,
        action: str = "advance",
        option_count: int = 3,
    ) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        payload = self.dialogue.build_director_payload(
            manifest,
            session_id=session_id,
            goal=goal,
            action=action,
            option_count=option_count,
        )
        try:
            options = self._generate_dialogue_director_options(run_id, payload)
        except LLMRequestError as exc:
            raise ValueError(friendly_dialogue_llm_error(exc)) from exc
        return {
            "goal": str(payload.get("director_goal", "")).strip(),
            "action": str(payload.get("director_action", "")).strip(),
            "options": options,
        }

    def ingest_dialogue_turn(
        self,
        run_id: str,
        *,
        session_id: str,
        responses: list[dict[str, str]],
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        session = self.dialogue.ingest_turn_responses(
            run_id,
            session_id=session_id,
            responses=responses,
            remember_turn_memory=True,
        )
        return self._refresh_dialogue_scene_progress(run_id, session)

    def _generate_dialogue_responses(
        self,
        run_id: str,
        payload: dict[str, Any],
        *,
        on_delta=None,
        on_attempt=None,
    ) -> dict[str, Any]:
        return generate_dialogue_responses_for_run(
            run_dir=self.runs_root / run_id,
            payload=payload,
            build_runtime_config_for_run=self._build_runtime_config_for_run,
            build_runtime_parts=build_runtime_parts,
            generate_dialogue_responses=generate_dialogue_responses,
            build_dialogue_llm_messages=lambda current_payload, retry_on_empty: self._build_dialogue_llm_messages(
                current_payload,
                retry_on_empty=retry_on_empty,
            ),
            parse_dialogue_responses=self._parse_dialogue_responses,
            on_delta=on_delta,
            on_attempt=on_attempt,
        )

    def _generate_dialogue_suggestion(
        self, run_id: str, payload: dict[str, Any]
    ) -> str:
        try:
            return generate_dialogue_suggestion_for_run(
                run_dir=self.runs_root / run_id,
                payload=payload,
                build_runtime_config_for_run=self._build_runtime_config_for_run,
                build_runtime_parts=build_runtime_parts,
                generate_dialogue_suggestion=generate_dialogue_suggestion,
                build_dialogue_suggestion_llm_messages=lambda current_payload, retry_on_empty: self._build_dialogue_suggestion_llm_messages(
                    current_payload,
                    retry_on_empty=retry_on_empty,
                ),
                parse_dialogue_suggestion=self._parse_dialogue_suggestion,
            )
        except Exception as exc:
            if not should_retry_suggestion_with_compact_payload(exc):
                raise
            compact_payload = compact_dialogue_suggestion_payload(payload)
            return generate_dialogue_suggestion_for_run(
                run_dir=self.runs_root / run_id,
                payload=compact_payload,
                build_runtime_config_for_run=self._build_runtime_config_for_run,
                build_runtime_parts=build_runtime_parts,
                generate_dialogue_suggestion=generate_dialogue_suggestion,
                build_dialogue_suggestion_llm_messages=lambda current_payload, retry_on_empty: self._build_dialogue_suggestion_llm_messages(
                    current_payload,
                    retry_on_empty=retry_on_empty,
                ),
                parse_dialogue_suggestion=self._parse_dialogue_suggestion,
            )

    def _generate_dialogue_associations(
        self, run_id: str, payload: dict[str, Any]
    ) -> list[dict[str, str]]:
        try:
            return generate_dialogue_associations_for_run(
                run_dir=self.runs_root / run_id,
                payload=payload,
                build_runtime_config_for_run=self._build_runtime_config_for_run,
                build_runtime_parts=build_runtime_parts,
                generate_dialogue_associations=generate_dialogue_associations,
                build_dialogue_association_llm_messages=lambda current_payload, retry_on_empty: self._build_dialogue_association_llm_messages(
                    current_payload,
                    retry_on_empty=retry_on_empty,
                ),
                parse_dialogue_associations=self._parse_dialogue_associations,
            )
        except Exception as exc:
            if not should_retry_suggestion_with_compact_payload(exc):
                raise
            compact_payload = compact_dialogue_suggestion_payload(payload)
            return generate_dialogue_associations_for_run(
                run_dir=self.runs_root / run_id,
                payload=compact_payload,
                build_runtime_config_for_run=self._build_runtime_config_for_run,
                build_runtime_parts=build_runtime_parts,
                generate_dialogue_associations=generate_dialogue_associations,
                build_dialogue_association_llm_messages=lambda current_payload, retry_on_empty: self._build_dialogue_association_llm_messages(
                    current_payload,
                    retry_on_empty=retry_on_empty,
                ),
                parse_dialogue_associations=self._parse_dialogue_associations,
            )

    def _generate_dialogue_consistency_review(
        self, run_id: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        config = self._build_runtime_config_for_run(run_dir=self.runs_root / run_id)
        parts = build_runtime_parts(config)
        if not hasattr(parts.llm, "chat_completion"):
            raise ValueError("Configured model does not support deep review.")
        messages = build_dialogue_consistency_review_messages(payload)
        result = parts.llm.chat_completion(
            messages,
            temperature=0.1,
            max_tokens=min(int(config.get("llm.max_tokens", 700) or 700), 700),
        )
        content = str((result or {}).get("content", "")).strip()
        if not content:
            raise ValueError("Model returned an empty consistency review.")
        return parse_dialogue_consistency_review(
            content,
            responses=list(payload.get("responses", []) or []),
            allowed_speakers=list(payload.get("participants", []) or []),
        )

    def _generate_dialogue_director_options(
        self, run_id: str, payload: dict[str, Any]
    ) -> list[dict[str, str]]:
        config = self._build_runtime_config_for_run(run_dir=self.runs_root / run_id)
        parts = build_runtime_parts(config)
        if not hasattr(parts.llm, "chat_completion"):
            raise ValueError("Configured model does not support director options.")
        expected_count = max(2, min(int(payload.get("option_count", 3) or 3), 4))
        last_error: Exception | None = None
        for retry_on_empty in (False, True):
            try:
                result = parts.llm.chat_completion(
                    build_dialogue_director_llm_messages(
                        payload, retry_on_empty=retry_on_empty
                    ),
                    temperature=0.65,
                    max_tokens=min(
                        int(config.get("llm.max_tokens", 900) or 900), 900
                    ),
                )
                content = str((result or {}).get("content", "")).strip()
                if not content:
                    raise ValueError("Model returned empty director options.")
                return parse_dialogue_director_options(
                    content, expected_count=expected_count
                )
            except LLMRequestError:
                raise
            except Exception as exc:
                last_error = exc
        raise ValueError(str(last_error or "Director options could not be generated."))

    @staticmethod
    def _build_dialogue_llm_messages(
        payload: dict[str, Any], *, retry_on_empty: bool = False
    ) -> list[dict[str, Any]]:
        return build_dialogue_llm_messages(payload, retry_on_empty=retry_on_empty)

    @staticmethod
    def _build_dialogue_suggestion_llm_messages(
        payload: dict[str, Any],
        *,
        retry_on_empty: bool = False,
    ) -> list[dict[str, str]]:
        return build_dialogue_suggestion_llm_messages(
            payload, retry_on_empty=retry_on_empty
        )

    @staticmethod
    def _build_dialogue_association_llm_messages(
        payload: dict[str, Any],
        *,
        retry_on_empty: bool = False,
    ) -> list[dict[str, str]]:
        return build_dialogue_association_llm_messages(
            payload, retry_on_empty=retry_on_empty
        )

    @staticmethod
    def _parse_dialogue_responses(
        content: str, allowed_speakers: list[str]
    ) -> list[dict[str, str]]:
        return parse_dialogue_responses(content, allowed_speakers)

    @staticmethod
    def _parse_dialogue_suggestion(content: str) -> str:
        return parse_dialogue_suggestion(content)

    @staticmethod
    def _parse_dialogue_associations(content: str) -> list[dict[str, str]]:
        return parse_dialogue_associations(content, require_suggestions=True)

    def _refresh_dialogue_scene_progress(
        self,
        run_id: str,
        session: dict[str, Any],
        *,
        use_llm: bool = True,
    ) -> dict[str, Any]:
        session_id = str((session or {}).get("session_id", "")).strip()
        if not session_id:
            return session
        generated: dict[str, Any] = {}
        if use_llm:
            try:
                generated = self._generate_dialogue_scene_progress(run_id, session)
            except Exception:
                generated = {}
        try:
            return self.dialogue.update_scene_progress_state(
                run_id,
                session_id,
                scene_progress=dict(generated or {}),
            )
        except Exception:
            return session

    def _generate_dialogue_scene_progress(
        self, run_id: str, session: dict[str, Any]
    ) -> dict[str, Any]:
        participants = [
            str(item).strip()
            for item in list((session or {}).get("participants", []) or [])
            if str(item).strip()
        ]
        if not participants:
            return {}
        config = self._build_runtime_config_for_run(run_dir=self.runs_root / run_id)
        if not self._should_use_scene_progress_llm(config, session):
            return {}
        parts = build_runtime_parts(config)
        if not hasattr(parts.llm, "chat_completion"):
            return {}

        payload = dict(session or {})
        payload["scene_progress"] = self.dialogue._session_scene_progress(payload)
        attempts = (
            build_dialogue_scene_progress_messages(payload),
            [
                *build_dialogue_scene_progress_messages(payload),
                {
                    "role": "user",
                    "content": "上一次输出不够稳定。请重新只返回完整 JSON，不要解释，不要 markdown。",
                },
            ],
        )
        last_error: Exception | None = None
        for messages in attempts:
            try:
                result = parts.llm.chat_completion(
                    messages,
                    temperature=0.1,
                    max_tokens=min(int(config.get("llm.max_tokens", 240) or 240), 240),
                )
                content = str((result or {}).get("content", "")).strip()
                if not content:
                    last_error = ValueError("empty scene progress")
                    continue
                return parse_dialogue_scene_progress(content, participants)
            except Exception as exc:
                last_error = exc
                continue
        if last_error is not None:
            return {}
        return {}

    @staticmethod
    def _should_use_scene_progress_llm(config: Any, session: dict[str, Any]) -> bool:
        transcript = list((session or {}).get("transcript", []) or [])
        history = list((session or {}).get("history", []) or [])
        if len(transcript) < 4 and len(history) < 4:
            return False
        base_url = str(config.get("llm.base_url", "") or "").strip().lower()
        api_key = str(config.get("llm.api_key", "") or "").strip().lower()
        if "example.com" in base_url:
            return False
        if api_key in {"sk-test", "test", "dummy", "placeholder"}:
            return False
        return True

    @_with_dialogue_session_lock
    def _evolve_relations_from_turn(
        self,
        run_id: str,
        pending_payload: dict[str, Any],
        responses: list[dict[str, str]],
        *,
        refine_with_llm: bool = True,
    ) -> None:
        if not responses:
            return
        try:
            session_id = str(pending_payload.get("session_id", "")).strip()
            if not session_id:
                return
            session_path = self.dialogue._session_file(run_id, session_id)
            session = self.dialogue._read_json(session_path)
            relation_delta = self.dialogue._session_relation_delta(session)
            locked_pairs = {
                str(key).strip()
                for key, value in dict(session.get("relation_locks", {}) or {}).items()
                if str(key).strip() and bool(value)
            }
            locked_relation_values = {
                key: dict(relation_delta.get(key, {}) or {})
                for key in locked_pairs
                if key in relation_delta
            }
            character_snapshots = self.dialogue._session_character_snapshots(session)
            event_signals = self.dialogue._session_event_signals(session)
            input_block = dict(pending_payload.get("input", {}) or {})
            speaker = str(input_block.get("speaker", "")).strip()
            participants = [
                str(item).strip()
                for item in input_block.get("participants", [])
                if str(item).strip()
            ]
            active = [
                str(item).strip()
                for item in input_block.get("active_participants", [])
                if str(item).strip()
            ]
            candidates = active or participants
            pending_message = str(input_block.get("message", "")).strip()
            pending_kind = (
                str(input_block.get("message_kind", "")).strip() or "dialogue"
            )
            detected_events: list[dict[str, Any]] = []
            if pending_kind != "plot":
                detected_events.extend(
                    self._extract_dialogue_event_signals(
                        participants=participants,
                        speaker=speaker,
                        message=pending_message,
                        source="pending_input",
                        message_kind=pending_kind,
                        target="",
                    )
                )

            for reply in responses:
                responder = str(reply.get("speaker", "")).strip()
                message = str(reply.get("message", "")).strip()
                if not responder or not message:
                    continue
                target = speaker
                if not target or target in {"User", "场景提示", "旁白"}:
                    pool = [name for name in candidates if name and name != responder]
                    target = pool[0] if pool else ""
                if target and target != responder:
                    key = self.dialogue._pair_key(responder, target)
                    current = dict(relation_delta.get(key, {}) or {})
                    delta = self._infer_relation_delta_from_message(message)
                    for field, amount in delta.items():
                        if (
                            field
                            not in {"trust", "affection", "hostility", "ambiguity"}
                            or not amount
                        ):
                            continue
                        current[field] = int(current.get(field, 0) or 0) + int(amount)
                    current["last_event"] = message[:220]
                    current["last_actor"] = responder
                    current["last_target"] = target
                    evidence_lines = list(current.get("evidence_lines", []) or [])
                    evidence_lines.append(f"{responder}->{target}: {message}"[:220])
                    current["evidence_lines"] = evidence_lines[-10:]
                    current["updated_at"] = session.get("updated_at", "")
                    current["momentum"] = max(
                        abs(int(current.get("trust", 0) or 0)),
                        abs(int(current.get("affection", 0) or 0)),
                        abs(int(current.get("hostility", 0) or 0)),
                        abs(int(current.get("ambiguity", 0) or 0)),
                    )
                    relation_delta[key] = current
                    if any(
                        int(current.get(field, 0) or 0)
                        for field in ("trust", "affection", "hostility", "ambiguity")
                    ):
                        detected_events.append(
                            {
                                "kind": "relationship_shift",
                                "scope": "relationship",
                                "actor": responder,
                                "target": target,
                                "cue": message[:160],
                                "source": "response",
                                "should_inline": False,
                                "ts": session.get("updated_at", "") or "",
                            }
                        )

                if responder in {"旁白", "场景提示"}:
                    self._update_offstage_snapshots_from_narration(
                        participants=participants,
                        message=message,
                        character_snapshots=character_snapshots,
                    )
                else:
                    snapshot = dict(character_snapshots.get(responder, {}) or {})
                    snapshot.update(
                        self._infer_character_snapshot(
                            responder=responder, target=target, message=message
                        )
                    )
                    character_snapshots[responder] = snapshot

                detected_events.extend(
                    self._extract_dialogue_event_signals(
                        participants=participants,
                        speaker=responder,
                        message=message,
                        source="response",
                        message_kind=(
                            "narration"
                            if responder in {"旁白", "场景提示"}
                            else "dialogue"
                        ),
                        target=target,
                    )
                )

            refined_state = (
                self._generate_dialogue_relation_state(
                    run_id,
                    session=session,
                    pending_payload=pending_payload,
                    responses=responses,
                    relation_delta=relation_delta,
                    character_snapshots=character_snapshots,
                )
                if refine_with_llm
                else {}
            )
            relation_delta = self._merge_relation_delta(
                relation_delta,
                dict(refined_state.get("relation_delta", {}) or {}),
            )
            for key in locked_pairs:
                if key in locked_relation_values:
                    relation_delta[key] = locked_relation_values[key]
                else:
                    relation_delta.pop(key, None)
            character_snapshots = self._merge_character_snapshots(
                character_snapshots,
                dict(refined_state.get("character_snapshots", {}) or {}),
            )
            current_turn_id = str(pending_payload.get("turn_id", "")).strip()
            if current_turn_id:
                detected_events = [
                    {**event, "turn_id": current_turn_id}
                    for event in detected_events
                ]
            event_signals = self.dialogue._merge_event_signals_state(
                session,
                detected_events,
            )

            self.dialogue._set_session_relation_delta(session, relation_delta)
            self.dialogue._set_session_character_snapshots(session, character_snapshots)
            self.dialogue._set_session_event_signals(session, event_signals)
            session["updated_at"] = session.get("updated_at") or ""
            self.dialogue._write_json(session_path, session)
            store = self.dialogue._resolve_memory_store(run_id)
            if store is not None:
                try:
                    store.save_relation_snapshot(session)
                except Exception:
                    pass
        except Exception:
            return

    def _generate_dialogue_relation_state(
        self,
        run_id: str,
        *,
        session: dict[str, Any],
        pending_payload: dict[str, Any],
        responses: list[dict[str, str]],
        relation_delta: dict[str, Any],
        character_snapshots: dict[str, Any],
    ) -> dict[str, Any]:
        participants = [
            str(item).strip()
            for item in list((session or {}).get("participants", []) or [])
            if str(item).strip()
        ]
        if not participants:
            return {}
        config = self._build_runtime_config_for_run(run_dir=self.runs_root / run_id)
        if not self._should_use_scene_progress_llm(config, session):
            return {}
        parts = build_runtime_parts(config)
        if not hasattr(parts.llm, "chat_completion"):
            return {}

        payload = dict(session or {})
        self.dialogue._set_session_relation_delta(payload, relation_delta)
        self.dialogue._set_session_character_snapshots(payload, character_snapshots)
        attempts = (
            build_dialogue_relation_state_messages(payload, pending_payload, responses),
            [
                *build_dialogue_relation_state_messages(
                    payload, pending_payload, responses
                ),
                {
                    "role": "user",
                    "content": "请重新只返回完整 JSON，并且仅做轻量修正，不要重写全部状态。",
                },
            ],
        )
        for messages in attempts:
            try:
                result = parts.llm.chat_completion(
                    messages,
                    temperature=0.1,
                    max_tokens=min(int(config.get("llm.max_tokens", 320) or 320), 320),
                )
                content = str((result or {}).get("content", "")).strip()
                if not content:
                    continue
                return parse_dialogue_relation_state(content, participants)
            except Exception:
                continue
        return {}

    @staticmethod
    def _merge_relation_delta(
        base: dict[str, Any], incoming: dict[str, Any]
    ) -> dict[str, Any]:
        merged = {
            str(key).strip(): dict(value or {})
            for key, value in dict(base or {}).items()
            if str(key).strip()
        }
        for key, value in dict(incoming or {}).items():
            normalized_key = str(key).strip()
            if not normalized_key:
                continue
            current = dict(merged.get(normalized_key, {}) or {})
            next_value = dict(value or {})
            for field in ("trust", "affection", "hostility", "ambiguity"):
                if field in next_value:
                    try:
                        current[field] = int(next_value.get(field, 0) or 0)
                    except Exception:
                        pass
            for field in (
                "last_event",
                "relation_change",
                "typical_interaction",
                "last_actor",
                "last_target",
                "updated_at",
            ):
                text = str(next_value.get(field, "")).strip()
                if text:
                    current[field] = text
            if "momentum" in next_value:
                try:
                    current["momentum"] = int(next_value.get("momentum", 0) or 0)
                except Exception:
                    pass
            evidence_lines = [
                str(item).strip()
                for item in list(next_value.get("evidence_lines", []) or [])
                if str(item).strip()
            ]
            if evidence_lines:
                current["evidence_lines"] = evidence_lines[:10]
            if current:
                merged[normalized_key] = current
        return merged

    @staticmethod
    def _merge_character_snapshots(
        base: dict[str, Any], incoming: dict[str, Any]
    ) -> dict[str, Any]:
        merged = {
            str(key).strip(): dict(value or {})
            for key, value in dict(base or {}).items()
            if str(key).strip()
        }
        for key, value in dict(incoming or {}).items():
            normalized_key = str(key).strip()
            if not normalized_key:
                continue
            current = dict(merged.get(normalized_key, {}) or {})
            for field, raw in dict(value or {}).items():
                text = str(raw).strip()
                if text:
                    current[field] = text
            if current:
                merged[normalized_key] = current
        return merged

    def _extract_dialogue_event_signals(
        self,
        *,
        participants: list[str],
        speaker: str,
        message: str,
        source: str,
        message_kind: str,
        target: str,
    ) -> list[dict[str, Any]]:
        text = str(message or "").strip()
        if not text:
            return []
        compact = "".join(text.split())
        is_scene_level = str(message_kind or "").strip() in {"narration", "plot"} or speaker in {
            "旁白",
            "场景提示",
        }
        events: list[dict[str, Any]] = []

        def push(
            kind: str,
            cue: str,
            *,
            scope: str,
            actor: str = "",
            target_name: str = "",
            should_inline: bool = False,
            time_hint: str = "",
            location_hint: str = "",
        ) -> None:
            normalized_cue = str(cue or "").strip()
            if not kind or not normalized_cue:
                return
            event = {
                "kind": kind,
                "scope": scope,
                "actor": actor,
                "target": target_name,
                "cue": normalized_cue[:160],
                "source": source,
                "should_inline": should_inline,
                "ts": "",
            }
            if time_hint:
                event["time_hint"] = time_hint
            if location_hint:
                event["location_hint"] = location_hint
            events.append(event)

        time_hint = _scene_signals.infer_time_hint(
            [
                {
                    "message": text,
                    "speaker": speaker,
                    "role": "scene" if is_scene_level else "character",
                }
            ]
        )
        if time_hint:
            push(
                "time_change",
                f"时间推进到{time_hint}",
                scope="scene",
                actor=speaker if is_scene_level else "",
                time_hint=time_hint,
            )

        if any(token in text for token in _scene_signals.ENVIRONMENT_TOKENS):
            push("environment_change", text, scope="scene")
        if any(token in text for token in _scene_signals.ATMOSPHERE_TOKENS):
            push("atmosphere_shift", text, scope="scene")

        if any(
            token in compact
            for token in _scene_signals.SCENE_ENTER_TOKENS
            + _scene_signals.SCENE_EXIT_TOKENS
        ):
            push(
                "scene_transition",
                text,
                scope="scene",
                location_hint=self._extract_location_hint(text),
            )

        for name in participants:
            if name not in text:
                continue
            if _scene_signals.contains_leave_signal(text, name):
                push("cast_exit", f"{name}离场", scope="scene", actor=name)
            elif _scene_signals.contains_return_signal(text, name):
                push("cast_enter", f"{name}返场", scope="scene", actor=name)

        if not is_scene_level and any(
            token in text for token in _scene_signals.ACTION_TOKENS
        ):
            push(
                "micro_action",
                text,
                scope="character",
                actor=speaker,
                target_name=target,
                should_inline=True,
            )

        if any(
            token in text
            for token in (
                "说开了",
                "到这里",
                "该换个地方",
                "该走下一幕",
                "下一幕",
                "先到这",
                "这幕先收住",
                "可以转到",
            )
        ):
            push("beat_complete", text, scope="scene")

        if (
            not is_scene_level
            and target
            and any(
                token in text
                for token in (
                    "只你我",
                    "单独",
                    "私下",
                    "我们两个",
                    "随我来",
                    "跟我走",
                    "留下",
                )
            )
        ):
            push(
                "focus_shift",
                text,
                scope="relationship",
                actor=speaker,
                target_name=target,
            )

        return events

    @staticmethod
    def _extract_location_hint(text: str) -> str:
        value = str(text or "").strip()
        matchers = (
            "花厅",
            "回廊",
            "偏厅",
            "房中",
            "屋里",
            "门外",
            "院中",
            "亭下",
            "船上",
            "私人影院",
            "影院",
            "家里",
        )
        for item in matchers:
            if item in value:
                return item
        return ""

    @staticmethod
    def _infer_relation_delta_from_message(message: str) -> dict[str, int]:
        text = str(message or "").strip()
        delta = {"trust": 0, "affection": 0, "hostility": 0, "ambiguity": 0}
        if any(
            token in text
            for token in (
                "谢谢",
                "抱歉",
                "理解",
                "关心",
                "在意",
                "一起",
                "陪你",
                "我陪",
                "别怕",
                "护着",
            )
        ):
            delta["trust"] += 1
            delta["affection"] += 1
            delta["hostility"] -= 1
        if any(
            token in text
            for token in ("滚", "讨厌", "厌恶", "闭嘴", "烦", "恨", "威胁", "不想见")
        ):
            delta["hostility"] += 2
            delta["trust"] -= 1
            delta["affection"] -= 2
        if any(
            token in text
            for token in ("也许", "或许", "未必", "再说", "以后再议", "说不好")
        ):
            delta["ambiguity"] += 1
        if any(
            token in text for token in ("算了", "就这样吧", "告辞", "先走一步", "改日")
        ):
            delta["ambiguity"] += 1
        return delta

    def _update_offstage_snapshots_from_narration(
        self,
        *,
        participants: list[str],
        message: str,
        character_snapshots: dict[str, Any],
    ) -> None:
        for name in participants:
            if name not in message:
                continue
            snapshot = dict(character_snapshots.get(name, {}) or {})
            if _scene_signals.contains_leave_signal(message, name):
                snapshot.update(
                    {
                        "mood": str(snapshot.get("mood", "")).strip() or "收住",
                        "interaction_state": "offstage",
                        "focus": "离场",
                        "last_event": message[:220],
                    }
                )
            elif _scene_signals.contains_return_signal(message, name):
                snapshot.update(
                    {
                        "interaction_state": "re-entered",
                        "focus": "返场",
                        "last_event": message[:220],
                    }
                )
            if snapshot:
                character_snapshots[name] = snapshot

    @staticmethod
    def _infer_character_snapshot(
        *, responder: str, target: str, message: str
    ) -> dict[str, str]:
        text = str(message or "").strip()
        mood = "平稳"
        if any(token in text for token in ("笑", "松了口气", "安心", "轻快", "温和")):
            mood = "放松"
        elif any(token in text for token in ("怒", "恼", "气", "烦", "冷", "厌")):
            mood = "发紧"
        elif any(token in text for token in ("愣", "怔", "沉默", "顿住", "迟疑")):
            mood = "迟疑"

        interaction_state = "engaged"
        if any(
            token in text for token in ("先走", "告退", "回房", "回家", "离开", "改日")
        ):
            interaction_state = "withdrawing"
        elif any(token in text for token in ("谢谢", "抱歉", "理解", "关心", "陪你")):
            interaction_state = "softening"
        elif any(token in text for token in ("滚", "闭嘴", "讨厌", "恨")):
            interaction_state = "hostile"

        focus = target or responder
        return {
            "mood": mood,
            "interaction_state": interaction_state,
            "focus": focus,
            "last_target": target,
            "last_message": text[:180],
            "last_event": text[:220],
        }

    def _dialogue_memory_store_for_run(self, run_id: str) -> Any:
        config = self._build_runtime_config_for_run(run_dir=self.runs_root / run_id)
        parts = self._build_runtime_parts(config)
        return parts.session_store
