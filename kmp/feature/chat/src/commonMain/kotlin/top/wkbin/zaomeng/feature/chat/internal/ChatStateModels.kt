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

data class ChatToolOption(
    val label: String,
    val value: String,
    val description: String = "",
    val messageKind: String = "plot",
    val suggestionDirection: String = "",
    val pluginId: String = "",
    val pluginActionId: String = "",
    val pluginSelection: String = "",
    val pluginSeedText: String = "",
    val pluginTitle: String = "",
)

data class ChatPluginAction(
    val pluginId: String,
    val pluginName: String,
    val actionId: String,
    val title: String,
    val icon: String = "",
    val contribution: String = "chat_action",
)

data class ChatGenerationEnhancer(
    val pluginId: String,
    val pluginName: String,
    val enhancerId: String,
    val title: String,
    val description: String = "",
    val icon: String = "",
    val defaultActive: Boolean = false,
) {
    val stateKey: String get() = "$pluginId/$enhancerId"

    fun isActive(session: DialogueSessionDto?): Boolean =
        session?.pluginEnhancerStates?.get(pluginId)?.get(enhancerId) ?: defaultActive
}

internal data class LoadedChatPlugins(
    val actions: List<ChatPluginAction> = emptyList(),
    val enhancers: List<ChatGenerationEnhancer> = emptyList(),
)

internal const val INNER_THOUGHTS_ENHANCER_KEY =
    "com.zaomeng.inner-thoughts/inner-thoughts"
internal const val MODEL_REASONING_DISPLAY_LIMIT = 16_000
internal const val MODEL_REASONING_UPDATE_INTERVAL_NANOS = 100_000_000L
internal const val STREAMING_UI_UPDATE_INTERVAL_MS = 40L

data class StreamingReplyPart(
    val index: Int,
    val speaker: String = "",
    val role: String = "character",
    val text: String = "",
    val innerThought: String = "",
)

enum class PendingUserMessageStatus {
    Sending,
    Failed,
    OutcomeUnknown,
}

data class PendingUserMessage(
    val operationId: String,
    val message: String,
    val messageKind: String,
    val speakerOverride: String = "",
    val status: PendingUserMessageStatus,
    val statusText: String = "",
    val retryable: Boolean = true,
)

data class ChatUiState(
    val runId: String = "",
    val sessionId: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** 正在向上加载更早的历史消息。 */
    val loadingEarlier: Boolean = false,
    val sending: Boolean = false,
    val recovering: Boolean = false,
    val sendOutcomeUnknown: Boolean = false,
    val sendBaselineTranscript: List<TranscriptItemDto>? = null,
    val failedOperationId: String = "",
    val failedMessage: String = "",
    val failedMessageKind: String = "dialogue",
    val failedSpeakerOverride: String = "",
    val modelReasoning: String = "",
    val streamingReplies: List<StreamingReplyPart> = emptyList(),
    val pendingUserMessage: PendingUserMessage? = null,
    val continuousObserveEnabled: Boolean = false,
    val includeInnerThoughts: Boolean = false,
    val toolBusy: String = "",
    val session: DialogueSessionDto? = null,
    /** 本卷会话列表（桌面端主从布局左侧面板用）。 */
    val runSessions: List<DialogueSessionDto> = emptyList(),
    val avatarBytes: Map<String, ByteArray> = emptyMap(),
    val sceneCards: List<ReusableCardDto> = emptyList(),
    val pluginActions: List<ChatPluginAction> = emptyList(),
    val generationEnhancers: List<ChatGenerationEnhancer> = emptyList(),
    val toolOptions: List<ChatToolOption> = emptyList(),
    val toolOptionsTitle: String = "",
    val recommendedSceneCardId: String = "",
    val recommendedTransition: String = "",
    val navigationSession: DialogueSessionDto? = null,
    val memorySaveRevision: Long = 0,
    val memoryQuality: MemoryQualityReportDto = MemoryQualityReportDto(),
    val draft: String = "",
    val draftSpeakerOverride: String = "",
    val messageKind: String = "dialogue",
    val pacing: String = "normal",
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<ChatSearchResultDto> = emptyList(),
    val chatDisplay: ChatDisplayPreferences = ChatDisplayPreferences(),
    val notice: String = "",
    val error: String = "",
) {
    val canSend: Boolean
        get() = !loading &&
            !refreshing &&
            !loadingEarlier &&
            !sending &&
            !continuousObserveEnabled &&
            !recovering &&
            !sendOutcomeUnknown &&
            failedOperationId.isBlank() &&
            toolBusy.isBlank() &&
            session?.status == "ready" &&
            draft.isNotBlank()

    val canUseTools: Boolean
        get() = !loading &&
            !refreshing &&
            !sending &&
            !continuousObserveEnabled &&
            !recovering &&
            !sendOutcomeUnknown &&
            failedOperationId.isBlank() &&
            toolBusy.isBlank() &&
            session?.status == "ready"

    val canRefresh: Boolean
        get() = !loading &&
            !refreshing &&
            !sending &&
            !continuousObserveEnabled &&
            !recovering &&
            toolBusy.isBlank()

    val canToggleContinuousObserve: Boolean
        get() = if (continuousObserveEnabled) {
            true
        } else {
            !loading &&
                !refreshing &&
                !sending &&
                !recovering &&
                !sendOutcomeUnknown &&
                failedOperationId.isBlank() &&
                toolBusy.isBlank() &&
                session?.status == "ready" &&
                session.mode == "observe"
        }
}
