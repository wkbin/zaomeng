package top.wkbin.zaomeng.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        Text(
            text = "造梦",
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        topLevelRailItems.forEach { item ->
            val selected = selectedDestination?.let { it::class == item.destination::class } == true
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
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
