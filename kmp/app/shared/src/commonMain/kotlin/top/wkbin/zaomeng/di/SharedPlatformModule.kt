package top.wkbin.zaomeng.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import org.koin.dsl.module
import top.wkbin.zaomeng.backend.BackendController
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.NovelConversionForeground
import top.wkbin.zaomeng.platform.SecureKeyValueStore

internal fun sharedPlatformModule(platform: AppPlatform) = module {
    single<AppPlatform> { platform }
    single<DataStore<Preferences>> { platform.dataStore }
    single<SecureKeyValueStore> { platform.secureStore }
    single<DistillationForeground> { platform.distillationForeground }
    single<NovelConversionForeground> { platform.novelConversionForeground }
    single<BackendController> { platform.backendController }
    single<BackendEndpointProvider> { platform.backendEndpointProvider }
}
