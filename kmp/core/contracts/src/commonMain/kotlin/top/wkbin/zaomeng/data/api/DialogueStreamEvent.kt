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
        /** include_transcript=false 时携带：本轮新增的 transcript 条目（按 turn_id）。 */
        val appendedTranscript: List<TranscriptItemDto> = emptyList(),
        /** 会话 transcript 总条数（轻量响应仍携带）。 */
        val transcriptCount: Int = 0,
    ) : DialogueStreamEvent

    data class Failure(
        val message: String,
        val retryable: Boolean,
    ) : DialogueStreamEvent
}
