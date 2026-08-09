package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.useContents
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad

/**
 * iOS 系统分享：弹出 UIActivityViewController。
 * iPad 必须提供 popover 锚点（sourceView/sourceRect），否则系统会崩溃。
 */
@Composable
actual fun rememberShareText(): (String) -> Unit = { text ->
    foregroundViewController()?.let { presenter ->
        val activityController = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null,
        )
        if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
            val bounds = presenter.view.bounds
            activityController.popoverPresentationController?.sourceView = presenter.view
            activityController.popoverPresentationController?.sourceRect = bounds.useContents {
                CGRectMake(size.width / 2.0, size.height / 2.0, 1.0, 1.0)
            }
        }
        presenter.presentViewController(activityController, animated = true, completion = null)
    }
}
