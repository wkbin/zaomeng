package top.wkbin.zaomeng.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Merge
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.icon.extended.Theme
import io.github.suqi8.coui.kmp.icon.extended.WorldClock
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.PreferenceCategory
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.ui.coui.CouiPage
import top.wkbin.zaomeng.ui.coui.isWideWindow
import top.wkbin.zaomeng.ui.theme.AppDimens

@Composable
internal fun MoreScreen(
    onOpenCards: () -> Unit,
    onOpenCrossover: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    CouiPage(
        title = "更多",
        wide = isWideWindow(),
    ) {
        item(key = "more_destinations") {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                ) {
                    PreferenceCategory(title = "创作工具")
                    Card(modifier = Modifier.padding(horizontal = AppDimens.screenPadding)) {
                        CardListGroup {
                            item(key = "library") {
                                MoreItem("在线书库", "查找并导入已有书卷", COUIIcons.WorldClock, onOpenLibrary)
                            }
                            item(key = "cards") {
                                MoreItem("创作卡库", "管理可复用的场景和身份设定", COUIIcons.Theme, onOpenCards)
                            }
                            item(key = "crossover") {
                                MoreItem("跨书卷共演", "组合来自不同书卷的人物 · 实验功能", COUIIcons.Merge, onOpenCrossover)
                            }
                        }
                    }
                    PreferenceCategory(title = "应用设置")
                    Card(modifier = Modifier.padding(horizontal = AppDimens.screenPadding)) {
                        CardListGroup {
                            item(key = "settings") {
                                MoreItem("设置", "模型、外观、聊天显示和插件", COUIIcons.Settings, onOpenSettings)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreItem(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    ArrowPreference(
        title = title,
        summary = description,
        startAction = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = COUITheme.colorScheme.primary,
            )
        },
        onClick = onClick,
    )
}
