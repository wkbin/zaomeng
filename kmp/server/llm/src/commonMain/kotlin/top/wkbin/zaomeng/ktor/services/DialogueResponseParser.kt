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

    /** 模型给 speaker 加括号注释（如「祥子（车夫）」）或标点时，先去掉装饰再比对。 */
    private val speakerDecoration = Regex("[（(\\[【].*?[）)\\]】]")
    private const val SPEAKER_TRIM_CHARS = "　 \t·:：,，.。!！?？;；、\"'“”‘’*_-—"

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
        val aliasMap = buildSpeakerAliasMap(allowed)
        val forbidden = (forbiddenFixed + forbiddenSpeakers)
            .map { normalizeSpeakerKey(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val clean = mutableListOf<DialogueResponse>()
        for (element in items) {
            if (element !is JsonObject) continue
            val rawSpeaker = element["speaker"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val message = element["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawSpeaker.isEmpty() || message.isEmpty()) continue
            if (normalizeSpeakerKey(rawSpeaker) in forbidden) continue
            val canonical = canonicalSpeaker(rawSpeaker, allowed, aliasMap) ?: continue
            if (normalizeSpeakerKey(canonical) in forbidden) continue
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
        // Each object is already a complete response. Some models emit several alternative
        // continuations for the same speaker; keep the first one instead of joining them into
        // an oversized message after streaming has shown several temporary bubbles.
        return dropConsecutiveSpeakerRepeats(clean)
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

    private fun dropConsecutiveSpeakerRepeats(responses: List<DialogueResponse>): List<DialogueResponse> {
        val accepted = mutableListOf<DialogueResponse>()
        for (response in responses) {
            if (accepted.lastOrNull()?.speaker == response.speaker) continue
            accepted += response
        }
        return accepted
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
     * 宽松化后按以下顺序取回响应对象：
     * 1. 整段文本是完整 JSON 文档（数组 / `responses` 包装 / 单对象 / 围栏包裹）
     * 2. NDJSON 或尾部截断：逐行回收完整对象（不能先走 balanced fragment，否则多行对象只剩第一个）
     * 3. 前后缀噪音中的平衡 JSON 片段
     */
    private fun loadResponseItems(text: String): List<JsonElement> {
        // 模型常把换行直接放进 message 字符串（严格 JSON 非法），先做一次宽松化再尝试各种形态
        val normalized = normalizeJsonText(text)
        parseWholeDocument(normalized)?.let { return interpretResponseDocument(it) }
        val ndjsonItems = salvageNdjsonObjects(normalized)
        if (ndjsonItems.isNotEmpty()) return ndjsonItems
        return interpretResponseDocument(loadLlmJson(normalized))
    }

    private fun interpretResponseDocument(parsed: JsonElement): List<JsonElement> = when (parsed) {
        is JsonArray -> parsed
        is JsonObject -> parsed["responses"] as? JsonArray
            ?: if (parsed["speaker"] != null && parsed["message"] != null) listOf(parsed)
            else throw IllegalArgumentException("Model reply is not a response list.")
        else -> throw IllegalArgumentException("Model reply is not a response list.")
    }

    /** 只把整段文本当作一个 JSON 文档解析，不做片段截取。 */
    private fun parseWholeDocument(text: String): JsonElement? {
        val raw = text.trim()
        val stripped = stripCodeFences(text)
        val candidates = if (stripped != raw) listOf(raw, stripped) else listOf(raw)
        for (candidate in candidates) {
            val parsed = runCatching { json.parseToJsonElement(candidate) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /** 逐行回收完整 `{speaker,message}` 对象；未闭合或噪音行跳过。 */
    private fun salvageNdjsonObjects(text: String): List<JsonElement> {
        val stripped = stripCodeFences(text)
        return stripped.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { line ->
                // 容忍对象后误加的逗号（NDJSON 逐行对象之间常见）
                val candidate = line.trimEnd(',').trim()
                runCatching { json.parseToJsonElement(candidate) as? JsonObject }.getOrNull()
                    ?.takeIf { it["speaker"] != null && it["message"] != null }
            }
            .toList()
    }

    /**
     * 宽松化模型原始输出，使其能被严格 JSON 解析器接受（对齐 Python `strict=False`）：
     * - 去除 BOM、把不间断空格换成普通空格
     * - 把字符串字面量里的裸换行/制表符/控制字符转义成 `\n`/`\t`/`\uXXXX`
     * 字符串外部的换行保持不变，因此 NDJSON 逐行结构不会被破坏；
     * 而已被折行的 `message` 会重新并回同一行，避免整条回复被判为非法 JSON。
     */
    private fun normalizeJsonText(text: String): String {
        val source = text.removePrefix("\uFEFF").replace('\u00A0', ' ')
        val out = StringBuilder(source.length + 16)
        var inString = false
        var escaped = false
        for (ch in source) {
            if (!inString) {
                if (ch == '"') inString = true
                out.append(ch)
                continue
            }
            when {
                escaped -> {
                    when {
                        ch == '\n' -> out.append("\\n")
                        ch == '\r' -> out.append("\\r")
                        ch == '\t' -> out.append("\\t")
                        ch.code < 0x20 -> out.appendControlEscape(ch)
                        else -> out.append(ch)
                    }
                    escaped = false
                }
                ch == '\\' -> {
                    out.append(ch)
                    escaped = true
                }
                ch == '"' -> {
                    out.append(ch)
                    inString = false
                }
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch.code < 0x20 -> out.appendControlEscape(ch)
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun StringBuilder.appendControlEscape(ch: Char) {
        append("\\u").append(ch.code.toString(16).padStart(4, '0'))
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

    private fun canonicalSpeaker(
        speaker: String,
        allowed: Set<String>,
        aliasMap: Map<String, String>,
    ): String? {
        if (speaker in allowed) return speaker
        return aliasMap[normalizeSpeakerKey(speaker)]
    }

    /** 归一化 speaker：去括号装饰、去首尾标点、合并空白、忽略大小写。 */
    private fun normalizeSpeakerKey(name: String): String {
        val withoutDecoration = speakerDecoration.replace(name, "")
        val trimmed = withoutDecoration.trim { it in SPEAKER_TRIM_CHARS }
        return trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()
    }

    private fun buildSpeakerAliasMap(allowed: Set<String>): Map<String, String> {
        val aliasMap = mutableMapOf<String, String>()
        for (name in allowed) {
            val key = normalizeSpeakerKey(name)
            if (key.isNotEmpty()) aliasMap.putIfAbsent(key, name)
        }
        return aliasMap
    }
}
