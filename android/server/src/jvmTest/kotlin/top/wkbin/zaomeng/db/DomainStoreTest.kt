package top.wkbin.zaomeng.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.ktor.services.StorageService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 领域实体表测试：验证 StorageService 写入时同步实体、列表读取切实体、
 * 删除级联、以及 documents → 实体的一次性回填。
 */
class DomainStoreTest {
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
    fun `run manifest write syncs runs entity and list reads use it`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val storage = newStorage(dbFile, db)

        storage.writeRunManifest(
            "run-1",
            buildJsonObject {
                put("run_id", "run-1")
                put("title", "测试书卷")
                put("novel_id", "novel-1")
                put("status", "ready")
            },
        )

        assertEquals(listOf("run-1"), storage.listRunIds())
        val manifests = storage.listRunManifests()
        assertEquals(1, manifests.size)
        assertEquals("测试书卷", manifests[0]["title"]?.jsonPrimitive?.contentOrNull)
        assertTrue(storage.runExists("run-1"))
        db.close()
    }

    @Test
    fun `session manifest write syncs session and message entities`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val storage = newStorage(dbFile, db)
        storage.writeRunManifest("run-1", buildJsonObject { put("run_id", "run-1") })

        val manifest = buildJsonObject {
            put("session_id", "dlg-1")
            put("run_id", "run-1")
            put("title", "第一幕")
            put("mode", "observe")
            put("status", "ready")
            put("updated_at", "2026-08-09T00:00:00Z")
            put(
                "transcript",
                buildJsonArray {
                    add(buildJsonObject {
                        put("turn_id", "t1")
                        put("speaker", "我")
                        put("role", "user")
                        put("message", "你好")
                        put("timestamp", "2026-08-09T00:00:00Z")
                    })
                    add(buildJsonObject {
                        put("turn_id", "t1")
                        put("speaker", "林晚")
                        put("role", "character")
                        put("message", "公子有礼。")
                        put("timestamp", "2026-08-09T00:00:01Z")
                    })
                },
            )
        }
        storage.writeTextAtomically(
            storage.getDialogueSessionManifestFile("run-1", "dlg-1"),
            json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), manifest),
        )

        assertEquals(listOf("dlg-1"), storage.listDialogueSessionIds("run-1"))
        val sessions = storage.listDialogueSessions("run-1")
        assertEquals(1, sessions.size)
        assertEquals("第一幕", sessions[0]["title"]?.jsonPrimitive?.contentOrNull)

        val messageCount = kotlinx.coroutines.runBlocking { db.domainDao().messageCount() }
        assertEquals(2L, messageCount)
        db.close()
    }

    @Test
    fun `card and persona writes sync entities`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val storage = newStorage(dbFile, db)
        val root = dbFile.absolutePath.toPath()

        // 卡片：scene-cards/<id>/card.json + scene-card.json
        val cardDir = root / "scene-cards" / "card-1"
        storage.mkdirs(cardDir)
        storage.writeTextAtomically(
            cardDir / "card.json",
            """{"card_id":"card-1","created_at":"2026-08-09T00:00:00Z","updated_at":"2026-08-09T01:00:00Z"}""",
        )
        storage.writeTextAtomically(
            cardDir / "scene-card.json",
            """{"title":"雨夜小院","location":"小院","atmosphere":"湿润","opening_situation":"初遇","scene_drive":"试探"}""",
        )
        val cards = kotlinx.coroutines.runBlocking { db.domainDao().cardsOf("scene") }
        assertEquals(1, cards.size)
        assertEquals("card-1", cards[0].cardId)
        assertEquals("雨夜小院", cards[0].title)
        assertTrue(cards[0].fieldsJson.contains("opening_situation"))

        // 人物：runs/<id>/artifacts/characters/<novelId>/<name>/PROFILE.md
        val profileDir = storage.getRunDirectory("run-1") / "artifacts" / "characters" / "novel-1" / "林晚"
        storage.mkdirs(profileDir)
        storage.writeTextAtomically(
            profileDir / "PROFILE.md",
            "---\nname: 林晚\ncore_identity: 温婉\n---\n\n正文……",
        )
        val personas = kotlinx.coroutines.runBlocking { db.domainDao().personaCount() }
        assertEquals(1, personas)
        db.close()
    }

    @Test
    fun `delete cascades to entities`() {
        val dbFile = tempDbFile()
        val db = newDatabase(dbFile)
        val storage = newStorage(dbFile, db)
        storage.writeRunManifest("run-1", buildJsonObject { put("run_id", "run-1") })
        storage.writeTextAtomically(
            storage.getDialogueSessionManifestFile("run-1", "dlg-1"),
            """{"session_id":"dlg-1","transcript":[]}""",
        )

        storage.deleteRecursively(storage.getDialogueSessionsDirectory("run-1") / "dlg-1")
        assertEquals(emptyList<String>(), storage.listDialogueSessionIds("run-1"))

        storage.deleteRecursively(storage.getRunDirectory("run-1"))
        assertEquals(emptyList<String>(), storage.listRunIds())
        assertEquals(0, kotlinx.coroutines.runBlocking { db.domainDao().runCount() })
        db.close()
    }

    private fun tempDbFile(): File =
        File.createTempFile("zaomeng-domain-test-", ".db").apply { deleteOnExit() }
}
