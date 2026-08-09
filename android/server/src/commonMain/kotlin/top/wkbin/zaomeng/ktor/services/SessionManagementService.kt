package top.wkbin.zaomeng.ktor.services

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
import top.wkbin.zaomeng.data.api.SessionListItem
import top.wkbin.zaomeng.data.api.SessionManifest
import top.wkbin.zaomeng.ktor.models.*
import okio.Path
import top.wkbin.zaomeng.platform.nowIsoString
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
) {
    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
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
            controlledCharacter = controlledCharacter,
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
        selfProfile: JsonObject = JsonObject(emptyMap())
    ): JsonObject {
        val session = createDialogueSession(
            runId = runId,
            mode = mode,
            participants = participants,
            controlledCharacter = controlledCharacter,
            sceneCardId = sceneCardId,
            sceneProfile = sceneProfile,
            selfCardId = selfCardId,
            selfProfile = selfProfile,
        )
        val sessionId = session["session_id"]?.jsonPrimitive?.content ?: return session
        try {
            val openingMessage = buildOpeningMessage(
                mode = mode,
                participants = participants,
                controlledCharacter = controlledCharacter,
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
            return getDialogueSession(runId, sessionId)
        } catch (e: Exception) {
            // 开场失败：删除会话（对齐 Python create_dialogue_session_payload 的删除行为）
            runCatching {
                storageService.deleteRecursively(storageService.getDialogueSessionsDirectory(runId) / sessionId)
            }
            throw e
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

    /**
     * 更新会话标题
     *
     * 对应 Python: run_service.update_dialogue_session_title()
     */
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

        // 读取会话清单
        val manifestFile = storageService.getDialogueSessionManifestFile(runId, sessionId)

        if (!storageService.exists(manifestFile)) {
            throw NoSuchElementException("Session not found: $sessionId")
        }

        val manifest = json.decodeFromString<JsonObject>(storageService.readText(manifestFile))

        // 更新标题和时间戳
        val updated = buildJsonObject {
            manifest.forEach { (key, value) -> put(key, value) }
            put("title", title)
            put("updated_at", nowIsoString())
        }

        // 写回文件
        storageService.writeTextAtomically(manifestFile, json.encodeToString(JsonObject.serializer(), updated))

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
        val runTitle = storageService.listRunManifests()
            .firstOrNull { it["run_id"]?.jsonPrimitive?.contentOrNull == runId }
            ?.let { it["title"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        val sessions = storageService.listDialogueSessions(runId).map { session ->
            toSessionListItem(manifest = session, runTitle = runTitle)
        }
        return pageSessions(
            sessions = sessions,
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
        val runTitles = storageService.listRunManifests()
            .mapNotNull { manifest ->
                val runId = manifest["run_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                runId to manifest["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            .toMap()
        val sessions = storageService.listFiles(storageService.runsDir)
            .asSequence()
            .filter { storageService.isDirectory(it) && PathSafety.STORAGE_ID_PATTERN.matches(it.name) }
            .flatMap { runDir ->
                storageService.listDialogueSessions(runDir.name).asSequence().map { session ->
                    toSessionListItem(manifest = session, runTitle = runTitles[runDir.name].orEmpty())
                }
            }
            .toList()
        return pageSessions(
            sessions = sessions,
            offset = offset,
            limit = limit,
            query = query,
            sort = sort,
        )
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
        val items = sorted.drop(offset).take(limit)
        return SessionsPage(
            items = items,
            total = total,
            hasMore = offset + items.size < total,
        )
    }

    /** 把 manifest JsonObject 投影为列表项（唯一一处字符串取字段，集中收口）。 */
    private fun toSessionListItem(manifest: JsonObject, runTitle: String): SessionListItem = SessionListItem(
        sessionId = manifest.stringValue("session_id"),
        runId = manifest.stringValue("run_id"),
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
    )

    private fun JsonObject.stringValue(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun sessionModeSearchLabel(mode: String): String = when (mode) {
        "act" -> "扮演人物"
        "insert" -> "自设入场"
        else -> "旁观群聊"
    }

    fun deleteDialogueSession(runId: String, sessionId: String): Boolean {
        PathSafety.validateStorageId(runId, "run_id")
        PathSafety.validateStorageId(sessionId, "session_id")
        val directory = storageService.getDialogueSessionsDirectory(runId) / sessionId
        val deleted = storageService.exists(directory) && runCatching {
            storageService.deleteRecursively(directory)
            true
        }.getOrDefault(false)
        if (deleted) {
            // 该会话归属的时间线/剧情事实（world_memory.json 中 source_session_id 匹配）一并清理
            runCatching { worldMemory?.purgeSession(runId, sessionId) }
        }
        return deleted
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
        val turnId = operationId.ifBlank { generateId() }
        // operation_id 来自请求体，需防路径穿越（如 "../x"）
        if (turnId.contains('/') || turnId.contains('\\') || turnId.contains("..")) {
            throw IllegalArgumentException("Invalid operation_id")
        }
        val timestamp = nowIsoString()

        // 读取会话清单
        val manifestFile = storageService.getDialogueSessionManifestFile(runId, sessionId)

        if (!storageService.exists(manifestFile)) {
            throw NoSuchElementException("Session not found: $sessionId")
        }

        val manifest = json.decodeFromString<JsonObject>(storageService.readText(manifestFile))

        // 创建轮次数据
        val turn = buildJsonObject {
            put("turn_id", turnId)
            put("session_id", sessionId)
            put("message", message)
            put("message_kind", messageKind)
            put("created_at", timestamp)
            put("status", "pending")
        }

        // 写入轮次文件
        val turnFile = storageService.getDialogueSessionsDirectory(runId) / "$sessionId/turn_$turnId.json"
        storageService.writeTextAtomically(turnFile, json.encodeToString(JsonObject.serializer(), turn))

        // 更新会话清单
        val updated = buildJsonObject {
            manifest.forEach { (key, value) -> put(key, value) }
            val turns = manifest["turns"]?.jsonArray?.toMutableList() ?: mutableListOf()
            turns.add(JsonPrimitive(turnId))
            put("turns", buildJsonArray { turns.forEach { add(it) } })
            put("current_turn_id", turnId)
            put("updated_at", timestamp)
        }

        storageService.writeTextAtomically(manifestFile, json.encodeToString(JsonObject.serializer(), updated))

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
