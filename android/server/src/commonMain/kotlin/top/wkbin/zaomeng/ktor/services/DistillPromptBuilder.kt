package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 蒸馏/关系提示词构建（迁移自 Python src/web/prompts/builders.py +
 * fragments.py）。
 */
object DistillPromptBuilder {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
    }

    /** render_payload_section：字符串直接展示，其他值 JSON dump（indent=2, 中文不转义）。 */
    fun renderPayloadSection(title: String, value: Any?): String {
        val body = when (value) {
            is String -> value.trim()
            else -> {
                val element = toJsonElement(value)
                json.encodeToString(JsonObject.serializer(), element as? JsonObject ?: JsonObject(emptyMap()))
            }
        }
        return "## $title\n$body".trim()
    }

    private fun render(template: String, params: Map<String, String>): String {
        var out = template
        params.forEach { (key, value) -> out = out.replace("{$key}", value) }
        return out.trim()
    }

    private fun chunkDistillGuidance(
        guidance: Map<String, String>,
        chunkLabel: String = "",
        chunkIndex: Int = 0,
        chunkTotal: Int = 0,
        chunkMode: String = "",
    ): String {
        if (chunkTotal <= 0) return ""
        return render(
            guidance["chunk_distill"].orEmpty(),
            mapOf(
                "chunk_index" to chunkIndex.toString(),
                "chunk_total" to chunkTotal.toString(),
                "chunk_label" to chunkLabel.ifEmpty { "未命名证据块" },
                "chunk_partial_line" to if (chunkMode == "partial") guidance["chunk_partial_line"].orEmpty() else "",
            ),
        )
    }

    private fun relationChunkGuidance(
        guidance: Map<String, String>,
        chunkLabel: String = "",
        chunkIndex: Int = 0,
        chunkTotal: Int = 0,
        chunkMode: String = "",
    ): String {
        if (chunkTotal <= 0) return ""
        return render(
            guidance["relation_chunk_distill"].orEmpty(),
            mapOf(
                "chunk_index" to chunkIndex.toString(),
                "chunk_total" to chunkTotal.toString(),
                "chunk_label" to chunkLabel.ifEmpty { "未命名关系块" },
                "chunk_partial_line" to if (chunkMode == "partial") guidance["relation_chunk_partial_line"].orEmpty() else "",
            ),
        )
    }

    private fun distillPriorityGuidance(guidance: Map<String, String>, character: String): String =
        render(guidance["priority_guidance"].orEmpty(), mapOf("character" to character))

    private fun dialogueStyleGuidance(guidance: Map<String, String>, evidenceLines: List<String>): String {
        val evidence = evidenceLines.mapNotNull { it.trim() }.filter { it.isNotEmpty() }
        val lines = if (evidence.isEmpty()) "- " else evidence.joinToString("\n") { "- $it" }
        return render(guidance["dialogue_style"].orEmpty(), mapOf("evidence_lines" to lines))
    }

    private fun excerptStageGuidance(guidance: Map<String, String>, excerptStages: Map<String, Any?>): String =
        render(
            guidance["excerpt_stages"].orEmpty(),
            mapOf(
                "start" to (excerptStages["start"]?.toString()?.trim().orEmpty()),
                "mid" to (excerptStages["mid"]?.toString()?.trim().orEmpty()),
                "end" to (excerptStages["end"]?.toString()?.trim().orEmpty()),
            ),
        )

    fun buildDistillMessages(
        payload: DistillPayload,
        character: String,
        peers: List<String>,
        chunkLabel: String = "",
        chunkIndex: Int = 0,
        chunkTotal: Int = 0,
        chunkMode: String = "",
    ): List<LlmClient.ChatMessage> {
        val focusedRequest = payload.request.toMutableMap().apply { remove("excerpt_stages") }
        val guidance = payload.guidance
        val peers = peersText(peers)
        val userHead = render(
            guidance["distill_user_head"].orEmpty(),
            mapOf("character" to character, "peers" to peers),
        )
        val userParts = mutableListOf<Any?>(
            userHead.ifEmpty { "目标角色：$character\n同批角色：$peers" },
            distillPriorityGuidance(guidance, character),
            excerptStageGuidance(
                guidance,
                (payload.request["excerpt_stages"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap(),
            ),
            dialogueStyleGuidance(guidance, emptyList()),
            chunkDistillGuidance(guidance, chunkLabel, chunkIndex, chunkTotal, chunkMode),
            renderPayloadSection("OUTPUT_SCHEMA", payload.references["output_schema"]),
            renderPayloadSection("STYLE_DIFFER", payload.references["style_differ"]),
            renderPayloadSection("LOGIC_CONSTRAINT", payload.references["logic_constraint"]),
            renderPayloadSection("VALIDATION_POLICY", payload.references["validation_policy"]),
            renderPayloadSection("REQUEST", focusedRequest),
            renderPayloadSection("META", payload.meta),
        )
        return listOf(
            LlmClient.ChatMessage(role = "system", content = payload.prompt),
            LlmClient.ChatMessage(role = "user", content = userParts.filterNotNull().joinToString("\n\n").trim()),
        )
    }

    fun buildDistillMergeMessages(
        payload: DistillPayload,
        character: String,
        peers: List<String>,
        chunkDrafts: List<Pair<String, String>>,
        fallbackReason: String,
    ): List<LlmClient.ChatMessage> {
        val focusedRequest = payload.request.toMutableMap().apply {
            remove("excerpt")
            remove("excerpt_stages")
        }
        val guidance = payload.guidance
        val peers = peersText(peers)
        val fallbackLine = if (fallbackReason.isNotBlank()) {
            render(guidance["fallback_line"].orEmpty(), mapOf("fallback_reason" to fallbackReason))
        } else {
            ""
        }
        val userHead = render(
            guidance["distill_merge_head"].orEmpty(),
            mapOf("character" to character, "peers" to peers, "fallback_line" to fallbackLine),
        )
        val draftsText = chunkDrafts.joinToString("\n\n") { (label, content) ->
            "### $label\n${content.trim()}".trim()
        }.trim()
        val userParts = mutableListOf<Any?>(
            userHead.ifEmpty { "目标角色：$character\n同批角色：$peers" },
            distillPriorityGuidance(guidance, character),
            excerptStageGuidance(
                guidance,
                (payload.request["excerpt_stages"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap(),
            ),
            dialogueStyleGuidance(guidance, emptyList()),
            renderPayloadSection("OUTPUT_SCHEMA", payload.references["output_schema"]),
            renderPayloadSection("STYLE_DIFFER", payload.references["style_differ"]),
            renderPayloadSection("LOGIC_CONSTRAINT", payload.references["logic_constraint"]),
            renderPayloadSection("VALIDATION_POLICY", payload.references["validation_policy"]),
            renderPayloadSection("REQUEST", focusedRequest),
            renderPayloadSection("CHUNK_DRAFTS", draftsText),
            renderPayloadSection("META", payload.meta),
        )
        return listOf(
            LlmClient.ChatMessage(role = "system", content = payload.prompt),
            LlmClient.ChatMessage(role = "user", content = userParts.filterNotNull().joinToString("\n\n").trim()),
        )
    }

    fun buildRelationMessages(
        payload: DistillPayload,
        characters: List<String>,
        chunkLabel: String = "",
        chunkIndex: Int = 0,
        chunkTotal: Int = 0,
        chunkMode: String = "",
    ): List<LlmClient.ChatMessage> {
        val relationRequest = payload.request.toMutableMap().apply { put("characters", characters) }
        val guidance = payload.guidance
        val userParts = mutableListOf<Any?>(
            guidance["relation_user_head"].orEmpty().ifEmpty { "请严格输出一份完整的关系图谱 Markdown。" },
            relationChunkGuidance(guidance, chunkLabel, chunkIndex, chunkTotal, chunkMode),
            renderPayloadSection("OUTPUT_SCHEMA", payload.references["output_schema"]),
            renderPayloadSection("LOGIC_CONSTRAINT", payload.references["logic_constraint"]),
            renderPayloadSection("VALIDATION_POLICY", payload.references["validation_policy"]),
            renderPayloadSection("REQUEST", relationRequest),
            renderPayloadSection("META", payload.meta),
        )
        return listOf(
            LlmClient.ChatMessage(role = "system", content = payload.prompt),
            LlmClient.ChatMessage(role = "user", content = userParts.filterNotNull().joinToString("\n\n").trim()),
        )
    }

    fun buildRelationMergeMessages(
        payload: DistillPayload,
        characters: List<String>,
        chunkDrafts: List<Pair<String, String>>,
        fallbackReason: String,
    ): List<LlmClient.ChatMessage> {
        val relationRequest = payload.request.toMutableMap().apply {
            put("characters", characters)
            remove("excerpt")
            remove("excerpt_stages")
        }
        val guidance = payload.guidance
        val fallbackLine = if (fallbackReason.isNotBlank()) {
            render(guidance["fallback_line"].orEmpty(), mapOf("fallback_reason" to fallbackReason))
        } else {
            ""
        }
        val userHead = render(
            guidance["relation_merge_head"].orEmpty(),
            mapOf("fallback_line" to fallbackLine),
        )
        val draftsText = chunkDrafts.joinToString("\n\n") { (label, content) ->
            "### $label\n${content.trim()}".trim()
        }.trim()
        val userParts = mutableListOf<Any?>(
            userHead.ifEmpty { "以下是基于多个证据块得到的局部关系图谱草稿，请整合成唯一一份最终 RELATION_GRAPH Markdown。" },
            renderPayloadSection("OUTPUT_SCHEMA", payload.references["output_schema"]),
            renderPayloadSection("LOGIC_CONSTRAINT", payload.references["logic_constraint"]),
            renderPayloadSection("VALIDATION_POLICY", payload.references["validation_policy"]),
            renderPayloadSection("REQUEST", relationRequest),
            renderPayloadSection("CHUNK_DRAFTS", draftsText.ifEmpty { "证据不足" }),
            renderPayloadSection("META", payload.meta),
        )
        return listOf(
            LlmClient.ChatMessage(role = "system", content = payload.prompt),
            LlmClient.ChatMessage(role = "user", content = userParts.filterNotNull().joinToString("\n\n").trim()),
        )
    }

    private fun peersText(peers: List<String>): String =
        peers.filter(String::isNotBlank).joinToString("、").ifEmpty { "无" }

    private fun toJsonElement(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (key, item) -> put(key.toString(), toJsonElement(item)) }
        }
        is List<*> -> buildJsonArray { value.forEach { add(toJsonElement(it)) } }
        else -> JsonPrimitive(value.toString())
    }
}

/** 蒸馏 prompt payload（对齐 Python prompt_payloads.build_distill_prompt_payload）。 */
data class DistillPayload(
    val prompt: String,
    val references: Map<String, String>,
    val request: Map<String, Any?>,
    val meta: Map<String, Any?>,
    val guidance: Map<String, String> = emptyMap(),
)
