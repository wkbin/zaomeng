package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 回归：切换场景卡只传 card_id 时，服务端必须从磁盘读取卡内容；
 * 不存在的卡 id 要报错，显式 scene_profile 应覆盖卡内字段。
 */
class SceneCardSwitchContractTest {
    @Test
    fun `switching by card id loads the card fields from disk`() {
        withSession { storage, service ->
            val switched = service.switchScene(
                runId = "run-1",
                sessionId = "sess-1",
                sceneCardId = "card-1",
                sceneProfile = JsonObject(emptyMap()),
                transitionMessage = "",
                autoContinue = false,
            )
            val card = switched.getValue("scene_card").jsonObject
            val fields = card.getValue("fields").jsonObject
            assertEquals("雨夜客栈", fields.getValue("title").jsonPrimitive.content)
            assertEquals("大堂", fields.getValue("location").jsonPrimitive.content)
            assertEquals("card-1", fields.getValue("scene_card_id").jsonPrimitive.content)
            assertEquals("雨夜客栈", card.getValue("preview").jsonObject.getValue("title").jsonPrimitive.content)
            val stored = storage.loadSessionManifest("run-1", "sess-1")
                .getValue("scene_card").jsonObject.getValue("fields").jsonObject
            assertEquals("雨夜客栈", stored.getValue("title").jsonPrimitive.content)
            assertTrue(storage.loadSessionManifest("run-1", "sess-1").containsKey("state"))
        }
    }

    @Test
    fun `missing card id is rejected`() {
        withSession { _, service ->
            val error = assertFailsWith<IllegalArgumentException> {
                service.switchScene("run-1", "sess-1", "card-missing", JsonObject(emptyMap()), "", false)
            }
            assertEquals("所选场景卡不存在。", error.message)
        }
    }

    @Test
    fun `explicit scene profile overrides card fields`() {
        withSession { _, service ->
            val switched = service.switchScene(
                runId = "run-1",
                sessionId = "sess-1",
                sceneCardId = "card-1",
                sceneProfile = buildJsonObject { put("title", "自定义标题") },
                transitionMessage = "",
                autoContinue = false,
            )
            val fields = switched.getValue("scene_card").jsonObject.getValue("fields").jsonObject
            assertEquals("自定义标题", fields.getValue("title").jsonPrimitive.content)
            assertEquals("大堂", fields.getValue("location").jsonPrimitive.content, "未覆盖的卡字段应保留")
        }
    }

    private fun withSession(block: (StorageService, DialogueAdvancedService) -> Unit) {
        val directory = Files.createTempDirectory("zaomeng-scene-card").toFile()
        try {
            val root = directory.absolutePath.toPath()
            val storage = StorageService(root)
            storage.writeRunManifest("run-1", buildJsonObject { put("run_id", "run-1") })
            storage.writeTextAtomically(
                storage.getStorageRoot() / "scene-cards" / "card-1" / "scene-card.json",
                buildJsonObject {
                    put("card_id", "card-1")
                    put("fields", buildJsonObject {
                        put("title", "雨夜客栈")
                        put("location", "大堂")
                        put("time_hint", "深夜")
                        put("atmosphere", "潮湿阴冷")
                    })
                }.toString(),
            )
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile("run-1", "sess-1"),
                buildJsonObject {
                    put("session_id", "sess-1")
                    put("run_id", "run-1")
                    put("mode", "observe")
                    put("participants", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("角色甲")) })
                    put("transcript_start", 0)
                    put("transcript_count", 0)
                    put("transcript", buildJsonArray {})
                }.toString(),
            )
            block(storage, DialogueAdvancedService(storage, llm = null, prompts = null))
        } finally {
            directory.deleteRecursively()
        }
    }
}
