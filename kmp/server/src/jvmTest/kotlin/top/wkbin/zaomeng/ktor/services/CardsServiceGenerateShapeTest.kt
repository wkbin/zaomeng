package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.ReusableCardDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 回归：卡片生成响应必须带 fields/preview 包装（客户端按 ReusableCardDto 解码，
 * 裸字段响应会导致空白卡）。
 */
class CardsServiceGenerateShapeTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun selfFields() = buildJsonObject {
        put("display_name", "沈照")
        put("scene_identity", "远房客人")
        put("core_identity", "旧宅书吏")
        put("story_role", "线索提供者")
        put("temperament_type", "温敛")
        put("speech_style", "寡言")
        put("soul_goal", "寻回旧事")
    }

    private fun sceneFields() = buildJsonObject {
        put("title", "雨夜小院")
        put("location", "旧宅后院")
        put("atmosphere", "潮湿")
        put("opening_situation", "初遇")
        put("scene_drive", "试探")
    }

    @Test
    fun `self card response wraps fields and preview`() {
        val fields = selfFields()

        val response = cardResponse("self", fields)

        assertEquals(fields, response["fields"]?.jsonObject)
        assertEquals("沈照", response["preview"]?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull)
        assertEquals("寡言", response["preview"]?.jsonObject?.get("speech_style")?.jsonPrimitive?.contentOrNull)
        // 客户端解码契约：fields 必须非空
        val dto = json.decodeFromString(ReusableCardDto.serializer(), json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), response))
        assertEquals("沈照", dto.fields["display_name"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `scene card response wraps fields and preview`() {
        val response = cardResponse("scene", sceneFields())

        assertEquals("雨夜小院", response["preview"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull)
        val dto = json.decodeFromString(ReusableCardDto.serializer(), json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), response))
        assertEquals("雨夜小院", dto.fields["title"]?.jsonPrimitive?.contentOrNull)
    }
}
