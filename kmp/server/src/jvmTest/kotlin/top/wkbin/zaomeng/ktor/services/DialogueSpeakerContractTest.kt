package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogueSpeakerContractTest {
    @Test
    fun `proxy character becomes suggestion speaker and keeps own persona`() = runBlocking {
        val directory = createTempDirectory("proxy-speaker")
        try {
            val storage = StorageService(directory.toString().toPath())
            val runManifest = buildJsonObject {
                put("run_id", "run")
                put("artifact_index", buildJsonObject {
                    put("characters", buildJsonArray {
                        add(buildJsonObject { put("name", "武松") })
                        add(buildJsonObject { put("name", "潘金莲") })
                        add(buildJsonObject { put("name", "西门庆") })
                    })
                })
            }
            val session = buildJsonObject {
                put("session_id", "session")
                put("mode", "act")
                put("controlled_character", "武松")
                put("participants", buildJsonArray {
                    add(JsonPrimitive("武松"))
                    add(JsonPrimitive("潘金莲"))
                })
                put("transcript", buildJsonArray {
                    add(buildJsonObject {
                        put("speaker", "潘金莲")
                        put("message", "叔叔请坐，我去温些酒来。")
                        put("role", "character")
                    })
                })
            }

            val payload = DialoguePayloadBuilder(storage).buildSuggestionPayload(
                runManifest = runManifest,
                session = session,
                speakerOverride = "西门庆",
            )
            val input = payload["input"] as Map<*, *>
            val persona = payload["user_persona"] as Map<*, *>

            assertEquals("西门庆", input["speaker"])
            assertEquals(listOf("潘金莲"), input["allowed_responders"])
            assertEquals("西门庆", persona["speaker"])
            assertEquals("proxy_character", persona["mode"])
            assertTrue((payload["persona_contexts"] as List<*>).any {
                (it as? Map<*, *>)?.get("name") == "西门庆"
            })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown proxy speaker is rejected`() = runBlocking {
        val directory = createTempDirectory("unknown-proxy-speaker")
        try {
            val storage = StorageService(directory.toString().toPath())
            val runManifest = buildJsonObject {
                put("run_id", "run")
                put("artifact_index", buildJsonObject { put("characters", buildJsonArray { }) })
            }
            val session = buildJsonObject {
                put("session_id", "session")
                put("mode", "act")
                put("controlled_character", "武松")
            }

            kotlin.test.assertFailsWith<IllegalArgumentException> {
                DialoguePayloadBuilder(storage).buildTurnPayload(
                    runManifest = runManifest,
                    session = session,
                    turnId = "turn",
                    message = "test",
                    speakerOverride = "不存在的人物",
                )
            }
            Unit
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `active and eligible responders never restore user controlled character`() {
        val active = selectDialogueActiveParticipants(
            participants = listOf("李斌", "严晓莉"),
            presentParticipants = listOf("李斌"),
            mode = "act",
            inputSpeaker = "李斌",
            controlledCharacter = "李斌",
        )
        val eligible = eligibleDialogueResponders(
            activeParticipants = listOf("李斌") + active,
            mode = "act",
            inputSpeaker = "李斌",
            controlledCharacter = "李斌",
        )

        assertEquals(listOf("严晓莉"), active)
        assertEquals(listOf("严晓莉"), eligible)
    }

    @Test
    fun `responder hints never grant the controlled character permission to speak`() {
        listOf("user_input", "plot", "narration").forEach { kind ->
            val hints = DialoguePromptRules.responderHints(
                mode = "act",
                participants = listOf("李斌", "严晓莉"),
                speaker = "李斌",
                messageKind = kind,
                controlledCharacter = "李斌",
            )

            assertEquals(listOf("严晓莉"), hints.map { it["name"] }, "message kind: $kind")
        }
    }

    @Test
    fun `scene rules contain no permission for controlled character to react`() {
        val modeRule = DialoguePromptRules.modeRule("act", "plot", "李斌")
        val styleRule = DialoguePromptRules.responseStyleRule("act", "plot", "李斌")
        val hostBrief = DialoguePromptRules.hostPromptBrief(
            mode = "act",
            speaker = "李斌",
            participants = listOf("李斌", "严晓莉"),
            messageKind = "plot",
            controlledCharacter = "李斌",
        )

        listOf(modeRule, styleRule, hostBrief).forEach { rule ->
            assertTrue("never" in rule.lowercase())
            assertFalse("may react" in rule.lowercase())
        }
    }

    @Test
    fun `fourth wall rules allow author negotiation while keeping speaker contract`() {
        val modeRule = DialoguePromptRules.modeRule("observe", "fourth_wall")
        val speakerRule = DialoguePromptRules.speakerRule("observe", emptyMap(), "fourth_wall")
        val styleRule = DialoguePromptRules.responseStyleRule("observe", "fourth_wall")

        assertTrue("author" in modeRule.lowercase())
        assertTrue("resist" in modeRule.lowercase() || "refuse" in modeRule.lowercase())
        assertTrue("author" in speakerRule.lowercase())
        assertTrue("refuse" in styleRule.lowercase())
        assertEquals("fourth_wall", DialoguePromptRules.normalizeMessageKind("author"))
    }

    @Test
    fun `atomic session update keeps latest unmute against stale snapshot`() {
        val directory = createTempDirectory("session-control-race")
        try {
            val storage = StorageService(directory.toString().toPath())
            val runId = "run"
            val sessionId = "session"
            val file = storage.getDialogueSessionManifestFile(runId, sessionId)
            storage.writeTextAtomically(
                file,
                buildJsonObject {
                    put("session_id", sessionId)
                    put("muted_characters", JsonArray(listOf(JsonPrimitive("林黛玉"))))
                    put("turn_count", 0)
                }.toString(),
            )
            val stale = storage.loadSessionManifest(runId, sessionId)

            storage.updateSessionManifest(runId, sessionId) { current ->
                buildJsonObject {
                    current.forEach { (key, value) -> if (key != "muted_characters") put(key, value) }
                    put("muted_characters", JsonArray(emptyList()))
                }
            }
            storage.updateSessionManifest(runId, sessionId) { latest ->
                buildJsonObject {
                    stale.forEach { (key, value) -> put(key, value) }
                    latest["muted_characters"]?.let { put("muted_characters", it) }
                    put("turn_count", 1)
                }
            }

            assertEquals(
                emptyList(),
                storage.loadSessionManifest(runId, sessionId)["muted_characters"]
                    ?.let { it as JsonArray }
                    ?.map { it.toString() },
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
