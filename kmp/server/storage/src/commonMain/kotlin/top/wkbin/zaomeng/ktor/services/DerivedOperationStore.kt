package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString
import okio.Path
import top.wkbin.zaomeng.platform.nowEpochMillis

class DerivedOperationInactiveException(message: String) : IllegalStateException(message)

@Serializable
enum class DerivedOperationKind { POST_TURN, CORRECTION }

@Serializable
enum class DerivedEffect { SESSION_STATE, WORLD_MEMORY, LONG_TERM_MEMORY, ORIGINAL_ENSURE }

@Serializable
enum class DerivedEffectStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

@Serializable
enum class DerivedOperationStatus { PREPARED, PENDING, RUNNING, RETRY_WAIT, SUCCEEDED, FAILED, SUPERSEDED, CANCELLED }

@Serializable
data class DerivedOperationFailure(
    val effect: DerivedEffect,
    val message: String,
    val retryable: Boolean,
)

@Serializable
data class DerivedOperation(
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("operation_id") val operationId: String,
    val kind: DerivedOperationKind,
    @SerialName("based_on_revision") val basedOnRevision: Long,
    @SerialName("source_hash") val sourceHash: String,
    val result: ReplyOperationResult,
    val status: DerivedOperationStatus,
    val effects: Map<String, DerivedEffectStatus>,
    val attempt: Int = 0,
    @SerialName("owner_id") val ownerId: String = "",
    @SerialName("next_attempt_at") val nextAttemptAt: Long = 0,
    val failure: DerivedOperationFailure? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

/** 正文提交后的持久派生 intent 与分效果回执。 */
class DerivedOperationStore(private val storage: StorageService) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun ensure(
        runId: String,
        sessionId: String,
        kind: DerivedOperationKind,
        result: ReplyOperationResult,
        activate: Boolean = true,
    ): DerivedOperation {
        val manifestPath = storage.getDialogueSessionManifestFile(runId, sessionId)
        if (!storage.exists(manifestPath)) throw NoSuchElementException("Session manifest not found: $sessionId")
        val operationId = operationId(result)
        val path = receiptPath(runId, sessionId, operationId)
        val sourceHash = sourceHash(result)
        val now = nowEpochMillis()
        val hasNewerCorrection = kind == DerivedOperationKind.CORRECTION && listAll(runId, sessionId).any {
            it.result.turnId == result.turnId &&
                it.basedOnRevision > result.committedRevision &&
                it.status !in setOf(
                    DerivedOperationStatus.PREPARED,
                    DerivedOperationStatus.SUPERSEDED,
                    DerivedOperationStatus.CANCELLED,
                )
        }
        fun create(status: DerivedOperationStatus) = DerivedOperation(
            runId = runId,
            sessionId = sessionId,
            operationId = operationId,
            kind = kind,
            basedOnRevision = result.committedRevision,
            sourceHash = sourceHash,
            result = result,
            status = status,
            effects = DerivedEffect.entries.associate { it.name to DerivedEffectStatus.PENDING },
            createdAt = now,
            updatedAt = now,
        )
        val ensured = storage.updateExistingSessionText(runId, sessionId, path) { original ->
            val current = original?.let(::decode)
            if (current != null) {
                if (current.sourceHash != sourceHash) {
                    check(current.status == DerivedOperationStatus.PREPARED ||
                        (current.status == DerivedOperationStatus.CANCELLED && !isCommitted(current))) {
                        "Derived operation source changed: $operationId"
                    }
                    val replacement = create(
                        if (activate) DerivedOperationStatus.PENDING else DerivedOperationStatus.PREPARED,
                    )
                    return@updateExistingSessionText encode(replacement) to replacement
                }
                if (hasNewerCorrection) {
                    val superseded = current.copy(status = DerivedOperationStatus.SUPERSEDED, ownerId = "", updatedAt = now)
                    return@updateExistingSessionText encode(superseded) to superseded
                }
                if (activate && current.status == DerivedOperationStatus.PREPARED) {
                    val activated = current.copy(status = DerivedOperationStatus.PENDING, updatedAt = now)
                    return@updateExistingSessionText encode(activated) to activated
                }
                return@updateExistingSessionText original to current
            }
            val operation = create(
                when {
                    hasNewerCorrection -> DerivedOperationStatus.SUPERSEDED
                    activate -> DerivedOperationStatus.PENDING
                    else -> DerivedOperationStatus.PREPARED
                },
            )
            encode(operation) to operation
        }
        if (kind == DerivedOperationKind.CORRECTION && activate) {
            listAll(runId, sessionId)
                .filter {
                    it.result.turnId == result.turnId &&
                        it.basedOnRevision < result.committedRevision &&
                        it.status in setOf(
                            DerivedOperationStatus.PENDING,
                            DerivedOperationStatus.RUNNING,
                            DerivedOperationStatus.RETRY_WAIT,
                        )
                }
                .forEach { old -> transition(old) { it.copy(status = DerivedOperationStatus.SUPERSEDED, ownerId = "") } }
        }
        return ensured
    }

    fun listAll(runId: String, sessionId: String): List<DerivedOperation> {
        val directory = derivedDirectory(runId, sessionId)
        if (!storage.isDirectory(directory)) return emptyList()
        return storage.listFiles(directory).mapNotNull { operationDir ->
            val path = operationDir / "receipt.json"
            storage.readTextOrNull(path)?.let { content -> runCatching { decode(content) }.getOrNull() }
        }.sortedBy(DerivedOperation::basedOnRevision)
    }

    fun reconcilePrepared(runId: String, sessionId: String) {
        listAll(runId, sessionId)
            .filter { it.status == DerivedOperationStatus.PREPARED }
            .forEach { operation ->
                if (isCommitted(operation)) {
                    ensure(runId, sessionId, operation.kind, operation.result, activate = true)
                } else {
                    transition(operation) { current ->
                        current.copy(status = DerivedOperationStatus.CANCELLED, ownerId = "", updatedAt = nowEpochMillis())
                    }
                }
            }
    }

    fun listActive(runId: String, sessionId: String): List<DerivedOperation> = listAll(runId, sessionId)
        .filter {
            it.status in setOf(
                DerivedOperationStatus.PENDING,
                DerivedOperationStatus.RUNNING,
                DerivedOperationStatus.RETRY_WAIT,
            )
        }

    fun claimNext(runId: String, sessionId: String, ownerId: String): DerivedOperation? {
        val now = nowEpochMillis()
        listActive(runId, sessionId).forEach { operation ->
            if (operation.status == DerivedOperationStatus.RUNNING || operation.nextAttemptAt > now) return@forEach
            if (!isCommitted(operation)) {
                transition(operation) { current ->
                    current.copy(status = DerivedOperationStatus.CANCELLED, ownerId = "", updatedAt = now)
                }
                return@forEach
            }
            val claimed = transition(operation) { current ->
                check(current.attempt == operation.attempt && current.ownerId == operation.ownerId) {
                    "Derived operation changed during claim"
                }
                check(current.status == DerivedOperationStatus.PENDING || current.status == DerivedOperationStatus.RETRY_WAIT) {
                    "Derived operation is not claimable: ${current.status}"
                }
                current.copy(
                    status = DerivedOperationStatus.RUNNING,
                    attempt = current.attempt + 1,
                    ownerId = ownerId,
                    failure = null,
                    updatedAt = now,
                )
            }
            return claimed
        }
        return null
    }

    private fun isCommitted(operation: DerivedOperation): Boolean {
        val manifest = storage.loadSessionManifest(operation.runId, operation.sessionId)
        if (sessionHistoryRevision(manifest) < operation.basedOnRevision) return false
        val committed = storage.transcriptReader.readByTurnId(
            operation.runId,
            operation.sessionId,
            operation.result.turnId,
        )
        if (committed.size != operation.result.appendedTranscript.size) return false
        val expectedBySeq = operation.result.appendedTranscript.mapNotNull { item ->
            item["seq"]?.jsonPrimitive?.intOrNull?.let { it to item.withoutSeq() }
        }.toMap()
        return committed.all { entry ->
            expectedBySeq[entry.seq] == entry.value.withoutSeq()
        }
    }

    private fun JsonObject.withoutSeq(): JsonObject = JsonObject(filterKeys { it != "seq" })

    fun completeEffect(operation: DerivedOperation, effect: DerivedEffect): DerivedOperation =
        transition(operation) { current ->
            checkActive(current)
            checkOwner(operation, current)
            val updatedEffects = current.effects + (effect.name to DerivedEffectStatus.DONE)
            val completed = DerivedEffect.entries.all { updatedEffects[it.name] in setOf(DerivedEffectStatus.DONE, DerivedEffectStatus.SKIPPED) }
            current.copy(
                status = if (completed) DerivedOperationStatus.SUCCEEDED else current.status,
                effects = updatedEffects,
                ownerId = if (completed) "" else current.ownerId,
                failure = null,
                updatedAt = nowEpochMillis(),
            )
        }

    fun skipEffect(operation: DerivedOperation, effect: DerivedEffect): DerivedOperation =
        transition(operation) { current ->
            checkActive(current)
            checkOwner(operation, current)
            val updatedEffects = current.effects + (effect.name to DerivedEffectStatus.SKIPPED)
            val completed = DerivedEffect.entries.all { updatedEffects[it.name] in setOf(DerivedEffectStatus.DONE, DerivedEffectStatus.SKIPPED) }
            current.copy(
                status = if (completed) DerivedOperationStatus.SUCCEEDED else current.status,
                effects = updatedEffects,
                ownerId = if (completed) "" else current.ownerId,
                failure = null,
                updatedAt = nowEpochMillis(),
            )
        }

    fun failEffect(
        operation: DerivedOperation,
        effect: DerivedEffect,
        error: Throwable,
        retryable: Boolean = true,
    ): DerivedOperation = transition(operation) { current ->
        checkActive(current)
        checkOwner(operation, current)
        val nextAttemptAt = if (retryable) nowEpochMillis() + retryDelayMillis(current.attempt) else 0
        current.copy(
            status = if (retryable) DerivedOperationStatus.RETRY_WAIT else DerivedOperationStatus.FAILED,
            effects = current.effects + (effect.name to DerivedEffectStatus.FAILED),
            ownerId = "",
            nextAttemptAt = nextAttemptAt,
            failure = DerivedOperationFailure(effect, error.message.orEmpty().take(500), retryable),
            updatedAt = nowEpochMillis(),
        )
    }

    fun recoverOrphan(operation: DerivedOperation): DerivedOperation = transition(operation) { current ->
        if (current.status != DerivedOperationStatus.RUNNING) {
            current
        } else {
            current.copy(
                status = DerivedOperationStatus.PENDING,
                effects = current.effects.mapValues { (_, status) ->
                    if (status == DerivedEffectStatus.RUNNING) DerivedEffectStatus.PENDING else status
                },
                ownerId = "",
                nextAttemptAt = 0,
                updatedAt = nowEpochMillis(),
            )
        }
    }

    fun cancelSession(runId: String, sessionId: String) {
        listActive(runId, sessionId).forEach { operation ->
            transition(operation) { current ->
                current.copy(status = DerivedOperationStatus.CANCELLED, ownerId = "", updatedAt = nowEpochMillis())
            }
        }
    }

    private fun transition(
        operation: DerivedOperation,
        transform: (DerivedOperation) -> DerivedOperation,
    ): DerivedOperation {
        val path = receiptPath(operation.runId, operation.sessionId, operation.operationId)
        return storage.updateExistingSessionText(operation.runId, operation.sessionId, path) { original ->
            val current = original?.let(::decode)
                ?: throw NoSuchElementException("Derived operation not found: ${operation.operationId}")
            val updated = transform(current).copy(updatedAt = nowEpochMillis())
            encode(updated) to updated
        }
    }

    private fun checkActive(operation: DerivedOperation) {
        if (operation.status !in setOf(
            DerivedOperationStatus.RUNNING,
            DerivedOperationStatus.RETRY_WAIT,
            DerivedOperationStatus.FAILED,
        )) {
            throw DerivedOperationInactiveException("Derived operation is no longer active: ${operation.status}")
        }
    }

    private fun checkOwner(expected: DerivedOperation, actual: DerivedOperation) {
        if (expected.operationId != actual.operationId || expected.attempt != actual.attempt || expected.ownerId != actual.ownerId) {
            throw DerivedOperationInactiveException("Derived operation owner or attempt changed")
        }
    }

    private fun receiptPath(runId: String, sessionId: String, operationId: String): Path {
        val id = PathSafety.validateStorageId(operationId, "derived_operation_id")
        return derivedDirectory(runId, sessionId) / "$id/receipt.json"
    }

    private fun derivedDirectory(runId: String, sessionId: String): Path =
        requireNotNull(storage.getDialogueSessionManifestFile(runId, sessionId).parent) / "derived-operations"

    private fun operationId(result: ReplyOperationResult): String =
        "${result.turnId}--${result.committedRevision}"

    private fun sourceHash(result: ReplyOperationResult): String =
        json.encodeToString(ReplyOperationResult.serializer(), result).encodeToByteArray().toByteString().sha256().hex()

    private fun encode(operation: DerivedOperation): String = json.encodeToString(DerivedOperation.serializer(), operation)

    private fun decode(content: String): DerivedOperation = json.decodeFromString(DerivedOperation.serializer(), content)

    private companion object {
        fun retryDelayMillis(attempt: Int): Long = minOf(1_000L * (1 shl (attempt - 1).coerceIn(0, 4)), 16_000L)
    }
}
