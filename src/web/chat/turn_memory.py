from __future__ import annotations

from typing import Any

from src.web.chat.persona_context import persona_snapshot_payload
from src.web.chat.text_utils import trim_summary_text


def build_turn_memory_context(
    *,
    state_summary: dict[str, Any],
    scene_progress: dict[str, Any] | None,
    character_snapshots: dict[str, Any],
    relation_delta: dict[str, Any],
    event_signals: list[dict[str, Any]],
    session_summary: dict[str, Any],
    memory_hits: list[dict[str, Any]],
) -> dict[str, Any]:
    archived_summary = {
        "summary": trim_summary_text(str(state_summary.get("summary", "")).strip(), 360),
        "key_points": [
            trim_summary_text(str(item).strip(), 120)
            for item in list(state_summary.get("key_points", []) or [])[:5]
            if str(item).strip()
        ],
        "compressed_turns": int(state_summary.get("compressed_turns", 0) or 0),
        "recent_turns_kept": int(state_summary.get("recent_turns_kept", 0) or 0),
    }
    archived_summary = {
        key: value
        for key, value in archived_summary.items()
        if value not in ("", [], 0)
    }

    normalized_progress = dict(scene_progress or {})
    progress_snapshot = {
        "time_hint": trim_summary_text(str(normalized_progress.get("time_hint", "")).strip(), 32),
        "location": trim_summary_text(str(normalized_progress.get("location", "")).strip(), 48),
        "progression_note": trim_summary_text(
            str(normalized_progress.get("progression_note", "")).strip(),
            120,
        ),
        "present_participants": [
            str(item).strip()
            for item in list(normalized_progress.get("present_participants", []) or [])[:6]
            if str(item).strip()
        ],
        "offstage_participants": [
            str(item).strip()
            for item in list(normalized_progress.get("offstage_participants", []) or [])[:6]
            if str(item).strip()
        ],
        "should_offer_scene_shift": bool(normalized_progress.get("should_offer_scene_shift", False)),
        "scene_shift_reason": trim_summary_text(
            str(normalized_progress.get("scene_shift_reason", "")).strip(),
            120,
        ),
        "world_tension_summary": trim_summary_text(
            str(normalized_progress.get("world_tension_summary", "")).strip(),
            120,
        ),
    }
    progress_snapshot = {
        key: value
        for key, value in progress_snapshot.items()
        if value not in ("", [], False)
    }

    compact_snapshots: dict[str, Any] = {}
    for name, snapshot in character_snapshots.items():
        normalized_name = str(name).strip()
        if not normalized_name:
            continue
        compact = persona_snapshot_payload(dict(snapshot or {}), detailed=True)
        if compact:
            compact_snapshots[normalized_name] = compact

    compact_relation_delta = {
        str(pair_key).strip(): {
            key: value
            for key, value in dict(delta or {}).items()
            if value not in ("", [], 0, None)
        }
        for pair_key, delta in relation_delta.items()
        if str(pair_key).strip()
    }
    compact_relation_delta = {
        key: value
        for key, value in compact_relation_delta.items()
        if value
    }
    return {
        "session_summary": session_summary,
        "archived_summary": archived_summary,
        "retrieved_memories": memory_hits,
        "scene_progress": progress_snapshot,
        "character_snapshots": compact_snapshots,
        "relation_delta": compact_relation_delta,
        "event_signals": event_signals,
    }


def search_turn_memory_hits(
    store: Any,
    *,
    session_id: str,
    speaker: str,
    message: str,
    participants: list[str],
    active_participants: list[str],
    scene_card: dict[str, Any],
    session_summary: dict[str, Any],
    scene_progress: dict[str, Any],
) -> list[dict[str, Any]]:
    if not session_id or store is None:
        return []
    query_parts: list[str] = []
    for item in [speaker, *active_participants[:3], *participants[:2]]:
        normalized = str(item).strip()
        if normalized and normalized not in query_parts:
            query_parts.append(normalized)
    for item in (
        str(scene_card.get("title", "")).strip(),
        str(scene_card.get("location", "")).strip(),
        str(scene_card.get("scene_drive", "")).strip(),
        str(scene_card.get("public_goal", "")).strip(),
        str(scene_card.get("hidden_tension", "")).strip(),
        str(session_summary.get("current_goal", "")).strip(),
        str(session_summary.get("unresolved_threads", "")).strip(),
        str(session_summary.get("current_location", "")).strip(),
        str(session_summary.get("current_companions", "")).strip(),
        str(session_summary.get("pending_commitments", "")).strip(),
        str(scene_progress.get("scene_shift_reason", "")).strip(),
        str(scene_progress.get("world_tension_summary", "")).strip(),
    ):
        if item and item not in query_parts:
            query_parts.append(item)
    trimmed_message = trim_summary_text(message, 80)
    if trimmed_message:
        query_parts.append(trimmed_message)
    if not query_parts:
        return []
    try:
        hits = store.search_long_term_memory(
            session_id,
            " ".join(query_parts),
            top_k=3,
        )
    except Exception:
        return []
    normalized_hits: list[dict[str, Any]] = []
    for item in hits:
        text = trim_summary_text(str((item or {}).get("text", "")).strip(), 140)
        if not text:
            continue
        normalized_hit = {
            "text": text,
            "score": round(float(item.get("score", 0.0) or 0.0), 4),
            "speaker": str(item.get("speaker", "")).strip(),
            "target": str(item.get("target", "")).strip(),
            "kind": str(item.get("kind", "")).strip(),
        }
        normalized_hits.append(
            {
                key: value
                for key, value in normalized_hit.items()
                if value not in ("", 0.0)
            }
        )
    return normalized_hits


__all__ = ["build_turn_memory_context", "search_turn_memory_hits"]
