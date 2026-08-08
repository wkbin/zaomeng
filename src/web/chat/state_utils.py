from __future__ import annotations

from typing import Any


def empty_event_signals_state() -> dict[str, Any]:
    return {
        "recent": [],
        "by_type": {},
        "updated_at": "",
    }


def empty_session_state(version: int) -> dict[str, Any]:
    return {
        "version": version,
        "scene": {
            "location": "",
            "time_hint": "",
            "atmosphere_summary": "",
            "progression_note": "",
            "updated_at": "",
        },
        "presence": {
            "present_participants": [],
            "offstage_participants": [],
            "updated_at": "",
        },
        "progression": {
            "should_offer_scene_shift": False,
            "scene_shift_reason": "",
            "turns_in_current_scene": 0,
            "beat_maturity": 0,
            "world_tension_summary": "",
            "updated_at": "",
        },
        "relations": {
            "matrix": {},
            "delta": {},
        },
        "characters": {
            "snapshots": {},
        },
        "signals": empty_event_signals_state(),
        "memory": {
            "summary": {},
        },
    }


def ensure_session_state(session: dict[str, Any], *, version: int) -> dict[str, Any]:
    state = dict(session.get("state", {}) or {})
    canonical = empty_session_state(version)
    canonical["version"] = int(state.get("version", version) or version)

    scene = dict(state.get("scene", {}) or {})
    scene_legacy = dict(state.get("scene_progress", {}) or {})
    canonical["scene"] = {
        **dict(canonical.get("scene", {}) or {}),
        **{
            key: value
            for key, value in scene.items()
            if key in {"location", "time_hint", "atmosphere_summary", "progression_note", "updated_at"}
        },
        **{
            key: value
            for key, value in scene_legacy.items()
            if key in {"location", "time_hint", "atmosphere_summary", "progression_note", "updated_at"}
        },
    }

    presence = dict(state.get("presence", {}) or {})
    canonical["presence"] = {
        **dict(canonical.get("presence", {}) or {}),
        **{
            "present_participants": list(
                presence.get("present_participants", []) or scene_legacy.get("present_participants", []) or []
            ),
            "offstage_participants": list(
                presence.get("offstage_participants", []) or scene_legacy.get("offstage_participants", []) or []
            ),
            "updated_at": str(presence.get("updated_at", "")).strip()
            or str(scene_legacy.get("updated_at", "")).strip(),
        },
    }

    progression = dict(state.get("progression", {}) or {})
    canonical["progression"] = {
        **dict(canonical.get("progression", {}) or {}),
        **{
            "should_offer_scene_shift": bool(
                progression.get("should_offer_scene_shift", scene_legacy.get("should_offer_scene_shift", False))
            ),
            "scene_shift_reason": str(
                progression.get("scene_shift_reason", scene_legacy.get("scene_shift_reason", ""))
            ).strip(),
            "turns_in_current_scene": int(
                progression.get("turns_in_current_scene", scene_legacy.get("turns_in_current_scene", 0)) or 0
            ),
            "beat_maturity": int(progression.get("beat_maturity", scene_legacy.get("beat_maturity", 0)) or 0),
            "world_tension_summary": str(
                progression.get("world_tension_summary", scene_legacy.get("world_tension_summary", ""))
            ).strip(),
            "updated_at": str(progression.get("updated_at", "")).strip()
            or str(scene_legacy.get("updated_at", "")).strip(),
        },
    }

    relations = dict(state.get("relations", {}) or {})
    canonical["relations"] = {
        "matrix": dict(relations.get("matrix", {}) or state.get("relation_matrix", {}) or {}),
        "delta": dict(relations.get("delta", {}) or state.get("relation_delta", {}) or {}),
    }
    characters = dict(state.get("characters", {}) or {})
    canonical["characters"] = {
        "snapshots": dict(characters.get("snapshots", {}) or state.get("character_snapshots", {}) or {}),
    }
    canonical["signals"] = dict(state.get("signals", {}) or state.get("event_signals", {}) or empty_event_signals_state())
    memory = dict(state.get("memory", {}) or {})
    canonical["memory"] = {
        "summary": dict(memory.get("summary", {}) or state.get("memory_summary", {}) or {}),
    }
    session["state"] = canonical
    return canonical


def session_scene_progress(state: dict[str, Any]) -> dict[str, Any]:
    scene = dict(state.get("scene", {}) or {})
    presence = dict(state.get("presence", {}) or {})
    progression = dict(state.get("progression", {}) or {})
    return {
        "present_participants": list(presence.get("present_participants", []) or []),
        "offstage_participants": list(presence.get("offstage_participants", []) or []),
        "time_hint": str(scene.get("time_hint", "")).strip(),
        "location": str(scene.get("location", "")).strip(),
        "atmosphere_summary": str(scene.get("atmosphere_summary", "")).strip(),
        "progression_note": str(scene.get("progression_note", "")).strip(),
        "should_offer_scene_shift": bool(progression.get("should_offer_scene_shift", False)),
        "scene_shift_reason": str(progression.get("scene_shift_reason", "")).strip(),
        "turns_in_current_scene": int(progression.get("turns_in_current_scene", 0) or 0),
        "beat_maturity": int(progression.get("beat_maturity", 0) or 0),
        "world_tension_summary": str(progression.get("world_tension_summary", "")).strip(),
        "updated_at": (
            str(progression.get("updated_at", "")).strip()
            or str(presence.get("updated_at", "")).strip()
            or str(scene.get("updated_at", "")).strip()
        ),
    }


def set_session_scene_progress(state: dict[str, Any], payload: dict[str, Any], *, updated_at: str) -> None:
    state["scene"] = {
        "location": str(payload.get("location", "")).strip(),
        "time_hint": str(payload.get("time_hint", "")).strip(),
        "atmosphere_summary": str(payload.get("atmosphere_summary", "")).strip(),
        "progression_note": str(payload.get("progression_note", "")).strip(),
        "updated_at": updated_at,
    }
    state["presence"] = {
        "present_participants": [
            str(item).strip() for item in list(payload.get("present_participants", []) or []) if str(item).strip()
        ],
        "offstage_participants": [
            str(item).strip() for item in list(payload.get("offstage_participants", []) or []) if str(item).strip()
        ],
        "updated_at": updated_at,
    }
    state["progression"] = {
        "should_offer_scene_shift": bool(payload.get("should_offer_scene_shift", False)),
        "scene_shift_reason": str(payload.get("scene_shift_reason", "")).strip(),
        "turns_in_current_scene": int(payload.get("turns_in_current_scene", 0) or 0),
        "beat_maturity": int(payload.get("beat_maturity", 0) or 0),
        "world_tension_summary": str(payload.get("world_tension_summary", "")).strip(),
        "updated_at": updated_at,
    }


def relation_matrix(state: dict[str, Any]) -> dict[str, Any]:
    return dict(state.get("relations", {}).get("matrix", {}) or {})


def set_relation_matrix(state: dict[str, Any], payload: dict[str, Any] | None) -> None:
    state.setdefault("relations", {})["matrix"] = dict(payload or {})


def relation_delta(state: dict[str, Any]) -> dict[str, Any]:
    return dict(state.get("relations", {}).get("delta", {}) or {})


def set_relation_delta(state: dict[str, Any], payload: dict[str, Any] | None) -> None:
    state.setdefault("relations", {})["delta"] = dict(payload or {})


def character_snapshots(state: dict[str, Any]) -> dict[str, Any]:
    return dict(state.get("characters", {}).get("snapshots", {}) or {})


def set_character_snapshots(state: dict[str, Any], payload: dict[str, Any] | None) -> None:
    state.setdefault("characters", {})["snapshots"] = dict(payload or {})


def event_signals(state: dict[str, Any]) -> dict[str, Any]:
    return dict(state.get("signals", {}) or {})


def set_event_signals(state: dict[str, Any], payload: dict[str, Any] | None) -> None:
    state["signals"] = dict(payload or empty_event_signals_state())


def memory_summary(state: dict[str, Any]) -> dict[str, Any]:
    return dict(state.get("memory", {}).get("summary", {}) or {})


def set_memory_summary(state: dict[str, Any], payload: dict[str, Any] | None) -> None:
    state.setdefault("memory", {})["summary"] = dict(payload or {})


__all__ = [
    "character_snapshots",
    "empty_event_signals_state",
    "empty_session_state",
    "ensure_session_state",
    "event_signals",
    "memory_summary",
    "relation_delta",
    "relation_matrix",
    "session_scene_progress",
    "set_character_snapshots",
    "set_event_signals",
    "set_memory_summary",
    "set_relation_delta",
    "set_relation_matrix",
    "set_session_scene_progress",
]
