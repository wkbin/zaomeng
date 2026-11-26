package top.wkbin.zaomeng.data.api

import okio.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class SessionsResponse(
    val items: List<SessionListItem> = emptyList(),
    /** 当前过滤/排序条件下的会话总数（不含 offset/limit 截断）。 */
    val total: Int = 0,
    /** 是否还有下一页（Paging 3 据此决定 nextKey）。 */
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** 列表投影 → 全量会话 DTO（列表接口只返回投影字段，其余保持默认值）。 */
fun SessionListItem.toDialogueSessionDto(): DialogueSessionDto = DialogueSessionDto(
    sessionId = sessionId,
    runId = runId,
    novelId = novelId,
    title = title,
    mode = mode,
    modeDisplay = modeDisplay,
    participants = participants,
    characterAvatars = characterAvatars,
    controlledCharacter = controlledCharacter,
    cardGreetingIndex = cardGreetingIndex,
    status = status,
    turnCount = turnCount,
    currentTurnId = currentTurnId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastEntryPreview = lastEntryPreview,
)

/** 服务端会话列表分页响应（类型化，避免 Map<String, Any> 携带自定义类型无法序列化）。 */
@Serializable
data class SessionsPageResponse(
    val items: List<SessionListItem>,
    val total: Int,
    @SerialName("has_more") val hasMore: Boolean,
)

/**
 * 会话列表投影（服务端列表接口返回）。
 *
 * 只携带列表需要的字段，避免整份 manifest（transcript/场景历史等重字段）透传；
 * 客户端 [DialogueSessionDto] 用默认值补齐其余字段，WebUI 列表也只依赖这些字段。
 */
@Serializable
data class SessionListItem(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("run_id") val runId: String = "",
    @SerialName("novel_id") val novelId: String = "",
    /** 书卷标题（服务端由 run manifest 关联注入，用于按书名搜索/排序）。 */
    @SerialName("run_title") val runTitle: String = "",
    val title: String = "",
    val mode: String = "observe",
    @SerialName("mode_display") val modeDisplay: String = "",
    val participants: List<String> = emptyList(),
    @SerialName("character_avatars") val characterAvatars: Map<String, String> = emptyMap(),
    @SerialName("controlled_character") val controlledCharacter: String = "",
    val status: String = "ready",
    @SerialName("turn_count") val turnCount: Int = 0,
    @SerialName("current_turn_id") val currentTurnId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("last_entry_preview") val lastEntryPreview: String = "",
    @SerialName("card_greeting_index") val cardGreetingIndex: Int? = null,
)

/**
 * 会话 manifest 的持久化投影（创建会话时构造后落盘）。
 *
 * 注意：已有会话的读-改-写路径仍走 JsonObject，因为高级字段由多个服务增量写入，
 * 类型化解码会丢掉未知字段；本模型只用于「新建」这种纯构造写出的场景。
 */
@Serializable
data class SessionManifest(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("run_id") val runId: String = "",
    val mode: String = "observe",
    val participants: List<String> = emptyList(),
    @SerialName("controlled_character") val controlledCharacter: String = "",
    @SerialName("scene_card_id") val sceneCardId: String = "",
    @SerialName("scene_profile") val sceneProfile: JsonObject = JsonObject(emptyMap()),
    @SerialName("self_card_id") val selfCardId: String = "",
    @SerialName("self_profile") val selfProfile: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val title: String = "",
    val status: String = "ready",
    /** 已提交剧情历史版本；旧存档缺省时为 0。 */
    val revision: Long = 0,
    val transcript: List<JsonObject> = emptyList(),
    val turns: List<String> = emptyList(),
    @SerialName("turn_count") val turnCount: Int = 0,
    @SerialName("current_turn_id") val currentTurnId: String = "",
)

@Serializable
data class SessionRefDto(
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class DeleteSessionsRequest(val items: List<SessionRefDto>)

@Serializable
data class DeleteSessionsResponse(
    val status: String = "",
    @SerialName("deleted_count") val deletedCount: Int = 0,
    val deleted: List<SessionRefDto> = emptyList(),
    @SerialName("not_found_count") val notFoundCount: Int = 0,
    @SerialName("not_found") val notFound: List<SessionRefDto> = emptyList(),
)

@Serializable
enum class AuthorNotePosition(val sillyTavernId: Int) {
    @SerialName("before_prompt") BeforePrompt(2),
    @SerialName("after_prompt") AfterPrompt(0),
    @SerialName("in_chat") InChat(1),
}

@Serializable
data class AuthorNoteDto(
    val text: String = "",
    val enabled: Boolean = true,
    val depth: Int = 4,
    val role: WorldBookInjectionRole = WorldBookInjectionRole.System,
    val interval: Int = 1,
    val position: AuthorNotePosition = AuthorNotePosition.InChat,
) {
    companion object {
        const val MAX_TEXT_LENGTH = 4000
    }
}

@Serializable
data class DialogueSessionDto(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("run_id") val runId: String = "",
    @SerialName("novel_id") val novelId: String = "",
    val title: String = "",
    val mode: String = "observe",
    @SerialName("mode_display") val modeDisplay: String = "",
    val participants: List<String> = emptyList(),
    @SerialName("character_avatars") val characterAvatars: Map<String, String> = emptyMap(),
    @SerialName("controlled_character") val controlledCharacter: String = "",
    @SerialName("card_greeting_index") val cardGreetingIndex: Int? = null,
    val status: String = "ready",
    /** 已提交剧情历史版本；轻量响应和消息分页使用同一版本。 */
    val revision: Long = 0,
    @SerialName("turns") val turns: List<JsonObject> = emptyList(),
    @SerialName("turn_count") val turnCount: Int = 0,
    @SerialName("current_turn_id") val currentTurnId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("last_entry_preview") val lastEntryPreview: String = "",
    val transcript: List<TranscriptItemDto> = emptyList(),
    /** transcript 总条数（include_transcript=false 的轻量响应仍携带，供分页/增量判定）。 */
    @SerialName("transcript_count") val transcriptCount: Int = 0,
    @SerialName("pending_turn_summary") val pendingTurnSummary: PendingTurnDto = PendingTurnDto(),
    @SerialName("scene_card_id") val sceneCardId: String = "",
    @SerialName("scene_card") val sceneCard: JsonObject = JsonObject(emptyMap()),
    @SerialName("scene_profile") val sceneProfile: JsonObject = JsonObject(emptyMap()),
    @SerialName("self_card_id") val selfCardId: String = "",
    @SerialName("self_insert") val selfInsert: JsonObject = JsonObject(emptyMap()),
    @SerialName("self_profile") val selfProfile: JsonObject = JsonObject(emptyMap()),
    @SerialName("memory_ledger") val memoryLedger: List<DialogueMemoryDto> = emptyList(),
    @SerialName("scene_history") val sceneHistory: List<JsonObject> = emptyList(),
    @SerialName("event_timeline") val eventTimeline: List<JsonObject> = emptyList(),
    @SerialName("runtime_state_overview") val runtimeStateOverview: JsonObject = JsonObject(emptyMap()),
    @SerialName("scene_progress") val sceneProgress: JsonObject = JsonObject(emptyMap()),
    @SerialName("relation_matrix") val relationMatrix: JsonObject = JsonObject(emptyMap()),
    @SerialName("relation_timeline") val relationTimeline: List<JsonObject> = emptyList(),
    @SerialName("relation_locks") val relationLocks: JsonObject = JsonObject(emptyMap()),
    @SerialName("branch_graph") val branchGraph: JsonObject = JsonObject(emptyMap()),
    @SerialName("branch_origin") val branchOrigin: JsonObject = JsonObject(emptyMap()),
    @SerialName("branch_meta") val branchMeta: JsonObject = JsonObject(emptyMap()),
    @SerialName("session_memory_summary") val sessionMemorySummary: JsonObject = JsonObject(emptyMap()),
    @SerialName("chapter_outline") val chapterOutline: JsonObject = JsonObject(emptyMap()),
    @SerialName("character_arcs") val characterArcs: List<JsonObject> = emptyList(),
    @SerialName("consistency_monitor") val consistencyMonitor: JsonObject = JsonObject(emptyMap()),
    @SerialName("speaker_activity") val speakerActivity: List<JsonObject> = emptyList(),
    @SerialName("speaker_balance") val speakerBalance: JsonObject = JsonObject(emptyMap()),
    @SerialName("event_signals") val eventSignals: JsonObject = JsonObject(emptyMap()),
    @SerialName("generation_cache_stats") val generationCacheStats: JsonObject = JsonObject(emptyMap()),
    @SerialName("latest_context_usage") val latestContextUsage: JsonObject = JsonObject(emptyMap()),
    @SerialName("plugin_enhancer_states")
    val pluginEnhancerStates: Map<String, Map<String, Boolean>> = emptyMap(),
    @SerialName("muted_characters") val mutedCharacters: List<String> = emptyList(),
    @SerialName("story_recap") val storyRecap: StoryRecapDto? = null,
    @SerialName("author_note") val authorNote: AuthorNoteDto = AuthorNoteDto(),
)

@Serializable
data class StoryRecapDto(
    val title: String = "",
    val summary: String = "",
    val location: String = "",
    @SerialName("time_hint") val timeHint: String = "",
    val atmosphere: String = "",
    val participants: List<String> = emptyList(),
    @SerialName("event_count") val eventCount: Int = 0,
    @SerialName("chapter_count") val chapterCount: Int = 0,
    @SerialName("unresolved_hook_count") val unresolvedHookCount: Int = 0,
    val events: List<StoryEventDto> = emptyList(),
    val relations: List<StoryRelationChangeDto> = emptyList(),
    @SerialName("character_arcs") val characterArcs: List<StoryCharacterArcDto> = emptyList(),
    val hooks: List<String> = emptyList(),
    val quotes: List<StoryQuoteDto> = emptyList(),
    @SerialName("next_hint") val nextHint: String = "",
    @SerialName("share_text") val shareText: String = "",
)

@Serializable
data class SessionProjectionDto(
    /** fresh / stale / missing / building / failed。 */
    val status: String = "missing",
    @SerialName("source_revision") val sourceRevision: Long = 0,
    @SerialName("current_revision") val currentRevision: Long = 0,
    /** 轻量会话，叠加可重建的 story_recap/session_memory_summary；不含完整 transcript。 */
    val session: DialogueSessionDto? = null,
    val error: String = "",
)

@Serializable
data class StoryEventDto(
    val title: String = "",
    @SerialName("turn_id") val turnId: String = "",
    @SerialName("time_hint") val timeHint: String = "",
    val location: String = "",
    val participants: List<String> = emptyList(),
    @SerialName("event_types") val eventTypes: List<String> = emptyList(),
    val responses: List<StoryResponseDto> = emptyList(),
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class StoryResponseDto(
    val speaker: String = "",
    val message: String = "",
)

@Serializable
data class StoryRelationChangeDto(
    @SerialName("pair_key") val pairKey: String = "",
    val label: String = "",
    val characters: List<String> = emptyList(),
    val current: Map<String, Int> = emptyMap(),
    val changes: List<StoryMetricChangeDto> = emptyList(),
    val reason: String = "",
    val evidence: String = "",
    @SerialName("turn_id") val turnId: String = "",
)

@Serializable
data class StoryMetricChangeDto(
    val metric: String = "",
    val label: String = "",
    val delta: Int = 0,
)

@Serializable
data class StoryCharacterArcDto(
    val name: String = "",
    @SerialName("growth_summary") val growthSummary: String = "",
    val current: Map<String, String> = emptyMap(),
)

@Serializable
data class StoryQuoteDto(
    val speaker: String = "",
    val message: String = "",
)

@Serializable
data class PendingTurnDto(
    @SerialName("turn_id") val turnId: String = "",
    val speaker: String = "",
    val message: String = "",
    @SerialName("message_kind") val messageKind: String = "dialogue",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class TranscriptItemDto(
    /** 当前 revision 下的全局零基位置；旧数据未携带时为 -1。 */
    val seq: Int = -1,
    val speaker: String = "",
    val message: String = "",
    @SerialName("inner_thought") val innerThought: String = "",
    val role: String = "character",
    @SerialName("turn_id") val turnId: String = "",
    val timestamp: String = "",
    val evidence: List<OriginalKnowledgeEntryDto> = emptyList(),
    @SerialName("world_book_matches") val worldBookMatches: List<WorldBookMatchDto> = emptyList(),
    @SerialName("sillytavern_message_index") val sillyTavernMessageIndex: String? = null,
    @SerialName("sillytavern_swipe_count") val sillyTavernSwipeCount: Int? = null,
)

@Serializable
data class ChatSearchResponse(
    val items: List<ChatSearchResultDto> = emptyList(),
)

@Serializable
data class ChatSearchResultDto(
    val speaker: String = "",
    val message: String = "",
    val role: String = "character",
    @SerialName("turn_id") val turnId: String = "",
    val timestamp: String = "",
    val archived: Boolean = false,
    val score: Double = 0.0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CreateDialogueSessionRequest(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val mode: String = "observe",
    val participants: List<String> = emptyList(),
    @SerialName("controlled_character") val controlledCharacter: String = "",
    @SerialName("scene_card_id") val sceneCardId: String = "",
    @SerialName("scene_profile") val sceneProfile: JsonObject = JsonObject(emptyMap()),
    @SerialName("self_card_id") val selfCardId: String = "",
    @SerialName("self_profile") val selfProfile: JsonObject = JsonObject(emptyMap()),
    @SerialName("card_greeting_index") val cardGreetingIndex: Int? = null,
)

@Serializable
data class CardGreetingsDto(
    val character: String = "",
    val options: List<CardGreetingDto> = emptyList(),
    val revision: String = "",
    @SerialName("next_alternate_index") val nextAlternateIndex: Int? = null,
)

@Serializable
data class CardGreetingDto(val index: Int, val text: String, val truncated: Boolean = false)

const val CARD_GREETING_CHAR_LIMIT = 4_000

@Serializable
data class UpdateCardGreetingRequest(val revision: String, val index: Int, val text: String)

@Serializable
data class ImportSillyTavernChatRequest(
    val filename: String,
    @SerialName("content_base64") val contentBase64: String,
)

@Serializable
data class ImportedSwipePageDto(
    val total: Int,
    @SerialName("selected_index") val selectedIndex: Int? = null,
    val items: List<ImportedSwipeDto> = emptyList(),
)

@Serializable
data class ImportedSwipeDto(val index: Int, val text: String, val truncated: Boolean = false)

@Serializable
data class BranchImportedSwipeRequest(
    @SerialName("message_index") val messageIndex: String,
    @SerialName("swipe_index") val swipeIndex: Int,
)

@Serializable
data class DialogueReplyRequest(
    val message: String,
    @SerialName("message_kind") val messageKind: String = "dialogue",
    /** Per-turn response pacing: brief, normal, or detailed. */
    val pacing: String = "normal",
    /** Optional character identity used when a plugin drafts and sends a reply on that character's behalf. */
    @SerialName("speaker_override") val speakerOverride: String = "",
    @SerialName("suppress_transcript_message") val suppressTranscriptMessage: Boolean = false,
    @SerialName("include_inner_thoughts") val includeInnerThoughts: Boolean = false,
    @SerialName("include_model_reasoning") val includeModelReasoning: Boolean = false,
    /** false 时响应/流式 complete 不再携带 transcript（客户端用消息分页接口 + appended 增量维护本地列表）。 */
    @SerialName("include_transcript") val includeTranscript: Boolean = false,
    @SerialName("operation_id") val operationId: String = "",
    @SerialName("include_prompt_preview") val includePromptPreview: Boolean = false,
)

@Serializable
data class DialoguePromptPreviewDto(
    @SerialName("turn_id") val turnId: String,
    val attempt: Int,
    val messages: List<DialoguePromptMessageDto>,
    @SerialName("world_book_matches") val worldBookMatches: List<WorldBookMatchDto> = emptyList(),
)

@Serializable
data class DialoguePromptMessageDto(
    val role: String,
    val text: String,
    val truncated: Boolean = false,
)

@Serializable
data class DialogueOperationStatusResponse(
    @SerialName("operation_id") val operationId: String = "",
    @SerialName("turn_id") val turnId: String = "",
    val status: String = "",
    @SerialName("base_revision") val baseRevision: Long = 0,
    @SerialName("committed_revision") val committedRevision: Long? = null,
    @SerialName("transcript_count") val transcriptCount: Int = 0,
    @SerialName("failure_code") val failureCode: String = "",
    @SerialName("failure_message") val failureMessage: String = "",
    val retryable: Boolean = false,
    val session: DialogueSessionDto? = null,
    @SerialName("appended_transcript") val appendedTranscript: List<TranscriptItemDto> = emptyList(),
)

/** 会话消息分页响应（历史消息懒加载）。 */
@Serializable
data class MessagesResponse(
    val items: List<TranscriptItemDto> = emptyList(),
    /** 会话 transcript 总条数。 */
    val total: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    /** 本页所属剧情历史版本。 */
    val revision: Long = 0,
    /** 同方向、同 revision 的下一页 offset；没有下一页时为 null。 */
    @SerialName("next_offset") val nextOffset: Int? = null,
)

@Serializable
data class DialogueSuggestionRequest(
    @SerialName("seed_text") val seedText: String = "",
    val direction: String = "",
)

@Serializable
data class PluginChatActionRequest(
    @SerialName("seed_text") val seedText: String = "",
    val direction: String = "",
    val selection: String = "",
)

@Serializable
data class PluginSuggestionOptionDto(
    val label: String = "",
    val suggestion: String = "",
)

@Serializable
data class PluginActionChoiceDto(
    val label: String = "",
    val value: String = "",
    val description: String = "",
)

@Serializable
data class PluginChatActionResponse(
    val suggestion: String = "",
    val suggestions: List<PluginSuggestionOptionDto> = emptyList(),
    val notice: String = "",
    val character: String = "",
    val session: DialogueSessionDto = DialogueSessionDto(),
    @SerialName("choice_prompt") val choicePrompt: String = "",
    val choices: List<PluginActionChoiceDto> = emptyList(),
)

@Serializable
data class PluginTemporaryNpcGeneratorRequest(
    val direction: String = "",
)

@Serializable
data class PluginTemporaryNpcGeneratorResponse(
    val session: DialogueSessionDto = DialogueSessionDto(),
    val notice: String = "",
    val npc: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class DialogueSuggestionResponse(val suggestion: String = "")

@Serializable
data class DialogueDirectorRequest(
    val goal: String,
    val action: String = "advance",
    @SerialName("option_count") val optionCount: Int = 3,
)

@Serializable
data class SwitchDialogueSceneRequest(
    @SerialName("scene_card_id") val sceneCardId: String = "",
    @SerialName("scene_profile") val sceneProfile: JsonObject = JsonObject(emptyMap()),
    @SerialName("transition_message") val transitionMessage: String = "",
    @SerialName("auto_continue") val autoContinue: Boolean = false,
)

@Serializable
data class UpsertDialogueMemoryRequest(
    val text: String,
    val category: String = "story",
    val pinned: Boolean = false,
    val enabled: Boolean = true,
)

@Serializable
data class DialogueMemoryDto(
    @SerialName("memory_id") val memoryId: String = "",
    val text: String = "",
    val category: String = "story",
    val pinned: Boolean = false,
    val enabled: Boolean = true,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    /** user = 用户维护的可控记忆；automatic = 从完成轮次自动建立的检索记忆。 */
    val source: String = "user",
    @SerialName("source_turn_id") val sourceTurnId: String = "",
    /** active / stale / conflict */
    val status: String = "active",
    @SerialName("last_hit_turn_id") val lastHitTurnId: String = "",
    @SerialName("last_hit_at") val lastHitAt: String = "",
    @SerialName("hit_count") val hitCount: Int = 0,
    @SerialName("duplicate_of") val duplicateOf: String = "",
    @SerialName("merged_source_ids") val mergedSourceIds: List<String> = emptyList(),
)

@Serializable
data class MemoryQualityReportDto(
    val entries: List<DialogueMemoryDto> = emptyList(),
    @SerialName("latest_hit_turn_id") val latestHitTurnId: String = "",
    @SerialName("duplicate_groups") val duplicateGroups: List<List<String>> = emptyList(),
    @SerialName("active_count") val activeCount: Int = 0,
    @SerialName("stale_count") val staleCount: Int = 0,
    @SerialName("conflict_count") val conflictCount: Int = 0,
)

@Serializable
data class UpdateMemoryQualityStatusRequest(
    val status: String,
)

@Serializable
data class BranchDialogueTurnRequest(
    @SerialName("turn_id") val turnId: String,
)

@Serializable
data class BranchDialogueSceneRequest(
    @SerialName("scene_index") val sceneIndex: Int,
)

@Serializable
data class UpdateDialogueBranchMetaRequest(
    val label: String? = null,
    @SerialName("is_mainline") val isMainline: Boolean? = null,
    @SerialName("locked_event_ids") val lockedEventIds: List<String>? = null,
)

@Serializable
data class UpdateDialogueSessionTitleRequest(
    val title: String,
)

@Serializable
data class UpdateDialogueRelationLockRequest(
    @SerialName("pair_key") val pairKey: String,
    val locked: Boolean,
)

@Serializable
data class DeleteStatusDto(val status: String = "")

@Serializable
data class SceneTensionDto(
    val score: Int = 30,
    val pacing: String = "Calm",
    val label: String = "平缓蓄势",
    val suggestion: String = "",
    @SerialName("dominant_emotion") val dominantEmotion: String = "neutral",
    @SerialName("conflict_indicator") val conflictIndicator: Float = 0.2f,
)

@Serializable
data class PlotEventPresetDto(
    val id: String = "",
    val category: String = "external",
    @SerialName("category_label") val categoryLabel: String = "外部异动",
    val title: String = "",
    val event: String = "",
    @SerialName("recommended_action") val recommendedAction: String = "advance",
)
