package top.wkbin.zaomeng.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun newerReleaseVersionsAreDetected() {
        assertTrue(isNewerVersion("v1.1", "1.0.1"))
        assertTrue(isNewerVersion("1.2.0", "1.1.9"))
        assertTrue(isNewerVersion("v2", "1.9.9"))
    }

    @Test
    fun currentOrOlderReleaseVersionsAreIgnored() {
        assertFalse(isNewerVersion("v1.1", "1.1.0"))
        assertFalse(isNewerVersion("1.0.9", "1.1.0"))
        assertFalse(isNewerVersion("preview", "1.1.0"))
    }

    @Test
    fun parsesNewerReleaseAndPrefersArm64Asset() {
        val update = parseLatestRelease(
            """{"tag_name":"v1.2.0","body":"- 修复问题","assets":[{"name":"zaomeng.apk","browser_download_url":"https://github.com/wkbin/zaomeng/releases/download/v1.2.0/zaomeng.apk"},{"name":"zaomeng-arm64-v8a.apk","browser_download_url":"https://github.com/wkbin/zaomeng/releases/download/v1.2.0/zaomeng-arm64-v8a.apk"}]}""",
            "1.1.0",
        )

        requireNotNull(update)
        assertEquals("1.2.0", update.version)
        assertEquals("https://github.com/wkbin/zaomeng/releases/download/v1.2.0/zaomeng-arm64-v8a.apk", update.downloadUrl)
        assertEquals("- 修复问题", update.releaseNotes)
    }

    @Test
    fun `accepts only configured GitHub release download urls`() {
        assertTrue(
            AppUpdateManager.isAllowedUpdateDownloadUrl(
                "https://github.com/wkbin/zaomeng/releases/download/v1.2.0/zaomeng-arm64-v8a.apk",
            ),
        )
        assertFalse(AppUpdateManager.isAllowedUpdateDownloadUrl("http://github.com/wkbin/zaomeng/releases/download/v1.2.0/zaomeng.apk"))
        assertFalse(AppUpdateManager.isAllowedUpdateDownloadUrl("https://example.com/zaomeng.apk"))
        assertFalse(AppUpdateManager.isAllowedUpdateDownloadUrl("https://github.com/other/releases/download/v1.2.0/zaomeng.apk"))
        assertFalse(AppUpdateManager.isAllowedUpdateDownloadUrl("https://github.com/wkbin/zaomeng/archive/refs/tags/v1.2.0.zip"))
    }
}
