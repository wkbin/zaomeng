package top.wkbin.zaomeng.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** Android 前台转换控制器：权限检查真实化；前台服务暂为 no-op（TODO 移植旧实现）。 */
class AndroidNovelConversionForeground(
    private val context: Context,
) : NovelConversionForeground {
    override fun start(runId: String, sessionId: String, title: String): Boolean = true

    override fun hasNotificationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
