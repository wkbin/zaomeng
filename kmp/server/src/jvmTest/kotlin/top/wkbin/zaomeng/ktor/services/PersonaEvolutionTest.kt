package top.wkbin.zaomeng.ktor.services

import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.PersonaEvolutionChangeDto
import top.wkbin.zaomeng.data.api.StoryCharacterArcDto
import top.wkbin.zaomeng.data.api.StoryMetricChangeDto
import top.wkbin.zaomeng.data.api.StoryQuoteDto
import top.wkbin.zaomeng.data.api.StoryRecapDto
import top.wkbin.zaomeng.data.api.StoryRelationChangeDto
import top.wkbin.zaomeng.db.DomainStore
import top.wkbin.zaomeng.db.RoomDocumentStore
import top.wkbin.zaomeng.db.ZaomengDatabase
import top.wkbin.zaomeng.db.buildZaomengDatabase
import top.wkbin.zaomeng.db.getDatabaseBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonaEvolutionTest {

    private fun newDatabase(file: File): ZaomengDatabase =
        buildZaomengDatabase(getDatabaseBuilder(file.absolutePath.toPath()))

    private fun newStorage(dbFile: File, db: ZaomengDatabase): StorageService {
        val root = dbFile.absolutePath.toPath()
        val documentStore = RoomDocumentStore(db.documentDao())
        val domain = DomainStore(root, db.domainDao(), documentStore)
        return StorageService(root, documentStore, domain)
    }

    private fun tempDbFile(): File = File.createTempFile("zaomeng-test-evolve-", ".db").apply { deleteOnExit() }

    @Test
    fun `generateEvolutionProposal extracts character arcs relations and quotes`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val storage = newStorage(dbFile, db)
        val personaService = PersonaService(storage, relations = RelationsService(storage))

        val runDir = storage.getRunDirectory("run-evolve-1")
        val daiyuDir = runDir / "artifacts/characters/novel-1/林黛玉"
        storage.mkdirs(daiyuDir)
        storage.writeRunManifest(
            "run-evolve-1",
            kotlinx.serialization.json.buildJsonObject {
                put("run_id", kotlinx.serialization.json.JsonPrimitive("run-evolve-1"))
            },
        )
        storage.writeTextAtomically(
            daiyuDir / "PROFILE.md",
            """
            ---
            name: 林黛玉
            core_identity: 寄人篱下的绛珠仙草
            inner_conflict: 多愁善感，常忧身世漂泊
            key_bonds: 与宝玉：青梅竹马
            typical_lines: 尔今死去侬收葬，未卜侬身何日丧
            ---
            
            正文描述...
            """.trimIndent(),
        )

        val recap = StoryRecapDto(
            title = "秋夜深谈",
            participants = listOf("林黛玉", "贾宝玉"),
            characterArcs = listOf(
                StoryCharacterArcDto(
                    name = "林黛玉",
                    growthSummary = "历经风雨探病，解开对宝玉的心结，渐生坦然信任之感",
                ),
            ),
            relations = listOf(
                StoryRelationChangeDto(
                    pairKey = "贾宝玉:林黛玉",
                    characters = listOf("贾宝玉", "林黛玉"),
                    changes = listOf(StoryMetricChangeDto(metric = "trust", label = "信任度", delta = 3)),
                    reason = "潇湘夜雨对坐赠帕，消融猜忌",
                ),
            ),
            quotes = listOf(
                StoryQuoteDto(speaker = "林黛玉", message = "我为你也算操碎了心，你可知道？"),
            ),
        )

        val proposal = personaService.generateEvolutionProposal("run-evolve-1", "林黛玉", recap)
        assertEquals("林黛玉", proposal.character)
        assertEquals("available", proposal.status)
        assertTrue(proposal.changes.isNotEmpty())

        val conflictChange = proposal.changes.find { it.field == "inner_conflict" }
        assertTrue(conflictChange != null)
        assertTrue(conflictChange.proposedValue.contains("解开对宝玉的心结"))

        val bondChange = proposal.changes.find { it.field == "key_bonds" }
        assertTrue(bondChange != null)
        assertTrue(bondChange.proposedValue.contains("潇湘夜雨对坐赠帕"))

        val quoteChange = proposal.changes.find { it.field == "typical_lines" }
        assertTrue(quoteChange != null)
        assertTrue(quoteChange.proposedValue.contains("我为你也算操碎了心"))
    }

    @Test
    fun `applyEvolution atomically updates profile and preserves body`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val storage = newStorage(dbFile, db)
        val personaService = PersonaService(storage, relations = RelationsService(storage))

        val runDir = storage.getRunDirectory("run-evolve-2")
        val baoyuDir = runDir / "artifacts/characters/novel-1/贾宝玉"
        storage.mkdirs(baoyuDir)
        storage.writeRunManifest(
            "run-evolve-2",
            kotlinx.serialization.json.buildJsonObject {
                put("run_id", kotlinx.serialization.json.JsonPrimitive("run-evolve-2"))
            },
        )
        storage.writeTextAtomically(
            baoyuDir / "PROFILE.md",
            """
            ---
            name: 贾宝玉
            core_identity: 荣国府通灵宝玉
            key_bonds: 与黛玉：青梅竹马
            ---
            
            这是宝玉的详细正文生平记录。
            """.trimIndent(),
        )

        val changes = listOf(
            PersonaEvolutionChangeDto(
                field = "key_bonds",
                fieldLabel = "关键羁绊",
                currentValue = "与黛玉：青梅竹马",
                proposedValue = "与黛玉：青梅竹马；与宝钗：相敬如宾",
                reason = "金玉良缘互动深化",
            ),
            PersonaEvolutionChangeDto(
                field = "typical_lines",
                fieldLabel = "代表台词",
                currentValue = "",
                proposedValue = "林妹妹说的极是",
                reason = "对白提炼",
            ),
        )

        val review = personaService.applyEvolution("run-evolve-2", "贾宝玉", changes)
        assertEquals("与黛玉：青梅竹马；与宝钗：相敬如宾", review.fields["key_bonds"])
        assertEquals("林妹妹说的极是", review.fields["typical_lines"])

        val updatedProfileContent = storage.readText(baoyuDir / "PROFILE.md")
        assertTrue(updatedProfileContent.contains("与宝钗：相敬如宾"))
        assertTrue(updatedProfileContent.contains("林妹妹说的极是"))
        assertTrue(updatedProfileContent.contains("这是宝玉的详细正文生平记录。"))
    }
}
