package top.wkbin.zaomeng.feature.chat.transcript

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EmotionBadge(innerThought: String?, modifier: Modifier = Modifier) {
    val emoji = classifyEmotion(innerThought.orEmpty()) ?: return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
    ) {
        Text(text = emoji, fontSize = 11.sp, lineHeight = 12.sp)
    }
}

internal fun classifyEmotion(text: String): String? {
    val normalized = text.trim().lowercase()
    if (normalized.isEmpty()) return null
    return emotionKeywords.firstOrNull { (_, keywords) ->
        keywords.any(normalized::contains)
    }?.first
}

private val emotionKeywords = listOf(
    "😄" to listOf("开心", "高兴", "喜悦", "欣喜", "愉快", "笑", "happy", "joy"),
    "😠" to listOf("愤怒", "生气", "恼火", "怒", "气愤", "angry", "anger"),
    "😢" to listOf("悲伤", "难过", "伤心", "哀伤", "哭", "sad", "sorrow"),
    "😮" to listOf("惊讶", "震惊", "诧异", "意外", "surprised", "shock"),
    "😰" to listOf("紧张", "害怕", "不安", "焦虑", "担心", "恐惧", "nervous", "afraid"),
    "😌" to listOf("平静", "冷静", "安心", "释然", "从容", "calm", "peaceful"),
)
