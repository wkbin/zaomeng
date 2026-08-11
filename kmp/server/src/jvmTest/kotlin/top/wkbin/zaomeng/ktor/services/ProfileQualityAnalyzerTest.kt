package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileQualityAnalyzerTest {
    @Test
    fun `detects empty placeholder and repeated fields`() {
        val fields = ProfileQualityAnalyzer.parseMarkdown(
            """
            # PROFILE
            - core_identity: 证据不足
            - soul_goal: 守护家族并查明真相
            - hidden_desire: 守护家族并查明真相
            - speech_style:
            """.trimIndent(),
        )

        val issues = ProfileQualityAnalyzer.analyze(fields)

        assertTrue(issues.any { "core_identity" in it.fields && "占位" in it.message })
        assertTrue(issues.any { "speech_style" in it.fields && "为空" in it.message })
        assertTrue(issues.any { it.fields.containsAll(listOf("soul_goal", "hidden_desire")) && "模板化" in it.message })
    }

    @Test
    fun `detects cross character template reuse for distinctive fields`() {
        val fields = mapOf("identity_anchor" to "永远把同伴放在自己之前")
        val peers = mapOf("乙" to mapOf("identity_anchor" to "永远把同伴放在自己之前"))

        val issues = ProfileQualityAnalyzer.analyze(fields, peers)

        assertTrue(issues.any { "identity_anchor" in it.fields && "乙" in it.message })
    }

    @Test
    fun `explicit contradiction note prevents false conflict warning`() {
        val fields = mapOf(
            "core_traits" to "平时冷静",
            "stress_response" to "绝境时冲动",
            "contradiction_note" to "平时冷静，绝境时会转为冲动。",
        )

        val issues = ProfileQualityAnalyzer.analyze(fields)

        assertFalse(issues.any { "冷静" in it.message && "冲动" in it.message })
    }

    @Test
    fun `contradictory fields without explanation are flagged`() {
        val issues = ProfileQualityAnalyzer.analyze(
            mapOf(
                "core_traits" to "冷静克制",
                "stress_response" to "冲动失控",
            ),
        )

        assertTrue(issues.any { it.severity == "high" && "冷静" in it.message && "冲动" in it.message })
        assertTrue(issues.any { it.severity == "high" && "克制" in it.message && "失控" in it.message })
    }

    @Test
    fun `missing fields reduce completeness once instead of being double penalized`() {
        val fields = ProfileQualityAnalyzer.REPAIRABLE_FIELDS.take(20)
            .associateWith { field -> "$field 的原文支持内容" }
        val issues = ProfileQualityAnalyzer.analyze(fields)

        assertEquals(51, ProfileQualityAnalyzer.qualityScore(fields, issues))
        assertTrue(issues.count(ProfileQualityAnalyzer::isMissingFieldIssue) > 0)
    }

    @Test
    fun `relation excerpt budget grows with selected character count`() {
        assertEquals(120, DistillExecutor.relationSentenceBudget(120, 1))
        assertEquals(300, DistillExecutor.relationSentenceBudget(120, 10))
        assertEquals(50_000, DistillExecutor.relationCharBudget(50_000, 1))
        assertEquals(80_000, DistillExecutor.relationCharBudget(50_000, 10))
    }

    @Test
    fun `distill excerpts carry stable source evidence ids`() {
        val source = "序章。\n宋江说道今日启程。\n旁人应允。\n后来宋江再次归来。"

        val result = DistillExcerptBuilder.build(source, listOf("宋江"), 20, 2_000)

        assertTrue("[S000002] 宋江说道今日启程。" in result.excerpt)
        assertTrue("[S000004] 后来宋江再次归来。" in result.excerpt)
        assertTrue(result.excerptStages.values.any { "[S000002]" in it })
    }
}
