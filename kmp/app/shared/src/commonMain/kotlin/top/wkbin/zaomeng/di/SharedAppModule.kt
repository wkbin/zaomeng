package top.wkbin.zaomeng.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import top.wkbin.zaomeng.backend.BackendController
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.LocalBackendController
import top.wkbin.zaomeng.backend.LocalBackendEndpointProvider
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.KtorCardsClient
import top.wkbin.zaomeng.data.api.KtorChapterClient
import top.wkbin.zaomeng.data.api.KtorDiagnosticsClient
import top.wkbin.zaomeng.data.api.KtorDialogueClient
import top.wkbin.zaomeng.data.api.KtorHttpClientProvider
import top.wkbin.zaomeng.data.api.KtorModelSettingsClient
import top.wkbin.zaomeng.data.api.KtorPersonaClient
import top.wkbin.zaomeng.data.api.KtorPluginClient
import top.wkbin.zaomeng.data.api.KtorRelationsClient
import top.wkbin.zaomeng.data.api.KtorRunManagementClient
import top.wkbin.zaomeng.data.api.KtorRunOpsClient
import top.wkbin.zaomeng.data.api.KtorRunsClient
import top.wkbin.zaomeng.data.api.KtorSessionClient
import top.wkbin.zaomeng.data.api.KtorWorldMemoryClient
import top.wkbin.zaomeng.data.api.KtorOriginalKnowledgeClient
import top.wkbin.zaomeng.data.library.OnlineLibraryRepository
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ContentDisclaimerPreferences
import top.wkbin.zaomeng.feature.bookshelf.BookshelfViewModel
import top.wkbin.zaomeng.feature.chapters.ChaptersViewModel
import top.wkbin.zaomeng.feature.chat.ChatViewModel
import top.wkbin.zaomeng.feature.sessions.SessionsViewModel
import top.wkbin.zaomeng.feature.settings.SettingsViewModel
import top.wkbin.zaomeng.feature.settings.ModelProfileEditorViewModel
import top.wkbin.zaomeng.feature.settings.ModelProfilesViewModel
import top.wkbin.zaomeng.feature.settings.PluginsViewModel
import top.wkbin.zaomeng.feature.cards.CardLibraryViewModel
import top.wkbin.zaomeng.feature.crossover.CrossoverViewModel
import top.wkbin.zaomeng.feature.library.OnlineLibraryViewModel
import top.wkbin.zaomeng.feature.persona.PersonaViewModel
import top.wkbin.zaomeng.feature.relations.RelationsViewModel
import top.wkbin.zaomeng.feature.storyrecap.StoryRecapViewModel
import top.wkbin.zaomeng.feature.timeline.WorldTimelineViewModel
import top.wkbin.zaomeng.feature.originalknowledge.OriginalKnowledgeViewModel
import top.wkbin.zaomeng.feature.importbook.ImportBookViewModel
import top.wkbin.zaomeng.feature.redistill.RedistillViewModel
import top.wkbin.zaomeng.feature.rundetail.RunDetailViewModel
import org.koin.core.parameter.parametersOf
import top.wkbin.zaomeng.platform.SecureKeyValueStore
import top.wkbin.zaomeng.platform.ServerPlatform
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.NovelConversionForeground

/** 跨平台 Koin 模块：数据层 + 书卷架 ViewModel（feature 逐个追加）。 */
fun sharedAppModule(platform: AppPlatform): Module = module {
    single<AppPlatform> { platform }
    single<DataStore<Preferences>> { platform.dataStore }
    single<ServerPlatform> { platform.serverPlatform }
    single<SecureKeyValueStore> { platform.serverPlatform.secureStore() }
    single<DistillationForeground> { platform.distillationForeground }
    single<NovelConversionForeground> { platform.novelConversionForeground }
    single<BackendController> {
        LocalBackendController(platform.serverPlatform, platform.backendPort, platform.backendToken)
    }
    single<BackendEndpointProvider> {
        LocalBackendEndpointProvider(get<BackendController>() as LocalBackendController, platform.backendToken)
    }

    single { AppPreferencesRepository(get()) }
    single { ContentDisclaimerPreferences(get()) }
    single { KtorHttpClientProvider(get()) }

    single { KtorModelSettingsClient(get(), get()) }
    single { KtorPluginClient(get(), get()) }
    single { KtorRunsClient(get(), get()) }
    single { KtorRunManagementClient(get(), get()) }
    single { KtorSessionClient(get(), get()) }
    single { KtorChapterClient(get(), get()) }
    single { KtorDiagnosticsClient(get(), get()) }
    single { KtorCardsClient(get(), get()) }
    single { KtorPersonaClient(get(), get()) }
    single { KtorDialogueClient(get(), get()) }
    single { KtorWorldMemoryClient(get(), get()) }
    single { KtorRelationsClient(get(), get()) }
    single { KtorRunOpsClient(get(), get()) }
    single { KtorOriginalKnowledgeClient(get(), get()) }

    single { OnlineLibraryRepository(platform.cacheDir) }
    single {
        ZaomengRepository(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }

    viewModel { BookshelfViewModel(get(), get()) }
    viewModel { SessionsViewModel(get()) }
    viewModel { parameters ->
        ChaptersViewModel(
            repository = get(),
            runId = parameters.get(),
            cacheDir = platform.cacheDir,
            novelConversionForeground = platform.novelConversionForeground,
        )
    }
    viewModel { ChatViewModel(get(), get()) }
    viewModel { SettingsViewModel(get()) }
    viewModel { ModelProfilesViewModel(get()) }
    viewModel { PluginsViewModel(get()) }
    viewModel { parameters -> ModelProfileEditorViewModel(get(), parameters.get()) }
    viewModel { CardLibraryViewModel(get()) }
    viewModel { CrossoverViewModel(get()) }
    viewModel { parameters -> RelationsViewModel(get(), parameters.get()) }
    viewModel { parameters -> WorldTimelineViewModel(get(), parameters.get()) }
    viewModel { parameters -> OriginalKnowledgeViewModel(get(), parameters.get()) }
    viewModel { parameters -> StoryRecapViewModel(get(), parameters.get(), parameters.get()) }
    viewModel { OnlineLibraryViewModel(get(), get()) }
    viewModel { PersonaViewModel(get()) }
    viewModel { ImportBookViewModel(get(), get()) }
    viewModel { parameters -> RedistillViewModel(get(), parameters.get(), get()) }
    viewModel { parameters ->
        RunDetailViewModel(get(), parameters.get(), platform.cacheDir, get())
    }
}
