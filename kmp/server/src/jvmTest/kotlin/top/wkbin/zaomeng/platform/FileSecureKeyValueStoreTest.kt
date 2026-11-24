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
            val codec = DesktopSecretCodec.platformDefault()
            if (codec.canEncrypt) {
                // Properties.store 会把 ':' 转义成 '\:'，这里只断言存在保护标记。
                val marker = checkNotNull(codec.marker)
                assertTrue("密文应带保护标记 $marker：$raw", raw.replace("\\:", ":").contains(marker))
                assertTrue("落盘内容不得包含明文密钥", !raw.contains("sk-secret-value"))
                assertEquals("sk-secret-value", FileSecureKeyValueStore(file).get("model_api_key"))
            } else {
                // 无系统级保护：退化为受限权限文件，读取仍应一致。
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

            if (DesktopSecretCodec.platformDefault().canEncrypt) {
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
        // 两种标记都覆盖：本机没有对应后端时必须按缺失处理，不能把密文当密钥发出去。
        // 同时放一条真正的明文，确认迁移不会把外来密文当明文一起“洗白”。
        Files.writeString(target, "model_api_key=dpapi:not-a-valid-ciphertext\nother=keychain:\nlegacy=plain-value\n")
        val file = target.toAbsolutePath().toString().toPath()
        try {
            val store = FileSecureKeyValueStore(file)
            assertNull(store.get("model_api_key"))
            assertNull(store.get("other"))
            assertEquals("plain-value", store.get("legacy"))
            // 外来密文必须原样留在文件里（或至少不能变成可解出的值）。
            val reopened = FileSecureKeyValueStore(file)
            assertNull(reopened.get("model_api_key"))
            assertNull(reopened.get("other"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 模拟 Keychain 这类“值不落文件”的后端，验证 key 感知的编解码与删除联动。 */
    private class ExternalBackendCodec : SecretCodec {
        override val protection: String = "test-backend"
        override val marker: String = SecretMarkers.KEYCHAIN
        val values = mutableMapOf<String, String>()
        val forgotten = mutableListOf<String>()

        override fun encode(key: String, plain: String): String {
            values[key] = plain
            return marker
        }

        override fun reveal(key: String, stored: String): String? =
            if (isEncoded(stored)) values[key] else PlaintextSecretCodec.reveal(key, stored)

        override fun forget(key: String) {
            forgotten += key
            values.remove(key)
        }
    }

    @Test
    fun `key aware backend keeps values out of the file and forgets on remove`() {
        val dir = Files.createTempDirectory("zaomeng-secure-store-external")
        val target = dir.resolve("secrets.properties")
        Files.writeString(target, "legacy_key=legacy-secret\n")
        val file = target.toAbsolutePath().toString().toPath()
        try {
            val backend = ExternalBackendCodec()
            val store = FileSecureKeyValueStore(file, backend)

            // 构造时迁移：值进后端，文件只剩标记。
            assertEquals("legacy-secret", store.get("legacy_key"))
            val raw = Files.readString(target)
            assertTrue("文件不得残留明文：$raw", !raw.contains("legacy-secret"))

            store.put("new_key", "new-secret")
            assertEquals("new-secret", store.get("new_key"))
            assertEquals(mapOf("legacy_key" to "legacy-secret", "new_key" to "new-secret"), store.entries())

            // 重新打开：只凭文件标记 + 后端即可恢复，不依赖进程内状态。
            val reopened = FileSecureKeyValueStore(file, backend)
            assertEquals("new-secret", reopened.get("new_key"))

            reopened.remove("new_key")
            assertNull(reopened.get("new_key"))
            assertEquals(listOf("new_key"), backend.forgotten)
            assertEquals("legacy-secret", reopened.get("legacy_key"))
            assertTrue(!Files.readString(target).contains("new_key"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
