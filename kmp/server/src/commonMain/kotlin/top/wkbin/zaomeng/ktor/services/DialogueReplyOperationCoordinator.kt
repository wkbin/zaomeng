package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import top.wkbin.zaomeng.ktor.models.DialogueResponse
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.randomUuid

class DialogueOperationException(
    val code: String,
    message: String,
    val retryable: Boolean,
) : IllegalStateException(message)

data class ReplyOperationRequest(
    val runId: String,
    val sessionId: String,
    val operationId: String = "",
    val message: String,
    val messageKind: String,
    val pacing: String = "normal",
    val speakerOverride: String = "",
    val suppressTranscriptMessage: Boolean = false,
    val includeInnerThoughts: Boolean = false,
)

data class ActiveReplyOperation(
    val key: String,
    val mutex: Mutex,
    var receipt: ReplyOperationReceipt,
    val replayed: Boolean,
)

sealed interface ReplyOperationBegin {
    data class Active(
        val lease: ActiveReplyOperation,
        val sessionManifest: JsonObject,
    ) : ReplyOperationBegin

    data class Replay(
        val receipt: ReplyOperationReceipt,
        val sessionManifest: JsonObject,
    ) : ReplyOperationBegin
}

data class DialogueCommitResult(
    val operationId: String,
    val turnId: String,
    val alreadyCommitted: Boolean,
    val baseRevision: Long,
    val committedRevision: Long,
    val manifest: JsonObject,
    val appendedTranscript: List<JsonObject>,
    val responses: List<DialogueResponse> = emptyList(),
)

data class DialogueReplyOutcome(
    val operationId: String,
    val turnId: String,
    val replayed: Boolean,
    val baseRevision: Long,
    val committedRevision: Long,
    val sessionManifest: JsonObject,
    val appendedTranscript: List<JsonObject>,
    val responses: List<DialogueResponse> = emptyList(),
)

/** 流式/非流式共享的 operation 编排与单进程 generation 串行化。 */
class DialogueReplyOperationCoordinator(private val storage: StorageService) {
    private val store = ReplyOperationStore(storage)
    private val mutexes = HashMap<String, Mutex>()
    private val active = HashSet<String>()
    private val registryLock = SimpleLock()

    suspend fun begin(request: ReplyOperationRequest): ReplyOperationBegin {
        val key = "${request.runId.trim()}:${request.sessionId.trim()}"
        val mutex = registryLock.withLock { mutexes.getOrPut(key) { Mutex() } }
        mutex.lock()
        try {
            val manifest = storage.loadSessionManifest(request.runId, request.sessionId)
            val operationId = request.operationId.trim().ifBlank { randomUuid() }
            val requestHash = fingerprint(request)
            val activeKey = "$key:$operationId"
            if (!registryLock.withLock { activeKey in active }) {
                store.recoverOrphan(request.runId, request.sessionId, operationId)
            }
            return when (val claim = store.claim(
                runId = request.runId,
                sessionId = request.sessionId,
                operationId = operationId,
                turnId = operationId,
                requestHash = requestHash,
                baseRevision = sessionHistoryRevision(manifest),
                ownerId = randomUuid(),
            )) {
                is OperationClaim.Replay -> {
                    mutex.unlock()
                    ReplyOperationBegin.Replay(claim.receipt, manifest)
                }
                is OperationClaim.Conflict -> throw DialogueOperationException(
                    code = claim.code,
                    message = claim.message,
                    retryable = false,
                )
                is OperationClaim.Wait -> throw DialogueOperationException(
                    code = "OPERATION_IN_PROGRESS",
                    message = "相同 operation_id 的请求正在执行，请稍后重试。",
                    retryable = true,
                )
                is OperationClaim.Owner -> {
                    registryLock.withLock { active += activeKey }
                    ReplyOperationBegin.Active(
                        lease = ActiveReplyOperation(
                            key = activeKey,
                            mutex = mutex,
                            receipt = claim.receipt,
                            replayed = claim.receipt.status == ReplyOperationStatus.COMMITTING,
                        ),
                        sessionManifest = manifest,
                    )
                }
            }
        } catch (error: Throwable) {
            mutex.unlock()
            throw error
        }
    }

    fun get(runId: String, sessionId: String, operationId: String): ReplyOperationReceipt? =
        store.get(runId, sessionId, operationId)

    fun markCommitting(
        lease: ActiveReplyOperation,
        result: ReplyOperationResult,
    ): ReplyOperationReceipt = store.markCommitting(lease.receipt, result).also {
        lease.receipt = it
    }

    fun markCompleted(lease: ActiveReplyOperation): ReplyOperationReceipt = store.markCompleted(lease.receipt).also {
        lease.receipt = it
    }

    /** 失败时先对账；manifest 已提交则补 completed，不能再次生成。 */
    fun recoverFailure(lease: ActiveReplyOperation, error: Throwable): DialogueReplyOutcome? =
        ifSessionExists(lease) { recoverExistingFailure(lease, error) }

    private fun recoverExistingFailure(lease: ActiveReplyOperation, error: Throwable): DialogueReplyOutcome? {
        val result = lease.receipt.result
        if (lease.receipt.status == ReplyOperationStatus.COMMITTING && result != null) {
            val recentCommitted = (storage.loadSessionManifest(lease.receipt.runId, lease.receipt.sessionId)["transcript"] as? JsonArray)
                .orEmpty().any { item ->
                    (item as? JsonObject)?.get("turn_id")?.jsonPrimitive?.contentOrNull == result.turnId
                }
            val committed = recentCommitted || storage.transcriptReader.readByTurnId(
                lease.receipt.runId,
                lease.receipt.sessionId,
                result.turnId,
            ).isNotEmpty()
            if (committed) {
                val receipt = markCompleted(lease)
                return outcome(receipt, storage.loadSessionManifest(receipt.runId, receipt.sessionId), replayed = true)
            }
        }
        val failure = when (error) {
            is DialogueOperationException -> ReplyOperationFailure(error.code, error.message.orEmpty(), error.retryable)
            is SessionRevisionConflictException -> ReplyOperationFailure(
                "SESSION_REVISION_CONFLICT",
                error.message.orEmpty(),
                false,
            )
            is CancellationException -> ReplyOperationFailure("OPERATION_CANCELLED", "操作已取消，可安全重试。", true)
            else -> ReplyOperationFailure("OPERATION_FAILED", error.message.orEmpty().ifBlank { "操作失败。" }, true)
        }
        store.markFailed(lease.receipt, failure).also { lease.receipt = it }
        return null
    }

    /** preflight 尚未交给 flow 时放弃 owner；COMMITTING 的持久结果必须保留供下次 resume。 */
    fun abandonBeforeHandoff(lease: ActiveReplyOperation, error: Throwable) {
        if (lease.receipt.status == ReplyOperationStatus.COMMITTING) {
            ifSessionExists(lease) {
                store.recoverOrphan(lease.receipt.runId, lease.receipt.sessionId, lease.receipt.operationId)
                    ?.let { lease.receipt = it }
            }
        } else {
            recoverFailure(lease, error)
        }
    }

    private fun <T> ifSessionExists(lease: ActiveReplyOperation, block: () -> T): T? {
        val manifest = storage.getDialogueSessionManifestFile(lease.receipt.runId, lease.receipt.sessionId)
        if (!storage.isFile(manifest)) return null
        return try {
            block()
        } catch (error: NoSuchElementException) {
            if (storage.isFile(manifest)) throw error
            null
        }
    }

    fun release(lease: ActiveReplyOperation) {
        registryLock.withLock { active -= lease.key }
        lease.mutex.unlock()
    }

    fun outcome(
        receipt: ReplyOperationReceipt,
        manifest: JsonObject,
        replayed: Boolean,
    ): DialogueReplyOutcome {
        val result = requireNotNull(receipt.result) { "Completed operation has no result" }
        return DialogueReplyOutcome(
            operationId = receipt.operationId,
            turnId = receipt.turnId,
            replayed = replayed,
            baseRevision = receipt.baseRevision,
            committedRevision = result.committedRevision,
            sessionManifest = manifest,
            appendedTranscript = result.appendedTranscript,
            responses = result.responses,
        )
    }

    fun outcome(
        lease: ActiveReplyOperation,
        commit: DialogueCommitResult,
    ): DialogueReplyOutcome = DialogueReplyOutcome(
        operationId = commit.operationId,
        turnId = commit.turnId,
        replayed = lease.replayed || commit.alreadyCommitted,
        baseRevision = commit.baseRevision,
        committedRevision = commit.committedRevision,
        sessionManifest = commit.manifest,
        appendedTranscript = commit.appendedTranscript,
        responses = commit.responses,
    )

    private fun fingerprint(request: ReplyOperationRequest): String {
        val canonical = buildJsonObject {
            put("message", request.message)
            put("message_kind", request.messageKind)
            put("pacing", DialogueService.normalizePacing(request.pacing))
            put("speaker_override", request.speakerOverride)
            put("suppress_transcript_message", request.suppressTranscriptMessage)
            // 只散列不可变请求字段；增强器开关后续变化不能把合法重试误判成新请求。
            put("include_inner_thoughts", request.includeInnerThoughts)
        }.toString()
        return canonical.encodeToByteArray().toByteString().sha256().hex()
    }
}
