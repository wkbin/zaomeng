package top.wkbin.zaomeng.ktor.utils

import kotlinx.serialization.Serializable

/**
 * 对话流式 JSON 增量投影器
 *
 * 将流式结构化 JSON 投影为可读的对话消息增量
 * 对应 Python 的 DialogueJsonDeltaProjector
 */
class DialogueStreamParser(
    private val chunkSize: Int = 24
) {
    private val buffer = StringBuilder()
    private val emittedLengths = mutableMapOf<String, Int>()

    // 正则表达式：匹配 JSON 中的 "speaker": "
    private val speakerPattern = Regex(""""speaker"\s*:\s*"""")
    private val messagePattern = Regex(""""message"\s*:\s*"""")
    private val innerThoughtPattern = Regex(""""inner_thought"\s*:\s*"""")

    fun reset() {
        buffer.clear()
        emittedLengths.clear()
    }

    /**
     * 返回已累积的完整原始输出（供流式结束后解析最终 responses）。
     */
    fun fullContent(): String = buffer.toString()

    /**
     * 喂入新的原始增量数据
     * @return 投影出的事件列表
     */
    fun feed(delta: String): List<StreamEvent> {
        buffer.append(delta)
        val messages = projectMessages()
        val events = mutableListOf<StreamEvent>()

        messages.forEachIndexed { index, message ->
            val role = if (message.speaker in setOf("旁白", "场景提示")) "scene" else "assistant"

            // 处理 message 字段
            processField(
                index = index,
                speaker = message.speaker,
                role = role,
                field = "message",
                value = message.message,
                events = events
            )

            // 处理 inner_thought 字段
            if (message.innerThought.isNotEmpty()) {
                processField(
                    index = index,
                    speaker = message.speaker,
                    role = role,
                    field = "inner_thought",
                    value = message.innerThought,
                    events = events
                )
            }
        }

        return events
    }

    private fun processField(
        index: Int,
        speaker: String,
        role: String,
        field: String,
        value: String,
        events: MutableList<StreamEvent>
    ) {
        val key = "$index:$field"
        val emitted = emittedLengths[key] ?: 0

        if (value.length <= emitted) return

        val suffix = value.substring(emitted)
        emittedLengths[key] = value.length

        // 按 chunkSize 切分
        var offset = 0
        while (offset < suffix.length) {
            val end = minOf(offset + chunkSize, suffix.length)
            events.add(
                StreamEvent(
                    index = index,
                    speaker = speaker,
                    role = role,
                    field = field,
                    text = suffix.substring(offset, end)
                )
            )
            offset = end
        }
    }

    private fun projectMessages(): List<ProjectedMessage> {
        val raw = buffer.toString()
        val messages = mutableListOf<ProjectedMessage>()

        // 查找所有 "speaker": " 的位置
        val speakerMatches = speakerPattern.findAll(raw).toList()

        for ((itemIndex, match) in speakerMatches.withIndex()) {
            // 解码 speaker 值
            val (speaker, speakerComplete, speakerEnd) = decodePartialJsonString(raw, match.range.last + 1)
            if (!speakerComplete || speaker.isBlank()) continue

            // 找到包含这个 speaker 的对象范围
            val (objStart, objEnd) = findObjectBounds(raw, match.range.first)

            // 在对象范围内查找 "message": "
            val messageMatch = messagePattern.find(raw, objStart)?.takeIf { it.range.first < objEnd }
            if (messageMatch == null) continue

            val (message, _, _) = decodePartialJsonString(raw, messageMatch.range.last + 1)
            if (message.isEmpty()) continue

            // 查找 inner_thought（可选）
            var innerThought = ""
            val innerMatch = innerThoughtPattern.find(raw, objStart)?.takeIf { it.range.first < objEnd }
            if (innerMatch != null) {
                val (inner, _, _) = decodePartialJsonString(raw, innerMatch.range.last + 1)
                innerThought = inner
            }

            messages.add(
                ProjectedMessage(
                    index = itemIndex,
                    speaker = speaker.trim(),
                    message = message,
                    innerThought = innerThought
                )
            )
        }

        return messages
    }

    /**
     * 解码 JSON 字符串（从开引号结束位置开始）
     * @return Triple(解码的字符串, 是否完整, 结束位置)
     */
    private fun decodePartialJsonString(text: String, start: Int): Triple<String, Boolean, Int> {
        val chars = StringBuilder()
        var index = start

        while (index < text.length) {
            val char = text[index]

            // 遇到闭引号，字符串完整
            if (char == '"') {
                return Triple(chars.toString(), true, index + 1)
            }

            // 非转义字符
            if (char != '\\') {
                chars.append(char)
                index++
                continue
            }

            // 转义字符：需要下一个字符
            if (index + 1 >= text.length) break

            val escaped = text[index + 1]
            when (escaped) {
                'u' -> {
                    // Unicode 转义：\uXXXX
                    if (index + 6 > text.length) break
                    val digits = text.substring(index + 2, index + 6)
                    val codePoint = digits.toIntOrNull(16)
                    if (codePoint != null) {
                        chars.append(codePoint.toChar())
                    } else {
                        chars.append('�')
                    }
                    index += 6
                }
                '"' -> { chars.append('"'); index += 2 }
                '\\' -> { chars.append('\\'); index += 2 }
                '/' -> { chars.append('/'); index += 2 }
                'b' -> { chars.append('\b'); index += 2 }
                'f' -> { chars.append(''); index += 2 }
                'n' -> { chars.append('\n'); index += 2 }
                'r' -> { chars.append('\r'); index += 2 }
                't' -> { chars.append('\t'); index += 2 }
                else -> { chars.append(escaped); index += 2 }
            }
        }

        return Triple(chars.toString(), false, index)
    }

    /**
     * 找到包含指定位置的 JSON 对象的边界
     * @return Pair(起始位置, 结束位置)
     */
    private fun findObjectBounds(text: String, position: Int): Pair<Int, Int> {
        val stack = mutableListOf<Int>()
        var targetStart = -1
        var inString = false
        var escaped = false

        for (index in text.indices) {
            if (index == position) {
                targetStart = stack.lastOrNull() ?: -1
            }

            if (inString) {
                if (escaped) {
                    escaped = false
                } else when (text[index]) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }

            when (text[index]) {
                '"' -> inString = true
                '{' -> stack.add(index)
                '}' -> {
                    if (stack.isNotEmpty()) {
                        val start = stack.removeAt(stack.lastIndex)
                        if (start == targetStart) {
                            return Pair(start, index + 1)
                        }
                    }
                }
            }
        }

        return if (targetStart >= 0) {
            Pair(targetStart, text.length)
        } else {
            Pair(0, text.length)
        }
    }

    private data class ProjectedMessage(
        val index: Int,
        val speaker: String,
        val message: String,
        val innerThought: String
    )
}

/**
 * 流式事件
 */
@Serializable
data class StreamEvent(
    val index: Int,
    val speaker: String,
    val role: String,
    val field: String,
    val text: String
)
