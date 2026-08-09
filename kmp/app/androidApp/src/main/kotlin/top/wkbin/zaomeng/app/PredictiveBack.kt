package top.wkbin.zaomeng.app

import android.content.pm.ApplicationInfo
import android.annotation.SuppressLint
import org.lsposed.hiddenapibypass.HiddenApiBypass
import top.wkbin.zaomeng.platform.predictiveBackSupported

/**
 * 预测性返回手势开关（参考 KernelSU PR #3208）。
 *
 * Android 16 起系统对 targetSdk 36 默认开启预测返回，manifest 属性无法做成运行时开关；
 * 因此通过隐藏 API 直接改写 ApplicationInfo.enableOnBackInvokedCallback，
 * 在 Activity/Window 创建前调用生效，运行时切换后需 recreate() 重建界面。
 */
object PredictiveBack {
    /** 需在 Activity/Window 创建前调用；失败时静默降级为系统默认行为。 */
    @SuppressLint("NewApi")
    fun setEnabled(applicationInfo: ApplicationInfo, enabled: Boolean) {
        if (!predictiveBackSupported()) return
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback",
            )
            val method = ApplicationInfo::class.java.getDeclaredMethod(
                "setEnableOnBackInvokedCallback",
                Boolean::class.javaPrimitiveType,
            )
            method.invoke(applicationInfo, enabled)
        }
    }
}
