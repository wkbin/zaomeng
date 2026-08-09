package top.wkbin.zaomeng.platform

import android.content.Context
import top.wkbin.zaomeng.backend.DistillationForegroundController

/** Android 蒸馏前台提示：启动前台服务监控运行中的蒸馏任务。 */
class AndroidDistillationForeground(
    private val context: Context,
) : DistillationForeground {
    override fun start() {
        DistillationForegroundController.start(context)
    }

    override fun stopAll() {
        DistillationForegroundController.stopAll(context)
    }

    override fun hasNotificationPermission(): Boolean =
        DistillationForegroundController.hasNotificationPermission(context)
}
