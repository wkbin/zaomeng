package top.wkbin.zaomeng.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.ui.theme.AppDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreScreen(
    onOpenCards: () -> Unit,
    onOpenCrossover: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("更多") }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(AppDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
            ) {
                Text("创作工具", style = MaterialTheme.typography.titleMedium)
                Card {
                    MoreItem("在线书库", "查找并导入已有书卷", Icons.Outlined.Public, onOpenLibrary)
                    HorizontalDivider()
                    MoreItem("创作卡库", "管理可复用的场景和身份设定", Icons.Outlined.Style, onOpenCards)
                    HorizontalDivider()
                    MoreItem("跨书卷共演", "组合来自不同书卷的人物 · 实验功能", Icons.Outlined.AutoAwesome, onOpenCrossover)
                }
                Text("应用设置", style = MaterialTheme.typography.titleMedium)
                Card {
                    MoreItem("设置", "模型、外观、聊天显示和插件", Icons.Outlined.Settings, onOpenSettings)
                }
            }
        }
    }
}

@Composable
private fun MoreItem(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
