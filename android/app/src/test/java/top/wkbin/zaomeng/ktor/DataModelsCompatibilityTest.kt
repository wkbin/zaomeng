package top.wkbin.zaomeng.ktor

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import top.wkbin.zaomeng.ktor.models.ModelSettings
import top.wkbin.zaomeng.ktor.models.RunManifest

class DataModelsCompatibilityTest {
    @Test
    fun `run manifest decodes python snake case fields`() {
        val manifest = Json { ignoreUnknownKeys = true }.decodeFromString<RunManifest>(
            """{"run_id":"run-1","novel_name":"Demo","max_sentences":80,"max_chars":12000,"created_at":"2026-08-06T00:00:00Z","updated_at":"2026-08-06T00:01:00Z","artifact_index":{"characters":[]}}"""
        )
        assertEquals("run-1", manifest.runId)
        assertEquals("Demo", manifest.novelName)
        assertEquals(80, manifest.maxSentences)
        assertEquals(12000, manifest.maxChars)
        assertEquals("2026-08-06T00:01:00Z", manifest.updatedAt)
    }

    @Test
    fun `model settings decodes profile snake case fields`() {
        val settings = Json.decodeFromString<ModelSettings>(
            """{"active_profile_id":"p1","profiles":[{"profile_id":"p1","profile_name":"Local","base_url":"http://localhost/v1","max_tokens":512,"reasoning_effort":"off"}]}"""
        )
        assertEquals("p1", settings.activeProfileId)
        assertEquals("Local", settings.profiles.single().profileName)
        assertEquals("http://localhost/v1", settings.profiles.single().baseUrl)
    }
}
