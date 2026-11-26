package top.wkbin.zaomeng.ktor.services

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.DialoguePromptPreviewDto
import top.wkbin.zaomeng.data.api.DialogueReplyRequest
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.WorldBookMatchDto
import top.wkbin.zaomeng.ktor.utils.SseEncoder
import top.wkbin.zaomeng.platform.FileSecureKeyValueStore
import top.wkbin.zaomeng.platform.JvmPromptSource
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialoguePromptPreviewTest {
    @Test
    fun `stream captures the messages received by the model including format retry`() = runBlocking {
        val directory = Files.createTempDirectory("zaomeng-prompt-preview").toFile()
        val storage = StorageService(directory.absolutePath.toPath())
        val keys = ModelApiKeyService(FileSecureKeyValueStore(directory.absolutePath.toPath() / "test.properties"))
        val llm = LlmClient(keys, storage)
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<List<Pair<String, String>>>()
        upstream.createContext("/v1/chat/completions") { exchange ->
            exchange.use {
                val body = Json.parseToJsonElement(it.requestBody.readBytes().decodeToString()).jsonObject
                requests.add(body.getValue("messages").jsonArray.map { message ->
                    val objectValue = message.jsonObject
                    objectValue.getValue("role").jsonPrimitive.content to objectValue.getValue("content").jsonPrimitive.content
                })
                val output = if (requests.size == 1) "格式错误" else """{"speaker":"林晚","message":"请进。"}"""
                val response = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":${JsonPrimitive(output)}}}]}\n\n" +
                    "data: [DONE]\n\n").encodeToByteArray()
                it.responseHeaders.add("Content-Type", "text/event-stream")
                it.sendResponseHeaders(200, response.size.toLong())
                it.responseBody.write(response)
            }
        }
        upstream.start()
        try {
            SettingsManagementService(storage, keys).saveModelSettings(
                provider = "openai-compatible", model = "local", baseUrl = "http://127.0.0.1:${upstream.address.port}/v1",
                apiKey = "test-key",
            )
            val card = """{"spec":"chara_card_v2","data":{"name":"林晚","first_mes":"欢迎。",
                "character_book":{"entries":[{"keys":["开门"],"content":"老门上有铜环。","position":"before_char"}]}}}"""
            val runId = RunPackageService(storage).importPackage(ImportRunPackageRequest(
                "card.json", Base64.getEncoder().encodeToString(card.encodeToByteArray()),
            )).getValue("run_id").jsonPrimitive.content
            val dialogue = DialogueService(storage)
            val sessions = SessionManagementService(storage, dialogue)
            val service = DialogueStreamService(storage, llm, PromptLoader(JvmPromptSource()), dialogue)
            val sessionId = sessions.openDialogueSession(runId, "insert", listOf("林晚"))
                .getValue("session_id").jsonPrimitive.content
            val frames = service.replyDialogueTurnFrames(
                runId, sessionId, "开门。", messageKind = "dialogue", operationId = "turn-preview", includePromptPreview = true,
            ).toList()
            val previews = frames.filterIsInstance<DialogueStreamFrame.PromptPreview>().map { it.preview }
            assertEquals(listOf(1, 2), previews.map { it.attempt })
            assertEquals(requests.toList(), previews.map { preview -> preview.messages.map { it.role to it.text } })
            assertTrue(previews.all { preview -> preview.worldBookMatches.single().excerpt == "老门上有铜环。" })
            assertTrue(frames.last() is DialogueStreamFrame.Completed)
            val normal = service.replyDialogueTurnFrames(
                runId, sessionId, "再问一句。", messageKind = "dialogue", operationId = "turn-normal",
            ).toList()
            assertTrue(normal.none { it is DialogueStreamFrame.PromptPreview })
            assertTrue(normal.last() is DialogueStreamFrame.Completed)
        } finally {
            upstream.stop(0)
            llm.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `preview preserves order and UTF-16 text boundaries with a bounded size`() {
        val messages = listOf(
            LlmClient.ChatMessage("system", "甲".repeat(23_999) + "😀"),
            LlmClient.ChatMessage("system", "乙".repeat(25_000)),
            LlmClient.ChatMessage("assistant", "丙".repeat(20_000)),
            LlmClient.ChatMessage("user", "本轮输入"),
        )
        val hit = WorldBookMatchDto("雨巷", "沿街有灯。", "injected")
        val preview = dialoguePromptPreview("turn-1", 2, messages, listOf(hit, hit.copy(status = "disabled")))
        assertEquals(messages.map { it.role }, preview.messages.map { it.role })
        assertEquals(listOf(23_999, 24_000, 16_001, 0), preview.messages.map { it.text.length })
        assertTrue(preview.messages.all { it.truncated })
        assertEquals(listOf(hit), preview.worldBookMatches)
        assertEquals("本轮输入", messages.last().content)
    }

    @Test
    fun `preview is opt in and embedded newlines stay within one SSE event`() {
        val json = Json
        assertFalse(json.decodeFromString<DialogueReplyRequest>("""{"message":"你好"}""").includePromptPreview)
        assertTrue(json.decodeFromString<DialogueReplyRequest>(
            """{"message":"你好","include_prompt_preview":true}""",
        ).includePromptPreview)
        val preview = dialoguePromptPreview("turn-1", 1, listOf(LlmClient.ChatMessage("user", "两行\n文字")), emptyList())
        val wire = SseEncoder.encodeEvent(
            "prompt_preview", json.encodeToJsonElement(DialoguePromptPreviewDto.serializer(), preview),
        )
        val lines = wire.lineSequence().filter(String::isNotEmpty).toList()
        assertEquals(2, lines.size)
        assertEquals("event: prompt_preview", lines.first())
        assertEquals(preview, json.decodeFromString<DialoguePromptPreviewDto>(lines.last().removePrefix("data: ")))
    }
}
