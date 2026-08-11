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

@Serializable
data class SearchOriginalKnowledgeRequest(
    val query: String,
    val participants: List<String> = emptyList(),
    val limit: Int = 6,
    @SerialName("pinned_only") val pinnedOnly: Boolean = false,
)

@Serializable
data class UpdateOriginalKnowledgeBoundaryRequest(
    val visibility: String,
    val knowers: List<String> = emptyList(),
)

@Serializable
data class UpdateOriginalKnowledgePinnedRequest(val pinned: Boolean)

@Serializable
data class OriginalKnowledgeSearchResponse(
    val items: List<OriginalKnowledgeEntryDto> = emptyList(),
)

@Serializable
data class OriginalKnowledgeEntryDto(
    @SerialName("source_id") val sourceId: String = "",
    val title: String = "",
    val excerpt: String = "",
    val score: Double = 0.0,
    val visibility: String = "uncertain",
    val knowers: List<String> = emptyList(),
    val characters: List<String> = emptyList(),
    @SerialName("allowed_characters") val allowedCharacters: List<String> = emptyList(),
    @SerialName("denied_characters") val deniedCharacters: List<String> = emptyList(),
    @SerialName("boundary_source") val boundarySource: String = "automatic",
    @SerialName("epistemic_status") val epistemicStatus: String = "explicit_source",
    val pinned: Boolean = false,
    val location: OriginalKnowledgeLocationDto = OriginalKnowledgeLocationDto(),
)

