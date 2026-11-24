package top.wkbin.zaomeng.ktor.services

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath

class DialogueHistoryRetrievalTest {
    @Test
    fun `history read keeps a turn that straddles the page boundary`() {
        withSession(entryCount = 205, targetSeqs = setOf(4, 5, 6)) { storage ->
            val result = DialogueHistoryRetrievalService(storage)
                .historyRead(RUN_ID, SESSION_ID, TARGET_TURN, expectedRevision = REVISION)

            assertEquals(listOf(4, 5, 6), result.items.map { it.seq })
        }
    }

    private fun withSession(entryCount: Int, targetSeqs: Set<Int>, block: (StorageService) -> Unit) {
        val dir = createTempDirectory("zaomeng-history-retrieval")
        try {
            val storage = StorageService(dir.toString().toPath())
            storage.writeRunManifest(RUN_ID, buildJsonObject { put("run_id", RUN_ID) })
            val manifest = buildJsonObject {
                put("session_id", SESSION_ID)
                put("revision", REVISION)
                put("transcript_start", 0)
                put("transcript", buildJsonArray {
                    (0 until entryCount).forEach { index ->
                        add(buildJsonObject {
                            put("speaker", if (index in targetSeqs) "甲" else "路人")
                            put("message", "第 $index 句")
                            put("turn_id", if (index in targetSeqs) TARGET_TURN else "turn-$index")
                        })
                    }
                })
            }
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(RUN_ID, SESSION_ID),
                manifest.toString(),
            )
            block(storage)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val SESSION_ID = "session-1"
        const val TARGET_TURN = "turn-target"
        const val REVISION = 1L
    }
}
