package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.*
import top.wkbin.zaomeng.plugins.api.Plugin
import okio.Path
import top.wkbin.zaomeng.platform.nowEpochMillis
import top.wkbin.zaomeng.platform.nowIsoString

/**
 * 插件服务：官方内置插件（source=official，来自 :builtin-plugins 模块）+ 第三方插件
 * （source=third-party，storage root plugins/ 下的物理目录）。
 *
 * 内置插件无物理目录：配置/日志存 plugins/.builtin/<id>/；内置不可卸载。
 */
class PluginService(
    private val storage: StorageService,
    private val builtins: List<Plugin> = emptyList(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    // 日志 jsonl 需要单行完整 JSON，不能用 prettyPrint（否则逐行解析失败）
    private val compactJson = Json { ignoreUnknownKeys = true; isLenient = true }
    private val root get() = storage.getStorageRoot() / "plugins"
    private val stateFile get() = root / "enabled.json"
    private val builtinRoot get() = root / ".builtin"

    fun list(): JsonObject {
        val enabled = readEnabled()
        // 首次运行（无状态文件）时，默认开启的内置插件按 defaultEnabled 生效
        val useDefaults = !storage.isFile(stateFile)
        val items = buildJsonArray {
            // 官方内置插件（source=official）
            builtins.sortedBy { it.manifest.name }.forEach { plugin ->
                add(builtinItem(plugin, enabled, useDefaults))
            }
            // 第三方插件（现有逻辑）
            storage.listFiles(root).filter { storage.isDirectory(it) && !it.name.startsWith(".") }.sortedBy { it.name }.forEach { dir ->
                itemFor(dir.name, enabled, useDefaults)?.let { add(it) }
            }
        }
        return buildJsonObject { put("items", items) }
    }

    /**
     * 构造单个插件项。
     *
     * 旧第三方 ZIP 只作为待迁移资源保存；声明式插件由 DeclarativePluginLoader 校验后
     * 才标记为 executable，enabled.json 中的历史残留不会被误解释成可执行。
     */
    private fun itemFor(pluginId: String, enabled: Set<String>, useDefaults: Boolean = false): JsonObject? {
        val builtin = findBuiltin(pluginId)
        if (builtin != null) return builtinItem(builtin, enabled, useDefaults)
        val manifest = root / "$pluginId/plugin.json"
        if (!storage.exists(manifest)) return null
        return try {
            val value = json.parseToJsonElement(storage.readText(manifest)).jsonObject
            externalItem(pluginId, value, enabled)
        } catch (_: Exception) { null }
    }

    private fun externalItem(pluginId: String, value: JsonObject, enabled: Set<String>): JsonObject {
        val evaluation = DeclarativePluginLoader.evaluate(pluginId, value)
        val isEnabled = evaluation.executable && pluginId in enabled
        return buildJsonObject {
            value.forEach { (key, item) ->
                if (key == "execution") return@forEach
                if (key == "contributes" && evaluation.plugin != null) return@forEach
                put(key, item)
            }
            put("id", value["id"]?.jsonPrimitive?.contentOrNull ?: pluginId)
            put("enabled", isEnabled)
            put("status", when {
                isEnabled -> "enabled"
                evaluation.executable -> "disabled"
                else -> "stored"
            })
            put("source", "third-party")
            put("config", getConfig(pluginId))
            put("defaultEnabled", value["defaultEnabled"]?.jsonPrimitive?.booleanOrNull ?: false)
            evaluation.plugin?.manifest?.let { manifest ->
                put("contributes", contributesJson(manifest))
            }
            put("executable", evaluation.executable)
            put("executionMode", evaluation.executionMode)
            put("capabilityNotice", evaluation.capabilityNotice)
        }
    }

    private fun contributesJson(manifest: top.wkbin.zaomeng.plugins.api.PluginManifest): JsonObject = buildJsonObject {
        put("chatActions", buildJsonArray {
            manifest.contributes.chatActions.forEach { action ->
                add(buildJsonObject {
                    put("id", action.id)
                    put("title", action.title)
                    put("icon", action.icon)
                    put("placement", action.placement)
                })
            }
        })
        put("generationEnhancers", buildJsonArray {
            manifest.contributes.generationEnhancers.forEach { enhancer ->
                add(buildJsonObject {
                    put("id", enhancer.id)
                    put("title", enhancer.title)
                    put("icon", enhancer.icon)
                })
            }
        })
        put("temporaryNpcGenerators", buildJsonArray {
            manifest.contributes.temporaryNpcGenerators.forEach { generator ->
                add(buildJsonObject {
                    put("id", generator.id)
                    put("title", generator.title)
                    put("icon", generator.icon)
                })
            }
        })
    }

    private fun builtinItem(plugin: Plugin, enabled: Set<String>, useDefaults: Boolean = false): JsonObject {
        val m = plugin.manifest
        return buildJsonObject {
            put("id", m.id)
            put("name", m.name)
            put("version", m.version)
            put("apiVersion", m.apiVersion)
            put("description", m.description)
            put("permissions", buildJsonArray { m.permissions.forEach { add(JsonPrimitive(it)) } })
            put("settings", buildJsonArray {
                m.settings.forEach { s ->
                    add(buildJsonObject {
                        put("key", s.key)
                        put("title", s.label)
                        put("type", s.type)
                        put("default", s.defaultValue)
                        put("options", buildJsonArray {
                            s.options.forEach { add(buildJsonObject { put("value", it); put("label", it) }) }
                        })
                    })
                }
            })
            put("config", getConfig(m.id))
            put("contributes", buildJsonObject {
                put("chatActions", buildJsonArray {
                    m.contributes.chatActions.forEach { a ->
                        add(buildJsonObject { put("id", a.id); put("title", a.title); put("icon", a.icon); put("placement", a.placement) })
                    }
                })
                put("generationEnhancers", buildJsonArray {
                    m.contributes.generationEnhancers.forEach { e ->
                        add(buildJsonObject { put("id", e.id); put("title", e.title); put("icon", e.icon) })
                    }
                })
                put("temporaryNpcGenerators", buildJsonArray {
                    m.contributes.temporaryNpcGenerators.forEach { g ->
                        add(buildJsonObject { put("id", g.id); put("title", g.title); put("icon", g.icon) })
                    }
                })
            })
            val isEnabled = m.id in enabled || (useDefaults && m.defaultEnabled)
            put("defaultEnabled", m.defaultEnabled)
            put("enabled", isEnabled)
            put("status", if (isEnabled) "enabled" else "disabled")
            put("error", "")
            put("source", "official")
            put("executable", true)
            put("executionMode", "builtin-kotlin")
            put("capabilityNotice", "由造梦内置 Kotlin 宿主执行。")
        }
    }

    fun setEnabled(pluginId: String, value: Boolean): JsonObject {
        val normalized = pluginId.trim()
        // 内置插件 id 为点分格式（com.zaomeng.*），不套用 STORAGE_ID_PATTERN
        if (!isBuiltin(normalized) && !PathSafety.STORAGE_ID_PATTERN.matches(normalized)) {
            throw IllegalArgumentException("Invalid plugin id")
        }
        if (!isKnown(normalized)) throw NoSuchElementException("Plugin not found: $normalized")
        if (!isBuiltin(normalized) && value && !isExecutableExternal(normalized)) {
            val notice = readExternalManifest(normalized)
                ?.let { DeclarativePluginLoader.evaluate(normalized, it).capabilityNotice }
                .orEmpty()
                .ifBlank { THIRD_PARTY_STORED_NOTICE }
            throw IllegalArgumentException(notice)
        }
        // 首次显式变更前把默认开启的内置插件一起落盘：
        // 否则状态文件里只有当前这一个插件，其它默认插件会全部变成关闭。
        val enabled = if (storage.isFile(stateFile)) {
            readEnabled().toMutableSet()
        } else {
            builtins.filter { it.manifest.defaultEnabled }.map { it.manifest.id }.toMutableSet()
        }
        if (value) enabled += normalized else enabled -= normalized
        storage.mkdirs(root)
        storage.writeTextAtomically(stateFile, json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("enabled", buildJsonArray { enabled.sorted().forEach { add(JsonPrimitive(it)) } })
        }))
        // 返回完整插件对象（App 端用它整体替换列表项，缺字段会导致名称/来源丢失）
        return itemFor(normalized, enabled)
            ?: throw NoSuchElementException("Plugin not found: $normalized")
    }

    fun getConfig(pluginId: String): JsonObject {
        val directory = pluginDirectory(pluginId)
        val config = directory / "config.json"
        return if (storage.exists(config)) json.decodeFromString(JsonObject.serializer(), storage.readText(config)) else JsonObject(emptyMap())
    }

    fun updateConfig(pluginId: String, config: JsonObject): JsonObject {
        val directory = pluginDirectory(pluginId)
        storage.mkdirs(directory)
        storage.writeTextAtomically(directory / "config.json", json.encodeToString(JsonObject.serializer(), config))
        return config
    }

    fun readData(pluginId: String, key: String): String? {
        val directory = pluginDirectory(pluginId) / "data"
        val safeKey = PathSafety.validateStorageId(key, "plugin data key")
        val file = directory / "$safeKey.txt"
        return if (storage.isFile(file)) storage.readText(file) else null
    }

    fun writeData(pluginId: String, key: String, value: String) {
        val directory = pluginDirectory(pluginId) / "data"
        val safeKey = PathSafety.validateStorageId(key, "plugin data key")
        storage.mkdirs(directory)
        storage.writeTextAtomically(directory / "$safeKey.txt", value)
    }

    /** 读取插件日志（jsonl，最多返回最近 200 条）。 */
    fun logs(pluginId: String): JsonObject {
        val logFile = pluginDirectory(pluginId) / "plugin-logs.jsonl"
        val entries = buildJsonArray {
            if (storage.isFile(logFile)) {
                storage.readText(logFile).lines().takeLast(200).forEach { line ->
                    runCatching { json.parseToJsonElement(line) }.getOrNull()?.let { add(it) }
                }
            }
        }
        return buildJsonObject { put("items", entries) }
    }

    /** 追加一条插件日志（由 PluginHost.log 调用）。 */
    fun appendLog(pluginId: String, level: String, message: String) {
        val directory = pluginDirectory(pluginId)
        storage.mkdirs(directory)
        val logFile = directory / "plugin-logs.jsonl"
        val entry = buildJsonObject {
            put("ts", nowIsoString())
            put("level", level)
            put("message", message)
        }
        storage.appendText(logFile, compactJson.encodeToString(JsonObject.serializer(), entry) + "\n")
    }

    fun uninstall(pluginId: String): JsonObject {
        val normalized = pluginId.trim()
        if (isBuiltin(normalized)) {
            throw IllegalArgumentException("官方内置插件不可卸载。")
        }
        val directory = pluginDirectory(normalized)
        val trash = storage.getStorageRoot() / "plugin-trash"
        storage.mkdirs(trash)
        val target = trash / "$normalized-${nowEpochMillis()}"
        if (!storage.rename(directory, target)) throw IllegalStateException("Unable to uninstall plugin")
        return buildJsonObject {
            put("status", "uninstalled")
            put("plugin_id", normalized)
            put("recoverable_path", target.toString())
            put("uninstalled_at", nowIsoString())
        }
    }

    fun isBuiltin(pluginId: String): Boolean = builtins.any { it.manifest.id == pluginId.trim() }

    fun findBuiltin(pluginId: String): Plugin? = builtins.firstOrNull { it.manifest.id == pluginId.trim() }

    fun findPlugin(pluginId: String): Plugin? {
        val normalized = pluginId.trim()
        findBuiltin(normalized)?.let { return it }
        if (!PathSafety.STORAGE_ID_PATTERN.matches(normalized)) return null
        val raw = readExternalManifest(normalized) ?: return null
        return DeclarativePluginLoader.evaluate(normalized, raw).plugin
    }

    fun isExecutableExternal(pluginId: String): Boolean =
        PathSafety.STORAGE_ID_PATTERN.matches(pluginId.trim()) &&
            readExternalManifest(pluginId.trim())?.let {
                DeclarativePluginLoader.evaluate(pluginId.trim(), it).executable
            } == true

    fun generationEnhancerRule(pluginId: String, enhancerId: String): String? {
        val normalized = pluginId.trim()
        if (isBuiltin(normalized) || !PathSafety.STORAGE_ID_PATTERN.matches(normalized)) return null
        val raw = readExternalManifest(normalized) ?: return null
        return DeclarativePluginLoader.evaluate(normalized, raw)
            .generationRecipes[enhancerId.trim()]?.rule
    }

    private fun isKnown(pluginId: String): Boolean = isBuiltin(pluginId) || storage.isFile(root / "$pluginId/plugin.json")

    private fun pluginDirectory(pluginId: String): Path {
        val normalized = pluginId.trim()
        // 内置插件 id 为点分格式（com.zaomeng.*），跳过 STORAGE_ID_PATTERN 校验
        if (isBuiltin(normalized)) return builtinRoot / normalized
        if (!PathSafety.STORAGE_ID_PATTERN.matches(normalized)) throw IllegalArgumentException("Invalid plugin id")
        val directory = root / normalized
        if (!storage.exists(directory / "plugin.json")) throw NoSuchElementException("Plugin not found: $normalized")
        return directory
    }

    private fun readEnabled(): Set<String> {
        if (!storage.isFile(stateFile)) return emptySet()
        val parsed = try {
            val value = json.parseToJsonElement(storage.readText(stateFile)).jsonObject["enabled"]?.jsonArray
            value?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
        // 自愈旧版本“启用单个插件导致默认插件全部关闭”的损坏状态：
        // 集合非空但一个默认插件都不在时，把默认插件合并回来。
        val hasAnyDefault = parsed.any { id ->
            builtins.any { it.manifest.id == id && it.manifest.defaultEnabled }
        }
        return if (parsed.isNotEmpty() && !hasAnyDefault) {
            parsed + builtins.filter { it.manifest.defaultEnabled }.map { it.manifest.id }
        } else {
            parsed
        }
    }

    private fun readExternalManifest(pluginId: String): JsonObject? {
        if (!PathSafety.STORAGE_ID_PATTERN.matches(pluginId.trim())) return null
        val manifest = root / "$pluginId/plugin.json"
        if (!storage.isFile(manifest)) return null
        return runCatching {
            json.parseToJsonElement(storage.readText(manifest)).jsonObject
        }.getOrNull()
    }

    private companion object {
        const val THIRD_PARTY_STORED_NOTICE =
            "第三方插件包目前只能保存，不能执行；造梦不会运行其中的 Python 或其他任意代码。"
    }
}
