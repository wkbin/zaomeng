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
data class RunsResponse(val items: List<RunManifestDto> = emptyList())

@Serializable
data class RunManifestDto(
    @SerialName("run_id") val runId: String = "",
    @SerialName("novel_id") val novelId: String = "",
    @SerialName("novel_name") val novelName: String = "",
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
    val entrypoint: String = "",
) {
    val isSillyTavernCharacterCard: Boolean get() = entrypoint == "sillytavern"

    val title: String
        get() = novelSources.lastOrNull()?.sourceName
            ?.substringBeforeLast('.')
            ?.takeIf(String::isNotBlank)
            ?: novelName.ifBlank { novelId.ifBlank { runId } }

    val isTerminal: Boolean
        get() = status in setOf("ready", "failed", "stopped", "draft")

    val isInterrupted: Boolean
        get() = status == "stopped" &&
            progress.stage == "interrupted" &&
            control.interruptionReason in setOf("android_process_ended", "process_ended")

    val availableCharacters: List<String>
        get() {
            val indexed = artifactIndex.characters.map(PersonaIndexDto::name).filter(String::isNotBlank)
            if (indexed.isNotEmpty()) return indexed
            return betaFeature
                ?.takeIf { it.kind == "cross_book_crossover" }
                ?.sourceSnapshots
                ?.map(CrossoverSourceDto::character)
                ?.filter(String::isNotBlank)
                ?.distinct()
                .orEmpty()
        }
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
    @SerialName("target_run_id") val targetRunId: String? = null,
    @SerialName("expected_world_book_revision") val expectedWorldBookRevision: String? = null,
)

@Serializable
data class SillyTavernPreviewRequest(
    val filename: String,
    @SerialName("content_base64") val contentBase64: String,
    @SerialName("world_book") val worldBook: Boolean = false,
    @SerialName("target_run_id") val targetRunId: String? = null,
    @SerialName("test_text") val testText: String? = null,
)

@Serializable
data class SillyTavernPreviewDto(
    @SerialName("character_name") val characterName: String = "",
    val format: String = "",
    @SerialName("has_avatar") val hasAvatar: Boolean = false,
    val greeting: String = "",
    @SerialName("alternate_greeting_count") val alternateGreetingCount: Int = 0,
    @SerialName("mapped_fields") val mappedFields: List<String> = emptyList(),
    @SerialName("book_name") val bookName: String = "",
    @SerialName("entry_count") val entryCount: Int = 0,
    @SerialName("previous_book_name") val previousBookName: String? = null,
    @SerialName("previous_entry_count") val previousEntryCount: Int? = null,
    @SerialName("unsupported_rules") val unsupportedRules: List<String> = emptyList(),
    @SerialName("tested_entries") val testedEntries: List<WorldBookMatchDto>? = null,
    @SerialName("injected_count") val injectedCount: Int = 0,
    @SerialName("previous_book_revision") val previousBookRevision: String? = null,
)

@Serializable
enum class WorldBookInjectionPosition(val sillyTavernId: Int) {
    @SerialName("before_char") BeforeCharacter(0),
    @SerialName("after_char") AfterCharacter(1),
    @SerialName("before_author_note") BeforeAuthorNote(2),
    @SerialName("after_author_note") AfterAuthorNote(3),
    @SerialName("before_examples") BeforeExamples(5),
    @SerialName("after_examples") AfterExamples(6),
    @SerialName("at_depth") AtDepth(4),
    @SerialName("outlet") Outlet(7);

    val isAuthorNote: Boolean get() = this == BeforeAuthorNote || this == AfterAuthorNote
}

@Serializable
enum class WorldBookInjectionRole(val chatRole: String, val sillyTavernId: Int) {
    @SerialName("system") System("system", 0),
    @SerialName("user") User("user", 1),
    @SerialName("assistant") Assistant("assistant", 2),
}

/** Total raw world-book text per turn, retaining the previous maximum of 18 × 240. */
const val WORLD_BOOK_CONTENT_CHAR_LIMIT = 4_320

@Serializable
data class WorldBookMatchDto(
    val title: String,
    val excerpt: String,
    val status: String,
    val keywords: List<String> = emptyList(),
    val source: String = "",
    @SerialName("trigger_probability") val triggerProbability: Double? = null,
    @SerialName("recursion_depth") val recursionDepth: Int = 0,
    val position: WorldBookInjectionPosition? = null,
    @SerialName("insertion_order") val insertionOrder: Int = 0,
    @SerialName("injection_depth") val injectionDepth: Int? = null,
    @SerialName("injection_role") val injectionRole: WorldBookInjectionRole? = null,
    @SerialName("outlet_name") val outletName: String? = null,
    @SerialName("sticky_active") val stickyActive: Boolean = false,
    @SerialName("ignore_budget") val ignoreBudget: Boolean = false,
    @SerialName("forced_activation") val forcedActivation: Boolean = false,
)

@Serializable
data class ManagedWorldBooksDto(val books: List<ManagedWorldBookDto> = emptyList())

@Serializable
data class ManagedWorldBookDto(
    val source: String,
    val name: String,
    val revision: String,
    val entries: List<ManagedWorldBookEntryDto>,
    @SerialName("recursion_enabled") val recursionEnabled: Boolean = false,
)

@Serializable
data class ManagedWorldBookEntryDto(
    val index: Int,
    val title: String,
    val content: String,
    val enabled: Boolean,
    val constant: Boolean,
    val keywords: List<String>,
    @SerialName("secondary_keywords") val secondaryKeywords: List<String>,
    val comment: String = "",
    val position: WorldBookInjectionPosition? = null,
    @SerialName("injection_depth") val injectionDepth: Int? = null,
    @SerialName("injection_role") val injectionRole: WorldBookInjectionRole? = null,
    @SerialName("outlet_name") val outletName: String? = null,
    @SerialName("delay") val activationDelay: Int = 0,
    val sticky: Int = 0,
    val cooldown: Int = 0,
    val group: WorldBookGroupSettings? = null,
    val triggers: List<WorldBookGenerationType> = emptyList(),
    @SerialName("has_unknown_triggers") val hasUnknownTriggers: Boolean = false,
    val matching: WorldBookKeywordMatching = WorldBookKeywordMatching(),
    @SerialName("ignore_budget") val ignoreBudget: Boolean = false,
)

@Serializable
data class WorldBookKeywordMatching(
    @SerialName("scan_depth") val scanDepth: Int? = null,
    @SerialName("case_sensitive") val caseSensitive: Boolean? = null,
    @SerialName("whole_words") val wholeWords: Boolean? = null,
    @SerialName("use_secondary") val useSecondary: Boolean = true,
    @SerialName("secondary_logic") val secondaryLogic: WorldBookSecondaryLogic = WorldBookSecondaryLogic.AndAny,
    val sources: List<WorldBookScanSource> = emptyList(),
) {
    companion object {
        const val MAX_SCAN_DEPTH = 32
    }
}

@Serializable
enum class WorldBookScanSource(val nativeField: String, val embeddedField: String) {
    @SerialName("persona_description") PersonaDescription("matchPersonaDescription", "match_persona_description"),
    @SerialName("character_description") CharacterDescription("matchCharacterDescription", "match_character_description"),
    @SerialName("character_personality") CharacterPersonality("matchCharacterPersonality", "match_character_personality"),
    @SerialName("character_depth_prompt") CharacterDepthPrompt("matchCharacterDepthPrompt", "match_character_depth_prompt"),
    @SerialName("scenario") Scenario("matchScenario", "match_scenario"),
    @SerialName("creator_notes") CreatorNotes("matchCreatorNotes", "match_creator_notes"),
}

@Serializable
enum class WorldBookSecondaryLogic(val sillyTavernId: Int) {
    @SerialName("and_any") AndAny(0),
    @SerialName("not_all") NotAll(1),
    @SerialName("not_any") NotAny(2),
    @SerialName("and_all") AndAll(3),
}

@Serializable
enum class WorldBookGenerationType(val sillyTavernId: String) {
    @SerialName("normal") Normal("normal"),
    @SerialName("continue") Continue("continue"),
    @SerialName("regenerate") Regenerate("regenerate"),
    @SerialName("impersonate") Impersonate("impersonate"),
    @SerialName("quiet") Quiet("quiet"),
    @SerialName("swipe") Swipe("swipe"),
}

@Serializable
data class WorldBookGroupSettings(
    val names: String = "",
    val priority: Boolean = false,
    val weight: Int = 100,
    val scoring: Boolean? = null,
)

@Serializable
data class SetWorldBookEntryRequest(val revision: String, val enabled: Boolean)

@Serializable
data class WorldBookEntryPlacement(
    val position: WorldBookInjectionPosition,
    val depth: Int? = null,
    val role: WorldBookInjectionRole? = null,
    @SerialName("outlet_name") val outletName: String? = null,
)

@Serializable
data class CreateWorldBookEntryRequest(
    val revision: String,
    val content: String,
    val keywords: List<String>,
    val comment: String = "",
    @SerialName("secondary_keywords") val secondaryKeywords: List<String> = emptyList(),
    val constant: Boolean = false,
    val placement: WorldBookEntryPlacement? = null,
    @SerialName("delay") val activationDelay: Int? = null,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val group: WorldBookGroupSettings? = null,
    val triggers: List<WorldBookGenerationType>? = null,
    val matching: WorldBookKeywordMatching? = null,
    @SerialName("ignore_budget") val ignoreBudget: Boolean? = null,
)

@Serializable
data class EditWorldBookEntryRequest(
    val revision: String,
    val comment: String? = null,
    val content: String? = null,
    val keywords: List<String>? = null,
    @SerialName("secondary_keywords") val secondaryKeywords: List<String>? = null,
    val constant: Boolean? = null,
    val placement: WorldBookEntryPlacement? = null,
    @SerialName("delay") val activationDelay: Int? = null,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val group: WorldBookGroupSettings? = null,
    val triggers: List<WorldBookGenerationType>? = null,
    val matching: WorldBookKeywordMatching? = null,
    @SerialName("ignore_budget") val ignoreBudget: Boolean? = null,
)

@Serializable
data class SetWorldBookRecursionRequest(val revision: String, val enabled: Boolean)

@Serializable
data class TestWorldBooksRequest(
    val text: String,
    @SerialName("message_count") val messageCount: Int = 1,
    @SerialName("generation_type") val generationType: WorldBookGenerationType = WorldBookGenerationType.Normal,
    val history: List<String> = emptyList(),
    val context: WorldBookTestContext = WorldBookTestContext(),
)

@Serializable
data class WorldBookTestContext(
    @SerialName("persona_description") val personaDescription: String = "",
    val scenario: String = "",
)

@Serializable
data class WorldBookTestDto(
    val entries: List<WorldBookMatchDto>,
    @SerialName("entry_count") val entryCount: Int,
    @SerialName("injected_count") val injectedCount: Int,
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
