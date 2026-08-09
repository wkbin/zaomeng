package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import platform.Foundation.NSURL
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/** iOS 用系统浏览器/外部应用打开 URL。 */
@Composable
actual fun rememberOpenExternalUrl(): (String) -> Unit = { url ->
    NSURL.URLWithString(url)?.let { nsUrl ->
        UIApplication.sharedApplication.openURL(nsUrl, emptyMap(), null)
    }
}

/** iOS 没有原生 Toast，用 1.5 秒后自动消失的 Alert 模拟短暂提示。 */
@Composable
actual fun rememberToast(): (String) -> Unit = { message ->
    foregroundViewController()?.let { presenter ->
        val alert = UIAlertController.alertControllerWithTitle(
            title = null,
            message = message,
            preferredStyle = UIAlertControllerStyleAlert,
        )
        presenter.presentViewController(alert, animated = true, completion = null)
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, 1_500_000_000L),
            dispatch_get_main_queue(),
        ) {
            alert.dismissViewControllerAnimated(true, completion = null)
        }
    }
}
