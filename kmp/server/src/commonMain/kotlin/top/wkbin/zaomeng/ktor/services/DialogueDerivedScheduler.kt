package top.wkbin.zaomeng.ktor.services

/** 历史变更后的派生调度入口，避免高级服务绕过持久派生管线。 */
interface DialogueDerivedScheduler {
    fun prepareDerivedResult(
        runId: String,
        sessionId: String,
        result: ReplyOperationResult,
        kind: DerivedOperationKind,
    ): DerivedOperation

    fun scheduleDerivedResult(
        runId: String,
        sessionId: String,
        result: ReplyOperationResult,
        kind: DerivedOperationKind,
    ): DerivedOperation
}
