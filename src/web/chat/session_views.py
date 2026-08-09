from __future__ import annotations

from typing import Any, Callable

from src.web.time_utils import utc_now


def serialize_transcript(session: dict[str, Any]) -> list[dict[str, Any]]:
    controlled = str(session.get("controlled_character", "")).strip()
    self_insert_name = str(session.get("self_insert", {}).get("display_name", "")).strip()
    mode = str(session.get("mode", "observe")).strip() or "observe"
    items: list[dict[str, Any]] = []
    for entry in session.get("history", []):
        speaker = str(entry.get("speaker", "")).strip()
        role = "character"
        if speaker in {"旁白", "场景提示"}:
            role = "director" if mode == "observe" else "scene"
        elif mode == "act" and speaker == controlled:
            role = "user"
        elif mode == "insert" and speaker == self_insert_name:
            role = "user"
        elif mode == "observe" and speaker == "User":
            role = "director"
        items.append(
            {
                "speaker": speaker,
                "message": str(entry.get("message", "")).strip(),
                "inner_thought": str(entry.get("inner_thought", "")).strip(),
                "role": role,
                "turn_id": str(entry.get("turn_id", "")).strip(),
                "timestamp": str(entry.get("ts", "")).strip(),
            }
        )
    return items


def build_session_card(
    session: dict[str, Any],
    *,
    mode_display: Callable[[str], str],
) -> dict[str, Any]:
    mode = str(session.get("mode", "observe")).strip() or "observe"
    return {
        "mode": mode,
        "mode_display": mode_display(mode),
        "participants": list(session.get("participants", [])),
        "controlled_character": str(session.get("controlled_character", "")).strip(),
        "scene_card_id": str(session.get("scene_card_id", "")).strip(),
        "scene_card": dict(session.get("scene_card", {})),
        "self_card_id": str(session.get("self_card_id", "")).strip(),
        "self_insert": dict(session.get("self_insert", {})),
    }


def serialize_scene_history(session: dict[str, Any]) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    current_scene_id = str(session.get("scene_card_id", "")).strip()
    for entry in list(session.get("scene_history", []) or []):
        scene_card_id = str(entry.get("scene_card_id", "")).strip()
        items.append(
            {
                "scene_card_id": scene_card_id,
                "title": str(entry.get("title", "")).strip(),
                "location": str(entry.get("location", "")).strip(),
                "atmosphere": str(entry.get("atmosphere", "")).strip(),
                "transition_message": str(entry.get("transition_message", "")).strip(),
                "scene_card": dict(entry.get("scene_card", {}) or {}),
                "memory_summary": dict(entry.get("memory_summary", {}) or {}),
                "ts": str(entry.get("ts", "")).strip(),
                "is_current": "true" if current_scene_id and scene_card_id == current_scene_id else "",
            }
        )
    return items


def build_scene_history_entry(
    scene_profile: dict[str, Any],
    *,
    transition_message: str = "",
    memory_summary: dict[str, str] | None = None,
) -> dict[str, Any]:
    scene = dict(scene_profile or {})
    return {
        "scene_card_id": str(scene.get("scene_card_id", "")).strip(),
        "title": str(scene.get("title", "")).strip(),
        "location": str(scene.get("location", "")).strip(),
        "atmosphere": str(scene.get("atmosphere", "")).strip(),
        "transition_message": str(transition_message or "").strip(),
        "scene_card": scene,
        "memory_summary": dict(memory_summary or {}),
        "ts": utc_now(),
    }


def build_pending_turn_summary(
    session: dict[str, Any],
    *,
    normalize_message_kind: Callable[[str], str],
) -> dict[str, Any]:
    pending = dict(session.get("pending_turn", {}) or {})
    if not pending:
        return {}
    return {
        "turn_id": str(pending.get("turn_id", "")).strip(),
        "speaker": str(pending.get("speaker", "")).strip(),
        "message": str(pending.get("user_message", "")).strip(),
        "message_kind": normalize_message_kind(str(pending.get("message_kind", "")).strip()),
        "mode": str(pending.get("mode", "")).strip(),
        "participants": list(pending.get("participants", [])),
        "active_participants": list(pending.get("active_participants", [])),
        "response_limit_hint": int(pending.get("response_limit_hint", 0) or 0),
    }


__all__ = [
    "build_pending_turn_summary",
    "build_scene_history_entry",
    "build_session_card",
    "serialize_scene_history",
    "serialize_transcript",
]
