package top.wkbin.zaomeng.data.api

sealed interface DialogueStreamEvent {
    data class Status(
        val phase: String,
        val message: String,
    ) : DialogueStreamEvent

    data class Delta(
        val index: Int,
        val speaker: String,
        val role: String,
        val text: String,
        val field: String = "message",
    ) : DialogueStreamEvent

    data class Reset(
        val message: String,
    ) : DialogueStreamEvent

    data class Complete(
        val session: DialogueSessionDto,
        val replayed: Boolean,
    ) : DialogueStreamEvent

    data class Failure(
        val message: String,
        val retryable: Boolean,
    ) : DialogueStreamEvent
}
