package top.wkbin.zaomeng.ktor.services

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
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import top.wkbin.zaomeng.ktor.models.*
import top.wkbin.zaomeng.platform.monotonicNanos
import top.wkbin.zaomeng.platform.toHex

/**
 * 存储服务
 *
 * 管理运行数据的文件系统访问，对应 Python 的 manifest/store.py。
 * 文件 IO 使用 okio（KMP 通用），平台差异仅剩 FileSystem.SYSTEM 的实际实现。
 */
class StorageService(
    private val storageRoot: Path,
    private val fs: FileSystem = FileSystem.SYSTEM,
) {
    /**
     * 按路径分片的写锁：不同 run/文件可并行写，同一路径仍串行。
     * 固定 64 路，避免全局锁串行化与无界锁表增长。
     */
    private val writeLocks = Array(64) { Any() }

    private fun lockFor(target: Path): Any {
        val path = target.toString()
        val index = (path.hashCode() and Int.MAX_VALUE) % writeLocks.size
        return writeLocks[index]
    }

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val runsRoot: Path
        get() = (storageRoot / "runs").also { fs.createDirectories(it) }

    /** 获取存储根目录（供外部访问） */
    fun getStorageRoot(): Path = storageRoot

    /** Serialize writes and replace the destination only after the full payload is on disk. */
    fun writeTextAtomically(target: Path, content: String) {
        writeBytesAtomically(target, content.encodeToByteArray())
    }

    /** 字节版原子写入（避免大文件先解码成 String 再回写）。 */
    fun writeBytesAtomically(target: Path, bytes: ByteArray) {
        synchronized(lockFor(target)) {
            runCatching { fs.createDirectories(target.parent!!) }
            val temp = target.parent!! / ".${target.name}.${monotonicNanos()}.tmp"
            fs.sink(temp).buffer().use { sink -> sink.write(bytes) }
            // Android（Linux）上 rename 可覆盖已存在目标；Windows 开发/测试环境无法覆盖，
            // 此时先删除旧目标再重命名作为兜底（仅失败路径，不影响正常原子性）
            if (!replace(temp, target)) {
                runCatching { fs.delete(target) }
                if (!replace(temp, target)) {
                    runCatching { fs.delete(temp) }
                    throw IllegalStateException("Unable to replace ${target.name}")
                }
            }
        }
    }

    private fun replace(source: Path, target: Path): Boolean = try {
        fs.atomicMove(source, target)
        true
    } catch (e: Exception) {
        false
    }

    /** 读取文本文件；不存在返回 null。 */
    fun readTextOrNull(path: Path): String? {
        if (!fs.exists(path)) return null
        return runCatching { fs.source(path).buffer().use { it.readUtf8() } }.getOrNull()
    }

    /** 读取整个文件字节；不存在/失败抛 IllegalStateException。 */
    fun readBytes(path: Path): ByteArray {
        if (!fs.exists(path)) throw IllegalStateException("File not found: $path")
        return fs.source(path).buffer().use { it.readByteArray() }
    }

    // ------------------------------------------------------------------
    // 通用文件助手（供各服务做跨平台文件 IO，语义对齐 java.io.File）
    // ------------------------------------------------------------------

    fun exists(path: Path): Boolean = fs.exists(path)

    fun isDirectory(path: Path): Boolean = fs.metadataOrNull(path)?.isDirectory == true

    fun isFile(path: Path): Boolean = fs.metadataOrNull(path)?.isRegularFile == true

    /** 读取文本文件；不存在/失败抛 IllegalStateException（对齐 File.readText）。 */
    fun readText(path: Path): String =
        readTextOrNull(path) ?: throw IllegalStateException("File not found: $path")

    fun writeText(path: Path, content: String) {
        runCatching { fs.createDirectories(path.parent!!) }
        fs.sink(path).buffer().use { sink -> sink.writeUtf8(content) }
    }

    fun writeBytes(path: Path, bytes: ByteArray) {
        runCatching { fs.createDirectories(path.parent!!) }
        fs.sink(path).buffer().use { sink -> sink.write(bytes) }
    }

    /** 追加文本（创建文件或续写；对齐 File.appendText）。 */
    fun appendText(path: Path, content: String) {
        runCatching { fs.createDirectories(path.parent!!) }
        fs.appendingSink(path).buffer().use { sink -> sink.writeUtf8(content) }
    }

    fun mkdirs(path: Path) {
        fs.createDirectories(path)
    }

    fun deleteFile(path: Path) {
        runCatching { fs.delete(path) }
    }

    fun deleteRecursively(path: Path) {
        runCatching { fs.deleteRecursively(path) }
    }

    fun listFiles(path: Path): List<Path> =
        if (fs.exists(path)) fs.list(path) else emptyList()

    fun lastModifiedMillis(path: Path): Long =
        fs.metadataOrNull(path)?.lastModifiedAtMillis ?: 0L

    fun fileSize(path: Path): Long =
        fs.metadataOrNull(path)?.size ?: 0L

    fun rename(source: Path, target: Path): Boolean = try {
        fs.atomicMove(source, target)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 获取运行目录
     */
    fun getRunDirectory(runId: String): Path {
        return PathSafety.resolveStorageChild(runsRoot, runId, "run_id")
    }

    /**
     * 获取运行清单文件路径
     */
    fun getRunManifestPath(runId: String): Path {
        return getRunDirectory(runId) / "run_manifest.json"
    }

    /**
     * 读取运行清单
     */
    fun readRunManifest(runId: String): JsonObject? {
        val manifestFile = getRunManifestPath(runId)
        val content = readTextOrNull(manifestFile) ?: return null
        return runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull()
    }

    /**
     * 写入运行清单
     */
    fun writeRunManifest(runId: String, manifest: JsonObject) {
        val manifestFile = getRunManifestPath(runId)
        runCatching { fs.createDirectories(manifestFile.parent!!) }
        writeTextAtomically(manifestFile, json.encodeToString(JsonObject.serializer(), manifest))
    }

    /**
     * 列出所有运行 ID
     */
    fun listRunIds(): List<String> {
        if (!fs.exists(runsRoot)) {
            return emptyList()
        }

        return fs.list(runsRoot)
            .filter { dir -> fs.metadataOrNull(dir)?.isDirectory == true && fs.exists(dir / "run_manifest.json") }
            .mapNotNull { it.name }
            .sorted()
    }

    /**
     * 列出所有运行清单（按修改时间倒序）
     */
    fun listRunManifests(): List<JsonObject> {
        if (!fs.exists(runsRoot)) {
            return emptyList()
        }

        return fs.list(runsRoot)
            .filter { dir -> fs.metadataOrNull(dir)?.isDirectory == true }
            .mapNotNull { dir ->
                val manifestFile = dir / "run_manifest.json"
                val content = readTextOrNull(manifestFile)
                if (content != null) {
                    try {
                        val manifest = json.parseToJsonElement(content).jsonObject
                        manifest to (fs.metadataOrNull(manifestFile)?.lastModifiedAtMillis ?: 0L)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * 检查运行是否存在
     */
    fun runExists(runId: String): Boolean {
        return try {
            fs.exists(getRunManifestPath(runId))
        } catch (e: InvalidStorageIdentifierException) {
            false
        }
    }

    /**
     * 获取对话会话目录
     */
    fun getDialogueSessionsDirectory(runId: String): Path {
        return getRunDirectory(runId) / "dialogue/sessions"
    }

    fun getDialogueSessionManifestFile(runId: String, sessionId: String): Path {
        PathSafety.validateStorageId(sessionId, "session_id")
        return getDialogueSessionsDirectory(runId) / "$sessionId/session_manifest.json"
    }

    /**
     * 列出对话会话 ID
     */
    fun listDialogueSessionIds(runId: String): List<String> {
        val sessionsDir = getDialogueSessionsDirectory(runId)
        if (!fs.exists(sessionsDir)) {
            return emptyList()
        }

        return fs.list(sessionsDir)
            .filter { fs.metadataOrNull(it)?.isDirectory == true }
            .mapNotNull { it.name }
            .filter { PathSafety.STORAGE_ID_PATTERN.matches(it) }
            .sorted()
    }

    /**
     * 加载会话清单
     */
    fun loadSessionManifest(runId: String, sessionId: String): JsonObject {
        val manifestFile = getDialogueSessionManifestFile(runId, sessionId)
        val content = readTextOrNull(manifestFile)
            ?: throw NoSuchElementException("Session manifest not found: $sessionId")

        return try {
            json.decodeFromString(JsonObject.serializer(), content)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load session manifest: ${e.message}", e)
        }
    }

    /**
     * 获取章节目录
     */
    fun getChaptersDirectory(runId: String): Path {
        return getRunDirectory(runId) / "chapters"
    }

    fun getChapterFile(runId: String, chapterId: String): Path {
        PathSafety.validateStorageId(chapterId, "chapter_id")
        return getChaptersDirectory(runId) / "$chapterId.json"
    }

    /**
     * 列出章节 ID
     */
    fun listChapterIds(runId: String): List<String> {
        val chaptersDir = getChaptersDirectory(runId)
        if (!fs.exists(chaptersDir)) {
            return emptyList()
        }

        return fs.list(chaptersDir)
            .filter { fs.metadataOrNull(it)?.isRegularFile == true && it.name.endsWith(".json") }
            .mapNotNull { it.name.removeSuffix(".json") }
            .sorted()
    }

    fun listChapters(runId: String): List<JsonObject> {
        return listChapterIds(runId).mapNotNull { chapterId ->
            try {
                val file = getChapterFile(runId, chapterId)
                val content = readTextOrNull(file) ?: return@mapNotNull null
                val value = json.decodeFromString(JsonObject.serializer(), content)
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
    fun getModelSettingsPath(): Path {
        return storageRoot / "model_settings.json"
    }

    /**
     * 读取模型设置
     */
    fun readModelSettings(): ModelSettings? {
        val settingsFile = getModelSettingsPath()
        val content = readTextOrNull(settingsFile) ?: return null
        return try {
            json.decodeFromString<ModelSettings>(content)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 写入模型设置
     */
    fun writeModelSettings(settings: ModelSettings) {
        val settingsFile = getModelSettingsPath()
        runCatching { fs.createDirectories(settingsFile.parent!!) }
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
     * 获取 API 密钥（保留占位实现；实际由 ModelApiKeyService 从 Keystore 获取）。
     */
    fun getApiKey(provider: String): String? {
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
        val sessionDir = getDialogueSessionsDirectory(runId) / sessionId
        val manifestFile = sessionDir / "session_manifest.json"
        val content = readTextOrNull(manifestFile)
            ?: throw NoSuchElementException("Session manifest not found: $sessionId")

        val decoded = json.decodeFromString(JsonObject.serializer(), content)
        return withCharacterAvatars(decoded, runId)
    }

    // ------------------------------------------------------------------
    // 角色头像（对齐 Python persona_avatars.py / chat service _serialize_session）
    // ------------------------------------------------------------------

    /** 角色头像文件：文件名 = sha256(角色名 UTF-8).png（与 Python 一致）。 */
    fun avatarFile(runId: String, character: String): Path {
        val digest = character.encodeToByteArray().toByteString().sha256().hex()
        return getRunDirectory(runId) / "avatars/$digest.png"
    }

    /** 头像版本号：mtime-大小；无头像文件返回空串（与 Python avatar_version 语义一致）。 */
    fun avatarVersion(runId: String, character: String): String {
        val file = avatarFile(runId, character)
        val metadata = fs.metadataOrNull(file)
        return if (metadata?.isRegularFile == true) {
            "${metadata.lastModifiedAtMillis ?: 0}-${metadata.size}"
        } else {
            ""
        }
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

    val runsDir: Path
        get() = runsRoot

}
