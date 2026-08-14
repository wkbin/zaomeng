from __future__ import annotations

from typing import Any, Callable

from src.core.exceptions import LLMRequestError


def _unpack_dialogue_generation(
    generated: Any,
) -> tuple[list[dict[str, str]], dict[str, Any] | None]:
    if isinstance(generated, dict) and isinstance(generated.get("responses"), list):
        observation = generated.get("generation_cache")
        return list(generated.get("responses", []) or []), (
            dict(observation or {}) if isinstance(observation, dict) else None
        )
    return list(generated or []), None


def _prepared_turn_id(prepared: dict[str, Any]) -> str:
    return str(
        dict(prepared.get("pending_turn_summary", {}) or {}).get("turn_id", "")
    ).strip()


def _abort_pending_turn_safely(
    dialogue: Any,
    run_id: str,
    session_id: str,
    *,
    expected_turn_id: str,
    reason: str,
) -> None:
    try:
        dialogue.abort_pending_turn(
            run_id,
            session_id,
            expected_turn_id=expected_turn_id,
            reason=reason,
        )
    except Exception:
        # Preserve the generation error that caused the abort attempt.
        return


def _ingest_generated_dialogue(
    dialogue: Any,
    run_id: str,
    session_id: str,
    responses: list[dict[str, str]],
    generation_cache: dict[str, Any] | None,
) -> dict[str, Any]:
    kwargs: dict[str, Any] = {
        "session_id": session_id,
        "responses": responses,
        "remember_turn_memory": True,
    }
    if generation_cache is not None:
        kwargs["generation_cache"] = generation_cache
    return dialogue.ingest_turn_responses(run_id, **kwargs)


def _complete_prepared_dialogue_turn(
    *,
    run_id: str,
    session_id: str,
    prepared: dict[str, Any],
    dialogue: Any,
    load_pending_turn_payload: Callable[[str, str], dict[str, Any]],
    generate_dialogue_responses: Callable[[str, dict[str, Any]], Any],
    friendly_dialogue_llm_error: Callable[[Exception], str],
    evolve_relations_from_turn: Callable[
        [str, dict[str, Any], list[dict[str, str]]], None
    ],
    refresh_scene_progress: (
        Callable[[str, dict[str, Any]], dict[str, Any]] | None
    ),
    failure_reason: str,
) -> dict[str, Any]:
    expected_turn_id = _prepared_turn_id(prepared)
    try:
        pending_payload = load_pending_turn_payload(run_id, session_id)
        try:
            generated = generate_dialogue_responses(run_id, pending_payload)
        except LLMRequestError as exc:
            raise ValueError(friendly_dialogue_llm_error(exc)) from exc
        responses, generation_cache = _unpack_dialogue_generation(generated)
        evolve_relations_from_turn(run_id, pending_payload, responses)
        ingested = _ingest_generated_dialogue(
            dialogue, run_id, session_id, responses, generation_cache
        )
        if callable(refresh_scene_progress):
            ingested = refresh_scene_progress(run_id, ingested)
        return ingested
    except Exception:
        _abort_pending_turn_safely(
            dialogue,
            run_id,
            session_id,
            expected_turn_id=expected_turn_id,
            reason=failure_reason,
        )
        raise


def create_dialogue_session_payload(
    *,
    run_id: str,
    manifest: dict[str, Any],
    dialogue: Any,
    mode: str,
    participants: list[str],
    controlled_character: str,
    scene_profile: dict[str, str] | None,
    self_profile: dict[str, str] | None,
    build_dialogue_opening_message: Callable[[dict[str, Any]], str],
    load_pending_turn_payload: Callable[[str, str], dict[str, Any]],
    generate_dialogue_responses: Callable[[str, dict[str, Any]], Any],
    friendly_dialogue_llm_error: Callable[[Exception], str],
    evolve_relations_from_turn: Callable[
        [str, dict[str, Any], list[dict[str, str]]], None
    ],
    refresh_scene_progress: (
        Callable[[str, dict[str, Any]], dict[str, Any]] | None
    ) = None,
) -> dict[str, Any]:
    if mode in {"act", "observe"} and len(participants) < 2:
        raise ValueError(
            "At least two participants are required for this dialogue mode."
        )
    if mode == "insert" and len(participants) < 1:
        raise ValueError(
            "At least one participant is required for self-insert dialogue."
        )
    session = dialogue.create_session(
        manifest,
        mode=mode,
        participants=participants,
        controlled_character=controlled_character,
        scene_profile=scene_profile,
        self_profile=self_profile,
    )
    session_id = str(session.get("session_id", "")).strip()
    try:
        opening_message = build_dialogue_opening_message(session)
        prepared = dialogue.prepare_turn(
            manifest,
            session_id=session_id,
            message=opening_message,
            speaker_override="场景提示",
            transcript_message="",
        )
        return _complete_prepared_dialogue_turn(
            run_id=run_id,
            session_id=session_id,
            prepared=prepared,
            dialogue=dialogue,
            load_pending_turn_payload=load_pending_turn_payload,
            generate_dialogue_responses=generate_dialogue_responses,
            friendly_dialogue_llm_error=friendly_dialogue_llm_error,
            evolve_relations_from_turn=evolve_relations_from_turn,
            refresh_scene_progress=refresh_scene_progress,
            failure_reason="opening_failed",
        )
    except Exception:
        try:
            dialogue.delete_session(run_id, session_id)
        except Exception:
            # Keep the opening error as the public failure reason.
            pass
        raise


def continue_dialogue_scene_opening_payload(
    *,
    run_id: str,
    session: dict[str, Any],
    manifest: dict[str, Any],
    dialogue: Any,
    build_dialogue_opening_message: Callable[[dict[str, Any]], str],
    load_pending_turn_payload: Callable[[str, str], dict[str, Any]],
    generate_dialogue_responses: Callable[[str, dict[str, Any]], Any],
    friendly_dialogue_llm_error: Callable[[Exception], str],
    evolve_relations_from_turn: Callable[
        [str, dict[str, Any], list[dict[str, str]]], None
    ],
    refresh_scene_progress: (
        Callable[[str, dict[str, Any]], dict[str, Any]] | None
    ) = None,
) -> dict[str, Any]:
    session_id = str(session.get("session_id", "")).strip()
    if not session_id:
        raise ValueError("Session not found.")
    opening_message = build_dialogue_opening_message(session)
    prepared = dialogue.prepare_turn(
        manifest,
        session_id=session_id,
        message=opening_message,
        speaker_override="场景提示",
        transcript_message="",
    )
    return _complete_prepared_dialogue_turn(
        run_id=run_id,
        session_id=session_id,
        prepared=prepared,
        dialogue=dialogue,
        load_pending_turn_payload=load_pending_turn_payload,
        generate_dialogue_responses=generate_dialogue_responses,
        friendly_dialogue_llm_error=friendly_dialogue_llm_error,
        evolve_relations_from_turn=evolve_relations_from_turn,
        refresh_scene_progress=refresh_scene_progress,
        failure_reason="opening_failed",
    )


def reply_dialogue_turn_payload(
    *,
    run_id: str,
    session_id: str,
    message: str,
    message_kind: str,
    suppress_transcript_message: bool = False,
    include_inner_thoughts: bool = False,
    manifest: dict[str, Any],
    dialogue: Any,
    load_pending_turn_payload: Callable[[str, str], dict[str, Any]],
    generate_dialogue_responses: Callable[[str, dict[str, Any]], Any],
    friendly_dialogue_llm_error: Callable[[Exception], str],
    evolve_relations_from_turn: Callable[
        [str, dict[str, Any], list[dict[str, str]]], None
    ],
    refresh_scene_progress: (
        Callable[[str, dict[str, Any]], dict[str, Any]] | None
    ) = None,
) -> dict[str, Any]:
    normalized_kind = str(message_kind or "").strip().lower()
    is_plot_push = normalized_kind in {"plot", "plot_push", "advance"}
    speaker_override = (
        "场景提示"
        if normalized_kind in {"narration", "plot", "plot_push", "advance"}
        else ""
    )
    prepared = dialogue.prepare_turn(
        manifest,
        session_id=session_id,
        message=message,
        message_kind=message_kind,
        speaker_override=speaker_override,
        transcript_message="" if suppress_transcript_message or is_plot_push else None,
        include_inner_thoughts=include_inner_thoughts,
        _serialize_result=False,
    )
    return _complete_prepared_dialogue_turn(
        run_id=run_id,
        session_id=session_id,
        prepared=prepared,
        dialogue=dialogue,
        load_pending_turn_payload=load_pending_turn_payload,
        generate_dialogue_responses=generate_dialogue_responses,
        friendly_dialogue_llm_error=friendly_dialogue_llm_error,
        evolve_relations_from_turn=evolve_relations_from_turn,
        refresh_scene_progress=refresh_scene_progress,
        failure_reason="reply_failed",
    )


def suggest_dialogue_turn_payload(
    *,
    run_id: str,
    session_id: str,
    seed_text: str,
    manifest: dict[str, Any],
    dialogue: Any,
    generate_dialogue_suggestion: Callable[[str, dict[str, Any]], str],
    friendly_dialogue_llm_error: Callable[[Exception], str],
    direction: str = "",
) -> dict[str, str]:
    payload = dialogue.build_suggestion_payload(
        manifest,
        session_id=session_id,
        seed_text=seed_text,
        direction=direction,
    )
    try:
        suggestion = generate_dialogue_suggestion(run_id, payload)
    except LLMRequestError as exc:
        raise ValueError(friendly_dialogue_llm_error(exc)) from exc
    return {"suggestion": suggestion}


def associate_dialogue_turn_payload(
    *,
    run_id: str,
    session_id: str,
    option_count: int,
    manifest: dict[str, Any],
    dialogue: Any,
    generate_dialogue_associations: Callable[
        [str, dict[str, Any]], list[dict[str, str]]
    ],
    friendly_dialogue_llm_error: Callable[[Exception], str],
) -> dict[str, Any]:
    payload = dialogue.build_association_payload(
        manifest,
        session_id=session_id,
        option_count=option_count,
    )
    try:
        options = generate_dialogue_associations(run_id, payload)
    except LLMRequestError as exc:
        raise ValueError(friendly_dialogue_llm_error(exc)) from exc
    return {"show": bool(options), "options": options}
