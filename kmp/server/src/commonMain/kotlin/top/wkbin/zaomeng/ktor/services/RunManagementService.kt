package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.ktor.models.*
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.base64Decode
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid

/**
 * 运行管理服务
 *
 * 对应 Python 的 WebRunService 中的运行管理功能
 */
class RunManagementService(
    private val storageService: StorageService,
    private val distillExecutor: DistillExecutor? = null,
) {
    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * 创建新运行
     *
     * 对应 Python: run_service.create_run()
     */
    fun createRun(
        novelName: String,
        novelContentBase64: String,
        characters: List<String> = emptyList(),
        maxSentences: Int = 120,
        maxChars: Int = 50000,
        autoRun: Boolean = false,
        deferRun: Boolean = false
    ): JsonObject {
        // 验证参数
        if (novelName.isBlank()) {
            throw IllegalArgumentException("Novel name cannot be blank")
        }
        if (novelContentBase64.isBlank()) {
            throw IllegalArgumentException("Novel content cannot be blank")
        }
        if (characters.isEmpty()) {
            throw IllegalArgumentException("Characters list cannot be empty")
        }
        if (maxSentences !in 20..300) {
            throw IllegalArgumentException("maxSentences must be between 20 and 300")
        }
        if (maxChars !in 2000..200000) {
            throw IllegalArgumentException("maxChars must be between 2000 and 200000")
        }
        // 对齐 Python create_run：非 defer 运行必须已配置模型（自动/手动蒸馏都依赖 LLM）
        if (!deferRun && distillExecutor?.isConfigured() == false) {
            throw IllegalArgumentException("请先在设置中完成模型配置。")
        }

        // 生成运行 ID
        val runId = generateId()
        val timestamp = nowIsoString()

        // 解码小说内容（字节直写文件，避免 String 往返拷贝）
        val novelBytes = try {
            base64Decode(novelContentBase64)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid base64 content: ${e.message}")
        }

        // 创建运行目录
        val runDir = storageService.getRunDirectory(runId)
        storageService.mkdirs(runDir)

        // 创建子目录
        storageService.mkdirs(runDir / "dialogue/sessions")
        storageService.mkdirs(runDir / "chapters")
        storageService.mkdirs(runDir / "personas")

        // 写入小说内容
        val novelFile = runDir / "novel.txt"
        storageService.writeBytesAtomically(novelFile, novelBytes)
        // char_count 需要一次 UTF-8 解码（仅用于估算与清单，不参与再次编码）
        val novelContent = novelBytes.decodeToString()

        // 创建运行清单
        val manifest = buildJsonObject {
            put("run_id", runId)
            put("novel_name", novelName)
            put("novel_sources", buildJsonArray {
                add(buildJsonObject {
                    put("source_name", novelName.ifBlank { novelFile.name })
                    put("source_path", novelFile.toString())
                    put("kind", "import")
                    put("timestamp", timestamp)
                    put("byte_size", novelBytes.size)
                    put("char_count", novelContent.length)
                })
            })
            put("characters", buildJsonArray { characters.forEach { add(JsonPrimitive(it)) } })
            put("locked_characters", buildJsonArray { characters.forEach { add(JsonPrimitive(it)) } })
            put("max_sentences", maxSentences)
            put("max_chars", maxChars)
            put("created_at", timestamp)
            put("updated_at", timestamp)
            put("status", if (deferRun) "pending" else "running")
            put("progress", buildJsonObject {
                put("stage", if (deferRun) "pending" else "starting")
                put("percent", 0.0)
            })
        }

        // 写入运行清单
        storageService.writeRunManifest(runId, manifest)

        // 对齐 Python create_run(auto_run=True) 的 _start_background_run：自动启动蒸馏执行
        if (autoRun && !deferRun) {
            distillExecutor?.start(runId, characters)
        }

        PlatformLog.d(TAG, "Created run: $runId with ${characters.size} characters")

        return buildJsonObject {
            put("run_id", runId)
            put("novel_name", novelName)
            put("characters", buildJsonArray { characters.forEach { add(JsonPrimitive(it)) } })
            put("created_at", timestamp)
            put("status", manifest["status"]?.jsonPrimitive?.contentOrNull ?: "running")
        }
    }

    /**
     * 获取运行详情
     */
    fun getRun(runId: String): JsonObject {
        val manifest = storageService.readRunManifest(runId)
            ?: throw IllegalArgumentException("Run not found: $runId")

        // 响应注入实时 avatar_version（对齐 Python core.py _serialize_manifest）
        return storageService.withLiveAvatarVersions(manifest, runId)
    }

    /**
     * 列出所有运行
     */
    fun listRuns(): List<JsonObject> {
        val manifests = storageService.listRunManifests()
        return manifests
    }

    /**
     * 停止运行
     *
     * 对应 Python: run_service.stop_run()
     */
    fun stopRun(runId: String): JsonObject {
        val manifest = storageService.readRunManifest(runId)
            ?: throw IllegalArgumentException("Run not found: $runId")

        // 更新状态
        val updatedAt = nowIsoString()
        val updatedManifest = buildJsonObject {
            manifest.forEach { (key, value) -> put(key, value) }
            put("status", "stopped")
            put("updated_at", updatedAt)
            put("control", buildJsonObject {
                manifest["control"]?.let { existing ->
                    if (existing is JsonObject) existing.forEach { (key, value) -> put(key, value) }
                }
                put("stop_requested", true)
                put("stop_requested_at", updatedAt)
            })
        }

        storageService.writeRunManifest(runId, updatedManifest)

        PlatformLog.d(TAG, "Stopped run: $runId")

        return buildJsonObject {
            put("run_id", runId)
            put("status", "stopped")
            put("updated_at", updatedAt)
        }
    }

    /**
     * 删除运行
     */
    fun deleteRun(runId: String): JsonObject {
        val runDir = storageService.getRunDirectory(runId)

        if (!storageService.exists(runDir)) {
            throw IllegalArgumentException("Run not found: $runId")
        }

        // 递归删除运行目录
        storageService.deleteRecursively(runDir)

        PlatformLog.d(TAG, "Deleted run: $runId")

        return buildJsonObject {
            put("run_id", runId)
            put("deleted", true)
        }
    }

    /**
     * 生成唯一 ID
     */
    private fun generateId(): String {
        return randomUuid().replace("-", "")
    }

    companion object {
        private const val TAG = "RunManagementService"
    }
}
