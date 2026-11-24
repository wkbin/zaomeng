package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okio.Path
import top.wkbin.zaomeng.ktor.models.DialogueResponse
import top.wkbin.zaomeng.platform.nowEpochMillis

@Serializable
enum class ReplyOperationStatus { GENERATING, COMMITTING, COMPLETED, FAILED }

@Serializable
data class ReplyOperationResult(
    @SerialName("turn_id") val turnId: String,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("transcript_count_at_commit") val transcriptCountAtCommit: Int,
    @SerialName("appended_transcript") val appendedTranscript: List<JsonObject>,
    val message: String,
    @SerialName("message_kind") val messageKind: String,
    @SerialName("input_speaker") val inputSpeaker: String = "",
    @SerialName("input_role") val inputRole: String = "user",
    val responses: List<DialogueResponse> = emptyList(),
    @SerialName("suppress_transcript_message") val suppressTranscriptMessage: Boolean = false,
    val evidence: JsonArray = JsonArray(emptyList()),
    @SerialName("world_book_matches") val worldBookMatches: JsonArray = JsonArray(emptyList()),
)

@Serializable
data class ReplyOperationFailure(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

@Serializable
data class ReplyOperationReceipt(
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("request_hash") val requestHash: String,
    val status: ReplyOperationStatus,
    val attempt: Int,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("base_revision") val baseRevision: Long,
    val result: ReplyOperationResult? = null,
    val failure: ReplyOperationFailure? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

sealed interface OperationClaim {
    data class Owner(val receipt: ReplyOperationReceipt) : OperationClaim
    data class Replay(val receipt: ReplyOperationReceipt) : OperationClaim
    data class Wait(val receipt: ReplyOperationReceipt) : OperationClaim
    data class Conflict(val code: String, val message: String) : OperationClaim
}

/** operation 回执的持久状态机；所有转换都在 receipt 路径锁内完成。 */
class ReplyOperationStore(private val storage: StorageService) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun get(runId: String, sessionId: String, operationId: String): ReplyOperationReceipt? {
        val path = receiptPath(runId, sessionId, operationId)
        val content = storage.readTextOrNull(path) ?: return null
        return decode(content)
    }

    fun listCompleted(runId: String, sessionId: String): List<ReplyOperationReceipt> {
        val directory = operationDirectory(runId, sessionId)
        if (!storage.isDirectory(directory)) return emptyList()
        return storage.listFiles(directory).mapNotNull { operationDir ->
            storage.readTextOrNull(operationDir / "receipt.json")
                ?.let { content -> runCatching { decode(content) }.getOrNull() }
        }.filter { it.status == ReplyOperationStatus.COMPLETED && it.result != null }
    }

    /** 进程内没有活跃 owner 时调用；COMMITTING 保留结果供恢复，GENERATING 转为可重试失败。 */
    fun recoverOrphan(runId: String, sessionId: String, operationId: String): ReplyOperationReceipt? {
        val path = receiptPath(runId, sessionId, operationId)
        if (!storage.exists(path)) return null
        return storage.updateExistingSessionText(runId, sessionId, path) { original ->
            val receipt = original?.let(::decode)
                ?: throw NoSuchElementException("Operation receipt not found: $operationId")
            val now = nowEpochMillis()
            val updated = when (receipt.status) {
                ReplyOperationStatus.GENERATING -> receipt.copy(
                    status = ReplyOperationStatus.FAILED,
                    ownerId = "",
                    failure = ReplyOperationFailure(
                        code = "OPERATION_INTERRUPTED",
                        message = "生成被服务重启中断，可安全重试。",
                        retryable = true,
                    ),
                    updatedAt = now,
                )
                ReplyOperationStatus.COMMITTING -> receipt.copy(ownerId = "", updatedAt = now)
                else -> receipt
            }
            encode(updated) to updated
        }
    }

    fun claim(
        runId: String,
        sessionId: String,
        operationId: String,
        turnId: String,
        requestHash: String,
        baseRevision: Long,
        ownerId: String,
    ): OperationClaim {
        val path = receiptPath(runId, sessionId, operationId)
        return storage.updateExistingSessionText(runId, sessionId, path) { original ->
            val now = nowEpochMillis()
            if (original == null) {
                val receipt = ReplyOperationReceipt(
                    runId = runId,
                    sessionId = sessionId,
                    operationId = operationId,
                    turnId = turnId,
                    requestHash = requestHash,
                    status = ReplyOperationStatus.GENERATING,
                    attempt = 1,
                    ownerId = ownerId,
                    baseRevision = baseRevision,
                    createdAt = now,
                    updatedAt = now,
                )
                return@updateExistingSessionText encode(receipt) to OperationClaim.Owner(receipt)
            }
            val current = decode(original)
            if (current.requestHash != requestHash) {
                return@updateExistingSessionText original to OperationClaim.Conflict(
                    "OPERATION_ID_CONFLICT",
                    "operation_id 已用于不同的请求。",
                )
            }
            when (current.status) {
                ReplyOperationStatus.COMPLETED -> {
                    val result = current.result
                    original to if (result == null) {
                        OperationClaim.Conflict("OPERATION_RESULT_UNAVAILABLE", "已完成回执缺少结果，请重新同步会话。")
                    } else {
                        OperationClaim.Replay(current)
                    }
                }
                ReplyOperationStatus.GENERATING -> original to OperationClaim.Wait(current)
                ReplyOperationStatus.COMMITTING -> {
                    if (current.ownerId.isNotBlank() || current.result == null) {
                        original to OperationClaim.Wait(current)
                    } else {
                        val resumed = current.copy(
                            attempt = current.attempt + 1,
                            ownerId = ownerId,
                            failure = null,
                            updatedAt = now,
                        )
                        encode(resumed) to OperationClaim.Owner(resumed)
                    }
                }
                ReplyOperationStatus.FAILED -> {
                    val failure = current.failure
                    if (failure?.retryable != true) {
                        original to OperationClaim.Conflict(
                            failure?.code ?: "OPERATION_FAILED",
                            failure?.message ?: "操作失败。",
                        )
                    } else {
                        val retry = current.copy(
                            status = ReplyOperationStatus.GENERATING,
                            attempt = current.attempt + 1,
                            ownerId = ownerId,
                            baseRevision = baseRevision,
                            result = null,
                            failure = null,
                            updatedAt = now,
                        )
                        encode(retry) to OperationClaim.Owner(retry)
                    }
                }
            }
        }
    }

    fun markCommitting(
        receipt: ReplyOperationReceipt,
        result: ReplyOperationResult,
    ): ReplyOperationReceipt = update(receipt) { current ->
        checkOwner(receipt, current)
        check(current.status == ReplyOperationStatus.GENERATING || current.status == ReplyOperationStatus.COMMITTING) {
            "Operation cannot enter COMMITTING from ${current.status}"
        }
        current.copy(
            status = ReplyOperationStatus.COMMITTING,
            result = result,
            failure = null,
            updatedAt = nowEpochMillis(),
        )
    }

    fun markCompleted(receipt: ReplyOperationReceipt): ReplyOperationReceipt = update(receipt) { current ->
        checkOwner(receipt, current)
        if (current.status == ReplyOperationStatus.COMPLETED) {
            current
        } else {
            check(current.status == ReplyOperationStatus.COMMITTING && current.result != null) {
                "Operation cannot complete without a committing result"
            }
            current.copy(status = ReplyOperationStatus.COMPLETED, failure = null, updatedAt = nowEpochMillis())
        }
    }

    fun markFailed(
        receipt: ReplyOperationReceipt,
        failure: ReplyOperationFailure,
    ): ReplyOperationReceipt = update(receipt) { current ->
        checkOwner(receipt, current)
        if (current.status == ReplyOperationStatus.COMPLETED) {
            current
        } else {
            current.copy(
                status = ReplyOperationStatus.FAILED,
                ownerId = "",
                failure = failure,
                updatedAt = nowEpochMillis(),
            )
        }
    }

    private fun update(
        receipt: ReplyOperationReceipt,
        transform: (ReplyOperationReceipt) -> ReplyOperationReceipt,
    ): ReplyOperationReceipt {
        val path = receiptPath(receipt)
        return storage.updateExistingSessionText(receipt.runId, receipt.sessionId, path) { original ->
            val current = original?.let(::decode)
                ?: throw IllegalStateException("Operation receipt not found: ${receipt.operationId}")
            val updated = transform(current)
            encode(updated) to updated
        }
    }

    private fun checkOwner(expected: ReplyOperationReceipt, actual: ReplyOperationReceipt) {
        check(expected.operationId == actual.operationId && expected.attempt == actual.attempt && expected.ownerId == actual.ownerId) {
            "Operation owner or attempt changed"
        }
    }

    private fun receiptPath(runId: String, sessionId: String, operationId: String): Path {
        val safeOperationId = PathSafety.validateStorageId(operationId, "operation_id")
        return operationDirectory(runId, sessionId) / "$safeOperationId/receipt.json"
    }

    private fun operationDirectory(runId: String, sessionId: String): Path =
        requireNotNull(storage.getDialogueSessionManifestFile(runId, sessionId).parent) / "operations"

    private fun receiptPath(receipt: ReplyOperationReceipt): Path =
        receiptPath(receipt.runId, receipt.sessionId, receipt.operationId)

    private fun encode(receipt: ReplyOperationReceipt): String =
        json.encodeToString(ReplyOperationReceipt.serializer(), receipt)

    private fun decode(content: String): ReplyOperationReceipt =
        json.decodeFromString(ReplyOperationReceipt.serializer(), content)
}
