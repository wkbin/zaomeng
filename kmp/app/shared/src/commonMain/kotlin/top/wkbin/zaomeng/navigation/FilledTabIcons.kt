package top.wkbin.zaomeng.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 底部“对话”tab 的实心图标。
 *
 * COUI 的 Messages 只有线性版本，24dp 放在悬浮底栏上笔画偏细；
 * 这里用 Material Symbols Rounded 的 FILL=1 气泡，轮廓更满、更好认。
 */
internal val ChatBubbleFilled: ImageVector = ImageVector.Builder(
    name = "ChatBubbleFilled",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 960f,
    viewportHeight = 960f,
).apply {
    // Material Symbols 的路径以 y 轴负方向绘制（viewBox 0 -960 960 960），
    // 用 group 平移 960 对齐 Compose 的 0..960 视口。
    addGroup(translationY = 960f)
    addPath(
        pathData = addPathNodes(
            "m240-240-92 92q-19 19-43.5 8.5T80-177v-623q0-33 23.5-56.5T160-880h640q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H240Z",
        ),
        fill = SolidColor(Color.Black),
    )
    clearGroup()
}.build()
