from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

from src.web.chat.cache_stats import summarize_completion_results
from src.web.path_safety import resolve_storage_child, validate_storage_id


DIALOGUE_ASSOCIATION_MAX_TOKENS = 768
DIALOGUE_SUGGESTION_MAX_TOKENS = 512
TEMPORARY_NPC_SPEAKER = "__temporary_npc__"


def load_pending_turn_payload(
    *,
    runs_root: Path,
    run_id: str,
    session_id: str,
    load_json_file: Callable[[Path], dict[str, Any] | None],
) -> dict[str, Any]:
    run_dir = resolve_storage_child(runs_root, run_id, field_name="run_id")
    dialogue_dir = resolve_storage_child(run_dir, "dialogue", field_name="dialogue directory")
    session_dir = resolve_storage_child(dialogue_dir, session_id, field_name="session_id")
    session_path = session_dir / "session.json"
    session_payload = load_json_file(session_path) or {}
    pending = dict(session_payload.get("pending_turn", {}) or {})
    turn_id = str(pending.get("turn_id", "")).strip()
    pending_path_text = str(pending.get("payload_path", "")).strip()
    if not turn_id and not pending_path_text:
        raise ValueError("Pending turn payload was not created.")
    safe_turn_id = validate_storage_id(turn_id, field_name="turn_id")
    canonical_path = session_dir / "turns" / f"{safe_turn_id}.payload.json"
    pending_payload = load_json_file(canonical_path)
    if not pending_payload and pending_path_text:
        pending_path = Path(pending_path_text)
        try:
            stored_is_local = pending_path.resolve().is_relative_to(
                session_dir.resolve()
            )
        except (OSError, RuntimeError, ValueError):
            stored_is_local = False
        if stored_is_local and pending_path.is_file():
            pending_payload = load_json_file(pending_path)
    if not pending_payload:
        raise ValueError("Pending turn payload is empty.")
    return pending_payload


def generate_dialogue_responses_for_run(
    *,
    run_dir: Path,
    payload: dict[str, Any],
    build_runtime_config_for_run: Callable[..., Any],
    build_runtime_parts: Callable[[Any], Any],
    generate_dialogue_responses: Callable[..., list[dict[str, str]]],
    build_dialogue_llm_messages: Callable[[dict[str, Any], bool], list[dict[str, Any]]],
    parse_dialogue_responses: Callable[[str, list[str]], list[dict[str, str]]],
    on_delta: Callable[[str], None] | None = None,
    on_attempt: Callable[[int], None] | None = None,
) -> dict[str, Any]:
    config = build_runtime_config_for_run(run_dir=run_dir)
    parts = build_runtime_parts(config)
    if not hasattr(parts.llm, "chat_completion"):
        raise ValueError("Configured model does not support chat generation.")

    allowed_speakers = [
        str(item.get("name", "")).strip() for item in payload.get("responder_hints", [])
    ]
    allowed_speakers.extend(["旁白", "场景提示"])
    # The parser accepts this sentinel as permission to retain a named,
    # temporary in-scene NPC. The service registers it before committing.
    allowed_speakers.append(TEMPORARY_NPC_SPEAKER)
    input_payload = dict(payload.get("input", {}) or {})
    forbidden_speakers = [
        str(input_payload.get("controlled_character", "")).strip(),
        str(input_payload.get("speaker", "")).strip(),
    ]
    allowed_speakers.extend(
        f"__forbidden_speaker__:{name}"
        for name in forbidden_speakers
        if name
    )
    completions: list[dict[str, Any]] = []
    attempt_index = 0

    def complete(
        messages: list[dict[str, Any]],
        temperature: float,
        max_tokens: int,
    ) -> dict[str, Any]:
        nonlocal attempt_index
        current_attempt = attempt_index
        attempt_index += 1
        if callable(on_attempt):
            on_attempt(current_attempt)

        stream_completion = getattr(parts.llm, "chat_completion_stream", None)
        if callable(on_delta) and callable(stream_completion):
            return stream_completion(
                messages,
                temperature=temperature,
                max_tokens=max_tokens,
                on_delta=on_delta,
            )

        result = parts.llm.chat_completion(
            messages,
            temperature=temperature,
            max_tokens=max_tokens,
        )
        if callable(on_delta):
            content = str((result or {}).get("content", ""))
            if content:
                on_delta(content)
        return result

    responses = generate_dialogue_responses(
        payload=payload,
        allowed_speakers=allowed_speakers,
        temperature=float(config.get("llm.temperature", 0.35) or 0.35),
        max_tokens=int(
            config.get("llm.max_tokens", 0) or 0
        ),
        chat_completion=complete,
        build_messages=lambda current_payload, retry_on_empty: build_dialogue_llm_messages(
            current_payload,
            retry_on_empty,
        ),
        parse_responses=parse_dialogue_responses,
        completion_observer=completions.append,
    )
    return {
        "responses": responses,
        "generation_cache": summarize_completion_results(completions),
    }


def generate_dialogue_suggestion_for_run(
    *,
    run_dir: Path,
    payload: dict[str, Any],
    build_runtime_config_for_run: Callable[..., Any],
    build_runtime_parts: Callable[[Any], Any],
    generate_dialogue_suggestion: Callable[..., str],
    build_dialogue_suggestion_llm_messages: Callable[
        [dict[str, Any], bool], list[dict[str, str]]
    ],
    parse_dialogue_suggestion: Callable[[str], str],
) -> str:
    config = build_runtime_config_for_run(run_dir=run_dir)
    parts = build_runtime_parts(config)
    if not hasattr(parts.llm, "chat_completion"):
        raise ValueError("Configured model does not support chat generation.")

    return generate_dialogue_suggestion(
        payload=payload,
        temperature=float(config.get("llm.temperature", 0.45) or 0.45),
        max_tokens=DIALOGUE_SUGGESTION_MAX_TOKENS,
        chat_completion=lambda messages, temperature, max_tokens: parts.llm.chat_completion(
            messages,
            temperature=temperature,
            max_tokens=max_tokens,
        ),
        build_messages=lambda current_payload, retry_on_empty: build_dialogue_suggestion_llm_messages(
            current_payload,
            retry_on_empty,
        ),
        parse_suggestion=parse_dialogue_suggestion,
    )


def generate_dialogue_associations_for_run(
    *,
    run_dir: Path,
    payload: dict[str, Any],
    build_runtime_config_for_run: Callable[..., Any],
    build_runtime_parts: Callable[[Any], Any],
    generate_dialogue_associations: Callable[..., list[dict[str, str]]],
    build_dialogue_association_llm_messages: Callable[
        [dict[str, Any], bool], list[dict[str, str]]
    ],
    parse_dialogue_associations: Callable[[str], list[dict[str, str]]],
) -> list[dict[str, str]]:
    config = build_runtime_config_for_run(run_dir=run_dir)
    parts = build_runtime_parts(config)
    if not hasattr(parts.llm, "chat_completion"):
        raise ValueError("Configured model does not support chat generation.")

    configured_max_tokens = int(config.get("llm.max_tokens", 0) or 0)
    return generate_dialogue_associations(
        payload=payload,
        temperature=float(config.get("llm.temperature", 0.5) or 0.5),
        max_tokens=max(
            512,
            min(
                configured_max_tokens or DIALOGUE_ASSOCIATION_MAX_TOKENS,
                DIALOGUE_ASSOCIATION_MAX_TOKENS,
            ),
        ),
        chat_completion=lambda messages, temperature, max_tokens: parts.llm.chat_completion(
            messages,
            temperature=temperature,
            max_tokens=max_tokens,
        ),
        build_messages=lambda current_payload, retry_on_empty: build_dialogue_association_llm_messages(
            current_payload,
            retry_on_empty,
        ),
        parse_associations=parse_dialogue_associations,
    )
