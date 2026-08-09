package top.wkbin.zaomeng.app.shared

import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import org.jetbrains.compose.resources.ExperimentalResourceApi
import top.wkbin.zaomeng.platform.PromptSource
import zaomeng.app.shared.generated.resources.Res

/**
 * 基于 CMP composeResources 的统一提示词读取（方案 A）。
 *
 * 提示词打包在 shared 的 composeResources/files/prompts 下，Android 与桌面
 * 两端用同一份资源与同一段代码。资源打包后为静态内容（mtime=0，缓存永久有效）。
 *
 * 开发回退：设置环境变量 ZAOMENG_PROMPTS_DIR 指向仓库根 prompts/ 时，
 * 直接读文件系统（改提示词无需重新打包）。
 */
@OptIn(ExperimentalResourceApi::class)
class ResPromptSource(
    private val env: (String) -> String? = ::envVar,
) : PromptSource {
    private val fs = FileSystem.SYSTEM

    override fun read(relativePath: String): Pair<String, Long>? {
        val devDir = env("ZAOMENG_PROMPTS_DIR")
        if (!devDir.isNullOrBlank()) {
            val file = "$devDir/$relativePath".toPath()
            if (fs.exists(file)) {
                val text = fs.source(file).buffer().use { it.readUtf8() }
                return text to (fs.metadataOrNull(file)?.lastModifiedAtMillis ?: 0L)
            }
            return null
        }

        return runBlocking {
            runCatching {
                val bytes = Res.readBytes("files/prompts/$relativePath")
                bytes.decodeToString() to 0L
            }.getOrNull()
        }
    }

    override fun lastModified(relativePath: String): Long? {
        val devDir = env("ZAOMENG_PROMPTS_DIR")
        if (!devDir.isNullOrBlank()) {
            val file = "$devDir/$relativePath".toPath()
            return if (fs.exists(file)) {
                fs.metadataOrNull(file)?.lastModifiedAtMillis ?: 0L
            } else {
                null
            }
        }
        // composeResources 打包后为静态内容（mtime=0）：探测资源是否存在
        return runBlocking {
            runCatching {
                Res.readBytes("files/prompts/$relativePath")
                0L
            }.getOrNull()
        }
    }
}
