from __future__ import annotations

from copy import deepcopy
import json
import re
from typing import Any, Callable

from src.core.exceptions import LLMRequestError
from src.skill_support.scene_recommendations import build_scene_opening_message
from prompts.loader import (
    get_dialogue_director_prompt,
    get_dialogue_suggestions_prompt,
    get_consistency_review_prompt,
    get_inner_thought_rule,
)


DIALOGUE_SUGGESTION_COMPACT_PROMPT_CHAR_THRESHOLD = 18_000

# 推理模型会把 reasoning_content 计入单次输出预算。1600 token 常在生成实际
# JSON 回复前耗尽，随后又触发一次完整重试。默认给足一轮预算，避免重复推理；
# 上限与模型设置页的可配置范围保持一致。
DIALOGUE_RESPONSE_MIN_MAX_TOKENS = 8_192
DIALOGUE_RESPONSE_MAX_MAX_TOKENS = 16_000


# 从配置文件加载读心功能规则
_INNER_THOUGHT_RULE = get_inner_thought_rule()


def _strip_code_fence(text: str) -> str:
    text = str(text or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        if "\n" in text:
            text = text.split("\n", 1)[1]
        if text.endswith("```"):
            text = text[:-3].strip()
    return text


def _balanced_json_candidates(text: str) -> list[str]:
    """Return complete object and array fragments without joining unrelated text."""
    candidates: list[str] = []
    start = 0
    while start < len(text):
        opener = text[start]
        if opener not in "[{":
            start += 1
            continue
        stack = ["]" if opener == "[" else "}"]
        in_string = False
        escaped = False
        completed = False
        for end in range(start + 1, len(text)):
            char = text[end]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue
            if char == '"':
                in_string = True
            elif char == "[":
                stack.append("]")
            elif char == "{":
                stack.append("}")
            elif char in "]}":
                if char != stack[-1]:
                    break
                stack.pop()
                if not stack:
                    candidates.append(text[start : end + 1])
                    start = end + 1
                    completed = True
                    break
        if completed:
            continue
        # A JSON-looking root that reaches EOF without closing is truncated.
        # Do not accept an object nested inside it as a separate response.
        if start + 1 < len(text) and text[start + 1] in '{["-0123456789tfn \t\r\n':
            break
        start += 1
    return candidates


def _loads_llm_json(text: str, *, prefer_array: bool = False) -> Any:
    text = _strip_code_fence(text).lstrip("\ufeff").replace("\u00a0", " ")
    if not text:
        raise ValueError("Model returned empty JSON.")
    candidates: list[str] = [text]
    for match in re.finditer(r"```(?:json)?[^\S\r\n]*\r?\n(.*?)```", text, re.DOTALL):
        fenced = match.group(1).strip()
        if fenced and fenced not in candidates:
            candidates.append(fenced)
    fragments = _balanced_json_candidates(text)
    preferred_opener = "[" if prefer_array else "{"
    fragments.sort(key=lambda candidate: 0 if candidate.startswith(preferred_opener) else 1)
    for fragment in fragments:
        if fragment not in candidates:
            candidates.append(fragment)
    last_error: json.JSONDecodeError | None = None
    for candidate in candidates:
        for strict in (True, False):
            try:
                return json.loads(candidate, strict=strict)
            except json.JSONDecodeError as exc:
                last_error = exc
                continue
    raise ValueError("Model reply is not valid JSON.") from last_error


def _session_state(session: dict[str, Any]) -> dict[str, Any]:
    return dict(session.get("state", {}) or {})


def _canonical_scene_progress(session: dict[str, Any]) -> dict[str, Any]:
    state = _session_state(session)
    scene = dict(state.get("scene", {}) or {})
    presence = dict(state.get("presence", {}) or {})
    progression = dict(state.get("progression", {}) or {})
    derived = {
        "present_participants": list(presence.get("present_participants", []) or []),
        "offstage_participants": list(presence.get("offstage_participants", []) or []),
        "time_hint": str(scene.get("time_hint", "")).strip(),
        "location": str(scene.get("location", "")).strip(),
        "atmosphere_summary": str(scene.get("atmosphere_summary", "")).strip(),
        "progression_note": str(scene.get("progression_note", "")).strip(),
        "should_offer_scene_shift": bool(
            progression.get("should_offer_scene_shift", False)
        ),
        "scene_shift_reason": str(progression.get("scene_shift_reason", "")).strip(),
        "turns_in_current_scene": int(
            progression.get("turns_in_current_scene", 0) or 0
        ),
        "beat_maturity": int(progression.get("beat_maturity", 0) or 0),
        "world_tension_summary": str(
            progression.get("world_tension_summary", "")
        ).strip(),
        "updated_at": (
            str(progression.get("updated_at", "")).strip()
            or str(presence.get("updated_at", "")).strip()
            or str(scene.get("updated_at", "")).strip()
        ),
    }
    merged = dict(derived)
    merged.update(dict(session.get("scene_progress", {}) or {}))
    return {
        key: value
        for key, value in merged.items()
        if value not in ("", [], False, 0, None)
    }


def _canonical_relation_delta(session: dict[str, Any]) -> dict[str, Any]:
    state = _session_state(session)
    relations = dict(state.get("relations", {}) or {})
    return dict(session.get("relation_delta", {}) or relations.get("delta", {}) or {})


def _canonical_character_snapshots(session: dict[str, Any]) -> dict[str, Any]:
    state = _session_state(session)
    characters = dict(state.get("characters", {}) or {})
    return dict(
        session.get("character_snapshots", {}) or characters.get("snapshots", {}) or {}
    )


def _canonical_event_signals(session: dict[str, Any]) -> dict[str, Any]:
    state = _session_state(session)
    return dict(session.get("event_signals", {}) or state.get("signals", {}) or {})


def build_dialogue_opening_message(session: dict[str, Any]) -> str:
    return build_scene_opening_message(
        mode=str(session.get("mode", "observe")).strip() or "observe",
        participants=[
            str(item).strip()
            for item in session.get("participants", [])
            if str(item).strip()
        ],
        scene_card=dict(session.get("scene_card", {}) or {}),
        controlled_character=str(session.get("controlled_character", "")).strip(),
        self_profile=dict(session.get("self_insert", {}) or {}),
    )


def friendly_dialogue_llm_error(exc: Exception) -> str:
    message = str(exc or "").strip()
    lowered = message.lower()
    if any(
        token in lowered
        for token in (
            "invalidsubscription",
            "codingplan",
            "subscription has expired",
            "does not have a valid",
        )
    ):
        return "当前模型账号没有可用的对话生成订阅权限，请更换可用模型，或检查并续订当前账号权限。"
    if any(
        token in lowered
        for token in (
            "maximum context",
            "context length",
            "prompt is too long",
            "too many tokens",
            "max context",
        )
    ):
        return "当前模型拒绝了这次续写建议请求，通常是上下文太长。系统已尝试自动压缩；如果仍失败，请减少参与角色或先清空一部分聊天上下文后重试。"
    return message or "当前模型调用失败，请检查模型配置后重试。"


def should_retry_suggestion_with_compact_payload(exc: Exception) -> bool:
    if not isinstance(exc, LLMRequestError):
        return False
    lowered = str(exc or "").lower()
    if "400" in lowered and "bad request" in lowered:
        return True
    return any(
        token in lowered
        for token in (
            "maximum context",
            "context length",
            "prompt is too long",
            "too many tokens",
            "max context",
            "context_window_exceeded",
        )
    )


def compact_dialogue_suggestion_payload(payload: dict[str, Any]) -> dict[str, Any]:
    compact = deepcopy(payload)

    compact["history"] = list(compact.get("history", []) or [])[-4:]

    input_block = dict(compact.get("input", {}) or {})
    input_block["message"] = _trim_text(
        str(input_block.get("message", "")).strip(), 120
    )
    compact["input"] = input_block

    relation_context = dict(compact.get("relation_context", {}) or {})
    relation_context["relations_excerpt"] = _trim_text(
        str(relation_context.get("relations_excerpt", "")).strip(), 1200
    )
    compact["relation_context"] = relation_context
    compact["memory_context"] = _compact_memory_context(
        dict(compact.get("memory_context", {}) or {})
    )

    compact["persona_contexts"] = [
        _compact_persona_context(item)
        for item in list(compact.get("persona_contexts", []) or [])[:4]
    ]
    compact["user_persona"] = _compact_user_persona(
        dict(compact.get("user_persona", {}) or {})
    )
    return compact


def _compact_persona_context(item: dict[str, Any]) -> dict[str, Any]:
    preview = dict(item.get("preview", {}) or {})
    profile = dict(item.get("profile", {}) or {})
    snapshot = dict(item.get("session_snapshot", {}) or {})
    compact_preview = {
        key: value
        for key, value in {
            "display_name": str(preview.get("display_name", "")).strip(),
            "core_identity": str(preview.get("core_identity", "")).strip(),
            "speech_style": str(preview.get("speech_style", "")).strip(),
            "appearance_feature": _trim_text(
                str(preview.get("appearance_feature", "")).strip(), 80
            ),
        }.items()
        if _has_meaningful_value(value)
    }
    compact_profile = {
        key: value
        for key, value in {
            "core_identity": str(profile.get("core_identity", "")).strip(),
            "story_role": str(profile.get("story_role", "")).strip(),
            "gender": str(profile.get("gender", "")).strip(),
            "age_stage": str(profile.get("age_stage", "")).strip(),
            "appearance_feature": _trim_text(
                str(profile.get("appearance_feature", "")).strip(), 100
            ),
            "habit_action": _trim_text(
                str(profile.get("habit_action", "")).strip(), 80
            ),
            "speech_style": str(profile.get("speech_style", "")).strip(),
            "temperament_type": str(profile.get("temperament_type", "")).strip(),
            "stress_response": str(profile.get("stress_response", "")).strip(),
            "key_bonds": _normalize_short_list(profile.get("key_bonds")),
            "preference_like": _normalize_short_list(profile.get("preference_like")),
            "dislike_hate": _normalize_short_list(profile.get("dislike_hate")),
        }.items()
        if _has_meaningful_value(value)
    }
    compact_snapshot = {
        key: value
        for key, value in {
            "mood": str(snapshot.get("mood", "")).strip(),
            "interaction_state": str(snapshot.get("interaction_state", "")).strip(),
            "focus": str(snapshot.get("focus", "")).strip(),
            "last_target": str(snapshot.get("last_target", "")).strip(),
            "last_event": _trim_text(str(snapshot.get("last_event", "")).strip(), 80),
        }.items()
        if _has_meaningful_value(value)
    }
    return {
        "name": str(item.get("name", "")).strip(),
        "preview": compact_preview,
        "profile": compact_profile,
        "session_snapshot": compact_snapshot,
    }


def _compact_static_persona_context(item: dict[str, Any]) -> dict[str, Any]:
    compact = _compact_persona_context(item)
    profile = dict(compact.get("profile", {}) or {})
    profile.pop("preference_like", None)
    profile.pop("dislike_hate", None)
    return {
        "name": str(compact.get("name", "")).strip(),
        "preview": dict(compact.get("preview", {}) or {}),
        "profile": profile,
    }


def _compact_active_persona_state(item: dict[str, Any]) -> dict[str, Any]:
    compact = _compact_persona_context(item)
    profile = dict(compact.get("profile", {}) or {})
    details = {
        key: profile[key]
        for key in ("preference_like", "dislike_hate")
        if _has_meaningful_value(profile.get(key))
    }
    return {
        key: value
        for key, value in {
            "name": str(compact.get("name", "")).strip(),
            "profile_details": details,
            "session_snapshot": dict(compact.get("session_snapshot", {}) or {}),
        }.items()
        if _has_meaningful_value(value)
    }


def _compact_user_persona(persona: dict[str, Any]) -> dict[str, Any]:
    profile = dict(persona.get("profile", {}) or {})
    compact_profile = {
        key: value
        for key, value in {
            "display_name": str(profile.get("display_name", "")).strip(),
            "scene_identity": str(profile.get("scene_identity", "")).strip(),
            "interaction_style": str(profile.get("interaction_style", "")).strip(),
            "core_identity": str(profile.get("core_identity", "")).strip(),
            "story_role": str(profile.get("story_role", "")).strip(),
            "gender": str(profile.get("gender", "")).strip(),
            "age_stage": str(profile.get("age_stage", "")).strip(),
            "appearance_feature": _trim_text(
                str(profile.get("appearance_feature", "")).strip(), 100
            ),
            "habit_action": _trim_text(
                str(profile.get("habit_action", "")).strip(), 80
            ),
            "soul_goal": str(profile.get("soul_goal", "")).strip(),
            "speech_style": str(profile.get("speech_style", "")).strip(),
            "worldview": _trim_text(str(profile.get("worldview", "")).strip(), 120),
            "belief_anchor": _trim_text(
                str(profile.get("belief_anchor", "")).strip(), 120
            ),
            "stress_response": _trim_text(
                str(profile.get("stress_response", "")).strip(), 120
            ),
            "key_bonds": _normalize_short_list(profile.get("key_bonds")),
            "preference_like": _normalize_short_list(profile.get("preference_like")),
            "dislike_hate": _normalize_short_list(profile.get("dislike_hate")),
            "preferred_moves": _normalize_short_list(profile.get("preferred_moves")),
            "goal": str(profile.get("goal", "")).strip(),
        }.items()
        if _has_meaningful_value(value)
    }
    compact_persona = dict(persona)
    compact_persona["profile"] = compact_profile
    scene_card = dict(persona.get("scene_card", {}) or {})
    compact_persona["scene_card"] = {
        key: value
        for key, value in {
            "title": str(scene_card.get("title", "")).strip(),
            "location": str(scene_card.get("location", "")).strip(),
            "atmosphere": str(scene_card.get("atmosphere", "")).strip(),
            "opening_situation": _trim_text(
                str(scene_card.get("opening_situation", "")).strip(), 140
            ),
            "public_goal": _trim_text(
                str(scene_card.get("public_goal", "")).strip(), 140
            ),
            "hidden_tension": _trim_text(
                str(scene_card.get("hidden_tension", "")).strip(), 140
            ),
            "scene_drive": _trim_text(
                str(scene_card.get("scene_drive", "")).strip(), 140
            ),
            "expected_rhythm": str(scene_card.get("expected_rhythm", "")).strip(),
        }.items()
        if _has_meaningful_value(value)
    }
    return compact_persona


def _compact_association_history(history: list[Any]) -> list[dict[str, str]]:
    compact: list[dict[str, str]] = []
    for raw_item in history[-4:]:
        if not isinstance(raw_item, dict):
            continue
        item = {
            key: value
            for key, value in {
                "speaker": str(raw_item.get("speaker", "")).strip(),
                "role": str(raw_item.get("role", "")).strip(),
                "message": _trim_text(
                    str(raw_item.get("message", "")).strip(), 160
                ),
            }.items()
            if value
        }
        if item.get("message"):
            compact.append(item)
    return compact


def _compact_association_scene_card(scene_card: dict[str, Any]) -> dict[str, Any]:
    return {
        key: _trim_text(str(scene_card.get(key, "")).strip(), 140)
        for key in (
            "title",
            "location",
            "time",
            "time_hint",
            "atmosphere",
            "opening_situation",
            "public_goal",
            "hidden_tension",
            "scene_drive",
            "expected_rhythm",
        )
        if _has_meaningful_value(scene_card.get(key))
    }


def _compact_association_scene_progress(
    scene_progress: dict[str, Any],
) -> dict[str, Any]:
    compact: dict[str, Any] = {}
    for key in (
        "present_participants",
        "offstage_participants",
        "time_hint",
        "location",
        "atmosphere_summary",
        "progression_note",
        "beat_maturity",
        "world_tension_summary",
        "should_offer_scene_shift",
        "scene_shift_reason",
        "next_hint",
    ):
        value = scene_progress.get(key)
        if not _has_meaningful_value(value):
            continue
        if isinstance(value, list):
            compact[key] = [
                _trim_text(str(item).strip(), 80)
                for item in value[:6]
                if str(item).strip()
            ]
        elif isinstance(value, str):
            compact[key] = _trim_text(value, 160)
        else:
            compact[key] = value
    return compact


def _compact_memory_context(memory_context: dict[str, Any]) -> dict[str, Any]:
    session_summary = dict(memory_context.get("session_summary", {}) or {})
    archived_summary = dict(memory_context.get("archived_summary", {}) or {})
    retrieved_memories = list(memory_context.get("retrieved_memories", []) or [])
    scene_progress = dict(memory_context.get("scene_progress", {}) or {})
    relation_delta = dict(memory_context.get("relation_delta", {}) or {})
    character_snapshots = dict(memory_context.get("character_snapshots", {}) or {})
    event_signals = list(memory_context.get("event_signals", []) or [])
    controlled_memories = list(memory_context.get("controlled_memories", []) or [])
    world_facts = list(memory_context.get("world_facts", []) or [])
    compact_archived = {
        key: value
        for key, value in {
            "summary": _trim_text(
                str(archived_summary.get("summary", "")).strip(), 180
            ),
            "key_points": [
                _trim_text(str(item).strip(), 80)
                for item in list(archived_summary.get("key_points", []) or [])[:3]
                if str(item).strip()
            ],
            "compressed_turns": archived_summary.get("compressed_turns", 0),
        }.items()
        if _has_meaningful_value(value)
    }
    compact_hits: list[dict[str, Any]] = []
    for item in retrieved_memories[:2]:
        compact_hit = {
            key: value
            for key, value in {
                "text": _trim_text(str(item.get("text", "")).strip(), 100),
                "speaker": str(item.get("speaker", "")).strip(),
                "target": str(item.get("target", "")).strip(),
                "kind": str(item.get("kind", "")).strip(),
            }.items()
            if _has_meaningful_value(value)
        }
        if compact_hit:
            compact_hits.append(compact_hit)
    compact_world_facts: list[dict[str, Any]] = []
    for item in sorted(
        (fact for fact in world_facts if isinstance(fact, dict)),
        key=lambda fact: not bool(fact.get("locked", False)),
    )[:18]:
        compact_fact = {
            key: value
            for key, value in {
                "fact_id": str(item.get("fact_id", "")).strip(),
                "category": str(item.get("category", "")).strip(),
                "summary": _trim_text(str(item.get("summary", "")).strip(), 240),
                "characters": [
                    _trim_text(str(name).strip(), 80)
                    for name in list(item.get("characters", []) or [])[:12]
                    if str(name).strip()
                ],
                "location": _trim_text(str(item.get("location", "")).strip(), 100),
                "time_hint": _trim_text(str(item.get("time_hint", "")).strip(), 80),
                "locked": bool(item.get("locked", False)),
            }.items()
            if _has_meaningful_value(value) or key == "locked"
        }
        if compact_fact.get("summary"):
            compact_world_facts.append(compact_fact)
    return {
        key: value
        for key, value in {
            "session_summary": {
                inner_key: _trim_text(str(inner_value).strip(), 120)
                for inner_key, inner_value in session_summary.items()
                if _has_meaningful_value(inner_value)
            },
            "archived_summary": compact_archived,
            "retrieved_memories": compact_hits,
            "scene_progress": {
                inner_key: (
                    list(inner_value)[:6]
                    if isinstance(inner_value, list)
                    else inner_value
                )
                for inner_key, inner_value in scene_progress.items()
                if _has_meaningful_value(inner_value)
            },
            "relation_delta": {
                str(pair_key).strip(): {
                    metric_key: metric_value
                    for metric_key, metric_value in dict(delta or {}).items()
                    if metric_value not in ("", [], 0, None)
                }
                for pair_key, delta in list(relation_delta.items())[:3]
                if str(pair_key).strip()
            },
            "character_snapshots": {
                str(name).strip(): {
                    snap_key: _trim_text(str(snap_value).strip(), 80)
                    for snap_key, snap_value in dict(snapshot or {}).items()
                    if _has_meaningful_value(snap_value)
                }
                for name, snapshot in list(character_snapshots.items())[:4]
                if str(name).strip()
            },
            "event_signals": [
                {
                    key: (
                        _trim_text(str(value).strip(), 80)
                        if isinstance(value, str)
                        else value
                    )
                    for key, value in dict(item or {}).items()
                    if value not in ("", [], None, False)
                }
                for item in event_signals[-6:]
                if dict(item or {}).get("kind")
            ],
            "world_facts": compact_world_facts,
            "controlled_memories": [
                {
                    "memory_id": str(item.get("memory_id", "")).strip(),
                    "text": _trim_text(str(item.get("text", "")).strip(), 500),
                    "category": str(item.get("category", "story")).strip()
                    or "story",
                    "pinned": bool(item.get("pinned", False)),
                }
                for item in controlled_memories[:20]
                if isinstance(item, dict) and str(item.get("text", "")).strip()
            ],
        }.items()
        if _has_meaningful_value(value)
    }


def _compact_association_memory_context(
    memory_context: dict[str, Any],
) -> dict[str, Any]:
    compact = _compact_memory_context(memory_context)
    session_summary = dict(compact.get("session_summary", {}) or {})
    summary_keys = (
        "current_location",
        "current_companions",
        "pending_commitments",
        "current_goal",
        "unresolved_threads",
        "recent_conflicts",
        "major_beats",
    )
    controlled_memories = [
        {
            "text": _trim_text(str(item.get("text", "")).strip(), 180),
            "category": str(item.get("category", "story")).strip() or "story",
            "pinned": bool(item.get("pinned", False)),
        }
        for item in list(compact.get("controlled_memories", []) or [])[:4]
        if isinstance(item, dict) and str(item.get("text", "")).strip()
    ]
    return {
        key: value
        for key, value in {
            "session_summary": {
                key: session_summary[key]
                for key in summary_keys
                if _has_meaningful_value(session_summary.get(key))
            },
            "archived_summary": dict(compact.get("archived_summary", {}) or {}),
            "retrieved_memories": list(
                compact.get("retrieved_memories", []) or []
            )[:2],
            "controlled_memories": controlled_memories,
        }.items()
        if _has_meaningful_value(value)
    }


def _normalize_short_list(value: Any) -> list[str] | str:
    if isinstance(value, list):
        cleaned = [str(item).strip() for item in value if str(item).strip()]
        return cleaned[:4]
    text = str(value or "").strip()
    if not text:
        return ""
    parts = [
        part.strip() for part in text.replace("；", ";").split(";") if part.strip()
    ]
    return parts[:4] if parts else text


def _has_meaningful_value(value: Any) -> bool:
    if isinstance(value, list):
        return bool(value)
    return bool(str(value or "").strip())


def _trim_text(text: str, limit: int) -> str:
    cleaned = str(text or "").strip()
    if len(cleaned) <= limit:
        return cleaned
    return cleaned[: max(1, limit - 1)].rstrip() + "…"


def build_dialogue_llm_messages(
    payload: dict[str, Any], *, retry_on_empty: bool = False
) -> list[dict[str, Any]]:
    input_block = dict(payload.get("input", {}) or {})
    session_mode = str(payload.get("mode", "")).strip() or "observe"
    include_inner_thoughts = bool(payload.get("include_inner_thoughts", False))
    message_kind = (
        str(input_block.get("message_kind", "dialogue")).strip() or "dialogue"
    )
    participants = [
        str(item).strip()
        for item in input_block.get("participants", [])
        if str(item).strip()
    ]
    active_participants = [
        str(item).strip()
        for item in input_block.get("active_participants", [])
        if str(item).strip()
    ]
    raw_personas = list(payload.get("persona_contexts", []) or [])
    persona_map = {
        str(item.get("name", "")).strip(): item
        for item in raw_personas
        if isinstance(item, dict) and str(item.get("name", "")).strip()
    }
    stable_persona_names: list[str] = []
    for name in [*participants, *active_participants]:
        if name in persona_map and name not in stable_persona_names:
            stable_persona_names.append(name)
    active_persona_names: list[str] = []
    for name in [*active_participants, *participants]:
        if name in persona_map and name not in active_persona_names:
            active_persona_names.append(name)
    stable_persona_contexts = [
        _compact_static_persona_context(persona_map[name])
        for name in stable_persona_names[:6]
    ]
    active_persona_states = [
        _compact_active_persona_state(persona_map[name])
        for name in active_persona_names[:6]
    ]
    relation_excerpt = _trim_text(
        str(
            payload.get("relation_context", {}).get("relations_excerpt", "")
        ).strip(),
        1200,
    )
    history = list(payload.get("history", []) or [])[-6:]
    memory_context = _compact_memory_context(
        dict(payload.get("memory_context", {}) or {})
    )
    memory_context.pop("relation_delta", None)
    memory_context.pop("character_snapshots", None)
    memory_context["event_signals"] = list(
        memory_context.get("event_signals", []) or []
    )[-3:]
    knowledge_context = [
        {
            "fact": _trim_text(str(item.get("fact", "")).strip(), 120),
            "holders": [
                str(name).strip()
                for name in list(item.get("holders", []) or [])
                if str(name).strip()
            ][:8],
        }
        for item in list(payload.get("knowledge_context", []) or [])[-12:]
        if isinstance(item, dict) and str(item.get("fact", "")).strip()
    ]
    raw_responder_hints = list(payload.get("responder_hints", []) or [])
    possible_responders = {
        str(item.get("name", "")).strip()
        for item in raw_responder_hints
        if isinstance(item, dict) and str(item.get("name", "")).strip()
    }
    if not possible_responders:
        possible_responders = set(active_participants or participants)

    def source_entry_is_safe_for_shared_generation(item: dict[str, Any]) -> bool:
        visibility = str(item.get("visibility", "uncertain")).strip()
        if visibility == "public":
            return True
        allowed = {
            str(name).strip()
            for name in list(item.get("allowed_characters", []) or [])
            if str(name).strip()
        }
        return (
            visibility in {"scene", "private"}
            and bool(possible_responders)
            and possible_responders.issubset(allowed)
        )

    original_source_entries = [
        {
            "excerpt": _trim_text(str(item.get("excerpt", "")).strip(), 320),
            "visibility": str(item.get("visibility", "uncertain")).strip(),
            "allowed_characters": [
                str(name).strip()
                for name in list(item.get("allowed_characters", []) or [])
                if str(name).strip()
            ],
        }
        for item in list(
            dict(payload.get("original_source_context", {}) or {}).get(
                "entries", []
            )
            or []
        )[:3]
        if isinstance(item, dict)
        and str(item.get("source_id", "")).strip()
        and str(item.get("excerpt", "")).strip()
        and source_entry_is_safe_for_shared_generation(item)
    ]
    correction_context = dict(payload.get("correction_context", {}) or {})
    instructions = dict(payload.get("instructions", {}) or {})
    host_action = dict(payload.get("host_action", {}) or {})
    scene_card = dict(payload.get("scene_card", {}) or {})
    response_limit = int(host_action.get("response_limit_hint", 2) or 2)

    stable_system_parts = [
        str(payload.get("host_prompt_brief", "")).strip(),
        str(instructions.get("generation_goal", "")).strip(),
        str(instructions.get("mode_rule", "")).strip(),
        str(instructions.get("speaker_rule", "")).strip(),
        str(instructions.get("response_style", "")).strip(),
        str(instructions.get("scene_rule", "")).strip(),
        str(host_action.get("output_rule", "")).strip(),
        "角色的明显小动作不要单独写成旁白或场景提示；应尽量内嵌到该角色自己的台词里，用很短的括号动作来带出。",
        "只返回 JSON 数组，每项必须包含 speaker 和 message。",
    ]
    if include_inner_thoughts:
        stable_system_parts.append(_INNER_THOUGHT_RULE)
    stable_context = {
        "mode": session_mode,
        "participants": participants,
        "scene_card": scene_card,
        "persona_contexts": stable_persona_contexts,
    }
    stable_system_parts.append(
        "STATIC_CHARACTER_CONTEXT\n"
        + json.dumps(
            stable_context,
            ensure_ascii=False,
            separators=(",", ":"),
        )
    )

    turn_system_parts = [
        str(instructions.get("progression_rule", "")).strip(),
        str(instructions.get("plot_progression_contract", "")).strip(),
        str(instructions.get("response_count_rule", "")).strip(),
        str(instructions.get("group_chat_rule", "")).strip(),
        str(instructions.get("mention_rule", "")).strip(),
        str(instructions.get("temporary_npc_rule", "")).strip(),
    ]
    speaker_plan = dict(payload.get("speaker_plan", {}) or {})
    responder_hints = raw_responder_hints
    speaker_activity = list(payload.get("speaker_activity", []) or [])
    if speaker_plan:
        turn_system_parts.append(
            "SPEAKER_PLAN 给出本轮自然的介入优先级。优先参考 recommended_speakers，"
            "但若角色与当前动作无关，可以少说或不说；不得让离场角色或用户控制的角色越权发言。"
        )
    if knowledge_context:
        turn_system_parts.append(
            "KNOWLEDGE_BOUNDARY 中每条 fact 只允许 holders 中的角色知晓。"
            "未列入 holders 的角色不得提及、暗示或据此行动。"
        )
    if original_source_entries:
        turn_system_parts.append(
            "ORIGINAL_SOURCE_CONTEXT 是本轮从原作动态检索出的证据。原作证据优先于模型预训练记忆；"
            "不得用模型自带的作品知识补写检索结果中没有的事实。角色只能使用 allowed_characters 中包含自己名字的片段；"
            "visibility=uncertain 的片段只可供旁白组织场景，角色不得将其当成自己知道的事实。"
            "原文只用于内部约束，不要在回复中引用、复述出处或解释检索过程。"
        )
    if list(memory_context.get("controlled_memories", []) or []):
        turn_system_parts.append(
            "CONTROLLED_MEMORIES 是用户明确管理的有效记忆。"
            "其中 pinned=true 的内容属于必须持续遵守的硬设定；其他内容也应作为当前有效上下文，"
            "除非本轮输入明确修改了该设定。不要在回复中解释记忆系统。"
        )
    if list(memory_context.get("world_facts", []) or []):
        turn_system_parts.append(
            "WORLD_FACTS are current story facts. Facts with locked=true are binding: "
            "do not contradict, replace, or silently change them without an explicit in-story cause. "
            "Use other active facts as context and never explain the memory system in the reply."
        )
    if correction_context:
        turn_system_parts.append(
            "CORRECTION_CONTEXT 表示上一版回复存在一致性问题。"
            "请重写同一轮角色回复，逐项消除 issues；保留已经成立的场景事实，"
            "不要解释修正过程，也不要凭空新增事件。"
        )
    if retry_on_empty:
        turn_system_parts.append(
            "这次至少返回 1 条可用回复；只有在确实需要场景切换、人物进退场或环境变化时，才返回 speaker 为“旁白”或“场景提示”的一条提示。"
        )
        if message_kind == "plot":
            turn_system_parts.append(
                "上一版没有完成剧情推动。第一项必须是 speaker 为“场景提示”或“旁白”的具体新事件或状态变化，随后再写角色反应；不得只延续原话题闲聊。"
            )
    stable_system_prompt = "\n".join(
        part for part in stable_system_parts if part
    )
    turn_system_prompt = "\n".join(part for part in turn_system_parts if part)

    user_payload = {
        "mode": session_mode,
        "message_kind": message_kind,
        "speaker": str(input_block.get("speaker", "")).strip(),
        "message": str(input_block.get("message", "")).strip(),
        "participants": participants,
        "active_participants": active_participants,
        "mention_targets": list(input_block.get("mention_targets", []) or []),
        "memory_context": memory_context,
        "knowledge_boundary": knowledge_context,
        "original_source_context": original_source_entries,
        "correction_context": correction_context,
        "response_limit": response_limit,
        "active_persona_state": active_persona_states,
        "speaker_plan": speaker_plan,
        "responder_hints": responder_hints,
        "speaker_activity": speaker_activity,
        "history": history,
        "relation_excerpt": relation_excerpt,
        "expected_output": host_action.get(
            "expected_output",
            [
                {
                    "speaker": "角色名",
                    "message": "回复内容",
                    **(
                        {"inner_thought": "角色没说出口的真实想法"}
                        if include_inner_thoughts
                        else {}
                    ),
                }
            ],
        ),
        "retry_on_empty": retry_on_empty,
    }
    user_prompt = json.dumps(
        user_payload,
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return [
        {
            "role": "system",
            "content": stable_system_prompt,
            "cache_static": True,
        },
        {"role": "system", "content": turn_system_prompt},
        {"role": "user", "content": user_prompt},
    ]


def build_dialogue_suggestion_llm_messages(
    payload: dict[str, Any],
    *,
    retry_on_empty: bool = False,
) -> list[dict[str, str]]:
    input_block = dict(payload.get("input", {}) or {})
    session_mode = str(payload.get("mode", "")).strip() or "observe"
    participants = [
        str(item).strip()
        for item in input_block.get("participants", [])
        if str(item).strip()
    ]
    persona_contexts = payload.get("persona_contexts", [])
    user_persona = dict(payload.get("user_persona", {}) or {})
    relation_excerpt = str(
        payload.get("relation_context", {}).get("relations_excerpt", "")
    ).strip()
    history = payload.get("history", [])
    memory_context = dict(payload.get("memory_context", {}) or {})
    scene_progress = dict(
        payload.get("scene_progress", {})
        or memory_context.get("scene_progress", {})
        or {}
    )
    instructions = dict(payload.get("instructions", {}) or {})
    host_action = dict(payload.get("host_action", {}) or {})
    scene_card = dict(payload.get("scene_card", {}) or {})
    selected_direction = str(payload.get("selected_direction", "")).strip()

    system_parts = [
        str(payload.get("host_prompt_brief", "")).strip(),
        "你不是在解释剧情，也不是在做回复分析；你要直接代写一条用户下一句要发出去的话。",
        str(instructions.get("generation_goal", "")).strip(),
        str(instructions.get("mode_rule", "")).strip(),
        str(instructions.get("speaker_rule", "")).strip(),
        str(instructions.get("response_style", "")).strip(),
        str(instructions.get("scene_rule", "")).strip(),
        str(host_action.get("output_rule", "")).strip(),
        "必须优先参考 user_persona：这代表当前应该由“你”如何说话。",
        "如果 mode=insert，就按 self-insert 的完整角色卡来写，不只参考上下文和别人刚才的回复。",
        "优先服从 self-insert 的核心身份、故事位置、灵魂目标、气质底色、世界观、信念支点、说话方式、应激反应和 interaction_style。",
        "如果上下文允许多种接法，优先选更符合 user_persona 的那一种，而不是只做一个泛用接话。",
        "如果 mode=act，就按 controlled character 的 persona profile、speech_style、temperament 和典型说话习惯来写。",
        "如果 mode=observe，就把这句话写成推动剧情的场景提示：让局势往前走，而不是复述、总结、劝说或规划。",
        "如果 scene_progress 显示这一拍已经成熟、适合转场，就优先写成自然的转场推进；如果还没到转场时机，就优先续当前这一拍的动作、情绪或张力。",
        "如果 mode=observe，句子必须像“下一下已经发生了”的即时场景推进，而不是“要不要/不如/可以让他们/继续聊”这种调度口吻。",
        "如果 user_persona.profile.anchor_lines 里有当前目标、未收线或最近冲突，就优先咬住这些锚点来推进，不要另起一条太泛的新线。",
        "offstage_participants 里的人不要被你无端写回来，除非这句提示本身就在明确推动他们重新入场。",
        "如果 scene_card 存在，优先服从它给出的地点、气氛、开场局面、明面目标、暗线张力与推进方向。",
        "只输出一段完整、可直接发送的成品文案；根据所选方向需要，可以写一至三句，但不能在语义未完成处收尾。",
        "不要解释上下文，不要总结历史，不要提供建议理由，不要写“作为/当前场景/我们可以/你可以/建议/回复：”这类分析话术。",
        "不要分段，不要项目符号，不要加包裹整段的引号，不要加说话人标签。",
    ]
    if selected_direction:
        system_parts.extend(
            [
                "用户已经点选了一个剧情推进方向。必须把 selected_direction 落实成当前角色或观察者下一句真正会发出的成品文案。",
                "selected_direction 是写作意图，不是要照抄的台词；不要复述选项标题，也不要解释你如何落实它。",
            ]
        )
    if retry_on_empty:
        system_parts.append(
            "上一次输出不可用或在中途被截断。重来：给出语义完整、可直接发送的一至三句话，结尾必须完整。"
        )
    system_prompt = "\n".join(part for part in system_parts if part)

    user_payload = {
        "mode": session_mode,
        "speaker": str(input_block.get("speaker", "")).strip(),
        "seed_text": str(input_block.get("message", "")).strip(),
        "selected_direction": selected_direction,
        "scene_card": scene_card,
        "scene_progress": scene_progress,
        "memory_context": memory_context,
        "user_persona": user_persona,
        "participants": participants,
        "persona_contexts": persona_contexts,
        "history": history,
        "relation_excerpt": relation_excerpt,
        "response_shape": host_action.get(
            "expected_output", {"suggestion": "一段完整、可直接发送的文案"}
        ),
        "good_examples": {
            "act_or_insert": [
                "抱歉，我刚才那句说重了。",
                "你先别气，我不是在呛你。",
                "那我换个说法，你别误会。",
            ],
            "observe": [
                "门外忽然传来两下敲门声，屋里一下静了。",
                "江澄先看见了他袖口上的血，话到嘴边忽然顿住。",
                "魏无羡低头笑了一下，却没立刻接这句话。",
                "回廊外的雨忽然更近了，像是有人已经走到了檐下。",
            ],
        },
        "bad_examples": [
            "我们作为“你”是误入此间的来客……",
            "当前场景是对方在生气，我们可以先安抚……",
            "建议回复：先道歉，再解释。",
            "你们继续聊下去吧。",
            "要不先让他们把刚才那句接下去？",
            "不如让场景自然推进到下一幕。",
        ],
        "retry_on_empty": retry_on_empty,
    }
    user_prompt = json.dumps(
        user_payload,
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]


def build_dialogue_association_llm_messages(
    payload: dict[str, Any],
    *,
    retry_on_empty: bool = False,
) -> list[dict[str, str]]:
    input_block = dict(payload.get("input", {}) or {})
    memory_context = dict(payload.get("memory_context", {}) or {})
    instructions = dict(payload.get("instructions", {}) or {})
    host_action = dict(payload.get("host_action", {}) or {})
    option_count = max(2, min(int(instructions.get("option_count", 3) or 3), 4))
    response_shape = deepcopy(host_action.get("expected_output", {}) or {})
    option_shapes = [
        dict(item)
        for item in list(response_shape.get("options", []) or [])
        if isinstance(item, dict)
    ]
    if not option_shapes:
        option_shapes = [
            {
                "label": "4-10字的推进选项",
                "direction": "供下一步代写使用的明确剧情方向",
                "anchor_speaker": "该方向所依据的最新回复角色",
                "anchor_quote": "从该角色最新回复中原样摘录的4-20字",
            }
        ]
    for option_shape in option_shapes:
        option_shape.setdefault(
            "suggestion",
            "一至三句符合当前用户角色口吻、可直接发送的成品文案",
        )
    response_shape["options"] = option_shapes

    # 使用配置文件中的提示词
    instructions = dict(payload.get("instructions", {}) or {})
    host_action = dict(payload.get("host_action", {}) or {})
    system_prompt = get_dialogue_suggestions_prompt(
        option_count=option_count,
        retry=retry_on_empty,
        generation_goal=str(instructions.get("generation_goal", "")).strip(),
        output_rule=str(host_action.get("output_rule", "")).strip(),
    )
    scene_progress = dict(
        payload.get("scene_progress", {})
        or memory_context.get("scene_progress", {})
        or {}
    )
    user_payload = {
        "mode": str(payload.get("mode", "")).strip() or "observe",
        "speaker": str(input_block.get("speaker", "")).strip(),
        "latest_exchange": dict(payload.get("latest_exchange", {}) or {}),
        "participants": [
            str(item).strip()
            for item in input_block.get("participants", [])
            if str(item).strip()
        ],
        "recent_completed_history": _compact_association_history(
            list(payload.get("history", []) or [])
        ),
        "scene_card": _compact_association_scene_card(
            dict(payload.get("scene_card", {}) or {})
        ),
        "scene_progress": _compact_association_scene_progress(
            scene_progress
        ),
        "memory_anchors": _compact_association_memory_context(memory_context),
        "user_persona": _compact_user_persona(
            dict(payload.get("user_persona", {}) or {})
        ),
        "persona_contexts": [
            _compact_persona_context(item)
            for item in list(payload.get("persona_contexts", []) or [])[:4]
        ],
        "relation_excerpt": _trim_text(
            str(
                payload.get("relation_context", {}).get("relations_excerpt", "")
            ).strip(),
            800,
        ),
        "response_shape": response_shape,
        "option_count": option_count,
        "retry_on_empty": retry_on_empty,
    }
    return [
        {"role": "system", "content": system_prompt},
        {
            "role": "user",
            "content": json.dumps(
                user_payload,
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        },
    ]


def build_dialogue_director_llm_messages(
    payload: dict[str, Any], *, retry_on_empty: bool = False
) -> list[dict[str, str]]:
    option_count = max(2, min(int(payload.get("option_count", 3) or 3), 4))
    system_prompt = get_dialogue_director_prompt(option_count=option_count, retry=retry_on_empty)
    input_payload = dict(payload.get("input", {}) or {})
    user_payload = {
        "director_goal": str(payload.get("director_goal", "")).strip(),
        "director_action": str(payload.get("director_action", "advance")).strip(),
        "mode": str(payload.get("mode", "observe")).strip() or "observe",
        "participants": list(input_payload.get("participants", []) or []),
        "active_participants": list(input_payload.get("active_participants", []) or []),
        "scene_card": dict(payload.get("scene_card", {}) or {}),
        "scene_progress": dict(payload.get("scene_progress", {}) or {}),
        "latest_exchange": dict(payload.get("latest_exchange", {}) or {}),
        "memory_context": _compact_memory_context(dict(payload.get("memory_context", {}) or {})),
        "relation_excerpt": _trim_text(
            str(dict(payload.get("relation_context", {}) or {}).get("relations_excerpt", "")).strip(),
            1200,
        ),
        "speaker_activity": list(payload.get("speaker_activity", []) or []),
        "option_count": option_count,
    }
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False, indent=2)},
    ]


def build_dialogue_scene_progress_messages(
    session: dict[str, Any],
) -> list[dict[str, str]]:
    transcript = list(session.get("transcript", []) or [])
    recent: list[dict[str, str]] = []
    for item in transcript[-12:]:
        speaker = str(item.get("speaker", "")).strip()
        role = str(item.get("role", "")).strip()
        message = str(item.get("message", "")).strip()
        if not message:
            continue
        recent.append(
            {
                "speaker": speaker,
                "role": role,
                "message": _trim_text(message, 120),
            }
        )
    payload = {
        "mode": str(session.get("mode", "observe")).strip() or "observe",
        "participants": [
            str(item).strip()
            for item in list(session.get("participants", []) or [])
            if str(item).strip()
        ],
        "scene_card": dict(
            session.get("session_card", {}).get("scene_card", {})
            or session.get("scene_card", {})
            or {}
        ),
        "session_memory_summary": dict(session.get("session_memory_summary", {}) or {}),
        "recent_transcript": recent,
        "current_scene_progress": _canonical_scene_progress(session),
        "event_signals": _canonical_event_signals(session),
    }
    system_prompt = "\n".join(
        [
            "你不是来续写对白，而是来提取当前场景状态。",
            "请根据最近几轮对话，判断：谁仍在场、谁已经离场、时间是否推进、地点是否变化、这一幕是否已经适合提示下一幕。",
            "offstage_participants 里的人默认不应继续直接开口，除非最近文本明确写到他们回来、进门、现身、重新加入。",
            "如果最近内容已经从白天聊到傍晚、夜里、深夜等，time_hint 要跟着更新，而不是一直停在原时间。",
            "如果几个人已经离开原场所进入更私密的新地点，其他未同去角色不要继续被视作同场。",
            "atmosphere_summary 用一句很短的话概括当前氛围，比如“安静下来”“暧昧发僵”“雨夜压下来”。",
            "beat_maturity 用 0-100 的整数表示这一拍推进到什么程度：刚起势偏低，已经聊出完整一拍则更高。",
            "world_tension_summary 用一句话概括当前这局最该继续带着走的张力、冲突或悬念。",
            "event_signals 里如果出现 scene_transition / cast_enter / cast_exit / atmosphere_shift / time_change / environment_change / beat_complete，要把它们纳入判断。",
            "should_offer_scene_shift 只在这一幕已经聊出明显一拍、适合自然转场时返回 true。",
            "只返回 JSON 对象，不要解释。",
            '格式：{"present_participants":[],"offstage_participants":[],"time_hint":"","location":"","atmosphere_summary":"","progression_note":"","beat_maturity":0,"world_tension_summary":"","should_offer_scene_shift":false,"scene_shift_reason":""}',
        ]
    )
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": json.dumps(payload, ensure_ascii=False, indent=2)},
    ]


def build_dialogue_relation_state_messages(
    session: dict[str, Any],
    pending_payload: dict[str, Any],
    responses: list[dict[str, str]],
) -> list[dict[str, str]]:
    transcript = list(session.get("transcript", []) or [])
    recent: list[dict[str, str]] = []
    for item in transcript[-10:]:
        speaker = str(item.get("speaker", "")).strip()
        role = str(item.get("role", "")).strip()
        message = str(item.get("message", "")).strip()
        if not message:
            continue
        recent.append(
            {
                "speaker": speaker,
                "role": role,
                "message": _trim_text(message, 120),
            }
        )
    current_state = {
        "relation_delta": _canonical_relation_delta(session),
        "character_snapshots": _canonical_character_snapshots(session),
        "event_signals": _canonical_event_signals(session),
    }
    payload = {
        "participants": [
            str(item).strip()
            for item in list(session.get("participants", []) or [])
            if str(item).strip()
        ],
        "pending_input": {
            "speaker": str(
                dict(pending_payload.get("input", {}) or {}).get("speaker", "")
            ).strip(),
            "message": _trim_text(
                str(
                    dict(pending_payload.get("input", {}) or {}).get("message", "")
                ).strip(),
                120,
            ),
            "active_participants": [
                str(item).strip()
                for item in list(
                    dict(pending_payload.get("input", {}) or {}).get(
                        "active_participants", []
                    )
                    or []
                )
                if str(item).strip()
            ],
        },
        "recent_transcript": recent,
        "new_responses": [
            {
                "speaker": str(item.get("speaker", "")).strip(),
                "message": _trim_text(str(item.get("message", "")).strip(), 120),
            }
            for item in list(responses or [])
            if str(item.get("speaker", "")).strip()
            and str(item.get("message", "")).strip()
        ],
        "current_state": current_state,
    }
    system_prompt = "\n".join(
        [
            "你不是来续写剧情，而是来轻量修正当前会话的关系增量和人物快照。",
            "current_state 是启发式先写好的底稿；你只能小幅修正、补全或删掉明显不合语境的项，不能凭空重写成另一套关系。",
            "relation_delta 只记录本会话里的增量变化，不是人物一生的最终关系定论。",
            "character_snapshots 只描述本会话当前阶段的状态，比如 mood、interaction_state、focus、last_target、last_event。",
            "event_signals 是统一事件层：场景进入/退出、角色登场/离场、明显动作、氛围突变、时间/环境变化、关系变化、互动重心变化、拍点完成都会记在这里。",
            "如果一句话不足以支持明显变化，就宁可保守，不要过拟合。",
            "只返回 JSON 对象，不要解释。",
            '格式：{"relation_delta":{},"character_snapshots":{}}',
        ]
    )
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": json.dumps(payload, ensure_ascii=False, indent=2)},
    ]


def parse_dialogue_scene_progress(
    content: str, participants: list[str]
) -> dict[str, Any]:
    text = str(content or "").strip()
    if not text:
        raise ValueError("Model returned an empty scene progress state.")
    try:
        parsed = _loads_llm_json(text)
    except ValueError as exc:
        raise ValueError("Model reply is not valid scene progress JSON.") from exc
    if not isinstance(parsed, dict):
        raise ValueError("Scene progress state is not an object.")

    allowed = {str(item).strip() for item in participants if str(item).strip()}

    def clean_names(value: Any) -> list[str]:
        names: list[str] = []
        for item in list(value or []):
            name = str(item or "").strip()
            if not name or (allowed and name not in allowed) or name in names:
                continue
            names.append(name)
        return names

    present = clean_names(parsed.get("present_participants", []))
    offstage = [
        name
        for name in clean_names(parsed.get("offstage_participants", []))
        if name not in present
    ]
    try:
        beat_maturity = max(0, min(100, int(parsed.get("beat_maturity", 0) or 0)))
    except Exception:
        beat_maturity = 0

    return {
        "present_participants": present,
        "offstage_participants": offstage,
        "time_hint": _trim_text(str(parsed.get("time_hint", "")).strip(), 40),
        "location": _trim_text(str(parsed.get("location", "")).strip(), 40),
        "atmosphere_summary": _trim_text(
            str(parsed.get("atmosphere_summary", "")).strip(), 80
        ),
        "progression_note": _trim_text(
            str(parsed.get("progression_note", "")).strip(), 120
        ),
        "beat_maturity": beat_maturity,
        "world_tension_summary": _trim_text(
            str(parsed.get("world_tension_summary", "")).strip(), 120
        ),
        "should_offer_scene_shift": bool(parsed.get("should_offer_scene_shift", False)),
        "scene_shift_reason": _trim_text(
            str(parsed.get("scene_shift_reason", "")).strip(), 120
        ),
    }


def parse_dialogue_relation_state(
    content: str, participants: list[str]
) -> dict[str, Any]:
    text = str(content or "").strip()
    if not text:
        raise ValueError("Model returned an empty relation state.")
    try:
        parsed = _loads_llm_json(text)
    except ValueError as exc:
        raise ValueError("Model reply is not valid relation state JSON.") from exc
    if not isinstance(parsed, dict):
        raise ValueError("Relation state is not an object.")

    allowed = [str(item).strip() for item in participants if str(item).strip()]
    allowed_set = set(allowed)

    def pair_key(left: str, right: str) -> str:
        return "_".join(sorted([left, right]))

    allowed_pairs = {
        pair_key(left, right)
        for index, left in enumerate(allowed)
        for right in allowed[index + 1 :]
        if left and right
    }
    relation_delta: dict[str, Any] = {}
    for raw_key, raw_value in dict(parsed.get("relation_delta", {}) or {}).items():
        key = str(raw_key).strip()
        if not key or key not in allowed_pairs:
            continue
        item = dict(raw_value or {})
        normalized: dict[str, Any] = {}
        for field in ("trust", "affection", "hostility", "ambiguity"):
            try:
                amount = int(item.get(field, 0) or 0)
            except Exception:
                amount = 0
            if amount:
                normalized[field] = max(-3, min(3, amount))
        for field in (
            "last_event",
            "relation_change",
            "typical_interaction",
            "last_actor",
            "last_target",
            "updated_at",
        ):
            value = _trim_text(str(item.get(field, "")).strip(), 120)
            if value:
                normalized[field] = value
        try:
            momentum = int(item.get("momentum", 0) or 0)
        except Exception:
            momentum = 0
        if momentum:
            normalized["momentum"] = max(0, min(10, momentum))
        evidence_lines = [
            _trim_text(str(line).strip(), 180)
            for line in list(item.get("evidence_lines", []) or [])
            if str(line).strip()
        ]
        if evidence_lines:
            normalized["evidence_lines"] = evidence_lines[:10]
        if normalized:
            relation_delta[key] = normalized

    character_snapshots: dict[str, Any] = {}
    for raw_name, raw_value in dict(
        parsed.get("character_snapshots", {}) or {}
    ).items():
        name = str(raw_name).strip()
        if not name or name not in allowed_set:
            continue
        item = dict(raw_value or {})
        normalized = {
            "mood": _trim_text(str(item.get("mood", "")).strip(), 40),
            "interaction_state": _trim_text(
                str(item.get("interaction_state", "")).strip(), 40
            ),
            "focus": _trim_text(str(item.get("focus", "")).strip(), 40),
            "last_target": _trim_text(str(item.get("last_target", "")).strip(), 40),
            "last_message": _trim_text(str(item.get("last_message", "")).strip(), 180),
            "last_event": _trim_text(str(item.get("last_event", "")).strip(), 180),
        }
        normalized = {key: value for key, value in normalized.items() if value}
        if normalized:
            if (
                normalized.get("last_target")
                and normalized["last_target"] not in allowed_set
            ):
                normalized.pop("last_target", None)
            character_snapshots[name] = normalized

    return {
        "relation_delta": relation_delta,
        "character_snapshots": character_snapshots,
    }


_SPEAKER_DECORATION = re.compile(r"[（(\[【].*?[）)\]】]")
_SPEAKER_TRIM_CHARS = "　 \t·:：,，.。!！?？;；、\"'“”‘’*_-—"


def _normalize_speaker_key(name: str) -> str:
    """把模型写法归一化成可比对的 key：去装饰、去标点、忽略大小写。"""
    text = _SPEAKER_DECORATION.sub("", str(name or ""))
    text = text.strip(_SPEAKER_TRIM_CHARS)
    return " ".join(text.split()).casefold()


def _build_speaker_alias_map(allowed_speakers: set[str]) -> dict[str, str]:
    alias_map: dict[str, str] = {}
    for name in allowed_speakers:
        key = _normalize_speaker_key(name)
        if key:
            alias_map.setdefault(key, name)
    return alias_map


def _canonical_speaker_name(speaker: str, alias_map: dict[str, str]) -> str:
    """把模型返回的 speaker 映射回白名单里的正式名，映射不上返回空串。

    模型常见的偏差是带称谓、括号注释或标点，例如「祥子（车夫）」「祥子:」。
    直接按原文比对会静默丢弃整条回复，最终报“没有返回可用的角色回复”。

    这里只做归一化后的精确匹配，不做包含匹配：「虎妞的父亲」这类写法指的是
    另一个人，模糊匹配会把台词错记到在场角色名下，比丢弃更难发现。
    """
    key = _normalize_speaker_key(speaker)
    if not key:
        return ""
    return alias_map.get(key, "")


def parse_dialogue_responses(
    content: str,
    allowed_speakers: list[str],
    *,
    forbidden_speakers: list[str] | None = None,
    max_temporary_npcs: int = 1,
) -> list[dict[str, str]]:
    text = str(content or "").strip()
    if not text:
        raise ValueError("Model returned an empty reply.")
    try:
        parsed = _loads_llm_json(text, prefer_array=True)
    except ValueError as exc:
        raise ValueError("Model reply is not valid JSON.") from exc

    if isinstance(parsed, dict):
        parsed = parsed.get("responses", [])
    if not isinstance(parsed, list):
        raise ValueError("Model reply is not a response list.")

    forbidden_prefix = "__forbidden_speaker__:"
    allowed = {name for name in allowed_speakers if name}
    allow_temporary_npc = "__temporary_npc__" in allowed
    allowed.discard("__temporary_npc__")
    forbidden = {
        str(name).strip().casefold()
        for name in list(forbidden_speakers or [])
        if str(name).strip()
    }
    forbidden.update(
        name.removeprefix(forbidden_prefix).strip().casefold()
        for name in list(allowed)
        if name.startswith(forbidden_prefix)
        and name.removeprefix(forbidden_prefix).strip()
    )
    allowed = {name for name in allowed if not name.startswith(forbidden_prefix)}
    forbidden.update({"user", "你", "模型推理", "system", "assistant"})
    temporary_speakers: set[str] = set()
    alias_map = _build_speaker_alias_map(allowed)
    clean_responses: list[dict[str, str]] = []
    for item in parsed:
        if not isinstance(item, dict):
            continue
        speaker = str(item.get("speaker", "")).strip()
        message = str(item.get("message", "")).strip()
        if not speaker or not message:
            continue
        if allowed or allow_temporary_npc:
            canonical = _canonical_speaker_name(speaker, alias_map)
            if not canonical:
                normalized_speaker = speaker.casefold()
                if (
                    not allow_temporary_npc
                    or normalized_speaker in forbidden
                    or len(speaker) > 40
                    or "\n" in speaker
                    or "\r" in speaker
                ):
                    continue
                if normalized_speaker not in temporary_speakers:
                    if len(temporary_speakers) >= max(
                        0, int(max_temporary_npcs)
                    ):
                        continue
                    temporary_speakers.add(normalized_speaker)
            else:
                speaker = canonical
        inner_thought = _trim_text(str(item.get("inner_thought", "")).strip(), 50)
        clean_responses.append(
            {
                "speaker": speaker,
                "message": message,
                **({"inner_thought": inner_thought} if inner_thought else {}),
            }
        )
    if not clean_responses:
        raise ValueError("Model reply did not contain usable character responses.")
    return clean_responses


def build_dialogue_consistency_review_messages(
    payload: dict[str, Any],
) -> list[dict[str, str]]:
    review_payload = {
        "mode": str(payload.get("mode", "")).strip(),
        "participants": list(payload.get("participants", []) or []),
        "scene_progress": dict(payload.get("scene_progress", {}) or {}),
        "persona_contexts": list(payload.get("persona_contexts", []) or []),
        "relation_context": dict(payload.get("relation_context", {}) or {}),
        "knowledge_context": list(payload.get("knowledge_context", []) or []),
        "history": list(payload.get("history", []) or [])[-8:],
        "input": dict(payload.get("input", {}) or {}),
        "responses": list(payload.get("responses", []) or []),
        "deterministic_report": dict(payload.get("deterministic_report", {}) or {}),
    }
    system_prompt = get_consistency_review_prompt()
    return [
        {"role": "system", "content": system_prompt},
        {
            "role": "user",
            "content": json.dumps(review_payload, ensure_ascii=False, indent=2),
        },
    ]


def parse_dialogue_consistency_review(
    content: str,
    *,
    responses: list[dict[str, Any]],
    allowed_speakers: list[str],
) -> dict[str, Any]:
    parsed = _loads_llm_json(str(content or "").strip())
    if not isinstance(parsed, dict):
        raise ValueError("Consistency review is not an object.")
    allowed_codes = {
        "semantic_voice_drift",
        "semantic_motivation_drift",
        "semantic_relationship_drift",
        "semantic_knowledge_drift",
    }
    allowed = {str(item).strip() for item in allowed_speakers if str(item).strip()}
    response_map = {
        str(item.get("speaker", "")).strip(): str(item.get("message", "")).strip()
        for item in responses
        if str(item.get("speaker", "")).strip()
    }
    issues: list[dict[str, str]] = []
    seen: set[tuple[str, str, str]] = set()
    for raw in list(parsed.get("issues", []) or [])[:8]:
        if not isinstance(raw, dict):
            continue
        code = str(raw.get("code", "")).strip()
        speaker = str(raw.get("speaker", "")).strip()
        severity = str(raw.get("severity", "warning")).strip()
        evidence = str(raw.get("evidence", "")).strip()
        response_text = response_map.get(speaker, "")
        if (
            code not in allowed_codes
            or speaker not in allowed
            or severity not in {"warning", "error"}
            or len(evidence) < 2
            or evidence not in response_text
        ):
            continue
        key = (code, speaker, evidence)
        if key in seen:
            continue
        seen.add(key)
        issues.append(
            {
                "code": code,
                "severity": severity,
                "speaker": speaker,
                "title": _trim_text(str(raw.get("title", "")).strip(), 40)
                or "语义一致性异常",
                "detail": _trim_text(str(raw.get("detail", "")).strip(), 180),
                "evidence": evidence[:40],
                "source": "semantic_review",
            }
        )
    return {
        "issues": issues,
        "summary": _trim_text(str(parsed.get("summary", "")).strip(), 180),
    }


def _looks_like_meta_suggestion(text: str) -> bool:
    normalized = " ".join(str(text or "").split()).strip()
    if not normalized:
        return True
    if "\n" in str(text or ""):
        return True
    if len(normalized) > 600:
        return True

    meta_tokens = (
        "作为",
        "当前场景",
        "我们作为",
        "我们可以",
        "你可以",
        "建议",
        "回复：",
        "回复:",
        "历史显示",
        "上下文",
        "保持角色",
        "角色一致",
        "这句已经",
        "直接送出",
        "分析",
        "解释",
    )
    lowered = normalized.lower()
    if any(token in normalized for token in meta_tokens):
        return True
    if any(
        token in lowered for token in ("context", "suggestion", "reply:", "analysis")
    ):
        return True
    generic_observe_wrappers = (
        "要不先让他们",
        "不如让他们",
        "先让他们",
        "继续聊下去",
        "让他们把刚才那句接下去",
        "让场景自然推进",
    )
    if any(token in normalized for token in generic_observe_wrappers):
        return True
    return False


def parse_dialogue_suggestion(content: str) -> str:
    text = str(content or "").strip()
    if not text:
        raise ValueError("Model returned an empty suggestion.")
    try:
        parsed = _loads_llm_json(text)
    except ValueError:
        parsed = None
    if isinstance(parsed, dict):
        text = str(parsed.get("suggestion", "")).strip()
    elif isinstance(parsed, list) and parsed:
        first = parsed[0]
        if isinstance(first, dict):
            text = str(first.get("suggestion", "") or first.get("message", "")).strip()
        else:
            text = str(first).strip()
    else:
        text = text.strip().strip('"').strip("'")
    if "：" in text:
        prefix, rest = text.split("：", 1)
        if 0 < len(prefix.strip()) <= 16 and rest.strip():
            text = rest.strip()
    elif ":" in text:
        prefix, rest = text.split(":", 1)
        if 0 < len(prefix.strip()) <= 16 and rest.strip():
            text = rest.strip()
    text = " ".join(text.split()).strip()
    if not text:
        raise ValueError("Model reply did not contain a usable suggestion.")
    if _looks_like_meta_suggestion(text):
        raise ValueError(
            "Model reply looked like explanation instead of a direct sendable line."
        )
    return text


def parse_dialogue_associations(
    content: str,
    *,
    require_suggestions: bool = False,
) -> list[dict[str, str]]:
    text = str(content or "").strip()
    if not text:
        raise ValueError("Model returned empty dialogue associations.")
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].strip().startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(
            "Model reply did not contain valid dialogue association JSON."
        ) from exc
    if not isinstance(parsed, dict):
        raise ValueError("Dialogue association reply must be a JSON object.")
    raw_options = parsed.get("options", [])
    if not isinstance(raw_options, list):
        raise ValueError("Dialogue association options must be a list.")

    options: list[dict[str, str]] = []
    seen: set[str] = set()
    for item in raw_options:
        suggestion = ""
        if isinstance(item, str):
            label = " ".join(item.split()).strip()
            direction = label
        elif isinstance(item, dict):
            label = " ".join(str(item.get("label", "")).split()).strip()
            direction = " ".join(str(item.get("direction", "")).split()).strip()
            suggestion = " ".join(
                str(item.get("suggestion", "") or item.get("draft", "")).split()
            ).strip()
            anchor_speaker = " ".join(
                str(item.get("anchor_speaker", "")).split()
            ).strip()
            anchor_quote = " ".join(str(item.get("anchor_quote", "")).split()).strip()
        else:
            continue
        label = label.strip("，。！？；：,.!?;:、 ")[:24].strip()
        direction = direction[:240].strip()
        if not label or not direction:
            continue
        key = label.casefold()
        if key in seen:
            continue
        seen.add(key)
        option = {"label": label, "direction": direction}
        if suggestion:
            try:
                option["suggestion"] = parse_dialogue_suggestion(suggestion)[:600]
            except ValueError:
                if require_suggestions:
                    continue
        elif require_suggestions:
            continue
        if isinstance(item, dict) and anchor_speaker and anchor_quote:
            option["anchor_speaker"] = anchor_speaker[:24].strip()
            option["anchor_quote"] = anchor_quote[:80].strip()
        options.append(option)
        if len(options) >= 4:
            break
    if len(options) < 2:
        raise ValueError(
            "Model reply did not contain enough distinct dialogue associations."
        )
    return options


def parse_dialogue_director_options(
    content: str, *, expected_count: int = 3
) -> list[dict[str, str]]:
    parsed = _loads_llm_json(content)
    if not isinstance(parsed, dict):
        raise ValueError("Director reply must be a JSON object.")
    raw_options = parsed.get("options", [])
    if not isinstance(raw_options, list):
        raise ValueError("Director options must be a list.")
    required = max(2, min(int(expected_count or 3), 4))
    options: list[dict[str, str]] = []
    seen: set[str] = set()
    for raw in raw_options:
        if not isinstance(raw, dict):
            continue
        option = {
            key: _trim_text(str(raw.get(key, "")).strip(), limit)
            for key, limit in (
                ("title", 20),
                ("focus", 20),
                ("beat", 180),
                ("direction", 220),
                ("expected_effect", 140),
                ("risk", 120),
            )
        }
        identity = option["title"].casefold()
        if not option["title"] or not option["beat"] or not option["direction"]:
            continue
        if identity in seen:
            continue
        seen.add(identity)
        options.append(option)
        if len(options) >= required:
            break
    if len(options) < required:
        raise ValueError("Model returned too few valid director options.")
    return options


def generate_dialogue_responses(
    *,
    payload: dict[str, Any],
    allowed_speakers: list[str],
    temperature: float,
    max_tokens: int,
    chat_completion: Callable[[list[dict[str, Any]], float, int], dict[str, Any]],
    build_messages: Callable[[dict[str, Any], bool], list[dict[str, Any]]],
    parse_responses: Callable[[str, list[str]], list[dict[str, str]]],
    completion_observer: Callable[[dict[str, Any]], None] | None = None,
) -> list[dict[str, str]]:
    response_limit = int(
        dict(payload.get("host_action", {}) or {}).get(
            "response_limit_hint", 0
        )
        or 0
    )
    configured_max_tokens = min(
        max(0, int(max_tokens or 0)), DIALOGUE_RESPONSE_MAX_MAX_TOKENS
    )
    if configured_max_tokens > 0:
        initial_max_tokens = configured_max_tokens
        retry_max_tokens = configured_max_tokens
    elif response_limit > 0:
        estimated_max_tokens = max(640, 520 + response_limit * 360)
        initial_max_tokens = min(
            max(
                DIALOGUE_RESPONSE_MIN_MAX_TOKENS,
                estimated_max_tokens,
                configured_max_tokens,
            ),
            DIALOGUE_RESPONSE_MAX_MAX_TOKENS,
        )
        retry_max_tokens = min(
            initial_max_tokens * 2, DIALOGUE_RESPONSE_MAX_MAX_TOKENS
        )
    else:
        initial_max_tokens = DIALOGUE_RESPONSE_MIN_MAX_TOKENS
        retry_max_tokens = min(
            initial_max_tokens * 2, DIALOGUE_RESPONSE_MAX_MAX_TOKENS
        )
    attempts = (
        (build_messages(payload, False), initial_max_tokens),
        (
            build_messages(payload, True),
            retry_max_tokens,
        ),
    )
    last_error: Exception | None = None
    for index, (llm_messages, attempt_max_tokens) in enumerate(attempts):
        llm_result = chat_completion(llm_messages, temperature, attempt_max_tokens)
        if callable(completion_observer):
            completion_observer(dict(llm_result or {}))
        content = str(llm_result.get("content", "")).strip()
        if _llm_result_was_truncated(llm_result):
            # 截断的 JSON 只会解析失败，重试时才有更大的 max_tokens 可用。
            last_error = ValueError(
                "模型回复被输出长度限制截断，请调高 llm.max_tokens。"
            )
            if index + 1 < len(attempts):
                continue
            raise last_error
        if not content:
            last_error = ValueError("Model returned an empty reply.")
            if index + 1 < len(attempts):
                continue
            break
        try:
            responses = parse_responses(content, allowed_speakers)
            reordered = _reorder_plot_push_responses(responses, payload)
            reordered = _prioritize_mentioned_responses(reordered, payload)
            normalized = _normalize_dialogue_responses(
                reordered,
                response_limit=int(
                    dict(payload.get("host_action", {}) or {}).get(
                        "response_limit_hint", 0
                    )
                    or 0
                ),
            )
            if _is_plot_push(payload) and not _has_plot_scene_beat(normalized):
                last_error = ValueError(
                    "剧情推动没有生成有效场景事件，模型只返回了角色闲聊。"
                )
                if index + 1 < len(attempts):
                    continue
                raise last_error
            missing_mentions = _missing_mention_targets(normalized, payload)
            if missing_mentions:
                last_error = ValueError(
                    f"被 @ 的在场角色没有回应：{', '.join(missing_mentions)}。"
                )
                if index + 1 < len(attempts):
                    continue
                raise last_error
            return normalized
        except (ValueError, json.JSONDecodeError) as exc:
            last_error = ValueError(str(exc)) if isinstance(exc, json.JSONDecodeError) else exc
            if index + 1 < len(attempts):
                continue
            raise last_error
    raise ValueError("模型没有返回可用的角色回复。") from last_error


def generate_dialogue_suggestion(
    *,
    payload: dict[str, Any],
    temperature: float,
    max_tokens: int,
    chat_completion: Callable[[list[dict[str, str]], float, int], dict[str, Any]],
    build_messages: Callable[[dict[str, Any], bool], list[dict[str, str]]],
    parse_suggestion: Callable[[str], str],
) -> str:
    initial_max_tokens = min(512, max(256, int(max_tokens or 0)))
    initial_payload = payload
    initial_messages = build_messages(payload, False)
    prompt_chars = sum(
        len(str(message.get("content", "")))
        for message in initial_messages
        if isinstance(message, dict)
    )
    if prompt_chars > DIALOGUE_SUGGESTION_COMPACT_PROMPT_CHAR_THRESHOLD:
        initial_payload = compact_dialogue_suggestion_payload(payload)
        initial_messages = build_messages(initial_payload, False)
    attempts = (
        (initial_messages, initial_max_tokens),
        (build_messages(initial_payload, True), 1024),
    )
    last_error: Exception | None = None
    for index, (llm_messages, attempt_max_tokens) in enumerate(attempts):
        llm_result = chat_completion(llm_messages, temperature, attempt_max_tokens)
        content = str(llm_result.get("content", "")).strip()
        if _llm_result_was_truncated(llm_result):
            last_error = ValueError(
                "Model suggestion was truncated by the output token limit."
            )
            if index + 1 < len(attempts):
                continue
            break
        if not content:
            last_error = ValueError("Model returned an empty suggestion.")
            if index + 1 < len(attempts):
                continue
            break
        try:
            return parse_suggestion(content)
        except ValueError as exc:
            last_error = exc
            if index + 1 < len(attempts):
                continue
            raise
    raise ValueError("模型没有返回可用的续写建议。") from last_error


def _reorder_plot_push_responses(
    responses: list[dict[str, str]],
    payload: dict[str, Any],
) -> list[dict[str, str]]:
    """剧情推动 + 扮演时，控制角色不应成为最后一条角色对白。"""
    mode = str(payload.get("mode", "")).strip()
    input_block = dict(payload.get("input", {}) or {})
    message_kind = str(input_block.get("message_kind", "")).strip()
    controlled = str(input_block.get("controlled_character", "")).strip()
    if message_kind not in {"narration", "plot"}:
        return responses

    meta_speakers = {"旁白", "场景提示"}
    characters: list[dict[str, str]] = []
    meta: list[dict[str, str]] = []
    for item in responses:
        speaker = str(item.get("speaker", "")).strip()
        if speaker in meta_speakers:
            meta.append(item)
        else:
            characters.append(item)

    if mode != "act" or not controlled or len(characters) <= 1:
        return (meta[:1] + characters) if message_kind == "plot" else (characters + meta)

    last_idx = len(characters) - 1
    controlled_idx = next(
        (index for index, item in enumerate(characters) if str(item.get("speaker", "")).strip() == controlled),
        -1,
    )
    if controlled_idx != last_idx:
        return (meta[:1] + characters) if message_kind == "plot" else (characters + meta)

    controlled_item = characters.pop(last_idx)
    if len(characters) >= 2:
        characters.insert(len(characters) - 1, controlled_item)
    else:
        characters.insert(0, controlled_item)
    return (meta[:1] + characters) if message_kind == "plot" else (characters + meta)


def _is_plot_push(payload: dict[str, Any]) -> bool:
    input_block = dict(payload.get("input", {}) or {})
    return str(input_block.get("message_kind", "")).strip() == "plot"


def _has_plot_scene_beat(responses: list[dict[str, str]]) -> bool:
    if not responses:
        return False
    return str(responses[0].get("speaker", "")).strip() in {"旁白", "场景提示"}


def _prioritize_mentioned_responses(
    responses: list[dict[str, str]],
    payload: dict[str, Any],
) -> list[dict[str, str]]:
    input_block = dict(payload.get("input", {}) or {})
    targets = [
        str(name).strip()
        for name in list(input_block.get("mention_targets", []) or [])
        if str(name).strip()
    ]
    if not targets:
        return responses
    meta = [item for item in responses if str(item.get("speaker", "")).strip() in {"旁白", "场景提示"}]
    characters = [item for item in responses if str(item.get("speaker", "")).strip() not in {"旁白", "场景提示"}]
    targeted = [
        item
        for target in targets
        for item in characters
        if str(item.get("speaker", "")).strip() == target
    ]
    others = [
        item
        for item in characters
        if str(item.get("speaker", "")).strip() not in targets
    ]
    return [*meta, *targeted, *others] if _is_plot_push(payload) else [*targeted, *others, *meta]


def _missing_mention_targets(
    responses: list[dict[str, str]],
    payload: dict[str, Any],
) -> list[str]:
    input_block = dict(payload.get("input", {}) or {})
    targets = [
        str(name).strip()
        for name in list(input_block.get("mention_targets", []) or [])
        if str(name).strip()
    ]
    speakers = {
        str(item.get("speaker", "")).strip()
        for item in responses
        if str(item.get("speaker", "")).strip()
    }
    return [name for name in targets if name not in speakers]


def generate_dialogue_associations(
    *,
    payload: dict[str, Any],
    temperature: float,
    max_tokens: int,
    chat_completion: Callable[[list[dict[str, str]], float, int], dict[str, Any]],
    build_messages: Callable[[dict[str, Any], bool], list[dict[str, str]]],
    parse_associations: Callable[[str], list[dict[str, str]]],
) -> list[dict[str, str]]:
    initial_max_tokens = max(768, int(max_tokens or 0))
    attempts = (
        (build_messages(payload, False), initial_max_tokens),
        (build_messages(payload, True), max(initial_max_tokens * 2, 1536)),
    )
    last_error: Exception | None = None
    for index, (llm_messages, attempt_max_tokens) in enumerate(attempts):
        llm_result = chat_completion(llm_messages, temperature, attempt_max_tokens)
        content = str(llm_result.get("content", "")).strip()
        if _llm_result_was_truncated(llm_result):
            last_error = ValueError(
                "Dialogue associations were truncated by the output token limit."
            )
            if index + 1 < len(attempts):
                continue
            break
        if not content:
            last_error = ValueError("Model returned empty dialogue associations.")
            if index + 1 < len(attempts):
                continue
            break
        try:
            options = parse_associations(content)
            expected_count = max(
                2,
                min(
                    int(
                        dict(payload.get("instructions", {}) or {}).get(
                            "option_count", 3
                        )
                        or 3
                    ),
                    4,
                ),
            )
            if len(options) != expected_count:
                raise ValueError(
                    f"Model returned {len(options)} dialogue associations; expected {expected_count}."
                )
            _validate_dialogue_association_grounding(options, payload)
            return options
        except ValueError as exc:
            last_error = exc
            if index + 1 < len(attempts):
                continue
            raise
    raise ValueError("模型没有返回可用的剧情联想。") from last_error


def _validate_dialogue_association_grounding(
    options: list[dict[str, str]], payload: dict[str, Any]
) -> None:
    latest_exchange = dict(payload.get("latest_exchange", {}) or {})
    sources = [
        dict(item)
        for item in list(latest_exchange.get("replies", []) or [])
        if isinstance(item, dict) and str(item.get("message", "")).strip()
    ]
    if not sources:
        user_turn = dict(latest_exchange.get("user_turn", {}) or {})
        if str(user_turn.get("message", "")).strip():
            sources = [user_turn]
    if not sources:
        return

    def normalized(value: Any) -> str:
        return "".join(
            re.findall(r"[\w\u4e00-\u9fff]", str(value or ""), flags=re.UNICODE)
        )

    for option in options:
        anchor_speaker = str(option.get("anchor_speaker", "")).strip()
        anchor_quote = normalized(option.get("anchor_quote", ""))
        if not anchor_speaker or len(anchor_quote) < 4:
            raise ValueError(
                "Dialogue association is missing a usable latest-reply anchor."
            )
        speaker_sources = [
            normalized(item.get("message", ""))
            for item in sources
            if str(item.get("speaker", "")).strip() == anchor_speaker
        ]
        if not speaker_sources or not any(
            anchor_quote in source for source in speaker_sources
        ):
            raise ValueError(
                "Dialogue association anchor was not found in the latest replies."
            )

    recent_speakers = {
        str(item.get("speaker", "")).strip()
        for item in list(payload.get("history", []) or [])[-8:]
        if isinstance(item, dict) and str(item.get("speaker", "")).strip()
    }
    already_participating = recent_speakers & {
        str(item).strip()
        for item in list(latest_exchange.get("present_participants", []) or [])
        if str(item).strip()
    }
    stale_invitation_pattern = re.compile(
        r"(?:拉|邀请|邀|请|叫|招呼).{0,16}(?:入局|加入|助阵|入场|进场|开口|参与)"
    )
    for option in options:
        option_text = normalized(
            f"{option.get('label', '')}{option.get('direction', '')}"
        )
        if not stale_invitation_pattern.search(option_text):
            continue
        for character in already_participating:
            aliases = {character}
            if len(character) >= 3:
                aliases.add(character[-2:])
            if any(alias and alias in option_text for alias in aliases):
                raise ValueError(
                    "Dialogue association tries to re-invite a character who already participated."
                )


def _llm_result_was_truncated(result: dict[str, Any]) -> bool:
    raw = result.get("raw", {})
    raw_payload = raw if isinstance(raw, dict) else {}
    reasons = [
        result.get("finish_reason", ""),
        raw_payload.get("finish_reason", ""),
        raw_payload.get("stop_reason", ""),
        raw_payload.get("done_reason", ""),
    ]
    choices = raw_payload.get("choices", [])
    if isinstance(choices, list) and choices and isinstance(choices[0], dict):
        reasons.append(choices[0].get("finish_reason", ""))
    normalized = {str(reason or "").strip().lower() for reason in reasons}
    return bool(normalized & {"length", "max_tokens", "max_output_tokens"})
def _normalize_dialogue_responses(
    responses: list[dict[str, str]],
    *,
    response_limit: int,
) -> list[dict[str, str]]:
    # Keep only one line per character speaker per turn (except narration/meta speakers),
    # then cap to the turn hint so noisy outputs do not flood the transcript.
    cleaned: list[dict[str, str]] = []
    seen_character_speakers: set[str] = set()
    for item in responses:
        speaker = str(item.get("speaker", "")).strip()
        message = str(item.get("message", "")).strip()
        inner_thought = str(item.get("inner_thought", "")).strip()
        if not speaker or not message:
            continue
        if speaker not in {"旁白", "场景提示"}:
            if speaker in seen_character_speakers:
                continue
            seen_character_speakers.add(speaker)
        cleaned.append(
            {
                "speaker": speaker,
                "message": message,
                **({"inner_thought": inner_thought} if inner_thought else {}),
            }
        )

    if not cleaned:
        raise ValueError("Model reply did not contain usable character responses.")
    if response_limit > 0:
        return cleaned[:response_limit]
    return cleaned
