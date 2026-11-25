package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString
import top.wkbin.zaomeng.data.api.ManagedWorldBookDto
import top.wkbin.zaomeng.data.api.EditWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.CreateWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.CardGreetingsDto
import top.wkbin.zaomeng.data.api.CARD_GREETING_CHAR_LIMIT
import top.wkbin.zaomeng.data.api.UpdateCardGreetingRequest
import top.wkbin.zaomeng.data.api.CardGreetingDto
import top.wkbin.zaomeng.data.api.expandSillyTavernNames
import top.wkbin.zaomeng.data.api.ManagedWorldBookEntryDto
import top.wkbin.zaomeng.data.api.ManagedWorldBooksDto
import top.wkbin.zaomeng.data.api.SillyTavernPreviewDto
import top.wkbin.zaomeng.data.api.WorldBookMatchDto
import top.wkbin.zaomeng.data.api.WORLD_BOOK_CONTENT_CHAR_LIMIT
import top.wkbin.zaomeng.data.api.WorldBookGroupSettings
import top.wkbin.zaomeng.data.api.WorldBookGenerationType
import top.wkbin.zaomeng.data.api.WorldBookKeywordMatching
import top.wkbin.zaomeng.data.api.WorldBookSecondaryLogic
import top.wkbin.zaomeng.data.api.WorldBookScanSource
import top.wkbin.zaomeng.data.api.SillyTavernCardMetadataDto
import top.wkbin.zaomeng.data.api.SillyTavernCharacterNoteDto
import top.wkbin.zaomeng.data.api.CARD_CHARACTER_NOTE_LIMIT
import top.wkbin.zaomeng.data.api.CARD_CHARACTER_NOTE_MAX_DEPTH
import top.wkbin.zaomeng.data.api.UpdateSillyTavernCardMetadataRequest
import top.wkbin.zaomeng.data.api.CARD_METADATA_LABEL_LIMIT
import top.wkbin.zaomeng.data.api.CARD_CREATOR_NOTES_LIMIT
import top.wkbin.zaomeng.data.api.CARD_TAG_LIMIT
import top.wkbin.zaomeng.data.api.CARD_TAG_CHAR_LIMIT
import top.wkbin.zaomeng.data.api.WorldBookTestContext
import top.wkbin.zaomeng.data.api.WorldBookInjectionPosition
import top.wkbin.zaomeng.data.api.WorldBookInjectionRole
import top.wkbin.zaomeng.data.api.WorldBookEntryPlacement
import top.wkbin.zaomeng.data.api.isValidSillyTavernOutletName
import top.wkbin.zaomeng.data.api.sillyTavernExampleBlocks
import top.wkbin.zaomeng.data.api.WorldBookTestDto
import top.wkbin.zaomeng.platform.SillyTavernPng
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid
import kotlin.random.Random

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
                put("entrypoint", CARD_ENTRYPOINT)
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

    fun importWorldBook(runId: String, bytes: ByteArray, expectedRevision: String?): JsonObject {
        val book = parseObject(bytes)
        loreEntries(book)
        return storage.updateRunManifest(runId) { manifest ->
            if (expectedRevision != null && readAttachedBook(runId, STANDALONE_SOURCE)?.revision.orEmpty() != expectedRevision) {
                throw WorldBookChangedException()
            }
            storage.writeTextAtomically(storage.getRunDirectory(runId) / WORLD_BOOK, book.toString())
            markLoreChanged(manifest)
        }
    }

    fun listWorldBooks(runId: String): ManagedWorldBooksDto = ManagedWorldBooksDto(readAttachedBooks(runId).map { current ->
        ManagedWorldBookDto(
            source = current.source,
            name = current.book.string("name").take(120),
            revision = current.revision,
            recursionEnabled = current.book.isRecursionEnabled(),
            entries = loreEntries(current.book).mapIndexed { index, entry ->
                val embedded = current.source == CHARACTER_SOURCE
                val position = entry.lorePlacementField("position", embedded).supportedPosition()
                val triggers = entry.generationTriggers()
                val knownTriggers = WorldBookGenerationType.entries.filter { it.sillyTavernId in triggers }
                ManagedWorldBookEntryDto(
                    index = index,
                    title = entryTitle(entry, index),
                    content = entry.string("content"),
                    enabled = entry.flag("disable") != true && entry.flag("enabled") != false,
                    constant = entry.flag("constant") == true,
                    keywords = entry.keys("key", "keys"),
                    secondaryKeywords = entry.keys("keysecondary", "secondary_keys"),
                    comment = entry.string("comment").take(120),
                    position = position,
                    injectionDepth = entry.injectionDepth(embedded, position),
                    injectionRole = entry.injectionRole(embedded, position),
                    outletName = entry.outletName(embedded, position),
                    activationDelay = entry.activationDelay(),
                    sticky = entry.timingValue("sticky"),
                    cooldown = entry.timingValue("cooldown"),
                    group = entry.groupSettings(),
                    triggers = knownTriggers,
                    hasUnknownTriggers = triggers.distinct().size > knownTriggers.size,
                    matching = entry.keywordMatching(),
                    ignoreBudget = entry.ignoresBudget(),
                )
            },
        )
    })

    fun exportWorldBook(runId: String, source: String): JsonObject {
        if (storage.readRunManifest(runId) == null) throw NoSuchElementException("书卷不存在。")
        val book = readAttachedBook(runId, source)?.book ?: throw NoSuchElementException("世界书不存在。")
        return SillyTavernExport.worldBook(book, embedded = source == CHARACTER_SOURCE)
    }

    fun testWorldBooks(
        runId: String,
        text: String,
        messageCount: Int = 1,
        generationType: WorldBookGenerationType = WorldBookGenerationType.Normal,
        history: List<String> = emptyList(),
        context: WorldBookTestContext = WorldBookTestContext(),
    ): WorldBookTestDto {
        require(text.isNotBlank() && text.length <= 2000) { "请输入 1 至 2000 字符的测试台词。" }
        require(messageCount > 0) { "测试消息数须为正整数。" }
        require(history.size < MAX_SCAN_DEPTH && history.all { it.isNotBlank() && it.length <= 2000 }) {
            "最多可测试 ${MAX_SCAN_DEPTH - 1} 条历史消息，每条须为 1 至 2000 个字符。"
        }
        require(context.personaDescription.length <= 4000 && context.scenario.length <= 4000) {
            "测试人设和场景各最多 4000 个字符。"
        }
        val books = readAttachedBooks(runId)
        val embedded = books.firstOrNull { it.source == CHARACTER_SOURCE }?.book
        val standalone = books.firstOrNull { it.source == STANDALONE_SOURCE }?.book
        val messages = history + text
        val extraScanContext = if (usesScanSources(embedded, standalone)) {
            val card = books.firstNotNullOfOrNull { it.card } ?: readScanCard(runId)
            scanContext(card, personaDescription = context.personaDescription, scenario = context.scenario)
        } else emptyMap()
        val entries = evaluateBooks(
            embedded, standalone, messages, messageCount = maxOf(messageCount, messages.size), generationType = generationType,
            scanContext = extraScanContext,
        )
        return WorldBookTestDto(
            entries = prioritizeTestResults(entries),
            entryCount = entries.size,
            injectedCount = entries.count { it.status == "injected" },
        )
    }

    fun setEntryEnabled(runId: String, source: String, index: Int, revision: String, enabled: Boolean): ManagedWorldBooksDto =
        updateEntry(runId, source, index, revision) { entry ->
            buildJsonObject {
                entry.forEach { (key, value) -> put(key, value) }
                if (source == STANDALONE_SOURCE || "disable" in entry) put("disable", !enabled)
                if (source == CHARACTER_SOURCE || "enabled" in entry) put("enabled", enabled)
            }
        }

    fun createBookEntry(runId: String, source: String, request: CreateWorldBookEntryRequest): ManagedWorldBooksDto {
        val comment = validatedComment(request.comment)
        val content = validatedContent(request.content)
        val keywords = validatedEditKeywords(request.keywords)
        val secondaryKeywords = validatedEditKeywords(request.secondaryKeywords)
        val placement = request.placement?.let(::validatedPlacement)
        val timing = validatedTiming(request.activationDelay, request.sticky, request.cooldown)
        val group = request.group?.let(::validatedGroup)
        require(request.constant || keywords.isNotEmpty()) { "请至少填写一个主关键词。" }
        return updateAttachedBook(runId, source, request.revision) { book ->
            val entries = loreEntries(book)
            require(entries.size < MAX_ENTRIES) { "每本世界书最多 2000 条。" }
            val original = book["entries"] as? JsonObject
            val usedIds = buildSet {
                original?.keys?.mapNotNullTo(this, String::toIntOrNull)
                entries.mapNotNullTo(this) { it.number("uid") }
                entries.mapNotNullTo(this) { it.number("id") }
            }
            // Leave implicit IDs used by existing exports available.
            var id = entries.size
            while (id in usedIds) id++
            val defaults = buildJsonObject {
                put("comment", comment)
                put("content", content)
                put("constant", request.constant)
                put("selective", true)
                if (source == STANDALONE_SOURCE || original != null) {
                    put("uid", id)
                    put("key", keywords)
                    put("keysecondary", secondaryKeywords)
                    put("disable", false)
                    put("order", 100)
                    put("position", 0)
                } else {
                    put("id", id)
                    put("keys", keywords)
                    put("secondary_keys", secondaryKeywords)
                    put("enabled", true)
                    put("insertion_order", 100)
                    put("position", "before_char")
                }
            }
            val embedded = source == CHARACTER_SOURCE
            val placed = placement?.let { defaults.withPlacement(it, embedded) } ?: defaults
            val entry = placed.withTiming(timing, embedded).withGroup(group, embedded)
                .withGenerationTriggers(request.triggers, embedded)
                .withKeywordMatching(request.matching, embedded)
                .withIgnoreBudget(request.ignoreBudget, embedded)
            val updatedEntries = if (original == null) JsonArray(entries + entry)
            else JsonObject(original + (id.toString() to entry))
            JsonObject(book + ("entries" to updatedEntries))
        }
    }

    fun editBookEntry(runId: String, source: String, index: Int, request: EditWorldBookEntryRequest): ManagedWorldBooksDto {
        require(
            request.comment != null || request.content != null || request.keywords != null ||
                request.secondaryKeywords != null || request.constant != null || request.placement != null ||
                request.activationDelay != null || request.sticky != null || request.cooldown != null ||
                request.group != null || request.triggers != null || request.matching != null || request.ignoreBudget != null,
        ) {
            "请至少修改一项。"
        }
        val comment = request.comment?.let(::validatedComment)
        val content = request.content?.let(::validatedContent)
        val keywords = request.keywords?.let(::validatedEditKeywords)
        val secondaryKeywords = request.secondaryKeywords?.let(::validatedEditKeywords)
        val placement = request.placement?.let(::validatedPlacement)
        val timing = validatedTiming(request.activationDelay, request.sticky, request.cooldown)
        val group = request.group?.let(::validatedGroup)
        return updateEntry(runId, source, index, request.revision) { entry ->
            if (request.constant == false && entry.flag("constant") == true) {
                val hasKeywords = keywords?.isNotEmpty() ?: entry.keys("key", "keys").isNotEmpty()
                require(hasKeywords) { "切换为关键词触发时，请至少填写一个主关键词。" }
            }
            val updated = entry.toMutableMap()
            comment?.let { updated["comment"] = JsonPrimitive(it) }
            content?.let { updated["content"] = JsonPrimitive(it) }
            request.constant?.let { updated["constant"] = JsonPrimitive(it) }
            fun writeKeywords(native: String, embedded: String, values: JsonArray) {
                if (source == STANDALONE_SOURCE || native in entry) updated[native] = values
                if (source == CHARACTER_SOURCE || embedded in entry) updated[embedded] = values
            }
            keywords?.let { writeKeywords("key", "keys", it) }
            secondaryKeywords?.let { writeKeywords("keysecondary", "secondary_keys", it) }
            val result = JsonObject(updated)
            val embedded = source == CHARACTER_SOURCE
            val placed = placement?.let { result.withPlacement(it, embedded) } ?: result
            placed.withTiming(timing, embedded).withGroup(group, embedded)
                .withGenerationTriggers(request.triggers, embedded)
                .withKeywordMatching(request.matching, embedded)
                .withIgnoreBudget(request.ignoreBudget, embedded)
        }
    }

    private fun JsonObject.withIgnoreBudget(ignoreBudget: Boolean?, embedded: Boolean): JsonObject {
        if (ignoreBudget == null) return this
        val value = JsonPrimitive(ignoreBudget)
        val updated = toMutableMap()
        val extensions = (get("extensions") as? JsonObject).orEmpty()
        if (!embedded || "ignoreBudget" in this) updated["ignoreBudget"] = value
        if (embedded || "ignore_budget" in extensions) {
            updated["extensions"] = JsonObject(extensions + ("ignore_budget" to value))
        }
        return JsonObject(updated)
    }

    private fun JsonObject.withKeywordMatching(matching: WorldBookKeywordMatching?, embedded: Boolean): JsonObject {
        if (matching == null) return this
        require((matching.scanDepth ?: 0) >= 0) { "扫描深度须为非负整数。" }
        val values = listOf(
            Triple("scanDepth", "scan_depth", matching.scanDepth?.let(::JsonPrimitive) ?: JsonNull),
            Triple("caseSensitive", "case_sensitive", matching.caseSensitive?.let(::JsonPrimitive) ?: JsonNull),
            Triple("matchWholeWords", "match_whole_words", matching.wholeWords?.let(::JsonPrimitive) ?: JsonNull),
            Triple("selectiveLogic", "selectiveLogic", JsonPrimitive(matching.secondaryLogic.sillyTavernId)),
        )
        val updated = toMutableMap()
        val extensions = (get("extensions") as? JsonObject).orEmpty().toMutableMap()
        updated["selective"] = JsonPrimitive(matching.useSecondary)
        for ((native, nested, value) in values) {
            if (!embedded || native in this) updated[native] = value
            if (embedded || nested in extensions) extensions[nested] = value
        }
        for (source in WorldBookScanSource.entries) {
            val active = source in matching.sources
            if (source.nativeField in this || !embedded && active) updated[source.nativeField] = JsonPrimitive(active)
            if (source.embeddedField in extensions || embedded && active) extensions[source.embeddedField] = JsonPrimitive(active)
        }
        if (extensions.isNotEmpty()) updated["extensions"] = JsonObject(extensions)
        return JsonObject(updated)
    }

    private fun JsonObject.withGenerationTriggers(
        triggers: List<WorldBookGenerationType>?,
        embedded: Boolean,
    ): JsonObject {
        if (triggers == null) return this
        val value = JsonArray(triggers.distinct().map { JsonPrimitive(it.sillyTavernId) })
        val extensions = this["extensions"] as? JsonObject
        val updated = toMutableMap()
        if (!embedded || "triggers" in this) updated["triggers"] = value
        if (embedded || extensions?.containsKey("triggers") == true) {
            updated["extensions"] = JsonObject(extensions.orEmpty() + ("triggers" to value))
        }
        return JsonObject(updated)
    }

    private fun validatedGroup(group: WorldBookGroupSettings): WorldBookGroupSettings {
        require(group.names.length <= 512 && group.names.none(Char::isISOControl)) { "分组名称须为一行且不超过 512 个字符。" }
        require(group.weight in 0..10_000) { "分组权重须为 0 至 10000 的整数。" }
        return group.copy(names = groupNames(group.names).joinToString(", "))
    }

    private fun JsonObject.withGroup(group: WorldBookGroupSettings?, embedded: Boolean): JsonObject {
        if (group == null) return this
        val values = listOf(
            Triple("group", "group", JsonPrimitive(group.names)),
            Triple("groupOverride", "group_override", JsonPrimitive(group.priority)),
            Triple("groupWeight", "group_weight", JsonPrimitive(group.weight)),
            Triple("useGroupScoring", "use_group_scoring", group.scoring?.let(::JsonPrimitive) ?: JsonNull),
        )
        val extensions = (get("extensions") as? JsonObject).orEmpty()
        val updated = toMutableMap()
        val updatedExtensions = extensions.toMutableMap()
        for ((native, nested, value) in values) {
            if (!embedded || native in this) updated[native] = value
            if (embedded || nested in extensions) updatedExtensions[nested] = value
        }
        if (updatedExtensions.isNotEmpty()) updated["extensions"] = JsonObject(updatedExtensions)
        return JsonObject(updated)
    }

    private fun validatedTiming(delay: Int?, sticky: Int?, cooldown: Int?): Map<String, JsonPrimitive> = buildMap {
        for ((key, value) in listOf("delay" to delay, "sticky" to sticky, "cooldown" to cooldown)) {
            if (value == null) continue
            require(value >= 0) { "计时设置须为非负整数。" }
            put(key, JsonPrimitive(value))
        }
    }

    private fun JsonObject.withTiming(timing: Map<String, JsonPrimitive>, embedded: Boolean): JsonObject {
        if (timing.isEmpty()) return this
        val extensions = this["extensions"] as? JsonObject
        val native = timing.filterKeys { !embedded || it in this }
        val nested = timing.filterKeys { embedded || extensions?.containsKey(it) == true }
        return buildJsonObject {
            this@withTiming.forEach { (key, value) -> put(key, value) }
            native.forEach { (key, value) -> put(key, value) }
            if (nested.isNotEmpty()) put("extensions", JsonObject(extensions.orEmpty() + nested))
        }
    }

    private fun validatedPlacement(placement: WorldBookEntryPlacement): WorldBookEntryPlacement {
        if (placement.position == WorldBookInjectionPosition.Outlet) {
            require(placement.depth == null && placement.role == null) { "命名出口不能同时设置深度或角色。" }
            val name = placement.outletName.orEmpty().trim()
            require(name.isValidSillyTavernOutletName()) { "出口名称须为 1 至 120 个字符，且不能包含花括号或换行。" }
            return placement.copy(outletName = name)
        }
        require(placement.outletName == null) { "只有命名出口位置可设置出口名称。" }
        if (placement.position != WorldBookInjectionPosition.AtDepth) {
            require(placement.depth == null && placement.role == null) { "只有聊天深度位置可以设置深度和角色。" }
            return placement
        }
        val depth = placement.depth ?: 4
        require(depth in 0..10_000) { "聊天深度须为 0 至 10000 的整数。" }
        return placement.copy(depth = depth, role = placement.role ?: WorldBookInjectionRole.System)
    }

    private fun JsonObject.withPlacement(placement: WorldBookEntryPlacement, embedded: Boolean): JsonObject {
        val position = placement.position.sillyTavernId
        val originalExtensions = this["extensions"] as? JsonObject
        return buildJsonObject {
            this@withPlacement.forEach { (key, value) -> put(key, value) }
            val oldPosition = this@withPlacement["position"] as? JsonPrimitive
            if (embedded && oldPosition?.intOrNull == null) {
                put("position", if (position == 0) "before_char" else "after_char")
            } else {
                put("position", position)
            }
            placement.depth?.let { if (!embedded || "depth" in this@withPlacement) put("depth", it) }
            placement.role?.let { if (!embedded || "role" in this@withPlacement) put("role", it.sillyTavernId) }
            placement.outletName?.let { if (!embedded || "outletName" in this@withPlacement) put("outletName", it) }
            if (embedded || originalExtensions?.containsKey("position") == true) {
                put("extensions", buildJsonObject {
                    originalExtensions?.forEach { (key, value) -> put(key, value) }
                    put("position", position)
                    placement.depth?.let { put("depth", it) }
                    placement.role?.let { put("role", it.sillyTavernId) }
                    placement.outletName?.let { put("outletName", it) }
                })
            }
        }
    }

    private fun validatedComment(value: String): String =
        value.trim().also { require(it.length <= 120) { "标题不能超过 120 字符。" } }

    private fun validatedContent(value: String): String =
        value.also { require(it.isNotBlank() && it.length <= 16_000) { "正文须为 1 至 16000 字符。" } }

    private fun validatedEditKeywords(values: List<String>): JsonArray {
        val keys = values.map(String::trim)
        require(keys.size <= 32 && keys.all { it.length in 1..120 }) { "每组最多 32 个关键词，每个须为 1 至 120 字符。" }
        return JsonArray(keys.map(::JsonPrimitive))
    }

    fun deleteBookEntry(runId: String, source: String, index: Int, revision: String): ManagedWorldBooksDto =
        updateEntry(runId, source, index, revision) { null }

    private fun updateEntry(
        runId: String,
        source: String,
        index: Int,
        revision: String,
        transform: (JsonObject) -> JsonObject?,
    ): ManagedWorldBooksDto = updateAttachedBook(runId, source, revision) { book ->
        val entries = loreEntries(book)
        val entry = entries.getOrNull(index) ?: throw NoSuchElementException("世界书条目不存在。")
        val updated = transform(entry)
        JsonObject(book + ("entries" to when (val original = book["entries"]) {
            is JsonObject -> {
                val key = original.keys.elementAt(index)
                JsonObject(if (updated == null) original - key else original + (key to updated))
            }
            else -> JsonArray(entries.mapIndexedNotNull { i, value -> if (i == index) updated else value })
        }))
    }

    fun setBookRecursion(runId: String, source: String, revision: String, enabled: Boolean): ManagedWorldBooksDto =
        updateAttachedBook(runId, source, revision) { book ->
            if (book.isRecursionEnabled() == enabled) book
            else JsonObject(book - "recursiveScanning" + ("recursive_scanning" to JsonPrimitive(enabled)))
        }

    private fun updateAttachedBook(
        runId: String,
        source: String,
        revision: String,
        transform: (JsonObject) -> JsonObject,
    ): ManagedWorldBooksDto {
        storage.updateRunManifest(runId) { manifest ->
            val current = readAttachedBook(runId, source) ?: throw NoSuchElementException("世界书不存在。")
            current.requireRevision(revision)
            val book = transform(current.book)
            if (book == current.book) return@updateRunManifest manifest
            val card = current.card
            val root = if (card == null) book else {
                val data = JsonObject(card.data + ("character_book" to book))
                if (card.data === card.original) data else JsonObject(card.original + ("data" to data))
            }
            val bytes = root.toString().encodeToByteArray()
            require(bytes.size <= MAX_STORED_JSON_BYTES) { "保存后文件大小将超过上限。" }
            storage.writeBytesAtomically(storage.getRunDirectory(runId) / sourceFile(source), bytes)
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

    private fun readScanCard(runId: String): Card? =
        storage.readTextOrNull(storage.getRunDirectory(runId) / CARD_FILE)?.let {
            runCatching { parseCard(it.encodeToByteArray(), stored = true) }.getOrNull()
        }

    fun getCardGreetings(runId: String): CardGreetingsDto {
        if (storage.readRunManifest(runId) == null) throw NoSuchElementException("书卷不存在。")
        val bytes = storage.readTextOrNull(storage.getRunDirectory(runId) / CARD_FILE)?.encodeToByteArray()
            ?: return CardGreetingsDto()
        val card = runCatching { parseCard(bytes, stored = true) }.getOrNull() ?: return CardGreetingsDto()
        return cardGreetings(card, bytes.toByteString().sha256().hex())
    }

    fun updateCardGreeting(runId: String, request: UpdateCardGreetingRequest): CardGreetingsDto {
        require(request.revision.isNotBlank() && request.index in 0..MAX_ALTERNATE_GREETINGS) { "开场版本或编号无效。" }
        require(request.text.length <= CARD_GREETING_CHAR_LIMIT) { "每条开场最多 4000 字符。" }
        updateStoredCard(runId) { card, bytes ->
            if (bytes.toByteString().sha256().hex() != request.revision) throw CardGreetingChangedException()
            val text = JsonPrimitive(request.text.trim())
            if (request.index == 0) {
                JsonObject(card.data + ("first_mes" to text))
            } else {
                val alternates = alternateGreetings(card).toMutableList()
                val position = request.index - 1
                require(position <= alternates.size) { "备用开场编号无效。" }
                if (position == alternates.size) alternates.add(text) else alternates[position] = text
                JsonObject(card.data + ("alternate_greetings" to JsonArray(alternates)))
            }
        }
        return getCardGreetings(runId)
    }

    fun updateCardMetadata(runId: String, character: String, request: UpdateSillyTavernCardMetadataRequest): SillyTavernCardMetadataDto {
        require(request.revision.isNotBlank()) { "角色卡版本不能为空。" }
        require(listOf(request.creator, request.creatorNotes, request.characterVersion, request.tags, request.characterNote).any { it != null }) {
            "请至少修改一个角色卡字段。"
        }
        require(request.creator.orEmpty().length <= CARD_METADATA_LABEL_LIMIT &&
            request.characterVersion.orEmpty().length <= CARD_METADATA_LABEL_LIMIT
        ) { "作者和版本各最多 120 字符。" }
        require(request.creatorNotes.orEmpty().length <= CARD_CREATOR_NOTES_LIMIT) { "作者说明最多 16000 字符。" }
        require(request.tags.orEmpty().size <= CARD_TAG_LIMIT &&
            request.tags.orEmpty().all { it.trim().length in 1..CARD_TAG_CHAR_LIMIT && '\n' !in it && '\r' !in it }
        ) { "最多填写 32 个标签，每个标签为 1 至 64 个单行字符。" }
        request.characterNote?.let { note ->
            require(note.text.length <= CARD_CHARACTER_NOTE_LIMIT) { "角色注记最多 4000 字符。" }
            require(note.depth in 0..CARD_CHARACTER_NOTE_MAX_DEPTH) { "角色注记深度须为 0 至 10000。" }
        }

        val saved = updateStoredCard(runId) { card, _ ->
            if (card.data.string("name") != character) throw NoSuchElementException("这份书卷没有该角色卡。")
            if (metadataRevision(card) != request.revision) throw CardMetadataChangedException()
            JsonObject(card.data.toMutableMap().apply {
                request.creator?.let { put("creator", JsonPrimitive(it.trim())) }
                request.creatorNotes?.let {
                    val notes = JsonPrimitive(it.trim())
                    put("creator_notes", notes)
                    if (containsKey("creatorcomment")) put("creatorcomment", notes)
                }
                request.characterVersion?.let { put("character_version", JsonPrimitive(it.trim())) }
                request.tags?.let { put("tags", JsonArray(it.map(String::trim).distinct().map(::JsonPrimitive))) }
                request.characterNote?.let { note ->
                    val extensions = (card.data["extensions"] as? JsonObject).orEmpty()
                    val updated = JsonObject(characterNote(card.data).orEmpty() + mapOf(
                        "prompt" to JsonPrimitive(note.text.trim()),
                        "depth" to JsonPrimitive(note.depth),
                        "role" to JsonPrimitive(note.role.chatRole),
                    ))
                    put("extensions", JsonObject(extensions + ("depth_prompt" to updated)))
                }
            })
        }
        return cardMetadata(saved)
    }

    private fun updateStoredCard(runId: String, transform: (Card, ByteArray) -> JsonObject): Card {
        lateinit var saved: Card
        storage.updateRunManifest(runId) { manifest ->
            val file = storage.getRunDirectory(runId) / CARD_FILE
            val bytes = storage.readTextOrNull(file)?.encodeToByteArray()
                ?: throw NoSuchElementException("这份书卷没有导入的角色卡。")
            val card = parseCard(bytes, stored = true)
            val data = transform(card, bytes)
            val root = if (card.data === card.original) data else JsonObject(card.original + ("data" to data))
            saved = Card(root, data)
            if (data == card.data) return@updateRunManifest manifest
            val updated = root.toString().encodeToByteArray()
            require(updated.size <= MAX_STORED_JSON_BYTES) { "保存后角色卡大小将超过上限。" }
            storage.writeBytesAtomically(file, updated)
            markLoreChanged(manifest)
        }
        return saved
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
            readAttachedBook(it, STANDALONE_SOURCE)
        }
        val name = card?.data?.string("name").orEmpty()
        val tested = testText?.let {
            val context = if (usesScanSources(book)) scanContext(card ?: targetRunId?.let(::readScanCard)) else emptyMap()
            if (worldBook) evaluateBooks(null, book, listOf(it), scanContext = context)
            else evaluateBooks(book, null, listOf(it), scanContext = context)
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
            previousBookName = previous?.book?.string("name")?.take(120),
            previousEntryCount = previous?.book?.let { loreEntries(it).size },
            previousBookRevision = targetRunId?.let { previous?.revision.orEmpty() },
            unsupportedRules = unsupportedRules(entries, book, embedded = !worldBook),
            testedEntries = tested?.let(::prioritizeTestResults),
            injectedCount = tested?.count { it.status == "injected" } ?: 0,
        )
    }

    companion object {
        const val MAX_BYTES = SillyTavernPng.MAX_FILE_BYTES
        const val CARD_FILE = "sillytavern-card.json"
        private const val CARD_ENTRYPOINT = "sillytavern"
        const val WORLD_BOOK = "sillytavern-world-book.json"
        const val REVISION_FIELD = "sillytavern_lore_revision"
        const val MAX_SCAN_DEPTH = WorldBookKeywordMatching.MAX_SCAN_DEPTH
        private const val MAX_RECURSION_DEPTH = 4
        private val recursionCandidateStatuses = setOf("no_keyword", "secondary", "recursion_only", "group_suppressed")
        private const val DEFAULT_SCAN_DEPTH = 6
        private const val CHARACTER_SOURCE = "character_book"
        private const val STANDALONE_SOURCE = "world_book"
        private const val MAX_JSON_BYTES = SillyTavernPng.MAX_JSON_BYTES
        private const val MAX_STORED_JSON_BYTES = MAX_JSON_BYTES + 128 * 1024
        private const val MAX_ENTRIES = 2000
        private val reservedName = Regex("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]", RegexOption.IGNORE_CASE)


        internal fun restorePackagedCardOrigin(storage: StorageService, manifest: JsonObject): JsonObject {
            if (manifest["entrypoint"] != JsonPrimitive("import")) return manifest
            val runId = (manifest["run_id"] as? JsonPrimitive)?.contentOrNull ?: return manifest
            val directory = try {
                storage.getRunDirectory(runId)
            } catch (_: InvalidStorageIdentifierException) {
                return manifest
            }
            return if (storage.isFile(directory / CARD_FILE)) {
                JsonObject(manifest + ("entrypoint" to JsonPrimitive(CARD_ENTRYPOINT)))
            } else {
                manifest
            }
        }

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
            val limit = if (stored) MAX_STORED_JSON_BYTES else MAX_JSON_BYTES
            require(bytes.size in 1..limit) { "SillyTavern JSON 数据过大或为空。" }
            val parsed = try {
                Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF"))
            } catch (_: Exception) {
                throw IllegalArgumentException("SillyTavern 文件须包含有效的 UTF-8 JSON。")
            }
            return requireNotNull(parsed as? JsonObject) { "SillyTavern 数据须为 JSON 对象。" }
        }

        internal fun readPngCard(bytes: ByteArray): ByteArray = SillyTavernPng.readCard(bytes)

        internal fun cardText(text: String, name: String, limit: Int, user: String = "你"): String =
            expandSillyTavernNames(text.take(limit), name, user).take(limit)

        internal fun cardMetadata(card: Card): SillyTavernCardMetadataDto {
            val data = card.data
            val notes = data.string("creator_notes").ifBlank { data.string("creatorcomment") }
            val characterNote = characterNote(data)
            val characterNoteText = characterNoteText(characterNote)
            val tags = (data["tags"] as? JsonArray).orEmpty().asSequence()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim() }
                .filter(String::isNotBlank)
                .distinct()
                .take(CARD_TAG_LIMIT + 1)
                .toList()
            return SillyTavernCardMetadataDto(
                creator = data.string("creator").take(CARD_METADATA_LABEL_LIMIT),
                creatorNotes = notes.take(CARD_CREATOR_NOTES_LIMIT),
                characterVersion = data.string("character_version").take(CARD_METADATA_LABEL_LIMIT),
                tags = tags.take(CARD_TAG_LIMIT).map { it.take(CARD_TAG_CHAR_LIMIT) }.distinct(),
                creatorNotesTruncated = notes.length > CARD_CREATOR_NOTES_LIMIT,
                tagsTruncated = tags.size > CARD_TAG_LIMIT || tags.any { it.length > CARD_TAG_CHAR_LIMIT || '\r' in it || '\n' in it },
                revision = metadataRevision(card),
                characterNote = SillyTavernCharacterNoteDto(
                    text = characterNoteText.take(CARD_CHARACTER_NOTE_LIMIT),
                    depth = characterNoteDepth(characterNote),
                    role = sillyTavernInjectionRole(characterNote?.get("role")),
                ),
                characterNoteTruncated = characterNoteText.length > CARD_CHARACTER_NOTE_LIMIT,
            )
        }

        private fun metadataRevision(card: Card): String = card.original.toString().encodeToByteArray().toByteString().sha256().hex()

        private const val MAX_ALTERNATE_GREETINGS = 20

        private fun alternateGreetings(card: Card): List<JsonElement> =
            when (val raw = card.data["alternate_greetings"]) {
                is JsonArray -> raw
                is JsonPrimitive -> listOf(raw)
                else -> emptyList()
            }

        internal fun cardGreetings(card: Card, revision: String = ""): CardGreetingsDto {
            val alternatives = alternateGreetings(card)
            fun option(index: Int, text: String) =
                CardGreetingDto(index, text.take(CARD_GREETING_CHAR_LIMIT), text.length > CARD_GREETING_CHAR_LIMIT)
            val options = buildList {
                card.data.string("first_mes").takeIf(String::isNotBlank)?.let { add(option(0, it)) }
                alternatives.take(MAX_ALTERNATE_GREETINGS).forEachIndexed { index, value ->
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                    if (text.isNotBlank()) add(option(index + 1, text))
                }
            }
            val emptySlot = alternatives.take(MAX_ALTERNATE_GREETINGS)
                .indexOfFirst { it is JsonPrimitive && it.isString && it.content.isBlank() }
            val next = if (emptySlot >= 0) emptySlot + 1
            else (alternatives.size + 1).takeIf { it <= MAX_ALTERNATE_GREETINGS }
            return CardGreetingsDto(card.data.string("name"), options, revision, next)
        }

        internal fun greeting(card: Card, character: String, user: String, index: Int = 0): String {
            val options = cardGreetings(card)
            if (options.character != character) return ""
            val text = options.options.firstOrNull { it.index == index }?.text.orEmpty()
            return cardText(text, character, 4000, user)
        }

        internal fun embeddedBook(card: Card, participants: List<String>): JsonObject? =
            (card.data["character_book"] as? JsonObject)?.takeIf { card.data.string("name") in participants }

        internal fun characterNote(data: JsonObject): JsonObject? =
            (data["extensions"] as? JsonObject)?.get("depth_prompt") as? JsonObject

        internal fun characterNoteText(note: JsonObject?): String = note?.string("prompt").orEmpty()

        internal fun characterNoteDepth(note: JsonObject?): Int =
            ((note?.get("depth") as? JsonPrimitive)?.intOrNull ?: 4).coerceIn(0, CARD_CHARACTER_NOTE_MAX_DEPTH)

        internal fun usesScanSources(vararg books: JsonObject?): Boolean =
            books.filterNotNull().any { book ->
                loreEntries(book).any { entry ->
                    val extensions = entry["extensions"] as? JsonObject
                    WorldBookScanSource.entries.any { entry.scansSource(extensions, it) }
                }
            }

        internal fun scanContext(
            card: Card?,
            personaDescription: String = "",
            scenario: String = "",
            characterDescription: String? = null,
            characterPersonality: String? = null,
            cardScenario: String? = null,
            user: String = "你",
        ): Map<WorldBookScanSource, String> {
            val data = card?.data
            val depth = data?.let(::characterNote)
            val name = data?.string("name").orEmpty().ifBlank { "{{char}}" }
            return mapOf(
                WorldBookScanSource.PersonaDescription to personaDescription,
                WorldBookScanSource.CharacterDescription to (characterDescription ?: data?.string("description").orEmpty()),
                WorldBookScanSource.CharacterPersonality to (characterPersonality ?: data?.string("personality").orEmpty()),
                WorldBookScanSource.CharacterDepthPrompt to characterNoteText(depth),
                WorldBookScanSource.Scenario to scenario.ifBlank { cardScenario ?: data?.string("scenario").orEmpty() },
                WorldBookScanSource.CreatorNotes to data?.string("creator_notes").orEmpty(),
            ).filterValues(String::isNotBlank).mapValues { (_, text) -> cardText(text, name, 4000, user) }
        }

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
            probabilitySeed: String? = null,
            messageCount: Int = messages.size,
            timing: WorldBookTiming? = null,
            generationType: WorldBookGenerationType = WorldBookGenerationType.Normal,
            scanContext: Map<WorldBookScanSource, String> = emptyMap(),
        ): List<WorldBookMatchDto> {
            val slots = availableSlots.coerceIn(0, 4)
            val scan = messages.takeLast(MAX_SCAN_DEPTH).map { it.takeLast(2000) }
            val ordered = entries.mapIndexed { index, entry ->
                val embedded = index < embeddedCount
                LoreCandidate(
                    index, entry,
                    if (embedded) embeddedBook else standaloneBook,
                    if (embedded) CHARACTER_SOURCE else STANDALONE_SOURCE,
                    if (embedded) index else index - embeddedCount,
                    timing,
                )
            }.sortedWith(compareByDescending<LoreCandidate> { it.stickyActive }.thenByDescending { it.insertionOrder })
            val hasExemptEntries = ordered.any { it.ignoreBudget && !it.disabled }
            if (!collectDiagnostics && slots == 0 && !hasExemptEntries) return emptyList()
            val results = arrayOfNulls<WorldBookMatchDto>(entries.size)
            val selected = mutableListOf<WorldBookMatchDto>()
            val injected = mutableSetOf<Pair<String?, String>>()
            val recursiveText = mutableListOf<String>()
            val occupiedGroups = mutableSetOf<String>()
            val hasGroups = ordered.any { it.groups.isNotEmpty() }
            var ordinarySelected = 0
            var remainingChars = WORLD_BOOK_CONTENT_CHAR_LIMIT
            fun hasSelectionRoom(): Boolean =
                remainingChars > 0 && selected.size < DialoguePromptRules.WORLD_FACT_LIMIT &&
                    (ordinarySelected < slots || hasExemptEntries)

            fun scanPass(depth: Int, level: Int): List<String> {
                val nextText = mutableListOf<String>()
                val matching = ordered.asSequence().filter { candidate ->
                    val mayUseSlot = collectDiagnostics || hasGroups || candidate.ignoreBudget || ordinarySelected < slots
                    mayUseSlot && (depth == 0 || candidate.canReceiveRecursion && candidate.delayLevel <= level &&
                        results[candidate.index]?.status in recursionCandidateStatuses)
                }.map { it.match(scan, recursiveText, depth, messageCount, generationType, scanContext) }
                val candidates = if (hasGroups) {
                    val eligible = matching.onEach { results[it.candidate.index] = it.match }
                        .filter { it.match.status == "injected" }.toList()
                    resolveInclusionGroups(eligible, occupiedGroups, probabilitySeed, depth) { loser ->
                        results[loser.candidate.index] = loser.match.copy(status = "group_suppressed")
                    }.asSequence()
                } else matching
                for ((candidate, match) in candidates) {
                    if (!collectDiagnostics && !hasSelectionRoom()) break
                    val injectionKey = match.outletName to candidate.content
                    val result = when {
                        match.status != "injected" -> match
                        !candidate.passesProbability(match, probabilitySeed) -> match.copy(status = "probability")
                        injectionKey in injected -> match.copy(status = "duplicate")
                        selected.size >= DialoguePromptRules.WORLD_FACT_LIMIT -> match.copy(status = "total_budget")
                        !candidate.ignoreBudget && ordinarySelected >= slots -> match.copy(status = "budget")
                        candidate.content.length > remainingChars -> match.copy(status = "content_budget")
                        else -> {
                            val accepted = match.copy(excerpt = candidate.content)
                            injected.add(injectionKey)
                            selected += accepted
                            remainingChars -= candidate.content.length
                            if (!candidate.ignoreBudget) ordinarySelected++
                            occupiedGroups.addAll(candidate.groups)
                            candidate.timedEntry?.let { timing?.selected(accepted, it) }
                            if (candidate.canPropagate) nextText += accepted.excerpt
                            accepted
                        }
                    }
                    results[candidate.index] = result
                }
                return nextText
            }

            recursiveText += scanPass(depth = 0, level = 0)
            if (recursiveText.isNotEmpty() && hasSelectionRoom()) {
                val levels = ordered.asSequence()
                    .filter { it.canReceiveRecursion && !it.disabled && it.delayLevel > 0 }
                    .map { it.delayLevel }.distinct().sorted().iterator()
                var level = if (levels.hasNext()) levels.next() else 0
                for (depth in 1..MAX_RECURSION_DEPTH) {
                    val nextText = scanPass(depth, level)
                    if (!hasSelectionRoom()) break
                    if (nextText.isNotEmpty()) {
                        recursiveText += nextText
                    } else if (levels.hasNext()) {
                        level = levels.next()
                    } else {
                        break
                    }
                }
            }
            return if (collectDiagnostics) ordered.mapNotNull { results[it.index] } else selected
        }

        private fun resolveInclusionGroups(
            eligible: List<LoreHit>,
            occupied: Set<String>,
            seed: String?,
            depth: Int,
            onSuppressed: (LoreHit) -> Unit,
        ): List<LoreHit> {
            val groups = linkedMapOf<String, MutableList<LoreHit>>()
            for (hit in eligible) {
                for (name in hit.candidate.groups) groups.getOrPut(name) { mutableListOf() }.add(hit)
            }
            val live = eligible.toMutableSet()
            fun suppress(hit: LoreHit) {
                if (live.remove(hit)) onSuppressed(hit)
            }
            // Resolve sticky exclusions across all groups before considering weighted winners.
            for ((name, hits) in groups) {
                if (name in occupied || hits.any { it.candidate.stickyActive }) {
                    hits.filterNot { it.candidate.stickyActive }.forEach(::suppress)
                }
            }
            for ((name, hits) in groups) {
                val remaining = hits.filter(live::contains)
                if (remaining.size < 2 || remaining.any { it.candidate.stickyActive }) continue
                val highestScore = remaining.maxOf { it.score }
                val shortlisted = remaining.filter { hit ->
                    val loses = hit.candidate.group?.scoring == true && hit.score < highestScore
                    if (loses) suppress(hit)
                    !loses
                }
                val winner = shortlisted.firstOrNull { it.candidate.group?.priority == true }
                    ?: chooseGroupEntry(shortlisted, seed?.let { "$it\u0000group\u0000$depth\u0000$name" })
                shortlisted.filterNot { it === winner }.forEach(::suppress)
            }
            return eligible.filter(live::contains)
        }

        private fun chooseGroupEntry(options: List<LoreHit>, seed: String?): LoreHit? {
            if (options.size < 2) return options.firstOrNull()
            val total = options.sumOf { it.candidate.group?.weight ?: 100 }
            if (total == 0) return options.first()
            var target = unitRoll(seed) * total
            for (option in options) {
                target -= option.candidate.group?.weight ?: 100
                if (target < 0) return option
            }
            return options.last()
        }

        private data class LoreHit(val candidate: LoreCandidate, val match: WorldBookMatchDto, val score: Int)

        private data class LoreContent(val text: String, val forceActivation: Boolean, val suppressActivation: Boolean)

        private fun parseLoreContent(raw: String): LoreContent {
            val activate = "@@activate"
            val suppress = "@@dont_activate"
            var forceActivation = false
            var suppressActivation = false
            var needsFallback = false
            var offset = 0
            while (raw.startsWith("@@", offset)) {
                val lineEnd = raw.indexOf('\n', offset).let { if (it < 0) raw.length else it }
                val line = raw.substring(offset, lineEnd).removeSuffix("\r")
                offset = if (lineEnd < raw.length) lineEnd + 1 else lineEnd
                val fallback = line.startsWith("@@@")
                if (fallback && !needsFallback) continue
                val directive = if (fallback) line.drop(1) else line
                needsFallback = !directive.startsWith(activate) && !directive.startsWith(suppress)
                forceActivation = forceActivation || directive == activate
                suppressActivation = suppressActivation || directive == suppress
            }
            return LoreContent(raw.substring(offset).trim(), forceActivation, suppressActivation)
        }

        private class LoreCandidate(
            val index: Int,
            val entry: JsonObject,
            private val book: JsonObject?,
            private val source: String,
            private val sourceIndex: Int,
            timing: WorldBookTiming?,
        ) {
            private val extensions = entry["extensions"] as? JsonObject
            private val matching = entry.keywordMatching()
            private val triggers = entry.generationTriggers()
            val group = entry.groupSettings()
            val groups = groupNames(group?.names.orEmpty())
            val timedEntry = timing?.let {
                val sticky = entry.timingValue("sticky")
                val cooldown = entry.timingValue("cooldown")
                if (sticky == 0 && cooldown == 0) null else {
                    val identity = entry.number("id") ?: entry.number("uid") ?: sourceIndex
                    val key = "$source\u0000$identity\u0000$entry".encodeToByteArray().toByteString().sha256().hex()
                    WorldBookTiming.Entry(key, sticky, cooldown)
                }
            }
            private val phase = timedEntry?.let { timing?.phase(it) }
            val stickyActive = phase == WorldBookTiming.Phase.Sticky
            val insertionOrder = entry.number("order") ?: entry.number("insertion_order") ?: 0
            val disabled = entry.flag("disable") == true || entry.flag("enabled") == false
            val ignoreBudget = entry.ignoresBudget()
            private val loreContent = parseLoreContent(
                (entry["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
            )
            val content = loreContent.text
            val delayLevel = when (val delay = (entry["delayUntilRecursion"] ?: extensions?.get("delay_until_recursion")) as? JsonPrimitive) {
                JsonPrimitive(true) -> 1
                else -> delay?.intOrNull?.coerceAtLeast(0) ?: 0
            }
            private val recursionEnabled = book?.isRecursionEnabled() == true
            val canReceiveRecursion = recursionEnabled &&
                (entry.flag("excludeRecursion") ?: extensions?.flag("exclude_recursion")) != true
            val canPropagate = recursionEnabled &&
                (entry.flag("preventRecursion") ?: extensions?.flag("prevent_recursion")) != true

            fun match(
                scan: List<String>, recursiveText: List<String>, depth: Int, messageCount: Int,
                generationType: WorldBookGenerationType,
                scanContext: Map<WorldBookScanSource, String>,
            ): LoreHit {
                val sensitive = matching.caseSensitive ?: false
                val wholeWords = matching.wholeWords ?: book?.flag("match_whole_words") ?: false
                val scanDepth = matching.scanDepth ?: book?.number("scan_depth") ?: DEFAULT_SCAN_DEPTH
                val start = (scan.size - scanDepth.coerceIn(0, MAX_SCAN_DEPTH)).coerceAtLeast(0)
                val extraTexts = if (scanDepth > 0) matching.sources.mapNotNull(scanContext::get) else emptyList()
                fun matches(key: String): Boolean {
                    if (key.startsWith("/")) return false
                    val useBoundaries = wholeWords && key.none(Char::isWhitespace)
                    return (start until scan.size).any { matchesLiteral(scan[it], key, sensitive, useBoundaries) } ||
                        extraTexts.any { matchesLiteral(it, key, sensitive, useBoundaries) } ||
                        (depth > 0 && recursiveText.any { matchesLiteral(it, key, sensitive, useBoundaries) })
                }
                val constant = entry.flag("constant") == true
                val delayed = messageCount < entry.activationDelay()
                val waitingForRecursion = depth == 0 && delayLevel > 0 && !stickyActive
                val coolingDown = phase == WorldBookTiming.Phase.Cooldown
                val differentGeneration = triggers.isNotEmpty() && generationType.sillyTavernId !in triggers
                val forced = loreContent.forceActivation
                val suppressed = loreContent.suppressActivation && !forced
                val primaryHits = if (disabled || suppressed || ((constant || forced) && groups.isEmpty()) || stickyActive ||
                    content.isBlank() || differentGeneration || delayed || coolingDown || waitingForRecursion
                ) {
                    emptyList()
                } else {
                    entry.keys("key", "keys").filter(::matches)
                }
                val secondary = entry.keys("keysecondary", "secondary_keys")
                val useSecondary = matching.useSecondary && secondary.isNotEmpty()
                val secondaryHits = if ((useSecondary || groups.isNotEmpty()) && primaryHits.isNotEmpty()) {
                    secondary.filter(::matches)
                } else emptyList()
                val logic = matching.secondaryLogic
                val passesSecondary = !useSecondary || when (logic) {
                    WorldBookSecondaryLogic.NotAll -> secondaryHits.size < secondary.size
                    WorldBookSecondaryLogic.NotAny -> secondaryHits.isEmpty()
                    WorldBookSecondaryLogic.AndAll -> secondaryHits.size == secondary.size
                    WorldBookSecondaryLogic.AndAny -> secondaryHits.isNotEmpty()
                }
                val probability = triggerProbability(entry, extensions)
                val embedded = source == CHARACTER_SOURCE
                val position = entry.lorePlacementField("position", embedded).supportedPosition()
                val outletName = entry.outletName(embedded, position)
                val emptyExample = content.length <= WORLD_BOOK_CONTENT_CHAR_LIMIT &&
                    (position == WorldBookInjectionPosition.BeforeExamples || position == WorldBookInjectionPosition.AfterExamples) &&
                    sillyTavernExampleBlocks(content).isEmpty()
                val status = when {
                    disabled -> "disabled"
                    content.isBlank() || emptyExample -> "empty"
                    differentGeneration -> "generation_type"
                    delayed -> "activation_delay"
                    coolingDown -> "cooldown"
                    waitingForRecursion -> "recursion_only"
                    suppressed -> "decorator_suppressed"
                    !constant && !stickyActive && !forced && primaryHits.isEmpty() -> "no_keyword"
                    !constant && !stickyActive && !forced && !passesSecondary -> "secondary"
                    position == WorldBookInjectionPosition.Outlet && outletName == null -> "missing_outlet"
                    else -> "injected"
                }
                val match = WorldBookMatchDto(
                    title = entryTitle(entry, index),
                    excerpt = content.take(240),
                    status = status,
                    keywords = if (constant || forced) emptyList() else (primaryHits + if (useSecondary) secondaryHits else emptyList())
                        .distinct().take(8),
                    source = source,
                    triggerProbability = probability,
                    recursionDepth = depth,
                    position = position,
                    insertionOrder = insertionOrder,
                    injectionDepth = entry.injectionDepth(embedded, position),
                    injectionRole = entry.injectionRole(embedded, position),
                    outletName = outletName,
                    stickyActive = stickyActive,
                    ignoreBudget = ignoreBudget,
                    forcedActivation = forced && status == "injected",
                )
                val secondaryScore = if (logic == WorldBookSecondaryLogic.AndAny ||
                    logic == WorldBookSecondaryLogic.AndAll && secondaryHits.size == secondary.size
                ) secondaryHits.size else 0
                return LoreHit(this, match, primaryHits.size + secondaryScore)
            }

            fun passesProbability(match: WorldBookMatchDto, seed: String?): Boolean =
                stickyActive || match.triggerProbability?.let {
                    passesProbability(it, seed, source, sourceIndex)
                } != false
        }

        internal fun evaluateBooks(
            embedded: JsonObject?,
            standalone: JsonObject?,
            messages: List<String>,
            availableSlots: Int = 4,
            collectDiagnostics: Boolean = true,
            probabilitySeed: String? = null,
            messageCount: Int = messages.size,
            timing: WorldBookTiming? = null,
            generationType: WorldBookGenerationType = WorldBookGenerationType.Normal,
            scanContext: Map<WorldBookScanSource, String> = emptyMap(),
        ): List<WorldBookMatchDto> {
            val embeddedEntries = embedded?.let(::loreEntries).orEmpty()
            val standaloneEntries = standalone?.let(::loreEntries).orEmpty()
            return evaluateLore(
                embeddedEntries + standaloneEntries, messages, embeddedEntries.size, availableSlots,
                embedded, standalone, collectDiagnostics, probabilitySeed, messageCount, timing, generationType, scanContext,
            )
        }

        private fun JsonObject.activationDelay(): Int = timingValue("delay")

        private fun JsonObject.ignoresBudget(): Boolean =
            (flag("ignoreBudget") ?: (get("extensions") as? JsonObject)?.flag("ignore_budget")) == true

        private fun JsonObject.generationTriggers(): List<String> {
            val values = (get("triggers") as? JsonArray) ?: ((get("extensions") as? JsonObject)?.get("triggers") as? JsonArray)
            return values.orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }
        }

        private fun JsonObject.timingValue(key: String): Int =
            (number(key) ?: (get("extensions") as? JsonObject)?.number(key) ?: 0).coerceAtLeast(0)

        private fun groupNames(value: String): List<String> =
            if (value.isBlank()) emptyList() else value.split(',').map(String::trim).filter(String::isNotEmpty).distinct()

        private fun JsonObject.groupSettings(): WorldBookGroupSettings? {
            val extensions = (get("extensions") as? JsonObject).orEmpty()
            val names = string("group").ifBlank { (extensions["group"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
            val priority = flag("groupOverride") ?: (extensions["group_override"] as? JsonPrimitive)?.booleanOrNull
            val weight = number("groupWeight") ?: (extensions["group_weight"] as? JsonPrimitive)?.intOrNull
            val scoring = flag("useGroupScoring") ?: (extensions["use_group_scoring"] as? JsonPrimitive)?.booleanOrNull
            if (names.isBlank() && priority == null && weight == null && scoring == null) return null
            return WorldBookGroupSettings(names, priority == true, weight?.coerceIn(0, 10_000) ?: 100, scoring)
        }

        private fun triggerProbability(entry: JsonObject, extensions: JsonObject?): Double? {
            if ((entry.flag("useProbability") ?: extensions?.flag("useProbability")) == false) return null
            return configuredProbability(entry, extensions)
        }

        internal fun configuredProbability(entry: JsonObject, extensions: JsonObject?): Double? =
            ((entry["probability"] as? JsonPrimitive)?.doubleOrNull
                ?: (extensions?.get("probability") as? JsonPrimitive)?.doubleOrNull)
                ?.takeIf(Double::isFinite)
                ?.coerceIn(0.0, 100.0)

        private fun passesProbability(probability: Double, seed: String?, source: String, index: Int): Boolean {
            if (probability == 0.0) return false
            if (probability == 100.0) return true
            return unitRoll(seed?.let { "$it\u0000$source\u0000$index" }) * 100.0 < probability
        }

        private fun unitRoll(seed: String?): Double {
            if (seed == null) return Random.nextDouble()
            val digest = seed.encodeToByteArray().toByteString().sha256()
            val bucket = (0..3).fold(0L) { value, offset -> (value shl 8) + (digest[offset].toInt() and 0xff) }
            return bucket / 4_294_967_296.0
        }

        private fun scanDepth(entry: JsonObject, extensions: JsonObject?): Int? =
            entry.number("scanDepth") ?: extensions?.number("scan_depth")

        private fun JsonObject.keywordMatching(): WorldBookKeywordMatching {
            val extensions = get("extensions") as? JsonObject
            val logic = number("selectiveLogic") ?: extensions?.number("selectiveLogic")
            return WorldBookKeywordMatching(
                scanDepth = scanDepth(this, extensions)?.coerceAtLeast(0),
                caseSensitive = flag("caseSensitive") ?: extensions?.flag("case_sensitive"),
                wholeWords = flag("matchWholeWords") ?: extensions?.flag("match_whole_words"),
                useSecondary = flag("selective") != false,
                secondaryLogic = WorldBookSecondaryLogic.entries.firstOrNull { it.sillyTavernId == logic }
                    ?: WorldBookSecondaryLogic.AndAny,
                sources = WorldBookScanSource.entries.filter { scansSource(extensions, it) },
            )
        }

        private fun JsonObject.scansSource(extensions: JsonObject?, source: WorldBookScanSource): Boolean =
            (flag(source.nativeField) ?: extensions?.flag(source.embeddedField)) == true

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
            entries.sortedWith(
                compareBy<WorldBookMatchDto> {
                    when (it.status) {
                        "injected" -> 0
                        "budget", "total_budget", "content_budget", "probability" -> 1
                        else -> 2
                    }
                }
                    .thenBy { it.recursionDepth },
            ).take(50)

        private fun unsupportedRules(entries: List<JsonObject>, book: JsonObject?, embedded: Boolean): List<String> = buildList {
            if (entries.any { entry -> (entry.keys("key", "keys") + entry.keys("keysecondary", "secondary_keys")).any { it.startsWith('/') } }) {
                add("正则关键词")
            }
            fun anyEntry(predicate: (JsonObject, JsonObject) -> Boolean) =
                entries.any { predicate(it, (it["extensions"] as? JsonObject) ?: JsonObject(emptyMap())) }
            if (entries.any { entry -> entry.lorePlacementField("position", embedded)?.let { it.supportedPosition() == null } == true }) {
                add("高级插入位置")
            }
            if ((book?.number("scan_depth") ?: 0) > MAX_SCAN_DEPTH ||
                anyEntry { entry, ext -> (scanDepth(entry, ext) ?: 0) > MAX_SCAN_DEPTH }
            ) add("超过 ${MAX_SCAN_DEPTH} 条的扫描范围")
            if (anyEntry { entry, ext -> entry.flag("vectorized") == true || ext.flag("vectorized") == true }) add("向量匹配")
            val knownGenerationTypes = WorldBookGenerationType.entries.mapTo(mutableSetOf()) { it.sillyTavernId }
            if (entries.any { entry -> entry.generationTriggers().any { it !in knownGenerationTypes } }) {
                add("未知生成类型")
            }
            if (anyEntry { entry, ext -> entry.string("automationId").isNotBlank() || ext.string("automation_id").isNotBlank() }) {
                add("自动化动作")
            }
        }
    }
}

internal fun JsonObject.lorePlacementField(field: String, embedded: Boolean): JsonElement? {
    val native = get(field)?.takeUnless { it == JsonNull }
    val extension = (get("extensions") as? JsonObject)?.get(field)?.takeUnless { it == JsonNull }
    return if (embedded) extension ?: native else native ?: extension
}

internal fun JsonElement?.supportedPosition(): WorldBookInjectionPosition? = when ((this as? JsonPrimitive)?.contentOrNull) {
    "0", "before_char" -> WorldBookInjectionPosition.BeforeCharacter
    "1", "after_char" -> WorldBookInjectionPosition.AfterCharacter
    "2", "before_author_note" -> WorldBookInjectionPosition.BeforeAuthorNote
    "3", "after_author_note" -> WorldBookInjectionPosition.AfterAuthorNote
    "5", "before_examples" -> WorldBookInjectionPosition.BeforeExamples
    "6", "after_examples" -> WorldBookInjectionPosition.AfterExamples
    "4", "at_depth" -> WorldBookInjectionPosition.AtDepth
    "7", "outlet" -> WorldBookInjectionPosition.Outlet
    else -> null
}

internal fun JsonObject.outletName(embedded: Boolean, position: WorldBookInjectionPosition?): String? =
    if (position == WorldBookInjectionPosition.Outlet) {
        (lorePlacementField("outletName", embedded) as? JsonPrimitive)?.takeIf { it.isString }
            ?.content?.trim()?.takeIf { it.isValidSillyTavernOutletName() }
    } else null

internal fun JsonObject.injectionDepth(embedded: Boolean, position: WorldBookInjectionPosition?): Int? =
    if (position == WorldBookInjectionPosition.AtDepth) {
        ((lorePlacementField("depth", embedded) as? JsonPrimitive)?.intOrNull ?: 4).coerceIn(0, 10_000)
    } else null

internal fun JsonObject.injectionRole(embedded: Boolean, position: WorldBookInjectionPosition?): WorldBookInjectionRole? {
    if (position != WorldBookInjectionPosition.AtDepth) return null
    return sillyTavernInjectionRole(lorePlacementField("role", embedded))
}

internal fun sillyTavernInjectionRole(value: JsonElement?): WorldBookInjectionRole =
    when ((value as? JsonPrimitive)?.contentOrNull) {
        "1", "user" -> WorldBookInjectionRole.User
        "2", "assistant" -> WorldBookInjectionRole.Assistant
        else -> WorldBookInjectionRole.System
    }

internal class WorldBookChangedException : IllegalStateException("世界书已变化，请刷新后重试。")
internal class CardGreetingChangedException : IllegalStateException("角色卡已变化，请重新加载开场后再编辑。")
internal class CardMetadataChangedException : IllegalStateException("角色卡已变化，请重新加载设定后再编辑。")

private fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty().trim()

private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.isRecursionEnabled(): Boolean = (flag("recursive_scanning") ?: flag("recursiveScanning")) == true
private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.keys(native: String, embedded: String): List<String> =
    ((get(native) ?: get(embedded)) as? JsonArray).orEmpty().take(32)
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.trim() }
        .filter { it.length in 1..120 }
