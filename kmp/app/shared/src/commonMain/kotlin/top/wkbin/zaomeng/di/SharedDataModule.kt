package top.wkbin.zaomeng.di

import org.koin.dsl.module
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.KtorCardsClient
import top.wkbin.zaomeng.data.api.KtorChapterClient
import top.wkbin.zaomeng.data.api.KtorDiagnosticsClient
import top.wkbin.zaomeng.data.api.KtorDialogueClient
import top.wkbin.zaomeng.data.api.KtorHttpClientProvider
import top.wkbin.zaomeng.data.api.KtorModelSettingsClient
import top.wkbin.zaomeng.data.api.KtorOriginalKnowledgeClient
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

internal fun sharedDataModule(platform: AppPlatform) = module {
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
}
