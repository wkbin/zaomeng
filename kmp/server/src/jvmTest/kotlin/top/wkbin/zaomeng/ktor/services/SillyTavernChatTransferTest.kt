package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.ImportSillyTavernChatRequest
import top.wkbin.zaomeng.db.DomainStore
import top.wkbin.zaomeng.db.RoomDocumentStore
import top.wkbin.zaomeng.db.buildZaomengDatabase
import top.wkbin.zaomeng.db.getDatabaseBuilder
import java.io.File
import java.util.Base64
import kotlin.test.*

class SillyTavernChatTransferTest {
    @Test
    fun `long group chat keeps archive and swipes through branch and round trip`() = withStorage { storage ->
        val service = SillyTavernChatTransfer(storage)
        val content = buildList {
            add("""{"user_name":"旅人","character_name":"Group","chat_metadata":{"integrity":"discard"}}""")
            add("""{"name":"林晚","is_user":false,"mes":"进来了","send_date":1710000000000,"original_avatar":"lin.png","swipe_id":1,"swipes":["在门口","进来了"],"swipe_info":[{"send_date":"2024-03-09T15:59:00Z","extra":{"private":"discard"}}]}""")
            add("""{"name":"旅人","is_user":true,"mes":"你好","send_date":"2024-03-09T16:00:00Z"}""")
            add("""{"name":"旁白甲","is_user":false,"mes":"灯亮起来","extra":{"type":"narrator"}}""")
            add("""{"name":"沈照","is_user":false,"mes":"这边请","swipes":["先等等","快进来"]}""")
            repeat(130) { index -> add("""{"name":"林晚","is_user":false,"mes":"后续 $index"}""") }
        }.joinToString("\r\n")
        val imported = service.importChat("run-a", request("\uFEFF$content"))
        val id = imported.text("session_id")
        assertEquals("迁移回归", imported.text("title"))
        assertEquals(listOf("林晚", "沈照"), imported.getValue("participants").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(134, imported.getValue("transcript_count").jsonPrimitive.int)
        assertNull(imported["transcript"])
        val recent = storage.loadSessionManifest("run-a", id).getValue("transcript").jsonArray
        assertEquals(80, recent.size)
        assertTrue(recent.none { "swipes" in it.jsonObject })
        val complete = storage.getDialogueSession("run-a", id).getValue("transcript").jsonArray
        assertEquals("scene", complete[2].jsonObject.text("role"))
        assertEquals("2024-03-09T16:00:00Z", complete[0].jsonObject.text("timestamp"))
        assertEquals("import-2", complete[3].jsonObject.text("turn_id"))
        val paged = SessionManagementService(storage, DialogueService(storage))
            .listDialogueSessionMessages("run-a", id, offset = 0, limit = 3, order = "asc").items
        assertEquals("0", paged.first().sillyTavernMessageIndex)
        assertEquals(2, paged.first().sillyTavernSwipeCount)
        assertEquals(0, paged[1].sillyTavernSwipeCount)
        val variants = service.listSwipes("run-a", id, "0", offset = 1, limit = 1)
        assertEquals(2, variants.total)
        assertEquals(1, variants.selectedIndex)
        assertEquals("进来了", variants.items.single().text)
        assertTrue(service.listSwipes("run-a", id, "0", offset = Int.MAX_VALUE, limit = 1).items.isEmpty())
        assertFailsWith<IllegalArgumentException> { service.listSwipes("run-a", id, "../0", 0, 4) }

        val exported = service.exportLines("run-a", id).toList()
        assertEquals(135, exported.size)
        assertEquals("旅人", exported.first().text("user_name"))
        assertTrue(exported.first().getValue("chat_metadata").jsonObject.isEmpty())
        assertEquals("lin.png", exported[1].text("original_avatar"))
        assertEquals(1710000000000L, exported[1].getValue("send_date").jsonPrimitive.long)
        assertEquals(1, exported[1].getValue("swipe_id").jsonPrimitive.int)
        assertEquals(listOf("在门口", "进来了"), exported[1].getValue("swipes").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("extra" in exported[1].getValue("swipe_info").jsonArray[0].jsonObject)
        assertEquals("旁白甲", exported[3].text("name"))
        assertEquals(listOf("先等等", "快进来", "这边请"), exported[4].getValue("swipes").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, exported[4].getValue("swipe_id").jsonPrimitive.int)

        val advanced = DialogueAdvancedService(storage, llm = null, prompts = null)
        val choice = advanced.branchFromImportedSwipe("run-a", id, "0", 0)
        val choiceId = choice.text("session_id")
        assertEquals(1, choice.getValue("transcript_count").jsonPrimitive.int)
        assertEquals("在门口", choice.text("last_entry_preview"))
        assertTrue(choice.text("title").endsWith("回复版本 1"))
        assertEquals(choiceId, advanced.branchFromImportedSwipe("run-a", id, "0", 0).text("session_id"))
        assertEquals(0, service.listSwipes("run-a", choiceId, "0", 0, 1).selectedIndex)
        val selected = service.exportLines("run-a", choiceId).toList()[1]
        assertEquals("在门口", selected.text("mes"))
        assertEquals("2024-03-09T15:59:00Z", selected.text("send_date"))
        assertEquals(0, selected.getValue("swipe_id").jsonPrimitive.int)
        assertEquals(1, service.listSwipes("run-a", id, "0", 0, 1).selectedIndex)
        assertEquals(135, service.exportLines("run-a", id).count())
        val middle = advanced.branchFromImportedSwipe("run-a", id, "3", 0)
        assertEquals(4, middle.getValue("transcript_count").jsonPrimitive.int)
        assertEquals("先等等", service.exportLines("run-a", middle.text("session_id")).last().text("mes"))
        assertFailsWith<IllegalArgumentException> { advanced.branchFromImportedSwipe("run-a", id, "1", 0) }
        assertFailsWith<NoSuchElementException> { advanced.branchFromImportedSwipe("run-a", id, "0", 30) }
        storage.writeTextAtomically(storage.getRunDirectory("run-a") / "world_memory.json", buildJsonObject {
            put("facts", buildJsonArray {
                add(buildJsonObject {
                    put("fact_id", "general")
                    put("summary", "书卷通用设定")
                })
                add(buildJsonObject {
                    put("fact_id", "future")
                    put("summary", "原会话后续事实")
                    put("source_session_id", id)
                    put("source_turn_id", "import-2")
                })
            })
            put("timeline", JsonArray(emptyList()))
        }.toString())
        val resumed = runBlocking {
            DialoguePayloadBuilder(storage).buildTurnPayload(
                requireNotNull(storage.readRunManifest("run-a")), storage.loadSessionManifest("run-a", choiceId), "next", "然后呢？",
            )
        }
        val facts = (resumed["memory_context"] as Map<*, *>)["world_facts"] as List<*>
        assertEquals(listOf("书卷通用设定"), facts.map { (it as Map<*, *>)["summary"] })

        val branch = advanced.branchFromTurn("run-a", id, "import-1")
        storage.deleteRecursively(storage.getDialogueSessionsDirectory("run-a") / id)
        val branched = service.exportLines("run-a", branch.text("session_id")).toList()
        assertEquals(2, branched.size)
        assertEquals(exported[1]["swipes"], branched[1]["swipes"])
        assertEquals("在门口", service.listSwipes("run-a", branch.text("session_id"), "0", 0, 1).items.single().text)
        assertFailsWith<NoSuchElementException> { service.listSwipes("run-a", branch.text("session_id"), "3", 0, 4) }
        val roundTrip = service.importChat("run-a", request(branched.joinToString("\n")))
        assertEquals(1, roundTrip.getValue("transcript_count").jsonPrimitive.int)
    }

    @Test
    fun `invalid and unmatched chats leave no session and headerless chats work`() = withStorage { storage ->
        val service = SillyTavernChatTransfer(storage)
        assertFailsWith<IllegalArgumentException> {
            service.importChat("run-a", request("""{"name":"陌生人","is_user":false,"mes":"不在书卷里"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            service.importChat("run-a", request("""{"name":"林晚","is_user":false,"mes":"可读"}""" + "\ninvalid"))
        }
        assertTrue(storage.listDialogueSessionIds("run-a").isEmpty())
        val imported = service.importChat("run-a", request("""{"name":"林晚","is_user":false,"mes":"不带文件头的群聊"}"""))
        assertEquals(1, imported.getValue("transcript_count").jsonPrimitive.int)
    }

    private fun withStorage(block: (StorageService) -> Unit) {
        val path = File.createTempFile("zaomeng-chat-transfer-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            storage.writeRunManifest("run-a", buildJsonObject {
                put("run_id", "run-a")
                put("artifact_index", buildJsonObject {
                    put("characters", buildJsonArray {
                        for (name in listOf("林晚", "沈照")) add(buildJsonObject { put("name", name) })
                    })
                })
            })
            block(storage)
        } finally {
            db.close()
        }
    }

    private fun request(content: String) = ImportSillyTavernChatRequest(
        "迁移回归.jsonl", Base64.getEncoder().encodeToString(content.encodeToByteArray()),
    )

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
