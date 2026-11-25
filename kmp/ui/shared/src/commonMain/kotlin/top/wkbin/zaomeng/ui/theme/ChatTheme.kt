package top.wkbin.zaomeng.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import io.github.suqi8.coui.kmp.theme.Colors

/** The COUI theme tint over a card surface keeps message text readable over a chat image. */
fun Colors.chatMessageTint(): Color = containerThemeHalftone.compositeOver(surfaceWithCard)
