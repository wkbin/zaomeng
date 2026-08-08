package top.wkbin.zaomeng.ktor.services

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.LoaderOptions
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 小说蒸馏执行器（Ktor 版，迁移自 Python src/web/pipeline/automatic_steps.py + progress.py）。
 *
 * 本轮实现（P1-P2 最小闭环）：
 * - 单角色非分块蒸馏：逐角色串行调用 LLM，产出 persona frontmatter（12 字段，对齐 PersonaService 读取）
 * - 逐角色进度上报：progress.current_character / completed_count / message（"正在蒸馏 X"、"X 蒸馏完成"）→ App 显示对齐 Python
 * - 落盘：run_dir/artifacts/characters/<novel_id>/<name>/PROFILE.md（--- frontmatter ---，refreshArtifactIndex 自动识别）
 * - 停止控制：检查 manifest.control.stop_requested
 *
 * 后续轮次（P3-P5）：分块并行+合并、关系图蒸馏、增量重蒸馏、resume 跳过已完成。
 */
class DistillExecutor(
    private val storage: StorageService,
    private val llm: LlmClient,
) {
    companion object {
        private const val TAG = "DistillExecutor"
        /** 对齐 Python workflow.py max_tokens：单次蒸馏 1800。 */
        const val DISTILL_MAX_TOKENS = 1800
        const val EXCERPT_MAX_CHARS = 12_000
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val yaml = Yaml(SafeConstructor(LoaderOptions()))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, Job>()

    /** 启动蒸馏任务（幂等：同一 run 已有任务则不重复启动）。 */
    fun start(runId: String, characters: List<String>) {
        val normalized = characters.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return
        val existing = running.putIfAbsent(runId, Job()) // 占位，避免并发双启动
        if (existing != null) return
        val job = scope.launch {
            try {
                execute(runId, normalized)
            } catch (e: Exception) {
                Log.e(TAG, "Distillation failed for run=$runId: ${e.message}", e)
                fail(runId, e.message ?: "蒸馏失败")
            } finally {
                running.remove(runId)
            }
        }
        running[runId] = job
    }

    /** 是否正在蒸馏。 */
    fun isRunning(runId: String): Boolean = running.containsKey(runId)

    private suspend fun execute(runId: String, characters: List<String>) {
        val manifest = storage.readRunManifest(runId) ?: return
        val runDir = storage.getRunDirectory(runId)

        // 0. 读小说文本
        val sourcePath = manifest["novel_sources"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("source_path")?.jsonPrimitive?.contentOrNull
        if (sourcePath.isNullOrBlank()) throw IllegalStateException("找不到小说源文件。")
        val novelText = runCatching { File(sourcePath).readText() }.getOrNull()
            ?: throw IllegalStateException("小说源文件读取失败。")
        val novelId = (manifest["novel_id"]?.jsonPrimitive?.contentOrNull ?: runId)

        updateProgress(runId, storage.readRunManifest(runId) ?: return) { p ->
            p.put("stage", "text_loaded")
            p.put("message", "已载入小说文本")
            p.put("current_character", "")
            p.put("completed_count", 0)
            p.put("total_characters", characters.size)
            p.put("completed_characters", JsonArray(emptyList()))
        }
        updateProgress(runId, storage.readRunManifest(runId) ?: return) { p ->
            p.put("stage", "characters_ready")
            p.put("message", "已锁定 ${characters.size} 个待蒸馏角色")
            p.put("total_characters", characters.size)
        }

        val completed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (character in characters) {
            if (isStopRequested(runId)) {
                finalizeStopped(runId, completed)
                return
            }
            // 正在蒸馏 X
            updateProgress(runId, storage.readRunManifest(runId) ?: return) { p ->
                p.put("stage", "distilling")
                p.put("message", "正在蒸馏 $character")
                p.put("current_character", character)
                p.put("completed_count", completed.size)
                p.put("total_characters", characters.size)
                p.put("completed_characters", buildJsonArray { completed.forEach { add(JsonPrimitive(it)) } })
            }
            val profile = distillCharacter(runId, character, novelText)
            if (profile != null) {
                runCatching { writeProfile(runDir, novelId, character, profile) }
                    .onSuccess { completed.add(character) }
                    .onFailure { e ->
                        Log.e(TAG, "Write profile failed for $character: ${e.message}", e)
                        failed.add(character)
                    }
            } else {
                failed.add(character)
            }
            // X 蒸馏完成
            updateProgress(runId, storage.readRunManifest(runId) ?: return) { p ->
                p.put("stage", "distilling")
                p.put("message", "$character 蒸馏完成")
                p.put("current_character", "")
                p.put("completed_count", completed.size)
                p.put("total_characters", characters.size)
                p.put("completed_characters", buildJsonArray { completed.forEach { add(JsonPrimitive(it)) } })
                if (failed.isNotEmpty()) {
                    p.put("failed_characters", buildJsonArray { failed.forEach { add(JsonPrimitive(it)) } })
                }
            }
        }
        finalizeSuccess(runId, characters, completed)
    }

    // ------------------------------------------------------------------
    // 单角色蒸馏
    // ------------------------------------------------------------------

    private suspend fun distillCharacter(runId: String, character: String, novelText: String): Map<String, Any?>? {
        val excerpt = sampleExcerpt(novelText, character)
        if (excerpt.isBlank()) {
            Log.w(TAG, "No excerpt found for character: $character")
        }
        val system = buildString {
            append("你是小说人物蒸馏引擎。从给定的小说文本中提取指定角色的完整人物档案。\n")
            append("只输出 YAML 文档（键值对，列表用 YAML 序列），不要任何解释、代码围栏或多余文字。\n")
            append("字段如下：\n")
            append("core_identity: 一句话概括角色核心身份\n")
            append("story_role: 在故事中的角色定位\n")
            append("gender: 性别\n")
            append("age_stage: 年龄段\n")
            append("appearance_feature: 外貌特征\n")
            append("habit_action: 习惯性动作或小动作\n")
            append("speech_style: 说话风格与常用语气\n")
            append("temperament_type: 气质类型\n")
            append("stress_response: 压力或冲突下的反应模式\n")
            append("key_bonds: 关键羁绊（列表，每人一句关系描述）\n")
            append("preference_like: 喜欢的事物（列表）\n")
            append("dislike_hate: 厌恶的事物（列表）\n")
            append("所有文本用简体中文。文本证据不足的字段写“证据不足”。")
        }
        val user = buildString {
            append("目标角色：$character\n\n")
            append("小说文本节选（含该角色出场的部分）：\n")
            append(excerpt.take(EXCERPT_MAX_CHARS))
        }
        val content = llm.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage(role = "system", content = system),
                LlmClient.ChatMessage(role = "user", content = user),
            ),
            temperature = 0.3,
            maxTokens = DISTILL_MAX_TOKENS,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (content.isBlank()) {
            Log.w(TAG, "Empty distill output for: $character")
            return null
        }
        return parseYamlProfile(content, character)
    }

    /** 按角色名提取文本节选：命中句 + 前后相邻句，拼接到上限。 */
    private fun sampleExcerpt(text: String, character: String, maxChars: Int = EXCERPT_MAX_CHARS): String {
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return text.take(maxChars)
        val kept = ArrayDeque<String>()
        var total = 0
        sentences.forEachIndexed { index, sentence ->
            val hit = sentence.contains(character)
            val adjacent = index > 0 && sentences.getOrNull(index - 1)?.contains(character) == true ||
                index + 1 < sentences.size && sentences.getOrNull(index + 1)?.contains(character) == true
            if (hit || adjacent) {
                kept.addLast(sentence)
                total += sentence.length
            }
        }
        if (kept.isEmpty()) return text.take(maxChars)
        val joined = kept.joinToString("\n")
        return if (joined.length <= maxChars) joined else joined.take(maxChars)
    }

    private fun splitSentences(text: String): List<String> = text
        .replace(Regex("(?<=[。！？!?；;])\\s*"), "\n")
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** 解析 LLM 输出的 YAML 人物档案（容忍 ```yaml 围栏与前后缀噪音）。 */
    private fun parseYamlProfile(content: String, character: String): Map<String, Any?>? {
        val yamlText = extractYamlBlock(content)
        val parsed = runCatching {
            @Suppress("UNCHECKED_CAST")
            yaml.load(yamlText) as? Map<String, Any?>
        }.getOrNull() ?: run {
            Log.w(TAG, "YAML parse failed for: $character; output head: ${content.take(200)}")
            null
        } ?: return null
        val normalized = linkedMapOf<String, Any?>()
        parsed.forEach { (key, value) ->
            when (value) {
                is List<*> -> {
                    val items = value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() && it != "证据不足" }
                    if (items.isNotEmpty()) normalized[key.toString()] = items
                }
                is String -> {
                    val s = value.trim()
                    if (s.isNotEmpty() && s != "证据不足") normalized[key.toString()] = s
                }
                else -> {}
            }
        }
        if (normalized.isEmpty()) {
            Log.w(TAG, "Empty normalized profile for: $character")
            return null
        }
        // 缺失字段兜底
        if (!normalized.containsKey("core_identity")) normalized["core_identity"] = "身份信息待补充"
        if (!normalized.containsKey("speech_style")) normalized["speech_style"] = "风格待补充"
        return normalized
    }

    private fun extractYamlBlock(content: String): String {
        var t = content.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").removePrefix("yaml").trim()
            t = t.trimEnd().removeSuffix("```").trim()
        }
        // 去掉 frontmatter 定界符（若 LLM 输出了 --- ... ---）
        if (t.startsWith("---")) {
            val parts = t.split("---", limit = 3)
            if (parts.size >= 2) t = parts[1].trim()
        }
        return t
    }

    // ------------------------------------------------------------------
    // 落盘与进度
    // ------------------------------------------------------------------

    private fun writeProfile(runDir: File, novelId: String, character: String, profile: Map<String, Any?>) {
        // 路径安全：角色名/小说名是用户可控输入，必须过滤路径分隔/穿越
        val safeNovel = PathSafety.sanitizePathComponent(novelId, "novelId")
        val safeChar = PathSafety.sanitizePathComponent(character, "character")
        val dir = File(runDir, "artifacts/characters/$safeNovel/$safeChar")
        dir.mkdirs()
        val frontmatter = yaml.dump(profile).trimEnd()
        storage.writeTextAtomically(File(dir, "PROFILE.md"), "---\n$frontmatter\n---\n")
    }

    /** 读-改-写 run_manifest 的 progress 字段。 */
    private fun updateProgress(runId: String, manifest: JsonObject, block: (JsonObjectBuilder) -> Unit) {
        val now = Instant.now().toString()
        val oldProgress = manifest["progress"]?.jsonObject ?: JsonObject(emptyMap())
        val newProgress = buildJsonObject {
            oldProgress.forEach { (k, v) -> put(k, v) }
            block(this)
            put("updated_at", now)
        }
        val updated = buildJsonObject {
            manifest.forEach { (k, v) -> if (k != "progress") put(k, v) }
            put("progress", newProgress)
            put("updated_at", now)
        }
        storage.writeRunManifest(runId, updated)
    }

    private fun isStopRequested(runId: String): Boolean {
        val manifest = storage.readRunManifest(runId) ?: return false
        return manifest["control"]?.jsonObject?.get("stop_requested")?.jsonPrimitive?.booleanOrNull == true
    }

    private fun finalizeSuccess(runId: String, planned: List<String>, completed: List<String>) {
        val manifest = storage.readRunManifest(runId) ?: return
        // stop 竞态：写回前复查 stop_requested，避免把 stopRun 刚写的 status=stopped 盖回 ready
        if (isStopRequested(runId)) {
            finalizeStopped(runId, completed)
            return
        }
        val failed = planned.filterNot { it in completed }
        val now = Instant.now().toString()
        val updated = buildJsonObject {
            manifest.forEach { (k, v) -> if (k !in setOf("status", "progress", "summary", "updated_at")) put(k, v) }
            put("status", "ready")
            put("updated_at", now)
            put("progress", buildJsonObject {
                (manifest["progress"]?.jsonObject ?: JsonObject(emptyMap())).forEach { (k, v) -> put(k, v) }
                put("stage", "completed")
                put("message", "蒸馏完成：${completed.size} 名人物${if (failed.isNotEmpty()) "，${failed.size} 名失败" else ""}。")
                put("current_character", "")
                put("completed_count", completed.size)
                put("total_characters", planned.size)
                put("completed_characters", buildJsonArray { completed.forEach { add(JsonPrimitive(it)) } })
                if (failed.isNotEmpty()) {
                    put("failed_characters", buildJsonArray { failed.forEach { add(JsonPrimitive(it)) } })
                }
                put("graph_status", "pending")
                put("updated_at", now)
            })
            put("summary", buildJsonObject {
                put("characters_total", planned.size)
                put("characters_completed", completed.size)
                put("graph_status", "pending")
                put("status_text", if (failed.isNotEmpty()) "部分完成" else "可使用")
            })
        }
        storage.writeRunManifest(runId, updated)
        Log.i(TAG, "Distillation completed for run=$runId: ${completed.size}/${planned.size} characters")
    }

    private fun finalizeStopped(runId: String, completed: List<String>) {
        val manifest = storage.readRunManifest(runId) ?: return
        val now = Instant.now().toString()
        val updated = buildJsonObject {
            manifest.forEach { (k, v) -> if (k !in setOf("status", "progress", "summary", "updated_at")) put(k, v) }
            put("status", "stopped")
            put("updated_at", now)
            put("progress", buildJsonObject {
                (manifest["progress"]?.jsonObject ?: JsonObject(emptyMap())).forEach { (k, v) -> put(k, v) }
                put("stage", "stopped")
                put("message", "蒸馏已停止，已完成 ${completed.size} 名人物。")
                put("current_character", "")
                put("completed_count", completed.size)
                put("completed_characters", buildJsonArray { completed.forEach { add(JsonPrimitive(it)) } })
                put("updated_at", now)
            })
            put("summary", buildJsonObject {
                put("characters_total", completed.size)
                put("characters_completed", completed.size)
                put("graph_status", "pending")
                put("status_text", "已停止")
            })
        }
        storage.writeRunManifest(runId, updated)
        Log.i(TAG, "Distillation stopped for run=$runId: ${completed.size} characters done")
    }

    private fun fail(runId: String, message: String) {
        val manifest = storage.readRunManifest(runId) ?: return
        val now = Instant.now().toString()
        val planned = (manifest["progress"]?.jsonObject?.get("total_characters")?.jsonPrimitive?.intOrNull)
            ?: (manifest["summary"]?.jsonObject?.get("characters_total")?.jsonPrimitive?.intOrNull)
            ?: 0
        val updated = buildJsonObject {
            manifest.forEach { (k, v) -> if (k !in setOf("status", "progress", "summary", "updated_at")) put(k, v) }
            put("status", "failed")
            put("updated_at", now)
            put("progress", buildJsonObject {
                (manifest["progress"]?.jsonObject ?: JsonObject(emptyMap())).forEach { (k, v) -> put(k, v) }
                put("stage", "failed")
                put("message", message)
                put("updated_at", now)
            })
            put("summary", buildJsonObject {
                put("characters_total", planned)
                put("characters_completed", 0)
                put("graph_status", "failed")
                put("status_text", "失败")
            })
        }
        storage.writeRunManifest(runId, updated)
    }
}
