package top.wkbin.zaomeng.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.window.core.layout.WindowSizeClass
import org.koin.core.parameter.parametersOf
import org.koin.compose.viewmodel.koinViewModel
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
import top.wkbin.zaomeng.feature.update.AppUpdateScreen
import top.wkbin.zaomeng.data.update.AppUpdateUiState
import top.wkbin.zaomeng.feature.importbook.ImportBookScreen
import top.wkbin.zaomeng.feature.importbook.ImportBookViewModel
import top.wkbin.zaomeng.feature.redistill.RedistillScreen
import top.wkbin.zaomeng.feature.redistill.RedistillViewModel
import top.wkbin.zaomeng.feature.rundetail.RunDetailScreen
import top.wkbin.zaomeng.feature.rundetail.RunDetailViewModel
/**
 * nav3 导航宿主：按窗口宽度自适应布局。
 *
 * - compact（手机竖屏/未展开折叠屏）：保持全屏单页导航，与旧版手机端一致；
 * - medium/expanded（平板、展开后的折叠屏、桌面窗口）：左侧导航栏 + 右侧内容区。
 *
 * 使用非持久化 NavBackStack（进程死亡不恢复）；后续需要恢复时可换
 * rememberNavBackStack(SavedStateConfiguration) + NavKey 多态注册。
 */
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
) {
    val backStack = remember { NavBackStack<NavKey>(BookshelfDestination) }

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
    // 应用内上一页可供预测返回预览，而不是直接预览"退出应用"。
    val popBack: () -> Unit = {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    val navEntryProvider = entryProvider<NavKey> {
            entry(BookshelfDestination) {
                val viewModel: BookshelfViewModel = koinViewModel()
                BookshelfScreen(
                    viewModel = viewModel,
                    showTopBarActions = !wideLayout,
                    onImport = { backStack.add(ImportBookDestination) },
                    onOpenSettings = { backStack.add(ModelSettingsDestination) },
                    onOpenCards = { backStack.add(CardLibraryDestination) },
                    onOpenSessions = { backStack.add(SessionsDestination()) },
                    onOpenCrossover = { backStack.add(CrossoverDestination) },
                    onOpenRun = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            entry(ImportBookDestination) {
                val viewModel: ImportBookViewModel = koinViewModel()
                ImportBookScreen(
                    viewModel = viewModel,
                    onBack = popBack,
                    onOpenSettings = { backStack.add(ModelSettingsDestination) },
                    onOpenOnlineLibrary = { backStack.add(OnlineLibraryDestination) },
                    onRunCreated = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            entry(ModelSettingsDestination) {
                SettingsHomeScreen(
                    showBackButton = !wideLayout || backStack.size > 1,
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
                ChatDisplaySettingsScreen(onBack = popBack)
            }
            entry(AppearanceSettingsDestination) {
                AppearanceSettingsScreen(onBack = popBack)
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
                PluginsScreen(onBack = popBack)
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
                    showBackButton = !wideLayout || backStack.size > 1,
                    onBack = popBack,
                )
            }
            entry(CrossoverDestination) {
                val viewModel: CrossoverViewModel = koinViewModel()
                CrossoverScreen(
                    viewModel = viewModel,
                    showBackButton = !wideLayout || backStack.size > 1,
                    onBack = popBack,
                    onCreated = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            addEntryProvider(clazz = SessionsDestination::class) { destination ->
                val viewModel: SessionsViewModel = koinViewModel()
                SessionsScreen(
                    viewModel = viewModel,
                    runId = destination.runId.takeIf(String::isNotBlank),
                    showBackButton = !wideLayout || backStack.size > 1,
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
                    onBack = popBack,
                    onOpenPersona = { runId, character ->
                        backStack.add(PersonaDestination(runId, character))
                    },
                    onOpenSessions = { runId -> backStack.add(SessionsDestination(runId)) },
                    onOpenChapters = { runId -> backStack.add(ChaptersDestination(runId)) },
                    onOpenRelations = { runId -> backStack.add(RelationsDestination(runId)) },
                    onOpenWorldTimeline = { runId -> backStack.add(WorldTimelineDestination(runId)) },
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
                    showBackButton = !wideLayout || backStack.size > 1,
                    onBack = popBack,
                    onRunImported = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            addEntryProvider(clazz = PersonaDestination::class) { destination ->
                PersonaScreen(
                    runId = destination.runId,
                    character = destination.character,
                    onBack = popBack,
                )
            }
        }

    if (wideLayout) {
        Row(Modifier.fillMaxSize()) {
            AppTopLevelRail(
                selectedDestination = backStack.lastOrNull { it.isTopLevelDestination() }
                    ?: BookshelfDestination,
                onSelectDestination = { destination ->
                    // 保留书卷架为栈底：先弹掉其上所有内容，再压入所选 tab。
                    // 已在该 tab 根部时不做任何事，避免重置页面状态。
                    while (backStack.size > 1) {
                        backStack.removeLastOrNull()
                    }
                    if (backStack.lastOrNull() != destination) {
                        backStack.add(destination)
                    }
                },
            )
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxHeight().weight(1f),
                onBack = popBack,
                entryProvider = navEntryProvider,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            )
        }
    } else {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            onBack = popBack,
            entryProvider = navEntryProvider,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        )
    }
}

/** 判断栈内条目是否为顶级导航目的地（侧栏 tab）。会话列表页视为顶级，书卷专属会话页不算。 */
private fun NavKey.isTopLevelDestination(): Boolean = when (this) {
    BookshelfDestination,
    CardLibraryDestination,
    CrossoverDestination,
    OnlineLibraryDestination,
    ModelSettingsDestination,
    -> true

    is SessionsDestination -> runId.isBlank()
    else -> false
}
