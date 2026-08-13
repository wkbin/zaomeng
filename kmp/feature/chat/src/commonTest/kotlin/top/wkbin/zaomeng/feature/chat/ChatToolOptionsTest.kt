package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ChatToolOptionsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun directorOptionsKeepBeatDirectionEffectAndRisk() {
        val payload = json.parseToJsonElement(
            """
            {
              "options": [
                {
                  "title": "雨夜摊牌",
                  "focus": "甲与乙",
                  "beat": "乙拿出那封旧信",
                  "direction": "迫使甲当场回应",
                  "expected_effect": "秘密关系被公开",
                  "risk": "冲突升级过快"
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val option = payload.extractDirectorOptions().single()

        assertEquals("plot", option.messageKind)
        assertEquals("乙拿出那封旧信；迫使甲当场回应", option.value)
        assertTrue(option.description.contains("秘密关系被公开"))
        assertTrue(option.description.contains("冲突升级过快"))
    }

    @Test
    fun fourthWallOptionsKeepResistancePriceAndMessageKind() {
        val payload = json.parseToJsonElement(
            """
            {
              "message_kind": "fourth_wall",
              "options": [
                {
                  "title": "拒绝和好",
                  "focus": "人物意志",
                  "beat": "她直视作者，表示这个安排违背她的底线",
                  "direction": "让角色拒绝作者的和好安排",
                  "expected_effect": "暴露人物自主意志",
                  "risk": "可能偏离作者目标",
                  "resistance": "她不愿原谅对方",
                  "price": "要求作者先删去那段被逼道歉的记忆"
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val option = payload.extractDirectorOptions().single()

        assertEquals("fourth_wall", option.messageKind)
        assertTrue(option.description.contains("抵抗：她不愿原谅对方"))
        assertTrue(option.description.contains("代价：要求作者先删去那段被逼道歉的记忆"))
    }
}
