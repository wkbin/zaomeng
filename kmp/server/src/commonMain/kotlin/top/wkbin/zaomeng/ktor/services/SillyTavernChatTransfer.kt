@file:OptIn(kotlin.time.ExperimentalTime::class)

package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.*
import top.wkbin.zaomeng.data.api.AuthorNoteDto
import top.wkbin.zaomeng.data.api.AuthorNotePosition
import top.wkbin.zaomeng.data.api.ImportSillyTavernChatRequest
import top.wkbin.zaomeng.data.api.ImportedSwipeDto
import top.wkbin.zaomeng.data.api.ImportedSwipePageDto
import top.wkbin.zaomeng.data.api.SessionManifest
import top.wkbin.zaomeng.data.api.WorldBookInjectionRole
import top.wkbin.zaomeng.platform.base64Decode
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid
import kotlin.time.Instant

internal class SillyTavernChatTransfer(private val storage: StorageService) {
    fun importChat(runId: String, request: ImportSillyTavernChatRequest): JsonObject {
        val run = storage.readRunManifest(runId) ?: throw NoSuchElementException("目标书卷不存在。")
        val knownCharacters = (run["artifact_index"] as? JsonObject)?.get("characters").let { it as? JsonArray }
            .orEmpty().mapNotNull { (it as? JsonObject)?.text("name")?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        require(knownCharacters.isNotEmpty()) { "目标书卷需要至少一位可用角色。" }
        val fileName = request.filename.substringAfterLast('/').substringAfterLast('\\')
        require(fileName.endsWith(".jsonl", ignoreCase = true)) { "请选择 SillyTavern JSONL 聊天记录。" }
        require(request.contentBase64.length <= (MAX_BYTES + 2) / 3 * 4) { "聊天文件不能超过 8 MB。" }
        val bytes = try {
            base64Decode(request.contentBase64)
        } catch (_: Exception) {
            throw IllegalArgumentException("聊天文件的 Base64 内容无效。")
        }
        require(bytes.size in 1..MAX_BYTES) { "聊天文件为空或超过 8 MB。" }
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
        } catch (_: Exception) {
            throw IllegalArgumentException("聊天文件须使用 UTF-8。")
        }
        val parsed = parseChat(text)
        val authorNote = importAuthorNote(parsed.header?.get("chat_metadata") as? JsonObject)
        val speakerNames = parsed.messages.filter { it.role == "character" }.map { it.speaker }.distinct()
        require(speakerNames.isNotEmpty()) { "聊天文件没有可映射的角色发言。" }
        val speakerMap = speakerNames.associateWith { speaker ->
            knownCharacters.firstOrNull { it == speaker }
                ?: knownCharacters.singleOrNull { it.equals(speaker, ignoreCase = true) }
                ?: knownCharacters.singleOrNull()?.takeIf { speakerNames.size == 1 }
                ?: throw IllegalArgumentException("目标书卷缺少角色「$speaker」。")
        }
        val participants = speakerMap.values.distinct()
        val userName = parsed.header?.text("user_name")?.trim()?.take(120)?.takeIf(String::isNotBlank)
            ?: parsed.messages.firstOrNull { it.role == "user" }?.speaker
            ?: "你"
        val sessionId = "sess-" + randomUuid().replace("-", "")
        val now = nowIsoString()
        var turn = 0
        val metadata = linkedMapOf<String, JsonElement>()
        val transcript = JsonArray(parsed.messages.mapIndexed { index, message ->
            if (turn == 0 || message.role == "user") turn++
            val speaker = when (message.role) {
                "character" -> speakerMap.getValue(message.speaker)
                "scene" -> "旁白"
                else -> message.speaker
            }
            val extras = message.metadata
            val date = normalizedDate(extras["send_date"])
            if (extras.isNotEmpty()) metadata[index.toString()] = extras
            buildJsonObject {
                put("speaker", speaker)
                put("role", message.role)
                put("message", message.content)
                put("turn_id", "import-$turn")
                put("timestamp", date)
                if (extras.isNotEmpty()) {
                    put(MESSAGE_INDEX, index.toString())
                    put("sillytavern_swipe_count", (extras["swipes"] as? JsonArray).orEmpty().size)
                }
            }
        })
        val manifest = json.encodeToJsonElement(
            SessionManifest(
                sessionId = sessionId,
                runId = runId,
                mode = "insert",
                participants = participants,
                selfProfile = buildJsonObject { put("display_name", userName) },
                title = fileName.dropLast(6).trim().take(120).ifBlank { "导入的 SillyTavern 会话" },
                createdAt = now,
                updatedAt = now,
                revision = if (parsed.messages.isEmpty()) 0 else 1,
                turnCount = turn,
                currentTurnId = "import-$turn",
            )
        ).jsonObject
        val directory = storage.getDialogueSessionsDirectory(runId) / sessionId
        try {
            storage.mkdirs(directory)
            if (metadata.isNotEmpty()) storage.writeTextAtomically(directory / METADATA_FILE, JsonObject(metadata).toString())
            val compacted = storage.compactSessionTranscript(runId, sessionId, manifest, transcript)
            val ready = buildJsonObject {
                manifest.forEach { (key, value) -> put(key, value) }
                compacted.writeFields(this)
                if (authorNote != null) put("author_note", json.encodeToJsonElement(authorNote))
                put("last_entry_preview", parsed.messages.last().content.take(200))
            }
            storage.writeTextAtomically(storage.getDialogueSessionManifestFile(runId, sessionId), ready.toString())
            return leanSession(storage.withCharacterAvatars(ready, runId))
        } catch (error: Throwable) {
            runCatching { storage.deleteRecursively(directory) }
            throw error
        }
    }

    fun exportLines(runId: String, sessionId: String): Sequence<JsonObject> {
        val session = storage.getDialogueSession(runId, sessionId)
        val metadata = readMetadata(runId, sessionId)
        val transcript = (session["transcript"] as? JsonArray).orEmpty()
        val user = (session["self_profile"] as? JsonObject)?.text("display_name").orEmpty().ifBlank {
            transcript.asSequence().filterIsInstance<JsonObject>()
                .firstOrNull { it.text("role") == "user" }?.text("speaker").orEmpty().ifBlank { "你" }
        }
        val participants = (session["participants"] as? JsonArray).orEmpty()
        val header = buildJsonObject {
            put("user_name", user)
            put("character_name", (participants.singleOrNull() as? JsonPrimitive)?.contentOrNull ?: "Group")
            put("create_date", session.text("created_at"))
            put("chat_metadata", exportAuthorNote(session.sessionAuthorNote()))
        }
        return sequenceOf(header) + transcript.asSequence().filterIsInstance<JsonObject>().map { item ->
            val extras = metadata[item.text(MESSAGE_INDEX)] as? JsonObject
            val role = item.text("role")
            val message = item.text("message")
            buildJsonObject {
                put("name", extras?.text("scene_name")?.takeIf(String::isNotBlank) ?: item.text("speaker"))
                put("is_user", role == "user")
                put("is_system", extras?.bool("is_system") == true)
                put("mes", message)
                put("send_date", extras?.get("send_date") ?: JsonPrimitive(item.text("timestamp")))
                if (role == "scene" || role == "director") put("extra", buildJsonObject { put("type", "narrator") })
                if (extras != null) {
                    if (role == "character") extras["original_avatar"]?.let { put("original_avatar", it) }
                    val swipes = extras["swipes"] as? JsonArray
                    if (!swipes.isNullOrEmpty()) {
                        val selected = ((extras["swipe_id"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(swipes.indices)
                        put("swipe_id", selected)
                        put("swipes", JsonArray(swipes.mapIndexed { index, value -> if (index == selected) JsonPrimitive(message) else value }))
                        (extras["swipe_info"] as? JsonArray)?.let { put("swipe_info", it) }
                    }
                }
            }
        }
    }

    private fun importAuthorNote(metadata: JsonObject?): AuthorNoteDto? {
        if (metadata == null || authorNoteKeys.none { it in metadata }) return null
        val defaults = AuthorNoteDto()
        val text = metadata.text("note_prompt")
        require(text.length <= AuthorNoteDto.MAX_TEXT_LENGTH) {
            "聊天文件中的作者注释超过 ${AuthorNoteDto.MAX_TEXT_LENGTH} 个字符。"
        }
        val interval = metadata.int("note_interval") ?: defaults.interval
        val depth = metadata.int("note_depth")?.takeIf { it in 0..10_000 }
        val position = metadata.int("note_position")
        val role = metadata.int("note_role")
        return AuthorNoteDto(
            text = text,
            enabled = interval > 0,
            interval = interval.takeIf { it > 0 } ?: defaults.interval,
            depth = depth ?: defaults.depth,
            position = AuthorNotePosition.entries.firstOrNull { it.sillyTavernId == position } ?: defaults.position,
            role = WorldBookInjectionRole.entries.firstOrNull { it.sillyTavernId == role } ?: defaults.role,
        )
    }

    private fun exportAuthorNote(note: AuthorNoteDto?) = buildJsonObject {
        if (note != null) {
            put("note_prompt", note.text)
            put("note_interval", if (note.enabled) note.interval else 0)
            put("note_depth", note.depth)
            put("note_position", note.position.sillyTavernId)
            put("note_role", note.role.sillyTavernId)
        }
    }

    fun listSwipes(runId: String, sessionId: String, messageIndex: String, offset: Int, limit: Int): ImportedSwipePageDto {
        validateMessageIndex(messageIndex)
        require(offset >= 0 && limit in 1..10) { "备用回复分页参数无效。" }
        val manifest = storage.getDialogueSessionManifestFile(runId, sessionId)
        if (!storage.isFile(manifest)) throw NoSuchElementException("会话不存在。")
        val message = readMetadata(runId, sessionId)[messageIndex] as? JsonObject
            ?: throw NoSuchElementException("迁移消息不存在。")
        val candidates = (message["swipes"] as? JsonArray).orEmpty()
        val page = if (offset >= candidates.size) emptyList() else {
            candidates.subList(offset, offset + minOf(limit, candidates.size - offset))
        }
        return ImportedSwipePageDto(
            total = candidates.size,
            selectedIndex = (message["swipe_id"] as? JsonPrimitive)?.intOrNull?.takeIf { it in candidates.indices },
            items = page.mapIndexed { index, item ->
                val text = (item as? JsonPrimitive)?.contentOrNull.orEmpty()
                ImportedSwipeDto(offset + index, text.take(MAX_MESSAGE_CHARS), text.length > MAX_MESSAGE_CHARS)
            },
        )
    }

    data class SwipeSelection(val messageIndex: String, val index: Int, val text: String, val timestamp: String)

    fun selectSwipe(runId: String, sessionId: String, messageIndex: String, swipeIndex: Int): SwipeSelection {
        validateMessageIndex(messageIndex)
        require(swipeIndex >= 0) { "回复版本序号无效。" }
        val extras = readMetadata(runId, sessionId)[messageIndex] as? JsonObject
            ?: throw NoSuchElementException("迁移消息不存在。")
        val text = ((extras["swipes"] as? JsonArray)?.getOrNull(swipeIndex) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content ?: throw NoSuchElementException("备用回复不存在。")
        require(text.isNotBlank() && text.length <= MAX_MESSAGE_CHARS) { "备用回复为空或超过 $MAX_MESSAGE_CHARS 字符。" }
        val info = (extras["swipe_info"] as? JsonArray)?.getOrNull(swipeIndex) as? JsonObject
        return SwipeSelection(messageIndex, swipeIndex, text, normalizedDate(info?.get("send_date")))
    }

    fun copyBranchMetadata(
        runId: String,
        sourceId: String,
        targetId: String,
        retained: List<JsonObject>,
        selection: SwipeSelection? = null,
    ) {
        if (sourceId.isBlank()) return
        val retainedIds = retained.mapNotNull { it.text(MESSAGE_INDEX).takeIf(String::isNotEmpty) }.toSet()
        if (retainedIds.isEmpty()) return
        val copied = readMetadata(runId, sourceId).filterKeys { it in retainedIds }.mapValues { (id, value) ->
            if (selection != null && id == selection.messageIndex && value is JsonObject) {
                val timing = (value["swipe_info"] as? JsonArray)?.getOrNull(selection.index) as? JsonObject
                val sendDate = timing?.get("send_date")?.takeIf { normalizedDate(it).isNotBlank() }
                JsonObject(value + ("swipe_id" to JsonPrimitive(selection.index)) +
                    if (sendDate == null) emptyMap() else mapOf("send_date" to sendDate))
            } else value
        }
        if (copied.isNotEmpty()) {
            val target = requireNotNull(storage.getDialogueSessionManifestFile(runId, targetId).parent) / METADATA_FILE
            storage.writeTextAtomically(target, JsonObject(copied).toString())
        }
    }

    private fun readMetadata(runId: String, sessionId: String): JsonObject {
        val manifest = storage.getDialogueSessionManifestFile(runId, sessionId)
        val file = requireNotNull(manifest.parent) / METADATA_FILE
        return storage.readTextOrNull(file)?.let { (Json.parseToJsonElement(it) as? JsonObject) } ?: JsonObject(emptyMap())
    }

    fun validateMessageIndex(messageIndex: String) {
        require(messageIndex.toIntOrNull()?.let { it in 0 until MAX_MESSAGES && it.toString() == messageIndex } == true) {
            "迁移消息序号无效。"
        }
    }

    private data class ImportedMessage(val speaker: String, val role: String, val content: String, val metadata: JsonObject)
    private data class ParsedChat(val header: JsonObject?, val messages: List<ImportedMessage>)

    private fun parseChat(text: String): ParsedChat {
        var header: JsonObject? = null
        val messages = mutableListOf<ImportedMessage>()
        var first = true
        text.lineSequence().forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            val entry = try {
                Json.parseToJsonElement(line) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: throw IllegalArgumentException("聊天文件第 ${index + 1} 行不是有效的 JSON 对象。")
            val isHeader = first && "mes" !in entry && listOf("chat_metadata", "character_name", "user_name").any { it in entry }
            first = false
            if (isHeader) {
                header = entry
                return@forEachIndexed
            }
            require(messages.size < MAX_MESSAGES) { "聊天文件最多支持 $MAX_MESSAGES 条消息。" }
            val msg = entry.text("mes")
            require(msg.length in 1..MAX_MESSAGE_CHARS && msg.isNotBlank()) { "第 ${index + 1} 行的消息为空或超过 $MAX_MESSAGE_CHARS 字符。" }
            val narrator = entry.bool("is_system") == true || (entry["extra"] as? JsonObject)?.text("type") == "narrator"
            val user = entry.bool("is_user")
            require(user != null || narrator) { "第 ${index + 1} 行缺少 is_user 标记。" }
            val role = if (narrator) "scene" else if (user == true) "user" else "character"
            val name = entry.text("name").trim().ifBlank {
                when (role) {
                    "scene" -> "旁白"
                    "user" -> header?.text("user_name").orEmpty().ifBlank { "你" }
                    else -> header?.text("character_name").orEmpty()
                }
            }
            require(name.length in 1..120 && name.none { it.isISOControl() }) { "第 ${index + 1} 行的发言者名称无效。" }
            val swipes = entry["swipes"]?.takeUnless { it == JsonNull }?.let { value ->
                require(value is JsonArray && value.all { it is JsonPrimitive && it.isString }) {
                    "第 ${index + 1} 行的备用回复无效。"
                }
                value
            }
            val metadata = buildJsonObject {
                (entry["send_date"] as? JsonPrimitive)?.takeIf { it != JsonNull && it.content.length <= 128 }?.let { put("send_date", it) }
                if (narrator) put("scene_name", name)
                if (entry.bool("is_system") == true) put("is_system", true)
                entry.text("original_avatar").takeIf {
                    role == "character" && it.length in 1..255 && it.none { ch -> ch in "/\\:" || ch.isISOControl() }
                }?.let { put("original_avatar", it) }
                if (!swipes.isNullOrEmpty()) {
                    val selectedText = JsonPrimitive(msg)
                    val requested = (entry["swipe_id"] as? JsonPrimitive)?.intOrNull
                    val matching = requested?.takeIf { swipes.getOrNull(it) == selectedText } ?: swipes.indexOf(selectedText)
                    val candidates = if (matching < 0) JsonArray(swipes + selectedText) else swipes
                    put("swipes", candidates)
                    put("swipe_id", matching.takeIf { it >= 0 } ?: swipes.size)
                    (entry["swipe_info"] as? JsonArray)?.let { info ->
                        put("swipe_info", JsonArray(candidates.indices.map { swipeIndex ->
                            JsonObject((info.getOrNull(swipeIndex) as? JsonObject).orEmpty().filter { (key, value) ->
                                key in swipeTimingFields && value is JsonPrimitive && value.content.length <= 128
                            })
                        }))
                    }
                }
            }
            messages += ImportedMessage(name, role, msg, metadata)
        }
        require(messages.isNotEmpty()) { "聊天文件没有消息。" }
        return ParsedChat(header, messages)
    }

    private fun normalizedDate(value: JsonElement?): String {
        val date = value as? JsonPrimitive ?: return ""
        return runCatching {
            date.longOrNull?.let { Instant.fromEpochMilliseconds(it) }?.toString()
                ?: Instant.parse(date.content).toString()
        }.getOrDefault("")
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_MESSAGES = 20_000
        const val MAX_MESSAGE_CHARS = 16_000
        const val MESSAGE_INDEX = "sillytavern_message_index"
        const val METADATA_FILE = "sillytavern-chat-metadata.json"
        val authorNoteKeys = setOf("note_prompt", "note_interval", "note_depth", "note_position", "note_role")
        val swipeTimingFields = setOf("send_date", "gen_started", "gen_finished")
        val json = Json { encodeDefaults = true }

        fun JsonObject.text(key: String): String =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

        fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
    }
}
