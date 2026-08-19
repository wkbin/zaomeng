package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

class CrossoverSpaceTest {
    @Test
    fun `create crossover keeps copied personas in artifact index`() {
        val dbFile = File.createTempFile("zaomeng-crossover-", ".db").apply { deleteOnExit() }
        val db = buildDatabase(dbFile)
        try {
            val storage = newStorage(dbFile, db)
            createSourceRun(storage, "run-a", "novel-1", "林晚")
            createSourceRun(storage, "run-b", "novel-1", "沈照")
            val service = RunOperationsService(
                storage = storage,
                runManagement = RunManagementService(storage),
                packageService = RunPackageService(storage),
                distillExecutor = DistillExecutor(storage, llm = null, promptLoader = null),
            )

            val crossover = service.createCrossoverSpace(
                title = "雨夜客栈",
                worldSetting = "两部小说的人物在客栈相遇。",
                participants = listOf("run-a" to "林晚", "run-b" to "沈照"),
            )

            val characters = crossover["artifact_index"]?.jsonObject
                ?.get("characters")?.jsonArray.orEmpty()
            assertEquals(
                setOf("林晚", "沈照"),
                characters.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.toSet(),
            )
            characters.forEach { entry ->
                val profile = entry.jsonObject["profile_file"]?.jsonPrimitive?.contentOrNull.orEmpty().toPath()
                assertTrue(storage.isFile(profile), "Copied persona profile should exist: $profile")
                assertEquals("novel-1", profile.parent?.parent?.name)
            }
        } finally {
            db.close()
        }
    }

    private fun buildDatabase(file: File): ZaomengDatabase =
        buildZaomengDatabase(getDatabaseBuilder(file.absolutePath.toPath()))

    private fun newStorage(dbFile: File, db: ZaomengDatabase): StorageService {
        val root = dbFile.absolutePath.toPath()
        val documentStore = RoomDocumentStore(db.documentDao())
        return StorageService(root, documentStore, DomainStore(root, db.domainDao(), documentStore))
    }

    private fun createSourceRun(storage: StorageService, runId: String, novelId: String, character: String) {
        val profileDir = storage.getRunDirectory(runId) / "artifacts/characters/$novelId/$character"
        val profileFile = profileDir / "PROFILE.md"
        storage.mkdirs(profileDir)
        storage.writeTextAtomically(profileFile, "---\nname: $character\ncore_identity: 测试人物\n---\n")
        storage.writeRunManifest(
            runId,
            buildJsonObject {
                put("run_id", runId)
                put("novel_id", novelId)
                put("novel_name", "$runId.txt")
                put("status", "ready")
                put("artifact_index", buildJsonObject {
                    put("characters", buildJsonArray {
                        add(buildJsonObject {
                            put("name", character)
                            put("profile_file", profileFile.toString())
                            put("persona_dir", profileDir.toString())
                        })
                    })
                })
            },
        )
    }
}
