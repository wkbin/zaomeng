package top.wkbin.zaomeng.platform

import android.content.Context
import top.wkbin.zaomeng.backend.NovelConversionForegroundController

/** Android 前台转换控制器：启动前台服务排队生成章节。 */
class AndroidNovelConversionForeground(
    private val context: Context,
) : NovelConversionForeground {
    override fun start(runId: String, sessionId: String, title: String): Boolean =
        NovelConversionForegroundController.start(context, runId, sessionId, title)

    override fun hasNotificationPermission(): Boolean =
        NovelConversionForegroundController.hasNotificationPermission(context)
}
