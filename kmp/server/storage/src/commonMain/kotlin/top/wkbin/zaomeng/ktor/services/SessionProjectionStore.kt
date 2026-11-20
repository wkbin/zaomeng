package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.Path

const val SESSION_PROJECTION_VERSION: Int = 1

@Serializable
data class SummaryVolume(
    @SerialName("start_seq") val startSeq: Int,
    @SerialName("end_seq") val endSeq: Int,
    @SerialName("source_revision") val sourceRevision: Long,
    @SerialName("source_hash") val sourceHash: String,
    val summary: String,
    @SerialName("key_points") val keyPoints: List<String> = emptyList(),
    @SerialName("turn_count") val turnCount: Int,
)

@Serializable
data class SessionProjection(
    @SerialName("projection_version") val projectionVersion: Int,
    @SerialName("run_id") val runId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("source_revision") val sourceRevision: Long,
    @SerialName("source_start_seq") val sourceStartSeq: Int,
    @SerialName("source_end_seq") val sourceEndSeq: Int,
    val volumes: List<SummaryVolume>,
    @SerialName("session_summary") val sessionSummary: JsonObject,
    @SerialName("generated_at") val generatedAt: String,
)

/** 可删除、可重建的会话摘要 sidecar；发布与历史 manifest 使用同一把锁。 */
class SessionProjectionStore(private val storage: StorageService) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun read(runId: String, sessionId: String): SessionProjection? {
        val content = storage.readTextOrNull(path(runId, sessionId)) ?: return null
        val projection = json.decodeFromString(SessionProjection.serializer(), content)
        check(projection.runId == runId && projection.sessionId == sessionId) {
            "Session projection identity mismatch"
        }
        return projection
    }

    fun publish(expectedRevision: Long, projection: SessionProjection): SessionProjection =
        storage.withSessionManifestLock(projection.runId, projection.sessionId) {
            val manifest = storage.loadSessionManifest(projection.runId, projection.sessionId)
            val currentRevision = sessionHistoryRevision(manifest)
            if (currentRevision != expectedRevision || projection.sourceRevision != expectedRevision) {
                throw SessionRevisionConflictException(expectedRevision, currentRevision)
            }
            val currentCount = sessionTranscriptCount(manifest)
            check(projection.sourceStartSeq == 0 && projection.sourceEndSeq == currentCount) {
                "Session projection source range is stale: expected 0 until $currentCount, got ${projection.sourceStartSeq} until ${projection.sourceEndSeq}"
            }
            check(projection.projectionVersion == SESSION_PROJECTION_VERSION) {
                "Session projection version mismatch"
            }
            storage.writeTextAtomically(path(projection.runId, projection.sessionId), encode(projection))
            projection
        }

    fun clear(runId: String, sessionId: String) {
        val target = path(runId, sessionId)
        if (storage.exists(target)) storage.deleteFile(target)
    }

    private fun path(runId: String, sessionId: String): Path {
        PathSafety.validateStorageId(sessionId, "session_id")
        return storage.getDialogueSessionsDirectory(runId) / "$sessionId/session_projection.json"
    }

    private fun encode(projection: SessionProjection): String =
        json.encodeToString(SessionProjection.serializer(), projection)
}
