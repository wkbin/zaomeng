package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
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
}
