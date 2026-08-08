from __future__ import annotations

from typing import Any

from src.web.chat.text_utils import event_kind_label, trim_summary_text


def build_runtime_state_overview(
    *,
    scene_progress: dict[str, Any],
    session_summary: dict[str, Any],
    character_snapshots: dict[str, Any],
    relation_delta: dict[str, Any],
    event_signals: dict[str, Any],
) -> dict[str, Any]:
    present = [
        str(item).strip()
        for item in list(scene_progress.get("present_participants", []) or [])
        if str(item).strip()
    ]
    offstage = [
        str(item).strip()
        for item in list(scene_progress.get("offstage_participants", []) or [])
        if str(item).strip()
    ]
    location = str(scene_progress.get("location", "")).strip()
    time_hint = str(scene_progress.get("time_hint", "")).strip()
    atmosphere = trim_summary_text(str(scene_progress.get("atmosphere_summary", "")).strip(), 80)
    beat_maturity = max(0, min(100, int(scene_progress.get("beat_maturity", 0) or 0)))
    should_offer_scene_shift = bool(scene_progress.get("should_offer_scene_shift", False))
    shift_reason = trim_summary_text(str(scene_progress.get("scene_shift_reason", "")).strip(), 120)
    tension = trim_summary_text(str(scene_progress.get("world_tension_summary", "")).strip(), 120)
    current_location = trim_summary_text(str(session_summary.get("current_location", "")).strip(), 160)
    current_companions = trim_summary_text(str(session_summary.get("current_companions", "")).strip(), 160)
    pending_commitments = trim_summary_text(str(session_summary.get("pending_commitments", "")).strip(), 180)

    pills = _build_pills(
        location=location,
        time_hint=time_hint,
        atmosphere=atmosphere,
        beat_maturity=beat_maturity,
        should_offer_scene_shift=should_offer_scene_shift,
        shift_reason=shift_reason,
    )
    character_rows = _build_character_rows(character_snapshots)
    relation_rows = _build_relation_rows(relation_delta)
    event_rows = _build_event_rows(event_signals)
    status_line = _build_status_line(
        pills=pills,
        present=present,
        offstage=offstage,
        tension=tension,
    )
    next_hint = _build_next_hint(
        should_offer_scene_shift=should_offer_scene_shift,
        shift_reason=shift_reason,
        tension=tension,
        event_rows=event_rows,
    )

    return {
        "present": present,
        "offstage": offstage,
        "location": location,
        "time_hint": time_hint,
        "atmosphere": atmosphere,
        "beat_maturity": beat_maturity,
        "should_offer_scene_shift": should_offer_scene_shift,
        "scene_shift_reason": shift_reason,
        "tension": tension,
        "current_location": current_location,
        "current_companions": current_companions,
        "pending_commitments": pending_commitments,
        "pills": pills,
        "character_rows": character_rows,
        "relation_rows": relation_rows,
        "event_rows": event_rows,
        "status_line": status_line,
        "next_hint": next_hint,
    }


def _build_pills(
    *,
    location: str,
    time_hint: str,
    atmosphere: str,
    beat_maturity: int,
    should_offer_scene_shift: bool,
    shift_reason: str,
) -> list[dict[str, Any]]:
    pills: list[dict[str, Any]] = []
    if location:
        pills.append({"text": f"地点 · {location}"})
    if time_hint:
        pills.append({"text": f"时间 · {time_hint}"})
    if atmosphere:
        pills.append({"text": f"氛围 · {atmosphere}"})
    if beat_maturity > 0:
        pills.append({"text": f"推进 {beat_maturity}/100"})
    if should_offer_scene_shift:
        pills.append({"text": f"可转场 · {shift_reason or '这一拍已经可以顺势转场'}"})
    return pills


def _build_character_rows(character_snapshots: dict[str, Any]) -> list[dict[str, str]]:
    rows: list[dict[str, Any]] = []
    for name, snapshot in character_snapshots.items():
        normalized_name = str(name).strip()
        if not normalized_name:
            continue
        current = dict(snapshot or {})
        parts: list[str] = []
        present_state = str(current.get("present_state", "")).strip()
        if present_state == "onstage":
            parts.append("在场")
        elif present_state == "offstage":
            parts.append("离场")
        for key in ("mood", "interaction_state"):
            value = str(current.get(key, "")).strip()
            if value:
                parts.append(value)
        focus = str(current.get("focus", "")).strip()
        if focus:
            parts.append(f"看向 {focus}")
        rows.append(
            {
                "title": normalized_name,
                "copy": trim_summary_text(" · ".join(parts) or "这一拍还没有额外漂移。", 120),
                "rank": 0 if present_state == "onstage" else 1,
            }
        )
    rows.sort(key=lambda item: (int(item.get("rank", 9) or 9), str(item.get("title", ""))))
    return [{"title": item["title"], "copy": item["copy"]} for item in rows[:4]]


def _build_relation_rows(relation_delta: dict[str, Any]) -> list[dict[str, str]]:
    rows: list[dict[str, Any]] = []
    for pair_key, delta in relation_delta.items():
        normalized_key = str(pair_key).strip()
        if not normalized_key:
            continue
        payload = dict(delta or {})
        metrics: list[str] = []
        momentum = int(payload.get("momentum", 0) or 0)
        for field, label in (("trust", "信任"), ("affection", "好感"), ("hostility", "敌意"), ("ambiguity", "摇摆")):
            amount = int(payload.get(field, 0) or 0)
            if amount:
                metrics.append(f"{label}{amount:+d}")
        last_event = trim_summary_text(str(payload.get("last_event", "")).strip(), 72)
        rows.append(
            {
                "title": normalized_key.replace("_", " · "),
                "copy": trim_summary_text(
                    f"{' / '.join(metrics)}{' · ' if metrics and last_event else ''}{last_event}".strip()
                    or "这组关系本局有变化。",
                    120,
                ),
                "rank": max(momentum, len(metrics)),
            }
        )
    rows.sort(key=lambda item: (-int(item.get("rank", 0) or 0), str(item.get("title", ""))))
    return [{"title": item["title"], "copy": item["copy"]} for item in rows[:3]]


def _build_event_rows(event_signals: dict[str, Any]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for event in list(dict(event_signals or {}).get("recent", []) or [])[-4:]:
        payload = dict(event or {})
        kind = str(payload.get("kind", "")).strip()
        cue = trim_summary_text(str(payload.get("cue", "")).strip(), 88)
        if not kind or not cue:
            continue
        actor = str(payload.get("actor", "")).strip()
        target = str(payload.get("target", "")).strip()
        scope = str(payload.get("scope", "")).strip()
        title_bits = [event_kind_label(kind)]
        if actor:
            title_bits.append(actor)
        if target:
            title_bits.append(target)
        rows.append(
            {
                "title": " · ".join(title_bits) if title_bits else (scope or "event"),
                "copy": cue,
            }
        )
    return rows


def _build_status_line(
    *,
    pills: list[dict[str, Any]],
    present: list[str],
    offstage: list[str],
    tension: str,
) -> str:
    status_bits: list[str] = []
    pill_texts = [str(item.get("text", "")).strip() for item in pills if str(item.get("text", "")).strip()]
    if pill_texts:
        status_bits.append(" · ".join(pill_texts[:3]))
    if present:
        status_bits.append(f"在场：{'、'.join(present[:3])}")
    if offstage:
        status_bits.append(f"离场：{'、'.join(offstage[:2])}")
    if tension:
        status_bits.append(f"张力：{trim_summary_text(tension, 56)}")
    return " ｜ ".join(status_bits)


def _build_next_hint(
    *,
    should_offer_scene_shift: bool,
    shift_reason: str,
    tension: str,
    event_rows: list[dict[str, str]],
) -> str:
    if should_offer_scene_shift:
        return shift_reason or "这一拍已经可以顺势转场。"
    if tension:
        return trim_summary_text(tension, 72)
    if event_rows:
        return trim_summary_text(str(event_rows[-1].get("copy", "")).strip(), 72)
    return ""


__all__ = ["build_runtime_state_overview"]
