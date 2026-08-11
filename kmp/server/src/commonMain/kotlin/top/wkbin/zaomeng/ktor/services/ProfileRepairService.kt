package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path
import top.wkbin.zaomeng.data.api.PersonaEvidenceDto
import top.wkbin.zaomeng.data.api.PersonaIssueDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaRepairChangeDto
import top.wkbin.zaomeng.data.api.PersonaRepairProposalDto
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.nowIsoString

/**
 * 蒸馏后的字段级质量检查与修复建议生成。
 *
 * 这里不写回 PROFILE：模型只返回问题字段的候选补丁，客户端把差异应用到草稿后，
 * 仍需用户通过现有“保存人物资料”流程确认。
 */
class ProfileRepairService(
    private val storage: StorageService,
    private val llm: LlmClient?,
    private val promptLoader: PromptLoader?,
    private val originalKnowledge: OriginalKnowledgeService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    suspend fun analyzeAndPropose(
        runManifest: JsonObject,
        runDir: Path,
        novelId: String,
        character: String,
        generatedMarkdown: String,
        peerCharacters: List<String>,
    ): ProfileRepairResult {
        val fields = ProfileQualityAnalyzer.parseMarkdown(generatedMarkdown)
        val peerProfiles = loadPeerProfiles(runDir, novelId, character, peerCharacters)
        val issues = ProfileQualityAnalyzer.analyze(fields, peerProfiles)
        val repairFields = issues.sortedBy(::repairPriority).asSequence()
            .flatMap { it.fields.asSequence() }
            .filter { it in ProfileQualityAnalyzer.REPAIRABLE_FIELDS }
            .distinct()
            .take(MAX_REPAIR_FIELDS)
            .toList()
        val evidenceByField = repairFields.associateWith { field ->
            searchEvidence(runManifest, character, field, fields[field].orEmpty())
        }
        val fieldsWithEvidence = evidenceByField.count { it.value.isNotEmpty() }
        val evidenceCoverage = if (repairFields.isEmpty()) {
            if (issues.isEmpty()) 100 else 0
        } else {
            (fieldsWithEvidence * 100 / repairFields.size).coerceIn(0, 100)
        }
        val changes = if (repairFields.isEmpty() || llm == null || promptLoader == null) {
            emptyList()
        } else {
            runCatching {
                generateChanges(character, fields, issues, repairFields, evidenceByField)
            }.onFailure { error ->
                PlatformLog.w(TAG, "Profile repair proposal failed for $character: ${error.message}")
            }.getOrDefault(emptyList())
        }
        val confidence = changes.map(PersonaRepairChangeDto::confidence)
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()
            ?: 0
        val status = when {
            issues.isEmpty() -> "not_needed"
            llm == null || promptLoader == null -> "unavailable"
            changes.isEmpty() -> "insufficient_evidence"
            else -> "pending"
        }
        val proposal = PersonaRepairProposalDto(
            character = character,
            status = status,
            createdAt = nowIsoString(),
            evidenceCoverage = evidenceCoverage,
            confidence = confidence,
            issues = issues,
            changes = changes,
        )
        val report = buildQualityReport(character, fields, issues, evidenceCoverage, confidence, changes.size)
        writeArtifacts(runDir, novelId, character, proposal, report)
        return ProfileRepairResult(proposal, report)
    }

    private fun repairPriority(issue: PersonaIssueDto): Int = when {
        "同时出现" in issue.message -> 0
        "模板化" in issue.message || "完全重复" in issue.message -> 1
        issue.severity == "high" -> 2
        else -> 3
    }

    private fun loadPeerProfiles(
        runDir: Path,
        novelId: String,
        character: String,
        peers: List<String>,
    ): Map<String, Map<String, String>> {
        val root = runDir / "artifacts/characters/${PathSafety.sanitizePathComponent(novelId, "novelId")}"
        return peers.asSequence()
            .filter { it != character }
            .mapNotNull { peer ->
                val file = root / PathSafety.sanitizePathComponent(peer, "character") / "PROFILE.generated.md"
                if (!storage.isFile(file)) return@mapNotNull null
                val parsed = runCatching { ProfileQualityAnalyzer.parseMarkdown(storage.readText(file)) }.getOrNull()
                    ?: return@mapNotNull null
                peer to parsed
            }
            .toMap()
    }

    private fun searchEvidence(
        runManifest: JsonObject,
        character: String,
        field: String,
        currentValue: String,
    ): List<PersonaEvidenceDto> {
        val hints = ProfileQualityAnalyzer.FIELD_HINTS[field].orEmpty()
        val query = buildList {
            add(character)
            addAll(hints)
            currentValue.takeIf { it.length in 2..80 }?.let(::add)
        }.joinToString(" ")
        return originalKnowledge.search(
            runManifest = runManifest,
            query = query,
            participants = listOf(character),
            activeParticipants = listOf(character),
            sceneTerms = hints,
            limit = EVIDENCE_PER_FIELD,
        ).mapNotNull(::evidenceDto)
    }

    private suspend fun generateChanges(
        character: String,
        fields: Map<String, String>,
        issues: List<PersonaIssueDto>,
        repairFields: List<String>,
        evidenceByField: Map<String, List<PersonaEvidenceDto>>,
    ): List<PersonaRepairChangeDto> {
        val activeLlm = requireNotNull(llm)
        val activePrompts = requireNotNull(promptLoader)
        val repairPrompt = requireNotNull(activePrompts.loadRawPrompt("distill/profile_repair.md")) {
            "Missing required prompt: distill/profile_repair.md"
        }
        val payload = buildJsonObject {
            put("character", character)
            put("allowed_fields", buildJsonArray { repairFields.forEach { add(JsonPrimitive(it)) } })
            put("current_fields", buildJsonObject {
                fields.filterKeys { it in ProfileQualityAnalyzer.CONTEXT_FIELDS }
                    .forEach { (field, value) -> put(field, value.take(MAX_FIELD_CHARS)) }
            })
            put("issues", buildJsonArray {
                issues.filter { issue -> issue.fields.any { it in repairFields } }.forEach { issue ->
                    add(buildJsonObject {
                        put("severity", issue.severity)
                        put("fields", buildJsonArray { issue.fields.forEach { add(JsonPrimitive(it)) } })
                        put("message", issue.message)
                    })
                }
            })
            put("evidence_by_field", buildJsonObject {
                evidenceByField.forEach { (field, evidence) ->
                    put(field, buildJsonArray {
                        evidence.forEach { item ->
                            add(buildJsonObject {
                                put("evidence_id", item.id)
                                put("title", item.title)
                                put("excerpt", item.excerpt)
                                put("start_char", item.startChar)
                                put("end_char", item.endChar)
                            })
                        }
                    })
                }
            })
        }
        val response = activeLlm.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", repairPrompt),
                LlmClient.ChatMessage("user", json.encodeToString(JsonObject.serializer(), payload)),
            ),
            temperature = 0.1,
            maxTokens = 1400,
            requireJsonObject = true,
        )
        val content = response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (content.isBlank()) return emptyList()
        val parsed = json.parseToJsonElement(stripJsonFence(content)).jsonObject
        val seen = mutableSetOf<String>()
        return parsed["changes"]?.jsonArray.orEmpty().mapNotNull { raw ->
            val item = runCatching { raw.jsonObject }.getOrNull() ?: return@mapNotNull null
            val field = item["field"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (field !in repairFields || !seen.add(field)) return@mapNotNull null
            val after = item["value"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(MAX_FIELD_CHARS)
            val before = fields[field].orEmpty().trim()
            val confidence = item["confidence"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 100) ?: 0
            if (after.isBlank() || after.isInsufficientValue() || normalized(after) == normalized(before) || confidence < MIN_CONFIDENCE) {
                return@mapNotNull null
            }
            val allowedEvidence = evidenceByField[field].orEmpty().associateBy(PersonaEvidenceDto::id)
            val evidence = item["evidence_ids"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.let(allowedEvidence::get) }
                .distinctBy(PersonaEvidenceDto::id)
            if (evidence.isEmpty()) return@mapNotNull null
            PersonaRepairChangeDto(
                field = field,
                before = before,
                after = after,
                reason = item["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(MAX_REASON_CHARS),
                confidence = confidence,
                evidence = evidence,
            )
        }
    }

    private fun buildQualityReport(
        character: String,
        fields: Map<String, String>,
        issues: List<PersonaIssueDto>,
        evidenceCoverage: Int,
        confidence: Int,
        pendingRepairs: Int,
    ): PersonaQualityReportDto {
        val score = ProfileQualityAnalyzer.qualityScore(fields, issues)
        return PersonaQualityReportDto(
            character = character,
            score = score,
            maxScore = 100,
            grade = when { score >= 85 -> "A"; score >= 70 -> "B"; score >= 55 -> "C"; else -> "D" },
            verdict = when {
                issues.isEmpty() -> "人物档案核心字段完整，未发现明显重复或矛盾。"
                pendingRepairs > 0 -> "发现 ${issues.size} 项质量问题，已生成 $pendingRepairs 项有原文依据的字段修复建议。"
                else -> "发现 ${issues.size} 项待完善内容，未生成可安全应用的自动修改；未知字段保持为空。"
            },
            issues = issues,
            evidenceCoverage = evidenceCoverage,
            confidence = confidence,
            pendingRepairCount = pendingRepairs,
        )
    }

    private fun writeArtifacts(
        runDir: Path,
        novelId: String,
        character: String,
        proposal: PersonaRepairProposalDto,
        report: PersonaQualityReportDto,
    ) {
        val dir = runDir / "artifacts/characters/${PathSafety.sanitizePathComponent(novelId, "novelId")}/${PathSafety.sanitizePathComponent(character, "character")}"
        storage.mkdirs(dir)
        storage.writeTextAtomically(
            dir / REPAIR_PROPOSAL_FILE,
            json.encodeToString(PersonaRepairProposalDto.serializer(), proposal),
        )
        storage.writeTextAtomically(
            dir / QUALITY_REPORT_FILE,
            json.encodeToString(PersonaQualityReportDto.serializer(), report),
        )
    }

    private fun evidenceDto(raw: Map<String, Any?>): PersonaEvidenceDto? {
        val id = raw["source_id"]?.toString()?.trim().orEmpty()
        val excerpt = raw["excerpt"]?.toString()?.trim().orEmpty()
        if (id.isBlank() || excerpt.isBlank()) return null
        val location = raw["location"] as? Map<*, *>
        return PersonaEvidenceDto(
            id = id,
            title = raw["title"]?.toString()?.trim().orEmpty(),
            excerpt = excerpt,
            startChar = location?.get("start_char")?.toString()?.toIntOrNull() ?: 0,
            endChar = location?.get("end_char")?.toString()?.toIntOrNull() ?: 0,
        )
    }

    private fun stripJsonFence(value: String): String {
        var result = value.trim()
        if (result.startsWith("```")) {
            result = result.removePrefix("```").removePrefix("json").trim()
            result = result.removeSuffix("```").trim()
        }
        return result
    }

    private fun normalized(value: String): String = value.lowercase().replace(NON_WORD, "")

    private fun String.isInsufficientValue(): Boolean = trim().lowercase() in INSUFFICIENT_VALUES || "…" in this || "..." in this

    companion object {
        const val REPAIR_PROPOSAL_FILE = "REPAIR_PROPOSAL.json"
        const val QUALITY_REPORT_FILE = "QUALITY_REPORT.json"
        private const val TAG = "ProfileRepairService"
        private const val MAX_REPAIR_FIELDS = 10
        private const val EVIDENCE_PER_FIELD = 3
        private const val MIN_CONFIDENCE = 60
        private const val MAX_FIELD_CHARS = 800
        private const val MAX_REASON_CHARS = 240
        private val NON_WORD = Regex("[\\s，。；：、,.!！?？'\"“”‘’（）()《》<>_-]+")
        private val INSUFFICIENT_VALUES = setOf("不详", "信息不足", "暂无", "未知", "资料不足", "证据不足", "待补充", "留空", "insufficient")
    }
}

data class ProfileRepairResult(
    val proposal: PersonaRepairProposalDto,
    val report: PersonaQualityReportDto,
)

/** 不依赖模型的确定性质量检查，方便回归测试和离线运行。 */
object ProfileQualityAnalyzer {
    val REPAIRABLE_FIELDS = linkedSetOf(
        "core_identity", "story_role", "identity_anchor", "temperament_type", "gender", "age_stage",
        "appearance_feature", "habit_action", "soul_goal", "hidden_desire", "inner_conflict", "self_cognition",
        "private_self", "speech_style", "cadence", "typical_lines", "signature_phrases", "social_mode",
        "thinking_style", "decision_rules", "reward_logic", "worldview", "belief_anchor", "moral_bottom_line",
        "restraint_threshold", "core_traits", "key_bonds", "preference_like", "dislike_hate",
        "forbidden_behaviors", "stress_response", "emotion_model", "anger_style", "joy_style",
        "grievance_style", "others_impression", "strengths", "weaknesses", "ooc_redline",
    )
    val CONTEXT_FIELDS = REPAIRABLE_FIELDS + setOf("name", "timeline_stage", "contradiction_note", "evidence_source")
    val FIELD_HINTS = mapOf(
        "core_identity" to listOf("身份", "职位", "称谓"),
        "story_role" to listOf("事件", "推动", "冲突"),
        "gender" to listOf("称谓", "夫人", "公子", "姑娘"),
        "age_stage" to listOf("年纪", "少年", "青年", "老者"),
        "appearance_feature" to listOf("容貌", "衣着", "身形", "眼", "发"),
        "habit_action" to listOf("常常", "总是", "习惯", "动作"),
        "soul_goal" to listOf("想要", "追求", "必须", "愿望"),
        "hidden_desire" to listOf("心底", "渴望", "秘密", "执念"),
        "speech_style" to listOf("说道", "问道", "答道", "笑道"),
        "typical_lines" to listOf("说道", "问道", "答道"),
        "signature_phrases" to listOf("常说", "口头", "说道"),
        "key_bonds" to listOf("关系", "朋友", "亲人", "敌人"),
        "stress_response" to listOf("危急", "愤怒", "恐惧", "绝境"),
        "strengths" to listOf("擅长", "能力", "本领"),
        "weaknesses" to listOf("弱点", "失败", "不能", "害怕"),
    )

    fun parseMarkdown(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("- ") || ':' !in line) return@forEach
            val (rawKey, rawValue) = line.removePrefix("- ").split(":", limit = 2)
            val key = rawKey.trim()
            if (key.isNotBlank()) result[key] = rawValue.trim()
        }
        return result
    }

    fun analyze(
        fields: Map<String, String>,
        peerProfiles: Map<String, Map<String, String>> = emptyMap(),
    ): List<PersonaIssueDto> {
        val issues = mutableListOf<PersonaIssueDto>()
        REPAIRABLE_FIELDS.forEach { field ->
            val value = fields[field].orEmpty().trim()
            if (value.isBlank() || value.isPlaceholder()) {
                issues += PersonaIssueDto(
                    severity = if (field in HIGH_VALUE_FIELDS) "high" else "warning",
                    fields = listOf(field),
                    message = "$field 为空或仍是占位内容。",
                    suggestion = "只在原文证据充分时补全该字段。",
                )
            }
        }

        fields.filterKeys { it in REPAIRABLE_FIELDS }
            .filterValues { it.normalized().length >= MIN_TEMPLATE_LENGTH }
            .entries
            .groupBy { it.value.normalized() }
            .values
            .filter { it.size > 1 }
            .forEach { repeated ->
                val repeatedFields = repeated.map { it.key }
                issues += PersonaIssueDto(
                    severity = "warning",
                    fields = repeatedFields,
                    message = "多个字段出现完全相同的模板化内容：${repeatedFields.joinToString("、")}。",
                    suggestion = "根据各字段定义和原文证据分别收紧。",
                )
            }

        fields.filterKeys { it in REPAIRABLE_FIELDS }.forEach { (field, value) ->
            if (GENERIC_TEMPLATES.any { marker -> value.contains(marker) }) {
                issues += PersonaIssueDto(
                    severity = "warning",
                    fields = listOf(field),
                    message = "$field 含有低辨识度模板话术。",
                    suggestion = "替换为该角色独有、可由原文定位的特征。",
                )
            }
        }

        DISTINCTIVE_FIELDS.forEach { field ->
            val value = fields[field].orEmpty().trim()
            if (value.normalized().length < MIN_TEMPLATE_LENGTH) return@forEach
            val duplicatedBy = peerProfiles.filterValues { peer -> peer[field].orEmpty().normalized() == value.normalized() }.keys
            if (duplicatedBy.isNotEmpty()) {
                issues += PersonaIssueDto(
                    severity = "warning",
                    fields = listOf(field),
                    message = "$field 与其他角色（${duplicatedBy.joinToString("、")}）完全重复。",
                    suggestion = "重新检索该角色独有证据，避免多人共用模板。",
                )
            }
        }

        val contradictionNote = fields["contradiction_note"].orEmpty()
        if (contradictionNote.isBlank()) {
            CONTRADICTION_PAIRS.forEach { (left, right) ->
                val leftFields = fields.filterValues { it.contains(left) }.keys
                val rightFields = fields.filterValues { it.contains(right) }.keys
                val affected = (leftFields + rightFields).filter { it in REPAIRABLE_FIELDS }.distinct()
                if (affected.size >= 2) {
                    issues += PersonaIssueDto(
                        severity = "high",
                        fields = affected,
                        message = "档案同时出现“$left”与“$right”，但没有时间线或条件说明。",
                        suggestion = "结合原文确认冲突是否属于阶段变化，并只修正相关字段。",
                    )
                }
            }
        }
        return issues.distinctBy { issue -> issue.fields.sorted().joinToString("|") + issue.message }
    }

    fun isMissingFieldIssue(issue: PersonaIssueDto): Boolean =
        issue.message.endsWith("为空或仍是占位内容。")

    fun qualityScore(fields: Map<String, String>, issues: List<PersonaIssueDto>): Int {
        val filled = REPAIRABLE_FIELDS.count { field ->
            val value = fields[field].orEmpty().trim()
            value.isNotBlank() && !value.isPlaceholder()
        }
        val completeness = filled * 100 / REPAIRABLE_FIELDS.size
        // 空字段已通过 completeness 计分，不能再作为 issue 重复扣一次分。
        // 否则一份实际完整度 50~60 的档案会被压到 10~20。
        val qualityPenalty = issues.filterNot(::isMissingFieldIssue)
            .sumOf { if (it.severity == "high") 5 else 2 }
            .coerceAtMost(35)
        return (completeness - qualityPenalty).coerceIn(0, 100)
    }

    private fun String.isPlaceholder(): Boolean {
        val normalized = trim().lowercase()
        return normalized in PLACEHOLDERS || "…" in this || "..." in this
    }

    private fun String.normalized(): String = lowercase().replace(NON_WORD, "")

    private val HIGH_VALUE_FIELDS = setOf(
        "core_identity", "identity_anchor", "soul_goal", "core_traits", "speech_style",
        "decision_rules", "moral_bottom_line", "ooc_redline",
    )
    private val DISTINCTIVE_FIELDS = setOf(
        "identity_anchor", "temperament_type", "soul_goal", "hidden_desire", "core_traits",
        "speech_style", "decision_rules", "stress_response", "ooc_redline",
    )
    private val GENERIC_TEMPLATES = listOf(
        "性格复杂", "内心深处", "不轻易表露", "有自己的原则", "重视身边的人", "外冷内热", "亦正亦邪",
    )
    private val CONTRADICTION_PAIRS = listOf(
        "忠诚" to "背叛", "冷静" to "冲动", "克制" to "失控", "善良" to "残忍", "守序" to "反抗",
    )
    private val PLACEHOLDERS = setOf("不详", "信息不足", "暂无", "未知", "资料不足", "证据不足", "待补充", "留空", "insufficient")
    private val NON_WORD = Regex("[\\s，。；：、,.!！?？'\"“”‘’（）()《》<>_-]+")
    private const val MIN_TEMPLATE_LENGTH = 6
}
