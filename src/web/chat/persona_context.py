from __future__ import annotations

from pathlib import Path
from typing import Any

from src.web.artifacts.ingest import load_profile_source


def build_persona_contexts(
    *,
    participants: list[str],
    active_participants: list[str],
    persona_map: dict[str, dict[str, Any]],
    mode: str,
    controlled_character: str,
    character_snapshots: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    detailed_names: list[str] = []
    for name in active_participants:
        normalized = str(name).strip()
        if normalized and normalized not in detailed_names:
            detailed_names.append(normalized)
    if mode == "act" and controlled_character and controlled_character not in detailed_names:
        detailed_names.append(controlled_character)
    detailed_budget = 4 if mode == "observe" else 3
    detailed_set = set(detailed_names[:detailed_budget])

    ordered_names = [name for name in participants if name in detailed_set] + [
        name for name in participants if name not in detailed_set
    ]
    contexts: list[dict[str, Any]] = []
    for name in ordered_names:
        normalized_name = str(name).strip()
        if not normalized_name:
            continue
        meta = persona_map.get(normalized_name, {})
        normalized_profile, profile_path = load_persona_profile(meta)
        is_detailed = normalized_name in detailed_set
        contexts.append(
            {
                "name": normalized_name,
                "profile_file": str(profile_path.resolve()) if profile_path.exists() else "",
                "persona_dir": str(meta.get("persona_dir", "")),
                "preview": persona_preview_payload(meta, normalized_profile),
                "profile": persona_profile_payload(normalized_profile, detailed=is_detailed),
                "detail_level": "full" if is_detailed else "compact",
                "is_active": normalized_name in set(active_participants),
                "session_snapshot": persona_snapshot_payload(
                    dict((character_snapshots or {}).get(normalized_name, {}) or {}),
                    detailed=is_detailed,
                ),
            }
        )
    return contexts


def load_persona_profile(meta: dict[str, Any]) -> tuple[dict[str, Any], Path]:
    profile_path_text = str(meta.get("profile_file", "")).strip()
    profile_path = Path(profile_path_text) if profile_path_text else Path("__missing_profile__")
    normalized: dict[str, Any] = dict(meta.get("profile", {}) or {})
    if profile_path.exists():
        normalized = load_profile_source(profile_path)
    return normalized, profile_path


def persona_preview_payload(meta: dict[str, Any], normalized_profile: dict[str, Any]) -> dict[str, Any]:
    preview = dict(meta.get("preview", {}) or {})
    return {
        "display_name": str(preview.get("display_name", "")).strip()
        or str(normalized_profile.get("display_name", "")).strip(),
        "core_identity": str(preview.get("core_identity", "")).strip()
        or str(normalized_profile.get("core_identity", "")).strip(),
        "speech_style": str(preview.get("speech_style", "")).strip()
        or str(normalized_profile.get("speech_style", "")).strip(),
        "appearance_feature": str(preview.get("appearance_feature", "")).strip()
        or str(normalized_profile.get("appearance_feature", "")).strip(),
    }


def persona_profile_payload(normalized_profile: dict[str, Any], *, detailed: bool) -> dict[str, Any]:
    base = {
        "core_identity": normalized_profile.get("core_identity", ""),
        "story_role": normalized_profile.get("story_role", ""),
        "gender": normalized_profile.get("gender", ""),
        "age_stage": normalized_profile.get("age_stage", ""),
        "appearance_feature": normalized_profile.get("appearance_feature", ""),
        "habit_action": normalized_profile.get("habit_action", ""),
        "speech_style": normalized_profile.get("speech_style", ""),
        "temperament_type": normalized_profile.get("temperament_type", ""),
        "stress_response": normalized_profile.get("stress_response", ""),
        "key_bonds": normalized_profile.get("key_bonds", []),
    }
    if detailed:
        base.update(
            {
                "soul_goal": normalized_profile.get("soul_goal", ""),
                "worldview": normalized_profile.get("worldview", ""),
                "social_mode": normalized_profile.get("social_mode", ""),
                "preference_like": normalized_profile.get("preference_like", []),
                "dislike_hate": normalized_profile.get("dislike_hate", []),
                "reward_logic": normalized_profile.get("reward_logic", ""),
                "decision_rules": normalized_profile.get("decision_rules", []),
                "forbidden_behaviors": normalized_profile.get("forbidden_behaviors", []),
                "moral_bottom_line": normalized_profile.get("moral_bottom_line", ""),
            }
        )
    return base


def persona_snapshot_payload(snapshot: dict[str, Any], *, detailed: bool) -> dict[str, Any]:
    if not snapshot:
        return {}
    fields = {
        "mood": str(snapshot.get("mood", "")).strip(),
        "interaction_state": str(snapshot.get("interaction_state", "")).strip(),
        "focus": str(snapshot.get("focus", "")).strip(),
        "last_target": str(snapshot.get("last_target", "")).strip(),
        "last_message": str(snapshot.get("last_message", "")).strip(),
        "present_state": str(snapshot.get("present_state", "")).strip(),
        "scene_location": str(snapshot.get("scene_location", "")).strip(),
        "time_hint": str(snapshot.get("time_hint", "")).strip(),
    }
    if detailed:
        fields["last_event"] = str(snapshot.get("last_event", "")).strip()
        fields["updated_at"] = str(snapshot.get("updated_at", "")).strip()
    return {key: value for key, value in fields.items() if value}


__all__ = [
    "build_persona_contexts",
    "load_persona_profile",
    "persona_preview_payload",
    "persona_profile_payload",
    "persona_snapshot_payload",
]
