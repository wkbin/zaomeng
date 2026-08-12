package top.wkbin.zaomeng.data

import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import top.wkbin.zaomeng.data.api.ArchiveDialogueChapterRequest
import top.wkbin.zaomeng.data.api.AskBookResponseDto
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ExportedChapterManuscript
import top.wkbin.zaomeng.data.api.KtorCardsClient
import top.wkbin.zaomeng.data.api.KtorChapterClient
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.SaveChapterRequest
import top.wkbin.zaomeng.data.api.SearchResultDto

class ChapterRepositoryImpl(
    private val ktorChapters: KtorChapterClient,
) : ChapterRepository {
    override suspend fun listChapters(runId: String): List<ChapterDto> = repositoryRequest {
        ktorChapters.list(runId).items
    }

    override suspend fun searchRunContent(runId: String, query: String): List<SearchResultDto> = repositoryRequest {
        ktorChapters.search(runId, query)
    }

    override suspend fun askBookQuestion(runId: String, question: String): AskBookResponseDto = repositoryRequest {
        ktorChapters.ask(runId, question)
    }

    override suspend fun saveChapter(
        runId: String,
        chapterId: String,
        payload: SaveChapterRequest,
    ): ChapterDto = repositoryRequest {
        ktorChapters.save(runId, chapterId, payload)
    }

    override suspend fun archiveSessionAsChapter(
        runId: String,
        sessionId: String,
        title: String,
    ): ChapterDto = repositoryRequest {
        ktorChapters.archiveSession(runId, ArchiveDialogueChapterRequest(sessionId, title))
    }

    override suspend fun convertSessionAsNovel(
        runId: String,
        sessionId: String,
        title: String,
    ): ChapterDto = repositoryRequest {
        ktorChapters.convertSessionAsNovel(runId, ArchiveDialogueChapterRequest(sessionId, title))
    }

    override suspend fun deleteChapter(runId: String, chapterId: String): JsonObject = repositoryRequest {
        ktorChapters.delete(runId, chapterId)
    }

    override suspend fun continueChapter(runId: String, chapterId: String): DialogueSessionDto = repositoryRequest {
        ktorChapters.continueWriting(runId, chapterId)
    }

    override suspend fun syncChapterSession(runId: String, chapterId: String): ChapterDto = repositoryRequest {
        ktorChapters.syncSession(runId, chapterId)
    }

    override suspend fun reorderChapter(
        runId: String,
        chapterId: String,
        targetOrder: Int,
    ): List<ChapterDto> = repositoryRequest {
        ktorChapters.reorder(runId, chapterId, targetOrder)
    }

    override suspend fun exportChapters(
        runId: String,
        format: String,
        cacheDirectory: Path,
    ): ExportedChapterManuscript = repositoryRequest {
        val response = ktorChapters.export(runId, format)
        if (response.status.value !in 200..299) {
            throw ApiRequestException(errorDetail(response.bodyAsText(), response.status.value))
        }
        val normalizedFormat = if (format == "text") "text" else "markdown"
        val streamed = streamChannelToTempFile(
            response.bodyAsChannel(),
            cacheDirectory,
            prefix = "zaomeng-manuscript-",
            suffix = if (normalizedFormat == "text") ".txt" else ".md",
        )
        ExportedChapterManuscript(
            filename = "$runId-manuscript.${if (normalizedFormat == "text") "txt" else "md"}",
            file = streamed.file,
        )
    }
}

class CardRepositoryImpl(
    private val ktorCards: KtorCardsClient,
) : CardRepository {
    override suspend fun listReusableCards(kind: ReusableCardKind): List<ReusableCardDto> = repositoryRequest {
        ktorCards.list(kindToSegment(kind))
    }

    override suspend fun getReusableCard(
        kind: ReusableCardKind,
        cardId: String,
    ): ReusableCardDto = repositoryRequest {
        ktorCards.get(kindToSegment(kind), cardId)
    }

    override suspend fun saveReusableCard(
        kind: ReusableCardKind,
        cardId: String,
        fields: JsonObject,
    ): ReusableCardDto = repositoryRequest {
        ktorCards.save(kindToSegment(kind), cardId, fields)
    }

    override suspend fun deleteReusableCard(kind: ReusableCardKind, cardId: String) = repositoryRequest {
        ktorCards.delete(kindToSegment(kind), cardId)
    }

    override suspend fun generateReusableCard(kind: ReusableCardKind): ReusableCardDto = repositoryRequest {
        when (kind) {
            ReusableCardKind.Scene -> ktorCards.generate("scene")
            ReusableCardKind.Self -> ktorCards.generate("self")
            ReusableCardKind.Opening -> throw ApiRequestException("开场预设需要先选择人物和卡片后保存。")
        }
    }

    override suspend fun recommendSceneCard(
        mode: String,
        participants: List<String>,
    ): String = repositoryRequest {
        val response = ktorCards.recommend(mode, participants)
        response["recommended_card_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}
