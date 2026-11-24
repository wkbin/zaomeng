package top.wkbin.zaomeng.navigation

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey

private data class MainDestination(val key: NavKey, val label: String, val icon: ImageVector)

private val mainDestinations = listOf(
    MainDestination(BookshelfDestination, "书架", Icons.AutoMirrored.Outlined.MenuBook),
    MainDestination(SessionsDestination(), "对话", Icons.Outlined.Forum),
    MainDestination(MoreDestination, "更多", Icons.Outlined.MoreHoriz),
)

@Composable
internal fun AppTopLevelRail(selected: NavKey?, onSelect: (NavKey) -> Unit) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        header = {
            Text(
                "造梦",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        },
    ) {
        mainDestinations.forEach { item ->
            NavigationRailItem(
                selected = selected == item.key,
                onClick = { onSelect(item.key) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
internal fun AppTopLevelBar(selected: NavKey?, onSelect: (NavKey) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        mainDestinations.forEach { item ->
            NavigationBarItem(
                selected = selected == item.key,
                onClick = { onSelect(item.key) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}
