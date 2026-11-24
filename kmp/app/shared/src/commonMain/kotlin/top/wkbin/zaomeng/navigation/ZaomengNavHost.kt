package top.wkbin.zaomeng.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import androidx.window.core.layout.WindowSizeClass
import org.koin.core.parameter.parametersOf
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.zaomeng.di.AppPlatform
import top.wkbin.zaomeng.feature.bookshelf.BookshelfScreen
import top.wkbin.zaomeng.feature.bookshelf.BookshelfViewModel
import top.wkbin.zaomeng.feature.chapters.ChaptersScreen
import top.wkbin.zaomeng.feature.chapters.ChaptersViewModel
import top.wkbin.zaomeng.feature.chat.ChatScreen
import top.wkbin.zaomeng.feature.chat.ChatViewModel
import top.wkbin.zaomeng.feature.sessions.SessionsScreen
import top.wkbin.zaomeng.feature.sessions.SessionsViewModel
import top.wkbin.zaomeng.feature.settings.AppearanceSettingsScreen
import top.wkbin.zaomeng.feature.settings.ChatDisplaySettingsScreen
import top.wkbin.zaomeng.feature.settings.AppSupportSettingsScreen
import top.wkbin.zaomeng.feature.settings.ModelProfileEditorScreen
import top.wkbin.zaomeng.feature.settings.ModelProfileEditorViewModel
import top.wkbin.zaomeng.feature.settings.ModelProfilesViewModel
import top.wkbin.zaomeng.feature.settings.ModelSettingsScreen
import top.wkbin.zaomeng.feature.settings.PluginsScreen
import top.wkbin.zaomeng.feature.pluginbuilder.PluginBuilderScreen
import top.wkbin.zaomeng.feature.settings.SettingsViewModel
import top.wkbin.zaomeng.feature.settings.SettingsHomeScreen
import top.wkbin.zaomeng.feature.settings.StartupRecoverySettingsScreen
import top.wkbin.zaomeng.feature.cards.CardLibraryScreen
import top.wkbin.zaomeng.feature.cards.CardLibraryViewModel
import top.wkbin.zaomeng.feature.crossover.CrossoverScreen
import top.wkbin.zaomeng.feature.crossover.CrossoverViewModel
import top.wkbin.zaomeng.feature.library.OnlineLibraryScreen
import top.wkbin.zaomeng.feature.library.OnlineLibraryViewModel
import top.wkbin.zaomeng.feature.persona.PersonaScreen
import top.wkbin.zaomeng.feature.relations.RelationsScreen
import top.wkbin.zaomeng.feature.relations.RelationsViewModel
import top.wkbin.zaomeng.feature.storyrecap.StoryRecapScreen
import top.wkbin.zaomeng.feature.storyrecap.StoryRecapViewModel
import top.wkbin.zaomeng.feature.timeline.WorldTimelineScreen
import top.wkbin.zaomeng.feature.timeline.WorldTimelineViewModel
import top.wkbin.zaomeng.feature.originalknowledge.OriginalKnowledgeScreen
import top.wkbin.zaomeng.feature.originalknowledge.OriginalKnowledgeViewModel
import top.wkbin.zaomeng.feature.update.AppUpdateScreen
import top.wkbin.zaomeng.data.update.AppUpdateUiState
import top.wkbin.zaomeng.feature.importbook.ImportBookScreen
import top.wkbin.zaomeng.feature.importbook.ImportBookViewModel
import top.wkbin.zaomeng.feature.redistill.RedistillScreen
import top.wkbin.zaomeng.feature.redistill.RedistillViewModel
import top.wkbin.zaomeng.feature.rundetail.RunDetailScreen
import top.wkbin.zaomeng.feature.rundetail.RunDetailViewModel
import top.wkbin.zaomeng.platform.PlatformBackHandler
/** 窄屏使用底栏，宽屏使用侧栏。详情页保留各自的返回路径。 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ZaomengNavHost(
    appUpdateState: AppUpdateUiState = AppUpdateUiState(),
    onCheckForAppUpdate: (Boolean) -> Unit = {},
    onDownloadAppUpdate: () -> Unit = {},
    startupUpdateCheckDisabled: Boolean = false,
    onStartupUpdateCheckDisabledChange: (Boolean) -> Unit = {},
    launchChaptersRunId: String? = null,
    onChaptersLaunchConsumed: () -> Unit = {},
    /** Android 内置导航返回处理开关切换回调，桌面/iOS 不展示此开关。 */
    onBuiltInBackHandlingEnabledChange: (Boolean) -> Unit = {},
    /** 是否由 Navigation3/NavDisplay 处理应用内返回；关闭时回退到 AndroidX BackHandler。 */
    builtInBackHandlingEnabled: Boolean = true,
) {
    val backStack = remember { NavBackStack<NavKey>(BookshelfDestination) }
    val platform: AppPlatform = koinInject()
    val runDetailRefreshEpoch = remember { mutableStateOf(0) }

    val pendingLaunchRunId = remember(launchChaptersRunId) {
        launchChaptersRunId?.takeIf { it.isNotBlank() }
    }
    LaunchedEffect(pendingLaunchRunId) {
        if (pendingLaunchRunId != null) {
            backStack.add(ChaptersDestination(pendingLaunchRunId))
            onChaptersLaunchConsumed()
        }
    }

    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val wideLayout = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    // 返回语义（参考 KernelSU）：栈深 >1 时弹栈；在非首页顶层 tab 时先回首页而不是退出；
    // 首页（栈深 1）时不做任何事，由系统处理退出。
    // 书卷架始终作为栈底（tab 切换只替换其上的内容），因此任何 tab 下返回都有真实的
    // 应用内上一页可供内置返回动画预览，而不是直接预览"退出应用"。
    val popBack: () -> Unit = {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    // 内置处理关闭时，隔离 NavDisplay 的 NavigationEventDispatcher，
    // 由 AndroidX BackHandler 接管应用内导航栈（栈深 >1 才拦截）。
    PlatformBackHandler(
        enabled = !builtInBackHandlingEnabled && backStack.size > 1,
        onBack = popBack,
    )

    val navEventOwner = rememberNavigationEventDispatcherOwner(
        enabled = builtInBackHandlingEnabled,
    )

    val navEntryProvider = entryProvider<NavKey> {
            entry(BookshelfDestination) {
                val viewModel: BookshelfViewModel = koinViewModel()
                BookshelfScreen(
                    viewModel = viewModel,
                    onImport = { backStack.add(ImportBookDestination) },
                    onOpenSettings = { backStack.add(ModelConfigurationDestination) },
                    onOpenRun = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            entry(MoreDestination) {
                MoreScreen(
                    onOpenCards = { backStack.add(CardLibraryDestination) },
                    onOpenCrossover = { backStack.add(CrossoverDestination) },
                    onOpenLibrary = { backStack.add(OnlineLibraryDestination) },
                    onOpenSettings = { backStack.add(ModelSettingsDestination) },
                )
            }
            entry(ImportBookDestination) {
                val viewModel: ImportBookViewModel = koinViewModel()
                ImportBookScreen(
                    viewModel = viewModel,
                    onBack = popBack,
                    onOpenSettings = { backStack.add(ModelConfigurationDestination) },
                    onOpenOnlineLibrary = { backStack.add(OnlineLibraryDestination) },
                    onRunCreated = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            entry(ModelSettingsDestination) {
                SettingsHomeScreen(
                    showBackButton = true,
                    onBack = popBack,
                    onOpenModelSettings = { backStack.add(ModelConfigurationDestination) },
                    onOpenChatDisplay = { backStack.add(ChatDisplaySettingsDestination) },
                    onOpenPlugins = { backStack.add(PluginsDestination) },
                    onOpenAppearance = { backStack.add(AppearanceSettingsDestination) },
                    onOpenStartupRecovery = { backStack.add(StartupRecoverySettingsDestination) },
                    onOpenAppSupport = { backStack.add(AppSupportSettingsDestination) },
                    onOpenAppUpdate = { backStack.add(AppUpdateDestination) },
                )
            }
            entry(ChatDisplaySettingsDestination) {
                ChatDisplaySettingsScreen(filesDir = platform.filesDir, onBack = popBack)
            }
            entry(AppearanceSettingsDestination) {
                AppearanceSettingsScreen(
                    onBack = popBack,
                    onBuiltInBackHandlingEnabledChange = onBuiltInBackHandlingEnabledChange,
                )
            }
            entry(StartupRecoverySettingsDestination) {
                StartupRecoverySettingsScreen(onBack = popBack)
            }
            entry(ModelConfigurationDestination) {
                val viewModel: ModelProfilesViewModel = koinViewModel()
                ModelSettingsScreen(
                    viewModel = viewModel,
                    onBack = popBack,
                    onAddProfile = { backStack.add(ModelProfileEditorDestination("")) },
                    onEditProfile = { profileId ->
                        backStack.add(ModelProfileEditorDestination(profileId))
                    },
                )
            }
            addEntryProvider(clazz = ModelProfileEditorDestination::class) { destination ->
                val viewModel: ModelProfileEditorViewModel =
                    koinViewModel(parameters = { parametersOf(destination.profileId) })
                ModelProfileEditorScreen(
                    viewModel = viewModel,
                    onBack = popBack,
                )
            }
            entry(PluginsDestination) {
                PluginsScreen(
                    filesDir = platform.filesDir,
                    onBack = popBack,
                    onOpenBuilder = { backStack.add(PluginBuilderDestination) },
                )
            }
            entry(PluginBuilderDestination) {
                PluginBuilderScreen(onBack = popBack)
            }
            entry(AppSupportSettingsDestination) {
                val viewModel: SettingsViewModel = koinViewModel()
                AppSupportSettingsScreen(
                    viewModel = viewModel,
                    onBack = popBack,
                )
            }
            entry(AppUpdateDestination) {
                AppUpdateScreen(
                    state = appUpdateState,
                    onBack = popBack,
                    onCheck = { onCheckForAppUpdate(true) },
                    onDownload = onDownloadAppUpdate,
                    startupUpdateCheckDisabled = startupUpdateCheckDisabled,
                    onStartupUpdateCheckDisabledChange = onStartupUpdateCheckDisabledChange,
                )
            }
            entry(CardLibraryDestination) {
                val viewModel: CardLibraryViewModel = koinViewModel()
                CardLibraryScreen(
                    viewModel = viewModel,
                    showBackButton = true,
                    onBack = popBack,
                )
            }
            entry(CrossoverDestination) {
                val viewModel: CrossoverViewModel = koinViewModel()
                CrossoverScreen(
                    viewModel = viewModel,
                    showBackButton = true,
                    onBack = popBack,
                    onCreated = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            addEntryProvider(clazz = SessionsDestination::class) { destination ->
                val viewModel: SessionsViewModel = koinViewModel()
                SessionsScreen(
                    viewModel = viewModel,
                    runId = destination.runId.takeIf(String::isNotBlank),
                    showBackButton = destination.runId.isNotBlank(),
                    onBack = popBack,
                    onOpenChat = { runId, sessionId ->
                        backStack.add(ChatDestination(runId, sessionId))
                    },
                )
            }
            addEntryProvider(clazz = RunDetailDestination::class) { destination ->
                val viewModel: RunDetailViewModel =
                    koinViewModel(parameters = { parametersOf(destination.runId) })
                RunDetailScreen(
                    viewModel = viewModel,
                    refreshEpoch = runDetailRefreshEpoch.value,
                    onBack = popBack,
                    onOpenPersona = { runId, character ->
                        backStack.add(PersonaDestination(runId, character))
                    },
                    onOpenSessions = { runId -> backStack.add(SessionsDestination(runId)) },
                    onOpenChat = { runId, sessionId -> backStack.add(ChatDestination(runId, sessionId)) },
                    onOpenChapters = { runId -> backStack.add(ChaptersDestination(runId)) },
                    onOpenRelations = { runId -> backStack.add(RelationsDestination(runId)) },
                    onOpenWorldTimeline = { runId -> backStack.add(WorldTimelineDestination(runId)) },
                    onOpenOriginalKnowledge = { runId -> backStack.add(OriginalKnowledgeDestination(runId)) },
                    onOpenRedistill = { runId -> backStack.add(RedistillDestination(runId)) },
                    onDeleted = { backStack.removeLastOrNull() },
                )
            }
            addEntryProvider(clazz = RedistillDestination::class) { destination ->
                val viewModel: RedistillViewModel =
                    koinViewModel(parameters = { parametersOf(destination.runId) })
                RedistillScreen(
                    viewModel = viewModel,
                    onBack = popBack,
                    onStarted = { backStack.removeLastOrNull() },
                )
            }
            addEntryProvider(clazz = OriginalKnowledgeDestination::class) { destination ->
                val viewModel: OriginalKnowledgeViewModel =
                    koinViewModel(parameters = { parametersOf(destination.runId) })
                OriginalKnowledgeScreen(viewModel = viewModel, onBack = popBack)
            }
            addEntryProvider(clazz = ChatDestination::class) { destination ->
                val viewModel: ChatViewModel =
                    koinViewModel(parameters = { parametersOf(destination.runId, destination.sessionId) })
                ChatScreen(
                    viewModel = viewModel,
                    runId = destination.runId,
                    sessionId = destination.sessionId,
                    onBack = popBack,
                    onOpenBranch = { runId, sessionId ->
                        backStack.add(ChatDestination(runId, sessionId))
                    },
                    onOpenStoryRecap = {
                        backStack.add(StoryRecapDestination(destination.runId, destination.sessionId))
                    },
                    onSelectSession = { newSessionId ->
                        if (newSessionId != destination.sessionId) {
                            // 桌面端主从布局：原地替换当前聊天条目，per-entry ViewModelStore
                            // 会重建 VM 并加载新会话
                            backStack.removeLastOrNull()
                            backStack.add(ChatDestination(destination.runId, newSessionId))
                        }
                    },
                )
            }
            addEntryProvider(clazz = ChaptersDestination::class) { destination ->
                val viewModel: ChaptersViewModel =
                    koinViewModel(parameters = { parametersOf(destination.runId) })
                ChaptersScreen(
                    viewModel = viewModel,
                    onBack = popBack,
                    onOpenChat = { runId, sessionId ->
                        backStack.add(ChatDestination(runId, sessionId))
                    },
                    onOpenPersona = { runId, character ->
                        backStack.add(PersonaDestination(runId, character))
                    },
                )
            }
            addEntryProvider(clazz = RelationsDestination::class) { destination ->
                val viewModel: RelationsViewModel =
                    koinViewModel(parameters = { parametersOf(destination.runId) })
                RelationsScreen(
                    viewModel = viewModel,
                    onBack = popBack,
                )
            }
            addEntryProvider(clazz = WorldTimelineDestination::class) { destination ->
                val viewModel: WorldTimelineViewModel =
                    koinViewModel(parameters = { parametersOf(destination.runId) })
                WorldTimelineScreen(
                    viewModel = viewModel,
                    onBack = popBack,
                    onOpenChat = { runId, sessionId ->
                        backStack.add(ChatDestination(runId, sessionId))
                    },
                )
            }
            addEntryProvider(clazz = StoryRecapDestination::class) { destination ->
                val viewModel: StoryRecapViewModel =
                    koinViewModel(parameters = { parametersOf(destination.runId, destination.sessionId) })
                StoryRecapScreen(
                    viewModel = viewModel,
                    onBack = popBack,
                )
            }
            entry(OnlineLibraryDestination) {
                val viewModel: OnlineLibraryViewModel = koinViewModel()
                OnlineLibraryScreen(
                    viewModel = viewModel,
                    showBackButton = true,
                    onBack = popBack,
                    onRunImported = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            addEntryProvider(clazz = PersonaDestination::class) { destination ->
                PersonaScreen(
                    runId = destination.runId,
                    character = destination.character,
                    onBack = popBack,
                    onDeleted = {
                        backStack.removeLastOrNull()
                        runDetailRefreshEpoch.value += 1
                    },
                )
            }
        }

    val current = backStack.lastOrNull()
    val showNavigation = current == BookshelfDestination ||
        current == SessionsDestination() || current == MoreDestination
    val selectDestination: (NavKey) -> Unit = { destination ->
        if (backStack.lastOrNull() != destination) {
            while (backStack.size > 1) backStack.removeLastOrNull()
            if (destination != BookshelfDestination) backStack.add(destination)
        }
    }

    val bottomNavigation = !wideLayout && showNavigation
    val contentInsets = if (bottomNavigation) {
        Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
    } else {
        Modifier
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).then(contentInsets)) {
            if (wideLayout && showNavigation) {
                AppTopLevelRail(current, selectDestination)
            }
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navEventOwner) {
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    onBack = popBack,
                    entryProvider = navEntryProvider,
                    predictivePopTransitionSpec = { _ -> lightPredictivePopTransition() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                )
            }
        }
        if (bottomNavigation) {
            AppTopLevelBar(current, selectDestination)
        }
    }
}

/**
 * 轻量级预测返回动画：页面只做小幅缩放，同时沿返回方向平移，避免 NavDisplay 默认的
 * 0.7 倍缩放产生明显的“缩进”效果。
 */
private fun AnimatedContentTransitionScope<*>.lightPredictivePopTransition(): ContentTransform =
    slideInHorizontally(initialOffsetX = { -it / 8 }) +
        scaleIn(initialScale = 0.98f) togetherWith
        slideOutHorizontally(targetOffsetX = { it / 8 }) +
        scaleOut(targetScale = 0.96f)
