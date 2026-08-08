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

    override fun read(relativePath: String): Pair<String, Long>? {
        // 1. assets 优先（prompts 目录整体打包为 assets 根内容）
        runCatching {
            val input = context.assets.open(relativePath)
            val text = input.bufferedReader().use { it.readText() }
            return text to 0L
        }
        // 2. 文件系统回退
        val configFile = promptsRoot.resolve(relativePath)
        if (configFile.exists()) {
            return configFile.readText() to configFile.lastModified()
        }
        // 3. zaomeng-skill（蒸馏 md）
        val skillFile = projectRoot.resolve("zaomeng-skill").resolve(relativePath.removePrefix("distill/"))
        if (skillFile.exists()) {
            return skillFile.readText() to skillFile.lastModified()
        }
        return null
    }
}
