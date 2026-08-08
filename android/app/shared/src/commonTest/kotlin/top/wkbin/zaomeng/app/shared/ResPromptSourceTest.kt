package top.wkbin.zaomeng.app.shared

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResPromptSourceTest {
    private val source = ResPromptSource(env = { null })

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
