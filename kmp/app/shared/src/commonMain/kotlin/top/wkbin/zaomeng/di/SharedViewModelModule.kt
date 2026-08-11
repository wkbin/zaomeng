package top.wkbin.zaomeng.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import top.wkbin.zaomeng.feature.bookshelf.BookshelfViewModel
import top.wkbin.zaomeng.feature.cards.CardLibraryViewModel
import top.wkbin.zaomeng.feature.chapters.ChaptersViewModel
import top.wkbin.zaomeng.feature.chat.ChatViewModel
import top.wkbin.zaomeng.feature.crossover.CrossoverViewModel
import top.wkbin.zaomeng.feature.importbook.ImportBookViewModel
import top.wkbin.zaomeng.feature.library.OnlineLibraryViewModel
import top.wkbin.zaomeng.feature.originalknowledge.OriginalKnowledgeViewModel
import top.wkbin.zaomeng.feature.persona.PersonaViewModel
import top.wkbin.zaomeng.feature.redistill.RedistillViewModel
import top.wkbin.zaomeng.feature.relations.RelationsViewModel
import top.wkbin.zaomeng.feature.rundetail.RunDetailViewModel
import top.wkbin.zaomeng.feature.sessions.SessionsViewModel
import top.wkbin.zaomeng.feature.settings.ModelProfileEditorViewModel
import top.wkbin.zaomeng.feature.settings.ModelProfilesViewModel
import top.wkbin.zaomeng.feature.settings.PluginsViewModel
import top.wkbin.zaomeng.feature.settings.SettingsViewModel
import top.wkbin.zaomeng.feature.storyrecap.StoryRecapViewModel
import top.wkbin.zaomeng.feature.timeline.WorldTimelineViewModel

internal fun sharedViewModelModule(platform: AppPlatform) = module {
    viewModel { BookshelfViewModel(get(), get()) }
    viewModel { SessionsViewModel(get(), get(), get()) }
    viewModel { parameters ->
        ChaptersViewModel(
            repository = get(),
            runId = parameters.get(),
            cacheDir = platform.cacheDir,
            novelConversionForeground = platform.novelConversionForeground,
        )
    }
    viewModel { ChatViewModel(get(), get(), get()) }
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
    viewModel { ImportBookViewModel(get(), get(), get()) }
    viewModel { parameters -> RedistillViewModel(get(), parameters.get(), get(), get(), get()) }
    viewModel { parameters ->
        RunDetailViewModel(get(), parameters.get(), platform.cacheDir, get(), get())
    }
}
