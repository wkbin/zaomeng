package top.wkbin.zaomeng.data.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun dialogueSessionDecodesAdvancedRuntimeState() {
        val session = json.decodeFromString<DialogueSessionDto>(
            """
            {
              "session_id": "dlg-branch",
              "run_id": "run-1",
              "title": "雨夜车站",
              "participants": ["甲", "乙"],
              "status": "ready",
              "pending_turn_summary": {},
              "transcript": [
                {"speaker": "甲", "message": "我们继续。", "role": "character"}
              ],
              "relation_matrix": {
                "乙_甲": {"trust": 7, "affection": 4, "hostility": 1, "ambiguity": 3}
              },
              "relation_locks": {"乙_甲": true},
              "memory_ledger": [
                {"memory_id": "mem-1", "text": "甲答应保守秘密", "pinned": true}
              ],
              "branch_graph": {
                "current_session_id": "dlg-branch",
                "nodes": [
                  {"session_id": "dlg-root", "label": "主线", "is_mainline": true},
                  {"session_id": "dlg-branch", "label": "修正版", "is_current": true}
                ]
              },
              "branch_meta": {"label": "修正版", "is_mainline": false},
              "event_timeline": [
                {"turn_id": "turn-1", "title": "秘密被揭开"}
              ],
              "scene_history": [
                {"scene_card_id": "scene-1", "title": "雨夜车站", "is_current": "true"}
              ],
              "consistency_monitor": {"status": "reviewed", "warning_count": 0},
              "event_signals": {
                "recent": [{"event_type": "promise", "summary": "甲答应保守秘密"}],
                "by_type": {"promise": 1}
              },
              "unknown_future_field": {"kept_by_backend": true}
            }
            """.trimIndent(),
        )

        assertEquals("dlg-branch", session.sessionId)
        assertEquals("雨夜车站", session.title)
        assertTrue(session.pendingTurnSummary.turnId.isBlank())
        assertEquals(
            7,
            session.relationMatrix["乙_甲"]
                ?.jsonObject
                ?.get("trust")
                ?.jsonPrimitive
                ?.int,
        )
        assertTrue(session.relationLocks.containsKey("乙_甲"))
        assertEquals("mem-1", session.memoryLedger.single().memoryId)
        assertEquals(2, session.branchGraph["nodes"]?.jsonArray?.size)
        assertEquals(1, session.eventTimeline.size)
        assertEquals(1, session.sceneHistory.size)
        assertFalse(session.consistencyMonitor.isEmpty())
        assertEquals(1, session.eventSignals["recent"]?.jsonArray?.size)
    }

    @Test
    fun runManifestDecodesNovelSourceHistory() {
        val run = json.decodeFromString<RunManifestDto>(
            """
            {
              "run_id": "run-1",
              "novel_id": "demo",
              "novel_path": "/data/input/part-2.txt",
              "novel_sources": [
                {
                  "source_name": "demo.txt",
                  "source_path": "/data/input/demo.txt",
                  "kind": "initial",
                  "timestamp": "2026-07-01T12:00:00Z",
                  "byte_size": 2048,
                  "char_count": 1800
                },
                {
                  "source_name": "part-2.txt",
                  "source_path": "/data/input/part-2.txt",
                  "kind": "incremental_update",
                  "timestamp": "2026-07-02T12:00:00Z",
                  "byte_size": 4096,
                  "char_count": 3600
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("/data/input/part-2.txt", run.novelPath)
        assertEquals(2, run.novelSources.size)
        assertEquals(4096L, run.novelSources.last().byteSize)
        assertEquals("incremental_update", run.novelSources.last().kind)
    }

    @Test
    fun createRunExplicitlyEncodesEnabledAutoRun() {
        val encoded = json.encodeToString(
            CreateRunRequest(
                novelName = "demo.txt",
                novelContentBase64 = "ZGVtbw==",
                characters = listOf("甲"),
                autoRun = true,
            ),
        )

        assertTrue(json.parseToJsonElement(encoded).jsonObject["auto_run"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun createRunExplicitlyEncodesDeferredImport() {
        val encoded = json.encodeToString(
            CreateRunRequest(
                novelName = "demo.txt",
                novelContentBase64 = "ZGVtbw==",
                characters = listOf("甲"),
                autoRun = false,
                deferRun = true,
            ),
        )

        val payload = json.parseToJsonElement(encoded).jsonObject
        assertEquals("false", payload["auto_run"]!!.jsonPrimitive.content)
        assertEquals("true", payload["defer_run"]!!.jsonPrimitive.content)
    }

    @Test
    fun observeSessionExplicitlyEncodesDefaultMode() {
        val encoded = json.encodeToString(
            CreateDialogueSessionRequest(participants = listOf("甲", "乙")),
        )

        val payload = json.parseToJsonElement(encoded).jsonObject
        assertEquals("observe", payload["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun updateSessionTitleUsesBackendFieldName() {
        val encoded = json.encodeToString(UpdateDialogueSessionTitleRequest(title = "月下重逢"))

        assertEquals(
            "月下重逢",
            json.parseToJsonElement(encoded).jsonObject["title"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun deleteSessionsUsesBackendFieldNames() {
        val encoded = json.encodeToString(
            DeleteSessionsRequest(
                items = listOf(SessionRefDto(runId = "run-1", sessionId = "session-1")),
            ),
        )
        val payload = json.parseToJsonElement(encoded).jsonObject
        val item = payload["items"]!!.jsonArray.single().jsonObject

        assertEquals("run-1", item["run_id"]!!.jsonPrimitive.content)
        assertEquals("session-1", item["session_id"]!!.jsonPrimitive.content)

        val response = json.decodeFromString<DeleteSessionsResponse>(
            """{"status":"deleted","not_found":[{"run_id":"run-1","session_id":"missing"}]}""",
        )
        assertEquals("missing", response.notFound.single().sessionId)
    }

    @Test
    fun samplingPlanUsesBackendFieldNames() {
        val encoded = json.encodeToString(
            EstimateSamplingRequest(
                charCount = 12_400,
                sentenceCount = 88,
                characterCount = 2,
                maxSentences = 80,
                maxChars = 12_000,
            ),
        )
        val payload = json.parseToJsonElement(encoded).jsonObject

        assertEquals("12400", payload["char_count"]!!.jsonPrimitive.content)
        assertEquals("88", payload["sentence_count"]!!.jsonPrimitive.content)
        assertEquals("2", payload["character_count"]!!.jsonPrimitive.content)
        assertEquals("80", payload["max_sentences"]!!.jsonPrimitive.content)
        assertEquals("12000", payload["max_chars"]!!.jsonPrimitive.content)

        val plan = json.decodeFromString<SamplingPlanDto>(
            """{"suggested_max_chars":12000,"suggested_max_sentences":80,"total_calls":10,"token_low":22000,"token_high":32000,"time_low_seconds":95,"time_high_seconds":180}""",
        )
        assertEquals(12_000, plan.suggestedMaxChars)
        assertEquals(10, plan.totalCalls)
        assertEquals(180, plan.timeHighSeconds)
    }
}
