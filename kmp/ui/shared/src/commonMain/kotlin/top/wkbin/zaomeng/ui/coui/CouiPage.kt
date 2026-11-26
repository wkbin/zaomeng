@file:OptIn(ExperimentalScrollBarApi::class)

package top.wkbin.zaomeng.ui.coui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.FabPosition
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.SnackbarHost
import io.github.suqi8.coui.kmp.basic.SnackbarHostState
import io.github.suqi8.coui.kmp.basic.ToolbarPosition
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.basic.TopAppBarDefaults
import io.github.suqi8.coui.kmp.basic.TopAppBarSearchState
import io.github.suqi8.coui.kmp.basic.VerticalScrollBar
import io.github.suqi8.coui.kmp.basic.rememberScrollBarAdapter
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.interfaces.ExperimentalScrollBarApi
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.rememberCOUIFlingBehavior
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic

/** The outer scaffold's floating navigation clearance, including its system bar inset. */
val LocalCouiNavigationPadding = staticCompositionLocalOf<PaddingValues> { PaddingValues(0.dp) }

/** The top bar layouts a [CouiPageScaffold] can use. */
enum class CouiTopBarStyle {
    /** A collapsing large title; with `wide = true` the pinned small bar of wide windows. */
    Large,

    /** Always the pinned small bar. */
    Small,

    /** The ColorOS Settings home bar: 116dp tall, 40sp title, 24dp / 18dp margins. */
    SettingsHome,
}

/** What page content needs to lay out under the page chrome. */
@Stable
class CouiPageContentScope(
    val contentPadding: PaddingValues,
    val scrollBehavior: ScrollBehavior?,
)

/** The scroll modifiers every page list uses: scroll-end haptic, COUI over-scroll, top bar nested scroll. */
fun Modifier.couiPageScroll(scope: CouiPageContentScope): Modifier = this
    .couiScrollEffects(scope)
    .fillMaxHeight()

/**
 * Same COUI scroll effects without the fillMaxHeight constraint. Use this for grids and other
 * layouts whose measurement must stay finite (LazyVerticalGrid / HorizontalPager pages).
 */
fun Modifier.couiGridScroll(scope: CouiPageContentScope): Modifier = this.couiScrollEffects(scope)

private fun Modifier.couiScrollEffects(scope: CouiPageContentScope): Modifier = this
    .scrollEndHaptic()
    .overScrollVertical()
    .then(
        if (scope.scrollBehavior != null) {
            nestedScroll(scope.scrollBehavior.nestedScrollConnection)
        } else {
            Modifier
        },
    )

/** Whether the window is wide enough for the pinned small top bar: 840dp, or 600dp in landscape. */
@Composable
fun isWideWindow(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) {
        val width = size.width.toDp()
        val height = size.height.toDp()
        width >= 840.dp || (width >= 600.dp && height < width)
    }
}

/**
 * A COUI page: collapsing top bar, COUI popup host for overlays, and bar / FAB / toolbar slots.
 */
@Composable
fun CouiPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    topBarStyle: CouiTopBarStyle = CouiTopBarStyle.Large,
    wide: Boolean = false,
    searchState: TopAppBarSearchState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottomContent: @Composable () -> Unit = {},
    topBarContainer: @Composable (topBar: @Composable () -> Unit) -> Unit = { topBar -> topBar() },
    snackbarHostState: SnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    floatingToolbar: @Composable () -> Unit = {},
    floatingToolbarPosition: ToolbarPosition = ToolbarPosition.BottomCenter,
    contentTopSpacing: Dp = 12.dp,
    contentBottomSpacing: Dp = 0.dp,
    content: @Composable (CouiPageContentScope) -> Unit,
) {
    val scrollBehavior = COUIScrollBehavior()
    val navigationPadding = LocalCouiNavigationPadding.current
    val navigationInsets = remember(navigationPadding) { NavigationBarPaddingInsets(navigationPadding) }
    Scaffold(
        modifier = modifier,
        topBar = {
            topBarContainer {
                CouiPageTopBar(
                    title = title,
                    scrollBehavior = scrollBehavior,
                    subtitle = subtitle,
                    onBack = onBack,
                    style = topBarStyle,
                    wide = wide,
                    searchState = searchState,
                    actions = actions,
                    bottomContent = topBarBottomContent,
                )
            }
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        floatingToolbar = floatingToolbar,
        floatingToolbarPosition = floatingToolbarPosition,
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(state = snackbarHostState) },
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            .union(navigationInsets),
    ) { innerPadding ->
        val padding = remember(innerPadding, contentTopSpacing, contentBottomSpacing) {
            PageContentPadding(innerPadding, contentTopSpacing, contentBottomSpacing)
        }
        val scope = remember(padding, scrollBehavior) { CouiPageContentScope(padding, scrollBehavior) }
        Box(Modifier.fillMaxSize()) {
            content(scope)
        }
    }
}

private class NavigationBarPaddingInsets(private val padding: PaddingValues) :
    WindowInsets by WindowInsets(0, 0, 0, 0) {
    override fun getBottom(density: Density): Int = with(density) {
        padding.calculateBottomPadding().roundToPx()
    }
}

/** Read the scaffold padding during layout so it tracks the top bar in the same frame. */
@Stable
private class PageContentPadding(
    private val innerPadding: PaddingValues,
    private val topSpacing: Dp,
    private val bottomSpacing: Dp,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        innerPadding.calculateLeftPadding(layoutDirection)

    override fun calculateTopPadding(): Dp = innerPadding.calculateTopPadding() + topSpacing

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        innerPadding.calculateRightPadding(layoutDirection)

    override fun calculateBottomPadding(): Dp = innerPadding.calculateBottomPadding() + bottomSpacing
}

/**
 * A [CouiPageScaffold] whose content is a lazy list with the page scroll behaviour and a scroll bar.
 *
 * @param overlays Dialogs and bottom sheets of the page. They live here, beside the list, so they
 *   are not disposed when a lazy item scrolls out of view.
 */
@Composable
fun CouiPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    topBarStyle: CouiTopBarStyle = CouiTopBarStyle.Large,
    wide: Boolean = false,
    searchState: TopAppBarSearchState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottomContent: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    listState: LazyListState = rememberLazyListState(),
    overlays: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    CouiPageScaffold(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        onBack = onBack,
        topBarStyle = topBarStyle,
        wide = wide,
        searchState = searchState,
        actions = actions,
        topBarBottomContent = topBarBottomContent,
        snackbarHostState = snackbarHostState,
    ) { scope ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.couiPageScroll(scope),
                contentPadding = scope.contentPadding,
                flingBehavior = rememberCOUIFlingBehavior(),
            ) {
                content()
                item(key = "coui_page_tail") { Spacer(Modifier.height(32.dp)) }
            }
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                trackPadding = scope.contentPadding,
            )
            overlays()
        }
    }
}

/** The page top bar, using COUI's own solid colours (no blur bridge on minSdk 24). */
@Composable
fun CouiPageTopBar(
    title: String,
    scrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    style: CouiTopBarStyle = CouiTopBarStyle.Large,
    wide: Boolean = false,
    searchState: TopAppBarSearchState? = null,
    color: Color = COUITheme.colorScheme.backgroundWithCard,
    showDivider: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    val colors = COUITheme.colorScheme
    val navigationIcon: @Composable () -> Unit = {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = COUIIcons.Back,
                    contentDescription = "返回",
                    tint = colors.labelPrimary,
                )
            }
        }
    }
    val smallBar = searchState == null &&
        (style == CouiTopBarStyle.Small || (wide && style == CouiTopBarStyle.Large))
    if (smallBar) {
        SmallTopAppBar(
            title = title,
            modifier = modifier,
            color = color,
            showDivider = showDivider,
            subtitle = subtitle,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
        )
    } else {
        val home = style == CouiTopBarStyle.SettingsHome
        TopAppBar(
            title = title,
            modifier = modifier,
            color = color,
            showDivider = showDivider,
            subtitle = subtitle,
            scrollBehavior = scrollBehavior,
            searchState = searchState,
            expandedHeight = if (home) 116.dp else TopAppBarDefaults.ExpandedHeight,
            largeTitleFontSize = if (home) 40.sp else TopAppBarDefaults.largeTitleFontSize(),
            titlePadding = if (home) 24.dp else Dp.Unspecified,
            largeTitleBottomMargin = if (home) 18.dp else Dp.Unspecified,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
        )
    }
}
