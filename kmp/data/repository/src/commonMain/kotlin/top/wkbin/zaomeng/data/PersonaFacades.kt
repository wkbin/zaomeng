package top.wkbin.zaomeng.data

import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.OriginalKnowledgeEntryDto
import top.wkbin.zaomeng.data.api.PersonaAvatarDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaRepairProposalDto
import top.wkbin.zaomeng.data.api.PersonaReviewDto
import top.wkbin.zaomeng.data.api.SuggestPersonaFieldResponse

interface PersonaRepository {
    suspend fun getPersona(runId: String, character: String): PersonaReviewDto

    suspend fun savePersona(
        runId: String,
        character: String,
        completeFields: Map<String, String>,
        reviewNote: String,
    ): PersonaReviewDto

    suspend fun getPersonaQuality(runId: String, character: String): PersonaQualityReportDto
    suspend fun getPersonaRepairProposal(runId: String, character: String): PersonaRepairProposalDto
    suspend fun deletePersona(runId: String, character: String): DeleteStatusDto
    suspend fun uploadPersonaAvatar(runId: String, character: String, bytes: ByteArray): PersonaAvatarDto
    suspend fun getPersonaAvatar(runId: String, character: String, version: String): ByteArray?
    suspend fun suggestPersonaField(runId: String, character: String, field: String): SuggestPersonaFieldResponse
}

interface OriginalKnowledgeRepository {
    suspend fun searchOriginalKnowledge(
        runId: String,
        query: String,
        participants: List<String>,
        pinnedOnly: Boolean,
    ): List<OriginalKnowledgeEntryDto>

    suspend fun updateOriginalKnowledgeBoundary(
        runId: String,
        entryId: String,
        visibility: String,
        knowers: List<String>,
    )

    suspend fun updateOriginalKnowledgePinned(runId: String, entryId: String, pinned: Boolean)
    suspend fun rebuildOriginalKnowledge(runId: String)
}
