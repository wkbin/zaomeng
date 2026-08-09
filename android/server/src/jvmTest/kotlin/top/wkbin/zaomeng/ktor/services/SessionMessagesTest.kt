package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 会话消息分页/轻量响应测试：
 * desc 反向分页、asc 正序切片、leanSession 去 transcript 保 count、按 turn_id 提取增量。
 */
class SessionMessagesTest {
    private fun sessionWithTranscript(count: Int): JsonObject = buildJsonObject {
        put("session_id", "s1")
        put("transcript", buildJsonArray {
            repeat(count) { index ->
                add(buildJsonObject {
                    put("speaker", "角色$index")
                    put("message", "消息$index")
                    put("role", if (index % 2 == 0) "character" else "scene")
                    put("turn_id", "turn-${index / 3}")
                    put("timestamp", "2026-08-09T00:00:0${index % 10}Z")
                })
            }
        })
    }

    @Test
    fun `desc page skips newest and returns older pages`() {
        val session = sessionWithTranscript(50)

        val first = pageTranscript(session, offset = 0, limit = 20, order = "desc")
        assertEquals(20, first.items.size)
        assertEquals("角色49", first.items.first().speaker)
        assertEquals(50, first.total)
        assertTrue(first.hasMore)

        val older = pageTranscript(session, offset = 20, limit = 20, order = "desc")
        assertEquals("角色29", older.items.first().speaker)
        assertTrue(older.hasMore)

        val last = pageTranscript(session, offset = 40, limit = 20, order = "desc")
        assertEquals("角色9", last.items.first().speaker)
        assertFalse(last.hasMore)
    }

    @Test
    fun `asc page slices from start`() {
        val page = pageTranscript(sessionWithTranscript(30), offset = 10, limit = 5, order = "asc")
        assertEquals(
            listOf("角色10", "角色11", "角色12", "角色13", "角色14"),
            page.items.map { it.speaker },
        )
        assertTrue(page.hasMore)
    }

    @Test
    fun `lean session strips transcript and keeps count`() {
        val lean = leanSession(sessionWithTranscript(7))
        assertFalse(lean.containsKey("transcript"))
        assertEquals(7, lean["transcript_count"]?.jsonPrimitive?.intOrNull)
        assertEquals("s1", lean["session_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `full response carries transcript count`() {
        val full = withTranscriptCount(sessionWithTranscript(4))
        assertEquals(4, full["transcript_count"]?.jsonPrimitive?.intOrNull)
        assertTrue(full.containsKey("transcript"))
    }

    @Test
    fun `appended by turn id works for fresh and replayed turns`() {
        val session = sessionWithTranscript(9)
        val appended = transcriptByTurnId(session, "turn-1")
        assertEquals(3, appended.size)
        assertTrue(appended.all { it["turn_id"]?.jsonPrimitive?.content == "turn-1" })
        assertEquals(emptyList(), transcriptByTurnId(session, "missing"))
    }
}
