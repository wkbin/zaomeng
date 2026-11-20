package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString
import top.wkbin.zaomeng.data.api.ManagedWorldBookDto
import top.wkbin.zaomeng.data.api.CardGreetingsDto
import top.wkbin.zaomeng.data.api.CardGreetingDto
import top.wkbin.zaomeng.data.api.expandSillyTavernNames
import top.wkbin.zaomeng.data.api.ManagedWorldBookEntryDto
import top.wkbin.zaomeng.data.api.ManagedWorldBooksDto
import top.wkbin.zaomeng.data.api.SillyTavernPreviewDto
import top.wkbin.zaomeng.data.api.WorldBookMatchDto
import top.wkbin.zaomeng.data.api.WorldBookTestDto
import top.wkbin.zaomeng.platform.base64Decode
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid

/** Interchange behavior based on SillyTavern's character-card-parser.js and World Info format. */
internal class SillyTavernImport(private val storage: StorageService) {
    internal data class Card(val original: JsonObject, val data: JsonObject)
    fun importCard(bytes: ByteArray, png: Boolean): JsonObject {
        val card = validatedCard(bytes, png)
        val data = card.data
        val name = data.string("name")
        val fields = mapOf("name" to name, "display_name" to name) + profileFields(data)
        val runId = randomUuid().replace("-", "")
        val root = storage.getRunDirectory(runId)
        val personaDir = root / "artifacts/characters/imported/$name"
        val profile = personaDir / "PROFILE.md"
        val timestamp = nowIsoString()
        try {
            storage.mkdirs(personaDir)
            val yaml = fields.entries.joinToString("\n") { (key, value) ->
                "$key: ${JsonPrimitive(value).toString().replace("---", "\\u002d--")}"
            }
            storage.writeTextAtomically(profile, "---\n$yaml\n---\n")
            storage.writeTextAtomically(root / CARD_FILE, card.original.toString())
            if (png) {
                val avatar = storage.avatarFile(runId, name)
                storage.mkdirs(requireNotNull(avatar.parent))
                storage.writeBytesAtomically(avatar, bytes)
            }
            val manifest = buildJsonObject {
                put("run_id", runId)
                put("novel_name", name)
                put("status", "ready")
                put("success", true)
                put("entrypoint", "sillytavern")
                put("created_at", timestamp)
                put("updated_at", timestamp)
                put("characters", JsonArray(listOf(JsonPrimitive(name))))
                put("locked_characters", JsonArray(listOf(JsonPrimitive(name))))
                put("artifact_index", buildJsonObject {
                    put("characters", buildJsonArray {
                        add(buildJsonObject {
                            put("name", name)
                            put("persona_dir", personaDir.toString())
                            put("profile_file", profile.toString())
                        })
                    })
                })
            }
            storage.writeRunManifest(runId, manifest)
            return storage.withLiveAvatarVersions(manifest, runId)
        } catch (error: Throwable) {
            runCatching { storage.deleteRecursively(root) }
            throw error
        }
    }

    fun importWorldBook(runId: String, bytes: ByteArray): JsonObject {
        val book = parseObject(bytes)
        loreEntries(book)
        return storage.updateRunManifest(runId) { manifest ->
            storage.writeTextAtomically(storage.getRunDirectory(runId) / WORLD_BOOK, book.toString())
            markLoreChanged(manifest)
        }
    }

    fun listWorldBooks(runId: String): ManagedWorldBooksDto = ManagedWorldBooksDto(readAttachedBooks(runId).map { current ->
        ManagedWorldBookDto(
            source = current.source,
            name = current.book.string("name").take(120),
            revision = current.revision,
            entries = loreEntries(current.book).mapIndexed { index, entry ->
                ManagedWorldBookEntryDto(
                    index = index,
                    title = entryTitle(entry, index),
                    content = entry.string("content"),
                    enabled = entry.flag("disable") != true && entry.flag("enabled") != false,
                    constant = entry.flag("constant") == true,
                    keywords = entry.keys("key", "keys"),
                    secondaryKeywords = entry.keys("keysecondary", "secondary_keys"),
                )
            },
        )
    })

    fun exportWorldBook(runId: String, source: String): JsonObject {
        if (storage.readRunManifest(runId) == null) throw NoSuchElementException("书卷不存在。")
        val book = readAttachedBook(runId, source)?.book ?: throw NoSuchElementException("世界书不存在。")
        return SillyTavernExport.worldBook(book)
    }

    fun testWorldBooks(runId: String, text: String): WorldBookTestDto {
        require(text.isNotBlank() && text.length <= 2000) { "请输入 1 至 2000 字符的测试台词。" }
        val books = readAttachedBooks(runId)
        val embedded = books.firstOrNull { it.source == CHARACTER_SOURCE }?.book
        val standalone = books.firstOrNull { it.source == STANDALONE_SOURCE }?.book
        val entries = evaluateBooks(embedded, standalone, listOf(text))
        return WorldBookTestDto(
            entries = prioritizeTestResults(entries),
            entryCount = entries.size,
            injectedCount = entries.count { it.status == "injected" },
        )
    }

    fun setEntryEnabled(runId: String, source: String, index: Int, revision: String, enabled: Boolean): ManagedWorldBooksDto {
        storage.updateRunManifest(runId) { manifest ->
            val current = readAttachedBook(runId, source) ?: throw NoSuchElementException("世界书不存在。")
            current.requireRevision(revision)
            val entries = loreEntries(current.book)
            val entry = entries.getOrNull(index) ?: throw NoSuchElementException("世界书条目不存在。")
            val updated = buildJsonObject {
                entry.forEach { (key, value) -> put(key, value) }
                if (source == STANDALONE_SOURCE || "disable" in entry) put("disable", !enabled)
                if (source == CHARACTER_SOURCE || "enabled" in entry) put("enabled", enabled)
            }
            val book = JsonObject(current.book + ("entries" to when (val original = current.book["entries"]) {
                is JsonObject -> JsonObject(original + (original.keys.elementAt(index) to updated))
                else -> JsonArray(entries.mapIndexed { i, value -> if (i == index) updated else value })
            }))
            val card = current.card
            val root = if (card == null) book else {
                val data = JsonObject(card.data + ("character_book" to book))
                if (card.data === card.original) data else JsonObject(card.original + ("data" to data))
            }
            storage.writeTextAtomically(storage.getRunDirectory(runId) / sourceFile(source), root.toString())
            markLoreChanged(manifest)
        }
        return listWorldBooks(runId)
    }

    fun removeStandaloneBook(runId: String, revision: String): ManagedWorldBooksDto {
        storage.updateRunManifest(runId) { manifest ->
            val current = readAttachedBook(runId, STANDALONE_SOURCE) ?: throw NoSuchElementException("独立世界书不存在。")
            current.requireRevision(revision)
            storage.deleteFile(storage.getRunDirectory(runId) / WORLD_BOOK)
            markLoreChanged(manifest)
        }
        return listWorldBooks(runId)
    }

    private fun readAttachedBooks(runId: String): List<AttachedBook> {
        if (storage.readRunManifest(runId) == null) throw NoSuchElementException("书卷不存在。")
        return listOfNotNull(readAttachedBook(runId, CHARACTER_SOURCE), readAttachedBook(runId, STANDALONE_SOURCE))
    }

    private fun readAttachedBook(runId: String, source: String): AttachedBook? {
        val file = storage.getRunDirectory(runId) / sourceFile(source)
        val bytes = storage.readTextOrNull(file)?.encodeToByteArray() ?: return null
        val card = if (source == CHARACTER_SOURCE) parseCard(bytes, stored = true) else null
        val book = if (card == null) parseObject(bytes, stored = true) else card.data["character_book"] as? JsonObject ?: return null
        return AttachedBook(source, book, card, bytes.toByteString().sha256().hex())
    }

    private data class AttachedBook(val source: String, val book: JsonObject, val card: Card?, val revision: String) {
        fun requireRevision(expected: String) {
            if (revision != expected) throw WorldBookChangedException()
        }
    }

    private fun sourceFile(source: String): String = when (source) {
        CHARACTER_SOURCE -> CARD_FILE
        STANDALONE_SOURCE -> WORLD_BOOK
        else -> throw IllegalArgumentException("未知的世界书来源。")
    }

    private fun markLoreChanged(manifest: JsonObject): JsonObject =
        JsonObject(manifest + (REVISION_FIELD to JsonPrimitive(randomUuid())))

    fun preview(
        bytes: ByteArray,
        png: Boolean,
        worldBook: Boolean,
        targetRunId: String?,
        testText: String?,
    ): SillyTavernPreviewDto {
        require(testText == null || testText.length <= 2000) { "试触发文本不能超过 2000 字符。" }
        require(targetRunId == null || worldBook) { "只有独立世界书需要选择目标书卷。" }
        val card = if (worldBook) null else validatedCard(bytes, png)
        val book = if (worldBook) parseObject(bytes) else card?.data?.get("character_book") as? JsonObject
        val entries = book?.let(::loreEntries).orEmpty()
        val previous = targetRunId?.let {
            if (storage.readRunManifest(it) == null) throw NoSuchElementException("目标书卷不存在。")
            val file = storage.getRunDirectory(it) / WORLD_BOOK
            storage.readTextOrNull(file)?.let { parseObject(it.encodeToByteArray(), stored = true) }
        }
        val name = card?.data?.string("name").orEmpty()
        val tested = testText?.let {
            if (worldBook) evaluateBooks(null, book, listOf(it)) else evaluateBooks(book, null, listOf(it))
        }
        return SillyTavernPreviewDto(
            characterName = name,
            format = if (worldBook) "world_book" else card?.original?.string("spec").orEmpty().ifBlank { "legacy" },
            hasAvatar = png,
            greeting = card?.let { greeting(it, name, "你") }.orEmpty(),
            alternateGreetingCount = card?.let { cardGreetings(it).options.count { option -> option.index > 0 } } ?: 0,
            mappedFields = card?.let { profileFields(it.data).keys.toList() }.orEmpty(),
            bookName = book?.string("name")?.take(120).orEmpty(),
            entryCount = entries.size,
            previousBookName = previous?.string("name")?.take(120),
            previousEntryCount = previous?.let { loreEntries(it).size },
            unsupportedRules = unsupportedRules(entries, book, card?.data),
            testedEntries = tested?.let(::prioritizeTestResults),
            injectedCount = tested?.count { it.status == "injected" } ?: 0,
        )
    }

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        const val CARD_FILE = "sillytavern-card.json"
        const val WORLD_BOOK = "sillytavern-world-book.json"
        const val REVISION_FIELD = "sillytavern_lore_revision"
        const val MAX_SCAN_DEPTH = 32
        private const val DEFAULT_SCAN_DEPTH = 6
        private const val CHARACTER_SOURCE = "character_book"
        private const val STANDALONE_SOURCE = "world_book"
        private const val MAX_JSON_BYTES = 1024 * 1024
        private const val MAX_ENTRIES = 2000
        private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private val reservedName = Regex("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]", RegexOption.IGNORE_CASE)


        private fun validatedCard(bytes: ByteArray, png: Boolean): Card {
            val card = parseCard(if (png) readPngCard(bytes) else bytes)
            val name = card.data.string("name")
            require(name.length in 1..120 && name != "." && ".." !in name &&
                name.none { it.isISOControl() || it in "/\\<>:\"|?*" } &&
                !name.endsWith('.') && !reservedName.matches(name.substringBefore('.'))
            ) { "角色卡的人物名不能用作造梦人物名。" }
            card.data["character_book"]?.takeUnless { it == JsonNull }?.let {
                loreEntries(requireNotNull(it as? JsonObject) { "角色卡内的世界书无效。" })
            }
            return card
        }

        internal fun profileFields(data: JsonObject): Map<String, String> {
            val name = data.string("name")
            return mapOf(
                "core_identity" to cardText(data.string("description"), name, 1200),
                "temperament_type" to cardText(data.string("personality"), name, 480),
                "story_role" to cardText(data.string("scenario"), name, 600),
                "speech_style" to cardText(data.string("mes_example"), name, 800),
            ).filterValues(String::isNotBlank)
        }

        internal fun parseCard(bytes: ByteArray, stored: Boolean = false): Card {
            val root = parseObject(bytes, stored)
            val data = when (root.string("spec")) {
                "chara_card_v2", "chara_card_v3" -> root["data"] as? JsonObject
                "" -> root.takeIf { "description" in it || "first_mes" in it || "personality" in it }
                else -> null
            }
            require(data != null && data.string("name").isNotBlank()) { "这不是有效的 SillyTavern 角色卡。" }
            return Card(root, data)
        }

        private fun parseObject(bytes: ByteArray, stored: Boolean = false): JsonObject {
            // Imported entries can gain enable/disable fields over their original upload size.
            val limit = if (stored) MAX_JSON_BYTES + 128 * 1024 else MAX_JSON_BYTES
            require(bytes.size in 1..limit) { "SillyTavern JSON 数据过大或为空。" }
            val parsed = try {
                Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF"))
            } catch (_: Exception) {
                throw IllegalArgumentException("SillyTavern 文件须包含有效的 UTF-8 JSON。")
            }
            return requireNotNull(parsed as? JsonObject) { "SillyTavern 数据须为 JSON 对象。" }
        }

        /** PNG tEXt keywords follow SillyTavern's ccv3-before-chara convention. */
        internal fun readPngCard(bytes: ByteArray): ByteArray {
            require(bytes.size in 20..MAX_BYTES && pngSignature.indices.all { bytes[it] == pngSignature[it] }) {
                "这不是有效的 PNG 角色卡。"
            }
            var offset = pngSignature.size
            val chunks = mutableMapOf<String, String>()
            var ended = false
            var hasImageData = false
            while (offset <= bytes.size - 12) {
                val length = uint32(bytes, offset)
                require(length <= bytes.size - offset - 12) { "PNG 角色卡的数据块已截断。" }
                val start = offset + 8
                val end = start + length.toInt()
                val type = bytes.decodeToString(offset + 4, start)
                require(offset != pngSignature.size || (type == "IHDR" && length == 13L)) {
                    "PNG 角色卡缺少有效的图像头。"
                }
                if (offset == pngSignature.size) {
                    val width = uint32(bytes, start)
                    val height = uint32(bytes, start + 4)
                    require(width in 1..4096 && height in 1..4096 && width * height <= 8_000_000) {
                        "PNG 角色卡的图片尺寸过大。"
                    }
                }
                when (type) {
                    "IDAT" -> if (length > 0) hasImageData = true
                    "tEXt" -> {
                        val separator = (start until minOf(end, start + 80)).firstOrNull { bytes[it] == 0.toByte() }
                        if (separator != null) {
                            val key = bytes.decodeToString(start, separator).lowercase()
                            if ((key == "ccv3" || key == "chara") && key !in chunks) {
                                require(end - separator - 1 <= 2 * MAX_JSON_BYTES) { "PNG 角色卡的元数据过大。" }
                                require(crc32(bytes, offset + 4, end) == uint32(bytes, end)) {
                                    "PNG 角色卡的元数据校验失败。"
                                }
                                chunks[key] = bytes.decodeToString(separator + 1, end)
                            }
                        }
                    }
                    "IEND" -> {
                        require(length == 0L && end + 4 == bytes.size) { "PNG 角色卡的结束块无效。" }
                        ended = true
                        break
                    }
                }
                offset = end + 4
            }
            require(ended && hasImageData) { "PNG 角色卡缺少图像数据或结束块。" }
            val encoded = chunks["ccv3"] ?: chunks["chara"]
                ?: throw IllegalArgumentException("PNG 中没有 SillyTavern 角色卡数据。")
            return runCatching { base64Decode(encoded) }
                .getOrElse { throw IllegalArgumentException("PNG 角色卡中的 Base64 数据无效。") }
        }

        private fun uint32(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            repeat(4) { value = (value shl 8) or (bytes[offset + it].toLong() and 255) }
            return value
        }

        private fun crc32(bytes: ByteArray, start: Int, end: Int): Long {
            var crc = UInt.MAX_VALUE
            for (index in start until end) {
                crc = crc xor bytes[index].toUByte().toUInt()
                repeat(8) {
                    crc = (crc shr 1) xor if (crc and 1u != 0u) 0xedb88320u else 0u
                }
            }
            return crc.inv().toLong()
        }

        internal fun cardText(text: String, name: String, limit: Int, user: String = "你"): String =
            expandSillyTavernNames(text.take(limit), name, user).take(limit)

        internal fun cardGreetings(card: Card): CardGreetingsDto {
            val alternatives = when (val raw = card.data["alternate_greetings"]) {
                is JsonArray -> raw
                is JsonPrimitive -> listOf(raw)
                else -> emptyList()
            }
            val options = buildList {
                card.data.string("first_mes").takeIf(String::isNotBlank)?.let { add(CardGreetingDto(0, it.take(4000))) }
                alternatives.take(20).forEachIndexed { index, value ->
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                    if (text.isNotBlank()) add(CardGreetingDto(index + 1, text.take(4000)))
                }
            }
            return CardGreetingsDto(card.data.string("name"), options)
        }

        internal fun greeting(card: Card, character: String, user: String, index: Int = 0): String {
            val options = cardGreetings(card)
            if (options.character != character) return ""
            val text = options.options.firstOrNull { it.index == index }?.text.orEmpty()
            return cardText(text, character, 4000, user)
        }

        internal fun embeddedBook(card: Card, participants: List<String>): JsonObject? =
            (card.data["character_book"] as? JsonObject)?.takeIf { card.data.string("name") in participants }

        internal fun loreEntries(book: JsonObject): List<JsonObject> {
            val entries = when (val value = book["entries"]) {
                is JsonArray -> value
                is JsonObject -> value.values
                else -> throw IllegalArgumentException("世界书缺少有效的 entries。")
            }
            require(entries.size <= MAX_ENTRIES && entries.all { it is JsonObject }) { "世界书条目无效或超过 2000 条。" }
            return entries.filterIsInstance<JsonObject>()
        }

        internal fun evaluateLore(
            entries: List<JsonObject>,
            messages: List<String>,
            embeddedCount: Int = 0,
            availableSlots: Int = 4,
            embeddedBook: JsonObject? = null,
            standaloneBook: JsonObject? = null,
            collectDiagnostics: Boolean = true,
        ): List<WorldBookMatchDto> {
            val slots = availableSlots.coerceIn(0, 4)
            if (!collectDiagnostics && slots == 0) return emptyList()
            val scan = messages.takeLast(MAX_SCAN_DEPTH).map { it.takeLast(2000) }
            val injected = mutableSetOf<String>()
            val evaluated = entries.asSequence().withIndex()
                .sortedByDescending { (_, entry) -> entry.number("order") ?: entry.number("insertion_order") ?: 0 }
                .map { (index, entry) ->
                    val book = if (index < embeddedCount) embeddedBook else standaloneBook
                    val extensions = entry["extensions"] as? JsonObject
                    val sensitive = entry.flag("caseSensitive") ?: extensions?.flag("case_sensitive") ?: false
                    val wholeWords = entry.flag("matchWholeWords") ?: extensions?.flag("match_whole_words")
                        ?: book?.flag("match_whole_words") ?: false
                    val depth = scanDepth(entry, extensions) ?: book?.number("scan_depth") ?: DEFAULT_SCAN_DEPTH
                    val start = (scan.size - depth.coerceIn(0, MAX_SCAN_DEPTH)).coerceAtLeast(0)
                    fun matches(key: String): Boolean {
                        if (key.startsWith("/")) return false
                        val useBoundaries = wholeWords && key.none(Char::isWhitespace)
                        return (start until scan.size).any { matchesLiteral(scan[it], key, sensitive, useBoundaries) }
                    }
                    val primary = entry.keys("key", "keys")
                    val secondary = entry.keys("keysecondary", "secondary_keys")
                    val excerpt = entry.string("content").take(240)
                    val constant = entry.flag("constant") == true
                    val disabled = entry.flag("disable") == true || entry.flag("enabled") == false
                    val primaryHits = if (disabled || constant || excerpt.isBlank()) emptyList() else primary.filter(::matches)
                    val useSecondary = entry.flag("selective") != false && secondary.isNotEmpty()
                    val secondaryHits = if (useSecondary && primaryHits.isNotEmpty()) secondary.filter(::matches) else emptyList()
                    val passesSecondary = !useSecondary || when (entry.number("selectiveLogic") ?: extensions?.number("selectiveLogic")) {
                        1 -> secondaryHits.size < secondary.size
                        2 -> secondaryHits.isEmpty()
                        3 -> secondaryHits.size == secondary.size
                        else -> secondaryHits.isNotEmpty()
                    }
                    val status = when {
                        disabled -> "disabled"
                        excerpt.isBlank() -> "empty"
                        !constant && primaryHits.isEmpty() -> "no_keyword"
                        !constant && !passesSecondary -> "secondary"
                        excerpt in injected -> "duplicate"
                        injected.size >= slots -> "budget"
                        else -> {
                            injected.add(excerpt)
                            "injected"
                        }
                    }
                    WorldBookMatchDto(
                        title = entryTitle(entry, index),
                        excerpt = excerpt,
                        status = status,
                        keywords = if (constant) emptyList() else (primaryHits + secondaryHits).distinct().take(8),
                        source = if (index < embeddedCount) CHARACTER_SOURCE else STANDALONE_SOURCE,
                    )
                }
            return if (collectDiagnostics) evaluated.toList() else evaluated.filter { it.status == "injected" }.take(slots).toList()
        }

        internal fun evaluateBooks(
            embedded: JsonObject?,
            standalone: JsonObject?,
            messages: List<String>,
            availableSlots: Int = 4,
            collectDiagnostics: Boolean = true,
        ): List<WorldBookMatchDto> {
            val embeddedEntries = embedded?.let(::loreEntries).orEmpty()
            val standaloneEntries = standalone?.let(::loreEntries).orEmpty()
            return evaluateLore(
                embeddedEntries + standaloneEntries, messages, embeddedEntries.size, availableSlots,
                embedded, standalone, collectDiagnostics,
            )
        }

        private fun scanDepth(entry: JsonObject, extensions: JsonObject?): Int? =
            entry.number("scanDepth") ?: extensions?.number("scan_depth")

        // SillyTavern applies JavaScript-style word boundaries to single-word literal keys.
        // Phrases with spaces still use a substring match.
        private fun matchesLiteral(text: String, key: String, sensitive: Boolean, wholeWords: Boolean): Boolean {
            if (!wholeWords) return text.contains(key, ignoreCase = !sensitive)
            var start = text.indexOf(key, ignoreCase = !sensitive)
            while (start >= 0) {
                val end = start + key.length
                if ((start == 0 || !text[start - 1].isWordChar()) && (end == text.length || !text[end].isWordChar())) return true
                start = text.indexOf(key, start + 1, ignoreCase = !sensitive)
            }
            return false
        }

        private fun Char.isWordChar(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_'

        private fun entryTitle(entry: JsonObject, index: Int): String =
            entry.string("comment").ifBlank {
                (entry.keys("key", "keys") + entry.keys("keysecondary", "secondary_keys")).firstOrNull().orEmpty()
            }.ifBlank { "条目 ${index + 1}" }.take(120)

        private fun prioritizeTestResults(entries: List<WorldBookMatchDto>): List<WorldBookMatchDto> =
            entries.sortedBy { when (it.status) { "injected" -> 0; "budget" -> 1; else -> 2 } }.take(50)

        private fun unsupportedRules(entries: List<JsonObject>, book: JsonObject?, card: JsonObject?): List<String> = buildList {
            if (entries.any { entry -> (entry.keys("key", "keys") + entry.keys("keysecondary", "secondary_keys")).any { it.startsWith('/') } }) {
                add("正则关键词")
            }
            fun anyEntry(predicate: (JsonObject, JsonObject) -> Boolean) =
                entries.any { predicate(it, (it["extensions"] as? JsonObject) ?: JsonObject(emptyMap())) }
            if (book?.flag("recursive_scanning") == true ||
                anyEntry { entry, ext -> entry.flag("delayUntilRecursion") == true || ext.flag("delay_until_recursion") == true }
            ) add("递归触发")
            if (anyEntry { entry, ext -> entry["position"] is JsonPrimitive || ext.number("position") != null }) add("插入位置")
            if ((book?.number("scan_depth") ?: 0) > MAX_SCAN_DEPTH ||
                anyEntry { entry, ext -> (scanDepth(entry, ext) ?: 0) > MAX_SCAN_DEPTH }
            ) add("超过 ${MAX_SCAN_DEPTH} 条的扫描范围")
            if (anyEntry { entry, ext ->
                    (entry.flag("useProbability") ?: ext.flag("useProbability")) == true &&
                        ((entry.number("probability") ?: ext.number("probability")) ?: 100) < 100
                }
            ) add("概率触发")
            if (anyEntry { entry, ext ->
                    entry.string("group").isNotBlank() || ext.string("group").isNotBlank() ||
                        listOf("sticky", "cooldown", "delay").any { (entry.number(it) ?: ext.number(it) ?: 0) > 0 }
                }
            ) add("分组、延迟或持续规则")
            if (card != null && (card.string("system_prompt").isNotBlank() || card.string("post_history_instructions").isNotBlank())) {
                add("角色卡自带的系统提示词")
            }
        }
    }
}

internal class WorldBookChangedException : IllegalStateException("世界书已变化，请刷新后重试。")

private fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty().trim()

private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.keys(native: String, embedded: String): List<String> =
    ((get(native) ?: get(embedded)) as? JsonArray).orEmpty().take(32)
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.trim() }
        .filter { it.length in 1..120 }
