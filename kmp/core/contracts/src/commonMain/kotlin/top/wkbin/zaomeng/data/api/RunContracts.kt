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
) {
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

