package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.feature.chat.insights.*

@Composable
internal fun SceneRow(
    card: ReusableCardDto,
    recommended: Boolean,
    enabled: Boolean,
    transition: String,
    onSwitch: (String, String, Boolean) -> Unit,
) {
    val title = card.preview.stringValue("title").ifBlank { card.fields.stringValue("title") }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "未命名场景" }, fontWeight = FontWeight.Medium)
                Text(
                    listOf(card.preview.stringValue("location"), card.preview.stringValue("atmosphere"))
                        .filter(String::isNotBlank).joinToString(" · "),
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            if (recommended) {
                Text(
                    "推荐",
                    style = COUITheme.textStyles.description,
                    color = COUITheme.colorScheme.primaryText,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                text = "切换",
                onClick = { onSwitch(card.cardId, transition, false) },
                modifier = Modifier.weight(1f),
                enabled = enabled && transition.isNotBlank(),
            )
            Button(
                onClick = { onSwitch(card.cardId, transition, true) },
                modifier = Modifier.weight(1f),
                enabled = enabled && transition.isNotBlank(),
            ) { Text("切换并续写") }
        }
    }
}
