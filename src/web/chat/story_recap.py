from __future__ import annotations

from typing import Any

from src.web.chat.text_utils import trim_summary_text


_RELATION_METRICS = (
    ("trust", "信任"),
    ("affection", "好感"),
    ("hostility", "敌意"),
    ("ambiguity", "摇摆"),
)


def build_story_recap(
    *,
    session: dict[str, Any],
    transcript: list[dict[str, Any]],
    chapter_outline: dict[str, Any],
    event_timeline: list[dict[str, Any]],
    relation_timeline: list[dict[str, Any]],
    character_arcs: list[dict[str, Any]],
    runtime_state_overview: dict[str, Any],
    session_memory_summary: dict[str, Any],
) -> dict[str, Any]:
    """Assemble a zero-LLM story card from state already derived for a session."""

    outline = dict(chapter_outline or {})
    chapters = [
        dict(item or {})
        for item in list(outline.get("chapters", []) or [])
        if isinstance(item, dict)
    ]
    events = [
        dict(item or {})
        for item in list(event_timeline or [])
        if isinstance(item, dict)
    ]
    relations = [
        dict(item or {})
        for item in list(relation_timeline or [])
        if isinstance(item, dict)
    ]
    arcs = [
        dict(item or {})
        for item in list(character_arcs or [])
        if isinstance(item, dict)
    ]
    runtime = dict(runtime_state_overview or {})
    memory_summary = dict(session_memory_summary or {})
    latest_chapter = chapters[-1] if chapters else {}
    title = trim_summary_text(
        str(
            latest_chapter.get("title")
            or session.get("session_card", {})
            .get("scene_title", "")
            or runtime.get("location", "")
            or "未命名章节"
        ).strip(),
        48,
    )
    summary = trim_summary_text(
        str(
            latest_chapter.get("summary")
            or memory_summary.get("recap", "")
            or "这一局刚开始，剧情复盘会随对话自然累积。"
        ).strip(),
        240,
    )

    recap_events = _build_event_rows(events)
    recap_relations = _build_relation_rows(relations)
    recap_arcs = _build_arc_rows(arcs)
    hooks = _build_hooks(chapters)
    quotes = _build_quotes(transcript)
    next_hint = trim_summary_text(str(runtime.get("next_hint", "")).strip(), 120)

    share_text = _build_share_text(
        title=title,
        summary=summary,
        events=recap_events,
        relations=recap_relations,
        arcs=recap_arcs,
        hooks=hooks,
        next_hint=next_hint,
        quotes=quotes,
    )

    return {
        "title": title,
        "summary": summary,
        "location": trim_summary_text(str(runtime.get("location", "")).strip(), 80),
        "time_hint": trim_summary_text(str(runtime.get("time_hint", "")).strip(), 48),
        "atmosphere": trim_summary_text(str(runtime.get("atmosphere", "")).strip(), 80),
        "participants": [
            str(item).strip()
            for item in list(session.get("participants", []) or [])
            if str(item).strip()
        ],
        "event_count": len(events),
        "chapter_count": int(outline.get("chapter_count", len(chapters)) or 0),
        "unresolved_hook_count": int(
            outline.get("unresolved_hook_count", len(hooks)) or 0
        ),
        "events": recap_events,
        "relations": recap_relations,
        "character_arcs": recap_arcs,
        "hooks": hooks,
        "quotes": quotes,
        "next_hint": next_hint,
        "share_text": share_text,
    }


def _build_event_rows(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for event in reversed(events):
        if len(rows) >= 6:
            break
        responses = [
            {
                "speaker": str(item.get("speaker", "")).strip(),
                "message": trim_summary_text(
                    str(item.get("message", "")).strip(), 120
                ),
            }
            for item in list(event.get("responses", []) or [])
            if isinstance(item, dict)
            and str(item.get("message", "")).strip()
        ][:3]
        rows.append(
            {
                "title": trim_summary_text(
                    str(event.get("title", "") or "剧情推进").strip(), 64
                ),
                "turn_id": str(event.get("turn_id", "")).strip(),
                "time_hint": trim_summary_text(
                    str(event.get("time_hint", "")).strip(), 48
                ),
                "location": trim_summary_text(
                    str(event.get("location", "")).strip(), 48
                ),
                "participants": [
                    str(item).strip()
                    for item in list(event.get("participants", []) or [])
                    if str(item).strip()
                ],
                "event_types": [
                    str(item).strip()
                    for item in list(event.get("event_types", []) or [])
                    if str(item).strip()
                ],
                "responses": responses,
                "updated_at": str(event.get("updated_at", "")).strip(),
            }
        )
    return rows


def _build_relation_rows(
    timelines: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for timeline in timelines:
        pair_key = str(timeline.get("pair_key", "")).strip()
        if not pair_key:
            continue
        points = [
            dict(item or {})
            for item in list(timeline.get("points", []) or [])
            if isinstance(item, dict)
        ]
        change_point = next(
            (
                point
                for point in reversed(points)
                if any(
                    int(dict(point.get("changes", {}) or {}).get(metric, 0) or 0)
                    for metric, _ in _RELATION_METRICS
                )
            ),
            None,
        )
        if change_point is None:
            continue
        changes = dict(change_point.get("changes", {}) or {})
        rows.append(
            {
                "pair_key": pair_key,
                "label": trim_summary_text(
                    str(
                        timeline.get("label")
                        or pair_key.replace("_", " · ")
                        or pair_key
                    ).strip(),
                    64,
                ),
                "characters": [
                    str(item).strip()
                    for item in list(timeline.get("characters", []) or [])
                    if str(item).strip()
                ],
                "current": {
                    metric: int(
                        dict(timeline.get("current", {}) or {}).get(metric, 0) or 0
                    )
                    for metric, _ in _RELATION_METRICS
                },
                "changes": [
                    {
                        "metric": metric,
                        "label": label,
                        "delta": int(changes.get(metric, 0) or 0),
                    }
                    for metric, label in _RELATION_METRICS
                    if int(changes.get(metric, 0) or 0) != 0
                ],
                "reason": trim_summary_text(
                    str(change_point.get("reason", "")).strip()
                    or "本轮互动推动了关系变化",
                    120,
                ),
                "evidence": trim_summary_text(
                    str(change_point.get("evidence", "")).strip(), 120
                ),
                "turn_id": str(change_point.get("turn_id", "")).strip(),
            }
        )
    rows.sort(
        key=lambda item: -max(
            abs(int(change.get("delta", 0) or 0))
            for change in list(item.get("changes", []) or [])
        )
    )
    return rows[:6]


def _build_arc_rows(arcs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for arc in arcs:
        name = str(arc.get("name", "")).strip()
        if not name:
            continue
        current = dict(arc.get("current", {}) or {})
        rows.append(
            {
                "name": name,
                "growth_summary": trim_summary_text(
                    str(arc.get("growth_summary", "")).strip()
                    or "尚未记录到明显的状态变化。",
                    120,
                ),
                "current": {
                    key: trim_summary_text(str(value).strip(), 40)
                    for key, value in (
                        ("mood", current.get("mood", "")),
                        ("interaction_state", current.get("interaction_state", "")),
                        ("focus", current.get("focus", "")),
                        ("last_target", current.get("last_target", "")),
                    )
                    if str(value).strip()
                },
            }
        )
    return rows[:6]


def _build_hooks(chapters: list[dict[str, Any]]) -> list[str]:
    hooks: list[str] = []
    for chapter in reversed(chapters):
        for hook in reversed(list(chapter.get("hooks", []) or [])):
            normalized = trim_summary_text(str(hook).strip(), 96)
            if normalized and normalized not in hooks:
                hooks.append(normalized)
            if len(hooks) >= 4:
                return list(reversed(hooks))
    return list(reversed(hooks))


def _build_quotes(transcript: list[dict[str, Any]]) -> list[dict[str, str]]:
    quotes: list[dict[str, str]] = []
    for item in reversed(list(transcript or [])):
        speaker = str(item.get("speaker", "")).strip()
        message = str(item.get("message", "")).strip()
        if not speaker or not message or str(item.get("role", "")).strip() == "loading":
            continue
        quotes.append(
            {
                "speaker": speaker,
                "message": trim_summary_text(message, 140),
            }
        )
        if len(quotes) >= 4:
            break
    return list(reversed(quotes))


def _build_share_text(
    *,
    title: str,
    summary: str,
    events: list[dict[str, Any]],
    relations: list[dict[str, Any]],
    arcs: list[dict[str, Any]],
    hooks: list[str],
    next_hint: str,
    quotes: list[dict[str, str]],
) -> str:
    parts = [f"《{title}》", summary]
    if quotes:
        parts.append(
            "片段\n"
            + "\n".join(
                f"{item.get('speaker', '角色')}：{item.get('message', '')}"
                for item in quotes
            )
        )
    if events:
        parts.append(
            "事件\n"
            + "\n".join(
                f"· {item.get('title', '剧情推进')}"
                + (
                    f"（{item.get('time_hint', '')} · {item.get('location', '')}）"
                    if item.get("time_hint") or item.get("location")
                    else ""
                )
                for item in events[:5]
            )
        )
    if relations:
        parts.append(
            "关系变化\n"
            + "\n".join(
                f"· {item.get('label', item.get('pair_key', ''))} "
                + "、".join(
                    f"{change.get('label', '')}"
                    f"{int(change.get('delta', 0) or 0):+d}"
                    for change in list(item.get("changes", []) or [])
                )
                for item in relations[:5]
            )
        )
    if arcs:
        parts.append(
            "人物状态\n"
            + "\n".join(
                f"· {item.get('name', '人物')}：{item.get('growth_summary', '')}"
                for item in arcs[:5]
            )
        )
    if hooks:
        parts.append("待续伏笔\n" + "\n".join(f"· {hook}" for hook in hooks[:4]))
    if next_hint:
        parts.append(f"下一拍\n{next_hint}")
    return "\n\n".join(parts)


__all__ = ["build_story_recap"]
