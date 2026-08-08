package top.wkbin.zaomeng.app.shared

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResPromptSourceTest {
    private val source = ResPromptSource(env = { null })

    /**
     * 注意：本测试放在 jvmTest（桌面路径）——CMP 的 Android host test 是纯 JVM
     * 单测，没有 Context/AssetManager，读不到打包进 assets 的 composeResources；
     * 桌面端资源打进 jar 可直接读取。Android 端由真机/桌面端实际运行覆盖。
     */
    @Test
    fun readsPackagedYamlPrompt() {
        val director = source.read("dialogue/director.yaml")
        assertNotNull(director, "dialogue/director.yaml 应打包进 composeResources")
        assertTrue(director.first.isNotBlank())
    }

    @Test
    fun readsPackagedDistillMarkdown() {
        // 此前 JVM 端从仓库根 prompts/ 读不到 distill md，统一到 Res 后必须可读
        val schema = source.read("distill/output_schema.md")
        assertNotNull(schema, "distill/output_schema.md 应打包进 composeResources")
        assertTrue(schema.first.isNotBlank())
    }

    @Test
    fun missingPromptReturnsNull() {
        assertNull(source.read("dialogue/not-exist.yaml"))
    }
}
