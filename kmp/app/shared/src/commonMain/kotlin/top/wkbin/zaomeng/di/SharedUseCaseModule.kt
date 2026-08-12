package top.wkbin.zaomeng.di

import org.koin.dsl.module
import top.wkbin.zaomeng.domain.chat.LoadChatSessionUseCase
import top.wkbin.zaomeng.domain.chat.ChatSessionGateway
import top.wkbin.zaomeng.domain.distill.EstimateDistillSamplingUseCase
import top.wkbin.zaomeng.domain.distill.DistillPlanningGateway
import top.wkbin.zaomeng.domain.distill.SuggestRedistillSegmentsUseCase
import top.wkbin.zaomeng.domain.run.LoadRunReviewUseCase
import top.wkbin.zaomeng.domain.run.RunReviewGateway
import top.wkbin.zaomeng.domain.sessions.CreateDialogueSessionUseCase
import top.wkbin.zaomeng.domain.sessions.CreateDialogueSessionGateway
import top.wkbin.zaomeng.domain.sessions.DeleteDialogueSessionUseCase
import top.wkbin.zaomeng.domain.sessions.DeleteDialogueSessionGateway

internal fun sharedUseCaseModule() = module {
    single { LoadChatSessionUseCase(get<ChatSessionGateway>()) }
    single { EstimateDistillSamplingUseCase(get<DistillPlanningGateway>()) }
    single { SuggestRedistillSegmentsUseCase(get<DistillPlanningGateway>()) }
    single { LoadRunReviewUseCase(get<RunReviewGateway>()) }
    single { CreateDialogueSessionUseCase(get<CreateDialogueSessionGateway>()) }
    single { DeleteDialogueSessionUseCase(get<DeleteDialogueSessionGateway>()) }
}
