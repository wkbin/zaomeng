package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.wkbin.zaomeng.plugins.api.ChatActionRequest
import top.wkbin.zaomeng.plugins.api.PluginHost
import top.wkbin.zaomeng.plugins.api.PluginPersonaSummary
import top.wkbin.zaomeng.plugins.api.PluginReplyAsCharacterResult
import top.wkbin.zaomeng.plugins.api.PluginSessionCharacterSummary
import top.wkbin.zaomeng.plugins.api.SuggestionOption
import top.wkbin.zaomeng.plugins.builtin.CharacterMutePlugin
import top.wkbin.zaomeng.plugins.builtin.ReplyAsCharacterPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltinCharacterToolsTest {
    @Test
    fun `reply as character asks user to choose before generating`() = runBlocking {
        val host = FakeHost().apply {
            offScene = listOf(
                PluginPersonaSummary("林黛玉", "敏感细腻"),
                PluginPersonaSummary("薛宝钗", "端方克制"),
            )
        }
        val plugin = ReplyAsCharacterPlugin()

        val choices = plugin.executeChatAction(
            "reply-as-character",
            ChatActionRequest(runId = "run", sessionId = "session", seedText = "替我婉拒"),
            host,
        )

        assertEquals(listOf("林黛玉", "薛宝钗"), choices.choices.map { it.value })
        assertTrue(choices.choicePrompt.contains("选择"))

        val result = plugin.executeChatAction(
            "reply-as-character",
            ChatActionRequest(
                runId = "run",
                sessionId = "session",
                seedText = "替我婉拒",
                selection = "林黛玉",
            ),
            host,
        )
        assertEquals("林黛玉", host.requestedReplyCharacter)
        assertEquals("这话我却不好应。", result.suggestion)
        assertEquals("林黛玉", result.character)
    }

    @Test
    fun `mute plugin only offers eligible present characters and can unmute`() = runBlocking {
        val host = FakeHost().apply {
            sceneCharacters = listOf(
                PluginSessionCharacterSummary("贾宝玉", muted = false, canMute = false),
                PluginSessionCharacterSummary("林黛玉", muted = false),
                PluginSessionCharacterSummary("薛宝钗", muted = true),
            )
        }
        val plugin = CharacterMutePlugin()

        val choices = plugin.executeChatAction(
            "manage-character-mute",
            ChatActionRequest(runId = "run", sessionId = "session"),
            host,
        )
        assertEquals(listOf("mute:林黛玉", "unmute:薛宝钗"), choices.choices.map { it.value })

        val result = plugin.executeChatAction(
            "manage-character-mute",
            ChatActionRequest(runId = "run", sessionId = "session", selection = "mute:林黛玉"),
            host,
        )
        assertEquals("林黛玉" to true, host.muteRequest)
        assertEquals("session", result.session["session_id"]?.let { (it as JsonPrimitive).content })

        plugin.executeChatAction(
            "manage-character-mute",
            ChatActionRequest(runId = "run", sessionId = "session", selection = "unmute:薛宝钗"),
            host,
        )
        assertEquals("薛宝钗" to false, host.muteRequest)
    }

    private class FakeHost : PluginHost {
        var offScene: List<PluginPersonaSummary> = emptyList()
        var sceneCharacters: List<PluginSessionCharacterSummary> = emptyList()
        var requestedReplyCharacter: String = ""
        var muteRequest: Pair<String, Boolean>? = null

        override suspend fun invokeSuggestion(
            runId: String,
            sessionId: String,
            seedText: String,
            direction: String,
        ): String = ""

        override suspend fun invokeVariants(
            runId: String,
            sessionId: String,
            seedText: String,
            direction: String,
        ): List<SuggestionOption> = emptyList()

        override suspend fun invokeNpc(runId: String, sessionId: String, direction: String): JsonObject = JsonObject(emptyMap())

        override suspend fun listOffScenePersonas(
            runId: String,
            sessionId: String,
        ): List<PluginPersonaSummary> = offScene

        override suspend fun listSessionCharacters(
            runId: String,
            sessionId: String,
        ): List<PluginSessionCharacterSummary> = sceneCharacters

        override suspend fun invokeReplyAsCharacter(
            runId: String,
            sessionId: String,
            character: String,
            seedText: String,
            direction: String,
        ): PluginReplyAsCharacterResult {
            requestedReplyCharacter = character
            return PluginReplyAsCharacterResult(character, "这话我却不好应。")
        }

        override suspend fun setSessionCharacterMuted(
            runId: String,
            sessionId: String,
            character: String,
            muted: Boolean,
        ): JsonObject {
            muteRequest = character to muted
            return JsonObject(mapOf("session_id" to JsonPrimitive(sessionId)))
        }

        override fun log(pluginId: String, level: String, message: String) = Unit
    }
}
