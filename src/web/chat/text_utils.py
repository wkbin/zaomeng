from __future__ import annotations

from typing import Any


def trim_summary_text(value: str, limit: int) -> str:
    text = " ".join(str(value or "").split()).strip()
    if not text:
        return ""
    if len(text) <= limit:
        return text
    return f"{text[:limit]}..."


def build_last_entry_preview(session: dict[str, Any]) -> str:
    history = list(session.get("history", []) or [])
    for entry in reversed(history):
        message = str(entry.get("message", "")).strip()
        if not message:
            continue
        normalized = " ".join(message.split())
        return normalized[:180]
    pending = dict(session.get("pending_turn", {}) or {})
    pending_message = str(pending.get("transcript_message", "")).strip()
    if pending_message:
        return " ".join(pending_message.split())[:180]
    return ""


def build_scene_switch_note(scene_card: dict[str, Any], transition_message: str) -> str:
    transition = str(transition_message or "").strip()
    if transition:
        return transition
    if not scene_card:
        return ""
    title = str(scene_card.get("title", "")).strip()
    location = str(scene_card.get("location", "")).strip()
    atmosphere = str(scene_card.get("atmosphere", "")).strip()
    opening = str(scene_card.get("opening_situation", "")).strip()
    scene_bits = [bit for bit in (title, location, atmosphere) if bit]
    prefix = f"场景转到：{' / '.join(scene_bits)}。" if scene_bits else "场景发生了变化。"
    if opening:
        return f"{prefix}{opening}"
    return prefix


def entry_to_memory_text(entry: dict[str, Any]) -> str:
    speaker = str(entry.get("speaker", "")).strip()
    message = " ".join(str(entry.get("message", "")).split()).strip()
    target = str(entry.get("target", "")).strip()
    if not message:
        return ""
    if speaker and target:
        return f"{speaker} -> {target}: {message}"
    if speaker:
        return f"{speaker}: {message}"
    return message


def mode_display(mode: str) -> str:
    mapping = {
        "act": "act · 代入角色",
        "insert": "insert · 你进入场景",
        "observe": "observe · 旁观群聊",
    }
    return mapping.get(mode, mode)


def event_kind_label(kind: str) -> str:
    mapping = {
        "scene_transition": "转场",
        "cast_enter": "入场",
        "cast_exit": "离场",
        "atmosphere_shift": "气氛变化",
        "time_change": "时间推进",
        "environment_change": "环境变化",
        "beat_complete": "一拍收束",
        "relationship_shift": "关系变化",
        "micro_action": "细微动作",
    }
    normalized = str(kind or "").strip()
    return mapping.get(normalized, normalized or "事件")


__all__ = [
    "build_last_entry_preview",
    "build_scene_switch_note",
    "entry_to_memory_text",
    "event_kind_label",
    "mode_display",
    "trim_summary_text",
]
