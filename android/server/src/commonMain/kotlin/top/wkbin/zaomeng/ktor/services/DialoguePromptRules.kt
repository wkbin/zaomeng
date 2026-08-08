package top.wkbin.zaomeng.ktor.services

/**
 * 对话 system prompt 规则文本生成（迁移自 Python src/web/chat/prompt_rules.py）。
 *
 * 全部为纯文本规则函数，无文件 IO。输入统一使用 Map<String, Any?>（从 JsonObject 转换）。
 */
object DialoguePromptRules {

    private fun isSceneMessageKind(messageKind: String): Boolean =
        messageKind.trim() in setOf("narration", "plot")

    fun trimSummaryText(value: Any?, limit: Int): String {
        val text = value?.toString()?.split(Regex("\\s+"))?.joinToString(" ")?.trim() ?: ""
        if (text.isEmpty()) return ""
        if (text.length <= limit) return text
        return text.take(limit) + "..."
    }

    fun modeRule(mode: String, messageKind: String = "dialogue", controlledCharacter: String = ""): String {
        if (isSceneMessageKind(messageKind)) {
            val controlled = controlledCharacter.trim()
            if (mode == "act" && controlled.isNotEmpty()) {
                return "The user pushed the scene with a director beat, not by speaking as $controlled. " +
                    "Other cast members must react in character; $controlled may also react, but must not be the only voice."
            }
            if (mode == "insert") {
                return "The user pushed the scene with a director beat, not as their self-insert line. " +
                    "The cast should react in character."
            }
            return "The user is observing. Characters should continue the scene among themselves."
        }
        if (mode == "act") {
            return "The user is speaking as one existing character. Other characters should reply to that role naturally."
        }
        if (mode == "insert") {
            return "The user enters the scene as themselves. Characters should react to the self-insert identity consistently."
        }
        return "The user is observing. Characters should continue the scene among themselves."
    }

    fun speakerRule(mode: String, session: Map<String, Any?>, messageKind: String = "dialogue"): String {
        if (isSceneMessageKind(messageKind)) {
            return "Treat the user message as an in-world scene cue or director beat, not as a cast member's spoken line."
        }
        if (mode == "act") {
            return "Treat the user message as spoken by ${session["controlled_character"]?.toString().orEmpty()}."
        }
        if (mode == "insert") {
            val card = session["self_insert"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val displayName = card["display_name"]?.toString().orEmpty().ifBlank { "你" }
            val sceneIdentity = card["scene_identity"]?.toString().orEmpty()
                .ifBlank { card["core_identity"]?.toString().orEmpty().ifBlank { "访客" } }
            return "Treat the user message as spoken by $displayName " +
                "who enters the scene as $sceneIdentity."
        }
        return "Treat the user message as a scene steering hint. Characters reply in-world."
    }

    fun responseStyleRule(
        mode: String,
        messageKind: String = "dialogue",
        controlledCharacter: String = "",
    ): String {
        if (isSceneMessageKind(messageKind)) {
            val base = "The cue is scene-driving. Let the cast react with concrete action/emotion changes; " +
                "use 场景提示 or 旁白 only for true scene beats such as entrances, exits, environment changes, or transitions; " +
                "for small gestures like raising eyes, lowering the head, smiling, pausing, or turning around, fold them into the character's spoken line with short parenthetical action instead of a separate narration line."
            val controlled = controlledCharacter.trim()
            if (mode == "act" && controlled.isNotEmpty()) {
                return "$base When the user controls $controlled, other participants must also speak; " +
                    "do not return only $controlled's line. " +
                    "If $controlled replies, place that line before the other cast members' closing lines, not as the final character reply."
            }
            return base
        }
        if (mode == "observe") {
            return "Prefer 2-4 short in-character replies when the scene is busy, and fewer when it is quiet. " +
                "Small visible actions should stay inside the character line as short parenthetical beats, for example （她低头笑了笑）..., rather than becoming standalone narration."
        }
        if (mode == "act") {
            return "Reply as the other characters addressing the controlled role directly. " +
                "If a character动作 is obvious but small, embed it in parentheses inside that character's line instead of emitting a separate narration line."
        }
        return "Reply as the cast addressing the self-insert user naturally inside the scene. " +
            "Keep obvious small actions inside the speaking character's line with short parentheses, not as separate narration."
    }

    fun sceneRule(sceneCard: Map<String, Any?>): String {
        if (sceneCard.isEmpty()) {
            return "If no explicit scene card is provided, infer a natural continuation from the recent transcript and relation context."
        }
        val details = listOf(
            "location=${sceneCard["location"]?.toString()?.trim().orEmpty()}",
            "atmosphere=${sceneCard["atmosphere"]?.toString()?.trim().orEmpty()}",
            "opening_situation=${sceneCard["opening_situation"]?.toString()?.trim().orEmpty()}",
            "public_goal=${sceneCard["public_goal"]?.toString()?.trim().orEmpty()}",
            "hidden_tension=${sceneCard["hidden_tension"]?.toString()?.trim().orEmpty()}",
            "scene_drive=${sceneCard["scene_drive"]?.toString()?.trim().orEmpty()}",
            "expected_rhythm=${sceneCard["expected_rhythm"]?.toString()?.trim().orEmpty()}",
        )
        val compact = details.filter { !it.endsWith("=") }.joinToString(" | ")
        val anchor = compact.ifBlank { "keep replies anchored in the chosen scene framing" }
        return "Keep the scene anchored to the selected scene card: $anchor."
    }

    fun sceneProgressRule(sceneProgress: Map<String, Any?>): String {
        val state = sceneProgress
        val present = (state["present_participants"] as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val offstage = (state["offstage_participants"] as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val timeHint = state["time_hint"]?.toString()?.trim().orEmpty()
        val location = state["location"]?.toString()?.trim().orEmpty()
        val atmosphere = state["atmosphere_summary"]?.toString()?.trim().orEmpty()
        val note = state["progression_note"]?.toString()?.trim().orEmpty()
        val shift = state["should_offer_scene_shift"] == true || state["should_offer_scene_shift"]?.toString() == "true"
        val reason = state["scene_shift_reason"]?.toString()?.trim().orEmpty()
        val beatMaturity = (state["beat_maturity"] as? Number)?.toInt() ?: 0

        val bits = mutableListOf(
            "Respect scene continuity: keep who is present, who already left, and what time/location the scene has drifted to internally consistent."
        )
        if (timeHint.isNotEmpty() || location.isNotEmpty()) {
            val details = mutableListOf<String>()
            if (timeHint.isNotEmpty()) details.add("time=$timeHint")
            if (location.isNotEmpty()) details.add("location=$location")
            if (atmosphere.isNotEmpty()) details.add("atmosphere=$atmosphere")
            bits.add("Current scene state: ${details.joinToString(", ")}.")
        }
        if (present.isNotEmpty()) {
            bits.add("Characters currently in-scene: ${present.joinToString(", ")}.")
        }
        if (offstage.isNotEmpty()) {
            bits.add(
                "Characters currently offstage: ${offstage.joinToString(", ")}. Offstage characters must not speak or act until the text explicitly brings them back."
            )
        }
        bits.add("Let farewells, departures, going home, changing rooms, or entering a more private location naturally change who can reply next.")
        bits.add("Allow time to move forward when the conversation cues it, instead of freezing the whole scene in one unchanged moment.")
        if (note.isNotEmpty()) {
            bits.add("Latest progression note: $note.")
        }
        if (beatMaturity > 0) {
            bits.add("Current beat maturity is $beatMaturity/100; let replies feel appropriately early, settled, or ready to turn.")
        }
        val tension = state["world_tension_summary"]?.toString()?.trim().orEmpty()
        if (tension.isNotEmpty()) {
            bits.add("Current world tension to carry forward: $tension.")
        }
        if (shift) {
            bits.add("This beat is mature enough to hint a next scene or transition if it helps momentum. Reason: ${reason.ifBlank { "the current beat already feels complete" }}.")
        }
        return bits.joinToString(" ")
    }

    fun plotProgressionContract(messageKind: String, sceneProgress: Map<String, Any?>): String {
        if (messageKind.trim() != "plot") return ""
        val state = sceneProgress
        val beatMaturity = (state["beat_maturity"] as? Number)?.toInt() ?: 0
        val turnsInScene = (state["turns_in_current_scene"] as? Number)?.toInt() ?: 0
        val contract = mutableListOf(
            "PLOT_PROGRESSION_CONTRACT is mandatory for this turn.",
            "The first output item must use speaker 场景提示 or 旁白 and establish one concrete scene-level change that happens now.",
            "The change must introduce at least one of: a new event, interruption, revealed information, escalating conflict, consequential decision, entrance or exit, time advance, location transition, or a completed objective that opens the next beat.",
            "Do not merely paraphrase the user's cue, repeat the current banter, or add only gestures and emotional reactions.",
            "After the scene beat, let the present cast react in character and end with a clear consequence, choice, question, or unresolved hook that can drive the next turn.",
            "Treat the user's text as a desired direction, not as a cast member's spoken line, even when it is phrased in first person or resembles dialogue.",
        )
        if (beatMaturity >= 70 || turnsInScene >= 8) {
            contract.add("The current beat is already mature or long-running; prefer turning into the next beat over extending the same conversational topic.")
        }
        return contract.joinToString(" ")
    }

    fun suggestionModeRule(mode: String): String = when (mode) {
        "act" -> "Draft the user's next line as the controlled character, fully in character."
        "insert" -> "Draft the user's next line as the self-insert identity inside the scene."
        else -> "Draft the user's next line as a short scene-steering utterance that introduces movement, tension, reaction, interruption, or new information; not a character reply."
    }

    fun suggestionStyleRule(mode: String): String = when (mode) {
        "observe" -> "Prefer one short scene-driving prompt that pushes the plot forward immediately, such as a new beat, interruption, reveal, gesture, or emotional turn, " +
            "written as something that happens now in-scene, not as a suggestion about what to do."
        "act" -> "Prefer one concise in-character line that another participant can answer naturally, as final sendable wording."
        else -> "Prefer one concise line that sounds like the self-insert user speaking naturally in the scene, as final sendable wording."
    }

    fun buildUserSuggestionPersona(
        mode: String,
        session: Map<String, Any?>,
        personaContexts: List<Map<String, Any?>>,
        sceneProgress: Map<String, Any?>? = null,
        sessionSummary: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        val sceneCard = (session["scene_card"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val state = sceneProgress ?: emptyMap()
        val summary = sessionSummary ?: emptyMap()
        if (mode == "act") {
            val controlled = session["controlled_character"]?.toString()?.trim().orEmpty()
            val matched = personaContexts.firstOrNull { it["name"]?.toString()?.trim() == controlled } ?: emptyMap()
            return mapOf(
                "mode" to "act",
                "speaker" to controlled,
                "source" to "controlled_character_persona",
                "must_follow" to "Write exactly as this controlled character would speak in the current scene.",
                "profile" to (matched["profile"] ?: emptyMap<Any?, Any?>()),
                "preview" to (matched["preview"] ?: emptyMap<Any?, Any?>()),
                "scene_card" to sceneCard,
            )
        }
        if (mode == "insert") {
            val card = (session["self_insert"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
            return mapOf(
                "mode" to "insert",
                "speaker" to (card["display_name"]?.toString()?.trim().orEmpty().ifBlank { "你" }),
                "source" to "self_insert_profile",
                "must_follow" to "Write as the self-insert user, keeping their full role card, identity, motives, and speaking flavor consistent.",
                "profile" to card,
                "scene_card" to sceneCard,
            )
        }
        val preferredMoves = mutableListOf(
            "introduce a new action",
            "add a small interruption",
            "surface a hidden tension",
            "shift the emotional temperature",
            "make someone notice something important",
        )
        val avoidPatterns = listOf(
            "generic steering wrappers like 要不先让他们 / 不如让他们 / 继续聊下去",
            "meta phrasing that explains what the user should do instead of directly doing it",
            "summary-style lines that only restate the current situation",
        )
        val offstage = (state["offstage_participants"] as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val shift = state["should_offer_scene_shift"] == true || state["should_offer_scene_shift"]?.toString() == "true"
        if (shift) {
            preferredMoves.addAll(
                listOf(
                    "turn the scene into its next beat naturally",
                    "advance time or location without sounding abrupt",
                    "trigger a concrete transition beat with an immediate sensory cue or interruption",
                )
            )
        } else if (offstage.isNotEmpty()) {
            preferredMoves.add("briefly cut to an offstage thread only if the text explicitly motivates it")
        }
        val anchorRaw = listOf(
            summary["current_location"],
            summary["current_companions"],
            summary["pending_commitments"],
            summary["current_goal"],
            summary["unresolved_threads"],
            summary["recent_conflicts"],
            summary["major_beats"],
            state["world_tension_summary"],
        )
        val anchorLines = anchorRaw
            .mapNotNull { it?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .map { trimSummaryText(it, 96) }
        return mapOf(
            "mode" to "observe",
            "speaker" to "User",
            "source" to "observer_hint",
            "must_follow" to "Write as a scene observer giving a short in-world nudge that actively moves the scene. " +
                "It should read like an immediate next beat happening now, not like advice about what could happen. " +
                "Respect the current scene progress, presence state, and whether this beat should continue or naturally turn into the next one.",
            "profile" to mapOf(
                "goal" to "push_plot_forward",
                "preferred_moves" to preferredMoves,
                "avoid_patterns" to avoidPatterns,
                "anchor_lines" to anchorLines.take(4),
                "scene_shift_reason" to (state["scene_shift_reason"]?.toString()?.trim().orEmpty()),
                "time_hint" to (state["time_hint"]?.toString()?.trim().orEmpty()),
                "location" to (state["location"]?.toString()?.trim().orEmpty()),
                "world_tension_summary" to (state["world_tension_summary"]?.toString()?.trim().orEmpty()),
            ),
            "scene_card" to sceneCard,
        )
    }

    fun responderHints(
        mode: String,
        participants: List<String>,
        speaker: String,
        messageKind: String = "dialogue",
        controlledCharacter: String = "",
    ): List<Map<String, String>> {
        val controlled = controlledCharacter.trim()
        val ordered = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        if (isSceneMessageKind(messageKind) && mode == "act" && controlled.isNotEmpty()) {
            val others = mutableListOf<String>()
            for (name in participants) {
                val normalized = name.trim()
                if (normalized.isEmpty() || normalized == controlled || normalized in seen) continue
                seen.add(normalized)
                others.add(normalized)
            }
            if (controlled in participants) {
                when {
                    others.size >= 2 -> ordered.addAll(listOf(others[0], controlled) + others.drop(1))
                    others.size == 1 -> ordered.addAll(listOf(controlled, others[0]))
                    else -> ordered.add(controlled)
                }
            }
        } else {
            for (name in participants) {
                val normalized = name.trim()
                if (normalized.isEmpty() || normalized in seen) continue
                seen.add(normalized)
                ordered.add(normalized)
            }
        }

        val hints = mutableListOf<Map<String, String>>()
        for (name in ordered) {
            if (mode == "act" && !isSceneMessageKind(messageKind) && name == speaker) continue
            var priority = "normal"
            if (isSceneMessageKind(messageKind) && mode == "act" && controlled.isNotEmpty()) {
                priority = if (name == controlled) "normal" else "high"
            } else if (hints.isEmpty()) {
                priority = "high"
            }
            hints.add(mapOf("name" to name, "should_reply" to "yes", "priority" to priority))
        }
        return hints
    }

    fun hostPromptBrief(
        mode: String,
        speaker: String,
        participants: List<String>,
        messageKind: String = "dialogue",
        controlledCharacter: String = "",
    ): String {
        val clean = participants.map { it.trim() }.filter { it.isNotEmpty() }
        if (isSceneMessageKind(messageKind)) {
            val controlled = controlledCharacter.trim()
            if (mode == "act" && controlled.isNotEmpty()) {
                val others = clean.filter { it != controlled }
                val otherLabel = if (others.isNotEmpty()) others.joinToString(", ") else "the other participants"
                return "The user sent an in-world scene cue (not a line from $controlled). " +
                    "Let $otherLabel answer in character first; $controlled may react too but other cast must not be silent."
            }
            if (mode == "insert") {
                return "The user sent a scene cue. Let ${clean.joinToString(", ")} react in character, " +
                    "with multiple cast voices when the scene is busy."
            }
            return "The user is observing. Let ${clean.joinToString(", ")} continue the scene in character and keep the chosen scene moving."
        }
        if (mode == "act") {
            return "The user speaks as $speaker. Let the other participants answer in character."
        }
        if (mode == "insert") {
            return "The user enters the scene as $speaker. Let the cast react in character."
        }
        return "The user is observing. Let ${clean.joinToString(", ")} continue the scene in character and keep the chosen scene moving."
    }

    fun hostSuggestionPromptBrief(
        mode: String,
        speaker: String,
        participants: List<String>,
        sceneProgress: Map<String, Any?>? = null,
    ): String {
        val state = sceneProgress ?: emptyMap()
        val clean = participants.map { it.trim() }.filter { it.isNotEmpty() }
        if (mode == "act") {
            return "Help the user speak as $speaker with one believable next line."
        }
        if (mode == "insert") {
            return "Help the user speak as $speaker inside the current scene with one natural next line."
        }
        val shiftReason = state["scene_shift_reason"]?.toString()?.trim().orEmpty()
        val shift = state["should_offer_scene_shift"] == true || state["should_offer_scene_shift"]?.toString() == "true"
        if (shift) {
            return "Help the user guide ${clean.joinToString(", ")} with one short prompt that naturally turns this scene into its next beat. " +
                "Current transition pressure: ${shiftReason.ifBlank { "the current beat already feels complete" }}. " +
                "Make it feel like the next beat is already landing, not like a planning note."
        }
        val tension = state["world_tension_summary"]?.toString()?.trim().orEmpty()
        if (tension.isNotEmpty()) {
            return "Help the user guide ${clean.joinToString(", ")} with one short prompt that clearly pushes the scene forward. " +
                "Carry this tension: $tension. Make it sound like an immediate in-world beat, not a meta hint."
        }
        return "Help the user guide ${clean.joinToString(", ")} with one short prompt that clearly pushes the scene into its next beat. " +
            "It must sound like an immediate in-world development."
    }

    fun normalizeMessageKind(messageKind: String): String {
        val kind = messageKind.trim().lowercase()
        if (kind in setOf("plot", "plot_push", "advance")) return "plot"
        if (kind in setOf("narration", "scene", "scene_prompt", "director")) return "narration"
        return "dialogue"
    }
}
