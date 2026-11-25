package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        title = title,
        summary = subtitle,
        startAction = leadingIcon?.let { icon ->
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = COUITheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        },
        endActions = {
            if (value != null) {
                Text(
                    text = value,
                    style = COUITheme.textStyles.bodyM,
                    color = COUITheme.colorScheme.labelTertiary,
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = COUIIcons.ChevronForward,
                    contentDescription = null,
                    tint = COUITheme.colorScheme.labelTertiary,
                )
            }
        },
        onClick = onClick,
        enabled = enabled,
    )
}
