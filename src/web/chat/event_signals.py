from __future__ import annotations

from typing import Any

from src.web.chat.text_utils import trim_summary_text


def merge_event_signals_state(
    current: dict[str, Any],
    incoming: list[dict[str, Any]],
    *,
    participants: list[str],
    updated_at: str,
) -> dict[str, Any]:
    recent = [
        dict(item or {})
        for item in list(dict(current or {}).get("recent", []) or [])
        if isinstance(item, dict)
    ]
    allowed_participants = {
        str(item).strip()
        for item in list(participants or [])
        if str(item).strip()
    }

    def normalize_event(item: dict[str, Any]) -> dict[str, Any]:
        event = dict(item or {})
        kind = str(event.get("kind", "")).strip()
        scope = str(event.get("scope", "")).strip() or ("character" if bool(event.get("should_inline", False)) else "scene")
        actor = str(event.get("actor", "")).strip()
        target = str(event.get("target", "")).strip()
        cue = trim_summary_text(str(event.get("cue", "")).strip(), 160)
        source = str(event.get("source", "")).strip() or "runtime"
        time_hint = trim_summary_text(str(event.get("time_hint", "")).strip(), 40)
        location_hint = trim_summary_text(str(event.get("location_hint", "")).strip(), 60)
        ts = str(event.get("ts", "")).strip() or updated_at
        turn_id = str(event.get("turn_id", "")).strip()
        if actor and allowed_participants and actor not in allowed_participants and actor not in {"场景提示", "旁白", "User"}:
            actor = ""
        if target and allowed_participants and target not in allowed_participants:
            target = ""
        normalized = {
            "kind": kind,
            "scope": scope,
            "actor": actor,
            "target": target,
            "cue": cue,
            "source": source,
            "should_inline": bool(event.get("should_inline", False)),
            "ts": ts,
        }
        if time_hint:
            normalized["time_hint"] = time_hint
        if location_hint:
            normalized["location_hint"] = location_hint
        if turn_id:
            normalized["turn_id"] = turn_id
        return normalized

    event_map: dict[str, dict[str, Any]] = {}
    for item in [*recent, *incoming]:
        normalized = normalize_event(item)
        if not normalized.get("kind") or not normalized.get("cue"):
            continue
        key = "|".join(
            [
                normalized["kind"],
                normalized.get("actor", ""),
                normalized.get("target", ""),
                normalized.get("cue", ""),
            ]
        )
        event_map[key] = normalized

    merged_recent = sorted(
        event_map.values(),
        key=lambda item: str(item.get("ts", "")).strip(),
    )[-40:]
    by_type: dict[str, list[dict[str, Any]]] = {}
    for item in merged_recent:
        kind = str(item.get("kind", "")).strip()
        if not kind:
            continue
        bucket = by_type.setdefault(kind, [])
        bucket.append(item)
        if len(bucket) > 8:
            by_type[kind] = bucket[-8:]
    return {
        "recent": merged_recent,
        "by_type": by_type,
        "updated_at": updated_at,
    }


def latest_event_signal(event_signals: dict[str, Any], *kinds: str) -> dict[str, Any]:
    wanted = {str(item).strip() for item in kinds if str(item).strip()}
    if not wanted:
        return {}
    recent = list(dict(event_signals or {}).get("recent", []) or [])
    for item in reversed(recent):
        event = dict(item or {})
        if str(event.get("kind", "")).strip() in wanted:
            return event
    return {}


def build_session_event_excerpt(event_signals: dict[str, Any]) -> list[dict[str, Any]]:
    recent = list(dict(event_signals or {}).get("recent", []) or [])
    normalized: list[dict[str, Any]] = []
    for item in recent[-8:]:
        event = dict(item or {})
        kind = str(event.get("kind", "")).strip()
        cue = trim_summary_text(str(event.get("cue", "")).strip(), 120)
        if not kind or not cue:
            continue
        normalized_event = {
            "kind": kind,
            "scope": str(event.get("scope", "")).strip(),
            "actor": str(event.get("actor", "")).strip(),
            "target": str(event.get("target", "")).strip(),
            "cue": cue,
            "should_inline": bool(event.get("should_inline", False)),
        }
        time_hint = str(event.get("time_hint", "")).strip()
        location_hint = str(event.get("location_hint", "")).strip()
        if time_hint:
            normalized_event["time_hint"] = time_hint
        if location_hint:
            normalized_event["location_hint"] = location_hint
        turn_id = str(event.get("turn_id", "")).strip()
        if turn_id:
            normalized_event["turn_id"] = turn_id
        normalized.append(
            {
                key: value
                for key, value in normalized_event.items()
                if value not in ("", [], False)
            }
        )
    return normalized


__all__ = [
    "build_session_event_excerpt",
    "latest_event_signal",
    "merge_event_signals_state",
]
