package top.wkbin.zaomeng.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import top.wkbin.zaomeng.backend.EmbeddedBackendController
import top.wkbin.zaomeng.backend.InstallationTokenStore
import top.wkbin.zaomeng.backend.ModelApiKeyStore
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.LocalApiFactory
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.library.OnlineLibraryRepository
import top.wkbin.zaomeng.feature.library.OnlineLibraryViewModel
import top.wkbin.zaomeng.feature.bookshelf.BookshelfViewModel
import top.wkbin.zaomeng.feature.chat.ChatViewModel
import top.wkbin.zaomeng.feature.chapters.ChaptersViewModel
import top.wkbin.zaomeng.feature.cards.CardLibraryViewModel
import top.wkbin.zaomeng.feature.importbook.ImportBookViewModel
import top.wkbin.zaomeng.feature.persona.PersonaViewModel
import top.wkbin.zaomeng.feature.crossover.CrossoverViewModel
import top.wkbin.zaomeng.feature.rundetail.RunDetailViewModel
import top.wkbin.zaomeng.feature.redistill.RedistillViewModel
import top.wkbin.zaomeng.feature.relations.RelationsViewModel
import top.wkbin.zaomeng.feature.sessions.SessionsViewModel
import top.wkbin.zaomeng.feature.timeline.WorldTimelineViewModel
import top.wkbin.zaomeng.feature.storyrecap.StoryRecapViewModel
import top.wkbin.zaomeng.feature.settings.SettingsViewModel
import top.wkbin.zaomeng.feature.settings.ModelProfileEditorViewModel
import top.wkbin.zaomeng.feature.settings.ModelProfilesViewModel
import top.wkbin.zaomeng.feature.settings.PluginsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { androidContext().preferencesDataStoreFile("zaomeng.preferences_pb") },
        )
    }
    single { InstallationTokenStore(androidContext()) }
    single { ModelApiKeyStore(androidContext()) }
    single { LocalApiFactory() }
    single {
        EmbeddedBackendController(
            context = androidContext(),
            tokenStore = get(),
            modelApiKeyStore = get(),
            apiFactory = get(),
        )
    }
    single { AppPreferencesRepository(get()) }
    single { OnlineLibraryRepository(androidContext()) }
    single { ZaomengRepository(get(), get(), get()) }

    viewModel { BookshelfViewModel(get(), androidContext()) }
    viewModel { SettingsViewModel(get(), androidContext()) }
    viewModel { ModelProfilesViewModel(get()) }
    viewModel { PluginsViewModel(get()) }
    viewModel { parameters -> ModelProfileEditorViewModel(get(), parameters.get()) }
    viewModel { ImportBookViewModel(get(), androidContext()) }
    viewModel { OnlineLibraryViewModel(get(), get()) }
    viewModel { CrossoverViewModel(get()) }
    viewModel { parameters -> RunDetailViewModel(get(), parameters.get(), androidContext()) }
    viewModel { parameters -> RedistillViewModel(get(), parameters.get(), androidContext()) }
    viewModel { parameters -> RelationsViewModel(get(), parameters.get()) }
    viewModel { parameters -> WorldTimelineViewModel(get(), parameters.get()) }
    viewModel { parameters -> StoryRecapViewModel(get(), parameters.get(), parameters.get()) }
    viewModel { parameters -> ChaptersViewModel(get(), parameters.get(), androidContext()) }
    viewModel { CardLibraryViewModel(get()) }
    viewModel { PersonaViewModel(get()) }
    viewModel { SessionsViewModel(get()) }
    viewModel { ChatViewModel(get(), get()) }
}
