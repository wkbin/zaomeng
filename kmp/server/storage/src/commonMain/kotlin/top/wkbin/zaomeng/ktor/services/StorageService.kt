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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import top.wkbin.zaomeng.db.DomainStore
import top.wkbin.zaomeng.db.DocumentStore
import top.wkbin.zaomeng.db.FileSystemDocumentStore
import top.wkbin.zaomeng.ktor.models.*
import top.wkbin.zaomeng.platform.nowEpochMillis
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.toHex

/**
 * 存储服务
 *
 * 管理运行数据的持久化访问，对应 Python 的 manifest/store.py。
 *
 * 生产环境统一走 Room（SQLite）文档存储；文件系统后端仅用于测试/开发。
 * 所有业务路径语义（目录、递归删除、改名、mtime/大小）由 [DocumentStore] 承载。
 */
class StorageService(
    private val storageRoot: Path,
    private val store: DocumentStore,
    private val domain: DomainStore? = null,
) {
    /** 测试/开发用的文件系统后端构造。 */
    constructor(storageRoot: Path, fs: FileSystem = FileSystem.SYSTEM) :
        this(storageRoot, FileSystemDocumentStore(fs), null)

    /**
     * 按路径分片的写锁：不同 run/文件可并行写，同一路径仍串行。
     * 固定 64 路，避免全局锁串行化与无界锁表增长。
     */
    private val writeLocks = Array(64) { SimpleLock() }
    private data class CachedJsonDocument(
        val modifiedAt: Long,
        val size: Long,
        val value: JsonObject,
    )
    private val sessionManifestCache = HashMap<String, CachedJsonDocument>()
    private val sessionManifestCacheLock = SimpleLock()

    private fun lockFor(target: Path): SimpleLock {
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
        get() = (storageRoot / "runs").also { store.mkdirs(it) }

    /** 获取存储根目录（供外部访问） */
    fun getStorageRoot(): Path = storageRoot

    /** 原子写入：先落完整内容再替换（Room 后端即事务 upsert）。 */
    fun writeTextAtomically(target: Path, content: String) {
        writeBytesAtomically(target, content.encodeToByteArray())
    }

    /** 字节版原子写入（避免大文件先解码成 String 再回写）。 */
    fun writeBytesAtomically(target: Path, bytes: ByteArray) {
        lockFor(target).withLock {
            writeBytesLocked(target, bytes)
        }
    }

    private fun writeBytesLocked(target: Path, bytes: ByteArray) {
        val mtime = nowEpochMillis()
        store.writeBytes(target, bytes, mtime)
        domain?.onWrite(target, bytes, mtime)
        sessionManifestCacheLock.withLock { sessionManifestCache.remove(target.toString()) }
    }

    /** 读取文本文件；不存在返回 null。 */
    fun readTextOrNull(path: Path): String? = store.readBytes(path)?.decodeToString()

    /** 读取整个文件字节；不存在/失败抛 IllegalStateException。 */
    fun readBytes(path: Path): ByteArray =
        store.readBytes(path) ?: throw IllegalStateException("File not found: $path")

    // ------------------------------------------------------------------
    // 通用文件助手（供各服务做跨平台文件 IO，语义对齐 java.io.File）
    // ------------------------------------------------------------------

    fun exists(path: Path): Boolean = store.exists(path)

    fun isDirectory(path: Path): Boolean = store.isDirectory(path)

    fun isFile(path: Path): Boolean = store.isFile(path)

    /** 读取文本文件；不存在/失败抛 IllegalStateException（对齐 File.readText）。 */
    fun readText(path: Path): String =
        readTextOrNull(path) ?: throw IllegalStateException("File not found: $path")

    fun writeText(path: Path, content: String) {
        writeTextAtomically(path, content)
    }

    fun writeBytes(path: Path, bytes: ByteArray) {
        writeBytesAtomically(path, bytes)
    }

    /** 追加文本（创建文件或续写；对齐 File.appendText）。 */
    fun appendText(path: Path, content: String) {
        lockFor(path).withLock {
            val existing = store.readBytes(path) ?: ByteArray(0)
            writeBytesLocked(path, existing + content.encodeToByteArray())
        }
    }

    fun mkdirs(path: Path) {
        store.mkdirs(path)
    }

    fun deleteFile(path: Path) {
        store.deleteFile(path)
        domain?.onDelete(path)
        sessionManifestCacheLock.withLock { sessionManifestCache.remove(path.toString()) }
    }

    fun deleteRecursively(path: Path) {
        store.deleteRecursively(path)
        domain?.onDelete(path)
        val prefix = path.toString().trimEnd('/') + "/"
        sessionManifestCacheLock.withLock {
            sessionManifestCache.keys.removeAll { it == path.toString() || it.startsWith(prefix) }
        }
    }

    /** 删除某个角色的 Room 实体行，供人物删除流程在删除档案目录后同步清理。 */
    fun deletePersonaEntities(runId: String, novelId: String, names: Collection<String>) {
        domain?.deletePersonas(runId, novelId, names)
    }

    fun listFiles(path: Path): List<Path> = store.listFiles(path)

    fun lastModifiedMillis(path: Path): Long = store.updatedAtMillis(path) ?: 0L

    fun fileSize(path: Path): Long = store.fileSize(path)

    fun rename(source: Path, target: Path): Boolean {
        val renamed = store.rename(source, target)
        if (renamed) {
            sessionManifestCacheLock.withLock {
                sessionManifestCache.remove(source.toString())
                sessionManifestCache.remove(target.toString())
            }
        }
        return renamed
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
        store.mkdirs(manifestFile.parent!!)
        writeTextAtomically(manifestFile, json.encodeToString(JsonObject.serializer(), manifest))
    }

    /**
     * 列出所有运行 ID
     */
    fun listRunIds(): List<String> {
        domain?.let { return it.listRunIds() }
        if (!store.isDirectory(runsRoot)) {
            return emptyList()
        }

        return store.listFiles(runsRoot)
            .filter { dir -> store.isDirectory(dir) && store.isFile(dir / "run_manifest.json") }
            .mapNotNull { it.name }
            .sorted()
    }

    /**
     * 列出所有运行清单（按修改时间倒序）
     */
    fun listRunManifests(): List<JsonObject> {
        domain?.let { return it.listRunManifests() }
        if (!store.isDirectory(runsRoot)) {
            return emptyList()
        }

        return store.listFiles(runsRoot)
            .filter { dir -> store.isDirectory(dir) }
            .mapNotNull { dir ->
                val manifestFile = dir / "run_manifest.json"
                val content = readTextOrNull(manifestFile)
                if (content != null) {
                    try {
                        val manifest = json.parseToJsonElement(content).jsonObject
                        manifest to lastModifiedMillis(manifestFile)
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
        domain?.let { return it.runExists(runId) }
        return try {
            store.isFile(getRunManifestPath(runId))
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

    private fun getTranscriptArchiveDirectory(runId: String, sessionId: String): Path {
        PathSafety.validateStorageId(sessionId, "session_id")
        return getDialogueSessionsDirectory(runId) / "$sessionId/transcript-archive"
    }

    data class CompactedTranscript(
        val recent: JsonArray,
        val startIndex: Int,
        val totalCount: Int,
    )

    /**
     * Keep the generation manifest bounded. Older entries are moved in batches so
     * the archive is not rewritten on every turn.
     */
    fun compactSessionTranscript(
        runId: String,
        sessionId: String,
        manifest: JsonObject,
        combined: JsonArray,
    ): CompactedTranscript {
        var startIndex = manifest["transcript_start"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
        val archiveDir = getTranscriptArchiveDirectory(runId, sessionId)

        // A full materialized session may have been saved by an edit/correction path.
        // Its stale archive is no longer authoritative and will be rebuilt if needed.
        if (startIndex == 0 && exists(archiveDir)) {
            deleteRecursively(archiveDir)
        }

        var recent = combined
        if (recent.size > TRANSCRIPT_ROLLOVER_THRESHOLD) {
            val archiveCount = recent.size - TRANSCRIPT_RECENT_TARGET
            val archived = JsonArray(recent.take(archiveCount))
            mkdirs(archiveDir)
            val chunkFile = archiveDir / "${startIndex.toString().padStart(10, '0')}.json"
            writeTextAtomically(chunkFile, json.encodeToString(JsonArray.serializer(), archived))
            startIndex += archived.size
            recent = JsonArray(recent.drop(archiveCount))
        }
        return CompactedTranscript(
            recent = recent,
            startIndex = startIndex,
            totalCount = startIndex + recent.size,
        )
    }

    private fun materializeSessionTranscript(runId: String, sessionId: String, manifest: JsonObject): JsonObject {
        val startIndex = manifest["transcript_start"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
        if (startIndex == 0) return manifest
        val archived = buildList {
            val archiveDir = getTranscriptArchiveDirectory(runId, sessionId)
            if (isDirectory(archiveDir)) {
                listFiles(archiveDir)
                    .filter { isFile(it) && it.name.endsWith(".json") }
                    .sortedBy { it.name }
                    .forEach { file ->
                        val items = readTextOrNull(file)?.let { raw ->
                            runCatching { json.decodeFromString(JsonArray.serializer(), raw) }.getOrNull()
                        }
                        if (items != null) addAll(items)
                    }
            }
        }
        val recent = manifest["transcript"] as? JsonArray ?: JsonArray(emptyList())
        val full = JsonArray(archived + recent)
        return buildJsonObject {
            manifest.forEach { (key, value) ->
                if (key !in setOf("transcript", "transcript_start", "transcript_count")) put(key, value)
            }
            put("transcript", full)
            put("transcript_start", 0)
            put("transcript_count", full.size)
        }
    }

    /**
     * 列出对话会话 ID
     */
    fun listDialogueSessionIds(runId: String): List<String> {
        domain?.let { return it.listSessionIds(runId) }
        val sessionsDir = getDialogueSessionsDirectory(runId)
        if (!store.isDirectory(sessionsDir)) {
            return emptyList()
        }

        return store.listFiles(sessionsDir)
            .filter { store.isDirectory(it) }
            .mapNotNull { it.name }
            .filter { PathSafety.STORAGE_ID_PATTERN.matches(it) }
            .sorted()
    }

    /**
     * 加载会话清单
     */
    fun loadSessionManifest(runId: String, sessionId: String): JsonObject {
        val manifestFile = getDialogueSessionManifestFile(runId, sessionId)
        val modifiedAt = store.updatedAtMillis(manifestFile)
            ?: throw NoSuchElementException("Session manifest not found: $sessionId")
        val size = store.fileSize(manifestFile)
        val cacheKey = manifestFile.toString()
        val cached = sessionManifestCacheLock.withLock {
            sessionManifestCache[cacheKey]
                ?.takeIf { it.modifiedAt == modifiedAt && it.size == size }
                ?.value
        }
        if (cached != null) return cached
        val content = readTextOrNull(manifestFile)
            ?: throw NoSuchElementException("Session manifest not found: $sessionId")

        return try {
            json.decodeFromString(JsonObject.serializer(), content).also { parsed ->
                sessionManifestCacheLock.withLock {
                    sessionManifestCache[cacheKey] = CachedJsonDocument(modifiedAt, size, parsed)
                    while (sessionManifestCache.size > MAX_CACHED_SESSION_MANIFESTS) {
                        sessionManifestCache.remove(sessionManifestCache.keys.first())
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load session manifest: ${e.message}", e)
        }
    }

    /**
     * 在与原子写相同的路径锁内完成会话清单读-改-写，避免插件动作、对话提交和后台状态刷新互相覆盖。
     */
    fun updateSessionManifest(
        runId: String,
        sessionId: String,
        transform: (JsonObject) -> JsonObject,
    ): JsonObject {
        val manifestFile = getDialogueSessionManifestFile(runId, sessionId)
        return lockFor(manifestFile).withLock {
            val content = store.readBytes(manifestFile)?.decodeToString()
                ?: throw NoSuchElementException("Session manifest not found: $sessionId")
            val current = runCatching { json.decodeFromString(JsonObject.serializer(), content) }
                .getOrElse { throw IllegalStateException("Failed to load session manifest: ${it.message}", it) }
            val updated = transform(current)
            writeBytesLocked(
                manifestFile,
                json.encodeToString(JsonObject.serializer(), updated).encodeToByteArray(),
            )
            updated
        }
    }

    private companion object {
        private const val MAX_CACHED_SESSION_MANIFESTS = 32
        private const val TRANSCRIPT_RECENT_TARGET = 80
        private const val TRANSCRIPT_ROLLOVER_THRESHOLD = 120
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
        if (!store.isDirectory(chaptersDir)) {
            return emptyList()
        }

        return store.listFiles(chaptersDir)
            .filter { store.isFile(it) && it.name.endsWith(".json") }
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
        store.mkdirs(settingsFile.parent!!)
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
                profile.reasoningEffort?.let { put("reasoning_effort", it) }
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
        domain?.let {
            return it.listSessionManifests(runId).map { manifest ->
                withCharacterAvatars(manifest, runId)
            }
        }
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
        return withCharacterAvatars(materializeSessionTranscript(runId, sessionId, decoded), runId)
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
        return if (store.isFile(file)) {
            "${store.updatedAtMillis(file) ?: 0}-${store.fileSize(file)}"
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
