package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.platform.PromptSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PromptLoader 缓存行为测试：
 * - mtime 未变时命中缓存，只探测 mtime、不再整文件读取；
 * - mtime 变化时重新读取并解析。
 */
class PromptLoaderCacheTest {
    private class CountingPromptSource : PromptSource {
        var readCount = 0
        var probeCount = 0

        override fun read(relativePath: String): Pair<String, Long>? {
            readCount++
            return "key: value" to 1000L
        }

        override fun lastModified(relativePath: String): Long? {
            probeCount++
            return 1000L
        }
    }

    private class MutablePromptSource : PromptSource {
        var mtime = 1000L
        var readCount = 0

        override fun read(relativePath: String): Pair<String, Long>? {
            readCount++
            return "key: value" to mtime
        }

        override fun lastModified(relativePath: String): Long? = mtime
    }

    @Test
    fun `cache hit avoids file read`() {
        val source = CountingPromptSource()
        val loader = PromptLoader(source)

        repeat(21) {
            loader.getPromptText("dialogue", "turn_system", "key")
        }

        assertEquals(1, source.readCount, "缓存命中后不应再读文件内容")
        assertTrue(source.probeCount >= 21, "每次调用都应做 mtime 探测")
    }

    @Test
    fun `mtime change triggers reload`() {
        val source = MutablePromptSource()
        val loader = PromptLoader(source)

        loader.getPromptText("dialogue", "turn_system", "key")
        source.mtime = 2000L
        loader.getPromptText("dialogue", "turn_system", "key")

        assertEquals(2, source.readCount, "mtime 变化后应重新读取")
    }
}
