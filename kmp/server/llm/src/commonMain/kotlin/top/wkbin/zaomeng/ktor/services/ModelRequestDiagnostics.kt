package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.Serializable
import okio.ByteString.Companion.toByteString
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.nowEpochMillis

@Serializable
data class LlmRequestContext(
    val runId: String = "",
    val sessionId: String = "",
    val turnId: String = "",
    val operationId: String = "",
    val task: String = "dialogue.reply",
    val attemptKind: String = "initial",
)

@Serializable
data class RequestSlotDigest(
    val key: String,
    val sha256: String,
    val estimatedTokens: Int,
)

@Serializable
data class CacheDiffResult(
    val status: String,
    val firstBreakIndex: Int = -1,
    val hitTokens: Int = 0,
    val breakTokens: Int = 0,
    val cascadeTokens: Int = 0,
    val appendedTokens: Int = 0,
    val invalidatedTokens: Int = 0,
    val estimatedPrefixHitRate: Double? = null,
    val truncated: Boolean = false,
)

@Serializable
data class UsageMetrics(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val totalInputTokens: Int = 0,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val cacheHitRate: Double? = null,
    val observed: Boolean = false,
)

@Serializable
data class ModelRequestRecord(
    val requestId: String,
    val sequence: Long,
    val context: LlmRequestContext,
    val model: String,
    val endpoint: String,
    val stream: Boolean,
    val startedAt: Long,
    val completedAt: Long,
    val ttftMs: Long? = null,
    val status: String,
    val slots: List<RequestSlotDigest>,
    val diff: CacheDiffResult,
    val usage: UsageMetrics? = null,
)

object RequestCacheDiff {
    fun slots(messages: List<LlmClient.ChatMessage>): List<RequestSlotDigest> = messages.mapIndexed { index, message ->
        val canonical = buildString {
            append(message.role)
            append('\u0000')
            append(message.content ?: "")
        }
        RequestSlotDigest(
            key = "msg:$index",
            sha256 = canonical.encodeToByteArray().toByteString().sha256().hex(),
            estimatedTokens = estimateTokens(canonical),
        )
    }

    fun compare(
        baseline: List<RequestSlotDigest>?,
        current: List<RequestSlotDigest>,
        namespaceChanged: Boolean = false,
    ): CacheDiffResult {
        if (baseline == null) return CacheDiffResult(status = "no-previous")
        if (namespaceChanged) {
            val invalidated = current.sumOf(RequestSlotDigest::estimatedTokens)
            return CacheDiffResult(status = "namespace-changed", firstBreakIndex = -2, invalidatedTokens = invalidated)
        }
        val currentTotal = current.sumOf(RequestSlotDigest::estimatedTokens)
        var breakIndex = -1
        val count = minOf(baseline.size, current.size)
        for (index in 0 until count) {
            if (baseline[index].sha256 != current[index].sha256) {
                breakIndex = index
                break
            }
        }
        val hitTokens = if (breakIndex < 0) {
            current.take(count).sumOf(RequestSlotDigest::estimatedTokens)
        } else {
            current.take(breakIndex).sumOf(RequestSlotDigest::estimatedTokens)
        }
        val breakTokens = if (breakIndex >= 0) current[breakIndex].estimatedTokens else 0
        val cascadeTokens = if (breakIndex >= 0) {
            current.drop(breakIndex + 1).sumOf(RequestSlotDigest::estimatedTokens)
        } else {
            0
        }
        val appendedTokens = if (breakIndex < 0 && current.size > baseline.size) {
            current.drop(baseline.size).sumOf(RequestSlotDigest::estimatedTokens)
        } else {
            0
        }
        val hitRate = if (currentTotal > 0) hitTokens.toDouble() / currentTotal else null
        return CacheDiffResult(
            status = if (current.size < baseline.size) "truncated" else "compared",
            firstBreakIndex = breakIndex,
            hitTokens = hitTokens,
            breakTokens = breakTokens,
            cascadeTokens = cascadeTokens,
            appendedTokens = appendedTokens,
            invalidatedTokens = breakTokens + cascadeTokens,
            estimatedPrefixHitRate = hitRate,
            truncated = current.size < baseline.size,
        )
    }

    private fun estimateTokens(value: String): Int {
        val weighted = value.sumOf { char -> if (char.code > 0xFF) 2 else 1 }
        return maxOf(1, (weighted + 3) / 4)
    }
}

class ModelRequestDiagnostics {
    private data class Pending(
        val id: String,
        val sequence: Long,
        val context: LlmRequestContext,
        val model: String,
        val endpoint: String,
        val stream: Boolean,
        val startedAt: Long,
        val slots: List<RequestSlotDigest>,
        val namespaceHash: String,
    )

    private val lock = SimpleLock()
    private var nextSequence = 0L
    private val pending = HashMap<String, Pending>()
    private val latestBySession = HashMap<String, ModelRequestRecord>()
    private data class CacheBaseline(
        val sequence: Long,
        val model: String,
        val namespaceHash: String,
        val slots: List<RequestSlotDigest>,
    )

    private val baselineBySession = HashMap<String, CacheBaseline>()
    private val recent = ArrayDeque<ModelRequestRecord>()

    fun start(
        context: LlmRequestContext,
        request: LlmClient.ChatCompletionRequest,
        endpoint: String,
    ): String? {
        if (context.sessionId.isBlank()) return null
        val slots = RequestCacheDiff.slots(request.messages)
        val namespaceHash = endpoint.trim().encodeToByteArray().toByteString().sha256().hex()
        return lock.withLock {
            val sequence = ++nextSequence
            val id = "req-${nowEpochMillis()}-$sequence"
            pending[id] = Pending(
                id = id,
                sequence = sequence,
                context = context,
                model = request.model,
                endpoint = safeEndpoint(endpoint),
                stream = request.stream,
                startedAt = nowEpochMillis(),
                slots = slots,
                namespaceHash = namespaceHash,
            )
            id
        }
    }

    fun finish(
        requestId: String?,
        usage: LlmClient.Usage? = null,
        ttftMs: Long? = null,
        status: String = "completed",
    ) {
        if (requestId == null) return
        val request = lock.withLock { pending.remove(requestId) } ?: return
        lock.withLock {
            val key = sessionKey(request.context)
            val current = baselineBySession[key]
            val diff = if (current != null && request.sequence < current.sequence) {
                CacheDiffResult(status = "out-of-order")
            } else {
                RequestCacheDiff.compare(
                    baseline = current?.slots,
                    current = request.slots,
                    namespaceChanged = current != null && (current.model != request.model || current.namespaceHash != request.namespaceHash),
                )
            }
            val record = ModelRequestRecord(
                requestId = request.id,
                sequence = request.sequence,
                context = request.context,
                model = request.model,
                endpoint = request.endpoint,
                stream = request.stream,
                startedAt = request.startedAt,
                completedAt = nowEpochMillis(),
                ttftMs = ttftMs,
                status = status,
                slots = request.slots,
                diff = diff,
                usage = usageMetrics(usage),
            )
            val latest = latestBySession[key]
            if (latest == null || request.sequence > latest.sequence) latestBySession[key] = record
            if (status == "completed" && (current == null || request.sequence > current.sequence)) {
                baselineBySession[key] = CacheBaseline(
                    sequence = request.sequence,
                    model = request.model,
                    namespaceHash = request.namespaceHash,
                    slots = request.slots,
                )
            }
            recent.addLast(record)
            while (recent.size > MAX_RECENT_RECORDS) recent.removeFirst()
        }
    }

    fun latest(runId: String, sessionId: String): ModelRequestRecord? =
        lock.withLock { latestBySession["$runId:$sessionId"] }

    fun exportRecent(): List<ModelRequestRecord> = lock.withLock { recent.toList() }

    private fun sessionKey(context: LlmRequestContext): String = "${context.runId}:${context.sessionId}"

    private fun usageMetrics(usage: LlmClient.Usage?): UsageMetrics? {
        usage ?: return null
        val read = usage.prompt_cache_hit_tokens
            ?: usage.cache_read_input_tokens
            ?: usage.prompt_tokens_details?.cached_tokens
        val write = usage.cache_creation_input_tokens
        val observed = read != null || write != null
        val totalInput = when {
            usage.input_tokens != null || usage.cache_read_input_tokens != null || usage.cache_creation_input_tokens != null ->
                (usage.input_tokens ?: 0) + (usage.cache_read_input_tokens ?: 0) + (usage.cache_creation_input_tokens ?: 0)
            usage.prompt_cache_hit_tokens != null || usage.prompt_cache_miss_tokens != null ->
                (usage.prompt_cache_hit_tokens ?: 0) + (usage.prompt_cache_miss_tokens ?: 0)
            else -> usage.prompt_tokens
        }
        return UsageMetrics(
            promptTokens = usage.prompt_tokens,
            completionTokens = usage.completion_tokens,
            totalTokens = usage.total_tokens,
            totalInputTokens = totalInput,
            cacheReadTokens = read,
            cacheWriteTokens = write,
            cacheHitRate = if (observed && totalInput > 0) (read ?: 0).toDouble() / totalInput else null,
            observed = observed,
        )
    }

    private fun safeEndpoint(value: String): String {
        val withoutUserInfo = value.substringAfter("://", value).substringAfter('@')
        return withoutUserInfo.substringBefore('?').substringBefore('#')
    }

    private companion object {
        const val MAX_RECENT_RECORDS = 40
    }
}
