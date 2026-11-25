package top.wkbin.zaomeng.ui.roleplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
fun RoleplayMessageContent(
    text: String,
    style: TextStyle,
    baseColor: Color,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    maxProseLines: Int = Int.MAX_VALUE,
    readingLayout: Boolean = false,
    plainProse: (@Composable (String) -> Unit)? = null,
) {
    val blocks = remember(text, streaming) { parseRoleplayMessage(text, streaming) }
    val paragraphSpacing = if (readingLayout) 14.dp else 8.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(paragraphSpacing)) {
        for (block in blocks) {
            when (block) {
                is RoleplayBlock.Prose -> {
                    val paragraphs = remember(block.text, readingLayout) {
                        if (readingLayout) roleplayParagraphs(block.text) else listOf(block.text)
                    }
                    for (paragraph in paragraphs) {
                        RoleplayProseText(paragraph, style, baseColor, maxProseLines, readingLayout, plainProse)
                    }
                }
                is RoleplayBlock.Status -> RoleplayStatus(block, style)
            }
        }
    }
}

@Composable
private fun RoleplayProseText(
    text: String,
    style: TextStyle,
    baseColor: Color,
    maxLines: Int,
    readingLayout: Boolean,
    plainProse: (@Composable (String) -> Unit)?,
) {
    val spans = remember(text) { roleplaySpans(text) }
    if (!readingLayout && plainProse != null && spans.none { it.tone == RoleplayTone.Narration }) {
        plainProse(text)
        return
    }

    val aside = COUITheme.colorScheme.labelSecondary
    val dialogue = COUITheme.colorScheme.primary
    val formatted = remember(spans, readingLayout, aside, dialogue) {
        buildAnnotatedString {
            for (span in spans) {
                when {
                    readingLayout && span.tone == RoleplayTone.Dialogue -> {
                        withStyle(SpanStyle(color = dialogue)) { append(span.text) }
                    }
                    !readingLayout && span.tone == RoleplayTone.Narration -> {
                        withStyle(SpanStyle(color = aside, fontStyle = FontStyle.Italic)) { append(span.text) }
                    }
                    else -> append(span.text)
                }
            }
        }
    }
    Text(formatted, style = style, color = baseColor, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun RoleplayStatus(block: RoleplayBlock.Status, style: TextStyle) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = COUITheme.colorScheme.backgroundWithCard,
        shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
    ) {
        Column {
            TextButton(
                text = if (expanded) "收起 · ${block.title}" else "展开 · ${block.title}",
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            if (expanded) {
                Text(
                    text = block.text,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    style = style,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
        }
    }
}
