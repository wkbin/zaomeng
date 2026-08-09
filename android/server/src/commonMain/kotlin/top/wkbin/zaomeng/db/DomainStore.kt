package top.wkbin.zaomeng.db

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import top.wkbin.zaomeng.platform.parseYaml
import top.wkbin.zaomeng.platform.runBlockingPlatform

/**
 * 领域实体同步/查询层。
 *
 * - 写入：StorageService 每次写已知业务文档后调用 [onWrite]，把结构化字段同步进实体表；
 * - 删除：[onDelete] 按路径级联清理实体行；
 * - 读取：列表/检索直接查实体表（带索引）。
 */
class DomainStore(
    private val storageRoot: Path,
    private val dao: DomainDao,
    private val documents: DocumentStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val rootKey = storageRoot.toString().replace('\\', '/').trimEnd('/')

    private val runManifestPattern = Regex("^$rootKey/runs/([^/]+)/run_manifest\\.json$")
    private val sessionManifestPattern =
        Regex("^$rootKey/runs/([^/]+)/dialogue/sessions/([^/]+)/session_manifest\\.json$")
    private val cardMetaPattern =
        Regex("^$rootKey/(scene-cards|self-cards|opening-presets)/([^/]+)/card\\.json$")
    private val cardDataPattern =
        Regex("^$rootKey/(scene-cards|self-cards|opening-presets)/([^/]+)/(scene-card|self-card|opening-preset)\\.json$")
    private val personaPattern =
        Regex("^$rootKey/runs/([^/]+)/artifacts/characters/([^/]+)/([^/]+)/PROFILE(?:\\.generated)?\\.md$")

    private fun keyOf(path: Path): String = path.toString().replace('\\', '/')

    // ------------------------------------------------------------------ 写入

    /** StorageService 写入任意文件后调用：识别业务路径并同步实体。 */
    fun onWrite(path: Path, bytes: ByteArray, updatedAtMillis: Long) {
        val key = keyOf(path)
        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return
        when {
            runManifestPattern.matches(key) -> upsertRun(key, text, updatedAtMillis)
            sessionManifestPattern.matches(key) -> upsertSession(key, text, updatedAtMillis)
            cardMetaPattern.matches(key) || cardDataPattern.matches(key) -> upsertCard(key)
            personaPattern.matches(key) -> upsertPersona(key, text, updatedAtMillis)
        }
    }

    /** StorageService 删除文件/目录后调用：级联清理实体行。 */
    fun onDelete(path: Path) {
        val key = keyOf(path)
        runManifestPattern.matchEntire(key)?.let { m ->
            deleteRunCascade(m.groupValues[1])
            return
        }
        sessionManifestPattern.matchEntire(key)?.let { m ->
            deleteSessionCascade(m.groupValues[1], m.groupValues[2])
            return
        }
        cardMetaPattern.matchEntire(key)?.let { m ->
            runBlockingPlatform { dao.deleteCard(m.groupValues[2]) }
            return
        }
        cardDataPattern.matchEntire(key)?.let { m ->
            runBlockingPlatform { dao.deleteCard(m.groupValues[2]) }
            return
        }
        // 目录级删除：runs/<id>、sessions/<sid>、<kindDir>/<cardId>
        val runDir = Regex("^$rootKey/runs/([^/]+)$").matchEntire(key)
        if (runDir != null) {
            deleteRunCascade(runDir.groupValues[1])
            return
        }
        val sessionDir = Regex("^$rootKey/runs/([^/]+)/dialogue/sessions/([^/]+)$").matchEntire(key)
        if (sessionDir != null) {
            deleteSessionCascade(sessionDir.groupValues[1], sessionDir.groupValues[2])
            return
        }
        val cardDir = Regex("^$rootKey/(scene-cards|self-cards|opening-presets)/([^/]+)$").matchEntire(key)
        if (cardDir != null) {
            runBlockingPlatform { dao.deleteCard(cardDir.groupValues[2]) }
        }
    }

    // ------------------------------------------------------------------ 读取

    fun listRunIds(): List<String> {
        return runBlockingPlatform { dao.allRuns().map { it.runId } }
    }

    fun listRunManifests(): List<JsonObject> {
        return runBlockingPlatform { dao.allRuns() }
            .mapNotNull { runCatching { json.parseToJsonElement(it.manifest).jsonObject }.getOrNull() }
    }

    fun runExists(runId: String): Boolean {
        return runBlockingPlatform { dao.runById(runId) != null }
    }

    fun listSessionIds(runId: String): List<String> {
        return runBlockingPlatform { dao.sessionsOf(runId).map { it.sessionId } }
    }

    fun listSessionManifests(runId: String): List<JsonObject> {
        return runBlockingPlatform { dao.sessionsOf(runId) }
            .mapNotNull { runCatching { json.parseToJsonElement(it.manifest).jsonObject }.getOrNull() }
    }

    fun listCards(kind: String): List<CardEntity> {
        return runBlockingPlatform { dao.cardsOf(kind) }
    }

    // ------------------------------------------------------------ 内部实现

    private fun upsertRun(key: String, text: String, updatedAtMillis: Long) {
        val match = runManifestPattern.matchEntire(key) ?: return
        val manifest = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val runId = match.groupValues[1]
        runBlockingPlatform {
            dao.upsertRun(
                RunEntity(
                    runId = runId,
                    title = manifest["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    novelId = manifest["novel_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    status = manifest["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    updatedAtMillis = updatedAtMillis,
                    manifest = text,
                ),
            )
        }
    }

    private fun upsertSession(key: String, text: String, updatedAtMillis: Long) {
        val match = sessionManifestPattern.matchEntire(key) ?: return
        val manifest = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val runId = match.groupValues[1]
        val sessionId = match.groupValues[2]
        val updatedIso = manifest["updated_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val updated = parseIsoMillis(updatedIso) ?: updatedAtMillis
        runBlockingPlatform {
            dao.upsertSession(
                SessionEntity(
                    sessionId = sessionId,
                    runId = runId,
                    title = manifest["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    mode = manifest["mode"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    status = manifest["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    updatedAtMillis = updated,
                    manifest = text,
                ),
            )
            syncMessages(runId, sessionId, manifest["transcript"] as? JsonArray ?: JsonArray(emptyList()))
        }
    }

    private suspend fun syncMessages(runId: String, sessionId: String, transcript: JsonArray) {
        dao.deleteMessagesOf(runId, sessionId)
        val messages = transcript.mapIndexedNotNull { seq, element ->
            runCatching {
                val item = element.jsonObject
                MessageEntity(
                    runId = runId,
                    sessionId = sessionId,
                    turnId = item["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    seq = seq,
                    speaker = item["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    role = item["role"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    message = item["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    timestamp = item["timestamp"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }.getOrNull()
        }
        if (messages.isNotEmpty()) dao.upsertMessages(messages)
    }

    private fun upsertCard(key: String) {
        val metaMatch = cardMetaPattern.matchEntire(key)
        val dataMatch = cardDataPattern.matchEntire(key)
        val kindDir = metaMatch?.groupValues?.get(1) ?: dataMatch?.groupValues?.get(1) ?: return
        val cardId = metaMatch?.groupValues?.get(2) ?: dataMatch?.groupValues?.get(2) ?: return
        val kind = when (kindDir) {
            "scene-cards" -> "scene"
            "self-cards" -> "self"
            else -> "opening"
        }
        val dataFile = when (kind) {
            "scene" -> "scene-card.json"
            "self" -> "self-card.json"
            else -> "opening-preset.json"
        }
        val metaText = readText(storageRoot / kindDir / cardId / "card.json")
        val fieldsText = readText(storageRoot / kindDir / cardId / dataFile)
        if (fieldsText == null) return
        val fields = runCatching { json.parseToJsonElement(fieldsText).jsonObject }.getOrNull() ?: return
        val meta = metaText?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val updatedIso = meta?.get("updated_at")?.jsonPrimitive?.contentOrNull.orEmpty()
        val title = when (kind) {
            "scene" -> fields["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            "self" -> fields["display_name"]?.jsonPrimitive?.contentOrNull
                ?: fields["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            else -> fields["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        runBlockingPlatform {
            dao.upsertCard(
                CardEntity(
                    cardId = cardId,
                    kind = kind,
                    title = title,
                    updatedAtMillis = parseIsoMillis(updatedIso) ?: 0L,
                    fieldsJson = fieldsText,
                ),
            )
        }
    }

    private fun upsertPersona(key: String, text: String, updatedAtMillis: Long) {
        val match = personaPattern.matchEntire(key) ?: return
        val runId = match.groupValues[1]
        val novelId = match.groupValues[2]
        val dirName = match.groupValues[3]
        val frontmatterName = frontmatterNameOf(text)
        val name = frontmatterName ?: dirName
        runBlockingPlatform {
            dao.upsertPersona(
                PersonaEntity(
                    personaId = "$runId::$novelId::$name",
                    runId = runId,
                    novelId = novelId,
                    name = name,
                    updatedAtMillis = updatedAtMillis,
                    profile = text,
                ),
            )
        }
    }

    private fun frontmatterNameOf(text: String): String? {
        val trimmed = text.trimStart('\uFEFF')
        if (!trimmed.startsWith("---")) return null
        val end = trimmed.indexOf("\n---", startIndex = 3)
        if (end < 0) return null
        val frontmatter = trimmed.substring(3, end)
        return runCatching {
            (parseYaml(frontmatter) as? Map<*, *>)?.get("name")?.toString()?.trim()?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun deleteRunCascade(runId: String) {
        runBlockingPlatform {
            dao.deleteRun(runId)
            dao.deleteSessionsOf(runId)
            dao.deleteMessagesOfRun(runId)
            dao.deletePersonasOf(runId)
        }
    }

    private fun deleteSessionCascade(runId: String, sessionId: String) {
        runBlockingPlatform {
            dao.deleteSession(sessionId)
            dao.deleteMessagesOf(runId, sessionId)
        }
    }

    private fun readText(path: Path): String? = documents.readBytes(path)?.decodeToString()

    private fun parseIsoMillis(iso: String): Long? =
        runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

}
