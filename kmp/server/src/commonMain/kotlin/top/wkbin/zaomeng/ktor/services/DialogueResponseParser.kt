package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.ktor.models.DialogueResponse

/**
 * 对话回复解析器（迁移自 Python src/web/chat/helpers.py:parse_dialogue_responses）。
 *
 * 主协议为 NDJSON（每行一个 {speaker, message, inner_thought}）；同时兼容旧数组与 responses 包装对象：
 * - 支持代码围栏剥离与容错 JSON 定位（找首个 [ 或 { 截取）
 * - 过滤 speaker/message 为空的项
 * - speaker 规范化：精确/去空白匹配 allowedSpeakers；不在允许列表且不在禁止列表的 speaker 丢弃
 * - 无有效回复时抛异常（不 fallback 原文，避免把 JSON 当对话文本）
 */
object DialogueResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    private val forbiddenFixed = setOf("user", "你", "模型推理", "system", "assistant")

    /**
     * 解析 LLM 回复文本为对话响应列表。
     *
     * @param content LLM 返回的原始文本
     * @param allowedSpeakers 允许的发言者（通常为参与者 + ["旁白", "场景提示"]）
     * @param forbiddenSpeakers 禁止的发言者（通常为受控角色与用户身份）
     * @throws IllegalArgumentException 无有效回复或格式错误
     */
    fun parse(
        content: String,
        allowedSpeakers: List<String>,
        forbiddenSpeakers: List<String> = emptyList(),
    ): List<DialogueResponse> {
        val text = content.trim()
        if (text.isEmpty()) throw IllegalArgumentException("Model returned an empty reply.")
        val items = loadResponseItems(text)
        val allowed = allowedSpeakers.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val forbidden = forbiddenFixed + forbiddenSpeakers
            .mapNotNull { it.trim().lowercase().ifEmpty { null } }
        val clean = mutableListOf<DialogueResponse>()
        for (element in items) {
            if (element !is JsonObject) continue
            val rawSpeaker = element["speaker"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val message = element["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawSpeaker.isEmpty() || message.isEmpty()) continue
            if (rawSpeaker.lowercase() in forbidden) continue
            val canonical = canonicalSpeaker(rawSpeaker, allowed) ?: continue
            val innerThought = element["inner_thought"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.take(50)?.ifEmpty { null }
            clean.add(
                DialogueResponse(
                    speaker = canonical,
                    message = message,
                    innerThought = innerThought,
                )
            )
        }
        if (clean.isEmpty()) {
            throw IllegalArgumentException("Model reply did not contain usable character responses.")
        }
        // 群聊兜底：同一角色连续多条时合并成一条，避免同一人连着发消息
        return mergeConsecutiveSpeakers(clean)
    }

    /**
     * 保守兼容未按 JSON 契约输出的模型：只有唯一一个合法角色时，才能把纯文本归属给该角色。
     * 多人场景、代码围栏、推理标签或疑似 JSON 一律不猜测，交回调用方重试。
     */
    fun parseSingleSpeakerPlainText(
        content: String,
        allowedSpeakers: List<String>,
        forbiddenSpeakers: List<String> = emptyList(),
    ): List<DialogueResponse>? {
        val text = content.trim()
        if (
            text.isEmpty() || text.length > 12_000 ||
            text.contains("```") || text.contains("<think", ignoreCase = true) ||
            text.contains('[') || text.contains('{') ||
            text.contains("\"speaker\"") || text.contains("\"message\"")
        ) {
            return null
        }
        val forbidden = (forbiddenFixed + forbiddenSpeakers)
            .mapNotNull { it.trim().lowercase().ifEmpty { null } }
            .toSet()
        val eligible = allowedSpeakers
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it in setOf("旁白", "场景提示") }
            .filterNot { it.lowercase() in forbidden }
            .distinct()
        if (eligible.size != 1) return null
        return listOf(DialogueResponse(speaker = eligible.single(), message = text))
    }

    private fun mergeConsecutiveSpeakers(responses: List<DialogueResponse>): List<DialogueResponse> {
        val merged = mutableListOf<DialogueResponse>()
        for (response in responses) {
            val last = merged.lastOrNull()
            if (last != null && last.speaker == response.speaker) {
                merged[merged.lastIndex] = last.copy(
                    message = last.message + "\n" + response.message,
                    innerThought = last.innerThought ?: response.innerThought,
                )
            } else {
                merged += response
            }
        }
        return merged
    }

    private fun loadLlmJson(text: String): JsonElement {
        // 候选机制（对齐 Python _loads_llm_json）：原始文本 → 围栏剥离 → 平衡片段，逐个尝试
        val raw = text.trim()
        val candidates = mutableListOf(raw)
        val stripped = stripCodeFences(text)
        if (stripped != raw) candidates += stripped
        // 平衡 [ ] / { } 片段：容忍 ```json[ 同行等围栏噪音（deepseek-v4-flash 输出格式）
        balancedJsonCandidates(stripped).forEach { candidate ->
            if (candidate !in candidates) candidates += candidate
        }
        for (candidate in candidates) {
            val parsed = runCatching { json.parseToJsonElement(candidate) }.getOrNull()
            if (parsed != null) return parsed
        }
        throw IllegalArgumentException("Model reply is not valid JSON.")
    }

    /**
     * NDJSON is attempted first so every completed line remains independently usable.
     * A malformed/truncated final line is ignored only when at least one earlier line is complete.
     */
    private fun loadResponseItems(text: String): List<JsonElement> {
        val stripped = stripCodeFences(text)
        val nonBlankLines = stripped.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (nonBlankLines.isNotEmpty()) {
            val ndjsonItems = mutableListOf<JsonElement>()
            var ndjsonShape = true
            for ((index, line) in nonBlankLines.withIndex()) {
                val item = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                if (item == null || item["speaker"] == null || item["message"] == null) {
                    // Only the final line may be incomplete. Noise or pretty-printed legacy JSON
                    // falls through to the legacy whole-document parser below.
                    if (index != nonBlankLines.lastIndex) ndjsonShape = false
                } else {
                    ndjsonItems += item
                }
            }
            if (ndjsonShape && ndjsonItems.isNotEmpty()) {
                return ndjsonItems
            }
        }

        return when (val parsed = loadLlmJson(text)) {
            is JsonArray -> parsed
            is JsonObject -> parsed["responses"] as? JsonArray
                ?: if (parsed["speaker"] != null && parsed["message"] != null) listOf(parsed)
                else throw IllegalArgumentException("Model reply is not a response list.")
            else -> throw IllegalArgumentException("Model reply is not a response list.")
        }
    }

    /**
     * 剥离代码围栏（``` 或 ```json）。兼容 ```json[ 与 JSON 同行的输出：
     * 语言标签后若紧跟 JSON 起始字符（[ 或 {），保留剩余部分而不是整行丢弃。
     */
    private fun stripCodeFences(text: String): String {
        var t = text.trim()
        while (t.startsWith("```")) {
            val firstNewline = t.indexOf('\n')
            t = if (firstNewline >= 0) {
                val firstLine = t.substring(0, firstNewline).trim()
                val restAfterLang = firstLine.removePrefix("```").removePrefix("json").trim()
                if (restAfterLang.startsWith("[") || restAfterLang.startsWith("{")) {
                    // ```json[ 同行：保留 [ 前缀
                    restAfterLang + "\n" + t.substring(firstNewline + 1)
                } else {
                    t.substring(firstNewline + 1)
                }
            } else {
                t.removePrefix("```").removePrefix("json").trim()
            }
            // 去掉结尾围栏
            t = t.trimEnd().removeSuffix("```").trim()
        }
        return t.trim()
    }

    /**
     * 提取完整平衡的 [ ] / { } 片段（容忍代码围栏与前后缀噪音；对齐 Python _balanced_json_candidates）。
     */
    private fun balancedJsonCandidates(text: String): List<String> {
        val candidates = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val opener = text[start]
            if (opener != '[' && opener != '{') { start++; continue }
            val stack = ArrayDeque<Char>()
            stack.addLast(if (opener == '[') ']' else '}')
            var inString = false
            var escaped = false
            var completed = false
            var end = start + 1
            while (end < text.length) {
                val char = text[end]
                if (inString) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') inString = false
                } else {
                    when (char) {
                        '"' -> inString = true
                        '[' -> stack.addLast(']')
                        '{' -> stack.addLast('}')
                        ']', '}' -> {
                            if (char != stack.last()) break
                            stack.removeLast()
                            if (stack.isEmpty()) {
                                candidates += text.substring(start, end + 1)
                                start = end + 1
                                completed = true
                                break
                            }
                        }
                    }
                }
                end++
            }
            if (completed) continue
            // JSON 根部截断（EOF 未闭合）：不把内部嵌套对象当独立响应（对齐 Python）
            if (start + 1 < text.length && text[start + 1] in "{[\"-0123456789tfn \t\r\n") break
            start++
        }
        return candidates
    }

    private fun canonicalSpeaker(speaker: String, allowed: Set<String>): String? {
        if (speaker in allowed) return speaker
        val normalized = speaker.replace(" ", "").lowercase()
        for (name in allowed) {
            if (name.replace(" ", "").lowercase() == normalized) return name
        }
        return null
    }
}
