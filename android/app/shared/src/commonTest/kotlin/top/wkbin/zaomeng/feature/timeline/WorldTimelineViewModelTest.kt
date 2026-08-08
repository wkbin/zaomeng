package top.wkbin.zaomeng.feature.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.WorldFactDto

class WorldTimelineViewModelTest {
    @Test
    fun `fact edit request keeps user managed fields`() {
        val fact = WorldFactDto(
            factId = "fact-1",
            category = "commitment",
            summary = "甲答应在日出前回来。",
            characters = listOf("甲"),
            location = "山门",
            timeHint = "夜晚",
            locked = false,
            active = false,
        )

        val request = fact.toRequest(locked = true)

        assertEquals("commitment", request.category)
        assertEquals(listOf("甲"), request.characters)
        assertEquals("山门", request.location)
        assertEquals("夜晚", request.timeHint)
        assertTrue(request.locked)
        assertFalse(request.active)
    }
}
