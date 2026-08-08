package top.wkbin.zaomeng.data.api

import java.io.File
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
data class ModelSettingsDto(
    val provider: String = "openai-compatible",
    val model: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 0,
    @SerialName("reasoning_effort") val reasoningEffort: String = "off",
    @SerialName("api_key_configured") val apiKeyConfigured: Boolean = false,
    val configured: Boolean = false,
    @SerialName("active_profile_id") val activeProfileId: String = "",
    val profiles: List<ModelProfileDto> = emptyList(),
)

@Serializable
data class ModelProfileDto(
    @SerialName("profile_id") val profileId: String = "",
    val name: String = "",
    val provider: String = "openai-compatible",
    val model: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 0,
    @SerialName("reasoning_effort") val reasoningEffort: String = "off",
    @SerialName("api_key_configured") val apiKeyConfigured: Boolean = false,
    val configured: Boolean = false,
)

@Serializable
data class SaveModelSettingsRequest(
    val provider: String,
    val model: String,
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 0,
    @SerialName("reasoning_effort") val reasoningEffort: String = "off",
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("profile_name") val profileName: String = "",
    @SerialName("create_profile") val createProfile: Boolean = false,
    @SerialName("activate_profile") val activateProfile: Boolean = true,
)

@Serializable
data class TestModelSettingsRequest(
    val provider: String,
    val model: String,
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 0,
    @SerialName("reasoning_effort") val reasoningEffort: String = "off",
    @SerialName("profile_id") val profileId: String = "",
)

@Serializable
data class ModelConnectionTestDto(
    val ok: Boolean = false,
    val provider: String = "",
    val model: String = "",
    @SerialName("latency_ms") val latencyMs: Int = 0,
    val message: String = "",
)

@Serializable
data class PluginsResponse(
    val items: List<PluginDto> = emptyList(),
)

@Serializable
data class PluginDto(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    @SerialName("apiVersion") val apiVersion: String = "",
    val description: String = "",
    val permissions: List<String> = emptyList(),
    val settings: List<PluginSettingDto> = emptyList(),
    val config: JsonObject = JsonObject(emptyMap()),
    val contributes: PluginContributionsDto = PluginContributionsDto(),
    @SerialName("defaultEnabled") val defaultEnabled: Boolean = false,
    val enabled: Boolean = false,
    val status: String = "disabled",
    val error: String = "",
    val source: String = "third-party",
)

@Serializable
data class PluginSettingDto(
    val key: String = "",
    val title: String = "",
    val type: String = "",
    val default: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonNull,
    val min: Int? = null,
    val max: Int? = null,
    val options: List<PluginSettingOptionDto> = emptyList(),
)

@Serializable
data class PluginSettingOptionDto(
    val value: String = "",
    val label: String = "",
)

@Serializable
data class InspectPluginPackageRequest(
    val filename: String,
    @SerialName("content_base64") val contentBase64: String,
)

@Serializable
data class PluginPackageInspectionDto(
    val token: String = "",
    val plugin: PluginDto = PluginDto(),
    val operation: String = "install",
    val blockedReason: String = "",
    val currentVersion: String = "",
    val compatible: Boolean = true,
    val hostApiVersion: String = "1",
    val fileCount: Int = 0,
    val extractedBytes: Long = 0,
)

@Serializable
data class InstallPluginPackageRequest(
    @SerialName("confirm_permissions") val confirmPermissions: Boolean,
    @SerialName("allow_update") val allowUpdate: Boolean,
)

@Serializable
data class UninstallPluginResponse(
    val status: String = "",
    val pluginId: String = "",
    val recoverablePath: String = "",
    val uninstalledAt: String = "",
)

@Serializable
data class PluginLogsResponse(
    val items: List<PluginLogDto> = emptyList(),
)

@Serializable
data class PluginConfigResponse(
    val config: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class UpdatePluginConfigRequest(
    val config: JsonObject,
)

@Serializable
data class PluginLogDto(
    val timestamp: String = "",
    val pluginId: String = "",
    val level: String = "info",
    val event: String = "",
    val message: String = "",
    val details: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PluginContributionsDto(
    @SerialName("chatActions") val chatActions: List<PluginChatActionDto> = emptyList(),
    @SerialName("generationEnhancers")
    val generationEnhancers: List<PluginGenerationEnhancerDto> = emptyList(),
    @SerialName("temporaryNpcGenerators")
    val temporaryNpcGenerators: List<PluginTemporaryNpcGeneratorDto> = emptyList(),
)

@Serializable
data class PluginChatActionDto(
    val id: String = "",
    val title: String = "",
    val placement: String = "composer",
    val icon: String = "",
)

@Serializable
data class PluginGenerationEnhancerDto(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val icon: String = "",
    @SerialName("defaultActive") val defaultActive: Boolean = false,
)

@Serializable
data class PluginTemporaryNpcGeneratorDto(
    val id: String = "",
    val title: String = "",
    val icon: String = "",
)

@Serializable
data class SetGenerationEnhancerStateRequest(val enabled: Boolean)

@Serializable
data class RunsResponse(val items: List<RunManifestDto> = emptyList())

@Serializable
data class BuiltinNovelsResponse(val items: List<BuiltinNovelDto> = emptyList())

@Serializable
data class BuiltinNovelDto(
    @SerialName("package_id") val packageId: String = "",
    val title: String = "",
    @SerialName("novel_id") val novelId: String = "",
    val status: String = "",
    @SerialName("character_count") val characterCount: Int = 0,
    @SerialName("has_relation_graph") val hasRelationGraph: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    val filename: String = "",
    val builtin: Boolean = false,
)

@Serializable
data class RunManifestDto(
    @SerialName("run_id") val runId: String = "",
    @SerialName("novel_id") val novelId: String = "",
    @SerialName("novel_path") val novelPath: String = "",
    val status: String = "unknown",
    val success: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("locked_characters") val lockedCharacters: List<String> = emptyList(),
    @SerialName("novel_sources") val novelSources: List<NovelSourceDto> = emptyList(),
    val progress: RunProgressDto = RunProgressDto(),
    val summary: RunSummaryDto = RunSummaryDto(),
    val timing: RunTimingDto = RunTimingDto(),
    val control: RunControlDto = RunControlDto(),
    @SerialName("artifact_index") val artifactIndex: ArtifactIndexDto = ArtifactIndexDto(),
    @SerialName("imported_from") val importedFrom: ImportedFromDto = ImportedFromDto(),
    @SerialName("beta_feature") val betaFeature: BetaFeatureDto? = null,
) {
    val title: String
        get() = novelSources.firstOrNull()?.sourceName
            ?.substringBeforeLast('.')
            ?.takeIf(String::isNotBlank)
            ?: novelId.ifBlank { runId }

    val isTerminal: Boolean
        get() = status in setOf("ready", "failed", "stopped", "draft")

    val isInterrupted: Boolean
        get() = status == "stopped" &&
            progress.stage == "interrupted" &&
            control.interruptionReason == "android_process_ended"

    val availableCharacters: List<String>
        get() = artifactIndex.characters.map(PersonaIndexDto::name).filter(String::isNotBlank)
}

@Serializable
data class NovelSourceDto(
    @SerialName("source_name") val sourceName: String = "",
    @SerialName("source_path") val sourcePath: String = "",
    val kind: String = "",
    val timestamp: String = "",
    @SerialName("byte_size") val byteSize: Long = 0,
    @SerialName("char_count") val charCount: Int = 0,
)

@Serializable(with = ImportedFromDtoSerializer::class)
data class ImportedFromDto(
    @SerialName("package_filename") val packageFilename: String = "",
    @SerialName("builtin_source") val builtinSource: Boolean = false,
    @SerialName("imported_at") val importedAt: String = "",
    @SerialName("online_library") val onlineLibrary: OnlineLibrarySourceDto? = null,
)

object ImportedFromDtoSerializer : KSerializer<ImportedFromDto> {
    override val descriptor: SerialDescriptor = ImportedFromDtoSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): ImportedFromDto {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("ImportedFromDto can only be decoded from JSON")
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonPrimitive && element.isString) {
            return ImportedFromDto(packageFilename = element.content)
        }
        val value = jsonDecoder.json.decodeFromJsonElement<ImportedFromDtoSurrogate>(element)
        return ImportedFromDto(
            packageFilename = value.packageFilename,
            builtinSource = value.builtinSource,
            importedAt = value.importedAt,
            onlineLibrary = value.onlineLibrary,
        )
    }

    override fun serialize(encoder: Encoder, value: ImportedFromDto) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("ImportedFromDto can only be encoded as JSON")
        jsonEncoder.encodeJsonElement(
            jsonEncoder.json.encodeToJsonElement(
                ImportedFromDtoSurrogate(
                    packageFilename = value.packageFilename,
                    builtinSource = value.builtinSource,
                    importedAt = value.importedAt,
                    onlineLibrary = value.onlineLibrary,
                ),
            ),
        )
    }
}

@Serializable
private data class ImportedFromDtoSurrogate(
    @SerialName("package_filename") val packageFilename: String = "",
    @SerialName("builtin_source") val builtinSource: Boolean = false,
    @SerialName("imported_at") val importedAt: String = "",
    @SerialName("online_library") val onlineLibrary: OnlineLibrarySourceDto? = null,
)

@Serializable
data class OnlineLibrarySourceDto(
    val id: String = "",
    val title: String = "",
    val version: String = "",
    @SerialName("download_url") val downloadUrl: String = "",
    val sha256: String = "",
)

@Serializable
data class BetaFeatureDto(
    val kind: String = "",
    val unstable: Boolean = false,
    @SerialName("world_setting") val worldSetting: String = "",
    @SerialName("source_snapshots") val sourceSnapshots: List<CrossoverSourceDto> = emptyList(),
)

@Serializable
data class CrossoverSourceDto(
    @SerialName("run_id") val runId: String = "",
    val character: String = "",
)

@Serializable
data class CrossoverParticipantRequest(
    @SerialName("run_id") val runId: String,
    val character: String,
)

@Serializable
data class CreateCrossoverSpaceRequest(
    val title: String,
    @SerialName("world_setting") val worldSetting: String,
    val participants: List<CrossoverParticipantRequest>,
)

@Serializable
data class RunProgressDto(
    val stage: String = "",
    val message: String = "",
    @SerialName("current_character") val currentCharacter: String = "",
    @SerialName("completed_characters") val completedCharacters: List<String> = emptyList(),
    @SerialName("total_characters") val totalCharacters: Int = 0,
    @SerialName("completed_count") val completedCount: Int = 0,
    @SerialName("graph_status") val graphStatus: String = "",
)

@Serializable
data class RunSummaryDto(
    @SerialName("characters_total") val charactersTotal: Int = 0,
    @SerialName("characters_completed") val charactersCompleted: Int = 0,
    @SerialName("graph_status") val graphStatus: String = "",
    @SerialName("status_text") val statusText: String = "",
)

@Serializable
data class RunTimingDto(
    @SerialName("elapsed_seconds") val elapsedSeconds: Double = 0.0,
    @SerialName("elapsed_text") val elapsedText: String = "",
)

@Serializable
data class RunControlDto(
    @SerialName("stop_requested") val stopRequested: Boolean = false,
    @SerialName("interrupted_at") val interruptedAt: String = "",
    @SerialName("interruption_reason") val interruptionReason: String = "",
)

@Serializable
data class ArtifactIndexDto(
    val characters: List<PersonaIndexDto> = emptyList(),
)

@Serializable
data class PersonaIndexDto(
    val name: String = "",
    val preview: PersonaPreviewDto = PersonaPreviewDto(),
    @SerialName("avatar_version") val avatarVersion: String = "",
)

@Serializable
data class PersonaAvatarDto(
    val character: String = "",
    @SerialName("avatar_version") val avatarVersion: String = "",
)

@Serializable
data class PersonaPreviewDto(
    @SerialName("core_identity") val coreIdentity: String = "",
    @SerialName("story_role") val storyRole: String = "",
    @SerialName("soul_goal") val soulGoal: String = "",
    @SerialName("speech_style") val speechStyle: String = "",
    @SerialName("temperament_type") val temperamentType: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CreateRunRequest(
    @SerialName("novel_name") val novelName: String,
    @SerialName("novel_content_base64") val novelContentBase64: String,
    val characters: List<String>,
    @SerialName("max_sentences") val maxSentences: Int = 120,
    @SerialName("max_chars") val maxChars: Int = 50_000,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("auto_run") val autoRun: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("defer_run") val deferRun: Boolean = false,
)

@Serializable
data class ImportRunPackageRequest(
    val filename: String,
    @SerialName("content_base64") val contentBase64: String,
    @SerialName("library_package") val libraryPackage: LibraryPackageImportDto? = null,
)

@Serializable
data class LibraryPackageImportDto(
    val id: String,
    val title: String,
    val version: String,
    @SerialName("download_url") val downloadUrl: String,
    val sha256: String,
)

@Serializable
data class EstimateSamplingRequest(
    @SerialName("char_count") val charCount: Int,
    @SerialName("sentence_count") val sentenceCount: Int,
    @SerialName("character_count") val characterCount: Int,
    @SerialName("max_sentences") val maxSentences: Int = 120,
    @SerialName("max_chars") val maxChars: Int = 50_000,
)

@Serializable
data class SamplingPlanDto(
    @SerialName("char_count") val charCount: Int = 0,
    @SerialName("sentence_count") val sentenceCount: Int = 0,
    @SerialName("character_count") val characterCount: Int = 0,
    @SerialName("suggested_max_chars") val suggestedMaxChars: Int = 50_000,
    @SerialName("suggested_max_sentences") val suggestedMaxSentences: Int = 120,
    @SerialName("effective_chars") val effectiveChars: Int = 0,
    @SerialName("effective_sentences") val effectiveSentences: Int = 0,
    @SerialName("distill_chunk_count") val distillChunkCount: Int = 1,
    @SerialName("relation_chunk_count") val relationChunkCount: Int = 1,
    @SerialName("distill_calls_per_character") val distillCallsPerCharacter: Int = 1,
    @SerialName("relation_calls") val relationCalls: Int = 1,
    @SerialName("total_calls") val totalCalls: Int = 0,
    @SerialName("token_low") val tokenLow: Int = 0,
    @SerialName("token_high") val tokenHigh: Int = 0,
    @SerialName("distill_time_low_seconds") val distillTimeLowSeconds: Int = 0,
    @SerialName("distill_time_high_seconds") val distillTimeHighSeconds: Int = 0,
    @SerialName("relation_time_low_seconds") val relationTimeLowSeconds: Int = 0,
    @SerialName("relation_time_high_seconds") val relationTimeHighSeconds: Int = 0,
    @SerialName("time_low_seconds") val timeLowSeconds: Int = 0,
    @SerialName("time_high_seconds") val timeHighSeconds: Int = 0,
)

@Serializable
data class RestartRunRequest(
    val characters: List<String> = emptyList(),
    @SerialName("novel_name") val novelName: String = "",
    @SerialName("novel_content_base64") val novelContentBase64: String = "",
    @SerialName("max_sentences") val maxSentences: Int = 120,
    @SerialName("max_chars") val maxChars: Int = 50_000,
)

@Serializable
data class SuggestRedistillSegmentsRequest(
    val character: String,
    @SerialName("max_segments") val maxSegments: Int = 3,
)

@Serializable
data class RedistillSuggestionsDto(
    val character: String = "",
    @SerialName("source_name") val sourceName: String = "",
    @SerialName("weak_field_labels") val weakFieldLabels: List<String> = emptyList(),
    val segments: List<RedistillSegmentDto> = emptyList(),
)

@Serializable
data class RedistillSegmentDto(
    @SerialName("segment_id") val segmentId: String = "",
    val preview: String = "",
    @SerialName("full_text") val fullText: String = "",
    @SerialName("start_sentence") val startSentence: Int = 0,
    @SerialName("end_sentence") val endSentence: Int = 0,
    val score: Int = 0,
    @SerialName("estimated_field_labels") val estimatedFieldLabels: List<String> = emptyList(),
    val reason: String = "",
)

@Serializable
data class DeleteRunResponse(
    val status: String = "",
    @SerialName("novel_id") val novelId: String = "",
    @SerialName("deleted_run_count") val deletedRunCount: Int = 0,
    @SerialName("deleted_session_count") val deletedSessionCount: Int = 0,
    @SerialName("deleted_run_ids") val deletedRunIds: List<String> = emptyList(),
)

@Serializable
data class RelationDetailsDto(
    @SerialName("run_id") val runId: String = "",
    @SerialName("novel_id") val novelId: String = "",
    @SerialName("relation_count") val relationCount: Int = 0,
    @SerialName("conflict_count") val conflictCount: Int = 0,
    val conflicts: List<RelationConflictDto> = emptyList(),
    val items: List<RelationItemDto> = emptyList(),
)

@Serializable
data class RelationItemDto(
    @SerialName("pair_key") val pairKey: String = "",
    val characters: List<String> = emptyList(),
    val trust: Int = 0,
    val affection: Int = 0,
    val hostility: Int = 0,
    val ambiguity: Int = 3,
    @SerialName("relationship_type") val relationshipType: String = "",
    @SerialName("relation_change") val relationChange: String = "",
    @SerialName("conflict_point") val conflictPoint: String = "",
    @SerialName("typical_interaction") val typicalInteraction: String = "",
    @SerialName("evidence_lines") val evidenceLines: List<String> = emptyList(),
)

@Serializable
data class RelationConflictDto(
    @SerialName("pair_key") val pairKey: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class UpdateRelationDetailRequest(
    val trust: Int,
    val affection: Int,
    val hostility: Int,
    val ambiguity: Int,
    @SerialName("relationship_type") val relationshipType: String = "",
    @SerialName("relation_change") val relationChange: String = "",
    @SerialName("conflict_point") val conflictPoint: String = "",
    @SerialName("typical_interaction") val typicalInteraction: String = "",
)

@Serializable
data class ReusableCardsResponse(val items: List<ReusableCardDto> = emptyList())

@Serializable
data class ReusableCardDto(
    @SerialName("card_id") val cardId: String = "",
    val fields: JsonObject = JsonObject(emptyMap()),
    val preview: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class RecommendSceneCardsRequest(
    val mode: String = "observe",
    val participants: List<String> = emptyList(),
)

@Serializable
data class PersonaReviewDto(
    @SerialName("run_id") val runId: String = "",
    val character: String = "",
    @SerialName("editable_profile_path") val editableProfilePath: String = "",
    @SerialName("generated_profile_path") val generatedProfilePath: String = "",
    val fields: Map<String, String> = emptyMap(),
)

@Serializable
data class PersonaQualityReportDto(
    val character: String = "",
    val score: Int = 0,
    @SerialName("max_score") val maxScore: Int = 100,
    val grade: String = "",
    val verdict: String = "",
    val issues: List<PersonaIssueDto> = emptyList(),
)

@Serializable
data class PersonaIssueDto(
    val severity: String = "",
    val fields: List<String> = emptyList(),
    val message: String = "",
    val suggestion: String = "",
)

@Serializable
data class SuggestPersonaFieldRequest(val field: String)

@Serializable
data class SuggestPersonaFieldResponse(
    val field: String = "",
    val label: String = "",
    val status: String = "",
    val value: String = "",
    val message: String = "",
    val reason: String = "",
)

@Serializable
data class SessionsResponse(val items: List<DialogueSessionDto> = emptyList())

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
    val status: String = "ready",
    @SerialName("turns") val turns: List<JsonObject> = emptyList(),
    @SerialName("turn_count") val turnCount: Int = 0,
    @SerialName("current_turn_id") val currentTurnId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("last_entry_preview") val lastEntryPreview: String = "",
    val transcript: List<TranscriptItemDto> = emptyList(),
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
    @SerialName("story_recap") val storyRecap: StoryRecapDto? = null,
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
    val speaker: String = "",
    val message: String = "",
    @SerialName("inner_thought") val innerThought: String = "",
    val role: String = "character",
    @SerialName("turn_id") val turnId: String = "",
    val timestamp: String = "",
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
)

@Serializable
data class DialogueReplyRequest(
    val message: String,
    @SerialName("message_kind") val messageKind: String = "dialogue",
    @SerialName("suppress_transcript_message") val suppressTranscriptMessage: Boolean = false,
    @SerialName("include_inner_thoughts") val includeInnerThoughts: Boolean = false,
    @SerialName("include_model_reasoning") val includeModelReasoning: Boolean = false,
    @SerialName("operation_id") val operationId: String = "",
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
)

@Serializable
data class PluginSuggestionOptionDto(
    val label: String = "",
    val suggestion: String = "",
)

@Serializable
data class PluginChatActionResponse(
    val suggestion: String = "",
    val suggestions: List<PluginSuggestionOptionDto> = emptyList(),
    val notice: String = "",
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
data class WorldMemoryDto(
    val version: Int = 1,
    val facts: List<WorldFactDto> = emptyList(),
    val timeline: List<WorldTimelineItemDto> = emptyList(),
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class WorldFactDto(
    @SerialName("fact_id") val factId: String = "",
    val category: String = "event",
    val summary: String = "",
    val characters: List<String> = emptyList(),
    val location: String = "",
    @SerialName("time_hint") val timeHint: String = "",
    val source: String = "manual",
    @SerialName("source_session_id") val sourceSessionId: String = "",
    @SerialName("source_turn_id") val sourceTurnId: String = "",
    val locked: Boolean = false,
    val active: Boolean = true,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class WorldTimelineItemDto(
    @SerialName("timeline_id") val timelineId: String = "",
    val title: String = "",
    val participants: List<String> = emptyList(),
    @SerialName("event_types") val eventTypes: List<String> = emptyList(),
    val location: String = "",
    @SerialName("time_hint") val timeHint: String = "",
    @SerialName("consistency_status") val consistencyStatus: String = "pass",
    @SerialName("source_session_id") val sourceSessionId: String = "",
    @SerialName("source_turn_id") val sourceTurnId: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SaveWorldFactRequest(
    val category: String = "event",
    val summary: String,
    val characters: List<String> = emptyList(),
    val location: String = "",
    @SerialName("time_hint") val timeHint: String = "",
    val locked: Boolean = false,
    val active: Boolean = true,
)

data class ExportedRunPackage(
    val filename: String,
    val file: File,
    val byteCount: Long,
)

@Serializable
data class ChaptersResponse(val items: List<ChapterDto> = emptyList())

@Serializable
data class ChapterDto(
    @SerialName("chapter_id") val chapterId: String = "",
    val order: Int = 0,
    val title: String = "",
    val goal: String = "",
    val participants: List<String> = emptyList(),
    val content: String = "",
    @SerialName("source_session_id") val sourceSessionId: String = "",
    @SerialName("last_session_id") val lastSessionId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SaveChapterRequest(
    val title: String,
    val goal: String = "",
    val participants: List<String> = emptyList(),
    val content: String = "",
)

@Serializable
data class ArchiveDialogueChapterRequest(
    @SerialName("session_id") val sessionId: String,
    val title: String = "",
)

@Serializable
data class ReorderChapterRequest(
    @SerialName("target_order") val targetOrder: Int,
)

@Serializable
data class RewriteChapterRequest(
    val instruction: String = "",
    @SerialName("context_summary") val contextSummary: String = "",
)

data class ExportedChapterManuscript(
    val filename: String,
    val file: File,
)

@Serializable
data class SearchResultsResponse(val items: List<SearchResultDto> = emptyList())

@Serializable
data class SearchResultDto(
    val kind: String = "",
    @SerialName("chapter_id") val chapterId: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val character: String = "",
    val title: String = "",
    val preview: String = "",
)

@Serializable
data class AskBookQuestionRequest(val question: String)

@Serializable
data class AskBookResponseDto(
    val answer: String = "",
    val evidence: List<SearchResultDto> = emptyList(),
)
