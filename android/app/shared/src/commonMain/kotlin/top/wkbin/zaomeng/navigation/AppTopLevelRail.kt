package top.wkbin.zaomeng.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.navigation3.runtime.NavKey

/**
 * 顶级导航栏（平板/桌面/展开后的折叠屏）。
 *
 * 只承载顶级目的地；内容页（书卷详情、会话、设置子页等）仍然推入内容区的返回栈。
 */
private data class TopLevelRailItem(
    val destination: NavKey,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

// 静态顶级目的地清单：与组合生命周期无关，文件级只创建一次。
private val topLevelRailItems = listOf(
    TopLevelRailItem(
        destination = BookshelfDestination,
        label = "书卷架",
        icon = Icons.AutoMirrored.Outlined.MenuBook,
        selectedIcon = Icons.AutoMirrored.Filled.MenuBook,
    ),
    TopLevelRailItem(
        destination = SessionsDestination(),
        label = "会话",
        icon = Icons.Outlined.Forum,
        selectedIcon = Icons.Filled.Forum,
    ),
    TopLevelRailItem(
        destination = CardLibraryDestination,
        label = "卡库",
        icon = Icons.Outlined.Style,
        selectedIcon = Icons.Filled.Style,
    ),
    TopLevelRailItem(
        destination = CrossoverDestination,
        label = "跨书卷",
        icon = Icons.Outlined.AutoAwesome,
        selectedIcon = Icons.Filled.AutoAwesome,
    ),
    TopLevelRailItem(
        destination = OnlineLibraryDestination,
        label = "在线书库",
        icon = Icons.Outlined.Public,
        selectedIcon = Icons.Filled.Public,
    ),
    TopLevelRailItem(
        destination = ModelSettingsDestination,
        label = "设置",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopLevelRail(
    selectedDestination: NavKey?,
    onSelectDestination: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            topLevelRailItems.forEach { item ->
                val selected = selectedDestination?.let { it::class == item.destination::class } == true
                TooltipBox(
                    positionProvider = rememberRailTooltipPositionProvider(),
                    tooltip = {
                        PlainTooltip {
                            Text(item.label, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    state = rememberTooltipState(),
                    enableUserInput = !selected,
                ) {
                    NavigationRailItem(
                        selected = selected,
                        onClick = { onSelectDestination(item.destination) },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.icon,
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                        // 未选中只显示图标，选中时才展开文字
                        alwaysShowLabel = false,
                    )
                }
            }
        }
    }
}

/** 提示框显示在图标右侧并垂直居中，贴近侧栏的桌面端交互。 */
@Composable
private fun rememberRailTooltipPositionProvider(): PopupPositionProvider {
    val spacing = with(LocalDensity.current) { 8.dp.roundToPx() }
    return remember(spacing) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.right + spacing
                val y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
                // 右侧放不下时回退到左侧
                return if (x + popupContentSize.width <= windowSize.width) {
                    IntOffset(x, y)
                } else {
                    IntOffset(anchorBounds.left - spacing - popupContentSize.width, y)
                }
            }
        }
    }
}
