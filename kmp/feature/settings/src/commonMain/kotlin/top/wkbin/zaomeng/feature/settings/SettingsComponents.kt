package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 一条设置项：可点击的用 [ArrowPreference]（自带 COUI 跳转箭头），纯展示的用 [BasicComponent]。
 *
 * 行首图标不加额外边距、跳转箭头也不自己画，避免和 COUI 分组的分割线、圆角错位。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val startAction: (@Composable () -> Unit)? = if (leadingIcon == null) {
        null
    } else {
        {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = COUITheme.colorScheme.primary,
            )
        }
    }
    val endActions: @Composable RowScope.() -> Unit = {
        if (value != null) {
            Text(
                text = value,
                style = COUITheme.textStyles.bodyM,
                color = COUITheme.colorScheme.labelTertiary,
            )
        }
    }
    if (onClick != null) {
        ArrowPreference(
            title = title,
            modifier = modifier.fillMaxWidth(),
            summary = subtitle,
            startAction = startAction,
            endActions = endActions,
            enabled = enabled,
            onClick = onClick,
        )
    } else {
        BasicComponent(
            modifier = modifier.fillMaxWidth(),
            title = title,
            summary = subtitle,
            startAction = startAction,
            endActions = endActions,
            enabled = enabled,
        )
    }
}
