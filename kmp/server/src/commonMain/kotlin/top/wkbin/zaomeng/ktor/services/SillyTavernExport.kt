package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.*

internal object SillyTavernExport {
    private val personaFields = mapOf(
        "description" to "core_identity",
        "personality" to "temperament_type",
        "scenario" to "story_role",
        "mes_example" to "speech_style",
    )
    private val cardTextFields = listOf("first_mes", "creator_notes", "system_prompt", "post_history_instructions", "creator", "character_version")
    private val supplements = mapOf(
        "identity_anchor" to "身份背景", "gender" to "性别", "age_stage" to "年龄阶段",
        "appearance_feature" to "外貌", "habit_action" to "习惯", "soul_goal" to "目标",
        "hidden_desire" to "隐藏愿望", "inner_conflict" to "内在冲突", "worldview" to "世界观",
        "belief_anchor" to "信念", "moral_bottom_line" to "底线", "core_traits" to "性格特征",
        "key_bonds" to "重要关系", "preference_like" to "偏好", "dislike_hate" to "厌恶",
        "decision_rules" to "决策习惯", "stress_response" to "压力反应", "typical_lines" to "典型台词",
    )

    fun characterCard(character: String, profile: Map<String, String>, original: SillyTavernImport.Card?): JsonObject {
        val originalData = original?.data?.takeIf { it.text("name").trim() == character }.orEmpty()
        val baseline = SillyTavernImport.profileFields(JsonObject(originalData))
        val normalize = PersonaProfileNormalizer::normalizeFieldValue
        fun currentCardText(target: String, source: String): String {
            val current = profile[source].orEmpty()
            return if (originalData.isNotEmpty() && current == normalize(baseline[source].orEmpty())) {
                originalData.text(target)
            } else current
        }
        val data = buildJsonObject {
            // Versioned cards can carry extension fields understood by SillyTavern and other tools.
            if (original?.original?.text("spec").orEmpty().isNotEmpty()) {
                originalData.forEach { (key, value) -> if (key != "character_book") put(key, value) }
            }
            put("name", character)
            for ((target, source) in personaFields) {
                put(target, currentCardText(target, source))
            }
            for (key in cardTextFields) put(key, originalData.text(key))
            if (originalData.text("creator_notes").isBlank() && originalData.text("creatorcomment").isNotBlank()) {
                put("creator_notes", originalData.text("creatorcomment"))
            }
            val extra = supplements.mapNotNull { (field, label) ->
                profile[field]?.takeIf(String::isNotBlank)?.let { "$label：$it" }
            }
            if (extra.isNotEmpty()) {
                val description = currentCardText("description", "core_identity")
                put("description", (listOf(description) + extra).filter(String::isNotBlank).joinToString("\n"))
            }
            val alternatives = when (val value = originalData["alternate_greetings"]) {
                is JsonArray -> JsonArray(value.filter { it is JsonPrimitive && it.isString })
                is JsonPrimitive -> if (value.isString) JsonArray(listOf(value)) else JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            put("alternate_greetings", alternatives)
            val tags = (originalData["tags"] as? JsonArray).orEmpty().filter { it is JsonPrimitive && it.isString }
            put("tags", JsonArray(tags))
            put("extensions", originalData["extensions"] as? JsonObject ?: JsonObject(emptyMap()))
            (originalData["character_book"] as? JsonObject)?.let { put("character_book", characterBook(it)) }
        }
        return buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("data", data)
        }
    }

    // Field names mirror SillyTavern's convertCharacterBook and convertWorldInfoToCharacterBook.
    private val worldExtensions = mapOf(
        "position" to "position",
        "exclude_recursion" to "excludeRecursion",
        "display_index" to "displayIndex",
        "probability" to "probability",
        "useProbability" to "useProbability",
        "depth" to "depth",
        "selectiveLogic" to "selectiveLogic",
        "outlet_name" to "outletName",
        "group" to "group",
        "group_override" to "groupOverride",
        "group_weight" to "groupWeight",
        "prevent_recursion" to "preventRecursion",
        "delay_until_recursion" to "delayUntilRecursion",
        "scan_depth" to "scanDepth",
        "match_whole_words" to "matchWholeWords",
        "use_group_scoring" to "useGroupScoring",
        "case_sensitive" to "caseSensitive",
        "automation_id" to "automationId",
        "role" to "role",
        "vectorized" to "vectorized",
        "sticky" to "sticky",
        "cooldown" to "cooldown",
        "delay" to "delay",
        "match_persona_description" to "matchPersonaDescription",
        "match_character_description" to "matchCharacterDescription",
        "match_character_personality" to "matchCharacterPersonality",
        "match_character_depth_prompt" to "matchCharacterDepthPrompt",
        "match_scenario" to "matchScenario",
        "match_creator_notes" to "matchCreatorNotes",
        "triggers" to "triggers",
        "ignore_budget" to "ignoreBudget",
    )

    fun worldBook(book: JsonObject): JsonObject {
        val originalKeys = (book["entries"] as? JsonObject)?.keys?.toList().orEmpty()
        val entries = SillyTavernImport.loreEntries(book)
        val ids = uniqueIds(entries.mapIndexed { index, entry ->
            entry.number("uid") ?: entry.number("id") ?: originalKeys.getOrNull(index)?.toIntOrNull()
        })
        val exported = buildJsonObject {
            for ((index, entry) in entries.withIndex()) {
                val id = ids[index]
                put(id.toString(), nativeWorldEntry(entry, book, id, index))
            }
        }
        return JsonObject(book - "originalData" + ("entries" to exported))
    }

    private fun nativeWorldEntry(entry: JsonObject, book: JsonObject, id: Int, index: Int): JsonObject = buildJsonObject {
        entry.forEach { (key, value) -> put(key, value) }
        val extensions = entry["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        for ((embedded, native) in worldExtensions) extensions[embedded]?.let { put(native, it) }
        // A file imported as World Info already stores the live values at the top level.
        for ((_, native) in worldExtensions) entry[native]?.let { put(native, it) }
        put("uid", id)
        put("key", entry.keyArray("key", "keys"))
        put("keysecondary", entry.keyArray("keysecondary", "secondary_keys"))
        put("comment", entry.text("comment"))
        put("content", entry.text("content"))
        put("constant", entry.boolean("constant") ?: false)
        put("selective", entry.boolean("selective") ?: entry.keyArray("keysecondary", "secondary_keys").isNotEmpty())
        put("disable", entry.boolean("disable") == true || entry.boolean("enabled") == false)
        put("order", entry.number("order") ?: entry.number("insertion_order") ?: 0)
        put("position", entry.number("position") ?: extensions.number("position") ?: if (entry.text("position") == "before_char") 0 else 1)
        put("displayIndex", entry.number("displayIndex") ?: extensions.number("display_index") ?: index)
        put("probability", entry.number("probability") ?: extensions.number("probability") ?: 100)
        put("useProbability", entry.boolean("useProbability") ?: extensions.boolean("useProbability") ?: true)
        put("scanDepth", entry.number("scanDepth") ?: extensions.number("scan_depth") ?: book.number("scan_depth"))
        put("matchWholeWords", entry.boolean("matchWholeWords") ?: extensions.boolean("match_whole_words") ?: book.boolean("match_whole_words"))
    }

    private fun characterBook(book: JsonObject): JsonObject {
        val native = book["entries"] is JsonObject
        val source = SillyTavernImport.loreEntries(book)
        val originalKeys = (book["entries"] as? JsonObject)?.keys?.toList().orEmpty()
        val ids = uniqueIds(source.mapIndexed { index, entry ->
            entry.number("id") ?: entry.number("uid") ?: originalKeys.getOrNull(index)?.toIntOrNull()
        })
        val entries = source.mapIndexed { index, entry ->
            buildJsonObject {
                entry.forEach { (key, value) -> put(key, value) }
                val extensions = buildJsonObject {
                    (entry["extensions"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
                    if (native) {
                        for ((embedded, field) in worldExtensions) entry[field]?.let { put(embedded, it) }
                    }
                    if (entry.number("scanDepth") == null && (entry["extensions"] as? JsonObject)?.number("scan_depth") == null) {
                        book.number("scan_depth")?.let { put("scan_depth", it) }
                    }
                    if (entry.boolean("matchWholeWords") == null && (entry["extensions"] as? JsonObject)?.boolean("match_whole_words") == null) {
                        book.boolean("match_whole_words")?.let { put("match_whole_words", it) }
                    }
                }
                put("id", ids[index])
                put("keys", entry.keyArray("keys", "key"))
                put("secondary_keys", entry.keyArray("secondary_keys", "keysecondary"))
                put("content", entry.text("content"))
                put("constant", entry.boolean("constant") ?: false)
                put("selective", entry.boolean("selective") ?: entry.keyArray("secondary_keys", "keysecondary").isNotEmpty())
                put("enabled", entry.boolean("disable") != true && entry.boolean("enabled") != false)
                put("use_regex", entry.boolean("use_regex") ?: true)
                put("insertion_order", entry.number("insertion_order") ?: entry.number("order") ?: 0)
                put("position", if (entry.text("position") == "before_char" || entry.number("position") == 0) "before_char" else "after_char")
                put("extensions", extensions)
            }
        }
        return JsonObject(
            book - "originalData" + mapOf(
                "entries" to JsonArray(entries),
                "extensions" to ((book["extensions"] as? JsonObject) ?: JsonObject(emptyMap())),
            ),
        )
    }

    private fun uniqueIds(requested: List<Int?>): List<Int> {
        val reserved = requested.filterNotNull().filter { it >= 0 }.toSet()
        val assigned = mutableSetOf<Int>()
        var next = 0
        return requested.map { id ->
            if (id != null && id >= 0 && assigned.add(id)) id else {
                while (next in reserved) next++
                next++
                next - 1
            }
        }
    }

    private fun Map<String, JsonElement>.text(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.keyArray(first: String, second: String): JsonArray =
        JsonArray(((get(first) as? JsonArray) ?: (get(second) as? JsonArray)).orEmpty().filter {
            it is JsonPrimitive && it.isString
        })
}
