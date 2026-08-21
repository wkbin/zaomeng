package top.wkbin.zaomeng.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.KtorOriginalKnowledgeClient
import top.wkbin.zaomeng.data.api.KtorPersonaClient
import top.wkbin.zaomeng.data.api.OriginalKnowledgeEntryDto
import top.wkbin.zaomeng.data.api.PersonaAvatarDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaRepairProposalDto
import top.wkbin.zaomeng.data.api.PersonaReviewDto
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.SuggestPersonaFieldResponse
import top.wkbin.zaomeng.data.api.WorldMemoryDto
import top.wkbin.zaomeng.domain.run.RunReviewGateway

class PersonaRepositoryImpl(
    private val ktorPersona: KtorPersonaClient,
) : PersonaRepository {
    private val avatarCache = mutableMapOf<String, ByteArray>()

    override suspend fun getPersona(runId: String, character: String): PersonaReviewDto = repositoryRequest {
        ktorPersona.getReview(runId, character)
    }

    override suspend fun savePersona(
        runId: String,
        character: String,
        completeFields: Map<String, String>,
        reviewNote: String,
    ): PersonaReviewDto = repositoryRequest {
        val payload = buildJsonObject {
            completeFields.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            put("review_source", JsonPrimitive("android"))
            put("review_note", JsonPrimitive(reviewNote))
        }
        ktorPersona.saveReview(runId, character, payload)
    }

    override suspend fun getPersonaQuality(runId: String, character: String): PersonaQualityReportDto = repositoryRequest {
        ktorPersona.getQuality(runId, character)
    }

    override suspend fun getPersonaRepairProposal(
        runId: String,
        character: String,
    ): PersonaRepairProposalDto = repositoryRequest {
        ktorPersona.getRepairProposal(runId, character)
    }

    override suspend fun deletePersona(runId: String, character: String): DeleteStatusDto = repositoryRequest {
        ktorPersona.delete(runId, character).also {
            avatarCache.keys.removeAll { key -> key.startsWith("$runId|$character|") }
        }
    }

    override suspend fun uploadPersonaAvatar(
        runId: String,
        character: String,
        bytes: ByteArray,
    ): PersonaAvatarDto = repositoryRequest {
        require(bytes.isNotEmpty()) { "头像文件为空。" }
        ktorPersona.uploadAvatar(runId, character, bytes).also { avatar ->
            avatarCache.keys.removeAll { it.startsWith("$runId|$character|") }
        }
    }

    override suspend fun getPersonaAvatar(
        runId: String,
        character: String,
        version: String,
    ): ByteArray? {
        if (version.isBlank()) return null
        val key = "$runId|$character|$version"
        avatarCache[key]?.let { return it }
        return repositoryRequest { ktorPersona.getAvatar(runId, character)?.also { avatarCache[key] = it } }
    }

    override suspend fun suggestPersonaField(
        runId: String,
        character: String,
        field: String,
    ): SuggestPersonaFieldResponse = repositoryRequest {
        ktorPersona.suggestField(runId, character, field)
    }

    override suspend fun getEvolutionProposal(
        runId: String,
        character: String,
        recap: top.wkbin.zaomeng.data.api.StoryRecapDto?,
    ): top.wkbin.zaomeng.data.api.PersonaEvolutionProposalDto = repositoryRequest {
        ktorPersona.getEvolutionProposal(runId, character, recap)
    }

    override suspend fun applyEvolution(
        runId: String,
        character: String,
        changes: List<top.wkbin.zaomeng.data.api.PersonaEvolutionChangeDto>,
    ): PersonaReviewDto = repositoryRequest {
        ktorPersona.applyEvolution(runId, character, changes)
    }
}

class OriginalKnowledgeRepositoryImpl(
    private val ktorOriginalKnowledge: KtorOriginalKnowledgeClient,
) : OriginalKnowledgeRepository {
    override suspend fun searchOriginalKnowledge(
        runId: String,
        query: String,
        participants: List<String>,
        pinnedOnly: Boolean,
    ): List<OriginalKnowledgeEntryDto> = repositoryRequest {
        ktorOriginalKnowledge.search(runId, query, participants, pinnedOnly).items
    }

    override suspend fun updateOriginalKnowledgeBoundary(
        runId: String,
        entryId: String,
        visibility: String,
        knowers: List<String>,
    ) = repositoryRequest {
        ktorOriginalKnowledge.updateBoundary(runId, entryId, visibility, knowers)
    }

    override suspend fun updateOriginalKnowledgePinned(
        runId: String,
        entryId: String,
        pinned: Boolean,
    ) = repositoryRequest {
        ktorOriginalKnowledge.updatePinned(runId, entryId, pinned)
    }

    override suspend fun rebuildOriginalKnowledge(runId: String) = repositoryRequest {
        ktorOriginalKnowledge.rebuild(runId)
    }
}

class RunReviewRepositoryImpl(
    private val relations: RelationsRepository,
    private val worldMemory: WorldMemoryRepository,
    private val persona: PersonaRepository,
) : RunReviewGateway {
    override suspend fun getRelations(runId: String): RelationDetailsDto = relations.getRelations(runId)

    override suspend fun getWorldMemory(runId: String): WorldMemoryDto = worldMemory.getWorldMemory(runId)

    override suspend fun getPersonaQuality(
        runId: String,
        character: String,
    ): PersonaQualityReportDto = persona.getPersonaQuality(runId, character)
}
