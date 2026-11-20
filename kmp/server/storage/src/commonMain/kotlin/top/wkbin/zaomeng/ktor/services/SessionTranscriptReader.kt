package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.Path

class SessionRevisionConflictException(
    val expectedRevision: Long,
    val actualRevision: Long,
) : IllegalStateException("Session revision conflict: expected=$expectedRevision, actual=$actualRevision")

class TranscriptArchiveException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

enum class TranscriptOrder { ASC, DESC }

data class SessionHistorySnapshot(
    val manifest: JsonObject,
    val revision: Long,
    val transcriptStart: Int,
    val transcriptCount: Int,
)

data class SessionHistoryMutation<T>(
    val manifest: JsonObject,
    val result: T,
)

data class SessionHistoryCommit<T>(
    val manifest: JsonObject,
    val revision: Long,
    val transcriptCount: Int,
    val result: T,
)

data class TranscriptEntry(
    val seq: Int,
    val value: JsonObject,
)

data class SessionTranscriptPage(
    val revision: Long,
    val total: Int,
    val items: List<TranscriptEntry>,
    val hasMore: Boolean,
    val nextOffset: Int?,
)

data class SessionTranscriptSnapshot(
    val manifest: JsonObject,
    val revision: Long,
    val items: List<TranscriptEntry>,
)

fun sessionHistoryRevision(manifest: JsonObject): Long =
    manifest["revision"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0) ?: 0

fun sessionTranscriptStart(manifest: JsonObject): Int =
    manifest["transcript_start"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0

fun sessionTranscriptCount(manifest: JsonObject): Int =
    sessionTranscriptStart(manifest) + (manifest["transcript"] as? JsonArray).orEmpty().size

/**
 * 固定剧情 revision 的轻量会话/消息读取器。archive 只在目标范围需要时解码。
 */
class SessionTranscriptReader internal constructor(
    private val storage: StorageService,
) {
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private data class ArchiveChunk(val start: Int, val end: Int, val path: Path)

    fun readSnapshot(
        runId: String,
        sessionId: String,
        expectedRevision: Long? = null,
    ): SessionHistorySnapshot = storage.withSessionManifestLock(runId, sessionId) {
        readSnapshotLocked(runId, sessionId, expectedRevision)
    }

    fun readPage(
        runId: String,
        sessionId: String,
        offset: Int,
        limit: Int,
        order: TranscriptOrder,
        expectedRevision: Long? = null,
    ): SessionTranscriptPage = storage.withSessionManifestLock(runId, sessionId) {
        val snapshot = readSnapshotLocked(runId, sessionId, expectedRevision)
        val total = snapshot.transcriptCount
        val normalizedOffset = offset.coerceAtLeast(0)
        val normalizedLimit = limit.coerceIn(1, 500)
        val range = when (order) {
            TranscriptOrder.ASC -> {
                val start = normalizedOffset.coerceAtMost(total)
                start to minOf(start + normalizedLimit, total)
            }
            TranscriptOrder.DESC -> {
                val end = (total - normalizedOffset).coerceAtLeast(0)
                maxOf(0, end - normalizedLimit) to end
            }
        }
        val ascending = readRangeLocked(runId, sessionId, snapshot, range.first, range.second)
        val items = if (order == TranscriptOrder.DESC) ascending.asReversed() else ascending
        val hasMore = when (order) {
            TranscriptOrder.ASC -> range.second < total
            TranscriptOrder.DESC -> range.first > 0
        }
        val nextOffset = if (!hasMore) {
            null
        } else {
            when (order) {
                TranscriptOrder.ASC -> range.second
                TranscriptOrder.DESC -> normalizedOffset + items.size
            }
        }
        assertSnapshotStableLocked(runId, sessionId, snapshot)
        SessionTranscriptPage(
            revision = snapshot.revision,
            total = total,
            items = items,
            hasMore = hasMore,
            nextOffset = nextOffset,
        )
    }

    fun readAll(
        runId: String,
        sessionId: String,
        expectedRevision: Long? = null,
    ): SessionTranscriptSnapshot = storage.withSessionManifestLock(runId, sessionId) {
        val snapshot = readSnapshotLocked(runId, sessionId, expectedRevision)
        val items = readRangeLocked(runId, sessionId, snapshot, 0, snapshot.transcriptCount)
        assertSnapshotStableLocked(runId, sessionId, snapshot)
        SessionTranscriptSnapshot(snapshot.manifest, snapshot.revision, items)
    }

    fun readByTurnId(
        runId: String,
        sessionId: String,
        turnId: String,
        expectedRevision: Long? = null,
    ): List<TranscriptEntry> = storage.withSessionManifestLock(runId, sessionId) {
        val snapshot = readSnapshotLocked(runId, sessionId, expectedRevision)
        if (turnId.isBlank()) return@withSessionManifestLock emptyList()
        val result = mutableListOf<TranscriptEntry>()
        val chunks = archiveChunksLocked(runId, sessionId, snapshot.transcriptStart)
        if (snapshot.transcriptStart > 0 && chunks.firstOrNull()?.start != 0) {
            throw TranscriptArchiveException("Transcript archive starts after sequence zero: $sessionId")
        }
        chunks.forEach { chunk ->
            val items = readChunkLocked(chunk)
            if (items.size != chunk.end - chunk.start) {
                throw TranscriptArchiveException("Transcript archive is incomplete: ${chunk.path}")
            }
            items.forEachIndexed { index, item ->
                if (item.stringField("turn_id") == turnId) {
                    result += TranscriptEntry(chunk.start + index, item)
                }
            }
        }
        (snapshot.manifest["transcript"] as? JsonArray).orEmpty().forEachIndexed { index, raw ->
            val item = raw as? JsonObject
                ?: throw TranscriptArchiveException("Invalid recent transcript entry")
            if (item.stringField("turn_id") == turnId) {
                result += TranscriptEntry(snapshot.transcriptStart + index, item)
            }
        }
        assertSnapshotStableLocked(runId, sessionId, snapshot)
        result.sortedBy(TranscriptEntry::seq)
    }

    private fun readSnapshotLocked(
        runId: String,
        sessionId: String,
        expectedRevision: Long? = null,
    ): SessionHistorySnapshot {
        val path = storage.getDialogueSessionManifestFile(runId, sessionId)
        val content = storage.readTextOrNull(path)
            ?: throw NoSuchElementException("Session manifest not found: $sessionId")
        val manifest = runCatching { json.parseToJsonElement(content) as? JsonObject }.getOrNull()
            ?: throw TranscriptArchiveException("Failed to load session manifest: $sessionId")
        val revision = sessionHistoryRevision(manifest)
        if (expectedRevision != null && expectedRevision != revision) {
            throw SessionRevisionConflictException(expectedRevision, revision)
        }
        return SessionHistorySnapshot(
            manifest = manifest,
            revision = revision,
            transcriptStart = sessionTranscriptStart(manifest),
            transcriptCount = sessionTranscriptCount(manifest),
        )
    }

    private fun readRangeLocked(
        runId: String,
        sessionId: String,
        snapshot: SessionHistorySnapshot,
        start: Int,
        end: Int,
    ): List<TranscriptEntry> {
        if (start >= end) return emptyList()
        val result = mutableListOf<TranscriptEntry>()
        val chunks = if (start < snapshot.transcriptStart) {
            archiveChunksLocked(runId, sessionId, snapshot.transcriptStart)
        } else {
            emptyList()
        }
        chunks.filter { it.start < end && it.end > start }
            .forEach { chunk ->
                val items = readChunkLocked(chunk)
                if (chunk.start + items.size > chunk.end) {
                    throw TranscriptArchiveException("Transcript archive overlaps the next chunk: ${chunk.path}")
                }
                items.forEachIndexed { index, item ->
                    val seq = chunk.start + index
                    if (seq in start until end) result += TranscriptEntry(seq, item)
                }
            }
        (snapshot.manifest["transcript"] as? JsonArray).orEmpty().forEachIndexed { index, raw ->
            val seq = snapshot.transcriptStart + index
            if (seq in start until end) {
                result += TranscriptEntry(
                    seq,
                    raw as? JsonObject ?: throw TranscriptArchiveException("Invalid recent transcript entry"),
                )
            }
        }
        val ordered = result.sortedBy(TranscriptEntry::seq)
        if (ordered.size != end - start || ordered.anyIndexed { index, entry -> entry.seq != start + index }) {
            throw TranscriptArchiveException("Transcript archive is incomplete for range $start until $end")
        }
        return ordered
    }

    private fun archiveChunksLocked(
        runId: String,
        sessionId: String,
        transcriptStart: Int,
    ): List<ArchiveChunk> {
        if (transcriptStart <= 0) return emptyList()
        val directory = storage.getDialogueSessionsDirectory(runId) / "$sessionId/transcript-archive"
        if (!storage.isDirectory(directory)) {
            throw TranscriptArchiveException("Transcript archive not found: $sessionId")
        }
        val files = storage.listFiles(directory).mapNotNull { path ->
            val start = path.name.removeSuffix(".json").toIntOrNull()
            if (path.name.endsWith(".json") && start != null && start in 0 until transcriptStart) {
                start to path
            } else {
                null
            }
        }.sortedBy { it.first }
        return files.mapIndexed { index, (start, path) ->
            ArchiveChunk(
                start = start,
                end = files.getOrNull(index + 1)?.first ?: transcriptStart,
                path = path,
            )
        }
    }

    private fun readChunkLocked(chunk: ArchiveChunk): List<JsonObject> {
        val content = storage.readTextOrNull(chunk.path)
            ?: throw TranscriptArchiveException("Transcript archive chunk not found: ${chunk.path}")
        val array = runCatching {
            json.parseToJsonElement(content) as? JsonArray
        }.getOrNull() ?: throw TranscriptArchiveException("Invalid transcript archive chunk: ${chunk.path}")
        return array.map { raw ->
            raw as? JsonObject ?: throw TranscriptArchiveException("Invalid transcript archive entry: ${chunk.path}")
        }
    }

    private fun assertSnapshotStableLocked(
        runId: String,
        sessionId: String,
        expected: SessionHistorySnapshot,
    ) {
        val current = readSnapshotLocked(runId, sessionId)
        if (current.revision != expected.revision ||
            current.transcriptStart != expected.transcriptStart ||
            current.transcriptCount != expected.transcriptCount
        ) {
            throw SessionRevisionConflictException(expected.revision, current.revision)
        }
    }

    private fun JsonObject.stringField(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
        for (index in indices) if (predicate(index, this[index])) return true
        return false
    }
}
