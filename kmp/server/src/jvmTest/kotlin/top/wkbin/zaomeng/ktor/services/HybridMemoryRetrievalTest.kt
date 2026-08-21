package top.wkbin.zaomeng.ktor.services

import okio.Path.Companion.toPath
import top.wkbin.zaomeng.db.DomainStore
import top.wkbin.zaomeng.db.RoomDocumentStore
import top.wkbin.zaomeng.db.ZaomengDatabase
import top.wkbin.zaomeng.db.buildZaomengDatabase
import top.wkbin.zaomeng.db.getDatabaseBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridMemoryRetrievalTest {

    private fun newDatabase(file: File): ZaomengDatabase =
        buildZaomengDatabase(getDatabaseBuilder(file.absolutePath.toPath()))

    private fun newStorage(dbFile: File, db: ZaomengDatabase): StorageService {
        val root = dbFile.absolutePath.toPath()
        val documentStore = RoomDocumentStore(db.documentDao())
        val domain = DomainStore(root, db.domainDao(), documentStore)
        return StorageService(root, documentStore, domain)
    }

    private fun tempDbFile(): File = File.createTempFile("zaomeng-test-hybrid-", ".db").apply { deleteOnExit() }

    @Test
    fun `LocalSemanticVector computes embedding and measures cosine similarity`() {
        val text1 = "宝玉雨夜前往潇湘馆探望黛玉"
        val text2 = "宝玉冒雨探视病中的林黛玉"
        val textUnrelated = "量子计算机超导芯片制程工艺研究报告"

        val simSelf = LocalSemanticVector.similarity(text1, text1)
        assertEquals(1.0f, simSelf, 0.001f)

        val simRelated = LocalSemanticVector.similarity(text1, text2)
        val simUnrelated = LocalSemanticVector.similarity(text1, textUnrelated)

        assertTrue(simRelated > 0.05f, "Related texts should have positive similarity: $simRelated")
        assertEquals(0.0f, simUnrelated, 0.001f)
        assertTrue(simRelated > simUnrelated, "Related similarity ($simRelated) should be greater than unrelated ($simUnrelated)")
    }

    @Test
    fun `HybridMemoryRetrievalStrategy balances lexical and semantic scores`() {
        val strategy = HybridMemoryRetrievalStrategy(lexicalWeight = 0.6f, semanticWeight = 0.4f)

        val query = "黛玉因身世心绪低落"
        val exactMatchMemory = "黛玉因身世心绪低落，独自垂泪"
        val semanticMatchMemory = "林姑娘感怀身世飘零，常自伤感叹息"
        val unrelatedMemory = "王熙凤在协理宁国府时严格核查账目银两"

        val exactScore = strategy.score(query, exactMatchMemory)
        val semanticScore = strategy.score(query, semanticMatchMemory)
        val unrelatedScore = strategy.score(query, unrelatedMemory)

        assertTrue(exactScore > 0f, "Exact match score should be positive")
        assertTrue(semanticScore > 0f, "Semantic paraphrase score should be positive")
        assertTrue(exactScore > semanticScore, "Exact match should score higher than pure paraphrase")
        assertTrue(semanticScore > unrelatedScore, "Paraphrase ($semanticScore) should score higher than unrelated ($unrelatedScore)")
    }

    @Test
    fun `LongTermMemoryService searches with hybrid strategy end-to-end`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val storage = newStorage(dbFile, db)
        val memoryService = LongTermMemoryService(storage, retrievalStrategy = HybridMemoryRetrievalStrategy())

        val runId = "run-hybrid-test"
        val sessionId = "session-hybrid-test"
        val sessionDir = storage.getDialogueSessionManifestFile(runId, sessionId).parent!!
        storage.mkdirs(sessionDir)

        memoryService.appendTurn(
            runId = runId,
            sessionId = sessionId,
            turnId = "turn-1",
            message = "你今日去过潇湘馆了？",
            responses = listOf(
                mapOf("speaker" to "贾宝玉", "message" to "昨夜冒雨去探望林妹妹，送了旧帕，她心结稍解。"),
            ),
        )

        memoryService.appendTurn(
            runId = runId,
            sessionId = sessionId,
            turnId = "turn-2",
            message = "凤姐那边账目可清了？",
            responses = listOf(
                mapOf("speaker" to "平儿", "message" to "大奶奶正在对账，库房银子都查得清楚。"),
            ),
        )

        // 查询与第一轮相关的记忆（同义表达）
        val results = memoryService.search(
            runId = runId,
            sessionId = sessionId,
            query = "昨夜去探望林妹妹赠帕之事",
            limit = 2,
        )

        assertTrue(results.isNotEmpty(), "Search should recall relevant turns")
        val topHit = results.first()
        assertEquals("贾宝玉", topHit["speaker"])
        assertTrue(topHit["text"].toString().contains("旧帕") || topHit["text"].toString().contains("潇湘馆"))
    }
}
