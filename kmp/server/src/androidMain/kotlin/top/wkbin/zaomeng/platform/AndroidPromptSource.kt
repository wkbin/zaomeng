package top.wkbin.zaomeng.platform

import android.content.Context
import java.io.File

/**
 * Android 提示词资源读取：assets 优先（打包进 APK），文件系统回退（开发机/测试）。
 *
 * 与迁移前 PromptLoader 的读取顺序一致：
 * 1. assets.open(relativePath)
 * 2. <projectRoot>/prompts/<relativePath>
 * 3. <projectRoot>/zaomeng-skill/<relativePath 去掉 distill/>（蒸馏 md 的开发机路径）
 */
class AndroidPromptSource(private val context: Context) : PromptSource {
    private val promptsRoot: File by lazy {
        // 开发机回退路径：prompts 目录位于 Android 项目上级
        val filesDir = context.filesDir
        val projectRoot = filesDir.parentFile?.parentFile?.parentFile?.parentFile
        projectRoot?.resolve("prompts")
            ?: throw IllegalStateException("Could not locate prompts directory")
    }
    private val projectRoot: File by lazy {
        val filesDir = context.filesDir
        filesDir.parentFile?.parentFile?.parentFile?.parentFile
            ?: throw IllegalStateException("Could not locate project root")
    }

    private fun resolveFallbackFile(relativePath: String): File? {
        // 2. 文件系统回退
        val configFile = promptsRoot.resolve(relativePath)
        if (configFile.exists()) return configFile
        // 3. zaomeng-skill（蒸馏 md 按 skill 布局放在 prompts/ 与 references/ 子目录）
        val skillName = relativePath.removePrefix("distill/")
        return listOf(
            projectRoot.resolve("zaomeng-skill").resolve(skillName),
            projectRoot.resolve("zaomeng-skill/prompts").resolve(skillName),
            projectRoot.resolve("zaomeng-skill/references").resolve(skillName),
        ).firstOrNull { it.exists() }
    }

    override fun read(relativePath: String): Pair<String, Long>? {
        // 1. assets 优先（prompts 目录整体打包为 assets 根内容，mtime 固定 0）
        runCatching {
            val input = context.assets.open(relativePath)
            val text = input.bufferedReader().use { it.readText() }
            return text to 0L
        }
        val file = resolveFallbackFile(relativePath) ?: return null
        return file.readText() to file.lastModified()
    }

    override fun lastModified(relativePath: String): Long? {
        // assets 打包后视为静态（mtime=0）；open 只做 zip 条目查找，不读内容
        runCatching {
            context.assets.open(relativePath).use { return 0L }
        }
        return resolveFallbackFile(relativePath)?.lastModified()
    }
}
