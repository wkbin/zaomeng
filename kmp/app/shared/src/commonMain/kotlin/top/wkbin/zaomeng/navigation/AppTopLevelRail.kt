package top.wkbin.zaomeng.navigation

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.FloatingNavigationBar
import io.github.suqi8.coui.kmp.basic.FloatingNavigationBarDefaults
import io.github.suqi8.coui.kmp.basic.FloatingNavigationBarItem
import io.github.suqi8.coui.kmp.basic.NavigationRail
import io.github.suqi8.coui.kmp.basic.NavigationRailItem
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.NotesFill
import io.github.suqi8.coui.kmp.theme.COUITheme

internal data class MainTab(val label: String, val icon: ImageVector)

/** 顶层 tab 的顺序，pager 页序与它一一对应。 */
internal val mainTabs = listOf(
    MainTab("书架", COUIIcons.NotesFill),
    MainTab("对话", ChatBubbleFilled),
    MainTab("更多", COUIIcons.More),
)

@Composable
internal fun AppTopLevelRail(selectedIndex: Int, onSelect: (Int) -> Unit) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        header = {
            Text(
                text = "造梦",
                style = COUITheme.textStyles.headlineXS.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(vertical = 16.dp),
            )
        },
    ) {
        mainTabs.forEachIndexed { index, item ->
            NavigationRailItem(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                icon = item.icon,
                label = item.label,
            )
        }
    }
}

@Composable
internal fun AppTopLevelBar(selectedIndex: Int, onSelect: (Int) -> Unit) {
    // 底栏保持原来的 COUI 悬浮胶囊样式；只把图标换成实心版本，提高辨识度。
    FloatingNavigationBar(color = FloatingNavigationBarDefaults.color().copy(alpha = 0.86f)) {
        mainTabs.forEachIndexed { index, item ->
            FloatingNavigationBarItem(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                icon = item.icon,
                label = item.label,
            )
        }
    }
}
