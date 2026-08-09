package top.wkbin.zaomeng.platform

import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 回归测试：okio Sink 包装的 OutputStream 必须 flush/close，
 * 否则 Properties.store() 的缓冲数据永远不落盘（桌面端「保存 API Key 后仍显示未配置模型」的根因）。
 */
class FileSecureKeyValueStoreTest {
    @Test
    fun `put persists entries to disk across store instances`() {
        val dir = Files.createTempDirectory("zaomeng-secure-store")
        val file = dir.resolve("secrets.properties").toAbsolutePath().toString().toPath()
        try {
            FileSecureKeyValueStore(file).put("model_api_key", "sk-test-123")

            assertTrue(
                "secrets.properties 不应为空，说明 store() 确实写盘了",
                Files.size(dir.resolve("secrets.properties")) > 0,
            )

            // 新建实例从磁盘重读，验证数据不是仅停留在内存缓冲。
            val reloaded = FileSecureKeyValueStore(file)
            assertEquals("sk-test-123", reloaded.get("model_api_key"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `remove deletes only the target key`() {
        val dir = Files.createTempDirectory("zaomeng-secure-store-remove")
        val file = dir.resolve("secrets.properties").toAbsolutePath().toString().toPath()
        try {
            val store = FileSecureKeyValueStore(file)
            store.put("k1", "v1")
            store.put("k2", "v2")
            store.remove("k1")

            val reloaded = FileSecureKeyValueStore(file)
            assertNull(reloaded.get("k1"))
            assertEquals("v2", reloaded.get("k2"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
