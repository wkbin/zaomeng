package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.data.api.AuthorNoteDto

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.wkbin.zaomeng.data.api.SessionListItem
import top.wkbin.zaomeng.data.api.CardGreetingsDto
import top.wkbin.zaomeng.data.api.UpdateCardGreetingRequest
import top.wkbin.zaomeng.data.api.SessionManifest
import top.wkbin.zaomeng.data.api.MessagesResponse
import top.wkbin.zaomeng.data.api.SessionProjectionDto
import top.wkbin.zaomeng.ktor.models.*
import okio.Path
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.platformIoDispatcher
import top.wkbin.zaomeng.platform.randomUuid

/**
 * 会话管理服务
 *
 * 对应 Python 的 WebRunService 中的会话管理功能
 */
class SessionManagementService(
    private val storageService: StorageService,
    private val dialogueService: DialogueService,
    private val worldMemory: WorldMemoryService? = null,
    private val projections: SessionProjectionService? = null,
) {
    internal val sillyTavernChats = SillyTavernChatTransfer(storageService)

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        // 类型化 SessionManifest 需要写全字段（title/mode 等默认值也必须落盘，
        // 否则 DialogueService 解码 DialogueManifest 会因缺字段报 400）
        encodeDefaults = true
    }

    /**
     * 创建对话会话
     *
     * 对应 Python: run_service.create_dialogue_session()
     */
    fun createDialogueSession(
        runId: String,
        mode: String = "observe",
        participants: List<String> = emptyList(),
        controlledCharacter: String = "",
        sceneCardId: String = "",
        sceneProfile: JsonObject = JsonObject(emptyMap()),
        selfCardId: String = "",
        selfProfile: JsonObject = JsonObject(emptyMap())
    ): JsonObject {
        // 验证运行存在
        if (!storageService.runExists(runId)) {
            throw NoSuchElementException("Run not found: $runId")
        }

        // 验证参数
        if (mode !in listOf("observe", "act", "insert", "control", "free")) {
            throw IllegalArgumentException("Invalid mode: $mode")
        }
        val normalizedControlledCharacter = if (mode == "act") {
            controlledCharacter.trim().also { controlled ->
                if (controlled.isBlank() || controlled !in participants) {
                    throw IllegalArgumentException("Controlled character must be one of the participants in act mode")
                }
            }
        } else {
            ""
        }

        // 生成会话 ID
        val sessionId = generateId()
        val timestamp = nowIsoString()

        // 创建会话目录
        val sessionDir = storageService.getDialogueSessionsDirectory(runId) / sessionId
        storageService.mkdirs(sessionDir)

        // 创建会话清单（纯构造写出，类型化模型序列化后的 JSON 与旧 manifest 完全一致）
        val manifest = SessionManifest(
            sessionId = sessionId,
            runId = runId,
            mode = mode,
            participants = participants,
            controlledCharacter = normalizedControlledCharacter,
            sceneCardId = sceneCardId,
            sceneProfile = sceneProfile,
            selfCardId = selfCardId,
            selfProfile = selfProfile,
            createdAt = timestamp,
            updatedAt = timestamp,
            title = "",
            status = "ready",
        )

        // 写入会话清单
        val manifestFile = sessionDir / "session_manifest.json"
        storageService.writeTextAtomically(manifestFile, json.encodeToString(SessionManifest.serializer(), manifest))

        // 响应注入 character_avatars（对齐 Python _serialize_session，不持久化）
        val response = json.encodeToJsonElement(SessionManifest.serializer(), manifest).jsonObject
        return storageService.withCharacterAvatars(response, runId)
    }

    /**
     * 创建对话会话并自动生成开场（对齐 Python create_dialogue_session）
     *
     * 对应 Python: create_dialogue_session_payload() —— 创建会话后立即调用 LLM
     * 生成场景与第一轮对白写入 transcript；开场失败则删除会话并抛出异常。
     */
    suspend fun openDialogueSession(
        runId: String,
        mode: String = "observe",
        participants: List<String> = emptyList(),
        controlledCharacter: String = "",
        sceneCardId: String = "",
        sceneProfile: JsonObject = JsonObject(emptyMap()),
        selfCardId: String = "",
        selfProfile: JsonObject = JsonObject(emptyMap()),
        cardGreetingIndex: Int? = null,
    ): JsonObject {
        val normalizedControlledCharacter = controlledCharacter.takeIf { mode == "act" }.orEmpty()
        if ((mode == "observe" || mode == "act") && participants.size < 2) {
            throw IllegalArgumentException("At least two participants are required for $mode mode")
        }
        if (mode == "insert" && participants.size < 1) {
            throw IllegalArgumentException("At least one participant is required for insert mode")
        }
        val greetingCharacter = participants.singleOrNull()?.takeIf {
            mode == "insert" && sceneCardId.isBlank() && sceneProfile.isEmpty()
        }
        require(cardGreetingIndex == null || greetingCharacter != null) {
            "角色卡开场仅适用于未指定场景的单人加入对话。"
        }
        val greeting = greetingCharacter?.let { character ->
            withContext(platformIoDispatcher) {
                val card = readSillyTavernCard(runId)
                val user = (selfProfile["display_name"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { "你" }
                card?.let { SillyTavernImport.greeting(it, character, user, cardGreetingIndex ?: 0) }.orEmpty()
            }
        }.orEmpty()
        require(cardGreetingIndex == null || greeting.isNotBlank()) { "所选角色卡开场不存在，请重新选择。" }
        val session = createDialogueSession(
            runId = runId,
            mode = mode,
            participants = participants,
            controlledCharacter = normalizedControlledCharacter,
            sceneCardId = sceneCardId,
            sceneProfile = sceneProfile,
            selfCardId = selfCardId,
            selfProfile = selfProfile,
        )
        val sessionId = session["session_id"]?.jsonPrimitive?.content ?: return session
        dialogueService.resetDerivedSession(runId, sessionId)
        try {
            if (greetingCharacter != null && greeting.isNotBlank()) {
                return withContext(platformIoDispatcher) {
                    val now = nowIsoString()
                    val turnId = generateId()
                    storageService.mutateSessionHistory(runId, sessionId) { current ->
                        val updated = buildJsonObject {
                            current.manifest.forEach { (key, value) -> put(key, value) }
                            put("card_greeting_index", cardGreetingIndex ?: 0)
                            put("turn_count", 1)
                            put("current_turn_id", turnId)
                            put("updated_at", now)
                            put("last_entry_preview", greeting.take(200))
                            put("transcript_start", 0)
                            put("transcript_count", 1)
                            put("transcript", buildJsonArray {
                                add(buildJsonObject {
                                    put("speaker", greetingCharacter)
                                    put("message", greeting)
                                    put("role", "character")
                                    put("turn_id", turnId)
                                    put("timestamp", now)
                                })
                            })
                        }
                        SessionHistoryMutation(updated, Unit)
                    }
                    projections?.rebuild(runId, sessionId)
                    getDialogueSession(runId, sessionId)
                }
            }
            val openingMessage = buildOpeningMessage(
                mode = mode,
                participants = participants,
                controlledCharacter = normalizedControlledCharacter,
                sceneProfile = sceneProfile,
                selfProfile = selfProfile,
            )
            dialogueService.replyDialogueTurn(
                runId = runId,
                sessionId = sessionId,
                message = openingMessage,
                messageKind = "plot",
                suppressTranscriptMessage = true,
            )
            val session = getDialogueSession(runId, sessionId)
            if (session["title"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                val generated = autoSessionTitle(
                    session = session,
                    mode = mode,
                    participants = participants,
                    controlledCharacter = normalizedControlledCharacter,
                    sceneProfile = sceneProfile,
                    selfProfile = selfProfile,
                )
                if (generated.isNotBlank()) {
                    updateDialogueSessionTitle(runId, sessionId, generated)
                    return getDialogueSession(runId, sessionId)
                }
            }
            return session
        } catch (e: Exception) {
            // 开场失败：删除会话（对齐 Python create_dialogue_session_payload 的删除行为）
            runCatching {
                deleteDialogueSession(runId, sessionId)
            }
            throw e
        }
    }

    fun getCardGreetings(runId: String): CardGreetingsDto =
        SillyTavernImport(storageService).getCardGreetings(runId)

    fun updateCardGreeting(runId: String, request: UpdateCardGreetingRequest): CardGreetingsDto =
        SillyTavernImport(storageService).updateCardGreeting(runId, request)

    private fun readSillyTavernCard(runId: String): SillyTavernImport.Card? {
        val file = storageService.getRunDirectory(runId) / SillyTavernImport.CARD_FILE
        return storageService.readTextOrNull(file)?.let {
            runCatching { SillyTavernImport.parseCard(it.encodeToByteArray(), stored = true) }.getOrNull()
        }
    }

    /**
     * 构造开场消息（对齐 Python build_scene_opening_message）
     */
    private fun buildOpeningMessage(
        mode: String,
        participants: List<String>,
        controlledCharacter: String,
        sceneProfile: JsonObject,
        selfProfile: JsonObject,
    ): String {
        val normalizedMode = mode.ifBlank { "observe" }
        // 参与者名单截断，避免超长输入（缓解 LLM 指令注入面）
        val cast = participants.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("、")
            .take(500)
            .ifBlank { "当前角色" }
        fun field(key: String): String =
            sceneProfile[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(200)
        val scenePrefixBits = listOf(field("title"), field("location"), field("atmosphere"))
            .filter { it.isNotEmpty() }
        val scenePrefix = if (scenePrefixBits.isNotEmpty()) {
            "场景设定：${scenePrefixBits.joinToString(" / ")}。"
        } else {
            ""
        }
        val openingSuffix = field("opening_situation")
            .takeIf { it.isNotEmpty() }
            ?.let { " 开场局面是：$it。" }
            .orEmpty()
        val driveSuffix = field("scene_drive")
            .takeIf { it.isNotEmpty() }
            ?.let { " 推进方向优先朝这边走：$it。" }
            .orEmpty()
        return when (normalizedMode) {
            "act" -> {
                val controlled = controlledCharacter.trim().ifBlank { "该角色" }
                "${scenePrefix}请先为 $controlled 与 $cast 生成一个自然开场。$openingSuffix$driveSuffix" +
                    "先给 1 条简短的场景提示或旁白，再让其他角色先接出第一轮对话，不要等待用户补充。"
            }
            "insert" -> {
                fun selfField(key: String): String =
                    selfProfile[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(200)
                val displayName = selfField("display_name").ifBlank { "我" }
                val sceneIdentity = selfField("scene_identity").ifBlank { selfField("core_identity") }
                val identitySuffix = if (sceneIdentity.isNotEmpty()) "，身份是$sceneIdentity" else ""
                "${scenePrefix}请先为 $displayName$identitySuffix 与 $cast 生成一个自然开场。$openingSuffix$driveSuffix" +
                    "先给 1 条简短的场景提示或旁白，再让角色们先开口，对这个进入场景的人作出第一轮反应。"
            }
            else -> "${scenePrefix}请先为 $cast 生成一个自然开场。$openingSuffix$driveSuffix" +
                "先给 1 条简短的场景提示或旁白，再让角色们开始第一轮对话，让场景自己动起来。"
        }
    }

    /**
     * 获取对话会话
     */
    fun getDialogueSession(runId: String, sessionId: String): JsonObject {
        val session = storageService.getDialogueSession(runId, sessionId)
        return session
    }

    /** 轻量会话：只读取近期 manifest，不物化 archive。 */
    fun getDialogueSessionHeader(runId: String, sessionId: String): JsonObject {
        val snapshot = storageService.transcriptReader.readSnapshot(runId, sessionId)
        return storageService.withCharacterAvatars(snapshot.manifest, runId)
    }

    fun getSessionProjection(runId: String, sessionId: String): SessionProjectionDto {
        val service = projections ?: throw IllegalStateException("Session projection service unavailable")
        return service.get(runId, sessionId)
    }

    fun rebuildSessionProjection(runId: String, sessionId: String): SessionProjectionDto {
        val service = projections ?: throw IllegalStateException("Session projection service unavailable")
        return service.rebuild(runId, sessionId)
    }

    fun listDialogueSessionMessages(
        runId: String,
        sessionId: String,
        offset: Int,
        limit: Int,
        order: String,
        expectedRevision: Long? = null,
    ): MessagesResponse {
        val page = storageService.transcriptReader.readPage(
            runId = runId,
            sessionId = sessionId,
            offset = offset,
            limit = limit,
            order = if (order == "desc") TranscriptOrder.DESC else TranscriptOrder.ASC,
            expectedRevision = expectedRevision,
        )
        return toMessagesResponse(page)
    }

    /**
     * 更新会话标题
     *
     * 对应 Python: run_service.update_dialogue_session_title()
     */
    fun updateAuthorNote(runId: String, sessionId: String, note: AuthorNoteDto): AuthorNoteDto {
        require(note.text.length <= AuthorNoteDto.MAX_TEXT_LENGTH) { "作者注释最多 ${AuthorNoteDto.MAX_TEXT_LENGTH} 个字符。" }
        require(note.depth in 0..10_000) { "聊天深度须为 0 至 10000 的整数。" }
        require(note.interval > 0) { "注入间隔须为正整数。" }
        storageService.updateSessionManifest(runId, sessionId) { current ->
            val manifest = if (note.enabled && note.interval > 1) {
                storageService.withArchivedUserMessageCount(runId, sessionId, current)
            } else current
            JsonObject(manifest + ("author_note" to Json.encodeToJsonElement(AuthorNoteDto.serializer(), note)))
        }
        return note
    }

    fun updateDialogueSessionTitle(
        runId: String,
        sessionId: String,
        title: String
    ): JsonObject {
        // 验证标题
        if (title.isBlank()) {
            throw IllegalArgumentException("Title cannot be blank")
        }
        if (title.length > 80) {
            throw IllegalArgumentException("Title too long (max 80 chars)")
        }

        val updated = storageService.updateSessionManifest(runId, sessionId) { current ->
            buildJsonObject {
                current.forEach { (key, value) -> put(key, value) }
                put("title", title)
                put("updated_at", nowIsoString())
            }
        }

        return storageService.withCharacterAvatars(updated, runId)
    }

    /**
     * 列出对话会话
     */
    fun listDialogueSessions(
        runId: String,
        offset: Int = 0,
        limit: Int = 50,
        query: String = "",
        sort: String = "recent",
    ): SessionsPage {
        return pageSessions(
            sessions = sessionListItems(runId),
            offset = offset,
            limit = limit,
            query = query,
            sort = sort,
        )
    }

    fun runExists(runId: String): Boolean = storageService.runExists(runId)

    fun listRecentSessions(
        offset: Int = 0,
        limit: Int = 50,
        query: String = "",
        sort: String = "recent",
    ): SessionsPage {
        return pageSessions(
            sessions = storageService.listDialogueSessionRunIds().flatMap(::sessionListItems),
            offset = offset,
            limit = limit,
            query = query,
            sort = sort,
        )
    }

    private fun sessionListItems(runId: String): List<SessionListItem> {
        val manifests = storageService.listDialogueSessionManifests(runId, includeCharacterAvatars = false)
        if (manifests.isEmpty()) return emptyList()
        val title = storageService.readRunManifest(runId)?.stringValue("title").orEmpty()
        return manifests.map { toSessionListItem(it, runId, title) }
    }

    /**
     * 统一的会话列表分页：先过滤（q）、再排序（recent/title）、最后切片。
     * 过滤字段与 Android 端此前客户端过滤保持一致：书卷标题/novel_id/模式/参与人/受控角色/最后一条预览。
     */
    private fun pageSessions(
        sessions: List<SessionListItem>,
        offset: Int,
        limit: Int,
        query: String,
        sort: String,
    ): SessionsPage {
        val normalizedQuery = query.trim()
        val filtered = if (normalizedQuery.isBlank()) {
            sessions
        } else {
            sessions.filter { session ->
                listOf(
                    session.runTitle,
                    session.novelId,
                    sessionModeSearchLabel(session.mode),
                    session.participants.joinToString(" "),
                    session.controlledCharacter,
                    session.lastEntryPreview,
                ).any { value -> value.contains(normalizedQuery, ignoreCase = true) }
            }
        }
        val sorted = when (sort) {
            "title" -> filtered.sortedWith { left, right ->
                val leftTitle = left.runTitle.takeIf { it.isNotBlank() } ?: left.novelId
                val rightTitle = right.runTitle.takeIf { it.isNotBlank() } ?: right.novelId
                val titleOrder = leftTitle.compareTo(rightTitle, ignoreCase = true)
                if (titleOrder != 0) {
                    titleOrder
                } else {
                    right.updatedAt.compareTo(left.updatedAt)
                }
            }
            else -> filtered.sortedByDescending { it.updatedAt }
        }
        val total = sorted.size
        val avatarVersions = mutableMapOf<Pair<String, String>, String>()
        val items = sorted.drop(offset).take(limit).map { item ->
            if (item.participants.isEmpty()) {
                item
            } else {
                item.copy(characterAvatars = item.participants.associateWith { character ->
                    avatarVersions.getOrPut(item.runId to character) {
                        storageService.avatarVersion(item.runId, character)
                    }
                })
            }
        }
        return SessionsPage(
            items = items,
            total = total,
            hasMore = offset + items.size < total,
        )
    }

    /** 把 manifest JsonObject 投影为列表项（唯一一处字符串取字段，集中收口）。 */
    private fun toSessionListItem(manifest: JsonObject, runId: String, runTitle: String): SessionListItem = SessionListItem(
        sessionId = manifest.stringValue("session_id"),
        runId = runId,
        novelId = manifest.stringValue("novel_id"),
        runTitle = runTitle,
        title = manifest.stringValue("title"),
        mode = manifest.stringValue("mode"),
        modeDisplay = manifest.stringValue("mode_display"),
        participants = (manifest["participants"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty(),
        characterAvatars = (manifest["character_avatars"] as? JsonObject)
            ?.mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { name to it }
            }
            ?.toMap()
            .orEmpty(),
        controlledCharacter = manifest.stringValue("controlled_character"),
        status = manifest.stringValue("status"),
        turnCount = (manifest["turn_count"] as? JsonPrimitive)?.intOrNull ?: 0,
        currentTurnId = manifest.stringValue("current_turn_id"),
        createdAt = manifest.stringValue("created_at"),
        updatedAt = manifest.stringValue("updated_at"),
        lastEntryPreview = manifest.stringValue("last_entry_preview"),
        cardGreetingIndex = (manifest["card_greeting_index"] as? JsonPrimitive)?.intOrNull,
    )

    private fun JsonObject.stringValue(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun sessionModeSearchLabel(mode: String): String = when (mode) {
        "act" -> "扮演人物"
        "insert" -> "自设入场"
        else -> "旁观群聊"
    }

    suspend fun deleteDialogueSession(runId: String, sessionId: String): Boolean {
        val run = PathSafety.validateStorageId(runId, "run_id")
        val session = PathSafety.validateStorageId(sessionId, "session_id")
        return withContext(NonCancellable + platformIoDispatcher) {
            val deleted = runCatching { dialogueService.deleteSessionData(run, session) }.getOrDefault(false)
            if (deleted) {
                runCatching { worldMemory?.purgeSession(run, session) }
            }
            deleted
        }
    }

    /**
     * 写入用户输入（准备对话轮次）
     *
     * 对应 Python: run_service.prepare_dialogue_turn()
     */
    fun prepareDialogueTurn(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String = "dialogue",
        operationId: String = ""
    ): JsonObject {
        // 验证消息
        if (message.isBlank()) {
            throw IllegalArgumentException("Message cannot be blank")
        }

        // 生成轮次 ID
        val turnId = PathSafety.validateStorageId(operationId.ifBlank { generateId() }, "operation_id")
        val timestamp = nowIsoString()

        // 创建轮次数据
        val turn = buildJsonObject {
            put("turn_id", turnId)
            put("session_id", sessionId)
            put("message", message)
            put("message_kind", messageKind)
            put("created_at", timestamp)
            put("status", "pending")
        }

        storageService.updateSessionManifest(runId, sessionId) { current ->
            val sessionDir = requireNotNull(storageService.getDialogueSessionManifestFile(runId, sessionId).parent)
            val turnFile = sessionDir / "turn_$turnId.json"
            storageService.writeExistingSessionText(
                runId, sessionId, turnFile, json.encodeToString(JsonObject.serializer(), turn),
            )
            buildJsonObject {
                current.forEach { (key, value) -> put(key, value) }
                val recentTurns = (current["turns"] as? JsonArray).orEmpty()
                    .filterNot { it == JsonPrimitive(turnId) }.takeLast(119)
                put("turns", JsonArray(recentTurns + JsonPrimitive(turnId)))
                put("current_turn_id", turnId)
                put("updated_at", timestamp)
            }
        }

        return turn
    }

    /**
     * 生成唯一 ID
     */
    private fun generateId(): String {
        return randomUuid().replace("-", "")
    }

}

/** 会话列表分页结果：列表项为类型化投影，客户端按 [DialogueSessionDto] 解码（缺省字段用默认值）。 */
data class SessionsPage(
    val items: List<SessionListItem>,
    val total: Int,
    val hasMore: Boolean,
)

/** 创建会话后按内容生成标题：开场首条消息 > 场景标题 > 模式兜底。 */
internal fun autoSessionTitle(
    session: JsonObject,
    mode: String,
    participants: List<String>,
    controlledCharacter: String,
    sceneProfile: JsonObject,
    selfProfile: JsonObject,
): String {
    transcriptOf(session).firstNotNullOfOrNull { item ->
        item["message"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    }?.let { return cleanSessionTitle(it) }

    sceneProfile["title"]?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it.take(80) }

    val cast = buildList {
        if (controlledCharacter.isNotBlank()) add(controlledCharacter.trim())
        addAll(participants.map { it.trim() }.filter { it.isNotBlank() })
    }
    return when (mode) {
        "act" -> "扮演 · " + cast.joinToString("、").take(40)
        "insert" -> {
            val selfName = selfProfile["display_name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
            selfName?.let { "$it 入场" } ?: "自设入场"
        }
        else -> "旁观会话"
    }
}

private fun cleanSessionTitle(raw: String): String = raw
    .trim()
    .trimStart('「', '」', '『', '』', '“', '”', '"', '\'', '《', '》')
    .trimEnd('「', '」', '『', '』', '“', '”', '"', '\'', '《', '》', '。', '！', '？', '…', '.', ',', '，', '!', '?')
    .replace(Regex("""\s+"""), " ")
    .trim()
    .take(24)
