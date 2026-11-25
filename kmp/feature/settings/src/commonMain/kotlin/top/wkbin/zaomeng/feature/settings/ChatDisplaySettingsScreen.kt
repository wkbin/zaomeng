package top.wkbin.zaomeng.feature.settings

import androidx.compose.runtime.Composable
import okio.Path
import top.wkbin.zaomeng.ui.coui.CouiPage
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun ChatDisplaySettingsScreen(filesDir: Path, onBack: () -> Unit) {
    CouiPage(
        title = "聊天显示",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
    ) {
        item { ChatDisplaySettingsCard(filesDir = filesDir) }
    }
}
