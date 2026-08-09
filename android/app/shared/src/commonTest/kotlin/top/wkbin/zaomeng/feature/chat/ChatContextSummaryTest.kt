package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.DialogueSessionDto

class ChatContextSummaryTest {
    @Test
    fun `uses scene card title and compacts participants`() {
        val summary = chatContextSummary(
            DialogueSessionDto(
                mode = "insert",
                modeDisplay = "以我入场",
                participants = listOf("黛玉", "宝玉", "宝钗", "凤姐"),
                sceneCard = buildJsonObject { put("title", "潇湘馆夜谈") },
            ),
        )

        assertEquals("以我入场", summary.mode)
        assertEquals("潇湘馆夜谈", summary.scene)
        assertEquals("黛玉、宝玉、宝钗 等 4 人", summary.participants)
    }
}
