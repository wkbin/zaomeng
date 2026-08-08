package top.wkbin.zaomeng.ktor.services

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
import top.wkbin.zaomeng.ktor.models.*
import java.io.File
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*
import java.nio.file.Files

/**
 * 存储服务
 *
 * 管理运行数据的文件系统访问，对应 Python 的 manifest/store.py
 */
class StorageService(
    private val storageRoot: File
) {
    private val writeLock = Any()
    /**
     * 从 Context 创建 StorageService
     */
    constructor(context: Context) : this(File(context.filesDir, "zaomeng"))

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val runsRoot: File
        get() = File(storageRoot, "runs").apply { mkdirs() }

    /**
     * 获取存储根目录（供外部访问）
     */
    fun getStorageRoot(): File = storageRoot

    /** Serialize writes and replace the destination only after the full payload is on disk. */
    fun writeTextAtomically(target: File, content: String) {
        synchronized(writeLock) {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
            temp.writeText(content)
            // Windows 上 File.renameTo 无法覆盖已存在目标，统一改用 Files.move(REPLACE_EXISTING)
            val replaced = runCatching {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrElse {
                // 兜底：删除旧目标后重命名（极端情况下仍失败则报错并清理临时文件）
                runCatching { target.delete() }.getOrDefault(false) && temp.renameTo(target)
            }
            if (!replaced) {
                temp.delete()
                throw IllegalStateException("Unable to replace ${target.name}")
            }
        }
    }

    /**
     * 获取运行目录
     */
    fun getRunDirectory(runId: String): File {
        return PathSafety.resolveStorageChild(runsRoot, runId, "run_id")
    }

    /**
     * 获取运行清单文件路径
     */
    fun getRunManifestPath(runId: String): File {
        return File(getRunDirectory(runId), "run_manifest.json")
    }

    /**
     * 读取运行清单
     */
    fun readRunManifest(runId: String): JsonObject? {
        val manifestFile = getRunManifestPath(runId)
        if (!manifestFile.exists()) {
            return null
        }
        return runCatching { json.parseToJsonElement(manifestFile.readText()).jsonObject }.getOrNull()
    }

    /**
     * 写入运行清单
     */
    fun writeRunManifest(runId: String, manifest: JsonObject) {
        val manifestFile = getRunManifestPath(runId)
        manifestFile.parentFile.mkdirs()
        writeTextAtomically(manifestFile, json.encodeToString(JsonObject.serializer(), manifest))
    }

    /**
     * 列出所有运行 ID
     */
    fun listRunIds(): List<String> {
        if (!runsRoot.exists()) {
            return emptyList()
        }

        return runsRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "run_manifest.json").exists() }
            ?.mapNotNull { it.name }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * 列出所有运行清单（按修改时间倒序）
     */
    fun listRunManifests(): List<JsonObject> {
        if (!runsRoot.exists()) {
            return emptyList()
        }

        return runsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val manifestFile = File(dir, "run_manifest.json")
                if (manifestFile.exists()) {
                    try {
                        val manifest = json.parseToJsonElement(manifestFile.readText()).jsonObject
                        manifest to manifestFile.lastModified()
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
            ?.sortedByDescending { it.second }
            ?.map { it.first }
            ?: emptyList()
    }

    /**
     * 检查运行是否存在
     */
    fun runExists(runId: String): Boolean {
        return try {
            getRunManifestPath(runId).exists()
        } catch (e: InvalidStorageIdentifierException) {
            false
        }
    }

    /**
     * 获取对话会话目录
     */
    fun getDialogueSessionsDirectory(runId: String): File {
        return File(getRunDirectory(runId), "dialogue/sessions")
    }

    fun getDialogueSessionManifestFile(runId: String, sessionId: String): File {
        PathSafety.validateStorageId(sessionId, "session_id")
        return File(getDialogueSessionsDirectory(runId), "$sessionId/session_manifest.json")
    }

    /**
     * 列出对话会话 ID
     */
    fun listDialogueSessionIds(runId: String): List<String> {
        val sessionsDir = getDialogueSessionsDirectory(runId)
        if (!sessionsDir.exists()) {
            return emptyList()
        }

        return sessionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { it.name }
            ?.filter { PathSafety.STORAGE_ID_PATTERN.matches(it) }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * 加载会话清单
     */
    fun loadSessionManifest(runId: String, sessionId: String): JsonObject {
        val manifestFile = getDialogueSessionManifestFile(runId, sessionId)

        if (!manifestFile.exists()) {
            throw NoSuchElementException("Session manifest not found: $sessionId")
        }

        return try {
            val content = manifestFile.readText()
            json.decodeFromString(JsonObject.serializer(), content)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load session manifest: ${e.message}", e)
        }
    }

    /**
     * 获取章节目录
     */
    fun getChaptersDirectory(runId: String): File {
        return File(getRunDirectory(runId), "chapters")
    }

    fun getChapterFile(runId: String, chapterId: String): File {
        PathSafety.validateStorageId(chapterId, "chapter_id")
        return File(getChaptersDirectory(runId), "$chapterId.json")
    }

    /**
     * 列出章节 ID
     */
    fun listChapterIds(runId: String): List<String> {
        val chaptersDir = getChaptersDirectory(runId)
        if (!chaptersDir.exists()) {
            return emptyList()
        }

        return chaptersDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    fun listChapters(runId: String): List<JsonObject> {
        return listChapterIds(runId).mapNotNull { chapterId ->
            try {
                val file = getChapterFile(runId, chapterId)
                val value = json.decodeFromString(JsonObject.serializer(), file.readText())
                buildJsonObject {
                    value.forEach { (key, item) -> put(key, item) }
                    put("chapter_id", JsonPrimitive(chapterId))
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 获取模型设置文件路径
     */
    fun getModelSettingsPath(): File {
        return File(storageRoot, "model_settings.json")
    }

    /**
     * 读取模型设置
     */
    fun readModelSettings(): ModelSettings? {
        val settingsFile = getModelSettingsPath()
        if (!settingsFile.exists()) {
            return null
        }

        return try {
            json.decodeFromString<ModelSettings>(settingsFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 写入模型设置
     */
    fun writeModelSettings(settings: ModelSettings) {
        val settingsFile = getModelSettingsPath()
        settingsFile.parentFile.mkdirs()
        writeTextAtomically(settingsFile, json.encodeToString(settings))
    }

    /**
     * 加载模型设置（返回 Map 以便灵活访问）
     */
    fun loadModelSettings(): Map<String, Any> {
        val settings = readModelSettings() ?: return emptyMap()

        // 将 ModelSettings 转换为 Map
        return buildMap<String, Any> {
            settings.activeProfileId?.let { put("active_profile_id", it) }

            // 查找活跃的 profile
            val activeProfile = settings.profiles.firstOrNull {
                it.profileId == settings.activeProfileId
            } ?: settings.profiles.firstOrNull()

            activeProfile?.let { profile ->
                profile.provider?.let { put("provider", it) }
                profile.model?.let { put("model", it) }
                profile.baseUrl?.let { put("base_url", it) }
            }
        }
    }

    /**
     * 获取 API 密钥（从 Android Keystore）
     *
     * Note: 这是一个占位实现。实际应该从 ModelApiKeyStore 获取。
     */
    fun getApiKey(provider: String): String? {
        // TODO: 集成 ModelApiKeyStore
        // 暂时返回 null，让调用方处理
        return null
    }

    /**
     * 列出对话会话（包含元数据）
     */
    fun listDialogueSessions(runId: String): List<JsonObject> {
        val sessionIds = listDialogueSessionIds(runId)
        return sessionIds.mapNotNull { sessionId ->
            try {
                getDialogueSession(runId, sessionId)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 获取对话会话详情
     */
    fun getDialogueSession(runId: String, sessionId: String): JsonObject {
        PathSafety.validateStorageId(sessionId, "session_id")
        val sessionDir = File(getDialogueSessionsDirectory(runId), sessionId)
        val manifestFile = File(sessionDir, "session_manifest.json")

        if (!manifestFile.exists()) {
            throw NoSuchElementException("Session manifest not found: $sessionId")
        }

        val content = manifestFile.readText()
        val decoded = json.decodeFromString(JsonObject.serializer(), content)
        return withCharacterAvatars(decoded, runId)
    }

    // ------------------------------------------------------------------
    // 角色头像（对齐 Python persona_avatars.py / chat service _serialize_session）
    // ------------------------------------------------------------------

    /** 角色头像文件：文件名 = sha256(角色名 UTF-8).png（与 Python 一致）。 */
    fun avatarFile(runId: String, character: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(character.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(getRunDirectory(runId), "avatars/$digest.png")
    }

    /** 头像版本号：mtime-大小；无头像文件返回空串（与 Python avatar_version 语义一致）。 */
    fun avatarVersion(runId: String, character: String): String {
        val file = avatarFile(runId, character)
        return if (file.isFile) "${file.lastModified()}-${file.length()}" else ""
    }

    /** 会话响应注入 character_avatars：{参与者名: 头像版本}。 */
    fun withCharacterAvatars(manifest: JsonObject, runId: String): JsonObject {
        val participants = (manifest["participants"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        return buildJsonObject {
            manifest.forEach { (key, value) -> put(key, value) }
            if (participants.isNotEmpty()) {
                put("character_avatars", buildJsonObject {
                    participants.forEach { name -> put(name, JsonPrimitive(avatarVersion(runId, name))) }
                })
            }
        }
    }

    /** run manifest 响应注入实时 avatar_version（对齐 Python core.py _serialize_manifest）。 */
    fun withLiveAvatarVersions(manifest: JsonObject, runId: String): JsonObject {
        val artifactIndex = manifest["artifact_index"]?.jsonObject ?: return manifest
        val characters = artifactIndex["characters"]?.jsonArray ?: return manifest
        val updatedCharacters = buildJsonArray {
            characters.forEach { element ->
                val item = element.jsonObject
                val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                add(buildJsonObject {
                    item.forEach { (key, value) -> put(key, value) }
                    put("avatar_version", avatarVersion(runId, name))
                })
            }
        }
        val updatedIndex = buildJsonObject {
            artifactIndex.forEach { (key, value) -> put(key, value) }
            put("characters", updatedCharacters)
        }
        return buildJsonObject {
            manifest.forEach { (key, value) -> if (key != "artifact_index") put(key, value) }
            put("artifact_index", updatedIndex)
        }
    }

    val runsDir: File
        get() = runsRoot
}
