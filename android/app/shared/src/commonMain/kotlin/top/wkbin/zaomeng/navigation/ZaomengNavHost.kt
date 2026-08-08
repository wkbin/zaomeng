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
                    onBack = { backStack.removeLastOrNull() },
                    onOpenSettings = { backStack.add(ModelSettingsDestination) },
                    onOpenOnlineLibrary = { backStack.add(OnlineLibraryDestination) },
                    onRunCreated = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            entry(ModelSettingsDestination) {
                SettingsHomeScreen(
                    onBack = { backStack.removeLastOrNull() },
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
                ChatDisplaySettingsScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry(AppearanceSettingsDestination) {
                AppearanceSettingsScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry(StartupRecoverySettingsDestination) {
                StartupRecoverySettingsScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry(ModelConfigurationDestination) {
                val viewModel: ModelProfilesViewModel = koinViewModel()
                ModelSettingsScreen(
                    viewModel = viewModel,
                    onBack = { backStack.removeLastOrNull() },
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
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry(PluginsDestination) {
                PluginsScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry(AppSupportSettingsDestination) {
                val viewModel: SettingsViewModel = koinViewModel()
                AppSupportSettingsScreen(
                    viewModel = viewModel,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry(AppUpdateDestination) {
                AppUpdateScreen(
                    state = appUpdateState,
                    onBack = { backStack.removeLastOrNull() },
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
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry(CrossoverDestination) {
                val viewModel: CrossoverViewModel = koinViewModel()
                CrossoverScreen(
                    viewModel = viewModel,
                    onBack = { backStack.removeLastOrNull() },
                    onCreated = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            addEntryProvider(clazz = SessionsDestination::class) { destination ->
                val viewModel: SessionsViewModel = koinViewModel()
                SessionsScreen(
                    viewModel = viewModel,
                    runId = destination.runId.takeIf(String::isNotBlank),
                    onBack = { backStack.removeLastOrNull() },
                    onOpenChat = { runId, sessionId ->
                        backStack.add(ChatDestination(runId, sessionId))
                    },
                    onOpenStoryRecap = { runId, sessionId ->
                        backStack.add(StoryRecapDestination(runId, sessionId))
                    },
                )
            }
            addEntryProvider(clazz = RunDetailDestination::class) { destination ->
                val viewModel: RunDetailViewModel =
                    koinViewModel(parameters = { parametersOf(destination.runId) })
                RunDetailScreen(
                    viewModel = viewModel,
                    onBack = { backStack.removeLastOrNull() },
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
                    onBack = { backStack.removeLastOrNull() },
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
                    onBack = { backStack.removeLastOrNull() },
                    onOpenBranch = { runId, sessionId ->
                        backStack.add(ChatDestination(runId, sessionId))
                    },
                    onOpenStoryRecap = {
                        backStack.add(StoryRecapDestination(destination.runId, destination.sessionId))
                    },
                )
            }
            addEntryProvider(clazz = ChaptersDestination::class) { destination ->
                val viewModel: ChaptersViewModel =
                    koinViewModel(parameters = { parametersOf(destination.runId) })
                ChaptersScreen(
                    viewModel = viewModel,
                    onBack = { backStack.removeLastOrNull() },
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
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            addEntryProvider(clazz = WorldTimelineDestination::class) { destination ->
                val viewModel: WorldTimelineViewModel =
                    koinViewModel(parameters = { parametersOf(destination.runId) })
                WorldTimelineScreen(
                    viewModel = viewModel,
                    onBack = { backStack.removeLastOrNull() },
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
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry(OnlineLibraryDestination) {
                val viewModel: OnlineLibraryViewModel = koinViewModel()
                OnlineLibraryScreen(
                    viewModel = viewModel,
                    onBack = { backStack.removeLastOrNull() },
                    onRunImported = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            addEntryProvider(clazz = PersonaDestination::class) { destination ->
                PersonaScreen(
                    runId = destination.runId,
                    character = destination.character,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        }

    if (wideLayout) {
        Row(Modifier.fillMaxSize()) {
            AppTopLevelRail(
                selectedDestination = backStack.firstOrNull(),
                onSelectDestination = { destination ->
                    backStack.clear()
                    backStack.addAll(listOf(destination))
                },
            )
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxHeight().weight(1f),
                onBack = { backStack.removeLastOrNull() },
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
            onBack = { backStack.removeLastOrNull() },
            entryProvider = navEntryProvider,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        )
    }
}
