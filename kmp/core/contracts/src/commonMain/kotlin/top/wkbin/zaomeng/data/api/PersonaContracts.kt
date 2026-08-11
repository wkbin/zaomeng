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
    @SerialName("evidence_coverage") val evidenceCoverage: Int = 0,
    val confidence: Int = 0,
    @SerialName("pending_repair_count") val pendingRepairCount: Int = 0,
)

@Serializable
data class PersonaIssueDto(
    val severity: String = "",
    val fields: List<String> = emptyList(),
    val message: String = "",
    val suggestion: String = "",
)

@Serializable
data class PersonaRepairProposalDto(
    val character: String = "",
    val status: String = "not_available",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("evidence_coverage") val evidenceCoverage: Int = 0,
    val confidence: Int = 0,
    val issues: List<PersonaIssueDto> = emptyList(),
    val changes: List<PersonaRepairChangeDto> = emptyList(),
)

@Serializable
data class PersonaRepairChangeDto(
    val field: String = "",
    val before: String = "",
    val after: String = "",
    val reason: String = "",
    val confidence: Int = 0,
    val evidence: List<PersonaEvidenceDto> = emptyList(),
)

@Serializable
data class PersonaEvidenceDto(
    val id: String = "",
    val title: String = "",
    val excerpt: String = "",
    @SerialName("start_char") val startChar: Int = 0,
    @SerialName("end_char") val endChar: Int = 0,
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

