package top.wkbin.zaomeng.platform

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 打包后的桌面安装不在仓库目录：数据目录与提示词必须回退到可写路径/classpath。 */
class JvmServerPlatformTest {
    @Test
    fun `data root falls back to user dir when no repo`() {
        val tmp = createTempDirectory("zaomeng-no-repo")
        try {
            val root = defaultDataRoot(tmp.toFile())
            val normalized = root.toString().replace('\\', '/')
            assertTrue(
                normalized.endsWith(".zaomeng/server") && !normalized.contains("Program Files"),
                "打包环境应落到用户数据目录: $root",
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `data root stays in repo during development`() {
        val repo = createTempDirectory("zaomeng-repo")
        try {
            File(repo.toFile(), "prompts").mkdirs()
            val root = defaultDataRoot(repo.toFile())
            assertTrue(
                root.toString().replace('\\', '/').endsWith("/zaomeng-data"),
                "开发环境应继续使用仓库 zaomeng-data: $root",
            )
        } finally {
            repo.toFile().deleteRecursively()
        }
    }

    @Test
    fun `prompt source falls back to bundled classpath resources`() {
        val tmp = createTempDirectory("zaomeng-prompt-no-repo")
        try {
            val source = JvmPromptSource(cwd = tmp.toFile())
            val entry = source.read("dialogue/fallback_probe.yaml")
            assertNotNull(entry, "打包后应能从 classpath 读到提示词")
            assertEquals("probe: ok", entry.first.trim())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
