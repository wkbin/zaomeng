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

    @Test
    fun `secrets do not persist as plaintext when OS protection is available`() {
        val dir = Files.createTempDirectory("zaomeng-secure-store-encrypted")
        val target = dir.resolve("secrets.properties")
        val file = target.toAbsolutePath().toString().toPath()
        try {
            FileSecureKeyValueStore(file).put("model_api_key", "sk-secret-value")
            val raw = Files.readString(target)
            if (SecretCodec.platformDefault().canEncrypt) {
                // Properties.store 会把 ':' 转义成 '\:'，这里只断言存在密文标记。
                assertTrue("密文应带 dpapi 标记：$raw", raw.contains("dpapi"))
                assertTrue("落盘内容不得包含明文密钥", !raw.contains("sk-secret-value"))
                assertEquals("sk-secret-value", FileSecureKeyValueStore(file).get("model_api_key"))
            } else {
                // 非 Windows：退化为受限权限文件，读取仍应一致。
                assertEquals("sk-secret-value", FileSecureKeyValueStore(file).get("model_api_key"))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy plaintext file migrates on first load`() {
        val dir = Files.createTempDirectory("zaomeng-secure-store-legacy")
        val target = dir.resolve("secrets.properties")
        Files.writeString(target, "model_api_key=sk-legacy-1\nother_key=plain-2\n")
        val file = target.toAbsolutePath().toString().toPath()
        try {
            val store = FileSecureKeyValueStore(file)
            assertEquals("sk-legacy-1", store.get("model_api_key"))
            assertEquals("plain-2", store.get("other_key"))

            if (SecretCodec.platformDefault().canEncrypt) {
                val raw = Files.readString(target)
                assertTrue("迁移后不得残留明文", !raw.contains("sk-legacy-1") && !raw.contains("plain-2"))
                val backups = Files.list(dir).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".legacy") }.toList()
                }
                assertEquals(
                    "应保留一个明文备份用于回滚，实际: ${backups.map { it.fileName }}",
                    1,
                    backups.size,
                )
                assertTrue(Files.readString(backups.single()).contains("sk-legacy-1"))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `undecryptable values are treated as missing`() {
        val dir = Files.createTempDirectory("zaomeng-secure-store-broken")
        val target = dir.resolve("secrets.properties")
        Files.writeString(target, "model_api_key=dpapi:not-a-valid-ciphertext\n")
        val file = target.toAbsolutePath().toString().toPath()
        try {
            assertNull(FileSecureKeyValueStore(file).get("model_api_key"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
