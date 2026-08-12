package top.wkbin.zaomeng.di

import org.koin.dsl.module
import top.wkbin.zaomeng.data.AppRuntimeRepository
import top.wkbin.zaomeng.data.AppRuntimeRepositoryImpl
import top.wkbin.zaomeng.data.CardRepository
import top.wkbin.zaomeng.data.CardRepositoryImpl
import top.wkbin.zaomeng.data.ChapterRepository
import top.wkbin.zaomeng.data.ChapterRepositoryImpl
import top.wkbin.zaomeng.data.ChatSessionGatewayImpl
import top.wkbin.zaomeng.data.CreateDialogueSessionGatewayImpl
import top.wkbin.zaomeng.data.DeleteDialogueSessionGatewayImpl
import top.wkbin.zaomeng.data.DiagnosticsRepository
import top.wkbin.zaomeng.data.DiagnosticsRepositoryImpl
import top.wkbin.zaomeng.data.DialogueRepository
import top.wkbin.zaomeng.data.DialogueRepositoryImpl
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.ModelSettingsRepositoryImpl
import top.wkbin.zaomeng.data.OriginalKnowledgeRepository
import top.wkbin.zaomeng.data.OriginalKnowledgeRepositoryImpl
import top.wkbin.zaomeng.data.PersonaRepository
import top.wkbin.zaomeng.data.PersonaRepositoryImpl
import top.wkbin.zaomeng.data.PluginRepository
import top.wkbin.zaomeng.data.PluginRepositoryImpl
import top.wkbin.zaomeng.data.RelationsRepository
import top.wkbin.zaomeng.data.RelationsRepositoryImpl
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.RunRepositoryImpl
import top.wkbin.zaomeng.data.RunReviewRepositoryImpl
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.SessionRepositoryImpl
import top.wkbin.zaomeng.data.WorldMemoryRepository
import top.wkbin.zaomeng.data.WorldMemoryRepositoryImpl
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
import top.wkbin.zaomeng.domain.chat.ChatSessionGateway
import top.wkbin.zaomeng.domain.distill.DistillPlanningGateway
import top.wkbin.zaomeng.domain.run.RunReviewGateway
import top.wkbin.zaomeng.domain.sessions.CreateDialogueSessionGateway
import top.wkbin.zaomeng.domain.sessions.DeleteDialogueSessionGateway

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

    single { RunRepositoryImpl(get(), get(), get(), get()) }

    single<AppRuntimeRepository> { AppRuntimeRepositoryImpl(get(), get()) }
    single<ModelSettingsRepository> { ModelSettingsRepositoryImpl(get(), get()) }
    single<DiagnosticsRepository> { DiagnosticsRepositoryImpl(get()) }
    single<PluginRepository> { PluginRepositoryImpl(get()) }
    single<RunRepository> { get<RunRepositoryImpl>() }
    single<WorldMemoryRepository> { WorldMemoryRepositoryImpl(get()) }
    single<RelationsRepository> { RelationsRepositoryImpl(get()) }
    single<SessionRepository> { SessionRepositoryImpl(get(), get(), get()) }
    single<DialogueRepository> { DialogueRepositoryImpl(get()) }
    single<PersonaRepository> { PersonaRepositoryImpl(get()) }
    single<OriginalKnowledgeRepository> { OriginalKnowledgeRepositoryImpl(get()) }
    single<ChapterRepository> { ChapterRepositoryImpl(get()) }
    single<CardRepository> { CardRepositoryImpl(get()) }

    single<ChatSessionGateway> { ChatSessionGatewayImpl(get(), get(), get()) }
    single<DistillPlanningGateway> { get<RunRepositoryImpl>() }
    single<RunReviewGateway> { RunReviewRepositoryImpl(get(), get(), get()) }
    single<CreateDialogueSessionGateway> { CreateDialogueSessionGatewayImpl(get()) }
    single<DeleteDialogueSessionGateway> { DeleteDialogueSessionGatewayImpl(get()) }
}
