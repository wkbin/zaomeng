package top.wkbin.zaomeng.di

import org.koin.dsl.module
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.domain.chat.LoadChatSessionUseCase
import top.wkbin.zaomeng.domain.distill.EstimateDistillSamplingUseCase
import top.wkbin.zaomeng.domain.distill.SuggestRedistillSegmentsUseCase
import top.wkbin.zaomeng.domain.run.LoadRunReviewUseCase
import top.wkbin.zaomeng.domain.sessions.CreateDialogueSessionUseCase
import top.wkbin.zaomeng.domain.sessions.DeleteDialogueSessionUseCase

internal fun sharedUseCaseModule() = module {
    single { LoadChatSessionUseCase(get<ZaomengRepository>()) }
    single { EstimateDistillSamplingUseCase(get<ZaomengRepository>()) }
    single { SuggestRedistillSegmentsUseCase(get<ZaomengRepository>()) }
    single { LoadRunReviewUseCase(get<ZaomengRepository>()) }
    single { CreateDialogueSessionUseCase(get<ZaomengRepository>()) }
    single { DeleteDialogueSessionUseCase(get<ZaomengRepository>()) }
}
