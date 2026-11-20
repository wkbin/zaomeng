package top.wkbin.zaomeng.ktor.services

import com.sun.net.httpserver.HttpServer
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.platform.FileSecureKeyValueStore
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelRequestTimeoutTest {
    @Test
    fun savedTimeoutAppliesToCompletionAndSurvivesAnOlderSaveRequest() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("zaomeng-model-timeout").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.use {
                it.requestBody.readBytes()
                Thread.sleep(1_400)
                val response = """{"model":"local","choices":[]}""".encodeToByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(200, response.size.toLong())
                it.responseBody.write(response)
            }
        }
        server.start()
        val root = directory.absolutePath.toPath()
        val storage = StorageService(root)
        val keys = ModelApiKeyService(FileSecureKeyValueStore(root / "test.properties"))
        val settings = SettingsManagementService(storage, keys)
        val client = LlmClient(keys, storage)
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
        val messages = listOf(LlmClient.ChatMessage("user", "test"))
        try {
            settings.saveModelSettings("openai-compatible", "local", baseUrl, "test-key")
            assertEquals(60, settings.getModelSettings()["request_timeout_seconds"]?.jsonPrimitive?.int)

            settings.saveModelSettings("openai-compatible", "local", baseUrl, requestTimeoutSeconds = 1)
            assertFailsWith<HttpRequestTimeoutException> { client.chatCompletion(messages) }

            settings.saveModelSettings("openai-compatible", "local", baseUrl, requestTimeoutSeconds = 5)
            settings.saveModelSettings("openai-compatible", "local", baseUrl)
            assertEquals(5, settings.getModelSettings()["request_timeout_seconds"]?.jsonPrimitive?.int)
            assertEquals("local", client.chatCompletion(messages).model)
            assertFailsWith<IllegalArgumentException> {
                settings.saveModelSettings("openai-compatible", "local", baseUrl, requestTimeoutSeconds = 301)
            }
        } finally {
            client.close()
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
