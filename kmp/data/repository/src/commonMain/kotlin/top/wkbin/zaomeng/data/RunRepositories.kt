package top.wkbin.zaomeng.data

import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import top.wkbin.zaomeng.client.platform.clientBase64Encode
import top.wkbin.zaomeng.data.api.CreateCrossoverSpaceRequest
import top.wkbin.zaomeng.data.api.CreateRunRequest
import top.wkbin.zaomeng.data.api.CrossoverParticipantRequest
import top.wkbin.zaomeng.data.api.DeleteRunResponse
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.EstimateSamplingRequest
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.KtorRelationsClient
import top.wkbin.zaomeng.data.api.KtorRunManagementClient
import top.wkbin.zaomeng.data.api.KtorRunOpsClient
import top.wkbin.zaomeng.data.api.KtorRunsClient
import top.wkbin.zaomeng.data.api.KtorWorldMemoryClient
import top.wkbin.zaomeng.data.api.LibraryPackageImportDto
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.RelationItemDto
import top.wkbin.zaomeng.data.api.RestartRunRequest
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import top.wkbin.zaomeng.data.api.SaveWorldFactRequest
import top.wkbin.zaomeng.data.api.SuggestRedistillSegmentsRequest
import top.wkbin.zaomeng.data.api.UpdateRelationDetailRequest
import top.wkbin.zaomeng.data.api.WorldFactDto
import top.wkbin.zaomeng.data.api.WorldMemoryDto
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.domain.distill.DistillPlanningGateway

class RunRepositoryImpl(
    private val ktorRuns: KtorRunsClient,
    private val ktorRunManagement: KtorRunManagementClient,
    private val ktorRunOps: KtorRunOpsClient,
    private val appPreferences: AppPreferencesRepository,
) : RunRepository, DistillPlanningGateway {
    override suspend fun listRuns(): List<RunManifestDto> = repositoryRequest {
        ktorRuns.list().items
    }

    override suspend fun createNovel(
        filename: String,
        bytes: ByteArray,
        characters: List<String>,
        maxSentences: Int,
        maxChars: Int,
        autoRun: Boolean,
    ): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            CreateRunRequest(
                novelName = filename,
                novelContentBase64 = clientBase64Encode(bytes),
                characters = characters,
                maxSentences = maxSentences,
                maxChars = maxChars,
                autoRun = autoRun,
                deferRun = !autoRun,
            )
        }
        return repositoryRequest {
            val run = ktorRunManagement.create(payload)
            appPreferences.rememberRun(run.runId)
            run
        }
    }

    override suspend fun estimateSampling(
        charCount: Int,
        sentenceCount: Int,
        characterCount: Int,
        maxSentences: Int,
        maxChars: Int,
    ): SamplingPlanDto = repositoryRequest {
        ktorRunOps.estimateSampling(
            EstimateSamplingRequest(
                charCount = charCount,
                sentenceCount = sentenceCount,
                characterCount = characterCount,
                maxSentences = maxSentences,
                maxChars = maxChars,
            ),
        )
    }

    override suspend fun importPackage(
        filename: String,
        bytes: ByteArray,
        libraryPackage: LibraryPackageImportDto?,
    ): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            ImportRunPackageRequest(
                filename = filename,
                contentBase64 = clientBase64Encode(bytes),
                libraryPackage = libraryPackage,
            )
        }
        return repositoryRequest {
            val run = ktorRunManagement.import(payload)
            appPreferences.rememberRun(run.runId)
            run
        }
    }

    override suspend fun saveImportDefaults(characters: String, autoDistill: Boolean) {
        appPreferences.saveImportDefaults(characters, autoDistill)
    }

    override suspend fun getRun(runId: String): RunManifestDto = repositoryRequest {
        ktorRunManagement.get(runId)
    }

    override suspend fun deleteRun(runId: String): DeleteRunResponse = repositoryRequest {
        ktorRunManagement.delete(runId).also {
            appPreferences.forgetRun(runId)
        }
    }

    override suspend fun refreshRun(runId: String): RunManifestDto = repositoryRequest {
        ktorRunOps.refreshRun(runId)
    }

    override suspend fun stopRun(runId: String): RunManifestDto = repositoryRequest {
        ktorRunManagement.stop(runId)
    }

    override suspend fun redistill(runId: String, characters: List<String>): RunManifestDto = repositoryRequest {
        ktorRunOps.redistill(runId, RestartRunRequest(characters = characters))
    }

    override suspend fun createCrossoverSpace(
        title: String,
        worldSetting: String,
        participants: List<CrossoverParticipantRequest>,
    ): RunManifestDto = repositoryRequest {
        val run = ktorRunOps.createCrossoverSpace(CreateCrossoverSpaceRequest(title, worldSetting, participants))
        appPreferences.rememberRun(run.runId)
        run
    }

    override suspend fun resumeDistill(runId: String): RunManifestDto = repositoryRequest {
        ktorRunOps.resumeDistill(runId)
    }

    override suspend fun redistill(
        runId: String,
        characters: List<String>,
        novelName: String,
        novelBytes: ByteArray?,
        maxSentences: Int,
        maxChars: Int,
    ): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            RestartRunRequest(
                characters = characters,
                novelName = novelName.takeIf { novelBytes != null }.orEmpty(),
                novelContentBase64 = novelBytes
                    ?.let { clientBase64Encode(it) }
                    .orEmpty(),
                maxSentences = maxSentences,
                maxChars = maxChars,
            )
        }
        return repositoryRequest { ktorRunOps.redistill(runId, payload) }
    }

    override suspend fun suggestRedistillSegments(
        runId: String,
        character: String,
        maxSegments: Int,
    ): RedistillSuggestionsDto = repositoryRequest {
        ktorRunOps.suggestRedistill(
            runId,
            SuggestRedistillSegmentsRequest(character = character, maxSegments = maxSegments),
        )
    }

    override suspend fun exportRun(
        runId: String,
        cacheDirectory: Path,
        includeDialogue: Boolean,
    ): ExportedRunPackage = repositoryRequest {
        val response = ktorRunOps.exportRun(runId, includeDialogue)
        if (response.status.value !in 200..299) {
            throw ApiRequestException(errorDetail(response.bodyAsText(), response.status.value))
        }
        val disposition = response.headers["Content-Disposition"].orEmpty()
        val filename = parseFilename(disposition).ifBlank { "$runId.zaomeng-run.zip" }
        val streamed = streamChannelToTempFile(response.bodyAsChannel(), cacheDirectory)
        ExportedRunPackage(
            filename = filename,
            file = streamed.file,
            byteCount = streamed.byteCount,
        )
    }
}

class WorldMemoryRepositoryImpl(
    private val ktorWorldMemory: KtorWorldMemoryClient,
) : WorldMemoryRepository {
    override suspend fun getWorldMemory(runId: String): WorldMemoryDto = repositoryRequest {
        ktorWorldMemory.get(runId)
    }

    override suspend fun saveWorldFact(
        runId: String,
        factId: String,
        requestBody: SaveWorldFactRequest,
    ): WorldFactDto = repositoryRequest {
        ktorWorldMemory.save(runId, factId, requestBody)
    }

    override suspend fun deleteWorldFact(runId: String, factId: String): DeleteStatusDto = repositoryRequest {
        ktorWorldMemory.delete(runId, factId)
    }
}

class RelationsRepositoryImpl(
    private val ktorRelations: KtorRelationsClient,
) : RelationsRepository {
    override suspend fun getRelations(runId: String): RelationDetailsDto = repositoryRequest {
        ktorRelations.get(runId)
    }

    override suspend fun updateRelation(
        runId: String,
        relation: RelationItemDto,
    ): RelationDetailsDto = repositoryRequest {
        ktorRelations.update(
            runId,
            relation.pairKey,
            UpdateRelationDetailRequest(
                trust = relation.trust.coerceIn(0, 10),
                affection = relation.affection.coerceIn(0, 10),
                hostility = relation.hostility.coerceIn(0, 10),
                ambiguity = relation.ambiguity.coerceIn(0, 10),
                relationshipType = relation.relationshipType,
                relationChange = relation.relationChange,
                conflictPoint = relation.conflictPoint,
                typicalInteraction = relation.typicalInteraction,
            ),
        )
    }
}
