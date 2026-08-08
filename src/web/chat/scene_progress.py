from __future__ import annotations

from typing import Any

import src.web.chat.event_signals as _event_signals
import src.web.chat.scene_signals as _scene_signals
import src.web.chat.state_utils as _state_utils
from src.web.chat.text_utils import trim_summary_text
from src.web.time_utils import utc_now


def merge_scene_progress_state(
    session: dict[str, Any],
    incoming: dict[str, Any],
    *,
    transcript: list[dict[str, Any]],
    state_version: int,
) -> dict[str, Any]:
    base = derive_scene_progress_state(
        session,
        transcript,
        state_version=state_version,
    )
    participants = _participants(session)
    allowed = set(participants)
    history = list(session.get("history", []) or [])

    def clean_names(values: Any) -> list[str]:
        names: list[str] = []
        for item in list(values or []):
            name = str(item or "").strip()
            if not name or name not in allowed or name in names:
                continue
            names.append(name)
        return names

    present = clean_names(incoming.get("present_participants", [])) or list(base.get("present_participants", []) or [])
    offstage = [name for name in clean_names(incoming.get("offstage_participants", [])) if name not in present]
    present, offstage = _stabilize_presence_transition(
        session,
        participants=participants,
        history=history,
        present=present,
        offstage=offstage,
        base=base,
        state_version=state_version,
    )
    merged = {
        "present_participants": present,
        "offstage_participants": offstage
        or [name for name in list(base.get("offstage_participants", []) or []) if name not in present],
        "time_hint": _scene_signals.merge_time_hint(
            incoming=str(incoming.get("time_hint", "")).strip(),
            base=str(base.get("time_hint", "")).strip(),
            history=history,
            scene_hint=str(dict(session.get("scene_card", {}) or {}).get("time_hint", "")).strip(),
            allow_history_drift=False,
        ),
        "location": str(incoming.get("location", "")).strip() or str(base.get("location", "")).strip(),
        "atmosphere_summary": str(incoming.get("atmosphere_summary", "")).strip()
        or str(base.get("atmosphere_summary", "")).strip(),
        "progression_note": str(incoming.get("progression_note", "")).strip()
        or str(base.get("progression_note", "")).strip(),
        "should_offer_scene_shift": bool(
            incoming.get("should_offer_scene_shift", base.get("should_offer_scene_shift", False))
        ),
        "scene_shift_reason": str(incoming.get("scene_shift_reason", "")).strip()
        or str(base.get("scene_shift_reason", "")).strip(),
        "turns_in_current_scene": int(base.get("turns_in_current_scene", 0) or 0),
        "beat_maturity": int(incoming.get("beat_maturity", base.get("beat_maturity", 0)) or 0),
        "world_tension_summary": str(incoming.get("world_tension_summary", "")).strip()
        or str(base.get("world_tension_summary", "")).strip(),
        "updated_at": utc_now(),
    }
    if merged["should_offer_scene_shift"]:
        merged["beat_maturity"] = max(75, int(merged.get("beat_maturity", 0) or 0))
    return merged


def derive_scene_progress_state(
    session: dict[str, Any],
    transcript: list[dict[str, Any]],
    *,
    state_version: int,
) -> dict[str, Any]:
    participants = _participants(session)
    scene_card = dict(session.get("scene_card", {}) or {})
    prior = _session_scene_progress(session, state_version=state_version)
    history = list(session.get("history", []) or [])
    presence_state = _derive_presence_state(
        session,
        participants=participants,
        history=history,
        state_version=state_version,
    )
    scene_frame = _derive_scene_frame_state(
        session,
        transcript=transcript,
        scene_card=scene_card,
        prior=prior,
        state_version=state_version,
    )
    progression_state = _derive_progression_state(
        session,
        transcript=transcript,
        scene_card=scene_card,
        prior=prior,
        presence_state=presence_state,
        scene_frame=scene_frame,
        state_version=state_version,
    )
    progression_bits = []
    if scene_frame.get("location"):
        progression_bits.append(f"地点：{scene_frame['location']}")
    if scene_frame.get("time_hint"):
        progression_bits.append(f"时间：{scene_frame['time_hint']}")
    if scene_frame.get("atmosphere_summary"):
        progression_bits.append(f"氛围：{scene_frame['atmosphere_summary']}")
    if presence_state.get("present_participants"):
        progression_bits.append(f"在场：{'、'.join(list(presence_state.get('present_participants', []))[:4])}")
    if presence_state.get("offstage_participants"):
        progression_bits.append(f"离场：{'、'.join(list(presence_state.get('offstage_participants', []))[:3])}")
    progression_bits.append(f"成熟度：{int(progression_state.get('beat_maturity', 0) or 0)}")
    return {
        **presence_state,
        **scene_frame,
        **progression_state,
        "progression_note": "；".join(bit for bit in progression_bits if bit),
        "updated_at": utc_now(),
    }


def _participants(session: dict[str, Any]) -> list[str]:
    return [
        str(item).strip()
        for item in list(session.get("participants", []) or [])
        if str(item).strip()
    ]


def _session_state(session: dict[str, Any], *, state_version: int) -> dict[str, Any]:
    return _state_utils.ensure_session_state(session, version=state_version)


def _session_scene_progress(session: dict[str, Any], *, state_version: int) -> dict[str, Any]:
    return _state_utils.session_scene_progress(_session_state(session, state_version=state_version))


def _session_event_signals(session: dict[str, Any], *, state_version: int) -> dict[str, Any]:
    return _state_utils.event_signals(_session_state(session, state_version=state_version))


def _session_relation_delta(session: dict[str, Any], *, state_version: int) -> dict[str, Any]:
    return _state_utils.relation_delta(_session_state(session, state_version=state_version))


def _latest_event_signal(
    session: dict[str, Any],
    *kinds: str,
    state_version: int,
) -> dict[str, Any]:
    return _event_signals.latest_event_signal(
        _session_event_signals(session, state_version=state_version),
        *kinds,
    )


def _derive_presence_state(
    session: dict[str, Any],
    *,
    participants: list[str],
    history: list[dict[str, Any]],
    state_version: int,
) -> dict[str, Any]:
    departed = _scene_signals.infer_departed_participants(participants, history)
    latest_exit = _latest_event_signal(session, "cast_exit", state_version=state_version)
    latest_enter = _latest_event_signal(session, "cast_enter", state_version=state_version)
    if latest_exit:
        actor = str(latest_exit.get("actor", "")).strip()
        if actor in participants:
            departed.add(actor)
    if latest_enter:
        actor = str(latest_enter.get("actor", "")).strip()
        if actor in participants:
            departed.discard(actor)
    present = [name for name in participants if name not in departed]
    if not present and participants:
        present = participants[:1]
    return {
        "present_participants": present,
        "offstage_participants": [name for name in participants if name not in present],
    }


def _stabilize_presence_transition(
    session: dict[str, Any],
    *,
    participants: list[str],
    history: list[dict[str, Any]],
    present: list[str],
    offstage: list[str],
    base: dict[str, Any],
    state_version: int,
) -> tuple[list[str], list[str]]:
    prior_offstage = [
        str(item).strip()
        for item in list(base.get("offstage_participants", []) or [])
        if str(item).strip()
    ]
    explicit_returns = _scene_signals.infer_returned_participants(participants, history)
    explicit_exits = _scene_signals.infer_departed_participants(participants, history)
    recent_events = _session_event_signals(session, state_version=state_version).get("recent", [])
    for event in list(recent_events or [])[-12:]:
        payload = dict(event or {})
        actor = str(payload.get("actor", "")).strip()
        kind = str(payload.get("kind", "")).strip()
        if actor not in participants:
            continue
        if kind == "cast_enter":
            explicit_returns.add(actor)
            explicit_exits.discard(actor)
        elif kind == "cast_exit":
            explicit_exits.add(actor)
            explicit_returns.discard(actor)

    stabilized_offstage = {name for name in offstage if name in participants}
    stabilized_present = [name for name in present if name in participants]
    for name in prior_offstage:
        if name in explicit_returns:
            continue
        stabilized_offstage.add(name)
        stabilized_present = [item for item in stabilized_present if item != name]
    for name in explicit_exits:
        stabilized_offstage.add(name)
        stabilized_present = [item for item in stabilized_present if item != name]

    ordered_present: list[str] = []
    for name in participants:
        if name in stabilized_present and name not in stabilized_offstage and name not in ordered_present:
            ordered_present.append(name)
    if not ordered_present:
        ordered_present = [name for name in participants if name not in stabilized_offstage][:1] or participants[:1]
    ordered_offstage = [name for name in participants if name in stabilized_offstage and name not in ordered_present]
    return ordered_present, ordered_offstage


def _derive_scene_frame_state(
    session: dict[str, Any],
    *,
    transcript: list[dict[str, Any]],
    scene_card: dict[str, Any],
    prior: dict[str, Any],
    state_version: int,
) -> dict[str, Any]:
    latest_time_event = _latest_event_signal(session, "time_change", state_version=state_version)
    latest_scene_event = _latest_event_signal(session, "scene_transition", state_version=state_version)
    time_hint = _scene_signals.merge_time_hint(
        incoming=str(latest_time_event.get("time_hint", "")).strip() or _scene_signals.infer_time_hint(transcript),
        base=str(prior.get("time_hint", "")).strip(),
        history=list(session.get("history", []) or []),
        scene_hint=str(scene_card.get("time_hint", "")).strip(),
        history_since=str(prior.get("updated_at", "")).strip(),
    )
    location = (
        str(latest_scene_event.get("location_hint", "")).strip()
        or str(prior.get("location", "")).strip()
        or str(scene_card.get("location", "")).strip()
    )
    latest_atmosphere_event = _latest_event_signal(session, "atmosphere_shift", state_version=state_version)
    atmosphere_summary = (
        trim_summary_text(str(latest_atmosphere_event.get("cue", "")).strip(), 80)
        or _infer_atmosphere_summary(transcript)
        or trim_summary_text(str(prior.get("atmosphere_summary", "")).strip(), 80)
        or trim_summary_text(str(scene_card.get("atmosphere", "")).strip(), 80)
    )
    return {
        "time_hint": time_hint,
        "location": location,
        "atmosphere_summary": atmosphere_summary,
    }


def _derive_progression_state(
    session: dict[str, Any],
    *,
    transcript: list[dict[str, Any]],
    scene_card: dict[str, Any],
    prior: dict[str, Any],
    presence_state: dict[str, Any],
    scene_frame: dict[str, Any],
    state_version: int,
) -> dict[str, Any]:
    latest_beat_event = _latest_event_signal(session, "beat_complete", state_version=state_version)
    turns_in_current_scene = _count_current_scene_turns(session)
    beat_maturity = _estimate_scene_maturity(
        turns_in_current_scene=turns_in_current_scene,
        transcript=transcript,
        scene_card=scene_card,
        presence_state=presence_state,
        scene_frame=scene_frame,
        latest_beat_event=latest_beat_event,
        prior=prior,
    )
    scene_shift_reason = ""
    should_offer_scene_shift = False
    if scene_card and beat_maturity >= 72:
        should_offer_scene_shift = True
        scene_shift_reason = "这一幕已经接了好几拍，可以顺势换到下一幕。"
    if latest_beat_event:
        should_offer_scene_shift = True
        scene_shift_reason = str(latest_beat_event.get("cue", "")).strip() or scene_shift_reason
    initial_time = str(scene_card.get("time_hint", "")).strip()
    time_hint = str(scene_frame.get("time_hint", "")).strip()
    if time_hint and initial_time and time_hint != initial_time and beat_maturity >= 55:
        should_offer_scene_shift = True
        scene_shift_reason = scene_shift_reason or f"时间已经自然推到{time_hint}，适合顺势转下一拍。"
    event_pressure_reason = _derive_transition_pressure_reason(
        session,
        presence_state=presence_state,
        scene_frame=scene_frame,
        scene_card=scene_card,
        prior=prior,
        state_version=state_version,
    )
    if event_pressure_reason and beat_maturity >= 42:
        should_offer_scene_shift = True
        scene_shift_reason = scene_shift_reason or event_pressure_reason
    return {
        "should_offer_scene_shift": should_offer_scene_shift,
        "scene_shift_reason": scene_shift_reason,
        "turns_in_current_scene": turns_in_current_scene,
        "beat_maturity": beat_maturity,
        "world_tension_summary": _derive_world_tension_summary(
            session,
            transcript=transcript,
            scene_frame=scene_frame,
            state_version=state_version,
        ),
    }


def _derive_transition_pressure_reason(
    session: dict[str, Any],
    *,
    presence_state: dict[str, Any],
    scene_frame: dict[str, Any],
    scene_card: dict[str, Any],
    prior: dict[str, Any],
    state_version: int,
) -> str:
    present = [
        str(item).strip()
        for item in list(presence_state.get("present_participants", []) or [])
        if str(item).strip()
    ]
    offstage = [
        str(item).strip()
        for item in list(presence_state.get("offstage_participants", []) or [])
        if str(item).strip()
    ]
    latest_exit = _latest_event_signal(session, "cast_exit", state_version=state_version)
    actor = str(latest_exit.get("actor", "")).strip()
    if actor and actor in offstage:
        if len(present) <= 1 and present:
            return f"{actor}已经离场，场上只剩{present[0]}，适合顺势切到下一幕。"
        return f"{actor}已经离场，在场关系重新收束，适合顺势转下一拍。"

    latest_scene_event = _latest_event_signal(session, "scene_transition", state_version=state_version)
    location = str(scene_frame.get("location", "")).strip()
    if latest_scene_event and location:
        prior_location = str(prior.get("location", "")).strip()
        scene_location = str(scene_card.get("location", "")).strip()
        if location != prior_location and location != scene_location:
            return f"地点已经转到{location}，适合顺势接下一幕。"
    return ""


def _estimate_scene_maturity(
    *,
    turns_in_current_scene: int,
    transcript: list[dict[str, Any]],
    scene_card: dict[str, Any],
    presence_state: dict[str, Any],
    scene_frame: dict[str, Any],
    latest_beat_event: dict[str, Any],
    prior: dict[str, Any],
) -> int:
    score = min(60, max(0, turns_in_current_scene * 10))
    if latest_beat_event:
        score += 25
    time_hint = str(scene_frame.get("time_hint", "")).strip()
    if time_hint and time_hint != str(scene_card.get("time_hint", "")).strip():
        score += 10
    location = str(scene_frame.get("location", "")).strip()
    if location and location != str(scene_card.get("location", "")).strip():
        score += 10
    if list(presence_state.get("offstage_participants", []) or []):
        score += 6
    if str(scene_frame.get("atmosphere_summary", "")).strip():
        score += 4
    previous_maturity = int(prior.get("beat_maturity", 0) or 0)
    if previous_maturity:
        score = max(score, min(100, previous_maturity - 8))
    if len(transcript) >= 6:
        score += 6
    return max(0, min(100, score))


def _infer_atmosphere_summary(transcript: list[dict[str, Any]]) -> str:
    recent_messages = [
        str(item.get("message", "")).strip()
        for item in list(transcript or [])[-8:]
        if str(item.get("message", "")).strip()
    ]
    if not recent_messages:
        return ""
    joined = " ".join(recent_messages)
    for token in _scene_signals.ATMOSPHERE_TOKENS:
        if token in joined:
            return trim_summary_text(token, 40)
    for message in reversed(recent_messages):
        trimmed = trim_summary_text(message, 40)
        if trimmed:
            return trimmed
    return ""


def _derive_world_tension_summary(
    session: dict[str, Any],
    *,
    transcript: list[dict[str, Any]],
    scene_frame: dict[str, Any],
    state_version: int,
) -> str:
    latest_atmosphere_event = _latest_event_signal(session, "atmosphere_shift", state_version=state_version)
    latest_relation_event = _latest_event_signal(session, "relationship_shift", state_version=state_version)
    latest_scene_event = _latest_event_signal(
        session,
        "scene_transition",
        "environment_change",
        "time_change",
        state_version=state_version,
    )
    for candidate in (latest_atmosphere_event, latest_relation_event, latest_scene_event):
        cue = trim_summary_text(str((candidate or {}).get("cue", "")).strip(), 88)
        if cue:
            return cue
    relation_delta = _session_relation_delta(session, state_version=state_version)
    if relation_delta:
        pair_key, delta = next(iter(relation_delta.items()))
        metrics: list[str] = []
        for field, label in (("trust", "信任"), ("affection", "好感"), ("hostility", "敌意"), ("ambiguity", "摇摆")):
            amount = int(dict(delta or {}).get(field, 0) or 0)
            if amount:
                metrics.append(f"{label}{amount:+d}")
        if metrics:
            return trim_summary_text(f"{pair_key} 当前仍在变化：{'、'.join(metrics)}", 88)
    atmosphere = str(scene_frame.get("atmosphere_summary", "")).strip()
    if atmosphere:
        return trim_summary_text(f"这一拍的气氛是：{atmosphere}", 88)
    for item in reversed(list(transcript or [])[-8:]):
        role = str(item.get("role", "")).strip()
        message = trim_summary_text(str(item.get("message", "")).strip(), 88)
        if role in {"scene", "director"} and message:
            return message
    return ""


def _count_current_scene_turns(session: dict[str, Any]) -> int:
    history = list(session.get("history", []) or [])
    scene_history = list(session.get("scene_history", []) or [])
    if not history:
        return 0
    latest_scene_ts = str((scene_history[-1] or {}).get("ts", "")).strip() if scene_history else ""
    if latest_scene_ts:
        return sum(
            1
            for item in history
            if str(item.get("ts", "")).strip() >= latest_scene_ts
            and str(item.get("message", "")).strip()
        )
    return len([item for item in history[-12:] if str(item.get("message", "")).strip()])


__all__ = ["derive_scene_progress_state", "merge_scene_progress_state"]
