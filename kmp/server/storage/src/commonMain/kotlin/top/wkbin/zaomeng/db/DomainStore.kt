package top.wkbin.zaomeng.db

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
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

    // 数据根目录可能包含 `(`、`+`、`[` 等正则元字符（例如安装在 "Zaomeng (Beta)"），
    // 直接拼进正则会令所有实体同步静默失效。
    private val rootKeyPattern = Regex.escape(rootKey)

    private val runManifestPattern = Regex("^$rootKeyPattern/runs/([^/]+)/run_manifest\\.json$")
    private val sessionManifestPattern =
        Regex("^$rootKeyPattern/runs/([^/]+)/dialogue/sessions/([^/]+)/session_manifest\\.json$")
    private val cardMetaPattern =
        Regex("^$rootKeyPattern/(scene-cards|self-cards|opening-presets)/([^/]+)/card\\.json$")
    private val cardDataPattern =
        Regex("^$rootKeyPattern/(scene-cards|self-cards|opening-presets)/([^/]+)/(scene-card|self-card|opening-preset)\\.json$")
    private val personaPattern =
        Regex("^$rootKeyPattern/runs/([^/]+)/artifacts/characters/([^/]+)/([^/]+)/PROFILE(?:\\.generated)?\\.md$")

    private fun keyOf(path: Path): String = path.toString().replace('\\', '/')

    // ------------------------------------------------------------------ 写入

    /** StorageService 写入任意文件后调用：识别业务路径并同步实体。 */
    fun onWrite(path: Path, bytes: ByteArray, updatedAtMillis: Long) {
        val key = keyOf(path)
        when {
            runManifestPattern.matches(key) -> upsertRun(key, bytes.decodeToString(), updatedAtMillis)
            sessionManifestPattern.matches(key) -> upsertSession(key, bytes.decodeToString(), updatedAtMillis)
            cardMetaPattern.matches(key) || cardDataPattern.matches(key) -> upsertCard(key)
            personaPattern.matches(key) -> upsertPersona(key, bytes.decodeToString(), updatedAtMillis)
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
        val runDir = Regex("^$rootKeyPattern/runs/([^/]+)$").matchEntire(key)
        if (runDir != null) {
            deleteRunCascade(runDir.groupValues[1])
            return
        }
        val sessionDir = Regex("^$rootKeyPattern/runs/([^/]+)/dialogue/sessions/([^/]+)$").matchEntire(key)
        if (sessionDir != null) {
            deleteSessionCascade(sessionDir.groupValues[1], sessionDir.groupValues[2])
            return
        }
        val cardDir = Regex("^$rootKeyPattern/(scene-cards|self-cards|opening-presets)/([^/]+)$").matchEntire(key)
        if (cardDir != null) {
            runBlockingPlatform { dao.deleteCard(cardDir.groupValues[2]) }
        }
    }

    // ------------------------------------------------------------------ 读取

    fun listRunIds(): List<String> {
        return runBlockingPlatform { dao.runIds() }
    }

    fun listRunManifests(): List<JsonObject> {
        return runBlockingPlatform { dao.allRuns() }
            .mapNotNull { runCatching { json.parseToJsonElement(it.manifest).jsonObject }.getOrNull() }
    }

    fun runExists(runId: String): Boolean {
        return runBlockingPlatform { dao.runExists(runId) }
    }

    fun listSessionIds(runId: String): List<String> {
        return runBlockingPlatform { dao.sessionIdsOf(runId) }
    }

    fun listSessionRunIds(): List<String> = runBlockingPlatform { dao.sessionRunIds() }

    fun listSessionManifests(runId: String): List<JsonObject> {
        return runBlockingPlatform { dao.sessionsOf(runId) }
            .mapNotNull { runCatching { json.parseToJsonElement(it.manifest).jsonObject }.getOrNull() }
    }

    fun listCards(kind: String): List<CardEntity> {
        return runBlockingPlatform { dao.cardsOf(kind) }
    }

    /** 删除单个角色的 Room 实体行；档案目录删除由调用方负责。 */
    fun deletePersonas(runId: String, novelId: String, names: Collection<String>) {
        val distinct = names.filter(String::isNotBlank).distinct()
        if (distinct.isEmpty()) return
        runBlockingPlatform { dao.deletePersonas(runId, novelId, distinct) }
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
        val session = SessionEntity(
            sessionId = sessionId,
            runId = runId,
            title = manifest["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            mode = manifest["mode"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            status = manifest["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            updatedAtMillis = updated,
            manifest = text,
        )
        val startSeq = manifest["transcript_start"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
        val transcript = (manifest["transcript"] as? JsonArray).orEmpty()
        val messages = transcript.mapIndexedNotNull { seq, element ->
            runCatching {
                val item = element.jsonObject
                MessageEntity(
                    runId = runId,
                    sessionId = sessionId,
                    turnId = item["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    seq = startSeq + seq,
                    speaker = item["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    role = item["role"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    message = item["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    timestamp = item["timestamp"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }.getOrNull()
        }
        runBlockingPlatform { dao.replaceSessionWindow(session, startSeq, messages) }
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
        runBlockingPlatform { dao.deleteRunCascade(runId) }
    }

    private fun deleteSessionCascade(runId: String, sessionId: String) {
        runBlockingPlatform { dao.deleteSessionCascade(runId, sessionId) }
    }

    private fun readText(path: Path): String? = documents.readBytes(path)?.decodeToString()

    private fun parseIsoMillis(iso: String): Long? =
        runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

}
