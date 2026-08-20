package top.wkbin.zaomeng.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ReusableCardKind
import top.wkbin.zaomeng.data.ApiRequestException
import top.wkbin.zaomeng.data.CardRepository
import top.wkbin.zaomeng.data.DialogueRepository
import top.wkbin.zaomeng.data.PluginRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueStreamEvent
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ChatDisplayPreferences
import top.wkbin.zaomeng.domain.chat.LoadChatSessionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import top.wkbin.zaomeng.client.platform.clientRandomUuid

/**
 * 从完整 transcript 中提取 baseline 之后「已提交」的新增条目。
 * 只有包含非用户内容才算提交成功（对齐旧 hasCommittedReply 语义，避免误判重复发送）。
 */
internal fun committedAppend(
    baselineSize: Int,
    currentTranscript: List<TranscriptItemDto>,
): List<TranscriptItemDto> {
    if (currentTranscript.size <= baselineSize) return emptyList()
    val appended = currentTranscript.drop(baselineSize)
    return appended.takeIf { items ->
        items.any { it.role != "user" && it.message.isNotBlank() }
    }.orEmpty()
}

/**
 * 按 turn_id 幂等合并 transcript：append 中已存在于 base 的条目替换而非重复追加。
 * 断连重试 / 失败恢复时，base 可能已经包含部分或全部本轮条目（本地已拼过、服务端已提交），
 * 直接 `base + append` 会把整段历史重复一遍；按 turn_id 去重后天然幂等。
 */
internal fun mergeTranscript(
    base: List<TranscriptItemDto>,
    append: List<TranscriptItemDto>,
): List<TranscriptItemDto> {
    if (append.isEmpty()) return base
    val appendedTurnIds = append.map { it.turnId }.filter { it.isNotBlank() }.toSet()
    if (appendedTurnIds.isEmpty()) return base + append
    return base.filterNot { it.turnId in appendedTurnIds } + append
}

internal fun hasCommittedContent(items: List<TranscriptItemDto>): Boolean =
    items.any { it.role != "user" && it.message.isNotBlank() }

internal fun JsonObject.extractDirectorOptions(): List<ChatToolOption> = this["options"]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { element ->
        val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val messageKind = stringValue("message_kind").ifBlank { "plot" }
        val title = item.stringValue("title")
        val beat = item.stringValue("beat")
        val direction = item.stringValue("direction")
        if (title.isBlank() || beat.isBlank() || direction.isBlank()) return@mapNotNull null
        val details = buildList {
            item.stringValue("focus").takeIf(String::isNotBlank)?.let { add("焦点：$it") }
            item.stringValue("expected_effect").takeIf(String::isNotBlank)?.let { add("效果：$it") }
            item.stringValue("risk").takeIf(String::isNotBlank)?.let { add("风险：$it") }
            item.stringValue("resistance").takeIf(String::isNotBlank)?.let { add("抵抗：$it") }
            item.stringValue("price").takeIf(String::isNotBlank)?.let { add("代价：$it") }
        }
        ChatToolOption(
            label = title,
            value = listOf(beat, direction).distinct().joinToString("；"),
            description = details.joinToString(" · "),
            messageKind = messageKind,
        )
    }
    .orEmpty()

internal fun JsonObject.stringValue(key: String): String = this[key]
    ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    .orEmpty()

internal fun JsonObject.stringList(key: String): List<String> = this[key]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { element ->
        runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }
    .orEmpty()

internal fun Throwable.readableMessage(fallback: String): String = message
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: fallback

internal class StreamReplyException(
    message: String,
    val retryable: Boolean,
) : Exception(message)
