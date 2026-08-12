package top.wkbin.zaomeng.data.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseUpdateCheckerTest {
    @Test
    fun `semantic version comparison accepts newer versions only`() {
        assertTrue(ReleaseUpdateChecker.isNewerVersion("2.2.0", "2.1.2"))
        assertTrue(ReleaseUpdateChecker.isNewerVersion("2.1.3", "2.1.2"))
        assertFalse(ReleaseUpdateChecker.isNewerVersion("2.1.2", "2.1.2"))
        assertFalse(ReleaseUpdateChecker.isNewerVersion("2.0.9", "2.1.2"))
    }
}
