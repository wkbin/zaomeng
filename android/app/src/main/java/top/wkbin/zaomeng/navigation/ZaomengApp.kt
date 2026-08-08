package top.wkbin.zaomeng.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.feature.bookshelf.BookshelfScreen
import top.wkbin.zaomeng.feature.bookshelf.BookshelfViewModel
import top.wkbin.zaomeng.feature.cards.CardLibraryScreen
import top.wkbin.zaomeng.feature.cards.CardLibraryViewModel
import top.wkbin.zaomeng.feature.chat.ChatScreen
import top.wkbin.zaomeng.feature.chat.ChatViewModel
import top.wkbin.zaomeng.feature.chapters.ChaptersScreen
import top.wkbin.zaomeng.feature.chapters.ChaptersViewModel
import top.wkbin.zaomeng.feature.importbook.ImportBookScreen
import top.wkbin.zaomeng.feature.importbook.ImportBookViewModel
import top.wkbin.zaomeng.feature.crossover.CrossoverScreen
import top.wkbin.zaomeng.feature.crossover.CrossoverViewModel
import top.wkbin.zaomeng.feature.library.OnlineLibraryScreen
import top.wkbin.zaomeng.feature.library.OnlineLibraryViewModel
import top.wkbin.zaomeng.feature.persona.PersonaScreen
import top.wkbin.zaomeng.feature.redistill.RedistillScreen
import top.wkbin.zaomeng.feature.redistill.RedistillViewModel
import top.wkbin.zaomeng.feature.relations.RelationsScreen
import top.wkbin.zaomeng.feature.relations.RelationsViewModel
import top.wkbin.zaomeng.feature.rundetail.RunDetailScreen
import top.wkbin.zaomeng.feature.rundetail.RunDetailViewModel
import top.wkbin.zaomeng.feature.sessions.SessionsScreen
import top.wkbin.zaomeng.feature.sessions.SessionsViewModel
import top.wkbin.zaomeng.feature.timeline.WorldTimelineScreen
import top.wkbin.zaomeng.feature.timeline.WorldTimelineViewModel
import top.wkbin.zaomeng.feature.storyrecap.StoryRecapScreen
import top.wkbin.zaomeng.feature.storyrecap.StoryRecapViewModel
import top.wkbin.zaomeng.feature.settings.ModelSettingsScreen
import top.wkbin.zaomeng.feature.settings.ModelProfileEditorScreen
import top.wkbin.zaomeng.feature.settings.ModelProfileEditorViewModel
import top.wkbin.zaomeng.feature.settings.ModelProfilesViewModel
import top.wkbin.zaomeng.feature.settings.PluginsScreen
import top.wkbin.zaomeng.feature.settings.PluginsViewModel
import top.wkbin.zaomeng.feature.settings.SettingsViewModel
import top.wkbin.zaomeng.feature.settings.AppearanceSettingsScreen
import top.wkbin.zaomeng.feature.settings.ChatDisplaySettingsScreen
import top.wkbin.zaomeng.feature.settings.SettingsHomeScreen
import top.wkbin.zaomeng.feature.settings.StartupRecoverySettingsScreen
import top.wkbin.zaomeng.feature.settings.AppSupportSettingsScreen
import top.wkbin.zaomeng.feature.update.AppUpdateScreen
import top.wkbin.zaomeng.data.update.AppUpdateUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun ZaomengApp(
    navController: NavHostController = rememberNavController(),
    repository: ZaomengRepository = koinInject(),
    appUpdateState: AppUpdateUiState,
    onCheckForAppUpdate: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    startupUpdateCheckDisabled: Boolean,
    onStartupUpdateCheckDisabledChange: (Boolean) -> Unit,
    launchChaptersRunId: String? = null,
    onChaptersLaunchConsumed: () -> Unit = {},
) {
    var restoreAttempted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(launchChaptersRunId) {
        val runId = launchChaptersRunId?.takeIf(String::isNotBlank).orEmpty()
        if (runId.isNotBlank()) {
            navController.navigate(ChaptersDestination(runId)) {
                launchSingleTop = true
            }
            onChaptersLaunchConsumed()
        }
    }
    LaunchedEffect(Unit) {
        if (restoreAttempted) return@LaunchedEffect
        if (!launchChaptersRunId.isNullOrBlank()) {
            restoreAttempted = true
            return@LaunchedEffect
        }
        repository.startBackend()
        repository.backendState.first { it is BackendState.Ready }
        val restoredLocation = try {
            repository.resolveRestoredLocation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (navController.currentDestination?.hasRoute<BookshelfDestination>() == true) {
            when (restoredLocation) {
                is RestoredLocation.Chat -> navController.navigate(
                    ChatDestination(restoredLocation.runId, restoredLocation.sessionId),
                ) { launchSingleTop = true }
                is RestoredLocation.Run -> navController.navigate(
                    RunDetailDestination(restoredLocation.runId),
                ) { launchSingleTop = true }
                null -> Unit
            }
        }
        restoreAttempted = true
    }

    NavHost(
        navController = navController,
        startDestination = BookshelfDestination,
    ) {
        composable<BookshelfDestination> {
            val viewModel: BookshelfViewModel = koinViewModel()
            BookshelfScreen(
                viewModel = viewModel,
                onImport = { navController.navigate(ImportBookDestination) },
                onOpenSettings = { navController.navigate(ModelSettingsDestination) },
                onOpenSessions = { navController.navigate(SessionsDestination()) },
                onOpenCards = { navController.navigate(CardLibraryDestination) },
                onOpenCrossover = { navController.navigate(CrossoverDestination) },
                onOpenRun = { navController.navigate(RunDetailDestination(it)) },
            )
        }

        composable<CrossoverDestination> {
            val viewModel: CrossoverViewModel = koinViewModel()
            CrossoverScreen(
                viewModel = viewModel,
                onBack = navController::navigateUp,
                onCreated = { runId ->
                    navController.navigate(SessionsDestination(runId)) {
                        popUpTo<CrossoverDestination> { inclusive = true }
                    }
                },
            )
        }

        composable<ImportBookDestination> {
            val viewModel: ImportBookViewModel = koinViewModel()
            ImportBookScreen(
                viewModel = viewModel,
                onBack = navController::navigateUp,
                onOpenSettings = { navController.navigate(ModelSettingsDestination) },
                onOpenOnlineLibrary = { navController.navigate(OnlineLibraryDestination) },
                onRunCreated = { runId ->
                    navController.navigate(RunDetailDestination(runId)) {
                        popUpTo<ImportBookDestination> { inclusive = true }
                    }
                },
            )
        }

        composable<OnlineLibraryDestination> {
            val viewModel: OnlineLibraryViewModel = koinViewModel()
            OnlineLibraryScreen(
                viewModel = viewModel,
                onBack = navController::navigateUp,
                onRunImported = { runId ->
                    navController.navigate(RunDetailDestination(runId)) {
                        popUpTo<ImportBookDestination> { inclusive = true }
                    }
                },
            )
        }

        composable<ModelSettingsDestination> {
            SettingsHomeScreen(
                onBack = navController::navigateUp,
                onOpenModelSettings = { navController.navigate(ModelConfigurationDestination) },
                onOpenChatDisplay = { navController.navigate(ChatDisplaySettingsDestination) },
                onOpenPlugins = { navController.navigate(PluginsDestination) },
                onOpenAppearance = { navController.navigate(AppearanceSettingsDestination) },
                onOpenStartupRecovery = { navController.navigate(StartupRecoverySettingsDestination) },
                onOpenAppSupport = { navController.navigate(AppSupportSettingsDestination) },
                onOpenAppUpdate = { navController.navigate(AppUpdateDestination) },
            )
        }

        composable<ModelConfigurationDestination> {
            val viewModel: ModelProfilesViewModel = koinViewModel()
            ModelSettingsScreen(
                viewModel = viewModel,
                onBack = navController::navigateUp,
                onAddProfile = { navController.navigate(ModelProfileEditorDestination()) },
                onEditProfile = { navController.navigate(ModelProfileEditorDestination(it)) },
            )
        }

        composable<ModelProfileEditorDestination> { entry ->
            val destination = entry.toRoute<ModelProfileEditorDestination>()
            val viewModel: ModelProfileEditorViewModel = koinViewModel(
                parameters = { parametersOf(destination.profileId) },
            )
            ModelProfileEditorScreen(viewModel = viewModel, onBack = navController::navigateUp)
        }

        composable<ChatDisplaySettingsDestination> {
            ChatDisplaySettingsScreen(onBack = navController::navigateUp)
        }

        composable<PluginsDestination> {
            val viewModel: PluginsViewModel = koinViewModel()
            PluginsScreen(viewModel = viewModel, onBack = navController::navigateUp)
        }

        composable<AppearanceSettingsDestination> {
            AppearanceSettingsScreen(onBack = navController::navigateUp)
        }

        composable<StartupRecoverySettingsDestination> {
            StartupRecoverySettingsScreen(onBack = navController::navigateUp)
        }

        composable<AppSupportSettingsDestination> {
            val viewModel: SettingsViewModel = koinViewModel()
            AppSupportSettingsScreen(viewModel = viewModel, onBack = navController::navigateUp)
        }

        composable<AppUpdateDestination> {
            AppUpdateScreen(
                state = appUpdateState,
                onBack = navController::navigateUp,
                onCheck = onCheckForAppUpdate,
                onDownload = onDownloadAppUpdate,
                startupUpdateCheckDisabled = startupUpdateCheckDisabled,
                onStartupUpdateCheckDisabledChange = onStartupUpdateCheckDisabledChange,
            )
        }

        composable<RunDetailDestination> { entry ->
            val destination = entry.toRoute<RunDetailDestination>()
            LaunchedEffect(destination.runId) {
                try {
                    repository.rememberRunLocation(destination.runId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Navigation remains usable if preference storage is temporarily unavailable.
                }
            }
            val viewModel: RunDetailViewModel = koinViewModel(
                parameters = { parametersOf(destination.runId) },
            )
            RunDetailWithExporter(
                viewModel = viewModel,
                onBack = navController::navigateUp,
                onOpenPersona = { runId, character ->
                    navController.navigate(PersonaDestination(runId, character))
                },
                onOpenSessions = { runId -> navController.navigate(SessionsDestination(runId)) },
                onOpenChapters = { runId -> navController.navigate(ChaptersDestination(runId)) },
                onOpenRelations = { runId -> navController.navigate(RelationsDestination(runId)) },
                onOpenWorldTimeline = { runId -> navController.navigate(WorldTimelineDestination(runId)) },
                onOpenRedistill = { runId -> navController.navigate(RedistillDestination(runId)) },
                onDeleted = { navController.popBackStack() },
            )
        }

        composable<RedistillDestination> { entry ->
            val destination = entry.toRoute<RedistillDestination>()
            val viewModel: RedistillViewModel = koinViewModel(
                parameters = { parametersOf(destination.runId) },
            )
            RedistillScreen(
                viewModel = viewModel,
                onBack = navController::navigateUp,
                onStarted = navController::navigateUp,
            )
        }

        composable<RelationsDestination> { entry ->
            val destination = entry.toRoute<RelationsDestination>()
            val viewModel: RelationsViewModel = koinViewModel(
                parameters = { parametersOf(destination.runId) },
            )
            RelationsScreen(viewModel = viewModel, onBack = navController::navigateUp)
        }

        composable<WorldTimelineDestination> { entry ->
            val destination = entry.toRoute<WorldTimelineDestination>()
            val viewModel: WorldTimelineViewModel = koinViewModel(
                parameters = { parametersOf(destination.runId) },
            )
            WorldTimelineScreen(
                viewModel = viewModel,
                onBack = navController::navigateUp,
                onOpenChat = { runId, sessionId ->
                    navController.navigate(ChatDestination(runId, sessionId))
                },
            )
        }

        composable<ChaptersDestination> { entry ->
            val destination = entry.toRoute<ChaptersDestination>()
            val viewModel: ChaptersViewModel = koinViewModel(
                parameters = { parametersOf(destination.runId) },
            )
            ChaptersScreen(
                viewModel = viewModel,
                onBack = navController::navigateUp,
                onOpenChat = { runId, sessionId -> navController.navigate(ChatDestination(runId, sessionId)) },
                onOpenPersona = { runId, character -> navController.navigate(PersonaDestination(runId, character)) },
            )
        }

        composable<CardLibraryDestination> {
            val viewModel: CardLibraryViewModel = koinViewModel()
            CardLibraryScreen(viewModel = viewModel, onBack = navController::navigateUp)
        }

        composable<PersonaDestination> { entry ->
            val destination = entry.toRoute<PersonaDestination>()
            PersonaScreen(
                runId = destination.runId,
                character = destination.character,
                onBack = navController::navigateUp,
            )
        }

        composable<SessionsDestination> { entry ->
            val destination = entry.toRoute<SessionsDestination>()
            val viewModel: SessionsViewModel = koinViewModel()
            SessionsScreen(
                viewModel = viewModel,
                runId = destination.runId.takeIf(String::isNotBlank),
                onBack = navController::navigateUp,
                onOpenChat = { runId, sessionId ->
                    navController.navigate(ChatDestination(runId, sessionId))
                },
                onOpenStoryRecap = { runId, sessionId ->
                    navController.navigate(StoryRecapDestination(runId, sessionId))
                },
            )
        }

        composable<StoryRecapDestination> { entry ->
            val destination = entry.toRoute<StoryRecapDestination>()
            val viewModel: StoryRecapViewModel = koinViewModel(
                parameters = { parametersOf(destination.runId, destination.sessionId) },
            )
            StoryRecapScreen(
                viewModel = viewModel,
                onBack = navController::navigateUp,
            )
        }

        composable<ChatDestination> { entry ->
            val destination = entry.toRoute<ChatDestination>()
            LaunchedEffect(destination.runId, destination.sessionId) {
                try {
                    repository.rememberSessionLocation(destination.runId, destination.sessionId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The active session is still valid even if remembering it fails.
                }
            }
            val viewModel: ChatViewModel = koinViewModel()
            ChatScreen(
                viewModel = viewModel,
                runId = destination.runId,
                sessionId = destination.sessionId,
                onBack = navController::navigateUp,
                onOpenBranch = { runId, sessionId ->
                    navController.navigate(ChatDestination(runId, sessionId)) {
                        launchSingleTop = true
                    }
                },
                onOpenStoryRecap = {
                    navController.navigate(
                        StoryRecapDestination(destination.runId, destination.sessionId),
                    )
                },
            )
        }
    }
}

private sealed interface RestoredLocation {
    data class Run(val runId: String) : RestoredLocation
    data class Chat(val runId: String, val sessionId: String) : RestoredLocation
}

private suspend fun ZaomengRepository.resolveRestoredLocation(): RestoredLocation? {
    val savedPreferences = preferences.first()
    if (!savedPreferences.restoreLastLocation) return null
    val runId = savedPreferences.lastRunId.trim()
    if (runId.isBlank()) {
        if (savedPreferences.lastSessionId.isNotBlank()) clearLastLocation()
        return null
    }

    val runs = try {
        listRuns()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        return null
    }
    if (runs.none { it.runId == runId }) {
        clearLastLocation()
        return null
    }

    val sessionId = savedPreferences.lastSessionId.trim()
    if (sessionId.isBlank()) return RestoredLocation.Run(runId)
    val sessions = try {
        listSessions(runId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        return RestoredLocation.Run(runId)
    }
    return if (sessions.any { it.sessionId == sessionId }) {
        RestoredLocation.Chat(runId, sessionId)
    } else {
        clearLastSessionLocation()
        RestoredLocation.Run(runId)
    }
}

@Composable
private fun RunDetailWithExporter(
    viewModel: RunDetailViewModel,
    onBack: () -> Unit,
    onOpenPersona: (String, String) -> Unit,
    onOpenSessions: (String) -> Unit,
    onOpenChapters: (String) -> Unit,
    onOpenRelations: (String) -> Unit,
    onOpenWorldTimeline: (String) -> Unit,
    onOpenRedistill: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    var requestedExportName by rememberSaveable { mutableStateOf("") }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        requestedExportName = ""
        if (uri == null) {
            viewModel.consumeExportedPackage()
        } else {
            viewModel.saveExportedPackage(uri)
        }
    }

    RunDetailScreen(
        viewModel = viewModel,
        onBack = onBack,
        onOpenPersona = onOpenPersona,
        onOpenSessions = onOpenSessions,
        onOpenChapters = onOpenChapters,
        onOpenRelations = onOpenRelations,
        onOpenWorldTimeline = onOpenWorldTimeline,
        onOpenRedistill = onOpenRedistill,
        onDeleted = onDeleted,
        onExportReady = { exported ->
            if (requestedExportName != exported.filename) {
                requestedExportName = exported.filename
                saveLauncher.launch(exported.filename)
            }
        },
    )
}
