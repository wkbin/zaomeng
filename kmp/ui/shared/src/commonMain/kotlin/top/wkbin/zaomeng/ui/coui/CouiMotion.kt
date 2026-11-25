package top.wkbin.zaomeng.ui.coui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import io.github.suqi8.coui.kmp.nav.transition.NavTransitions

/**
 * COUI 17 的动效参数。
 *
 * 页面翻页由 COUI 的 `NavDisplay`（`io.github.suqi8.coui.kmp:coui-navigation3-ui`）直接提供，
 * 默认就是 COUI 17 的页面转场：进入页自尾部边缘整宽滑入，被覆盖页向首边平移 30%，
 * 350ms、cubic-bezier(0.3, 0.1, 0.3, 1)。这里只提供同页内容切换用的曲线与常量。
 *
 * 所有数值取自 COUI 官方 [NavTransitions]，不另行硬编码，避免升级后与官方漂移。
 */
object CouiMotion {

    private const val PageDurationMillis: Int = NavTransitions.COUI_PAGE_DURATION_MILLIS
    private val PageEasing = NavTransitions.COUIPageEasing

    /**
     * 同页内容切换用的 COUI 曲线淡入淡出。
     *
     * 时长与曲线都与 COUI 页面转场一致，而不是 Compose 默认的 150ms 线性淡变。
     */
    fun AnimatedContentTransitionScope<*>.couiContentFade(): ContentTransform = fadeIn(
        animationSpec = tween(PageDurationMillis, easing = PageEasing),
    ) togetherWith fadeOut(
        animationSpec = tween(PageDurationMillis, easing = PageEasing),
    )

}
