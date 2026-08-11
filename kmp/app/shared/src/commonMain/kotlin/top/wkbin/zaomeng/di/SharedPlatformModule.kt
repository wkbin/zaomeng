package top.wkbin.zaomeng.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import org.koin.dsl.module
import top.wkbin.zaomeng.backend.BackendController
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.LocalBackendController
import top.wkbin.zaomeng.backend.LocalBackendEndpointProvider
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.NovelConversionForeground
import top.wkbin.zaomeng.platform.SecureKeyValueStore
import top.wkbin.zaomeng.platform.ServerPlatform

internal fun sharedPlatformModule(platform: AppPlatform) = module {
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
}
