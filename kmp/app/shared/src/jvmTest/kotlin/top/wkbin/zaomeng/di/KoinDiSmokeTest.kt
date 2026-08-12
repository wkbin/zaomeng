package top.wkbin.zaomeng.di

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import top.wkbin.zaomeng.backend.BackendController
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.data.CardRepository
import top.wkbin.zaomeng.data.DialogueRepository
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.PersonaRepository
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.library.OnlineLibraryRepository
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.domain.chat.ChatSessionGateway
import top.wkbin.zaomeng.domain.distill.DistillPlanningGateway
import top.wkbin.zaomeng.domain.run.RunReviewGateway
import top.wkbin.zaomeng.domain.sessions.CreateDialogueSessionGateway
import top.wkbin.zaomeng.domain.sessions.DeleteDialogueSessionGateway
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.NovelConversionForeground
import top.wkbin.zaomeng.platform.SecureKeyValueStore

/** 桌面平台 DI 冒烟：确保共享模块的平台服务与数据层定义都能解析（防止运行期 NoDefinitionFound）。 */
class KoinDiSmokeTest : KoinComponent {
    @Test
    fun `desktop koin graph resolves platform services and data layer`() {
        startKoin { modules(sharedAppModules(DesktopAppPlatform())) }

        val distillation: DistillationForeground by inject()
        val conversion: NovelConversionForeground by inject()
        val backend: BackendController by inject()
        val endpointProvider: BackendEndpointProvider by inject()
        val secureStore: SecureKeyValueStore by inject()
        val appPlatform: AppPlatform by inject()
        val preferences: AppPreferencesRepository by inject()
        val onlineLibrary: OnlineLibraryRepository by inject()
        val chatGateway: ChatSessionGateway by inject()
        val distillGateway: DistillPlanningGateway by inject()
        val runReviewGateway: RunReviewGateway by inject()
        val createSessionGateway: CreateDialogueSessionGateway by inject()
        val deleteSessionGateway: DeleteDialogueSessionGateway by inject()
        val runRepository: RunRepository by inject()
        val sessionRepository: SessionRepository by inject()
        val dialogueRepository: DialogueRepository by inject()
        val personaRepository: PersonaRepository by inject()
        val modelSettingsRepository: ModelSettingsRepository by inject()
        val cardRepository: CardRepository by inject()

        assertNotNull(distillation)
        assertNotNull(conversion)
        assertNotNull(backend)
        assertNotNull(endpointProvider)
        assertNotNull(secureStore)
        assertNotNull(appPlatform)
        assertNotNull(preferences)
        assertNotNull(onlineLibrary)
        assertNotNull(chatGateway)
        assertNotNull(distillGateway)
        assertNotNull(runReviewGateway)
        assertNotNull(createSessionGateway)
        assertNotNull(deleteSessionGateway)
        assertNotNull(runRepository)
        assertNotNull(sessionRepository)
        assertNotNull(dialogueRepository)
        assertNotNull(personaRepository)
        assertNotNull(modelSettingsRepository)
        assertNotNull(cardRepository)
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }
}
