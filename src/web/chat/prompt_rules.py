from __future__ import annotations

from typing import Any


def _is_scene_message_kind(message_kind: str) -> bool:
    return str(message_kind or "").strip() in {"narration", "plot"}


def _trim_summary_text(value: str, limit: int) -> str:
    text = " ".join(str(value or "").split()).strip()
    if not text:
        return ""
    if len(text) <= limit:
        return text
    return f"{text[:limit]}..."


def _mode_rule(mode: str, message_kind: str = "dialogue", controlled_character: str = "") -> str:
    if _is_scene_message_kind(message_kind):
        controlled = str(controlled_character or "").strip()
        if mode == "act" and controlled:
            return (
                f"The user pushed the scene with a director beat, not by speaking as {controlled}. "
                f"Other cast members must react in character; {controlled} may also react, but must not be the only voice."
            )
        if mode == "insert":
            return (
                "The user pushed the scene with a director beat, not as their self-insert line. "
                "The cast should react in character."
            )
        return "The user is observing. Characters should continue the scene among themselves."
    if mode == "act":
        return "The user is speaking as one existing character. Other characters should reply to that role naturally."
    if mode == "insert":
        return "The user enters the scene as themselves. Characters should react to the self-insert identity consistently."
    return "The user is observing. Characters should continue the scene among themselves."


def _speaker_rule(mode: str, session: dict[str, Any], message_kind: str = "dialogue") -> str:
    if _is_scene_message_kind(message_kind):
        return "Treat the user message as an in-world scene cue or director beat, not as a cast member's spoken line."
    if mode == "act":
        return f"Treat the user message as spoken by {session.get('controlled_character', '')}."
    if mode == "insert":
        card = session.get("self_insert", {})
        return (
            f"Treat the user message as spoken by {card.get('display_name', '你')} "
            f"who enters the scene as {card.get('scene_identity', card.get('core_identity', '访客'))}."
        )
    return "Treat the user message as a scene steering hint. Characters reply in-world."


def _response_style_rule(
    mode: str,
    message_kind: str = "dialogue",
    controlled_character: str = "",
) -> str:
    if _is_scene_message_kind(message_kind):
        base = (
            "The cue is scene-driving. Let the cast react with concrete action/emotion changes; "
            "use 场景提示 or 旁白 only for true scene beats such as entrances, exits, environment changes, or transitions; "
            "for small gestures like raising eyes, lowering the head, smiling, pausing, or turning around, fold them into the character's spoken line with short parenthetical action instead of a separate narration line."
        )
        controlled = str(controlled_character or "").strip()
        if mode == "act" and controlled:
            return (
                f"{base} "
                f"When the user controls {controlled}, other participants must also speak; "
                f"do not return only {controlled}'s line. "
                f"If {controlled} replies, place that line before the other cast members' closing lines, not as the final character reply."
            )
        return base
    if mode == "observe":
        return (
            "Prefer 2-4 short in-character replies when the scene is busy, and fewer when it is quiet. "
            "Small visible actions should stay inside the character line as short parenthetical beats, for example （她低头笑了笑）..., rather than becoming standalone narration."
        )
    if mode == "act":
        return (
            "Reply as the other characters addressing the controlled role directly. "
            "If a character动作 is obvious but small, embed it in parentheses inside that character's line instead of emitting a separate narration line."
        )
    return (
        "Reply as the cast addressing the self-insert user naturally inside the scene. "
        "Keep obvious small actions inside the speaking character's line with short parentheses, not as separate narration."
    )


def _scene_rule(scene_card: dict[str, Any]) -> str:
    if not scene_card:
        return "If no explicit scene card is provided, infer a natural continuation from the recent transcript and relation context."
    details = [
        f"location={str(scene_card.get('location', '')).strip()}",
        f"atmosphere={str(scene_card.get('atmosphere', '')).strip()}",
        f"opening_situation={str(scene_card.get('opening_situation', '')).strip()}",
        f"public_goal={str(scene_card.get('public_goal', '')).strip()}",
        f"hidden_tension={str(scene_card.get('hidden_tension', '')).strip()}",
        f"scene_drive={str(scene_card.get('scene_drive', '')).strip()}",
        f"expected_rhythm={str(scene_card.get('expected_rhythm', '')).strip()}",
    ]
    compact = " | ".join(part for part in details if not part.endswith("="))
    if not compact:
        compact = "keep replies anchored in the chosen scene framing"
    return f"Keep the scene anchored to the selected scene card: {compact}."


def _scene_progress_rule(scene_progress: dict[str, Any]) -> str:
    state = dict(scene_progress or {})
    present = [str(item).strip() for item in list(state.get("present_participants", []) or []) if str(item).strip()]
    offstage = [str(item).strip() for item in list(state.get("offstage_participants", []) or []) if str(item).strip()]
    time_hint = str(state.get("time_hint", "")).strip()
    location = str(state.get("location", "")).strip()
    atmosphere = str(state.get("atmosphere_summary", "")).strip()
    note = str(state.get("progression_note", "")).strip()
    shift = bool(state.get("should_offer_scene_shift", False))
    reason = str(state.get("scene_shift_reason", "")).strip()
    beat_maturity = int(state.get("beat_maturity", 0) or 0)

    bits = [
        "Respect scene continuity: keep who is present, who already left, and what time/location the scene has drifted to internally consistent.",
    ]
    if time_hint or location:
        details = []
        if time_hint:
            details.append(f"time={time_hint}")
        if location:
            details.append(f"location={location}")
        if atmosphere:
            details.append(f"atmosphere={atmosphere}")
        bits.append(f"Current scene state: {', '.join(details)}.")
    if present:
        bits.append(f"Characters currently in-scene: {', '.join(present)}.")
    if offstage:
        bits.append(
            f"Characters currently offstage: {', '.join(offstage)}. Offstage characters must not speak or act until the text explicitly brings them back."
        )
    bits.append(
        "Let farewells, departures, going home, changing rooms, or entering a more private location naturally change who can reply next."
    )
    bits.append(
        "Allow time to move forward when the conversation cues it, instead of freezing the whole scene in one unchanged moment."
    )
    if note:
        bits.append(f"Latest progression note: {note}.")
    if beat_maturity:
        bits.append(f"Current beat maturity is {beat_maturity}/100; let replies feel appropriately early, settled, or ready to turn.")
    tension = str(state.get("world_tension_summary", "")).strip()
    if tension:
        bits.append(f"Current world tension to carry forward: {tension}.")
    if shift:
        bits.append(
            f"This beat is mature enough to hint a next scene or transition if it helps momentum. Reason: {reason or 'the current beat already feels complete'}."
        )
    return " ".join(bits)


def _plot_progression_contract(
    message_kind: str,
    scene_progress: dict[str, Any],
) -> str:
    if str(message_kind or "").strip() != "plot":
        return ""
    state = dict(scene_progress or {})
    beat_maturity = int(state.get("beat_maturity", 0) or 0)
    turns_in_scene = int(state.get("turns_in_current_scene", 0) or 0)
    contract = [
        "PLOT_PROGRESSION_CONTRACT is mandatory for this turn.",
        "The first output item must use speaker 场景提示 or 旁白 and establish one concrete scene-level change that happens now.",
        "The change must introduce at least one of: a new event, interruption, revealed information, escalating conflict, consequential decision, entrance or exit, time advance, location transition, or a completed objective that opens the next beat.",
        "Do not merely paraphrase the user's cue, repeat the current banter, or add only gestures and emotional reactions.",
        "After the scene beat, let the present cast react in character and end with a clear consequence, choice, question, or unresolved hook that can drive the next turn.",
        "Treat the user's text as a desired direction, not as a cast member's spoken line, even when it is phrased in first person or resembles dialogue.",
    ]
    if beat_maturity >= 70 or turns_in_scene >= 8:
        contract.append(
            "The current beat is already mature or long-running; prefer turning into the next beat over extending the same conversational topic."
        )
    return " ".join(contract)


def _suggestion_mode_rule(mode: str) -> str:
    if mode == "act":
        return "Draft the user's next line as the controlled character, fully in character."
    if mode == "insert":
        return "Draft the user's next line as the self-insert identity inside the scene."
    return "Draft the user's next line as a short scene-steering utterance that introduces movement, tension, reaction, interruption, or new information; not a character reply."


def _suggestion_style_rule(mode: str) -> str:
    if mode == "observe":
        return (
            "Prefer one short scene-driving prompt that pushes the plot forward immediately, such as a new beat, interruption, reveal, gesture, or emotional turn, "
            "written as something that happens now in-scene, not as a suggestion about what to do."
        )
    if mode == "act":
        return "Prefer one concise in-character line that another participant can answer naturally, as final sendable wording."
    return "Prefer one concise line that sounds like the self-insert user speaking naturally in the scene, as final sendable wording."


def _build_user_suggestion_persona(
    mode: str,
    session: dict[str, Any],
    persona_contexts: list[dict[str, Any]],
    *,
    scene_progress: dict[str, Any] | None = None,
    session_summary: dict[str, Any] | None = None,
) -> dict[str, Any]:
    scene_card = dict(session.get("scene_card", {}) or {})
    state = dict(scene_progress or {})
    summary = dict(session_summary or {})
    if mode == "act":
        controlled = str(session.get("controlled_character", "")).strip()
        matched = next(
            (item for item in persona_contexts if str(item.get("name", "")).strip() == controlled),
            {},
        )
        return {
            "mode": "act",
            "speaker": controlled,
            "source": "controlled_character_persona",
            "must_follow": "Write exactly as this controlled character would speak in the current scene.",
            "profile": dict(matched.get("profile", {}) or {}),
            "preview": dict(matched.get("preview", {}) or {}),
            "scene_card": scene_card,
        }
    if mode == "insert":
        card = dict(session.get("self_insert", {}) or {})
        return {
            "mode": "insert",
            "speaker": str(card.get("display_name", "")).strip() or "你",
            "source": "self_insert_profile",
            "must_follow": "Write as the self-insert user, keeping their full role card, identity, motives, and speaking flavor consistent.",
            "profile": dict(card),
            "scene_card": scene_card,
        }
    preferred_moves = [
        "introduce a new action",
        "add a small interruption",
        "surface a hidden tension",
        "shift the emotional temperature",
        "make someone notice something important",
    ]
    avoid_patterns = [
        "generic steering wrappers like 要不先让他们 / 不如让他们 / 继续聊下去",
        "meta phrasing that explains what the user should do instead of directly doing it",
        "summary-style lines that only restate the current situation",
    ]
    offstage = [str(item).strip() for item in list(state.get("offstage_participants", []) or []) if str(item).strip()]
    if bool(state.get("should_offer_scene_shift", False)):
        preferred_moves.extend(
            [
                "turn the scene into its next beat naturally",
                "advance time or location without sounding abrupt",
                "trigger a concrete transition beat with an immediate sensory cue or interruption",
            ]
        )
    elif offstage:
        preferred_moves.append("briefly cut to an offstage thread only if the text explicitly motivates it")
    anchor_lines = [
        str(summary.get("current_location", "")).strip(),
        str(summary.get("current_companions", "")).strip(),
        str(summary.get("pending_commitments", "")).strip(),
        str(summary.get("current_goal", "")).strip(),
        str(summary.get("unresolved_threads", "")).strip(),
        str(summary.get("recent_conflicts", "")).strip(),
        str(summary.get("major_beats", "")).strip(),
        str(state.get("world_tension_summary", "")).strip(),
    ]
    anchor_lines = [_trim_summary_text(item, 96) for item in anchor_lines if item.strip()]
    return {
        "mode": "observe",
        "speaker": "User",
        "source": "observer_hint",
        "must_follow": (
            "Write as a scene observer giving a short in-world nudge that actively moves the scene. "
            "It should read like an immediate next beat happening now, not like advice about what could happen. "
            "Respect the current scene progress, presence state, and whether this beat should continue or naturally turn into the next one."
        ),
        "profile": {
            "goal": "push_plot_forward",
            "preferred_moves": preferred_moves,
            "avoid_patterns": avoid_patterns,
            "anchor_lines": anchor_lines[:4],
            "scene_shift_reason": str(state.get("scene_shift_reason", "")).strip(),
            "time_hint": str(state.get("time_hint", "")).strip(),
            "location": str(state.get("location", "")).strip(),
            "world_tension_summary": str(state.get("world_tension_summary", "")).strip(),
        },
        "scene_card": scene_card,
    }


def _responder_hints(
    mode: str,
    participants: list[str],
    speaker: str,
    message_kind: str = "dialogue",
    controlled_character: str = "",
) -> list[dict[str, str]]:
    controlled = str(controlled_character or "").strip()
    ordered: list[str] = []
    seen: set[str] = set()
    if _is_scene_message_kind(message_kind) and mode == "act" and controlled:
        others: list[str] = []
        for name in participants:
            normalized = str(name or "").strip()
            if not normalized or normalized == controlled or normalized in seen:
                continue
            seen.add(normalized)
            others.append(normalized)
        if controlled in participants:
            if len(others) >= 2:
                ordered = [others[0], controlled, *others[1:]]
            elif len(others) == 1:
                ordered = [controlled, others[0]]
            else:
                ordered = [controlled]
    else:
        for name in participants:
            normalized = str(name or "").strip()
            if not normalized or normalized in seen:
                continue
            seen.add(normalized)
            ordered.append(normalized)

    hints: list[dict[str, str]] = []
    for name in ordered:
        if mode == "act" and not _is_scene_message_kind(message_kind) and name == speaker:
            continue
        priority = "normal"
        if _is_scene_message_kind(message_kind) and mode == "act" and controlled:
            priority = "normal" if name == controlled else "high"
        elif not hints:
            priority = "high"
        hints.append(
            {
                "name": name,
                "should_reply": "yes",
                "priority": priority,
            }
        )
    return hints


def _host_prompt_brief(
    mode: str,
    speaker: str,
    participants: list[str],
    message_kind: str = "dialogue",
    controlled_character: str = "",
) -> str:
    if _is_scene_message_kind(message_kind):
        controlled = str(controlled_character or "").strip()
        if mode == "act" and controlled:
            others = [str(name).strip() for name in participants if str(name).strip() and str(name).strip() != controlled]
            other_label = ", ".join(others) if others else "the other participants"
            return (
                f"The user sent an in-world scene cue (not a line from {controlled}). "
                f"Let {other_label} answer in character first; {controlled} may react too but other cast must not be silent."
            )
        if mode == "insert":
            return (
                f"The user sent a scene cue. Let {', '.join(participants)} react in character, "
                "with multiple cast voices when the scene is busy."
            )
        return f"The user is observing. Let {', '.join(participants)} continue the scene in character and keep the chosen scene moving."
    if mode == "act":
        return f"The user speaks as {speaker}. Let the other participants answer in character."
    if mode == "insert":
        return f"The user enters the scene as {speaker}. Let the cast react in character."
    return f"The user is observing. Let {', '.join(participants)} continue the scene in character and keep the chosen scene moving."


def _host_suggestion_prompt_brief(
    mode: str,
    speaker: str,
    participants: list[str],
    *,
    scene_progress: dict[str, Any] | None = None,
) -> str:
    state = dict(scene_progress or {})
    if mode == "act":
        return f"Help the user speak as {speaker} with one believable next line."
    if mode == "insert":
        return f"Help the user speak as {speaker} inside the current scene with one natural next line."
    shift_reason = str(state.get("scene_shift_reason", "")).strip()
    if bool(state.get("should_offer_scene_shift", False)):
        return (
            f"Help the user guide {', '.join(participants)} with one short prompt that naturally turns this scene into its next beat. "
            f"Current transition pressure: {shift_reason or 'the current beat already feels complete'}. "
            "Make it feel like the next beat is already landing, not like a planning note."
        )
    tension = str(state.get("world_tension_summary", "")).strip()
    if tension:
        return (
            f"Help the user guide {', '.join(participants)} with one short prompt that clearly pushes the scene forward. "
            f"Carry this tension: {tension}. Make it sound like an immediate in-world beat, not a meta hint."
        )
    return (
        f"Help the user guide {', '.join(participants)} with one short prompt that clearly pushes the scene into its next beat. "
        "It must sound like an immediate in-world development."
    )


def _normalize_message_kind(message_kind: str) -> str:
    kind = str(message_kind or "").strip().lower()
    if kind in {"plot", "plot_push", "advance"}:
        return "plot"
    if kind in {"narration", "scene", "scene_prompt", "director"}:
        return "narration"
    return "dialogue"


__all__ = [
    "_build_user_suggestion_persona",
    "_host_prompt_brief",
    "_host_suggestion_prompt_brief",
    "_mode_rule",
    "_normalize_message_kind",
    "_responder_hints",
    "_response_style_rule",
    "_scene_progress_rule",
    "_plot_progression_contract",
    "_scene_rule",
    "_speaker_rule",
    "_suggestion_mode_rule",
    "_suggestion_style_rule",
]
