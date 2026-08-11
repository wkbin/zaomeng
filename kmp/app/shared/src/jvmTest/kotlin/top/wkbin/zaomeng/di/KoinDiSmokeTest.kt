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
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.library.OnlineLibraryRepository
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
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
        val repository: ZaomengRepository by inject()
        val preferences: AppPreferencesRepository by inject()
        val onlineLibrary: OnlineLibraryRepository by inject()

        assertNotNull(distillation)
        assertNotNull(conversion)
        assertNotNull(backend)
        assertNotNull(endpointProvider)
        assertNotNull(secureStore)
        assertNotNull(appPlatform)
        assertNotNull(repository)
        assertNotNull(preferences)
        assertNotNull(onlineLibrary)
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }
}
