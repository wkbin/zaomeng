package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** 回归：创建会话后按开场内容自动生成标题。 */
class SessionAutoTitleTest {
    @Test
    fun `title comes from first transcript message`() {
        val session = buildJsonObject {
            put("title", "")
            put(
                "transcript",
                buildJsonArray {
                    add(buildJsonObject { put("speaker", "旁白"); put("message", "「清晨，城郊的茶馆里坐满了人。」") })
                    add(buildJsonObject { put("speaker", "林七夜"); put("message", "今天天气不错。") })
                },
            )
        }
        val title = autoSessionTitle(
            session = session,
            mode = "observe",
            participants = listOf("林七夜"),
            controlledCharacter = "",
            sceneProfile = JsonObject(emptyMap()),
            selfProfile = JsonObject(emptyMap()),
        )
        assertEquals("清晨，城郊的茶馆里坐满了人", title)
    }

    @Test
    fun `title falls back to scene title when transcript empty`() {
        val session = buildJsonObject { put("title", ""); put("transcript", buildJsonArray {}) }
        val title = autoSessionTitle(
            session = session,
            mode = "observe",
            participants = emptyList(),
            controlledCharacter = "",
            sceneProfile = buildJsonObject { put("title", "雨夜小院") },
            selfProfile = JsonObject(emptyMap()),
        )
        assertEquals("雨夜小院", title)
    }

    @Test
    fun `title falls back to mode label`() {
        val session = buildJsonObject { put("title", ""); put("transcript", buildJsonArray {}) }
        val title = autoSessionTitle(
            session = session,
            mode = "act",
            participants = listOf("林七夜", "沈青竹"),
            controlledCharacter = "周平",
            sceneProfile = JsonObject(emptyMap()),
            selfProfile = JsonObject(emptyMap()),
        )
        assertEquals("扮演 · 周平、林七夜、沈青竹", title)
    }
}
