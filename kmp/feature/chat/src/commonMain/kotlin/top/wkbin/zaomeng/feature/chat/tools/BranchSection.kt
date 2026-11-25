package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.HorizontalSplit
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.icon.extended.Unlock
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.feature.chat.insights.*

@Composable
internal fun BranchRow(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = COUITheme.textStyles.bodyXS, color = COUITheme.colorScheme.labelSecondary)
        }
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(COUIIcons.HorizontalSplit, contentDescription = "创建分支")
        }
    }
}

@Composable
internal fun EventBranchRow(
    title: String,
    detail: String,
    locked: Boolean,
    enabled: Boolean,
    onBranch: () -> Unit,
    onToggleLock: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = COUITheme.textStyles.bodyXS, color = COUITheme.colorScheme.labelSecondary)
        }
        IconButton(onClick = onBranch, enabled = enabled) {
            Icon(COUIIcons.HorizontalSplit, contentDescription = "从事件创建分支")
        }
        IconButton(onClick = { onToggleLock(!locked) }, enabled = enabled) {
            Icon(
                if (locked) COUIIcons.Lock else COUIIcons.Unlock,
                contentDescription = if (locked) "解除主线事件锁定" else "锁定为主线事件",
            )
        }
    }
}

@Composable
internal fun BranchGraphInsights(
    nodes: List<BranchNodeInsight>,
    enabled: Boolean,
    onOpenBranch: (String) -> Unit,
) {
    ToolSection("现有分支") {
        nodes.forEachIndexed { index, node ->
            Surface(
                onClick = { onOpenBranch(node.sessionId) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !node.isCurrent && node.sessionId.isNotBlank(),
                shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
                color = COUITheme.colorScheme.containerThemeHalftone,
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(COUIIcons.HorizontalSplit, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            node.label,
                            fontWeight = if (node.isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            node.detail,
                            style = COUITheme.textStyles.bodyXS,
                            color = COUITheme.colorScheme.labelSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        when {
                            node.isCurrent -> "当前"
                            node.isMainline -> "主线"
                            else -> "打开"
                        },
                        style = COUITheme.textStyles.description,
                    )
                }
            }
            if (index != nodes.lastIndex) HorizontalDivider()
        }
    }
}
