package top.wkbin.zaomeng.di

import org.koin.core.module.Module

/** Composition root kept small so platform, data, use-case and presentation bindings stay independent. */
fun sharedAppModules(platform: AppPlatform): List<Module> = listOf(
    sharedPlatformModule(platform),
    sharedDataModule(platform),
    sharedUseCaseModule(),
    sharedViewModelModule(platform),
)
