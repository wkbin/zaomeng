package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonaProfileNormalizerTest {
    @Test
    fun `literal placeholders become truly empty fields`() {
        val result = PersonaProfileNormalizer.normalize(
            markdown = """
                # PROFILE
                - core_identity: 留空
                - gender: `证据不足`
                - soul_goal: 寻找失散的亲人
            """.trimIndent(),
            allowedEvidenceIds = emptySet(),
            maxEvidenceIds = 12,
        )

        assertTrue("- core_identity:" in result.markdown)
        assertTrue("- gender:" in result.markdown)
        assertTrue("- soul_goal: 寻找失散的亲人" in result.markdown)
        assertEquals(setOf("core_identity", "gender"), result.clearedFields)
    }

    @Test
    fun `evidence ids are filtered deduplicated and capped instead of failing profile`() {
        val allowed = (1..15).map { "S${it.toString().padStart(6, '0')}" }.toSet()
        val source = (1..15).joinToString("；") { "S${it.toString().padStart(6, '0')}" } + "；S999999；S000001"

        val result = PersonaProfileNormalizer.normalize(
            markdown = "- evidence_source: $source",
            allowedEvidenceIds = allowed,
            maxEvidenceIds = 12,
        )

        val value = result.markdown.substringAfter(": ")
        assertEquals(12, Regex("S\\d{6}").findAll(value).count())
        assertTrue("S000012" in value)
        assertTrue("S000013" !in value)
        assertTrue("S999999" !in value)
        assertEquals(5, result.removedEvidenceCount)
    }
}
