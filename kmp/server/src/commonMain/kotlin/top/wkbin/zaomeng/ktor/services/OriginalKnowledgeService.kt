package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.nowIsoString

/**
 * 原文知识索引：把当前原文按短段落切分，并按参与角色、可知范围做轻量检索。
 * 这是本地 lexical retrieval，不依赖外部 embedding 服务，适合 Android 离线运行。
 */
class OriginalKnowledgeService(private val storage: StorageService) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private data class CachedPayload(
        val modifiedAt: Long,
        val size: Long,
        val payload: JsonObject,
    )

    private fun lockFor(runId: String): SimpleLock = locksLock.withLock {
        locks.getOrPut(runId) { SimpleLock() }
    }

    fun get(runId: String): JsonObject = lockFor(runId).withLock { read(runId) }

    fun rebuild(runManifest: JsonObject): JsonObject = ensure(runManifest, force = true)

    fun ensure(
        runManifest: JsonObject,
        characterNames: List<String> = emptyList(),
        force: Boolean = false,
    ): JsonObject {
        val runId = runManifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(runId.isNotBlank()) { "Run id is required" }
        return lockFor(runId).withLock {
            val source = resolveSource(runManifest)
                ?: throw NoSuchElementException("Original source not found")
            val current = read(runId)
            val sourceSignature = sourceSignature(source)
            val names = (characterNames + this.characterNames(runManifest))
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
            val currentSource = current["source"]?.jsonObject ?: JsonObject(emptyMap())
            val indexedCharacters = currentSource["characters"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            if (!force && current["entries"]?.jsonArray?.isNotEmpty() == true &&
                currentSource["path"]?.jsonPrimitive?.contentOrNull == source.toString() &&
                currentSource["byte_size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() == sourceSignature.byteSize &&
                currentSource["modified_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() == sourceSignature.modifiedAt &&
                names.all { it in indexedCharacters }
            ) {
                return@withLock current
            }

            val oldOverrides = current["entries"]?.jsonArray.orEmpty()
                .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .filter { it["boundary_source"]?.jsonPrimitive?.contentOrNull == "manual" }
                .associateBy { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim() }
            val entries = buildEntries(storage.readText(source), source.name, names).map { entry ->
                val old = oldOverrides[entry["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()]
                if (old == null) entry else buildJsonObject {
                    entry.forEach { (key, value) -> put(key, value) }
                    old["visibility"]?.let { put("visibility", it) }
                    old["knowers"]?.let { put("knowers", it) }
                    put("boundary_source", "manual")
                }
            }
            val updated = buildJsonObject {
                put("version", 1)
                put("source", buildJsonObject {
                    put("name", source.name)
                    put("path", source.toString())
                    put("byte_size", sourceSignature.byteSize)
                    put("modified_at", sourceSignature.modifiedAt)
                    put("characters", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
                })
                put("entries", buildJsonArray { entries.forEach(::add) })
                put("entry_count", entries.size)
                put("updated_at", nowIsoString())
            }
            write(runId, updated)
            updated
        }
    }

    fun updateBoundary(
        runId: String,
        entryId: String,
        visibility: String,
        knowers: List<String>,
    ): JsonObject = lockFor(runId).withLock {
        val normalizedVisibility = visibility.trim().lowercase()
        require(normalizedVisibility in VISIBILITIES) { "Invalid knowledge visibility" }
        val payload = read(runId)
        val entries = payload["entries"]?.jsonArray?.map { it.jsonObject }?.toMutableList()
            ?: mutableListOf()
        val index = entries.indexOfFirst { it["entry_id"]?.jsonPrimitive?.contentOrNull == entryId }
        require(index >= 0) { "Original knowledge entry not found" }
        entries[index] = buildJsonObject {
            entries[index].forEach { (key, value) -> put(key, value) }
            put("visibility", normalizedVisibility)
            put("knowers", buildJsonArray {
                knowers.map(String::trim).filter(String::isNotBlank).distinct().forEach { add(JsonPrimitive(it)) }
            })
            put("boundary_source", "manual")
            put("updated_at", nowIsoString())
        }
        val updated = buildJsonObject {
            payload.forEach { (key, value) -> if (key != "entries") put(key, value) }
            put("entries", buildJsonArray { entries.forEach(::add) })
            put("entry_count", entries.size)
            put("updated_at", nowIsoString())
        }
        write(runId, updated)
        entries[index]
    }

    fun search(
        runManifest: JsonObject,
        query: String,
        participants: List<String>,
        activeParticipants: List<String> = participants,
        sceneTerms: List<String> = emptyList(),
        limit: Int = 6,
        rebuildIfMissing: Boolean = true,
    ): List<Map<String, Any?>> {
        val names = participants.map(String::trim).filter(String::isNotBlank).distinct()
        val active = activeParticipants.map(String::trim).filter(String::isNotBlank).distinct()
        val payload = if (rebuildIfMissing) {
            runCatching {
                ensure(
                    runManifest,
                    characterNames = names + characterNames(runManifest),
                )
            }.getOrElse { return emptyList() }
        } else {
            val runId = runManifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            lockFor(runId).withLock { read(runId) }
        }
        val queryText = (listOf(query) + sceneTerms)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" ")
        val exactQuery = query.trim()
        val tokens = tokens(queryText)
        val entries = payload["entries"]?.jsonArray.orEmpty()
        if (queryText.isBlank()) {
            return entries.take(limit.coerceIn(1, 10)).mapNotNull { raw ->
                val entry = runCatching { raw.jsonObject }.getOrNull() ?: return@mapNotNull null
                retrievalPayload(entry, 0.0, names)
            }
        }
        val scored = entries.mapNotNull { raw ->
            val entry = runCatching { raw.jsonObject }.getOrNull() ?: return@mapNotNull null
            val text = entry["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val overlap = tokens.count { text.contains(it, ignoreCase = true) }
            var score = overlap.toDouble()
            if (exactQuery.isNotBlank() && text.contains(exactQuery, ignoreCase = true)) score += 16.0
            val mentioned = entry["characters"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            score += mentioned.count { it in active } * 2.5
            score += mentioned.count { it in names } * 0.75
            score += mentioned.count { it in active && queryText.contains(it, ignoreCase = true) } * 3.0
            if (score <= 0.0) return@mapNotNull null
            score to entry
        }.sortedWith(compareByDescending<Pair<Double, JsonObject>> { it.first })
            .take(limit.coerceIn(1, 10))
        return scored.map { (score, entry) -> retrievalPayload(entry, score, names) }
    }

    private fun read(runId: String): JsonObject {
        val file = storage.getRunDirectory(runId) / "original_knowledge.json"
        val key = file.toString()
        if (!storage.isFile(file)) {
            payloadCacheLock.withLock { payloadCache.remove(key) }
            return emptyPayload()
        }
        val modifiedAt = storage.lastModifiedMillis(file)
        val size = storage.fileSize(file)
        val cached = payloadCacheLock.withLock {
            payloadCache[key]?.takeIf { it.modifiedAt == modifiedAt && it.size == size }?.payload
        }
        if (cached != null) return cached
        val parsed = runCatching { json.parseToJsonElement(storage.readText(file)).jsonObject }
            .getOrElse { emptyPayload() }
        payloadCacheLock.withLock {
            payloadCache[key] = CachedPayload(modifiedAt = modifiedAt, size = size, payload = parsed)
            while (payloadCache.size > MAX_CACHED_INDEXES) payloadCache.remove(payloadCache.keys.first())
        }
        return parsed
    }

    private fun write(runId: String, payload: JsonObject) {
        val file = storage.getRunDirectory(runId) / "original_knowledge.json"
        storage.writeTextAtomically(
            file,
            json.encodeToString(JsonObject.serializer(), payload),
        )
        payloadCacheLock.withLock {
            payloadCache[file.toString()] = CachedPayload(
                modifiedAt = storage.lastModifiedMillis(file),
                size = storage.fileSize(file),
                payload = payload,
            )
            while (payloadCache.size > MAX_CACHED_INDEXES) payloadCache.remove(payloadCache.keys.first())
        }
    }

    private fun emptyPayload(): JsonObject = buildJsonObject {
        put("version", 1)
        put("source", JsonObject(emptyMap()))
        put("entries", JsonArray(emptyList()))
        put("entry_count", 0)
        put("updated_at", "")
    }

    private fun resolveSource(manifest: JsonObject): Path? {
        val runId = manifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val runDir = storage.getRunDirectory(runId).normalized()
        val candidates = buildList {
            manifest["novel_sources"]?.jsonArray?.lastOrNull()?.jsonObject
                ?.get("source_path")?.jsonPrimitive?.contentOrNull?.let { add(it.toPath()) }
            manifest["novel_path"]?.jsonPrimitive?.contentOrNull?.let { add(it.toPath()) }
            add(runDir / "novel.txt")
        }
        return candidates.map { it.normalized() }.firstOrNull { candidate ->
            storage.isFile(candidate) && runCatching { candidate.relativeTo(runDir); true }.getOrDefault(false)
        }
    }

    private fun characterNames(manifest: JsonObject): List<String> =
        manifest["artifact_index"]?.jsonObject?.get("characters")?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()

    private fun sourceSignature(source: Path): SourceSignature =
        SourceSignature(storage.fileSize(source), storage.lastModifiedMillis(source))

    private fun buildEntries(text: String, sourceName: String, names: List<String>): List<JsonObject> {
        val entries = mutableListOf<JsonObject>()
        val buffer = StringBuilder()
        var start = 0
        var cursor = 0
        fun flush() {
            val chunk = buffer.toString().trim()
            if (chunk.isBlank()) return
            val ordinal = entries.size + 1
            val mentioned = names.filter { it in chunk }
            val boundary = inferBoundary(chunk, mentioned)
            entries += buildJsonObject {
                put("entry_id", "src-${ordinal.toString().padStart(5, '0')}")
                put("ordinal", ordinal)
                put("title", "$sourceName · 原文片段 $ordinal")
                put("text", chunk)
                put("start_char", start)
                put("end_char", start + chunk.length)
                put("characters", buildJsonArray { mentioned.forEach { add(JsonPrimitive(it)) } })
                put("visibility", boundary.first)
                put("knowers", buildJsonArray { boundary.second.forEach { add(JsonPrimitive(it)) } })
                put("boundary_source", "automatic")
                put("epistemic_status", "explicit_source")
            }
            buffer.clear()
        }
        splitSentences(text.replace("\r\n", "\n").replace('\r', '\n')).forEach { sentence ->
            if (entries.size >= MAX_ENTRIES) return@forEach
            if (buffer.isNotEmpty() && buffer.length + sentence.length > CHUNK_CHAR_LIMIT) {
                flush()
                start = cursor
            }
            if (buffer.isEmpty()) start = cursor
            buffer.append(sentence)
            cursor += sentence.length
        }
        if (entries.size < MAX_ENTRIES) flush()
        return entries
    }

    private fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        text.forEachIndexed { index, char ->
            if (char in charArrayOf('。', '！', '？', '!', '?', '；', ';', '\n')) {
                result += text.substring(start, index + 1)
                start = index + 1
            }
        }
        if (start < text.length) result += text.substring(start)
        return result.filter { it.isNotBlank() }
    }

    private fun inferBoundary(text: String, mentioned: List<String>): Pair<String, List<String>> {
        val explicit = mentioned.filter { name ->
            Regex("${Regex.escape(name)}.{0,8}(说|问|答|告诉|听见|听到|看见|看到|知道|明白|发现|记得|想起|心想|暗想|意识到)").containsMatchIn(text)
        }
        if (SECRET_MARKERS.any { it in text }) return if (explicit.isNotEmpty()) "private" to explicit else "uncertain" to emptyList()
        if ("\u7f51\u7edc" in text || PUBLIC_MARKERS.any { it in text }) return "public" to emptyList()
        if (explicit.size > 1) return "scene" to explicit
        if (explicit.size == 1) return "private" to explicit
        return "uncertain" to emptyList()
    }

    private fun retrievalPayload(entry: JsonObject, score: Double, participants: List<String>): Map<String, Any?> {
        val visibility = entry["visibility"]?.jsonPrimitive?.contentOrNull ?: "uncertain"
        val knowers = entry["knowers"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val allowed = when (visibility) {
            "public" -> participants
            "scene", "private" -> participants.filter { it in knowers }
            else -> emptyList()
        }
        return mapOf(
            "source_id" to entry["entry_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            "title" to entry["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            "excerpt" to trim(entry["text"]?.jsonPrimitive?.contentOrNull.orEmpty(), 760),
            "location" to mapOf(
                "start_char" to (entry["start_char"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0),
                "end_char" to (entry["end_char"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0),
            ),
            "score" to (score * 1000.0).toInt() / 1000.0,
            "visibility" to visibility,
            "allowed_characters" to allowed,
            "denied_characters" to participants.filter { it !in allowed },
            "epistemic_status" to (entry["epistemic_status"]?.jsonPrimitive?.contentOrNull ?: "explicit_source"),
        )
    }

    private fun tokens(text: String): Set<String> {
        val result = linkedSetOf<String>()
        Regex("[A-Za-z0-9_]{2,}|[\\u3400-\\u9fff]+|[^\\s]{2,}").findAll(text.lowercase()).forEach { match ->
            val token = match.value
            result += token
            if (token.all { it in '\u3400'..'\u9fff' }) {
                if (token.length <= 4) result += token
                for (index in 0 until token.length - 1) result += token.substring(index, index + 2)
            }
        }
        return result
    }

    private fun trim(value: String, max: Int): String =
        if (value.length <= max) value else value.take(max - 1).trimEnd() + "…"

    private data class SourceSignature(val byteSize: Long, val modifiedAt: Long)

    companion object {
        // DialoguePayloadBuilder is created for every request and DialogueService owns a
        // separate OriginalKnowledgeService. Keep both coordination and parsed indexes at
        // process scope so those instances do not reparse the same multi-megabyte file.
        private val locks = HashMap<String, SimpleLock>()
        private val locksLock = SimpleLock()
        private val payloadCache = HashMap<String, CachedPayload>()
        private val payloadCacheLock = SimpleLock()
        private const val MAX_CACHED_INDEXES = 3
        private const val CHUNK_CHAR_LIMIT = 900
        private const val MAX_ENTRIES = 12_000
        private val VISIBILITIES = setOf("public", "scene", "private", "uncertain")
        private val SECRET_MARKERS = listOf("秘密", "瞒着", "隐瞒", "只有", "不得告诉", "不能让", "不知情")
        private val PUBLIC_MARKERS = listOf("世界", "规则", "所有人", "任何人", "人们", "法律", "制度", "历史", "城市", "国家", "组织")
    }
}
