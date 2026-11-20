package top.wkbin.zaomeng.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseUpdateCheckerTest {
    @Test
    fun `semantic version comparison accepts newer versions only`() {
        assertTrue(ReleaseUpdateChecker.isNewerVersion("2.2.0", "2.1.2"))
        assertTrue(ReleaseUpdateChecker.isNewerVersion("2.1.3", "2.1.2"))
        assertFalse(ReleaseUpdateChecker.isNewerVersion("2.1.2", "2.1.2"))
        assertFalse(ReleaseUpdateChecker.isNewerVersion("2.0.9", "2.1.2"))
    }

    @Test
    fun `release digest normalization accepts sha256 hex only`() {
        val hex = "a".repeat(64)
        assertEquals(hex, AppUpdateDigest.normalize(hex))
        assertEquals(hex, AppUpdateDigest.normalize("sha256:$hex"))
        assertEquals(hex, AppUpdateDigest.normalize("SHA256:${hex.uppercase()}"))
        assertEquals(hex, AppUpdateDigest.normalize("  sha256:$hex  "))

        assertNull(AppUpdateDigest.normalize(""))
        assertNull(AppUpdateDigest.normalize("sha512:$hex"))
        assertNull(AppUpdateDigest.normalize(hex.dropLast(1)))
        assertNull(AppUpdateDigest.normalize("sha256:${"g".repeat(64)}"))
    }

    @Test
    fun `digest comparison rejects unknown or mismatched expectations`() {
        val hex = "0123456789abcdef".repeat(4)
        assertTrue(AppUpdateDigest.matches("sha256:${hex.uppercase()}", hex))
        assertFalse(AppUpdateDigest.matches("", hex))
        assertFalse(AppUpdateDigest.matches(hex, hex.dropLast(1) + "0"))
    }
}
