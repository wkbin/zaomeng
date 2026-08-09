package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.plugins.api.ChatActionRequest
import top.wkbin.zaomeng.plugins.api.NpcGeneratorRequest
import okio.Path
import top.wkbin.zaomeng.platform.base64Decode
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid
import top.wkbin.zaomeng.platform.readZipEntries

/**
 * 插件包管理服务
 *
 * 对应 Python src/web/service_facades/plugins.py 的：
 * inspect / install 两阶段插件包安装，以及对话中插件动作。
 *
 * 注意：插件动作（chat_action / npc_generator）在 Python 端由 Python 运行时执行，
 * Ktor 后端没有该运行时，返回明确错误而非 404。enhancer 状态仅做会话内存储。
 */
class PluginOperationsService(
    private val storage: StorageService,
    private val pluginService: PluginService,
    private val host: top.wkbin.zaomeng.plugins.api.PluginHost? = null,
) {
    companion object {
        private const val MAX_PACKAGE_BYTES = 10 * 1024 * 1024
        private const val MAX_ENTRIES = 500
        private const val MAX_EXTRACTED_BYTES = 100L * 1024 * 1024
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    /** 检查插件包（两阶段安装第一步）。对应 Python inspect_plugin_package。 */
    fun inspect(filename: String, contentBase64: String): JsonObject {
        val safeName = filename.substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotBlank) ?: "plugin.zip"
        val bytes = runCatching { base64Decode(contentBase64) }
            .getOrElse { throw IllegalArgumentException("插件包 Base64 内容无效。", it) }
        require(bytes.isNotEmpty() && bytes.size <= MAX_PACKAGE_BYTES) { "插件包不能为空且不能超过 10 MB。" }
        val token = randomUuid().replace("-", "")
        val tmpRoot = storage.getStorageRoot() / "plugin-staging/$token"
        try {
            extractSafely(bytes, tmpRoot)
            val pluginJson = findPluginJson(tmpRoot)
                ?: throw IllegalArgumentException("插件包内缺少 plugin.json。")
            val manifest = json.parseToJsonElement(storage.readText(pluginJson)).jsonObject
            val id = manifest["id"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: throw IllegalArgumentException("plugin.json 缺少 id 字段。")
            if (!PathSafety.STORAGE_ID_PATTERN.matches(id)) {
                throw IllegalArgumentException("插件 id 不合法：$id")
            }
            val installedDir = storage.getStorageRoot() / "plugins" / id
            val installedManifest = installedDir / "plugin.json"
            val currentVersion = if (storage.exists(installedManifest)) {
                runCatching {
                    json.parseToJsonElement(storage.readText(installedManifest)).jsonObject["version"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }.getOrDefault("")
            } else {
                ""
            }
            val fileCount = countFiles(tmpRoot)
            val extractedBytes = totalFileBytes(tmpRoot)
            return buildJsonObject {
                put("token", token)
                put("plugin", buildPluginDto(id, manifest))
                put("operation", if (currentVersion.isNotBlank()) "update" else "install")
                put("blocked_reason", "")
                put("current_version", currentVersion)
                put("compatible", true)
                put("host_api_version", "1")
                put("file_count", fileCount)
                put("extracted_bytes", extractedBytes)
                put("staging_dir", tmpRoot.toString())
            }
        } catch (error: Throwable) {
            storage.deleteRecursively(tmpRoot)
            throw error
        }
    }

    /** 安装已检查的插件包（两阶段第二步）。对应 Python install_inspected_plugin_package。 */
    fun install(token: String, confirmPermissions: Boolean, allowUpdate: Boolean): JsonObject {
        val normalizedToken = token.trim()
        if (!PathSafety.STORAGE_ID_PATTERN.matches(normalizedToken)) throw IllegalArgumentException("无效的安装令牌。")
        val staging = storage.getStorageRoot() / "plugin-staging" / normalizedToken
        val pluginJson = findPluginJson(staging)
            ?: throw IllegalArgumentException("安装令牌已失效，请重新检查插件包。")
        val manifest = json.parseToJsonElement(storage.readText(pluginJson)).jsonObject
        val id = manifest["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val version = manifest["version"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (id.isEmpty()) throw IllegalArgumentException("plugin.json 缺少 id 字段。")
        val pluginsRoot = storage.getStorageRoot() / "plugins"
        storage.mkdirs(pluginsRoot)
        val target = pluginsRoot / id
        if (storage.exists(target)) {
            if (!allowUpdate) throw IllegalArgumentException("同名插件已安装，如确认升级请勾选允许更新。")
            storage.deleteRecursively(target)
        }
        if (!storage.rename(staging, target)) {
            // Windows/跨盘回退：复制后删除
            copyRecursively(staging, target)
            storage.deleteRecursively(staging)
        }
        // 更新 enabled.json 默认启用
        pluginService.setEnabled(id, true)
        val dto = buildPluginDto(id, manifest)
        return buildJsonObject {
            dto.forEach { (key, value) -> put(key, value) }
            put("version", version)
            put("enabled", true)
            put("status", "enabled")
        }
    }

    /** 设置生成增强器状态（会话内存储）。对应 Python set_generation_enhancer_state。 */
    fun setEnhancerState(runId: String, sessionId: String, pluginId: String, enhancerId: String, enabled: Boolean): JsonObject {
        val session = loadSession(runId, sessionId)
        val existing = session["plugin_enhancer_states"]?.jsonObject ?: JsonObject(emptyMap())
        val pluginStates = existing[pluginId]?.jsonObject ?: JsonObject(emptyMap())
        val updatedPluginStates = buildJsonObject {
            pluginStates.forEach { (key, value) -> put(key, value) }
            put(enhancerId, enabled)
        }
        val updatedStates = buildJsonObject {
            existing.forEach { (key, value) -> if (key != pluginId) put(key, value) }
            put(pluginId, updatedPluginStates)
        }
        val file = storage.getDialogueSessionManifestFile(runId, sessionId)
        val updated = buildJsonObject {
            session.forEach { (key, value) -> put(key, value) }
            put("plugin_enhancer_states", updatedStates)
            put("updated_at", nowIsoString())
        }
        storage.writeTextAtomically(file, json.encodeToString(JsonObject.serializer(), updated))
        return updated
    }

    /** 插件聊天动作：内置插件直接分发（对齐 Python invoke_plugin_chat_action）；第三方插件需 Python 运行时。 */
    suspend fun invokeChatAction(runId: String, sessionId: String, pluginId: String, actionId: String, seedText: String, direction: String): JsonObject {
        val plugin = pluginService.findBuiltin(pluginId)
            ?: throw IllegalArgumentException("插件「$pluginId」不是内置插件，执行需要 Python 运行时，当前 Ktor 后端暂不支持。")
        val pluginHost = requireNotNull(host) { "插件宿主未配置" }
        val request = ChatActionRequest(
            runId = runId,
            sessionId = sessionId,
            seedText = seedText,
            direction = direction,
            config = configMap(pluginService.getConfig(pluginId)),
        )
        val result = plugin.executeChatAction(actionId, request, pluginHost)
        return buildJsonObject {
            put("suggestion", JsonPrimitive(result.suggestion))
            put("suggestions", buildJsonArray {
                result.suggestions.forEach {
                    add(buildJsonObject {
                        put("label", JsonPrimitive(it.label))
                        put("suggestion", JsonPrimitive(it.suggestion))
                    })
                }
            })
            put("notice", JsonPrimitive(result.notice))
        }
    }

    /** 临时 NPC 生成：内置插件分发并写入会话（对齐 Python invoke_plugin_temporary_npc_generator）。 */
    suspend fun invokeNpcGenerator(runId: String, sessionId: String, pluginId: String, generatorId: String, direction: String): JsonObject {
        val plugin = pluginService.findBuiltin(pluginId)
            ?: throw IllegalArgumentException("插件「$pluginId」不是内置插件，执行需要 Python 运行时，当前 Ktor 后端暂不支持。")
        val pluginHost = requireNotNull(host) { "插件宿主未配置" }
        val request = NpcGeneratorRequest(
            runId = runId,
            sessionId = sessionId,
            direction = direction,
            config = configMap(pluginService.getConfig(pluginId)),
        )
        val result = plugin.generateTemporaryNpc(generatorId, request, pluginHost)
        val session = loadSession(runId, sessionId) // host 已写入 temporary_npcs，重新加载返回
        val name = result.npc["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return buildJsonObject {
            put("npc", result.npc)
            put("session", session)
            put("notice", JsonPrimitive(result.notice.ifBlank { if (name.isNotBlank()) "${name}已加入当前场景。" else "" }))
        }
    }

    /** 把插件 config（JsonObject）转为插件接口的 Map 配置（primitive 取字符串，其他 toString）。 */
    private fun configMap(config: JsonObject): Map<String, Any?> = config.mapValues { (_, value) ->
        when (value) {
            is JsonPrimitive -> value.contentOrNull ?: value.toString()
            else -> value.toString()
        }
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private fun loadSession(runId: String, sessionId: String): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        return runCatching { storage.getDialogueSession(runId, sessionId) }.getOrNull()
            ?: throw NoSuchElementException("Session not found: $sessionId")
    }

    private fun extractSafely(bytes: ByteArray, root: Path) {
        storage.mkdirs(root)
        val entries = readZipEntries(bytes)
        require(entries.size <= MAX_ENTRIES) { "插件包文件数量超过限制。" }
        var extractedBytes = 0L
        for (entry in entries) {
            require(entry.content.size <= MAX_EXTRACTED_BYTES) { "插件包内存在超大文件。" }
            extractedBytes += entry.content.size
            require(extractedBytes <= MAX_EXTRACTED_BYTES) { "插件包解压后体积超过限制。" }
            val target = resolveSafe(root, entry.name.replace('\\', '/'))
            storage.mkdirs(target.parent!!)
            storage.writeBytes(target, entry.content)
        }
    }

    private fun resolveSafe(root: Path, name: String): Path {
        val normalizedRoot = root.normalized()
        val candidate = (root / name).normalized()
        require(candidate == normalizedRoot || !candidate.relativeTo(normalizedRoot).toString().startsWith("..")) { "插件包包含不安全路径。" }
        return candidate
    }

    private fun copyRecursively(source: Path, target: Path) {
        storage.mkdirs(target)
        for (child in storage.listFiles(source)) {
            val dest = target / child.name
            if (storage.isDirectory(child)) {
                copyRecursively(child, dest)
            } else {
                storage.writeBytes(dest, storage.readBytes(child))
            }
        }
    }

    private fun findPluginJson(root: Path): Path? {
        val direct = root / "plugin.json"
        if (storage.isFile(direct)) return direct
        // 允许 zip 内单层目录包裹
        return storage.listFiles(root).firstOrNull { storage.isDirectory(it) }?.let { dir ->
            (dir / "plugin.json").takeIf { storage.isFile(it) }
        }
    }

    private fun countFiles(root: Path): Int = totalFileBytesAndCount(root).second

    private fun totalFileBytes(root: Path): Long = totalFileBytesAndCount(root).first

    private fun totalFileBytesAndCount(root: Path): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        fun visit(path: Path) {
            storage.listFiles(path).forEach { child ->
                if (storage.isDirectory(child)) {
                    visit(child)
                } else if (storage.isFile(child)) {
                    bytes += storage.fileSize(child)
                    count++
                }
            }
        }
        visit(root)
        return bytes to count
    }

    private fun buildPluginDto(id: String, manifest: JsonObject): JsonObject = buildJsonObject {
        put("id", id)
        put("name", manifest["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: id)
        put("version", manifest["version"]?.jsonPrimitive?.contentOrNull.orEmpty())
        put("apiVersion", manifest["apiVersion"]?.jsonPrimitive?.contentOrNull.orEmpty())
        put("description", manifest["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
        put("permissions", manifest["permissions"]?.jsonArray ?: JsonArray(emptyList()))
        put("settings", manifest["settings"]?.jsonArray ?: JsonArray(emptyList()))
        put("config", JsonObject(emptyMap()))
        put("contributes", manifest["contributes"]?.jsonObject ?: JsonObject(emptyMap()))
        put("defaultEnabled", manifest["defaultEnabled"]?.jsonPrimitive?.booleanOrNull ?: true)
        put("enabled", true)
        put("status", "enabled")
        put("error", "")
        put("source", "third-party")
    }
}
