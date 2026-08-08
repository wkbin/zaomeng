package top.wkbin.zaomeng.ktor.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.LoaderOptions
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * 小说蒸馏执行器（Ktor 版，迁移自 Python src/web/pipeline/automatic_steps.py +
 * chunking.py + chunk_execution.py + generation.py + progress.py + quality.py）。
 *
 * P3-P5 范围（相对旧版 P1-P2 补齐）：
 * - 角色聚焦节选（前/中/后段证据分桶，对齐 novel_preparation.build_excerpt_payload）
 * - 长文分块蒸馏：并行分块草稿 + LLM 汇总合并（对齐 chunking/chunk_execution/generation）
 * - 增量蒸馏：已有 PROFILE 时 update_mode=incremental，existing_profiles 进 payload
 * - 人物关系图谱：单遍/分块关系抽取 + 落盘 artifacts/relations + manifest 关联
 * - 进度文案与 manifest 字段对齐 Python（progress/events/capabilities/quality/summary/timing）
 * - resume 跳过已完成角色
 *
 * 已知保留差异：repair/completion 二次修复（profile_repair.py / repair_orchestration.py）、
 * 关系图 mermaid/html 导出（WebUI 用）未迁移；角色别名表未打包。
 */
class DistillExecutor(
    private val context: Context?,
    private val storage: StorageService,
    private val llm: LlmClient?,
    private val promptLoader: PromptLoader?,
) {
    companion object {
        private const val TAG = "DistillExecutor"

        // 对齐 Python workflow.py 蒸馏常量
        private const val DISTILL_CHUNK_TRIGGER_CHARS = 18_000
        private const val DISTILL_CHUNK_TRIGGER_SENTENCES = 180
        private const val DISTILL_CHUNK_MAX_CHARS = 9_000
        private const val DISTILL_CHUNK_MAX_SENTENCES = 70
        private const val RELATION_CHUNK_TRIGGER_CHARS = 9_000
        private const val RELATION_CHUNK_TRIGGER_SENTENCES = 110
        private const val RELATION_CHUNK_MAX_CHARS = 4_800
        private const val RELATION_CHUNK_MAX_SENTENCES = 36
        private const val DISTILL_SINGLE_MAX_TOKENS = 1800
        private const val DISTILL_MERGE_MAX_TOKENS = 1800
        private const val RELATION_SINGLE_MAX_TOKENS = 1000
        private const val RELATION_CHUNK_MAX_TOKENS = 800
        private const val RELATION_MERGE_MAX_TOKENS = 1000
        private const val PARALLEL_WORKERS_CAP = 6
        private const val EXCERPT_MAX_CHARS = 12_000
        private const val RELATION_MAX_CHARS = 12_000
        private const val RELATION_MAX_SENTENCES = 80
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

    /** 模型是否已配置（对齐 Python restart_run_distill 的 model_is_configured 校验）。 */
    fun isConfigured(): Boolean =
        llm?.let { runCatching { it.isConfigured() }.getOrDefault(false) } == true

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    private suspend fun execute(runId: String, characters: List<String>) {
        val startedAt = Instant.now().toString()
        val manifest = storage.readRunManifest(runId) ?: return
        val runDir = storage.getRunDirectory(runId)
        val sourcePath = resolveSourcePath(manifest, runDir)
        if (sourcePath.isBlank()) throw IllegalStateException("找不到小说源文件。")
        val novelText = runCatching { File(sourcePath).readText() }.getOrNull()
            ?: throw IllegalStateException("小说源文件读取失败。")
        val novelId = (manifest["novel_id"]?.jsonPrimitive?.contentOrNull ?: runId)

        applyDistillProgress(runId, "text_loaded", mapOf("source_path" to sourcePath))
        val completedSet = readCompletedCharacters(runId)
        val pending = characters.filterNot { it in completedSet }
        applyDistillProgress(
            runId,
            "characters_ready",
            mapOf("total" to characters.size, "characters" to characters),
        )
        patchManifest(runId) { current ->
            val timing = (current["timing"]?.jsonObject ?: JsonObject(emptyMap())).let { old ->
                buildJsonObject {
                    old.forEach { (k, v) -> put(k, v) }
                    put("started_at", startedAt)
                }
            }
            buildJsonObject {
                current.forEach { (k, v) -> if (k != "timing") put(k, v) }
                put("timing", timing)
            }
        }

        val completed = completedSet.toMutableList()
        val failed = mutableListOf<String>()
        val characterDirs = linkedMapOf<String, String>()
        val distillPayloadPaths = linkedMapOf<String, String>()
        val distillChunkByCharacter = linkedMapOf<String, Map<String, Any?>>()
        val qualityMatched = mutableSetOf<String>()
        val qualityMissing = mutableSetOf<String>()
        val qualityStagePresence = mutableSetOf<String>()
        val qualityFocus = linkedMapOf<String, Map<String, Any?>>()
        val profileRepairCharacters = mutableListOf<String>()

        val maxSentences = manifest["max_sentences"]?.jsonPrimitive?.intOrNull ?: 120
        val maxChars = manifest["max_chars"]?.jsonPrimitive?.intOrNull ?: 50_000

        for (character in pending) {
            if (isStopRequested(runId)) {
                finalizeStopped(runId, completed)
                return
            }
            applyDistillProgress(runId, "drafting_character", mapOf("character" to character))
            val payload = buildDistillPayload(
                novelText = novelText,
                sourcePath = sourcePath,
                novelId = novelId,
                runDir = runDir,
                character = character,
                lockedCharacters = characters,
                maxSentences = maxSentences,
                maxChars = maxChars,
            )
            val payloadFile = File(runDir, "payloads/distill_${PathSafety.sanitizePathComponent(character, "character")}.json")
            payloadFile.parentFile?.mkdirs()
            storage.writeTextAtomically(payloadFile, json.encodeToString(JsonObject.serializer(), payloadToJson(payload)))
            distillPayloadPaths[character] = payloadFile.absolutePath

            val excerptFocus = (payload.request["excerpt_focus"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
            val matchedList = ((excerptFocus["matched_characters"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { it?.toString() }
            val missingList = ((excerptFocus["missing_characters"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { it?.toString() }
            if (character in matchedList) qualityMatched.add(character)
            if (character in missingList) qualityMissing.add(character)
            ((excerptFocus["stage_presence"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { it?.toString() }.forEach { qualityStagePresence.add(it) }

            val (content, chunkMeta) = generateCharacterProfileMarkdown(
                runId = runId,
                payload = payload,
                character = character,
                peerCharacters = characters,
            )
            if (content.isBlank()) throw IllegalArgumentException("$character 的人物档案生成为空。")
            qualityFocus[character] = mapOf(
                "matched" to (character in matchedList),
                "missing" to (character in missingList),
                "stage_presence" to qualityStagePresence.toList(),
                "chunk_count" to ((chunkMeta["chunk_count"] as? Number)?.toInt() ?: 1),
                "chunked" to (chunkMeta["chunked"] == true),
            )
            distillChunkByCharacter[character] = chunkMeta

            applyDistillProgress(runId, "materializing_character", mapOf("character" to character))
            runCatching { writeProfile(runDir, novelId, character, content) }
                .onSuccess {
                    completed.add(character)
                    characterDirs[character] = File(runDir, "artifacts/characters/${PathSafety.sanitizePathComponent(novelId, "novelId")}/${PathSafety.sanitizePathComponent(character, "character")}").absolutePath
                }
                .onFailure { e ->
                    Log.e(TAG, "Write profile failed for $character: ${e.message}", e)
                    failed.add(character)
                }
            applyDistillProgress(runId, "character_done", mapOf("character" to character, "completed" to completed, "total" to characters.size))

            patchManifest(runId) { current ->
                val refreshed = refreshArtifactIndex(runId, current)
                val artifacts = (current["artifacts"]?.jsonObject ?: JsonObject(emptyMap())).let { old ->
                    buildJsonObject {
                        old.forEach { (k, v) -> put(k, v) }
                        put("character_dirs", buildJsonObject { characterDirs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
                        put("payloads", buildJsonObject {
                            put("distill_characters", buildJsonObject { distillPayloadPaths.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
                        })
                    }
                }
                val capabilities = (current["capabilities"]?.jsonObject ?: JsonObject(emptyMap())).let { old ->
                    buildJsonObject {
                        old.forEach { (k, v) -> put(k, v) }
                        put("distill", buildJsonObject {
                            put("status", "running")
                            put("success", false)
                            put("updated_at", Instant.now().toString())
                            put("message", "canonical profiles generating")
                        })
                    }
                }
                buildJsonObject {
                    refreshed.forEach { (k, v) -> if (k !in setOf("artifacts", "capabilities")) put(k, v) }
                    put("artifacts", artifacts)
                    put("capabilities", capabilities)
                }
            }
        }

        // 关系图谱
        if (!isStopRequested(runId) && pending.isNotEmpty()) {
            runCatching {
                processRelationGraph(
                    runId = runId,
                    runDir = runDir,
                    novelText = novelText,
                    sourcePath = sourcePath,
                    novelId = novelId,
                    characters = characters,
                    maxSentences = maxSentences,
                    maxChars = maxChars,
                )
            }.onFailure { e ->
                Log.e(TAG, "Relation graph failed for run=$runId: ${e.message}", e)
                patchManifest(runId) { current ->
                    val progress = (current["progress"]?.jsonObject ?: JsonObject(emptyMap())).let { old ->
                        buildJsonObject {
                            old.forEach { (k, v) -> put(k, v) }
                            put("graph_status", "failed")
                            put("message", "人物蒸馏已完成，关系图谱生成失败：${e.message}")
                            put("stage", "graph_failed")
                        }
                    }
                    buildJsonObject {
                        current.forEach { (k, v) -> if (k != "progress") put(k, v) }
                        put("progress", progress)
                    }
                }
            }
        }

        finalizeSuccess(
            runId = runId,
            planned = characters,
            completed = completed,
            failed = failed,
            qualityMatched = qualityMatched,
            qualityMissing = qualityMissing,
            qualityStagePresence = qualityStagePresence,
            qualityFocus = qualityFocus,
            profileRepairCharacters = profileRepairCharacters,
            distillChunkByCharacter = distillChunkByCharacter,
            characterDirs = characterDirs,
            distillPayloadPaths = distillPayloadPaths,
            startedAt = startedAt,
        )
    }

    // ------------------------------------------------------------------
    // 角色蒸馏
    // ------------------------------------------------------------------

    private suspend fun generateCharacterProfileMarkdown(
        runId: String,
        payload: DistillPayload,
        character: String,
        peerCharacters: List<String>,
    ): Pair<String, Map<String, Any?>> {
        val excerpt = payload.request["excerpt"]?.toString()?.trim().orEmpty()
        if (shouldUseChunking(excerpt, DISTILL_CHUNK_TRIGGER_CHARS, DISTILL_CHUNK_TRIGGER_SENTENCES)) {
            return generateChunkedProfileMarkdown(runId, payload, character, peerCharacters, fallbackReason = "")
        }
        return try {
            val content = callLlm(
                DistillPromptBuilder.buildDistillMessages(payload, character, peerCharacters),
                DISTILL_SINGLE_MAX_TOKENS,
                0.2,
            )
            content to mapOf("chunked" to false, "chunk_count" to 1)
        } catch (e: Exception) {
            Log.w(TAG, "Single-pass distill failed for $character, retrying with chunked distill: ${e.message}")
            generateChunkedProfileMarkdown(runId, payload, character, peerCharacters, fallbackReason = e.message.orEmpty())
        }
    }

    private suspend fun generateChunkedProfileMarkdown(
        runId: String,
        payload: DistillPayload,
        character: String,
        peerCharacters: List<String>,
        fallbackReason: String,
    ): Pair<String, Map<String, Any?>> {
        val chunkEntries = buildDistillChunkPayloads(payload)
        if (chunkEntries.size <= 1) {
            val content = callLlm(
                DistillPromptBuilder.buildDistillMessages(payload, character, peerCharacters),
                DISTILL_SINGLE_MAX_TOKENS,
                0.2,
            )
            return content to mapOf("chunked" to false, "chunk_count" to 1)
        }
        val workers = minOf(PARALLEL_WORKERS_CAP, chunkEntries.size)
        val drafts = runChunkDrafts(chunkEntries, workers) { entry, index ->
            applyDistillProgress(
                runId,
                "chunking_character",
                mapOf(
                    "character" to character,
                    "chunk_index" to index,
                    "chunk_total" to chunkEntries.size,
                    "chunk_label" to entry.label,
                    "parallel_workers" to workers,
                ),
            )
            callLlm(
                DistillPromptBuilder.buildDistillMessages(
                    entry.payload,
                    character,
                    peerCharacters,
                    chunkLabel = entry.label,
                    chunkIndex = index,
                    chunkTotal = chunkEntries.size,
                    chunkMode = "partial",
                ),
                DISTILL_SINGLE_MAX_TOKENS,
                0.2,
            )
        }
        if (drafts.isEmpty()) throw IllegalArgumentException("$character 的分批蒸馏结果为空。")
        val chunkMeta = mapOf(
            "chunked" to true,
            "chunk_count" to chunkEntries.size,
            "fallback_reason" to fallbackReason,
            "parallel_workers" to workers,
        )
        if (drafts.size == 1) {
            return drafts.first().second to chunkMeta
        }
        applyDistillProgress(
            runId,
            "merging_character",
            mapOf("character" to character, "chunk_total" to drafts.size, "parallel_workers" to workers),
        )
        val merged = callLlm(
            DistillPromptBuilder.buildDistillMergeMessages(payload, character, peerCharacters, drafts, fallbackReason),
            DISTILL_MERGE_MAX_TOKENS,
            0.2,
        )
        return merged to chunkMeta
    }

    // ------------------------------------------------------------------
    // 关系图谱
    // ------------------------------------------------------------------

    private suspend fun processRelationGraph(
        runId: String,
        runDir: File,
        novelText: String,
        sourcePath: String,
        novelId: String,
        characters: List<String>,
        maxSentences: Int,
        maxChars: Int,
    ) {
        applyRelationProgress(runId, "rendering_graph", emptyMap())
        val relationMaxSentences = minOf(maxSentences, RELATION_MAX_SENTENCES)
        val relationMaxChars = minOf(maxChars, RELATION_MAX_CHARS)
        val payload = buildRelationPayload(
            novelText = novelText,
            sourcePath = sourcePath,
            novelId = novelId,
            characters = characters,
            maxSentences = relationMaxSentences,
            maxChars = relationMaxChars,
        )
        val (markdown, chunkMeta) = generateRelationMarkdown(runId, payload, characters)
        if (markdown.isBlank()) throw IllegalArgumentException("人物关系图谱结果为空。")

        val relationsDir = File(runDir, "artifacts/relations").apply { mkdirs() }
        val relationsFile = File(relationsDir, "${PathSafety.sanitizePathComponent(novelId, "novelId")}.relations.md")
        val relationsPayload = parseRelationsMarkdown(markdown, novelId)
        val frontmatter = yaml.dump(relationsPayload).trimEnd()
        storage.writeTextAtomically(relationsFile, "---\n$frontmatter\n---\n# RELATION_GRAPH\n")

        val relationCount = ((relationsPayload["relations"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()).size
        patchManifest(runId) { current ->
            val artifactIndex = (current["artifact_index"]?.jsonObject ?: JsonObject(emptyMap())).let { old ->
                buildJsonObject {
                    old.forEach { (k, v) -> put(k, v) }
                    put(
                        "relation_graph",
                        buildJsonObject {
                            put("relations_file", relationsFile.absolutePath)
                            put("relation_count", relationCount)
                            put("has_relation_graph", true)
                            put("chunked", chunkMeta["chunked"] == true)
                        },
                    )
                }
            }
            val capabilities = (current["capabilities"]?.jsonObject ?: JsonObject(emptyMap())).let { old ->
                buildJsonObject {
                    old.forEach { (k, v) -> put(k, v) }
                    put("export_graph", buildJsonObject {
                        put("status", "complete")
                        put("success", true)
                        put("updated_at", Instant.now().toString())
                        put("message", "relation graph exported")
                    })
                }
            }
            buildJsonObject {
                current.forEach { (k, v) -> if (k !in setOf("artifact_index", "capabilities", "has_relation_graph")) put(k, v) }
                put("artifact_index", artifactIndex)
                put("capabilities", capabilities)
                put("has_relation_graph", true)
            }
        }
        applyRelationProgress(runId, "graph_done", emptyMap())
        Log.i(TAG, "Relation graph generated for run=$runId: $relationCount relations")
    }

    private suspend fun generateRelationMarkdown(
        runId: String,
        payload: DistillPayload,
        characters: List<String>,
    ): Pair<String, Map<String, Any?>> {
        val excerpt = payload.request["excerpt"]?.toString()?.trim().orEmpty()
        if (shouldUseChunking(excerpt, RELATION_CHUNK_TRIGGER_CHARS, RELATION_CHUNK_TRIGGER_SENTENCES)) {
            return generateChunkedRelationMarkdown(runId, payload, characters, fallbackReason = "")
        }
        return try {
            val content = callLlm(
                DistillPromptBuilder.buildRelationMessages(payload, characters),
                RELATION_SINGLE_MAX_TOKENS,
                0.2,
            )
            content to mapOf("chunked" to false, "chunk_count" to 1)
        } catch (e: Exception) {
            Log.w(TAG, "Single-pass relation graph failed, retrying with chunked relation distill: ${e.message}")
            generateChunkedRelationMarkdown(runId, payload, characters, fallbackReason = e.message.orEmpty())
        }
    }

    private suspend fun generateChunkedRelationMarkdown(
        runId: String,
        payload: DistillPayload,
        characters: List<String>,
        fallbackReason: String,
    ): Pair<String, Map<String, Any?>> {
        val chunkEntries = buildRelationChunkPayloads(payload)
        if (chunkEntries.size <= 1) {
            val content = callLlm(
                DistillPromptBuilder.buildRelationMessages(payload, characters),
                RELATION_SINGLE_MAX_TOKENS,
                0.2,
            )
            return content to mapOf("chunked" to false, "chunk_count" to 1)
        }
        val workers = minOf(PARALLEL_WORKERS_CAP, chunkEntries.size)
        val drafts = runChunkDrafts(chunkEntries, workers) { entry, index ->
            applyRelationProgress(
                runId,
                "chunking_graph",
                mapOf(
                    "chunk_index" to index,
                    "chunk_total" to chunkEntries.size,
                    "chunk_label" to entry.label,
                    "parallel_workers" to workers,
                ),
            )
            callLlm(
                DistillPromptBuilder.buildRelationMessages(
                    entry.payload,
                    characters,
                    chunkLabel = entry.label,
                    chunkIndex = index,
                    chunkTotal = chunkEntries.size,
                    chunkMode = "partial",
                ),
                RELATION_CHUNK_MAX_TOKENS,
                0.2,
            )
        }
        if (drafts.isEmpty()) throw IllegalArgumentException("分批关系图谱结果为空。")
        val chunkMeta = mapOf(
            "chunked" to true,
            "chunk_count" to chunkEntries.size,
            "fallback_reason" to fallbackReason,
            "parallel_workers" to workers,
        )
        if (drafts.size == 1) return drafts.first().second to chunkMeta
        applyRelationProgress(runId, "merging_graph", mapOf("chunk_total" to drafts.size, "parallel_workers" to workers))
        val merged = callLlm(
            DistillPromptBuilder.buildRelationMergeMessages(payload, characters, drafts, fallbackReason),
            RELATION_MERGE_MAX_TOKENS,
            0.2,
        )
        return merged to chunkMeta
    }

    // ------------------------------------------------------------------
    // 分块（chunking.py）
    // ------------------------------------------------------------------

    private fun shouldUseChunking(text: String, triggerChars: Int, triggerSentences: Int): Boolean {
        val excerpt = text.trim()
        if (excerpt.isEmpty()) return false
        val sentenceCount = DistillExcerptBuilder.splitSentences(excerpt).size
        return excerpt.length > triggerChars || sentenceCount > triggerSentences
    }

    private fun splitTextIntoChunks(text: String, maxChars: Int, maxSentences: Int): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        var sentences = DistillExcerptBuilder.splitSentences(clean).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.isEmpty()) {
            sentences = clean.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(clean) }
        }
        val chunks = mutableListOf<String>()
        val current = mutableListOf<String>()
        var currentChars = 0
        for (sentence in sentences) {
            val units = if (sentence.length > maxChars) sentence.chunked(maxChars) else listOf(sentence)
            for (rawUnit in units) {
                val unit = rawUnit.trim()
                if (unit.isEmpty()) continue
                val projected = currentChars + unit.length + if (current.isNotEmpty()) 1 else 0
                if (current.isNotEmpty() && (current.size >= maxSentences || projected > maxChars)) {
                    chunks.add(current.joinToString("\n").trim())
                    current.clear()
                    currentChars = 0
                }
                current.add(unit)
                currentChars += unit.length + if (current.size > 1) 1 else 0
            }
        }
        if (current.isNotEmpty()) chunks.add(current.joinToString("\n").trim())
        return chunks.filter { it.isNotEmpty() }
    }

    private data class ChunkEntry(val label: String, val payload: DistillPayload)

    private fun buildDistillChunkPayloads(payload: DistillPayload): List<ChunkEntry> {
        val request = payload.request.toMutableMap()
        val excerpt = request["excerpt"]?.toString()?.trim().orEmpty()
        val stages = (request["excerpt_stages"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val entries = mutableListOf<ChunkEntry>()
        for ((stageKey, stageLabel) in listOf("start" to "前段", "mid" to "中段", "end" to "后段")) {
            val stageText = stages[stageKey]?.toString()?.trim().orEmpty()
            if (stageText.isEmpty()) continue
            val chunks = splitTextIntoChunks(stageText, DISTILL_CHUNK_MAX_CHARS, DISTILL_CHUNK_MAX_SENTENCES)
            chunks.forEachIndexed { index, chunkText ->
                val chunkRequest = request.toMutableMap()
                chunkRequest["excerpt"] = chunkText
                chunkRequest["excerpt_stages"] = linkedMapOf("start" to "", "mid" to "", "end" to "").apply {
                    put(stageKey, chunkText)
                }
                val focus = ((request["excerpt_focus"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.toMutableMap() ?: mutableMapOf())
                focus["strategy"] = "chunked_character_windows"
                chunkRequest["excerpt_focus"] = focus
                val chunkMeta = payload.meta.toMutableMap()
                chunkMeta["chunk_stage"] = stageKey
                chunkMeta["chunk_index"] = index + 1
                chunkMeta["chunk_total"] = chunks.size
                entries.add(
                    ChunkEntry(
                        label = if (chunks.size > 1) "$stageLabel-${index + 1}" else stageLabel,
                        payload = payload.copy(request = chunkRequest, meta = chunkMeta),
                    ),
                )
            }
        }
        if (entries.isNotEmpty()) return entries
        val excerptChunks = splitTextIntoChunks(excerpt, DISTILL_CHUNK_MAX_CHARS, DISTILL_CHUNK_MAX_SENTENCES)
        return excerptChunks.mapIndexed { index, chunkText ->
            val chunkRequest = request.toMutableMap()
            chunkRequest["excerpt"] = chunkText
            chunkRequest["excerpt_stages"] = linkedMapOf("start" to "", "mid" to "", "end" to "")
            val chunkMeta = payload.meta.toMutableMap()
            chunkMeta["chunk_index"] = index + 1
            chunkMeta["chunk_total"] = excerptChunks.size
            ChunkEntry(
                label = "证据块-${index + 1}",
                payload = payload.copy(request = chunkRequest, meta = chunkMeta),
            )
        }
    }

    private fun buildRelationChunkPayloads(payload: DistillPayload): List<ChunkEntry> {
        val request = payload.request.toMutableMap()
        val excerpt = request["excerpt"]?.toString()?.trim().orEmpty()
        val stages = (request["excerpt_stages"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val entries = mutableListOf<ChunkEntry>()
        for ((stageKey, stageLabel) in listOf("start" to "前段", "mid" to "中段", "end" to "后段")) {
            val stageText = stages[stageKey]?.toString()?.trim().orEmpty()
            if (stageText.isEmpty()) continue
            val chunks = splitTextIntoChunks(stageText, RELATION_CHUNK_MAX_CHARS, RELATION_CHUNK_MAX_SENTENCES)
            chunks.forEachIndexed { index, chunkText ->
                val chunkRequest = request.toMutableMap()
                chunkRequest["excerpt"] = chunkText
                chunkRequest["excerpt_stages"] = linkedMapOf("start" to "", "mid" to "", "end" to "").apply {
                    put(stageKey, chunkText)
                }
                val focus = ((request["excerpt_focus"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.toMutableMap() ?: mutableMapOf())
                focus["strategy"] = "chunked_relation_windows"
                chunkRequest["excerpt_focus"] = focus
                val chunkMeta = payload.meta.toMutableMap()
                chunkMeta["chunk_stage"] = stageKey
                chunkMeta["chunk_index"] = index + 1
                chunkMeta["chunk_total"] = chunks.size
                entries.add(
                    ChunkEntry(
                        label = if (chunks.size > 1) "$stageLabel-${index + 1}" else stageLabel,
                        payload = payload.copy(request = chunkRequest, meta = chunkMeta),
                    ),
                )
            }
        }
        if (entries.isNotEmpty()) return entries
        val excerptChunks = splitTextIntoChunks(excerpt, RELATION_CHUNK_MAX_CHARS, RELATION_CHUNK_MAX_SENTENCES)
        return excerptChunks.mapIndexed { index, chunkText ->
            val chunkRequest = request.toMutableMap()
            chunkRequest["excerpt"] = chunkText
            chunkRequest["excerpt_stages"] = linkedMapOf("start" to "", "mid" to "", "end" to "")
            val chunkMeta = payload.meta.toMutableMap()
            chunkMeta["chunk_index"] = index + 1
            chunkMeta["chunk_total"] = excerptChunks.size
            ChunkEntry(
                label = "关系块-${index + 1}",
                payload = payload.copy(request = chunkRequest, meta = chunkMeta),
            )
        }
    }

    private suspend fun runChunkDrafts(
        entries: List<ChunkEntry>,
        workers: Int,
        runOne: suspend (ChunkEntry, Int) -> String,
    ): List<Pair<String, String>> {
        if (entries.isEmpty()) return emptyList()
        if (workers <= 1) {
            val results = mutableListOf<Pair<String, String>>()
            for ((index, entry) in entries.withIndex()) {
                val content = runOne(entry, index + 1)
                if (content.isNotBlank()) results.add(entry.label to content)
            }
            return results
        }
        val semaphore = Semaphore(workers)
        return coroutineScope {
            val deferred = entries.mapIndexed { index, entry ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        runOne(entry, index + 1)
                    } finally {
                        semaphore.release()
                    }
                }
            }
            val contents = deferred.awaitAll()
            entries.mapIndexedNotNull { index, entry ->
                val content = contents.getOrNull(index).orEmpty()
                if (content.isBlank()) null else entry.label to content
            }
        }
    }

    // ------------------------------------------------------------------
    // payload 构建
    // ------------------------------------------------------------------

    private suspend fun buildDistillPayload(
        novelText: String,
        sourcePath: String,
        novelId: String,
        runDir: File,
        character: String,
        lockedCharacters: List<String>,
        maxSentences: Int,
        maxChars: Int,
    ): DistillPayload {
        val excerpt = DistillExcerptBuilder.build(novelText, listOf(character), maxSentences, maxChars)
        val existingProfiles = loadExistingProfiles(runDir, novelId, lockedCharacters)
        val updateMode = if (existingProfiles.isNotEmpty()) "incremental" else "create"
        val excerptFocus = mapOf(
            "requested_characters" to excerpt.requestedCharacters,
            "matched_characters" to excerpt.matchedCharacters,
            "missing_characters" to excerpt.missingCharacters,
            "strategy" to excerpt.strategy,
        )
        return DistillPayload(
            prompt = loadPrompt("distill_prompt.md"),
            references = loadReferences(),
            guidance = loadGuidance(),
            request = mapOf(
                "characters" to excerpt.requestedCharacters,
                "excerpt" to excerpt.excerpt,
                "excerpt_stages" to excerpt.excerptStages,
                "source_name" to File(sourcePath).name,
                "excerpt_focus" to excerptFocus,
                "update_mode" to updateMode,
                "existing_profiles" to existingProfiles,
            ),
            meta = mapOf(
                "novel_id" to novelId,
                "source_path" to sourcePath,
                "max_sentences" to maxSentences,
                "max_chars" to maxChars,
                "existing_character_count" to existingProfiles.size,
                "warnings" to emptyList<String>(),
            ),
        )
    }

    private suspend fun buildRelationPayload(
        novelText: String,
        sourcePath: String,
        novelId: String,
        characters: List<String>,
        maxSentences: Int,
        maxChars: Int,
    ): DistillPayload {
        val excerpt = DistillExcerptBuilder.build(novelText, characters, maxSentences, maxChars)
        val excerptFocus = mapOf(
            "requested_characters" to excerpt.requestedCharacters,
            "matched_characters" to excerpt.matchedCharacters,
            "missing_characters" to excerpt.missingCharacters,
            "strategy" to excerpt.strategy,
        )
        return DistillPayload(
            prompt = loadPrompt("relation_prompt.md"),
            references = loadReferences(),
            guidance = loadGuidance(),
            request = mapOf(
                "excerpt" to excerpt.excerpt,
                "excerpt_stages" to excerpt.excerptStages,
                "source_name" to File(sourcePath).name,
                "characters" to excerpt.requestedCharacters,
                "excerpt_focus" to excerptFocus,
            ),
            meta = mapOf(
                "source_path" to sourcePath,
                "max_sentences" to maxSentences,
                "max_chars" to maxChars,
                "warnings" to emptyList<String>(),
            ),
        )
    }

    private fun loadPrompt(name: String): String {
        promptLoader?.loadRawPrompt("distill/$name")?.let { return it }
        val ctx = context ?: return ""
        return runCatching {
            ctx.assets.open("distill/$name").bufferedReader().use { it.readText() }.trim()
        }.getOrElse {
            Log.w(TAG, "Failed to load distill asset $name: ${it.message}")
            ""
        }
    }

    private fun loadGuidance(): Map<String, String> =
        promptLoader?.getDistillGuidance() ?: emptyMap()

    private fun loadReferences(): Map<String, String> = mapOf(
        "output_schema" to loadPrompt("output_schema.md"),
        "style_differ" to loadPrompt("style_differ.md"),
        "logic_constraint" to loadPrompt("logic_constraint.md"),
        "validation_policy" to loadPrompt("validation_policy.md"),
    )

    /** 读取已有 PROFILE.md frontmatter（增量蒸馏上下文）。 */
    private fun loadExistingProfiles(runDir: File, novelId: String, characters: List<String>): Map<String, Any?> {
        val root = File(runDir, "artifacts/characters/${PathSafety.sanitizePathComponent(novelId, "novelId")}")
        if (!root.isDirectory) return emptyMap()
        val result = linkedMapOf<String, Any?>()
        for (character in characters) {
            val profileFile = File(root, "${PathSafety.sanitizePathComponent(character, "character")}/PROFILE.md")
            if (!profileFile.isFile) continue
            val text = runCatching { profileFile.readText() }.getOrNull() ?: continue
            val front = extractFrontMatter(text) ?: continue
            val parsed = runCatching {
                @Suppress("UNCHECKED_CAST")
                yaml.load<Any>(front) as? Map<String, Any>
            }.getOrNull() ?: continue
            result[character] = parsed.mapValues { (_, value) -> value.toString() }
        }
        return result
    }

    private fun extractFrontMatter(text: String): String? {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return null
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return null
        return lines.drop(1).take(end).joinToString("\n")
    }

    private fun parseRelationsMarkdown(markdown: String, novelId: String): Map<String, Any?> {
        val relations = linkedMapOf<String, Any?>()
        var currentKey = ""
        var currentPayload: MutableMap<String, Any?>? = null
        for (rawLine in markdown.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("## ")) {
                currentKey = line.removePrefix("## ").trim()
                currentPayload = linkedMapOf()
                relations[currentKey] = currentPayload
                continue
            }
            if (line.startsWith("- ") && line.contains(":") && currentPayload != null) {
                val body = line.removePrefix("- ")
                val (key, rawValue) = body.split(":", limit = 2)
                val value = rawValue.trim()
                currentPayload[key.trim()] = value.toIntOrNull() ?: value
            }
        }
        return linkedMapOf(
            "novel_id" to novelId,
            "relations" to relations,
        )
    }

    private fun payloadToJson(payload: DistillPayload): JsonObject = buildJsonObject {
        put("mode", "distill")
        put("prompt", payload.prompt)
        put("references", buildJsonObject { payload.references.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
        put("request", toJsonObject(payload.request))
        put("meta", toJsonObject(payload.meta))
    }

    private fun toJsonObject(map: Map<String, Any?>): JsonObject = buildJsonObject {
        map.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is Map<*, *> -> put(key, toJsonObject(value.mapKeys { it.key.toString() }))
                is List<*> -> put(key, buildJsonArray {
                    value.forEach { item ->
                        when (item) {
                            is Map<*, *> -> add(toJsonObject(item.mapKeys { it.key.toString() }))
                            is String -> add(JsonPrimitive(item))
                            is Boolean -> add(JsonPrimitive(item))
                            is Int -> add(JsonPrimitive(item))
                            is Long -> add(JsonPrimitive(item))
                            is Double -> add(JsonPrimitive(item))
                            null -> add(kotlinx.serialization.json.JsonNull)
                            else -> add(JsonPrimitive(item.toString()))
                        }
                    }
                })
                null -> put(key, kotlinx.serialization.json.JsonNull)
                else -> put(key, value.toString())
            }
        }
    }

    // ------------------------------------------------------------------
    // 落盘与解析
    // ------------------------------------------------------------------

    private fun writeProfile(runDir: File, novelId: String, character: String, generatedText: String) {
        val safeNovel = PathSafety.sanitizePathComponent(novelId, "novelId")
        val safeChar = PathSafety.sanitizePathComponent(character, "character")
        val dir = File(runDir, "artifacts/characters/$safeNovel/$safeChar")
        dir.mkdirs()
        val clean = stripFences(generatedText).trim()
        storage.writeTextAtomically(File(dir, "PROFILE.generated.md"), clean + "\n")
        val profile = parseGeneratedMarkdown(clean)
        if (profile.isEmpty()) throw IllegalArgumentException("$character 的人物档案生成为空。")
        val frontmatter = yaml.dump(profile).trimEnd()
        storage.writeTextAtomically(File(dir, "PROFILE.md"), "---\n$frontmatter\n---\n")
    }

    /** 解析 PROFILE.generated.md 的 `- key: value` 行为扁平 map（对齐 persona_bundle 的解析粒度）。 */
    private fun parseGeneratedMarkdown(text: String): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (!line.startsWith("- ") || !line.contains(":")) continue
            val body = line.removePrefix("- ")
            val (key, rawValue) = body.split(":", limit = 2)
            val keyText = key.trim()
            if (keyText.isEmpty()) continue
            val value = rawValue.trim()
            result[keyText] = value
        }
        return result
    }

    private fun stripFences(content: String): String {
        var t = content.trim()
        val fenced = Regex("```(?:markdown|md)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL).find(t)
        if (fenced != null) return fenced.groupValues[1].trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").removePrefix("markdown").removePrefix("md").trim()
            t = t.trimEnd().removeSuffix("```").trim()
        }
        return t
    }

    private suspend fun callLlm(messages: List<LlmClient.ChatMessage>, maxTokens: Int, temperature: Double): String {
        val client = llm ?: throw IllegalStateException("LLM 客户端未配置")
        val content = client.chatCompletion(
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (content.isBlank()) throw IllegalStateException("LLM 返回空内容")
        return stripFences(content)
    }

    private fun resolveSourcePath(manifest: JsonObject, runDir: File): String {
        manifest["novel_sources"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("source_path")?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)?.let { return it }
        val novelPath = manifest["novel_path"]?.jsonPrimitive?.contentOrNull
        if (!novelPath.isNullOrBlank() && File(novelPath).isFile) return novelPath
        return File(runDir, "novel.txt").takeIf { it.exists() }?.absolutePath.orEmpty()
    }

    private fun readCompletedCharacters(runId: String): Set<String> {
        val manifest = storage.readRunManifest(runId) ?: return emptySet()
        return manifest["progress"]?.jsonObject?.get("completed_characters")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().toSet()
    }

    /** 重新扫描 artifacts/characters 更新 artifact_index（保留 relation_graph）。 */
    private fun refreshArtifactIndex(runId: String, manifest: JsonObject): JsonObject {
        val runDir = storage.getRunDirectory(runId)
        val charactersRoot = File(runDir, "artifacts/characters")
        val characters = mutableListOf<JsonObject>()
        if (charactersRoot.isDirectory) {
            charactersRoot.listFiles()?.filter(File::isDirectory)?.forEach { novelDir ->
                novelDir.listFiles()?.filter(File::isDirectory)?.forEach { characterDir ->
                    val profile = characterDir.listFiles()
                        ?.firstOrNull { it.isFile && it.name.startsWith("PROFILE") && it.name.endsWith(".md") }
                    if (profile != null) {
                        characters.add(
                            buildJsonObject {
                                put("name", characterDir.name)
                                put("profile_file", profile.absolutePath)
                                put("persona_dir", characterDir.absolutePath)
                            },
                        )
                    }
                }
            }
        }
        return buildJsonObject {
            manifest.forEach { (key, value) -> if (key != "artifact_index") put(key, value) }
            put("artifact_index", buildJsonObject {
                put("characters", buildJsonArray { characters.forEach { add(it) } })
                manifest["artifact_index"]?.jsonObject?.get("relation_graph")?.let { put("relation_graph", it) }
            })
        }
    }

    // ------------------------------------------------------------------
    // 进度与收尾（progress.py）
    // ------------------------------------------------------------------

    private fun applyDistillProgress(runId: String, stage: String, payload: Map<String, Any?>) {
        patchManifest(runId) { current ->
            val now = Instant.now().toString()
            val oldProgress = current["progress"]?.jsonObject ?: JsonObject(emptyMap())
            val progress = buildJsonObject {
                oldProgress.forEach { (k, v) -> put(k, v) }
                when (stage) {
                    "text_loaded" -> {
                        put("stage", "text_loaded")
                        put("message", "已载入小说文本")
                    }
                    "characters_ready" -> {
                        put("stage", "characters_ready")
                        put("message", "已锁定 ${payload["total"]} 个待蒸馏角色")
                    }
                    "drafting_character" -> {
                        put("stage", "distilling")
                        put("current_character", payload["character"]?.toString().orEmpty())
                        put("message", "正在蒸馏 ${payload["character"]}")
                    }
                    "chunking_character" -> {
                        put("stage", "distilling")
                        put("current_character", payload["character"]?.toString().orEmpty())
                        val index = (payload["chunk_index"] as? Number)?.toInt() ?: 0
                        val total = (payload["chunk_total"] as? Number)?.toInt() ?: 0
                        val workers = (payload["parallel_workers"] as? Number)?.toInt() ?: 1
                        val suffix = if (workers > 1) "，并行 $workers 线程" else ""
                        put("message", "正在分批蒸馏 ${payload["character"]}（$index/$total）$suffix")
                    }
                    "merging_character" -> {
                        put("stage", "distilling")
                        put("current_character", payload["character"]?.toString().orEmpty())
                        put("message", "正在汇总 ${payload["character"]} 的分批草稿")
                    }
                    "materializing_character" -> {
                        put("stage", "distilling")
                        put("current_character", payload["character"]?.toString().orEmpty())
                        put("message", "正在落盘 ${payload["character"]}")
                    }
                    "character_done" -> {
                        put("stage", "distilling")
                        val character = payload["character"]?.toString()?.trim().orEmpty()
                        val completed = ((payload["completed"] as? List<*>) ?: emptyList<Any?>())
                            .mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                        put("completed_characters", buildJsonArray { completed.forEach { add(JsonPrimitive(it)) } })
                        put("completed_count", completed.size)
                        put("total_characters", (payload["total"] as? Number)?.toInt() ?: 0)
                        put("current_character", "")
                        put("message", "$character 蒸馏完成")
                    }
                }
                put("updated_at", now)
            }
            val events = (current["events"]?.jsonArray ?: JsonArray(emptyList())).toMutableList().apply {
                add(
                    buildJsonObject {
                        put("stage", stage)
                        put("status", "running")
                        put("message", progress["message"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("character", payload["character"]?.toString()?.trim().orEmpty())
                        put("capability", if (stage.contains("graph")) "export_graph" else "distill")
                        put("timestamp", now)
                    },
                )
            }
            buildJsonObject {
                current.forEach { (k, v) -> if (k !in setOf("progress", "events", "updated_at")) put(k, v) }
                put("progress", progress)
                put("events", buildJsonArray { events.takeLast(120).forEach(::add) })
                put("updated_at", now)
            }
        }
    }

    private fun applyRelationProgress(runId: String, stage: String, payload: Map<String, Any?>) {
        patchManifest(runId) { current ->
            val now = Instant.now().toString()
            val oldProgress = current["progress"]?.jsonObject ?: JsonObject(emptyMap())
            val progress = buildJsonObject {
                oldProgress.forEach { (k, v) -> put(k, v) }
                when (stage) {
                    "rendering_graph" -> {
                        put("stage", "rendering_graph")
                        put("graph_status", "running")
                        put("current_character", "")
                        put("message", "正在生成人物关系图谱")
                    }
                    "chunking_graph" -> {
                        put("stage", "rendering_graph")
                        put("graph_status", "running")
                        val index = (payload["chunk_index"] as? Number)?.toInt() ?: 0
                        val total = (payload["chunk_total"] as? Number)?.toInt() ?: 0
                        val workers = (payload["parallel_workers"] as? Number)?.toInt() ?: 1
                        val suffix = if (workers > 1) "，并行 $workers 线程" else ""
                        put("message", "正在分批抽取人物关系（$index/$total）$suffix")
                    }
                    "merging_graph" -> {
                        put("stage", "rendering_graph")
                        put("graph_status", "running")
                        put("message", "正在汇总分批关系草稿")
                    }
                    "graph_done" -> {
                        put("stage", "graph_done")
                        put("graph_status", "complete")
                        put("message", "人物关系图谱已生成")
                    }
                }
                put("updated_at", now)
            }
            val events = (current["events"]?.jsonArray ?: JsonArray(emptyList())).toMutableList().apply {
                add(
                    buildJsonObject {
                        put("stage", stage)
                        put("status", "running")
                        put("message", progress["message"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("character", "")
                        put("capability", "export_graph")
                        put("timestamp", now)
                    },
                )
            }
            buildJsonObject {
                current.forEach { (k, v) -> if (k !in setOf("progress", "events", "updated_at")) put(k, v) }
                put("progress", progress)
                put("events", buildJsonArray { events.takeLast(120).forEach(::add) })
                put("updated_at", now)
            }
        }
    }

    private fun patchManifest(runId: String, transform: (JsonObject) -> JsonObject) {
        val manifest = storage.readRunManifest(runId) ?: return
        storage.writeRunManifest(runId, transform(manifest))
    }

    private fun isStopRequested(runId: String): Boolean {
        val manifest = storage.readRunManifest(runId) ?: return false
        return manifest["control"]?.jsonObject?.get("stop_requested")?.jsonPrimitive?.booleanOrNull == true
    }

    private fun finalizeSuccess(
        runId: String,
        planned: List<String>,
        completed: List<String>,
        failed: List<String>,
        qualityMatched: Set<String>,
        qualityMissing: Set<String>,
        qualityStagePresence: Set<String>,
        qualityFocus: Map<String, Map<String, Any?>>,
        profileRepairCharacters: List<String>,
        distillChunkByCharacter: Map<String, Map<String, Any?>>,
        characterDirs: Map<String, String>,
        distillPayloadPaths: Map<String, String>,
        startedAt: String,
    ) {
        val manifest = storage.readRunManifest(runId) ?: return
        if (isStopRequested(runId)) {
            finalizeStopped(runId, completed)
            return
        }
        val refreshed = refreshArtifactIndex(runId, manifest)
        val now = Instant.now().toString()
        val elapsedSeconds = runCatching {
            maxOf(0.0, Duration.between(Instant.parse(startedAt), Instant.parse(now)).toMillis() / 1000.0)
        }.getOrDefault(0.0)
        val graphStatus = manifest["progress"]?.jsonObject?.get("graph_status")?.jsonPrimitive?.contentOrNull ?: "complete"
        val updated = buildJsonObject {
            refreshed.forEach { (k, v) -> if (k !in setOf("status", "success", "progress", "summary", "updated_at", "timing", "capabilities", "quality", "events", "artifact_index")) put(k, v) }
            put("artifact_index", refreshed["artifact_index"] ?: manifest["artifact_index"] ?: JsonObject(emptyMap()))
            put("status", "ready")
            put("success", true)
            put("updated_at", now)
            put("timing", buildJsonObject {
                put("started_at", startedAt)
                put("completed_at", now)
                put("elapsed_seconds", Math.round(elapsedSeconds * 1000.0) / 1000.0)
                put("elapsed_text", formatElapsedText(elapsedSeconds))
            })
            put("progress", buildJsonObject {
                (manifest["progress"]?.jsonObject ?: JsonObject(emptyMap())).forEach { (k, v) -> put(k, v) }
                put("stage", "completed")
                put("message", "蒸馏完成：${completed.size} 名人物${if (failed.isNotEmpty()) "，${failed.size} 名失败" else ""}。")
                put("current_character", "")
                put("completed_count", completed.size)
                put("total_characters", planned.size)
                put("completed_characters", buildJsonArray { completed.forEach { add(JsonPrimitive(it)) } })
                if (failed.isNotEmpty()) put("failed_characters", buildJsonArray { failed.forEach { add(JsonPrimitive(it)) } })
                put("graph_status", graphStatus)
                put("updated_at", now)
            })
            put("summary", buildJsonObject {
                put("characters_total", planned.size)
                put("characters_completed", completed.size)
                put("graph_status", graphStatus)
                put("status_text", if (failed.isNotEmpty()) "部分完成" else "可使用")
            })
            put("capabilities", buildJsonObject {
                put("distill", buildJsonObject {
                    put("status", "complete")
                    put("success", true)
                    put("updated_at", now)
                    put("message", "canonical profiles generated")
                })
                put("materialize", buildJsonObject {
                    put("status", "complete")
                    put("success", true)
                    put("updated_at", now)
                    put("message", "persona bundle written")
                })
                put("export_graph", buildJsonObject {
                    put("status", if (graphStatus == "complete") "complete" else "failed")
                    put("success", graphStatus == "complete")
                    put("updated_at", now)
                    put("message", if (graphStatus == "complete") "relation graph exported" else "relation graph failed")
                })
                put("verify_workflow", buildJsonObject {
                    put("status", "complete")
                    put("success", true)
                    put("updated_at", now)
                    put("message", "automatic workflow finished")
                })
            })
            put("quality", buildJsonObject {
                put("matched_characters", buildJsonArray { qualityMatched.forEach { add(JsonPrimitive(it)) } })
                put("missing_characters", buildJsonArray { qualityMissing.forEach { add(JsonPrimitive(it)) } })
                put("strategy", "character_windows")
                put("excerpt_stages", buildJsonObject {
                    put("start", if ("前段" in qualityStagePresence) "yes" else "")
                    put("mid", if ("中段" in qualityStagePresence) "yes" else "")
                    put("end", if ("后段" in qualityStagePresence) "yes" else "")
                })
                put("character_focus", buildJsonObject {
                    qualityFocus.forEach { (name, focus) -> put(name, toJsonObject(focus)) }
                })
                put("profile_repairs", buildJsonObject {
                    put("count", profileRepairCharacters.size)
                    put("characters", buildJsonArray { profileRepairCharacters.forEach { add(JsonPrimitive(it)) } })
                })
                put("distill_chunk_by_character", buildJsonObject {
                    distillChunkByCharacter.forEach { (name, meta) -> put(name, toJsonObject(meta)) }
                })
            })
            put("events", buildJsonArray {
                (manifest["events"]?.jsonArray ?: JsonArray(emptyList())).forEach(::add)
                add(buildJsonObject {
                    put("stage", "workflow_complete")
                    put("status", "complete")
                    put("message", "本次整理耗时 ${formatElapsedText(elapsedSeconds)}")
                    put("character", "")
                    put("capability", "verify_workflow")
                    put("timestamp", now)
                })
            })
        }
        storage.writeRunManifest(runId, updated)
        Log.i(TAG, "Distillation completed for run=$runId: ${completed.size}/${planned.size} characters")
    }

    private fun formatElapsedText(seconds: Double): String {
        val totalSeconds = seconds.toInt()
        return when {
            totalSeconds < 60 -> "${totalSeconds} 秒"
            totalSeconds < 3600 -> "${totalSeconds / 60} 分 ${totalSeconds % 60} 秒"
            else -> "${totalSeconds / 3600} 小时 ${(totalSeconds % 3600) / 60} 分"
        }
    }

    private fun finalizeStopped(runId: String, completed: List<String>) {
        val manifest = storage.readRunManifest(runId) ?: return
        val now = Instant.now().toString()
        val startedAt = manifest["timing"]?.jsonObject?.get("started_at")?.jsonPrimitive?.contentOrNull.orEmpty()
        val elapsedSeconds = runCatching {
            if (startedAt.isNotBlank()) maxOf(0.0, Duration.between(Instant.parse(startedAt), Instant.parse(now)).toMillis() / 1000.0) else 0.0
        }.getOrDefault(0.0)
        val updated = buildJsonObject {
            manifest.forEach { (k, v) -> if (k !in setOf("status", "progress", "summary", "updated_at", "timing")) put(k, v) }
            put("status", "stopped")
            put("success", false)
            put("updated_at", now)
            put("timing", buildJsonObject {
                if (startedAt.isNotBlank()) put("started_at", startedAt)
                put("stopped_at", now)
                put("elapsed_seconds", Math.round(elapsedSeconds * 1000.0) / 1000.0)
                put("elapsed_text", formatElapsedText(elapsedSeconds))
            })
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
        val startedAt = manifest["timing"]?.jsonObject?.get("started_at")?.jsonPrimitive?.contentOrNull.orEmpty()
        val elapsedSeconds = runCatching {
            if (startedAt.isNotBlank()) maxOf(0.0, Duration.between(Instant.parse(startedAt), Instant.parse(now)).toMillis() / 1000.0) else 0.0
        }.getOrDefault(0.0)
        val updated = buildJsonObject {
            manifest.forEach { (k, v) -> if (k !in setOf("status", "progress", "summary", "updated_at", "timing")) put(k, v) }
            put("status", "failed")
            put("success", false)
            put("updated_at", now)
            put("timing", buildJsonObject {
                if (startedAt.isNotBlank()) put("started_at", startedAt)
                put("failed_at", now)
                put("elapsed_seconds", Math.round(elapsedSeconds * 1000.0) / 1000.0)
                put("elapsed_text", formatElapsedText(elapsedSeconds))
            })
            put("progress", buildJsonObject {
                (manifest["progress"]?.jsonObject ?: JsonObject(emptyMap())).forEach { (k, v) -> put(k, v) }
                put("stage", "failed")
                put("message", message)
                put("error", message)
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
