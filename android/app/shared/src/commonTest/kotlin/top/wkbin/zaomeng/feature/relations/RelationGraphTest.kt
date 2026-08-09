package top.wkbin.zaomeng.feature.relations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.RelationItemDto

class RelationGraphTest {
    @Test
    fun graphNodesDeduplicateRelationParticipants() {
        val nodes = relationGraphNodes(
            listOf(
                RelationItemDto(pairKey = "a_b", characters = listOf("甲", "乙")),
                RelationItemDto(pairKey = "b_c", characters = listOf("乙", "丙")),
            ),
        )

        assertEquals(listOf("甲", "乙", "丙"), nodes.map { it.name })
        assertTrue(nodes.all { it.x in 0f..1f && it.y in 0f..1f })
    }
}
