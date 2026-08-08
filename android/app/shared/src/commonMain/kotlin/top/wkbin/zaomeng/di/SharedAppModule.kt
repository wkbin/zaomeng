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
import top.wkbin.zaomeng.data.library.OnlineLibraryRepository
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ContentDisclaimerPreferences
import top.wkbin.zaomeng.feature.bookshelf.BookshelfViewModel
import top.wkbin.zaomeng.feature.chapters.ChaptersViewModel
import top.wkbin.zaomeng.feature.chat.ChatViewModel
import top.wkbin.zaomeng.feature.sessions.SessionsViewModel
import org.koin.core.parameter.parametersOf
import top.wkbin.zaomeng.platform.SecureKeyValueStore
import top.wkbin.zaomeng.platform.ServerPlatform

/** 跨平台 Koin 模块：数据层 + 书卷架 ViewModel（feature 逐个追加）。 */
fun sharedAppModule(platform: AppPlatform): Module = module {
    single<DataStore<Preferences>> { platform.dataStore }
    single<ServerPlatform> { platform.serverPlatform }
    single<SecureKeyValueStore> { platform.serverPlatform.secureStore() }
    single<BackendController> {
        LocalBackendController(platform.serverPlatform, platform.backendPort, platform.backendToken)
    }
    single<BackendEndpointProvider> {
        LocalBackendEndpointProvider(platform.backendPort, platform.backendToken)
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
}
