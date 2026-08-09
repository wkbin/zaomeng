package top.wkbin.zaomeng.ui.format

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormattingTest {
    @Test
    fun `utc timestamp is displayed in the device time zone`() = withTimeZone {
        assertEquals(
            "2026-07-27 10:15",
            "2026-07-27T02:15:30.123456Z".toLocalDateTimeDisplay(),
        )
    }

    @Test
    fun `explicit offset is converted to the device time zone`() = withTimeZone {
        assertEquals(
            "2026-07-27 10:15",
            "2026-07-27T04:15:30+02:00".toLocalDateTimeDisplay(),
        )
    }

    @Test
    fun `invalid timestamp returns caller fallback`() = withTimeZone {
        assertEquals("未知", "not-a-time".toLocalDateTimeDisplay("未知"))
    }

    private fun withTimeZone(block: () -> Unit) {
        val oldTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            block()
        } finally {
            TimeZone.setDefault(oldTimeZone)
        }
    }
}
