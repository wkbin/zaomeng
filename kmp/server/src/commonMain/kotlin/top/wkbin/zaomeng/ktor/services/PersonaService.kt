package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
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
import top.wkbin.zaomeng.data.api.PersonaAvatarDto
import top.wkbin.zaomeng.data.api.PersonaIssueDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaReviewDto
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.dumpYaml
import top.wkbin.zaomeng.platform.parseYaml

class PersonaService(
    private val storage: StorageService,
    private val llm: LlmClient? = null,
    private val prompts: PromptLoader? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    fun getReview(runId: String, character: String): PersonaReviewDto {
        val source = resolveProfile(runId, character)
        val profile = loadProfile(source)
        val directory = requireNotNull(source.parent)
        return PersonaReviewDto(
            runId = runId,
            character = profile["name"]?.toString()?.trim().orEmpty().ifBlank { character },
            editableProfilePath = (directory / "PROFILE.md").takeIf { storage.exists(it) }?.toString().orEmpty(),
            generatedProfilePath = (directory / "PROFILE.generated.md").takeIf { storage.exists(it) }?.toString().orEmpty(),
            fields = REVIEW_FIELDS.associateWith { readField(profile, it) },
        )
    }

    fun saveReview(runId: String, character: String, fields: Map<String, String>): PersonaReviewDto {
        val source = resolveProfile(runId, character)
        val profile = loadProfile(source).toMutableMap()
        fields.forEach { (field, value) ->
            if (field in REVIEW_FIELDS) applyField(profile, field, value)
        }
        val editable = requireNotNull(source.parent) / "PROFILE.md"
        val existingBody = storage.readText(source).substringAfterFrontmatter()
        val frontmatter = dumpYaml(profile).trimEnd()
        storage.writeTextAtomically(editable, "---\n$frontmatter\n---\n\n$existingBody")
        return getReview(runId, character)
    }

    fun getQualityReport(runId: String, character: String): PersonaQualityReportDto {
        val source = resolveProfile(runId, character)
        val reportFile = requireNotNull(source.parent) / "QUALITY_REPORT.json"
        if (storage.exists(reportFile)) {
            runCatching { return json.decodeFromString<PersonaQualityReportDto>(storage.readText(reportFile)) }
        }
        val review = getReview(runId, character)
        val completed = review.fields.count { it.value.isNotBlank() && it.value !in INSUFFICIENT_VALUES }
        val score = (completed * 100 / REVIEW_FIELDS.size).coerceIn(0, 100)
        val missing = review.fields.filterValues { it.isBlank() || it in INSUFFICIENT_VALUES }.keys
        val report = PersonaQualityReportDto(
            character = review.character,
            score = score,
            maxScore = 100,
            grade = when { score >= 85 -> "A"; score >= 70 -> "B"; score >= 55 -> "C"; else -> "D" },
            verdict = if (missing.isEmpty()) "人物档案完整。" else "仍有 ${missing.size} 项人物信息需要补充。",
            issues = missing.map { PersonaIssueDto(severity = "warning", fields = listOf(it), message = "$it 尚未补充", suggestion = "结合原文证据补充该字段。") },
        )
        storage.writeTextAtomically(reportFile, json.encodeToString(PersonaQualityReportDto.serializer(), report))
        return report
    }

    fun saveAvatar(runId: String, character: String, bytes: ByteArray): PersonaAvatarDto {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        resolveProfile(runId, character)
        require(bytes.isNotEmpty() && bytes.size <= MAX_AVATAR_BYTES) { "头像不能为空且不能超过 5 MB。" }
        require(isSupportedImage(bytes)) { "仅支持 JPEG、PNG 或 WebP 图片。" }
        val avatar = storage.avatarFile(runId, character)
        storage.mkdirs(requireNotNull(avatar.parent))
        storage.writeBytes(avatar, bytes)
        val version = "${storage.lastModifiedMillis(avatar)}-${storage.fileSize(avatar)}"
        updateAvatarVersion(runId, character, version)
        return PersonaAvatarDto(character = character, avatarVersion = version)
    }

    fun getAvatar(runId: String, character: String): Path {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val avatar = storage.avatarFile(runId, character)
        if (!storage.isFile(avatar)) throw NoSuchElementException("Avatar not found: $character")
        return avatar
    }

    /** 读取头像字节（供路由直接响应；头像文件统一为 PNG）。 */
    fun getAvatarBytes(runId: String, character: String): ByteArray =
        storage.readBytes(getAvatar(runId, character))

    suspend fun suggestField(runId: String, character: String, field: String, currentFields: Map<String, String>): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val normalizedCharacter = character.trim()
        val normalizedField = field.trim()
        if (normalizedCharacter.isEmpty() || normalizedField.isEmpty()) throw IllegalArgumentException("character and field are required")
        val activeLlm = checkNotNull(llm) { "LLM service is unavailable" }
        val activePrompts = checkNotNull(prompts) { "Prompt loader is unavailable" }
        val profile = currentFields.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        val prompt = activePrompts.getPersonaSuggestFieldTemplate()
            .replace("{character}", normalizedCharacter)
            .replace("{field}", normalizedField)
            .replace("{profile}", profile.ifBlank { "（暂无）" })
        val response = activeLlm.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", activePrompts.getPersonaCompletionPrompt("knowledge_based")),
                LlmClient.ChatMessage("user", prompt),
            ),
            temperature = 0.2,
            maxTokens = 700,
        )
        val text = response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (text.isBlank()) throw IllegalStateException("LLM returned empty persona field")
        return try {
            val parsed = json.decodeFromString<JsonObject>(text.removePrefix("```").removeSuffix("```").trim().removePrefix("json").trim())
            buildJsonObject {
                put("status", parsed["status"] ?: JsonPrimitive("insufficient"))
                put("value", parsed["value"] ?: JsonPrimitive(""))
                put("reason", parsed["reason"] ?: JsonPrimitive("证据不足"))
                put("field", normalizedField)
                put("character", normalizedCharacter)
            }
        } catch (error: Exception) {
            throw IllegalStateException("LLM returned invalid persona JSON", error)
        }
    }

    private fun resolveProfile(runId: String, character: String): Path {
        // 轻量路径安全校验（角色名可为中文，不能含路径分隔符/穿越序列）
        if (character.isBlank() || character.contains('/') || character.contains('\\') || character.contains("..")) {
            throw IllegalArgumentException("Invalid character name")
        }
        val manifest = storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found: $runId")
        val runDir = storage.getRunDirectory(runId)
        val item = manifest["artifact_index"]?.jsonObject?.get("characters")?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == character }
        val candidates = buildList {
            item?.get("profile_file")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                val indexed = it.toPath()
                indexed.parent?.let { directory -> add(directory / "PROFILE.md") }
                add(indexed)
            }
            item?.get("persona_dir")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { directory ->
                add(directory.toPath() / "PROFILE.md"); add(directory.toPath() / "PROFILE.generated.md")
            }
            add(runDir / "artifacts/characters/$character/PROFILE.md")
            add(runDir / "artifacts/characters/$character/PROFILE.generated.md")
        }
        // 优先选择可解析的档案文件（有 YAML 内容），其次任意存在的文件
        candidates.firstOrNull { storage.isFile(it) && hasParsableYaml(it) }?.let { return it }
        candidates.firstOrNull { storage.isFile(it) }?.let { return it }
        findProfileRecursive(runDir, character, depth = 6)?.let { return it }
        throw NoSuchElementException("Character not found: $character")
    }

    private fun findProfileRecursive(root: Path, character: String, depth: Int): Path? {
        if (depth <= 0) return null
        for (child in storage.listFiles(root)) {
            if (storage.isDirectory(child)) {
                findProfileRecursive(child, character, depth - 1)?.let { return it }
            } else if (storage.isFile(child) && child.parent?.name == character && child.name in PROFILE_FILENAMES) {
                return child
            }
        }
        return null
    }

    private fun hasParsableYaml(file: Path): Boolean = runCatching {
        val text = storage.readText(file)
        val yamlText = text.frontmatter() ?: text
        parseYaml(yamlText) is Map<*, *>
    }.getOrDefault(false)

    private fun loadProfile(file: Path): Map<String, Any?> {
        PlatformLog.d(TAG, "Loading persona profile from: $file")
        val text = storage.readText(file)
        val yamlText = text.frontmatter()
        // 新格式：人物档案统一使用 YAML frontmatter（---\n...\n---）
        val loaded: Map<*, *>? = yamlText?.let {
            runCatching { parseYaml(it) as? Map<*, *> }.getOrNull()
        }
        if (loaded != null) {
            return loaded.entries.associate { it.key.toString() to normalizeYamlValue(it.value) }
        }
        // Markdown 档案回退（对齐 Python parse_persona_markdown）：
        // PROFILE.md 常见格式为 `- key: value` 列表，YAML 解析会把列表当 sequence 而非 map。
        val markdownParsed = parsePersonaMarkdown(text)
        if (markdownParsed.isNotEmpty()) {
            PlatformLog.d(TAG, "Persona profile 以 Markdown 列表解析成功: $file（${markdownParsed.size} 个字段）")
            return markdownParsed
        }
        PlatformLog.w(TAG, "Persona profile 无有效 YAML/Markdown 内容，按空档案处理: $file")
        return emptyMap()
    }

    /**
     * 解析 Markdown 档案（对齐 Python src/modules/persona_profile_io.py parse_persona_markdown）：
     * 提取所有 `- key: value` 行，重复 key 以「；」合并。
     */
    private fun parsePersonaMarkdown(text: String): Map<String, Any?> {
        val parsed = linkedMapOf<String, String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("- ") || ":" !in line) return@forEach
            val (key, value) = line.drop(2).split(":", limit = 2)
            val trimmedKey = key.trim()
            val trimmedValue = value.trim()
            if (trimmedKey.isBlank() || trimmedValue.isBlank()) return@forEach
            parsed[trimmedKey] = if (parsed.containsKey(trimmedKey)) {
                "${parsed[trimmedKey]}；$trimmedValue"
            } else {
                trimmedValue
            }
        }
        return parsed
    }

    private fun normalizeYamlValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to normalizeYamlValue(it.value) }
        is List<*> -> value.map(::normalizeYamlValue)
        else -> value
    }

    private fun readField(profile: Map<String, Any?>, field: String): String {
        val nested = when {
            field == "cadence" || field in SPEECH_LIST_FIELDS -> (profile["speech_habits"] as? Map<*, *>)?.get(field)
            field in EMOTION_FIELDS -> (profile["emotion_profile"] as? Map<*, *>)?.get(field)
            else -> null
        }
        return formatField(nested ?: profile[field])
    }

    private fun formatField(value: Any?): String = when (value) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.joinToString("；")
        is Map<*, *> -> value.entries.joinToString("；") { "${it.key}=${it.value}" }
        else -> value?.toString()?.trim().orEmpty()
    }

    private fun applyField(profile: MutableMap<String, Any?>, field: String, value: String) {
        val normalized: Any = if (field in LIST_FIELDS) value.split(Regex("\\s*[；;]\\s*")).filter(String::isNotBlank) else value.trim()
        profile[field] = normalized
        if (field == "cadence" || field in SPEECH_LIST_FIELDS) {
            val nested = profile["speech_habits"].asNestedMap()
            nested[field] = normalized
            profile["speech_habits"] = nested
        }
        if (field in EMOTION_FIELDS) {
            val nested = profile["emotion_profile"].asNestedMap()
            nested[field] = normalized
            profile["emotion_profile"] = nested
        }
    }

    /** 将 Any? 安全转换为可变字符串映射（star-projection，无 unchecked cast）。 */
    private fun Any?.asNestedMap(): MutableMap<String, Any?> = when (this) {
        is Map<*, *> -> this.entries.associate { it.key.toString() to it.value }.toMutableMap()
        else -> mutableMapOf()
    }

    private fun updateAvatarVersion(runId: String, character: String, version: String) {
        val manifest = storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found: $runId")
        val artifactIndex = manifest["artifact_index"]?.jsonObject ?: JsonObject(emptyMap())
        val characters = artifactIndex["characters"]?.jsonArray ?: JsonArray(emptyList())
        var found = false
        val updatedCharacters = buildJsonArray {
            characters.forEach { element ->
                val item = element.jsonObject
                if (item["name"]?.jsonPrimitive?.contentOrNull == character) {
                    found = true
                    add(buildJsonObject { item.forEach { (k, v) -> put(k, v) }; put("avatar_version", JsonPrimitive(version)) })
                } else add(item)
            }
        }
        if (!found) throw NoSuchElementException("Character not found: $character")
        val updatedIndex = buildJsonObject { artifactIndex.forEach { (k, v) -> put(k, v) }; put("characters", updatedCharacters) }
        storage.writeRunManifest(runId, buildJsonObject { manifest.forEach { (k, v) -> put(k, v) }; put("artifact_index", updatedIndex) })
    }

    private fun isSupportedImage(bytes: ByteArray): Boolean =
        bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) ||
            bytes.startsWith(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) ||
            (bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray()))

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)
    private fun String.frontmatter(): String? = if (startsWith("---")) split("---", limit = 3).getOrNull(1) else null
    private fun String.substringAfterFrontmatter(): String = if (startsWith("---")) split("---", limit = 3).getOrNull(2)?.trimStart().orEmpty() else this

    companion object {
        private const val TAG = "PersonaService"
        private const val MAX_AVATAR_BYTES = 5 * 1024 * 1024
        private val PROFILE_FILENAMES = setOf("PROFILE.md", "PROFILE.generated.md")
        private val INSUFFICIENT_VALUES = setOf("不详", "信息不足", "暂无", "未知", "资料不足", "证据不足", "待补充")
        private val REVIEW_FIELDS = listOf("core_identity", "story_role", "identity_anchor", "temperament_type", "gender", "age_stage", "appearance_feature", "habit_action", "soul_goal", "hidden_desire", "inner_conflict", "self_cognition", "private_self", "speech_style", "cadence", "typical_lines", "signature_phrases", "sentence_openers", "sentence_endings", "social_mode", "thinking_style", "decision_rules", "reward_logic", "worldview", "belief_anchor", "moral_bottom_line", "restraint_threshold", "core_traits", "key_bonds", "preference_like", "dislike_hate", "forbidden_behaviors", "stress_response", "emotion_model", "anger_style", "joy_style", "grievance_style", "others_impression")
        private val LIST_FIELDS = setOf("typical_lines", "signature_phrases", "sentence_openers", "sentence_endings", "decision_rules", "core_traits", "key_bonds", "preference_like", "dislike_hate", "forbidden_behaviors")
        private val SPEECH_LIST_FIELDS = setOf("signature_phrases", "sentence_openers", "sentence_endings")
        private val EMOTION_FIELDS = setOf("anger_style", "joy_style", "grievance_style")
    }
}
