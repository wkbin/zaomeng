package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.*
import top.wkbin.zaomeng.data.api.WorldBookInjectionPosition
import top.wkbin.zaomeng.data.api.WorldBookScanSource

internal object SillyTavernExport {
    internal val personaFields = mapOf(
        "description" to "core_identity",
        "personality" to "temperament_type",
        "scenario" to "story_role",
        "mes_example" to "speech_style",
    )
    val distilledCardFields = personaFields.keys.map { "card_$it" } + "card_first_mes"
    val promptFields = listOf("system_prompt", "post_history_instructions")
    private val cardTextFields = listOf("first_mes", "creator_notes", "creator", "character_version")
    private val supplements = mapOf(
        "identity_anchor" to "身份背景", "gender" to "性别", "age_stage" to "年龄阶段",
        "appearance_feature" to "外貌", "habit_action" to "习惯", "soul_goal" to "目标",
        "hidden_desire" to "隐藏愿望", "inner_conflict" to "内在冲突", "worldview" to "世界观",
        "belief_anchor" to "信念", "moral_bottom_line" to "底线", "core_traits" to "性格特征",
        "key_bonds" to "重要关系", "preference_like" to "偏好", "dislike_hate" to "厌恶",
        "decision_rules" to "决策习惯", "stress_response" to "压力反应", "typical_lines" to "典型台词",
        "speech_style" to "说话方式",
    )

    fun importedProfileFields(profile: Map<String, Any?>, card: SillyTavernImport.Card): Map<String, String> {
        val baseline = SillyTavernImport.profileFields(card.data)
        val normalize = PersonaProfileNormalizer::normalizeFieldValue
        return personaFields.mapValues { (field, mapped) ->
            val current = normalize(profile[mapped]?.toString().orEmpty())
            (profile[field] as? String) ?: if (current == normalize(baseline[mapped].orEmpty())) {
                card.data.text(field)
            } else current
        } + promptFields.associateWith { field -> (profile[field] as? String) ?: card.data.text(field) }
    }

    fun characterCard(
        character: String,
        profile: Map<String, String>,
        original: SillyTavernImport.Card?,
        cardFields: Map<String, String>? = null,
    ): JsonObject {
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
                val authored = if (originalData.isEmpty()) profile["card_$target"].orEmpty().replace("\\n", "\n") else ""
                val value = authored.ifBlank {
                    if (target == "mes_example" && originalData.isEmpty()) "" else currentCardText(target, source)
                }
                put(target, cardFields?.get(target) ?: value)
            }
            for (key in cardTextFields) put(key, originalData.text(key))
            for (key in promptFields) put(key, cardFields?.get(key) ?: originalData.text(key))
            if (originalData.isEmpty()) {
                put("first_mes", profile["card_first_mes"].orEmpty().replace("\\n", "\n"))
            }
            if (originalData.text("creator_notes").isBlank() && originalData.text("creatorcomment").isNotBlank()) {
                put("creator_notes", originalData.text("creatorcomment"))
            }
            val extra = supplements.mapNotNull { (field, label) ->
                if (field == "speech_style" && originalData.isNotEmpty()) return@mapNotNull null
                profile[field]?.takeIf(String::isNotBlank)?.let { "$label：$it" }
            }
            if (extra.isNotEmpty() && !(originalData.isEmpty() && !profile["card_description"].isNullOrBlank())) {
                val description = cardFields?.get("description") ?: currentCardText("description", "core_identity")
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
        val isV3 = originalData.isNotEmpty() && original?.original?.text("spec") == "chara_card_v3"
        return buildJsonObject {
            put("spec", if (isV3) "chara_card_v3" else "chara_card_v2")
            put("spec_version", if (isV3) "3.0" else "2.0")
            put("data", data)
        }
    }

    // Field names mirror SillyTavern's convertCharacterBook and convertWorldInfoToCharacterBook.
    private val fieldsSupportedAtBothLevels = setOf(
        "delay", "sticky", "cooldown", "group", "groupOverride", "groupWeight", "useGroupScoring", "triggers",
        "scanDepth", "caseSensitive", "matchWholeWords", "selectiveLogic", "ignoreBudget",
    ) + WorldBookScanSource.entries.map { it.nativeField }

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
        "exclude_recursion" to "excludeRecursion",
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
        "triggers" to "triggers",
        "ignore_budget" to "ignoreBudget",
    ) + WorldBookScanSource.entries.associate { it.embeddedField to it.nativeField }

    fun worldBook(book: JsonObject, embedded: Boolean = book["entries"] is JsonArray): JsonObject {
        val originalKeys = (book["entries"] as? JsonObject)?.keys?.toList().orEmpty()
        val entries = SillyTavernImport.loreEntries(book)
        val ids = uniqueIds(entries.mapIndexed { index, entry ->
            entry.number("uid") ?: entry.number("id") ?: originalKeys.getOrNull(index)?.toIntOrNull()
        })
        val exported = buildJsonObject {
            for ((index, entry) in entries.withIndex()) {
                val id = ids[index]
                put(id.toString(), nativeWorldEntry(entry, book, id, index, embedded))
            }
        }
        return JsonObject(book - "originalData" + ("entries" to exported))
    }

    private fun nativeWorldEntry(
        entry: JsonObject,
        book: JsonObject,
        id: Int,
        index: Int,
        embedded: Boolean,
    ): JsonObject = buildJsonObject {
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
        val rawPosition = entry.lorePlacementField("position", embedded)
        val position = rawPosition.supportedPosition()?.sillyTavernId ?: (rawPosition as? JsonPrimitive)?.intOrNull ?: 1
        put("position", position)
        put("displayIndex", entry.number("displayIndex") ?: extensions.number("display_index") ?: index)
        val probability = SillyTavernImport.configuredProbability(entry, extensions) ?: 100.0
        put("probability", if (probability % 1 == 0.0) JsonPrimitive(probability.toInt()) else JsonPrimitive(probability))
        put("useProbability", entry.boolean("useProbability") ?: extensions.boolean("useProbability") ?: true)
        put("scanDepth", entry.number("scanDepth") ?: extensions.number("scan_depth") ?: book.number("scan_depth"))
        put("matchWholeWords", entry.boolean("matchWholeWords") ?: extensions.boolean("match_whole_words") ?: book.boolean("match_whole_words"))
        putSpecialPlacement(entry, embedded)
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
                    for ((embedded, field) in worldExtensions) {
                        if (native || field in fieldsSupportedAtBothLevels && entry[field] != JsonNull) {
                            entry[field]?.let { put(embedded, it) }
                        }
                    }
                    if (entry.number("scanDepth") == null && (entry["extensions"] as? JsonObject)?.number("scan_depth") == null) {
                        book.number("scan_depth")?.let { put("scan_depth", it) }
                    }
                    if (entry.boolean("matchWholeWords") == null && (entry["extensions"] as? JsonObject)?.boolean("match_whole_words") == null) {
                        book.boolean("match_whole_words")?.let { put("match_whole_words", it) }
                    }
                    putSpecialPlacement(entry, embedded = true)
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
                val position = entry.lorePlacementField("position", embedded = true).supportedPosition()
                put("position", if (position == WorldBookInjectionPosition.BeforeCharacter) "before_char" else "after_char")
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

    private fun JsonObjectBuilder.putSpecialPlacement(entry: JsonObject, embedded: Boolean) {
        val position = entry.lorePlacementField("position", embedded).supportedPosition()
        if (position == WorldBookInjectionPosition.BeforeExamples || position == WorldBookInjectionPosition.AfterExamples ||
            position == WorldBookInjectionPosition.BeforeAuthorNote || position == WorldBookInjectionPosition.AfterAuthorNote
        ) {
            put("position", position.sillyTavernId)
            return
        }
        if (position == WorldBookInjectionPosition.Outlet) {
            put("position", 7)
            entry.outletName(embedded, position)?.let { put("outletName", it) }
            return
        }
        val depth = entry.injectionDepth(embedded, position) ?: return
        put("position", 4)
        put("depth", depth)
        entry.injectionRole(embedded, position)?.let { put("role", it.sillyTavernId) }
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
