package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelDiscoveryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun appendsModelsToTheConfiguredApiPrefix() {
        assertEquals(
            "https://example.com/compatible/v1/models",
            modelDiscoveryEndpoint(" https://example.com/compatible/v1/ ").toString(),
        )
        assertEquals("https://api.openai.com/v1/models", modelDiscoveryEndpoint("").toString())
        assertFailsWith<IllegalArgumentException> { modelDiscoveryEndpoint("file:///v1") }
        assertFailsWith<IllegalArgumentException> { modelDiscoveryEndpoint("https://user@example.com/v1") }
    }

    @Test
    fun returnsUniqueSortedNonBlankIds() {
        val body = """{"object":"list","data":[{"id":"z","object":"model"},{"id":"a"},{"id":"z"},{"id":" "}]}"""
        assertEquals(listOf("a", "z"), parseDiscoveredModels(json, body))
        assertEquals(emptyList(), parseDiscoveredModels(json, """{"data":[]}"""))
        assertFailsWith<SerializationException> {
            parseDiscoveredModels(json, """{"error":"upstream error"}""")
        }
    }
}
