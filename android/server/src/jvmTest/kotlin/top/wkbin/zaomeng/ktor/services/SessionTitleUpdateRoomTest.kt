package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

/** 复现：Room 后端（打包应用同款）下修改会话标题是否抛异常。 */
class SessionTitleUpdateRoomTest {
    @Test
    fun `update session title over room backend succeeds`() {
        val dbFile = File.createTempFile("zaomeng-title-room-", ".db")
        try {
            val db = buildZaomengDatabase(getDatabaseBuilder(dbFile.absolutePath.toPath()))
            val root = dbFile.absolutePath.toPath()
            val documentStore = RoomDocumentStore(db.documentDao())
            val storage = StorageService(root, documentStore, DomainStore(root, db.domainDao(), documentStore))
            val service = SessionManagementService(storage, DialogueService(storage))

            storage.writeRunManifest("run-1", buildJsonObject { put("run_id", "run-1"); put("title", "测试") })
            val created = service.createDialogueSession(
                runId = "run-1",
                mode = "observe",
                participants = listOf("角色甲"),
            )
            val sessionId = created["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val updated = service.updateDialogueSessionTitle("run-1", sessionId, "新标题")

            assertEquals("新标题", updated["title"]?.jsonPrimitive?.contentOrNull)
            assertEquals("新标题", storage.loadSessionManifest("run-1", sessionId)["title"]?.jsonPrimitive?.contentOrNull)
            db.close()
        } finally {
            dbFile.delete()
        }
    }
}
