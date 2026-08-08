package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.ktor.models.*
import java.io.File
import java.time.Instant
import java.util.*

/**
 * 会话管理服务
 *
 * 对应 Python 的 WebRunService 中的会话管理功能
 */
class SessionManagementService(
    private val storageService: StorageService,
    private val dialogueService: DialogueService,
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
        val timestamp = Instant.now().toString()

        // 创建会话目录
        val sessionDir = File(storageService.getDialogueSessionsDirectory(runId), sessionId)
        sessionDir.mkdirs()

        // 创建会话清单
        val manifest = buildJsonObject {
            put("session_id", sessionId)
            put("run_id", runId)
            put("mode", mode)
            put("participants", buildJsonArray { participants.forEach { add(JsonPrimitive(it)) } })
            put("controlled_character", controlledCharacter)
            put("scene_card_id", sceneCardId)
            put("scene_profile", sceneProfile)
            put("self_card_id", selfCardId)
            put("self_profile", selfProfile)
            put("created_at", timestamp)
            put("updated_at", timestamp)
            put("title", "")
            put("status", "ready")
            put("transcript", buildJsonArray { })
            put("turns", buildJsonArray { })
            put("turn_count", 0)
            put("current_turn_id", "")
        }

        // 写入会话清单
        val manifestFile = File(sessionDir, "session_manifest.json")
        storageService.writeTextAtomically(manifestFile, json.encodeToString(JsonObject.serializer(), manifest))

        // 响应注入 character_avatars（对齐 Python _serialize_session，不持久化）
        return storageService.withCharacterAvatars(manifest, runId)
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
                File(storageService.getDialogueSessionsDirectory(runId), sessionId).deleteRecursively()
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

        if (!manifestFile.exists()) {
            throw NoSuchElementException("Session not found: $sessionId")
        }

        val manifest = json.decodeFromString<JsonObject>(manifestFile.readText())

        // 更新标题和时间戳
        val updated = buildJsonObject {
            manifest.forEach { (key, value) -> put(key, value) }
            put("title", title)
            put("updated_at", Instant.now().toString())
        }

        // 写回文件
        storageService.writeTextAtomically(manifestFile, json.encodeToString(JsonObject.serializer(), updated))

        return storageService.withCharacterAvatars(updated, runId)
    }

    /**
     * 列出对话会话
     */
    fun listDialogueSessions(runId: String): List<JsonObject> {
        return storageService.listDialogueSessions(runId)
    }

    fun runExists(runId: String): Boolean = storageService.runExists(runId)

    fun listRecentSessions(): List<JsonObject> {
        return storageService.runsDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && PathSafety.STORAGE_ID_PATTERN.matches(it.name) }
            ?.flatMap { runDir ->
                storageService.listDialogueSessions(runDir.name).asSequence().map { session ->
                    if (session["run_id"] != null) session else buildJsonObject {
                        session.forEach { (key, value) -> put(key, value) }
                        put("run_id", JsonPrimitive(runDir.name))
                    }
                }
            }
            ?.sortedByDescending { it["updated_at"]?.toString().orEmpty() }
            ?.toList()
            ?: emptyList()
    }

    fun deleteDialogueSession(runId: String, sessionId: String): Boolean {
        PathSafety.validateStorageId(runId, "run_id")
        PathSafety.validateStorageId(sessionId, "session_id")
        val directory = File(storageService.getDialogueSessionsDirectory(runId), sessionId)
        return directory.exists() && directory.deleteRecursively()
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
        val timestamp = Instant.now().toString()

        // 读取会话清单
        val manifestFile = storageService.getDialogueSessionManifestFile(runId, sessionId)

        if (!manifestFile.exists()) {
            throw NoSuchElementException("Session not found: $sessionId")
        }

        val manifest = json.decodeFromString<JsonObject>(manifestFile.readText())

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
        val turnFile = File(storageService.getDialogueSessionsDirectory(runId), "$sessionId/turn_$turnId.json")
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
        return UUID.randomUUID().toString().replace("-", "")
    }

}
