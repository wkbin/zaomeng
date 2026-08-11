package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import top.wkbin.zaomeng.platform.dumpYaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PersonaDeleteTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun newDatabase(file: File): ZaomengDatabase =
        buildZaomengDatabase(getDatabaseBuilder(file.absolutePath.toPath()))

    private fun newStorage(dbFile: File, db: ZaomengDatabase): StorageService {
        val root = dbFile.absolutePath.toPath()
        val documentStore = RoomDocumentStore(db.documentDao())
        val domain = DomainStore(root, db.domainDao(), documentStore)
        return StorageService(root, documentStore, domain)
    }

    @Test
    fun `delete persona removes files entity relations and manifest entries`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val storage = newStorage(dbFile, db)
        val runDir = storage.getRunDirectory("run-1")

        val linProfileDir = runDir / "artifacts/characters/novel-1/林晚"
        val shenProfileDir = runDir / "artifacts/characters/novel-1/沈照"
        storage.mkdirs(linProfileDir)
        storage.mkdirs(shenProfileDir)
        storage.writeTextAtomically(
            linProfileDir / "PROFILE.md",
            "---\nname: 林晚\ncore_identity: 旧宅书吏\n---\n",
        )
        storage.writeTextAtomically(
            shenProfileDir / "PROFILE.md",
            "---\nname: 沈照\ncore_identity: 捕头\n---\n",
        )

        val avatar = storage.avatarFile("run-1", "林晚")
        storage.mkdirs(avatar.parent!!)
        storage.writeTextAtomically(avatar, "avatar-bytes")

        val relationsFile = runDir / "artifacts/relations/novel-1.relations.md"
        storage.mkdirs(relationsFile.parent!!)
        storage.writeTextAtomically(
            relationsFile,
            "---\n" + dumpYaml(
                mapOf(
                    "novel_id" to "novel-1",
                    "relations" to mapOf(
                        "林晚_沈照" to mapOf("trust" to 5, "affection" to 3),
                    ),
                    "conflicts" to emptyList<Any>(),
                ),
            ) + "---\n# RELATION_GRAPH\n",
        )

        storage.writeRunManifest(
            "run-1",
            buildJsonObject {
                put("run_id", "run-1")
                put("novel_id", "novel-1")
                put("status", "ready")
                put("locked_characters", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("林晚"))
                    add(kotlinx.serialization.json.JsonPrimitive("沈照"))
                })
                put("artifact_index", buildJsonObject {
                    put("characters", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "林晚")
                            put("profile_file", (linProfileDir / "PROFILE.md").toString())
                            put("persona_dir", linProfileDir.toString())
                        })
                        add(buildJsonObject {
                            put("name", "沈照")
                            put("profile_file", (shenProfileDir / "PROFILE.md").toString())
                            put("persona_dir", shenProfileDir.toString())
                        })
                    })
                    put("relation_graph", buildJsonObject {
                        put("relations_file", relationsFile.toString())
                        put("relation_count", 1)
                        put("has_relation_graph", true)
                    })
                })
                put("progress", buildJsonObject {
                    put("completed_characters", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("林晚"))
                        add(kotlinx.serialization.json.JsonPrimitive("沈照"))
                    })
                    put("completed_count", 2)
                    put("total_characters", 2)
                    put("stage", "completed")
                })
                put("summary", buildJsonObject {
                    put("characters_total", 2)
                    put("characters_completed", 2)
                    put("status_text", "可使用")
                })
            },
        )

        val persona = PersonaService(storage, relations = RelationsService(storage))
        val result = persona.deletePersona("run-1", "林晚")

        assertEquals("deleted", result.status)
        assertFalse(storage.isFile(linProfileDir / "PROFILE.md"))
        assertFalse(storage.isFile(avatar))
        assertFalse(storage.readText(relationsFile).contains("林晚"))

        val manifest = storage.readRunManifest("run-1")!!
        val characters = manifest["artifact_index"]!!.jsonObject["characters"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.contentOrNull.orEmpty() }
        assertEquals(listOf("沈照"), characters)
        val locked = manifest["locked_characters"]!!.jsonArray
            .map { it.jsonPrimitive.contentOrNull.orEmpty() }
        assertEquals(listOf("沈照"), locked)
        val progress = manifest["progress"]!!.jsonObject
        assertEquals(1, progress["completed_count"]!!.jsonPrimitive.contentOrNull?.toInt())
        assertEquals(1, progress["total_characters"]!!.jsonPrimitive.contentOrNull?.toInt())
        assertEquals(1, runBlocking { db.domainDao().personaCount() })
        db.close()
    }

    @Test
    fun `delete persona is rejected while distillation is running`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val storage = newStorage(dbFile, db)
        storage.writeRunManifest(
            "run-1",
            buildJsonObject {
                put("run_id", "run-1")
                put("novel_id", "novel-1")
                put("status", "running")
            },
        )

        val persona = PersonaService(storage, relations = RelationsService(storage))
        assertFailsWith<IllegalArgumentException> {
            persona.deletePersona("run-1", "林晚")
        }
        db.close()
    }

    private fun tempDbFile(): File =
        File.createTempFile("zaomeng-persona-delete-", ".db").apply { deleteOnExit() }
}
