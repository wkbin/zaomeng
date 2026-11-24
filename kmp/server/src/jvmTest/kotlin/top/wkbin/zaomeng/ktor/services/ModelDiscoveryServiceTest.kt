package top.wkbin.zaomeng.ktor.services

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.DiscoverModelsRequest
import top.wkbin.zaomeng.platform.FileSecureKeyValueStore
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelDiscoveryServiceTest {
    @Test
    fun discoversPublicModelsAndReusesSavedCredentialsOnlyAtTheSavedEndpoint() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("zaomeng-model-discovery").toFile()
        val root = directory.absolutePath.toPath()
        val storage = StorageService(root)
        val keys = ModelApiKeyService(FileSecureKeyValueStore(root / "test.properties"))
        val settings = SettingsManagementService(storage, keys)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        listOf("/v1/models", "/other/v1/models").forEach { path ->
            server.createContext(path) { exchange ->
                exchange.use {
                    val model = when (it.requestHeaders.getFirst("Authorization")) {
                        null -> "public-model"
                        "Bearer saved-test-key" -> "private-model"
                        else -> null
                    }
                    if (model == null) {
                        it.sendResponseHeaders(401, -1)
                    } else {
                        val response = """{"data":[{"id":"$model"}]}""".encodeToByteArray()
                        it.responseHeaders.add("Content-Type", "application/json")
                        it.sendResponseHeaders(200, response.size.toLong())
                        it.responseBody.write(response)
                    }
                }
            }
        }
        server.start()
        val origin = "http://127.0.0.1:${server.address.port}"
        val baseUrl = "$origin/v1"
        val profileId = "test-profile"
        try {
            assertEquals(listOf("public-model"), settings.discoverModels(DiscoverModelsRequest(baseUrl)).models)

            settings.saveModelSettings("openai-compatible", "private-model", baseUrl, "saved-test-key", profileId = profileId)
            assertEquals(
                listOf("private-model"),
                settings.discoverModels(DiscoverModelsRequest(baseUrl, profileId = profileId)).models,
            )
            assertEquals(
                listOf("public-model"),
                settings.discoverModels(DiscoverModelsRequest("$origin/other/v1", profileId = profileId)).models,
            )

            val error = assertFailsWith<ModelDiscoveryException> {
                settings.discoverModels(DiscoverModelsRequest(baseUrl, "invalid-test-key", profileId))
            }
            assertContains(error.message.orEmpty(), "HTTP 401")
            assertContains(error.message.orEmpty(), "API Key")
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
