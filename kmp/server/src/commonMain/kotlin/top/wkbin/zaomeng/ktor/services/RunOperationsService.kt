package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import okio.Path
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.platform.base64Decode
import top.wkbin.zaomeng.platform.base64Encode
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.parseYaml
import top.wkbin.zaomeng.platform.randomUuid
import top.wkbin.zaomeng.platform.writeZipEntries
import top.wkbin.zaomeng.platform.ZipEntryData
import kotlin.math.ceil
import kotlin.math.roundToInt
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest

/**
 * 运行操作服务
 *
 * 对应 Python src/web/service_facades/runs.py 与 run_ops/ 的：
 * 采样估算、共演空间、导出书卷包、重新蒸馏、恢复蒸馏、
 * 蒸馏片段推荐、刷新运行清单。
 *
 * 注意：Android 端蒸馏由 App 的 DistillationForegroundService 本地执行，
 * redistill/resume 仅负责把运行清单切换到 running 状态并返回。
 */
class RunOperationsService(
    private val storage: StorageService,
    private val runManagement: RunManagementService,
    private val packageService: RunPackageService,
    private val distillExecutor: DistillExecutor,
) {
    companion object {
        private const val DISTILL_CHUNK_MAX_CHARS = 10_000
        private const val DISTILL_CHUNK_MAX_SENTENCES = 80
        private const val RELATION_CHUNK_MAX_CHARS = 12_000
        private const val RELATION_CHUNK_MAX_SENTENCES = 80

        private val KEY_FIELDS = listOf(
            "core_identity", "story_role", "identity_anchor", "temperament_type",
            "soul_goal", "core_traits", "key_bonds", "speech_style", "worldview",
            "belief_anchor", "moral_bottom_line", "restraint_threshold", "stress_response",
        )
        private val FIELD_LABELS = mapOf(
            "core_identity" to "核心身份",
            "story_role" to "故事位置",
            "identity_anchor" to "身份锚点",
            "temperament_type" to "气质底色",
            "soul_goal" to "灵魂目标",
            "core_traits" to "核心特质",
            "key_bonds" to "重要牵系",
            "speech_style" to "说话方式",
            "worldview" to "世界观",
            "belief_anchor" to "信念支点",
            "moral_bottom_line" to "道德底线",
            "restraint_threshold" to "失控阈值",
            "stress_response" to "应激反应",
        )
        private val DIALOGUE_TOKENS = listOf("“", "”", "\"", "「", "」", "『", "』", "道", "说", "问", "答", "笑道")
        private val THOUGHT_TOKENS = listOf("心想", "想道", "想着", "觉得", "只觉", "暗想", "心里", "思忖")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    // ------------------------------------------------------------------
    // 采样估算
    // ------------------------------------------------------------------

    fun estimate(
        charCount: Int,
        sentenceCount: Int,
        characterCount: Int,
        maxSentences: Int,
        maxChars: Int,
    ): JsonObject {
        val chars = maxOf(1, charCount)
        val sentences = maxOf(1, sentenceCount)
        val characters = maxOf(1, characterCount)
        val samplingMaxChars = clamp(maxChars, 2_000, 200_000)
        val samplingMaxSentences = clamp(maxSentences, 20, 300)
        val suggestedMaxChars = suggestMaxChars(chars)
        val suggestedMaxSentences = suggestMaxSentences(sentences)
        val effectiveChars = maxOf(1, minOf(chars, samplingMaxChars))
        val effectiveSentences = maxOf(1, minOf(sentences, samplingMaxSentences))
        val distillChunks = chunkCount(effectiveChars, effectiveSentences, DISTILL_CHUNK_MAX_CHARS, DISTILL_CHUNK_MAX_SENTENCES)
        val relationChars = minOf(effectiveChars, 12_000)
        val relationSentences = minOf(effectiveSentences, 80)
        val relationChunks = chunkCount(relationChars, relationSentences, RELATION_CHUNK_MAX_CHARS, RELATION_CHUNK_MAX_SENTENCES)
        val distillCallsPerCharacter = if (distillChunks > 1) distillChunks + 1 else 1
        val relationCalls = if (relationChunks > 1) relationChunks + 1 else 1
        val totalCalls = characters * distillCallsPerCharacter + relationCalls
        val distillTokens = tokenBudget(effectiveChars, distillChunks, mode = "distill")
        val relationTokens = tokenBudget(relationChars, relationChunks, mode = "relation")
        val totalTokens = characters * distillTokens + relationTokens
        val time = estimateTime(characters, distillChunks, relationChunks)
        return buildJsonObject {
            put("char_count", chars)
            put("sentence_count", sentences)
            put("character_count", characters)
            put("suggested_max_chars", suggestedMaxChars)
            put("suggested_max_sentences", suggestedMaxSentences)
            put("effective_chars", effectiveChars)
            put("effective_sentences", effectiveSentences)
            put("distill_chunk_count", distillChunks)
            put("relation_chunk_count", relationChunks)
            put("distill_calls_per_character", distillCallsPerCharacter)
            put("relation_calls", relationCalls)
            put("total_calls", totalCalls)
            put("token_low", roundToStep(totalTokens * 0.82, 500))
            put("token_high", roundToStep(totalTokens * 1.18, 500))
            time.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun suggestMaxChars(charCount: Int): Int =
        if (charCount <= 50_000) maxOf(2_000, roundToStep(charCount, 1_000))
        else minOf(120_000, roundToStep(maxOf(50_000, (charCount * 0.38).roundToInt()), 5_000))

    private fun suggestMaxSentences(sentenceCount: Int): Int =
        if (sentenceCount <= 120) maxOf(20, sentenceCount)
        else minOf(300, roundToStep(maxOf(120, (sentenceCount * 0.32).roundToInt()), 10))

    private fun chunkCount(chars: Int, sentences: Int, chunkChars: Int, chunkSentences: Int): Int =
        maxOf(1, ceil(chars.toDouble() / chunkChars).toInt(), ceil(sentences.toDouble() / chunkSentences).toInt())

    private fun tokenBudget(chars: Int, chunkCount: Int, mode: String): Int {
        val charTokens = (chars * 1.1).roundToInt()
        val base = if (mode == "distill") 1_800 else 1_200
        if (chunkCount <= 1) return charTokens + base
        val overhead = if (mode == "distill") chunkCount * 700 + 1_100 else chunkCount * 500 + 900
        return charTokens + base + overhead
    }

    private fun estimateTime(characterCount: Int, distillChunkCount: Int, relationChunkCount: Int): Map<String, Int> {
        val workers = when {
            distillChunkCount >= 6 -> 6
            distillChunkCount >= 4 -> 4
            distillChunkCount >= 2 -> 2
            else -> 1
        }
        val distillLowPerCharacter = if (distillChunkCount > 1) ceil(distillChunkCount.toDouble() / workers).toInt() * 22 + 28 else 35
        val distillHighPerCharacter = if (distillChunkCount > 1) ceil(distillChunkCount.toDouble() / workers).toInt() * 42 + 55 else 70
        val relationLow = if (relationChunkCount > 1) ceil(relationChunkCount.toDouble() / workers).toInt() * 14 + 18 else 24
        val relationHigh = if (relationChunkCount > 1) ceil(relationChunkCount.toDouble() / workers).toInt() * 28 + 38 else 48
        val materializeLow = maxOf(4, characterCount * 3)
        val materializeHigh = maxOf(8, characterCount * 7)
        val distillLow = characterCount * distillLowPerCharacter + materializeLow
        val distillHigh = characterCount * distillHighPerCharacter + materializeHigh
        return buildMap {
            put("distill_time_low_seconds", roundToStep(distillLow, 5))
            put("distill_time_high_seconds", roundToStep(distillHigh, 10))
            put("relation_time_low_seconds", roundToStep(relationLow, 5))
            put("relation_time_high_seconds", roundToStep(relationHigh, 10))
            put("time_low_seconds", roundToStep(distillLow + relationLow, 5))
            put("time_high_seconds", roundToStep(distillHigh + relationHigh, 10))
        }
    }

    private fun roundToStep(value: Number, step: Int): Int = maxOf(step, (value.toDouble() / step + 0.5).toInt() * step)
    private fun clamp(value: Int, lower: Int, upper: Int): Int = maxOf(lower, minOf(upper, value))

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    fun exportRunPackage(runId: String, builtin: Boolean, includeDialogue: Boolean?): Pair<ByteArray, String> {
        val manifest = storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found: $runId")
        if (manifest["status"]?.jsonPrimitive?.contentOrNull == "running") {
            throw IllegalArgumentException("这本书还在整理中，等这一轮结束后再导出小说包。")
        }
        val runDir = storage.getRunDirectory(runId)
        val dialogueExists = storage.isDirectory(runDir / "dialogue")
        val includesDialogue = includeDialogue ?: (!builtin && dialogueExists)
        val novelId = manifest["novel_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: runId
        val title = (manifest["title"]?.jsonPrimitive?.contentOrNull
            ?: manifest["novel_name"]?.jsonPrimitive?.contentOrNull)
            ?.takeIf(String::isNotBlank) ?: novelId
        val slug = title.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }.take(80).ifBlank { "novel" }
        val filename = "$slug.zaomeng-run.zip"
        val bytes = buildPackageZip(runDir, manifest, novelId, builtin, includesDialogue)
        return bytes to filename
    }

    private fun buildPackageZip(runDir: Path, manifest: JsonObject, novelId: String, builtin: Boolean, includeDialogue: Boolean): ByteArray {
        val packageManifest = buildJsonObject {
            put("kind", "zaomeng_web_run_package")
            put("schema_version", 2)
            put("package_root", "run")
            put("exported_at", nowIsoString())
            put("builtin", builtin)
            put("includes_dialogue", includeDialogue)
            put("includes_chapters", storage.isDirectory(runDir / "chapters"))
            put("source_run_id", manifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("novel_id", novelId)
        }
        val entries = mutableListOf<ZipEntryData>()
        val ignored = setOf("exports", "tmp", "tmp-imports", ".git", "__pycache__", ".pyc")
        fun addDir(base: Path, relative: String) {
            storage.listFiles(base).filter { !it.name.endsWith(".tmp") }.forEach { file ->
                val childRelative = if (relative.isEmpty()) file.name else "$relative/${file.name}"
                if (storage.isDirectory(file)) {
                    if (file.name in ignored) return@forEach
                    // 不携带会话时整个 dialogue 目录都不导出（含空目录条目）
                    if (file.name == "dialogue" && !includeDialogue) return@forEach
                    entries += ZipEntryData("run/$childRelative/", ByteArray(0))
                    addDir(file, childRelative)
                } else {
                    // 不携带会话时：对话目录与对话派生的时间线/剧情事实（world_memory.json）都不导出
                    if (childRelative == "dialogue" || (childRelative.startsWith("dialogue/") && !includeDialogue)) return@forEach
                    if (childRelative == "world_memory.json" && !includeDialogue) return@forEach
                    entries += ZipEntryData("run/$childRelative", storage.readBytes(file))
                }
            }
        }
        entries += ZipEntryData(
            "package_manifest.json",
            json.encodeToString(JsonObject.serializer(), packageManifest).encodeToByteArray(),
        )
        addDir(runDir, "")
        return writeZipEntries(entries)
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

    // ------------------------------------------------------------------
    // 共演空间
    // ------------------------------------------------------------------

    fun createCrossoverSpace(title: String, worldSetting: String, participants: List<Pair<String, String>>): JsonObject {
        if (participants.size !in 2..8) throw IllegalArgumentException("共演空间需要选择 2 到 8 名人物。")
        val names = mutableSetOf<String>()
        val selected = mutableListOf<Triple<String, String, Path>>()
        for ((runId, character) in participants) {
            if (runId.isBlank() || character.isBlank() || character in names) {
                throw IllegalArgumentException("共演人物不能为空或重名。")
            }
            val sourceManifest = storage.readRunManifest(runId)
                ?: throw NoSuchElementException("Run not found: $runId")
            val entry = sourceManifest["artifact_index"]?.jsonObject?.get("characters")?.jsonArray
                ?.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == character }
            val profileFile = entry?.jsonObject?.get("profile_file")?.jsonPrimitive?.contentOrNull.orEmpty()
            val profile = profileFile.toPath()
            val sourceRunDir = storage.getRunDirectory(runId)
            if (profileFile.isEmpty() || !storage.isFile(profile) || !profile.toString().startsWith(sourceRunDir.toString())) {
                throw NoSuchElementException("$runId:$character 的人物资料不存在。")
            }
            val profileParent = profile.parent
                ?: throw NoSuchElementException("$runId:$character 的人物资料目录不可用。")
            selected.add(Triple(runId, character, profileParent))
            names.add(character)
        }
        if (selected.map { it.first }.distinct().size < 2) {
            throw IllegalArgumentException("跨书卷共演至少需要来自两个不同书卷的人物。")
        }
        val safeTitle = title.trim()
        val setting = worldSetting.trim()
        val seed = "共演空间：$safeTitle\n世界设定：${setting.ifEmpty { "由参与者共同展开。" }}"
        val created = runManagement.createRun(
            novelName = "$safeTitle.txt",
            novelContentBase64 = base64Encode(seed.encodeToByteArray()),
            characters = selected.map { it.second },
            deferRun = true,
        )
        val runId = created["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        try {
            val manifest = storage.readRunManifest(runId) ?: throw IllegalStateException("共演空间创建失败。")
            val charactersRoot = storage.getRunDirectory(runId) / "artifacts/characters"
            storage.mkdirs(charactersRoot)
            for ((_, character, sourceDir) in selected) {
                val target = charactersRoot / sourceDir.name
                if (!storage.exists(target)) copyRecursively(sourceDir, target)
            }
            val now = nowIsoString()
            val refreshed = refreshArtifactIndex(runId, manifest)
            val updated = buildJsonObject {
                refreshed.forEach { (key, value) -> put(key, value) }
                put("status", "ready")
                put("success", true)
                put("entrypoint", "crossover_beta")
                put("updated_at", now)
                put("beta_feature", buildJsonObject {
                    put("kind", "cross_book_crossover")
                    put("unstable", true)
                    put("world_setting", setting)
                    put("source_snapshots", buildJsonArray {
                        selected.forEach { (sourceRunId, character, _) ->
                            add(buildJsonObject {
                                put("run_id", sourceRunId)
                                put("character", character)
                            })
                        }
                    })
                })
                put("summary", buildJsonObject {
                    put("status_text", "crossover_beta_ready")
                })
            }
            storage.writeRunManifest(runId, updated)
            return updated
        } catch (error: Throwable) {
            storage.deleteRecursively(storage.getRunDirectory(runId))
            throw error
        }
    }

    // ------------------------------------------------------------------
    // 重新蒸馏 / 恢复蒸馏
    // ------------------------------------------------------------------

    fun redistill(
        runId: String,
        characters: List<String>,
        novelName: String,
        novelContentBase64: String,
        maxSentences: Int,
        maxChars: Int,
    ): JsonObject {
        // 对齐 Python restart_run_distill：未配置模型时直接 400
        if (!distillExecutor.isConfigured()) {
            throw IllegalArgumentException("请先在设置中完成模型配置。")
        }
        val manifest = storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found: $runId")
        val normalizedCharacters = characters.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalizedCharacters.isEmpty()) throw IllegalArgumentException("至少保留一位要蒸馏的人物。")
        val runDir = storage.getRunDirectory(runId)
        val now = nowIsoString()

        val novelSource = if (novelContentBase64.isNotBlank()) {
            val bytes = runCatching { base64Decode(novelContentBase64) }
                .getOrElse { throw IllegalArgumentException("小说内容 Base64 无效。") }
            val safeName = PathSafety.sanitizePathComponent(novelName.ifBlank { "redistill-source.txt" }, "novelName")
            val existingPaths = buildList {
                manifest["novel_sources"]?.jsonArray?.forEach { source ->
                    source.jsonObject["source_path"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank)?.let { add(it.toPath()) }
                }
                manifest["novel_path"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)?.let { add(it.toPath()) }
                add(runDir / "novel.txt")
            }.distinct()
            val sourceFile = existingPaths.firstOrNull { candidate ->
                storage.isFile(candidate) && storage.fileSize(candidate) == bytes.size.toLong() &&
                    runCatching { storage.readBytes(candidate).contentEquals(bytes) }.getOrDefault(false)
            } ?: run {
                val digest = bytes.toByteString().sha256().hex()
                val extension = safeName.substringAfterLast('.', "txt").take(12)
                val contentAddressed = runDir / "redistill-sources/$digest.$extension"
                if (!storage.isFile(contentAddressed)) storage.writeBytesAtomically(contentAddressed, bytes)
                contentAddressed
            }
            val sourceText = bytes.decodeToString()
            buildJsonObject {
                put("source_name", safeName)
                put("source_path", sourceFile.toString())
                put("kind", "redistill")
                put("timestamp", now)
                put("byte_size", bytes.size)
                put("char_count", sourceText.length)
            }
        } else {
            null
        }

        val existingSources = manifest["novel_sources"]?.jsonArray?.toMutableList() ?: mutableListOf()
        novelSource?.let { source ->
            val sourcePath = source.jsonObject["source_path"]?.jsonPrimitive?.contentOrNull
            val latestPath = existingSources.lastOrNull()?.jsonObject
                ?.get("source_path")?.jsonPrimitive?.contentOrNull
            if (sourcePath != latestPath) existingSources.add(source)
        }

        val updated = buildJsonObject {
            manifest.forEach { (key, value) -> put(key, value) }
            put("novel_sources", buildJsonArray { existingSources.forEach(::add) })
            put("locked_characters", buildJsonArray { normalizedCharacters.forEach { add(JsonPrimitive(it)) } })
            put("status", "running")
            put("updated_at", now)
            put("progress", buildJsonObject {
                put("stage", "starting")
                put("message", "已开始重新蒸馏 ${normalizedCharacters.size} 名人物。")
                put("current_character", "")
                put("completed_characters", JsonArray(emptyList()))
                put("total_characters", normalizedCharacters.size)
                put("completed_count", 0)
                put("graph_status", "pending")
            })
            put("control", buildJsonObject {
                put("stop_requested", false)
            })
            put("summary", buildJsonObject {
                put("characters_total", normalizedCharacters.size)
                put("characters_completed", 0)
                put("graph_status", "pending")
                put("status_text", "蒸馏中")
            })
        }
        storage.writeRunManifest(runId, updated)
        // 真正启动蒸馏执行（迁移自 Python：切状态后由 DistillExecutor 逐角色调 LLM 并更新进度）
        distillExecutor.start(runId, normalizedCharacters)
        return updated
    }

    fun resumeDistill(runId: String): JsonObject {
        val manifest = storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found: $runId")
        if (manifest["status"]?.jsonPrimitive?.contentOrNull == "running") {
            throw IllegalArgumentException("这本书已经在蒸馏中。")
        }
        val locked = manifest["locked_characters"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val completed = manifest["progress"]?.jsonObject?.get("completed_characters")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().toSet()
        val unfinished = locked.filterNot { it in completed }
        if (unfinished.isEmpty()) throw IllegalArgumentException("所有锁定的角色都已经蒸馏完成。")
        return redistill(
            runId,
            characters = unfinished,
            novelName = "",
            novelContentBase64 = "",
            maxSentences = 120,
            maxChars = 50_000,
        )
    }

    // ------------------------------------------------------------------
    // 蒸馏片段推荐
    // ------------------------------------------------------------------

    fun suggestRedistillSegments(runId: String, character: String, maxSegments: Int): JsonObject {
        val normalizedCharacter = character.trim()
        if (normalizedCharacter.isEmpty()) throw IllegalArgumentException("请先输入要推荐片段的角色名。")
        val manifest = storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found: $runId")
        val sourceEntry = resolveCurrentSourceEntry(manifest)
        val sourceFile = sourceEntry["source_path"]?.jsonPrimitive?.contentOrNull.orEmpty().toPath()
        if (!storage.isFile(sourceFile)) throw NoSuchElementException("找不到小说源文件，请先上传小说。")
        val text = storage.readText(sourceFile)
        val sentences = splitSentences(text)
        val currentFields = currentPersonaFields(runId, manifest, normalizedCharacter)
        val weakFields = collectWeakFields(currentFields)
        val segments = buildSegmentWindows(sentences, normalizedCharacter, weakFields, maxSegments)
        return buildJsonObject {
            put("character", normalizedCharacter)
            put("source_name", sourceEntry["source_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: sourceFile.name)
            put("weak_field_labels", buildJsonArray { weakFields.map { FIELD_LABELS[it] ?: it }.forEach { add(JsonPrimitive(it)) } })
            put("segments", segments)
        }
    }

    private fun resolveCurrentSourceEntry(manifest: JsonObject): JsonObject {
        val sources = manifest["novel_sources"]?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()
        sources.asReversed().firstOrNull { it["source_path"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }?.let { return it }
        val novelPath = manifest["novel_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (novelPath.isNotBlank() && storage.isFile(novelPath.toPath())) {
            return buildJsonObject {
                put("source_name", novelPath.toPath().name)
                put("kind", "initial")
                put("source_path", novelPath)
            }
        }
        throw NoSuchElementException("找不到小说源文件。")
    }

    private fun currentPersonaFields(runId: String, manifest: JsonObject, character: String): Map<String, String> {
        val entry = manifest["artifact_index"]?.jsonObject?.get("characters")?.jsonArray
            ?.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == character }
        val profileFile = entry?.jsonObject?.get("profile_file")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (profileFile.isBlank() || !storage.isFile(profileFile.toPath())) return emptyMap()
        return runCatching {
            val raw = storage.readText(profileFile.toPath())
            val frontMatter = extractFrontMatter(raw) ?: return@runCatching emptyMap()
            @Suppress("UNCHECKED_CAST")
            val loaded = parseYaml(frontMatter) as? Map<String, Any> ?: return@runCatching emptyMap()
            val result = HashMap<String, String>()
            loaded.forEach { (key, value) -> result[key] = value.toString() }
            result
        }.getOrDefault(emptyMap())
    }

    private fun extractFrontMatter(text: String): String? {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return null
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return null
        return lines.drop(1).take(end).joinToString("\n")
    }

    private fun collectWeakFields(fields: Map<String, String>): List<String> {
        val weak = KEY_FIELDS.filter { isFieldWeak(it, fields[it].orEmpty().trim()) }
        return weak.ifEmpty { KEY_FIELDS.take(4) }
    }

    private fun isFieldWeak(field: String, value: String): Boolean {
        if (value.isEmpty()) return true
        val lengthThreshold = when (field) {
            in setOf("worldview", "belief_anchor", "moral_bottom_line", "restraint_threshold", "stress_response", "speech_style", "identity_anchor", "soul_goal") -> 10
            in setOf("core_traits", "key_bonds") -> 6
            else -> 4
        }
        return value.length < lengthThreshold
    }

    private fun splitSentences(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\u3000', ' ')
        val sentences = mutableListOf<String>()
        val buffer = StringBuilder()
        for (char in normalized) {
            buffer.append(char)
            if (char in charArrayOf('。', '！', '？', '…', '\n')) {
                val sentence = buffer.toString().trim()
                if (sentence.isNotEmpty()) sentences.add(sentence)
                buffer.setLength(0)
            }
        }
        if (buffer.isNotBlank()) sentences.add(buffer.toString().trim())
        return sentences
    }

    private fun buildSegmentWindows(
        sentences: List<String>,
        character: String,
        weakFields: List<String>,
        maxSegments: Int,
    ): JsonArray {
        if (sentences.isEmpty()) return JsonArray(emptyList())
        val candidates = mutableListOf<JsonObject>()
        val windowSize = 8
        val stride = 4
        var start = 0
        while (start < sentences.size) {
            val chunk = sentences.subList(start, minOf(start + windowSize, sentences.size))
            val excerpt = chunk.joinToString("").trim()
            if (excerpt.isNotEmpty()) {
                val castHits = excerpt.countMentions(character)
                if (castHits > 0) {
                    val dialogueHits = chunk.count { looksLikeDialogue(it) }
                    val thoughtHits = chunk.count { looksLikeThought(it) }
                    val score = castHits * 30 + dialogueHits * 12 + thoughtHits * 10 - maxOf(0, excerpt.length - 420) / 30
                    val estimated = estimateFields(weakFields, dialogueHits, thoughtHits, castHits)
                    candidates.add(
                        buildJsonObject {
                            put("segment_id", "seg-${start + 1}")
                            put("preview", previewText(excerpt))
                            put("full_text", excerpt)
                            put("start_sentence", start + 1)
                            put("end_sentence", start + chunk.size)
                            put("score", maxOf(score, 1))
                            put("estimated_field_labels", buildJsonArray {
                                estimated.map { FIELD_LABELS[it] ?: it }.forEach { add(JsonPrimitive(it)) }
                            })
                            put("reason", estimateReason(dialogueHits, thoughtHits, castHits))
                        },
                    )
                }
            }
            start += stride
        }
        candidates.sortWith(
            compareByDescending<JsonObject> { it["score"]?.jsonPrimitive?.intOrNull ?: 0 }
                .thenBy { it["start_sentence"]?.jsonPrimitive?.intOrNull ?: 0 },
        )
        val unique = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        for (candidate in candidates) {
            val key = "${candidate["start_sentence"]?.jsonPrimitive?.contentOrNull}-${candidate["end_sentence"]?.jsonPrimitive?.contentOrNull}"
            if (key in seen) continue
            seen.add(key)
            unique.add(candidate)
            if (unique.size >= maxSegments) break
        }
        return buildJsonArray { unique.forEach(::add) }
    }

    private fun estimateFields(weakFields: List<String>, dialogueHits: Int, thoughtHits: Int, castHits: Int): List<String> {
        val suggestions = mutableListOf<String>()
        fun add(field: String) {
            if (field in weakFields && field !in suggestions) suggestions.add(field)
        }
        if (dialogueHits > 0) add("speech_style")
        if (thoughtHits > 0) {
            add("soul_goal"); add("worldview"); add("identity_anchor")
        }
        if (castHits >= 2) {
            add("key_bonds"); add("core_traits")
        }
        if (dialogueHits + thoughtHits >= 2) {
            add("temperament_type"); add("stress_response")
        }
        if (castHits > 0) {
            add("core_identity"); add("story_role")
        }
        for (field in weakFields) {
            if (suggestions.size >= 4) break
            if (field !in suggestions) suggestions.add(field)
        }
        return suggestions.take(4)
    }

    private fun estimateReason(dialogueHits: Int, thoughtHits: Int, castHits: Int): String {
        val reasons = mutableListOf<String>()
        if (dialogueHits > 0) reasons.add("对白密度较高")
        if (thoughtHits > 0) reasons.add("含人物内心或判断")
        if (castHits >= 2) reasons.add("角色命中集中")
        if (reasons.isEmpty()) reasons.add("这一段和目标角色有直接命中")
        return reasons.joinToString("，")
    }

    private fun previewText(text: String, limit: Int = 120): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= limit) clean else clean.take(limit - 1).trimEnd() + "…"
    }

    private fun looksLikeDialogue(sentence: String): Boolean = DIALOGUE_TOKENS.any { it in sentence }
    private fun looksLikeThought(sentence: String): Boolean = THOUGHT_TOKENS.any { it in sentence }
    private fun String.countMentions(character: String): Int {
        if (isEmpty() || character.isEmpty()) return 0
        var count = 0
        var index = indexOf(character)
        while (index >= 0) {
            count++
            index = indexOf(character, index + character.length)
        }
        return count
    }

    // ------------------------------------------------------------------
    // 刷新运行清单
    // ------------------------------------------------------------------

    fun refresh(runId: String): JsonObject {
        val manifest = storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found: $runId")
        val refreshed = refreshArtifactIndex(runId, manifest)
        val updated = buildJsonObject {
            refreshed.forEach { (key, value) -> put(key, value) }
            put("updated_at", nowIsoString())
        }
        storage.writeRunManifest(runId, updated)
        return updated
    }

    /** 重新扫描 artifacts/characters 更新 artifact_index，avatar_version 实时计算。 */
    private fun refreshArtifactIndex(runId: String, manifest: JsonObject): JsonObject {
        val runDir = storage.getRunDirectory(runId)
        val charactersRoot = runDir / "artifacts/characters"
        val characters = mutableListOf<JsonObject>()
        if (storage.isDirectory(charactersRoot)) {
            storage.listFiles(charactersRoot).filter { storage.isDirectory(it) }.forEach { novelDir ->
                storage.listFiles(novelDir).filter { storage.isDirectory(it) }.forEach { characterDir ->
                    val profile = storage.listFiles(characterDir)
                        .firstOrNull { storage.isFile(it) && it.name.startsWith("PROFILE") && it.name.endsWith(".md") }
                    if (profile != null) {
                        characters.add(
                            buildJsonObject {
                                put("name", characterDir.name)
                                put("profile_file", profile.toString())
                                put("persona_dir", characterDir.toString())
                                put("avatar_version", storage.avatarVersion(runId, characterDir.name))
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
                // 保留既有 relation_graph（蒸馏落盘后 refresh 不应抹掉）
                manifest["artifact_index"]?.jsonObject?.get("relation_graph")?.let { put("relation_graph", it) }
            })
        }
    }
}
