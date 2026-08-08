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
import android.util.Log
import org.yaml.snakeyaml.Yaml
import top.wkbin.zaomeng.data.api.PersonaAvatarDto
import top.wkbin.zaomeng.data.api.PersonaIssueDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaReviewDto
import java.io.File

class PersonaService(
    private val storage: StorageService,
    private val llm: LlmClient? = null,
    private val prompts: PromptLoader? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private val yaml = Yaml(org.yaml.snakeyaml.constructor.SafeConstructor(org.yaml.snakeyaml.LoaderOptions()))

    fun getReview(runId: String, character: String): PersonaReviewDto {
        val source = resolveProfile(runId, character)
        val profile = loadProfile(source)
        val directory = requireNotNull(source.parentFile)
        return PersonaReviewDto(
            runId = runId,
            character = profile["name"]?.toString()?.trim().orEmpty().ifBlank { character },
            editableProfilePath = File(directory, "PROFILE.md").takeIf(File::exists)?.absolutePath.orEmpty(),
            generatedProfilePath = File(directory, "PROFILE.generated.md").takeIf(File::exists)?.absolutePath.orEmpty(),
            fields = REVIEW_FIELDS.associateWith { readField(profile, it) },
        )
    }

    fun saveReview(runId: String, character: String, fields: Map<String, String>): PersonaReviewDto {
        val source = resolveProfile(runId, character)
        val profile = loadProfile(source).toMutableMap()
        fields.forEach { (field, value) ->
            if (field in REVIEW_FIELDS) applyField(profile, field, value)
        }
        val editable = File(requireNotNull(source.parentFile), "PROFILE.md")
        val existingBody = source.readText().substringAfterFrontmatter()
        val frontmatter = yaml.dump(profile).trimEnd()
        storage.writeTextAtomically(editable, "---\n$frontmatter\n---\n\n$existingBody")
        return getReview(runId, character)
    }

    fun getQualityReport(runId: String, character: String): PersonaQualityReportDto {
        val source = resolveProfile(runId, character)
        val reportFile = File(requireNotNull(source.parentFile), "QUALITY_REPORT.json")
        if (reportFile.exists()) {
            runCatching { return json.decodeFromString<PersonaQualityReportDto>(reportFile.readText()) }
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
        requireNotNull(avatar.parentFile).mkdirs()
        avatar.writeBytes(bytes)
        val version = "${avatar.lastModified()}-${avatar.length()}"
        updateAvatarVersion(runId, character, version)
        return PersonaAvatarDto(character = character, avatarVersion = version)
    }

    fun getAvatar(runId: String, character: String): File {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val avatar = storage.avatarFile(runId, character)
        if (!avatar.isFile) throw NoSuchElementException("Avatar not found: $character")
        return avatar
    }

    suspend fun suggestField(runId: String, character: String, field: String, currentFields: Map<String, String>): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val normalizedCharacter = character.trim()
        val normalizedField = field.trim()
        if (normalizedCharacter.isEmpty() || normalizedField.isEmpty()) throw IllegalArgumentException("character and field are required")
        val activeLlm = checkNotNull(llm) { "LLM service is unavailable" }
        val activePrompts = checkNotNull(prompts) { "Prompt loader is unavailable" }
        val profile = currentFields.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        val prompt = "人物：$normalizedCharacter\n目标字段：$normalizedField\n当前档案：\n${profile.ifBlank { "（暂无）" }}\n严格返回 JSON：{\"status\":\"filled\"|\"insufficient\",\"value\":\"...\",\"reason\":\"...\"}"
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

    private fun resolveProfile(runId: String, character: String): File {
        // 轻量路径安全校验（角色名可为中文，不能含路径分隔符/穿越序列）
        if (character.isBlank() || character.contains('/') || character.contains('\\') || character.contains("..")) {
            throw IllegalArgumentException("Invalid character name")
        }
        val manifest = storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found: $runId")
        val runDir = storage.getRunDirectory(runId).canonicalFile
        val item = manifest["artifact_index"]?.jsonObject?.get("characters")?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == character }
        val candidates = buildList {
            item?.get("profile_file")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                val indexed = File(it)
                indexed.parentFile?.let { directory -> add(File(directory, "PROFILE.md")) }
                add(indexed)
            }
            item?.get("persona_dir")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { directory ->
                add(File(directory, "PROFILE.md")); add(File(directory, "PROFILE.generated.md"))
            }
            add(File(runDir, "artifacts/characters/$character/PROFILE.md"))
            add(File(runDir, "artifacts/characters/$character/PROFILE.generated.md"))
        }
        // 优先选择可解析的档案文件（有 YAML 内容），其次任意存在的文件
        candidates.firstOrNull { it.isFile && hasParsableYaml(it) }?.let { return it }
        candidates.firstOrNull { it.isFile }?.let { return it }
        runDir.walkTopDown().maxDepth(6).firstOrNull {
            it.isFile && it.parentFile?.name == character && it.name in PROFILE_FILENAMES
        }?.let { return it }
        throw NoSuchElementException("Character not found: $character")
    }

    private fun hasParsableYaml(file: File): Boolean = runCatching {
        val text = file.readText()
        val yamlText = text.frontmatter() ?: text
        yaml.load<Any?>(yamlText) is Map<*, *>
    }.getOrDefault(false)

    private fun loadProfile(file: File): Map<String, Any?> {
        Log.d(TAG, "Loading persona profile from: ${file.absolutePath}")
        val text = file.readText()
        val yamlText = text.frontmatter()
        val loaded: Map<*, *>? = if (yamlText != null) {
            runCatching { yaml.load<Any?>(yamlText) as? Map<*, *> }.getOrNull()
        } else {
            // 无 frontmatter 标记：尝试把整个文件解析为 YAML map（兼容纯 YAML 档案）
            runCatching { yaml.load<Any?>(text) as? Map<*, *> }.getOrNull()
        }
        if (loaded != null) {
            return loaded.entries.associate { it.key.toString() to normalizeYamlValue(it.value) }
        }
        // Markdown 档案回退（对齐 Python parse_persona_markdown）：
        // PROFILE.md 常见格式为 `- key: value` 列表，YAML 解析会把列表当 sequence 而非 map。
        val markdownParsed = parsePersonaMarkdown(text)
        if (markdownParsed.isNotEmpty()) {
            Log.d(TAG, "Persona profile 以 Markdown 列表解析成功: ${file.absolutePath}（${markdownParsed.size} 个字段）")
            return markdownParsed
        }
        Log.w(TAG, "Persona profile 无有效 YAML/Markdown 内容，按空档案处理: ${file.absolutePath}")
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
                    add(buildJsonObject { item.forEach(::put); put("avatar_version", version) })
                } else add(item)
            }
        }
        if (!found) throw NoSuchElementException("Character not found: $character")
        val updatedIndex = buildJsonObject { artifactIndex.forEach(::put); put("characters", updatedCharacters) }
        storage.writeRunManifest(runId, buildJsonObject { manifest.forEach(::put); put("artifact_index", updatedIndex) })
    }

    private fun isSupportedImage(bytes: ByteArray): Boolean =
        bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) ||
            bytes.startsWith(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) ||
            (bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()))

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
