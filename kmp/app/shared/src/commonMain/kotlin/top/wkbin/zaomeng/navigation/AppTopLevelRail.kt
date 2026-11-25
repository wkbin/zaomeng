package top.wkbin.zaomeng.navigation

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import io.github.suqi8.coui.kmp.basic.FloatingNavigationBar
import io.github.suqi8.coui.kmp.basic.FloatingNavigationBarDefaults
import io.github.suqi8.coui.kmp.basic.FloatingNavigationBarItem
import io.github.suqi8.coui.kmp.basic.NavigationRail
import io.github.suqi8.coui.kmp.basic.NavigationRailItem
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Messages
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.theme.COUITheme

private data class MainDestination(val key: NavKey, val label: String, val icon: ImageVector)

private val mainDestinations = listOf(
    MainDestination(BookshelfDestination, "书架", COUIIcons.Notes),
    MainDestination(SessionsDestination(), "对话", COUIIcons.Messages),
    MainDestination(MoreDestination, "更多", COUIIcons.More),
)

@Composable
internal fun AppTopLevelRail(selected: NavKey?, onSelect: (NavKey) -> Unit) {
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
        mainDestinations.forEach { item ->
            NavigationRailItem(
                selected = selected == item.key,
                onClick = { onSelect(item.key) },
                icon = item.icon,
                label = item.label,
            )
        }
    }
}

@Composable
internal fun AppTopLevelBar(selected: NavKey?, onSelect: (NavKey) -> Unit) {
    FloatingNavigationBar(color = FloatingNavigationBarDefaults.color().copy(alpha = 0.86f)) {
        mainDestinations.forEach { item ->
            FloatingNavigationBarItem(
                selected = selected == item.key,
                onClick = { onSelect(item.key) },
                icon = item.icon,
                label = item.label,
            )
        }
    }
}
