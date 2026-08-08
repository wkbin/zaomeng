package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class ChatSessionInsightsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun branchGraphKeepsCurrentMainlineAndNavigableSessionIds() {
        val graph = json.parseToJsonElement(
            """
            {
              "current_session_id": "dlg-branch",
              "nodes": [
                {
                  "session_id": "dlg-root",
                  "label": "主线",
                  "is_mainline": true,
                  "origin_kind": "root",
                  "event_count": 4
                },
                {
                  "session_id": "dlg-branch",
                  "label": "修正版",
                  "origin_kind": "turn",
                  "origin_title": "秘密被揭开"
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val nodes = graph.branchNodeInsights()

        assertEquals(listOf("dlg-root", "dlg-branch"), nodes.map(BranchNodeInsight::sessionId))
        assertTrue(nodes.first().isMainline)
        assertFalse(nodes.first().isCurrent)
        assertTrue(nodes.last().isCurrent)
        assertTrue(nodes.first().detail.contains("4 个事件"))
        assertTrue(nodes.last().detail.contains("秘密被揭开"))
    }

    @Test
    fun advancedSessionPayloadProducesHumanReadableInsights() {
        val payload = json.parseToJsonElement(
            """
            {
              "consistency_monitor": {
                "latest": {
                  "status": "warning",
                  "summary": "本轮有一处知识边界风险。",
                  "issues": [
                    {"code": "knowledge_boundary_violation", "detail": "乙知道了尚未公开的秘密"}
                  ]
                },
                "metrics": {
                  "checked_turns": 5,
                  "average_score": 88,
                  "pass_rate": 80,
                  "current_pass_streak": 0,
                  "total_issues": 1
                }
              },
              "character_arcs": [
                {
                  "name": "甲",
                  "current": {"mood": "释然", "focus": "修复关系"},
                  "growth_summary": "最近变化：情绪、目标",
                  "change_count": 2,
                  "points": [
                    {"reason": "甲接受了解释。", "state": {"mood": "释然"}}
                  ]
                }
              ],
              "speaker_activity": [
                {
                  "name": "乙",
                  "reply_count": 2,
                  "spoken_turns": 2,
                  "total_turns": 5,
                  "turns_since_spoke": 3,
                  "participation_rate": 0.4,
                  "status": "silent"
                }
              ],
              "speaker_balance": {
                "recommended_speakers": ["乙"],
                "reasons": {"乙": "已连续 3 轮未发言"}
              },
              "relation_timeline": [
                {
                  "pair_key": "乙_甲",
                  "label": "乙 · 甲",
                  "locked": true,
                  "current": {"trust": 7, "affection": 5, "hostility": 1, "ambiguity": 2},
                  "points": [
                    {
                      "changes": {"trust": 2, "affection": 1, "hostility": -1, "ambiguity": 0},
                      "reason": "乙选择相信甲的解释。",
                      "evidence": "这一次我信你。"
                    }
                  ]
                }
              ],
              "event_signals": {
                "recent": [
                  {
                    "kind": "relationship_shift",
                    "actor": "乙",
                    "target": "甲",
                    "cue": "乙放下戒心",
                    "location_hint": "旧宅"
                  }
                ]
              }
            }
            """.trimIndent(),
        ).jsonObject

        val consistency = requireNotNull(payload["consistency_monitor"]?.jsonObject?.consistencyInsight())
        val arcs = payload["character_arcs"]!!.jsonArray.map { it.jsonObject }.characterArcInsights()
        val speakers = requireNotNull(
            speakerInsights(
                payload["speaker_activity"]!!.jsonArray.map { it.jsonObject },
                payload["speaker_balance"]!!.jsonObject,
            ),
        )
        val relations = payload["relation_timeline"]!!.jsonArray
            .map { it.jsonObject }
            .relationTimelineInsights()
        val events = payload["event_signals"]!!.jsonObject.eventSignalInsights()

        assertEquals("需要复核", consistency.statusLabel)
        assertEquals(5, consistency.checkedTurns)
        assertEquals("乙知道了尚未公开的秘密", consistency.latestIssues.single())
        assertEquals("甲", arcs.single().name)
        assertTrue(arcs.single().stateSummary.contains("情绪：释然"))
        assertEquals(listOf("乙"), speakers.recommendedSpeakers)
        assertTrue(speakers.activity.single().needsAttention)
        assertTrue(speakers.activity.single().detail.contains("参与率 40%"))
        assertTrue(relations.single().locked)
        assertTrue(relations.single().changeSummary.contains("信任 +2"))
        assertEquals("关系变化", events.single().kindLabel)
        assertEquals("乙 → 甲 · 旧宅", events.single().context)
    }

    @Test
    fun eventSignalsAcceptLegacyEventTypeAndSummaryFields() {
        val payload = json.parseToJsonElement(
            """
            {
              "recent": [
                {"event_type": "promise", "summary": "甲答应保守秘密"}
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val event = payload.eventSignalInsights().single()

        assertEquals("promise", event.kindLabel)
        assertEquals("甲答应保守秘密", event.cue)
    }
}
