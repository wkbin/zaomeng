package top.wkbin.zaomeng.platform

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：桌面端（JVM）提示词读取必须覆盖蒸馏 md。
 *
 * 仓库根 prompts/ 只有 distill/guidance.yaml，蒸馏 md 在 zaomeng-skill 的
 * prompts/（distill_prompt、relation_prompt）与 references/（output_schema、
 * logic_constraint、style_differ、validation_policy）子目录；回退路径必须找到它们，
 * 否则桌面端蒸馏 payload 的参考文档为空。
 */
class JvmPromptSourceTest {
    private val source = JvmPromptSource()

    private fun assertReadable(relativePath: String) {
        val entry = source.read(relativePath)
        assertNotNull("应能读取 $relativePath", entry)
        assertTrue("$relativePath 不应为空", entry!!.first.isNotBlank())
    }

    @Test
    fun `reads dialogue and chapters prompts from repo prompts`() {
        assertReadable("dialogue/director.yaml")
        assertReadable("dialogue/turn_system.yaml")
        assertReadable("chapters/novel_rewrite.yaml")
    }

    @Test
    fun `reads distill reference markdown from zaomeng-skill`() {
        assertReadable("distill/distill_prompt.md")
        assertReadable("distill/relation_prompt.md")
        assertReadable("distill/output_schema.md")
        assertReadable("distill/style_differ.md")
        assertReadable("distill/logic_constraint.md")
        assertReadable("distill/validation_policy.md")
    }
}
