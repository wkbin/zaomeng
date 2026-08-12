package top.wkbin.zaomeng.data

import okio.Path
import top.wkbin.zaomeng.data.api.CrossoverParticipantRequest
import top.wkbin.zaomeng.data.api.DeleteRunResponse
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.data.api.LibraryPackageImportDto
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.RelationItemDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import top.wkbin.zaomeng.data.api.SaveWorldFactRequest
import top.wkbin.zaomeng.data.api.WorldFactDto
import top.wkbin.zaomeng.data.api.WorldMemoryDto

interface RunRepository {
    suspend fun listRuns(): List<RunManifestDto>

    suspend fun createNovel(
        filename: String,
        bytes: ByteArray,
        characters: List<String>,
        maxSentences: Int,
        maxChars: Int,
        autoRun: Boolean = true,
    ): RunManifestDto

    suspend fun estimateSampling(
        charCount: Int,
        sentenceCount: Int,
        characterCount: Int,
        maxSentences: Int,
        maxChars: Int,
    ): SamplingPlanDto

    suspend fun importPackage(
        filename: String,
        bytes: ByteArray,
        libraryPackage: LibraryPackageImportDto? = null,
    ): RunManifestDto

    suspend fun saveImportDefaults(characters: String, autoDistill: Boolean = true)
    suspend fun getRun(runId: String): RunManifestDto
    suspend fun deleteRun(runId: String): DeleteRunResponse
    suspend fun refreshRun(runId: String): RunManifestDto
    suspend fun stopRun(runId: String): RunManifestDto
    suspend fun redistill(runId: String, characters: List<String>): RunManifestDto

    suspend fun createCrossoverSpace(
        title: String,
        worldSetting: String,
        participants: List<CrossoverParticipantRequest>,
    ): RunManifestDto

    suspend fun resumeDistill(runId: String): RunManifestDto

    suspend fun redistill(
        runId: String,
        characters: List<String>,
        novelName: String,
        novelBytes: ByteArray?,
        maxSentences: Int,
        maxChars: Int,
    ): RunManifestDto

    suspend fun suggestRedistillSegments(
        runId: String,
        character: String,
        maxSegments: Int,
    ): RedistillSuggestionsDto

    suspend fun exportRun(
        runId: String,
        cacheDirectory: Path,
        includeDialogue: Boolean = true,
    ): ExportedRunPackage
}

interface WorldMemoryRepository {
    suspend fun getWorldMemory(runId: String): WorldMemoryDto
    suspend fun saveWorldFact(runId: String, factId: String, requestBody: SaveWorldFactRequest): WorldFactDto
    suspend fun deleteWorldFact(runId: String, factId: String): DeleteStatusDto
}

interface RelationsRepository {
    suspend fun getRelations(runId: String): RelationDetailsDto
    suspend fun updateRelation(runId: String, relation: RelationItemDto): RelationDetailsDto
}
