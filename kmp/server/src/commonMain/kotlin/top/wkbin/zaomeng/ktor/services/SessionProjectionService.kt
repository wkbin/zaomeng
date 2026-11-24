package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.SessionProjectionDto
import top.wkbin.zaomeng.data.api.StoryEventDto
import top.wkbin.zaomeng.data.api.StoryResponseDto
import top.wkbin.zaomeng.data.api.StoryQuoteDto
import top.wkbin.zaomeng.data.api.StoryRecapDto
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.platformIoDispatcher

/** 原文为权威、revision 固定的可重建摘要 Projection。 */
class SessionProjectionService(
    private val storage: StorageService,
    private val llm: LlmClient? = null,
    private val prompts: PromptLoader? = null,
) {
    private val store = SessionProjectionStore(storage)
    private val scope = CoroutineScope(SupervisorJob() + platformIoDispatcher)
    private val jobs = HashMap<String, Job>()
    private val workerIds = HashMap<String, String>()
    private val queued = HashMap<String, Long>()
    private val failures = HashMap<String, String>()
    private val cancelled = HashSet<String>()
    private val lock = SimpleLock()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private data class TurnSlice(
        val turnId: String,
        val entries: List<TranscriptEntry>,
    )

    /** 供生成请求使用的非等待 fresh 摘要；stale/missing 时只排队，不阻塞正文。 */
    fun freshPromptSummaries(runId: String, sessionId: String): Pair<Map<String, Any?>, Map<String, Any?>>? {
        if (lock.withLock { key(runId, sessionId) in cancelled }) return null
        val snapshot = storage.transcriptReader.readSnapshot(runId, sessionId)
        val projection = store.read(runId, sessionId)
        if (!isFresh(projection, snapshot)) {
            rebuild(runId, sessionId)
            return null
        }
        val valid = requireNotNull(projection)
        return valid.sessionSummary.withCurrentState(snapshot.manifest).toPlainMap() to
            archivedSummary(valid, snapshot.transcriptCount).toPlainMap()
    }

    fun get(runId: String, sessionId: String): SessionProjectionDto {
        if (lock.withLock { key(runId, sessionId) in cancelled }) {
            return SessionProjectionDto(status = "failed", error = "会话已删除")
        }
        val snapshot = storage.transcriptReader.readSnapshot(runId, sessionId)
        val projection = store.read(runId, sessionId)
        val key = key(runId, sessionId)
        val active = lock.withLock { jobs[key]?.isActive == true }
        val fresh = isFresh(projection, snapshot)
        val failure = lock.withLock { failures[key] }.orEmpty()
        val status = when {
            active -> "building"
            failure.isNotBlank() -> "failed"
            fresh -> "fresh"
            projection != null -> "stale"
            else -> "missing"
        }
        val recap = projection?.let { storyRecap(it, recentSnapshot(snapshot)) }
        val sessionJson = buildJsonObject {
            leanSession(snapshot.manifest).forEach { (key, value) -> put(key, value) }
            recap?.let { put("story_recap", json.encodeToJsonElement(StoryRecapDto.serializer(), it)) }
            projection?.let { put("session_memory_summary", it.sessionSummary.withCurrentState(snapshot.manifest)) }
        }
        return SessionProjectionDto(
            status = status,
            sourceRevision = projection?.sourceRevision ?: snapshot.revision,
            currentRevision = snapshot.revision,
            session = json.decodeFromJsonElement(DialogueSessionDto.serializer(), sessionJson),
            error = failure,
        )
    }

    suspend fun cancel(runId: String, sessionId: String) {
        val key = key(runId, sessionId)
        val job = lock.withLock {
            cancelled += key
            queued.remove(key)
            failures.remove(key)
            workerIds.remove(key)
            jobs[key]
        }
        job?.cancelAndJoin()
        lock.withLock {
            if (jobs[key] === job) jobs.remove(key)
        }
    }

    fun reset(runId: String, sessionId: String) {
        val key = key(runId, sessionId)
        lock.withLock {
            cancelled.remove(key)
            failures.remove(key)
            queued.remove(key)
            workerIds.remove(key)
        }
        store.clear(runId, sessionId)
    }

    fun rebuild(runId: String, sessionId: String): SessionProjectionDto {
        if (lock.withLock { key(runId, sessionId) in cancelled }) return get(runId, sessionId)
        val revision = storage.transcriptReader.readSnapshot(runId, sessionId).revision
        val key = key(runId, sessionId)
        lock.withLock {
            if (key in cancelled) return@withLock
            queued[key] = maxOf(queued[key] ?: revision, revision)
            if (jobs[key]?.isActive != true) {
                startDrainLocked(key)
            }
        }
        return get(runId, sessionId)
    }

    private fun startDrainLocked(key: String) {
        val workerId = top.wkbin.zaomeng.platform.randomUuid()
        workerIds[key] = workerId
        jobs[key] = scope.launch { drain(key, workerId) }
    }

    private suspend fun drain(key: String, workerId: String) {
        try {
            while (true) {
                val target = lock.withLock { queued[key] } ?: return
                try {
                    buildAndPublish(key, target)
                    lock.withLock {
                        if (queued[key] == target) failures.remove(key)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lock.withLock {
                        if (queued[key] == target) failures[key] = error.message.orEmpty().take(500)
                    }
                }
                val completed = lock.withLock {
                    if (queued[key] == target) {
                        queued.remove(key)
                        true
                    } else {
                        false
                    }
                }
                if (completed) return
            }
        } finally {
            lock.withLock {
                if (workerIds[key] == workerId) {
                    jobs.remove(key)
                    workerIds.remove(key)
                    if (queued.containsKey(key) && key !in cancelled) {
                        startDrainLocked(key)
                    }
                }
            }
        }
    }

    private suspend fun buildAndPublish(key: String, expectedRevision: Long) {
        val snapshot = storage.transcriptReader.readAll(key.substringBefore(':'), key.substringAfter(':'), expectedRevision)
        val previous = runCatching { store.read(key.substringBefore(':'), key.substringAfter(':')) }.getOrNull()
            ?.takeIf { it.projectionVersion == SESSION_PROJECTION_VERSION }
        val previousByRange = previous?.volumes.orEmpty().associateBy { it.startSeq to it.endSeq }
        val turns = groupTurns(snapshot.items)
        val volumes = turns.chunked(TURNS_PER_VOLUME).map { volumeTurns ->
            val entries = volumeTurns.flatMap(TurnSlice::entries)
            val startSeq = entries.first().seq
            val endSeq = entries.last().seq + 1
            val hash = sourceHash(entries)
            val reusable = previousByRange[startSeq to endSeq]?.takeIf { it.sourceHash == hash }
            val generated = reusable?.let { it.summary to it.keyPoints } ?: generateVolume(volumeTurns, expectedRevision)
            SummaryVolume(
                startSeq = startSeq,
                endSeq = endSeq,
                sourceRevision = expectedRevision,
                sourceHash = hash,
                summary = generated.first,
                keyPoints = generated.second,
                turnCount = volumeTurns.size,
            )
        }
        val projection = SessionProjection(
            projectionVersion = SESSION_PROJECTION_VERSION,
            runId = snapshot.manifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            sessionId = snapshot.manifest["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            sourceRevision = expectedRevision,
            sourceStartSeq = 0,
            sourceEndSeq = snapshot.items.size,
            volumes = volumes,
            sessionSummary = buildSessionSummary(volumes, snapshot.manifest),
            generatedAt = nowIsoString(),
        )
        store.publish(expectedRevision, projection)
    }

    private suspend fun generateVolume(turns: List<TurnSlice>, revision: Long): Pair<String, List<String>> {
        val client = llm
        val system = prompts?.loadRawPrompt("dialogue/session_projection.md")?.takeIf(String::isNotBlank)
        if (client == null || system == null) {
            val texts = turns.flatMap { it.entries }.map { it.value }
            val summary = texts.joinToString("；") { item ->
                "${item["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty()}：${item["message"]?.jsonPrimitive?.contentOrNull.orEmpty().take(80)}"
            }.take(600)
            val keyPoints = texts.takeLast(3).map { it["message"]?.jsonPrimitive?.contentOrNull.orEmpty().take(80) }
                .filter(String::isNotBlank)
            return summary to keyPoints
        }
        val turnsJson = buildJsonArray {
            turns.forEach { turn ->
                add(buildJsonObject {
                    put("turn_id", turn.turnId)
                    put("entries", buildJsonArray {
                        turn.entries.forEach { entry ->
                            add(buildJsonObject {
                                put("seq", entry.seq)
                                put("speaker", entry.value["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty())
                                put("role", entry.value["role"]?.jsonPrimitive?.contentOrNull.orEmpty())
                                put("message", entry.value["message"]?.jsonPrimitive?.contentOrNull.orEmpty().take(800))
                                entry.value["inner_thought"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf(String::isNotBlank)?.let { put("inner_thought", it.take(240)) }
                            })
                        }
                    })
                })
            }
        }
        val response = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", system),
                LlmClient.ChatMessage("user", buildJsonObject {
                    put("source_revision", revision)
                    put("turns", turnsJson)
                }.toString()),
            ),
            model = storage.loadModelSettings()["model"] as? String ?: "gpt-4",
            temperature = 0.2,
            maxTokens = 1_200,
            requireJsonObject = true,
        )
        val content = response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        require(content.isNotBlank()) { "Projection model returned an empty response" }
        val normalized = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val result = runCatching { json.parseToJsonElement(normalized).jsonObject }
            .getOrElse { throw IllegalStateException("Projection model returned invalid JSON", it) }
        val summary = result["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(summary.isNotBlank()) { "Projection model returned an empty summary" }
        val keyPoints = (result["key_points"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            .take(8)
        return summary to keyPoints
    }

    private fun buildSessionSummary(volumes: List<SummaryVolume>, manifest: JsonObject): JsonObject {
        val recap = volumes.takeLast(4).joinToString("\n") { it.summary }.takeLast(2_000)
        val last = volumes.lastOrNull()
        val stateScene = manifest["state"]?.jsonObject?.get("scene")?.jsonObject ?: JsonObject(emptyMap())
        val sceneProgress = manifest["scene_progress"]?.jsonObject ?: JsonObject(emptyMap())
        return buildJsonObject {
            put("recap", recap)
            put("current_goal", last?.keyPoints?.firstOrNull().orEmpty())
            put("unresolved_threads", buildJsonArray {
                last?.keyPoints.orEmpty().take(5).forEach { add(JsonPrimitive(it)) }
            })
            put(
                "current_location",
                stateScene["location"]?.jsonPrimitive?.contentOrNull
                    ?: sceneProgress["location"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            put("current_companions", buildJsonArray {
                (manifest["participants"] as? JsonArray).orEmpty().forEach(::add)
            })
            put("pending_commitments", JsonArray(emptyList()))
            put("recent_conflicts", JsonArray(emptyList()))
            put("major_beats", buildJsonArray {
                volumes.takeLast(6).forEach { add(JsonPrimitive(it.summary)) }
            })
        }
    }

    private fun JsonObject.withCurrentState(manifest: JsonObject): JsonObject {
        val stateScene = manifest["state"]?.jsonObject?.get("scene")?.jsonObject ?: JsonObject(emptyMap())
        val sceneProgress = manifest["scene_progress"]?.jsonObject ?: JsonObject(emptyMap())
        return buildJsonObject {
            this@withCurrentState.forEach { (key, value) -> put(key, value) }
            put(
                "current_location",
                stateScene["location"]?.jsonPrimitive?.contentOrNull
                    ?: sceneProgress["location"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            put("current_companions", buildJsonArray {
                (manifest["participants"] as? JsonArray).orEmpty().forEach(::add)
            })
        }
    }

    private fun archivedSummary(projection: SessionProjection, transcriptCount: Int): JsonObject {
        val cutoff = (transcriptCount - RECENT_ENTRIES_KEPT).coerceAtLeast(0)
        if (cutoff == 0) return JsonObject(emptyMap())
        val archived = projection.volumes.filter { it.endSeq <= cutoff }
        if (archived.isEmpty()) return JsonObject(emptyMap())
        return buildJsonObject {
            // 归档摘要要保留最近的上下文：按时间累积后截断时取下限，而不是最老的部分。
            put("summary", archived.joinToString("\n") { it.summary }.takeLast(1_800))
            put("key_points", buildJsonArray {
                archived.flatMap { it.keyPoints }.distinct().takeLast(12).forEach { add(JsonPrimitive(it)) }
            })
            put("compressed_turns", archived.sumOf(SummaryVolume::turnCount))
            put("recent_turns_kept", transcriptCount - cutoff)
        }
    }

    private fun storyRecap(projection: SessionProjection, snapshot: SessionTranscriptSnapshot): StoryRecapDto {
        val manifest = snapshot.manifest
        val summary = projection.sessionSummary
        val turns = groupTurns(snapshot.items)
        val stateScene = manifest["state"]?.jsonObject?.get("scene")?.jsonObject ?: JsonObject(emptyMap())
        val location = stateScene["location"]?.jsonPrimitive?.contentOrNull
            ?: manifest["scene_progress"]?.jsonObject?.get("location")?.jsonPrimitive?.contentOrNull.orEmpty()
        val timeHint = stateScene["time_hint"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val atmosphere = stateScene["atmosphere"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val events = turns.takeLast(10).map { turn ->
            val first = turn.entries.first().value
            StoryEventDto(
                title = first["message"]?.jsonPrimitive?.contentOrNull.orEmpty().take(60),
                turnId = turn.turnId,
                timeHint = timeHint,
                location = location,
                participants = turn.entries.mapNotNull {
                    it.value["speaker"]?.jsonPrimitive?.contentOrNull
                }.distinct(),
                responses = turn.entries.map { entry ->
                    StoryResponseDto(
                        speaker = entry.value["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        message = entry.value["message"]?.jsonPrimitive?.contentOrNull.orEmpty().take(400),
                    )
                },
                updatedAt = turn.entries.lastOrNull()?.value?.get("timestamp")?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        val recap = summary["recap"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val nextHint = summary["current_goal"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return StoryRecapDto(
            title = manifest["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "剧情复盘" },
            summary = recap,
            location = location,
            timeHint = timeHint,
            atmosphere = atmosphere,
            participants = (manifest["participants"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            eventCount = turns.size,
            chapterCount = projection.volumes.size,
            unresolvedHookCount = projection.volumes.lastOrNull()?.keyPoints?.size ?: 0,
            events = events,
            relations = emptyList(),
            characterArcs = emptyList(),
            hooks = projection.volumes.lastOrNull()?.keyPoints.orEmpty(),
            quotes = snapshot.items.filter {
                it.value["role"]?.jsonPrimitive?.contentOrNull != "user"
            }.takeLast(5).map { entry ->
                StoryQuoteDto(
                    speaker = entry.value["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    message = entry.value["message"]?.jsonPrimitive?.contentOrNull.orEmpty().take(300),
                )
            },
            nextHint = nextHint,
            shareText = listOf(recap, nextHint).filter(String::isNotBlank).joinToString("\n\n"),
        )
    }

    private fun recentSnapshot(snapshot: SessionHistorySnapshot): SessionTranscriptSnapshot {
        val items = (snapshot.manifest["transcript"] as? JsonArray).orEmpty().mapIndexedNotNull { index, raw ->
            (raw as? JsonObject)?.let { TranscriptEntry(snapshot.transcriptStart + index, it) }
        }
        return SessionTranscriptSnapshot(snapshot.manifest, snapshot.revision, items)
    }

    private fun isFresh(projection: SessionProjection?, snapshot: SessionHistorySnapshot): Boolean {
        if (projection == null || projection.projectionVersion != SESSION_PROJECTION_VERSION) return false
        if (projection.runId != snapshot.manifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty() ||
            projection.sessionId != snapshot.manifest["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        ) return false
        if (projection.sourceRevision != snapshot.revision || projection.sourceStartSeq != 0 ||
            projection.sourceEndSeq != snapshot.transcriptCount
        ) return false
        var expected = 0
        projection.volumes.forEach { volume ->
            if (volume.startSeq != expected || volume.endSeq <= volume.startSeq) return false
            expected = volume.endSeq
        }
        return expected == projection.sourceEndSeq
    }

    private fun groupTurns(entries: List<TranscriptEntry>): List<TurnSlice> {
        val result = mutableListOf<TurnSlice>()
        var currentId = ""
        var current = mutableListOf<TranscriptEntry>()
        entries.forEach { entry ->
            val id = entry.value["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "seq-${entry.seq}" }
            if (current.isNotEmpty() && id != currentId) {
                result += TurnSlice(currentId, current)
                current = mutableListOf()
            }
            currentId = id
            current += entry
        }
        if (current.isNotEmpty()) result += TurnSlice(currentId, current)
        return result
    }

    private fun sourceHash(entries: List<TranscriptEntry>): String =
        JsonArray(entries.map { it.value }).toString().encodeToByteArray().toByteString().sha256().hex()

    private fun JsonObject.toPlainMap(): Map<String, Any?> = mapValues { (_, value) -> value.toPlain() }

    private fun JsonElement.toPlain(): Any? = when (this) {
        is JsonObject -> toPlainMap()
        is JsonArray -> map { it.toPlain() }
        is JsonPrimitive -> if (isString) content else contentOrNull ?: toString()
    }

    private fun key(runId: String, sessionId: String): String = "${runId.trim()}:${sessionId.trim()}"

    private companion object {
        const val TURNS_PER_VOLUME = 8
        const val RECENT_ENTRIES_KEPT = 8
    }
}
