package top.wkbin.zaomeng.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
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

/**
 * nav3 导航宿主：书卷架已迁移，其余目的地为占位页（按 feature 逐个替换）。
 *
 * 使用非持久化 NavBackStack（进程死亡不恢复）；后续需要恢复时可换
 * rememberNavBackStack(SavedStateConfiguration) + NavKey 多态注册。
 */
@Composable
fun ZaomengNavHost() {
    val backStack = remember { NavBackStack<NavKey>(BookshelfDestination) }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider<NavKey> {
            entry(BookshelfDestination) {
                val viewModel: BookshelfViewModel = koinViewModel()
                BookshelfScreen(
                    viewModel = viewModel,
                    onImport = { backStack.add(ImportBookDestination) },
                    onOpenSettings = { backStack.add(ModelSettingsDestination) },
                    onOpenCards = { backStack.add(CardLibraryDestination) },
                    onOpenSessions = { backStack.add(SessionsDestination()) },
                    onOpenCrossover = { backStack.add(CrossoverDestination) },
                    onOpenRun = { runId -> backStack.add(RunDetailDestination(runId)) },
                )
            }
            entry(ImportBookDestination) { PlaceholderScreen("导入书（迁移中）") }
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
            entry(AppUpdateDestination) { PlaceholderScreen("更新（迁移中）") }
            entry(CardLibraryDestination) { PlaceholderScreen("卡库（迁移中）") }
            entry(CrossoverDestination) { PlaceholderScreen("联动（迁移中）") }
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
            addEntryProvider(clazz = RunDetailDestination::class) { PlaceholderScreen("运行详情（迁移中）") }
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
            addEntryProvider(clazz = StoryRecapDestination::class) { PlaceholderScreen("剧情回顾（迁移中）") }
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
            addEntryProvider(clazz = PersonaDestination::class) { PlaceholderScreen("人物（迁移中）") }
        },
    )
}

@Composable
private fun PlaceholderScreen(title: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            "该页面迁移中，后续 feature 会替换此占位。",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
