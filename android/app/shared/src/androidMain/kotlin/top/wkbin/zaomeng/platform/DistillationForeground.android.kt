package top.wkbin.zaomeng.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** Android 蒸馏前台提示：权限检查真实化；前台服务暂为 no-op（TODO 移植旧实现）。 */
class AndroidDistillationForeground(
    private val context: Context,
) : DistillationForeground {
    override fun start() = Unit

    override fun stopAll() = Unit

    override fun hasNotificationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
