package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ChatGenerationInsightsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun generationStatsExposeLatestAndSessionMetrics() {
        val stats = json.parseToJsonElement(
            """
            {
              "latest": {
                "provider": "deepseek",
                "model": "deepseek-chat",
                "prompt_tokens": 1200,
                "completion_tokens": 340,
                "total_tokens": 1540,
                "elapsed_seconds": 2.4,
                "attempt_count": 2,
                "observed": true,
                "status": "hit",
                "hit_rate": 0.75
              },
              "session": {
                "total_turns": 4,
                "total_tokens": 6200,
                "elapsed_seconds": 9.6,
                "retry_count": 1
              }
            }
            """.trimIndent(),
        ).jsonObject

        val insight = requireNotNull(stats.generationInsight())

        assertEquals("deepseek", insight.provider)
        assertEquals("deepseek-chat", insight.model)
        assertEquals(1540, insight.totalTokens)
        assertEquals(4, insight.sessionTurns)
        assertEquals(6200, insight.sessionTokens)
        assertTrue(insight.cacheObserved)
    }

    @Test
    fun contextUsageKeepsOnlyInjectedSourcesAndPreviewItems() {
        val usage = json.parseToJsonElement(
            """
            {
              "speaker": "甲",
              "sources": [
                {"label": "近期对话", "count": 6},
                {
                  "label": "长期记忆检索",
                  "count": 2,
                  "items": ["甲答应保守秘密", "乙仍在怀疑", "不会显示第三条"]
                },
                {"label": "知识边界", "count": 0}
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val insight = requireNotNull(usage.contextUsageInsight())

        assertEquals("甲", insight.speaker)
        assertEquals(listOf("近期对话", "长期记忆检索"), insight.sources.map(ContextSourceInsight::label))
        assertEquals(listOf("甲答应保守秘密", "乙仍在怀疑"), insight.sources.last().items)
    }

    @Test
    fun emptyPayloadDoesNotShowEmptyInsightCards() {
        val empty = json.parseToJsonElement("{}").jsonObject

        assertNull(empty.generationInsight())
        assertNull(empty.contextUsageInsight())
    }
}
