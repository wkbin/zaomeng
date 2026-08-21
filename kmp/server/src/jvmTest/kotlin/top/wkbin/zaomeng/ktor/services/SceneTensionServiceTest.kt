package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SceneTensionServiceTest {

    private val service = SceneTensionService()

    @Test
    fun `empty messages returns calm tension default`() {
        val tension = service.evaluateTension(emptyList())
        assertEquals(30, tension.score)
        assertEquals("Calm", tension.pacing)
        assertEquals("平缓蓄势", tension.label)
    }

    @Test
    fun `calm dialogue evaluates to Calm pacing`() {
        val calmMessages = listOf(
            "今日天气晴好，我们坐下饮茶闲谈片刻吧。",
            "甚好，微风徐来，十分安好。",
            "缓缓行过庭院，月色淡然。",
        )
        val tension = service.evaluateTension(calmMessages)
        assertTrue(tension.score <= 35, "Calm dialogue score (${tension.score}) should be <= 35")
        assertEquals("Calm", tension.pacing)
    }

    @Test
    fun `conflict and emotional dialogue elevates tension to Building or Climax`() {
        val tenseMessages = listOf(
            "你为何冷笑？！当年旧案的真相到底是什么！",
            "凭什么要我住口？你分明在骗我，休想再隐瞒！",
            "拔剑！今日若不说清楚，休怪我不念旧情！",
            "可笑！你以为背叛的人是我？！",
        )
        val tension = service.evaluateTension(tenseMessages)
        assertTrue(tension.score >= 66, "Conflict dialogue score (${tension.score}) should be >= 66")
        assertTrue(tension.pacing in setOf("Climax", "Intense"), "Pacing should be Climax or Intense, got ${tension.pacing}")
        assertTrue(tension.conflictIndicator > 0.5f)
    }

    @Test
    fun `preset events covers all four story categories`() {
        val allEvents = service.getPresetEvents()
        assertTrue(allEvents.size >= 8)

        val categories = allEvents.map { it.category }.toSet()
        assertTrue("external" in categories, "Should include external events")
        assertTrue("secret" in categories, "Should include secret events")
        assertTrue("emotion" in categories, "Should include emotion events")
        assertTrue("crisis" in categories, "Should include crisis events")

        val externalOnly = service.getPresetEvents("external")
        assertTrue(externalOnly.isNotEmpty())
        assertTrue(externalOnly.all { it.category == "external" })
    }
}
