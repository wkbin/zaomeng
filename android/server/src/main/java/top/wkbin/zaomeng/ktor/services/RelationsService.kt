package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Instant

/**
 * 人物关系服务
 *
 * 对应 Python src/web/artifacts/operations.py 的 list_relation_details /
 * update_relation_detail 与 src/core/relation_store.py 的冲突检测。
 * 存储：runs/{run_id}/artifacts/relations/{novel_id}_relations.md（YAML front-matter）。
 */
class RelationsService(private val storage: StorageService) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 列出人物关系。对应 Python list_relation_details。 */
    fun list(runId: String): JsonObject {
        val manifest = storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found: $runId")
        val file = resolveRelationsFile(runId, manifest) ?: return emptyDetails(runId, manifest)
        val payload = loadPayload(file) ?: return emptyDetails(runId, manifest)
        val relations = payload["relations"]?.jsonObject ?: JsonObject(emptyMap())
        val conflicts = payload["conflicts"]?.jsonArray ?: JsonArray(emptyList())
        val items = buildJsonArray {
            relations.entries.sortedBy { it.key }.forEach { (pairKey, value) ->
                val relation = runCatching { value.jsonObject }.getOrNull() ?: return@forEach
                val leftRight = splitPair(pairKey)
                add(
                    buildJsonObject {
                        put("pair_key", pairKey)
                        put("characters", buildJsonArray {
                            leftRight.forEach { add(JsonPrimitive(it)) }
                        })
                        put("trust", metric(relation, "trust", 0))
                        put("affection", metric(relation, "affection", 0))
                        put("hostility", metric(relation, "hostility", 0))
                        put("relationship_type", relationTypeLabel(relation))
                        put("relation_change", relation["relation_change"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put("conflict_point", relation["conflict_point"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put("typical_interaction", relation["typical_interaction"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put("ambiguity", metric(relation, "ambiguity", 3))
                        put("evidence_lines", buildJsonArray {
                            evidenceLines(relation).forEach { add(JsonPrimitive(it)) }
                        })
                    },
                )
            }
        }
        val conflictMap = buildJsonObject {
            conflicts.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.forEach { item ->
                item["pair_key"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { key ->
                    put(key, item)
                }
            }
        }
        return buildJsonObject {
            put("run_id", runId)
            put("novel_id", manifest["novel_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: runId)
            put("relations_file", file.absolutePath)
            put("relation_count", items.size)
            put("conflict_count", conflictMap.size)
            put("conflicts", buildJsonArray { conflictMap.values.forEach(::add) })
            put("items", items)
        }
    }

    /** 更新人物关系。对应 Python update_relation_detail。 */
    fun update(
        runId: String,
        pairKey: String,
        trust: Int,
        affection: Int,
        hostility: Int,
        ambiguity: Int,
        relationshipType: String,
        relationChange: String,
        conflictPoint: String,
        typicalInteraction: String,
    ): JsonObject {
        val manifest = storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found: $runId")
        val file = resolveRelationsFile(runId, manifest) ?: throw NoSuchElementException("关系图谱不存在，请先导入或生成人物关系。")
        val payload = loadPayload(file) ?: throw IllegalStateException("关系图谱解析失败。")
        val relations = (payload["relations"]?.jsonObject ?: JsonObject(emptyMap())).toMutableMap()
        val existing = relations[pairKey]?.jsonObject
            ?: throw NoSuchElementException("Pair not found: $pairKey")
        val updated = buildJsonObject {
            existing.forEach { (key, value) -> put(key, value) }
            put("trust", clamp(trust))
            put("affection", clamp(affection))
            put("hostility", clamp(hostility))
            put("ambiguity", clamp(ambiguity))
            put("relationship_type", relationshipType.trim())
            put("relation_change", relationChange.trim())
            put("conflict_point", conflictPoint.trim())
            put("typical_interaction", typicalInteraction.trim())
            put("updated_at", System.currentTimeMillis() / 1000)
        }
        relations[pairKey] = updated
        val conflicts = detectConflicts(relations.toMap())
        val updatedPayload = buildJsonObject {
            put("novel_id", manifest["novel_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: runId)
            put("relations", buildJsonObject {
                relations.entries.sortedBy { it.key }.forEach { (key, value) -> put(key, value) }
            })
            put("conflicts", conflicts)
        }
        writePayload(file, updatedPayload)
        return updatedPayload
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private fun emptyDetails(runId: String, manifest: JsonObject): JsonObject = buildJsonObject {
        put("run_id", runId)
        put("novel_id", manifest["novel_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: runId)
        put("relations_file", "")
        put("relation_count", 0)
        put("conflict_count", 0)
        put("conflicts", JsonArray(emptyList()))
        put("items", JsonArray(emptyList()))
    }

    private fun resolveRelationsFile(runId: String, manifest: JsonObject): File? {
        val fromManifest = manifest["artifact_index"]?.jsonObject?.get("relation_graph")?.jsonObject
            ?.get("relations_file")?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
        if (fromManifest != null) {
            val candidate = File(fromManifest)
            if (candidate.isFile) return candidate
        }
        val relationsDir = File(storage.getRunDirectory(runId), "artifacts/relations")
        if (!relationsDir.isDirectory) return null
        return relationsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") && it.name.contains("relation") && !it.name.endsWith(".mermaid.md") }
            ?.firstOrNull()
    }

    private fun loadPayload(file: File): JsonObject? {
        val text = file.readText()
        val frontMatter = extractFrontMatter(text) ?: return null
        return runCatching {
            val yaml = Yaml(org.yaml.snakeyaml.constructor.SafeConstructor(org.yaml.snakeyaml.LoaderOptions()))
            @Suppress("UNCHECKED_CAST")
            val loaded = yaml.load<Any>(frontMatter) as? Map<String, Any> ?: return@runCatching null
            toJsonElement(loaded).jsonObject
        }.getOrNull()
    }

    private fun extractFrontMatter(text: String): String? {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return null
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return null
        return lines.drop(1).take(end).joinToString("\n")
    }

    private fun writePayload(file: File, payload: JsonObject) {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
            indent = 2
        }
        val yaml = Yaml(options)
        val dump = yaml.dump(toJavaMap(payload))
        val content = "---\n$dump---\n# RELATION_GRAPH\n"
        storage.writeTextAtomically(file, content)
    }

    private fun detectConflicts(relations: Map<String, JsonElement>): JsonArray {
        val conflicts = mutableListOf<JsonObject>()
        for ((pairKey, value) in relations) {
            val relation = runCatching { value.jsonObject }.getOrNull() ?: continue
            val trust = metric(relation, "trust", 0)
            val affection = metric(relation, "affection", 0)
            val hostility = metric(relation, "hostility", 0)
            val ambiguity = metric(relation, "ambiguity", 3)
            val tags = mutableListOf<String>()
            if (trust >= 8 && hostility >= 6) tags.add("high_trust_high_hostility")
            if (affection >= 8 && hostility >= 6) tags.add("high_affection_high_hostility")
            if (ambiguity >= 8 && maxOf(trust, affection, hostility) >= 8) tags.add("high_ambiguity_with_extreme_signal")
            if (tags.isEmpty()) continue
            conflicts.add(
                buildJsonObject {
                    put("pair_key", pairKey)
                    put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
                },
            )
        }
        return buildJsonArray { conflicts.forEach(::add) }
    }

    private fun metric(relation: JsonObject, field: String, fallback: Int): Int {
        val value = relation[field]?.jsonPrimitive?.intOrNull ?: fallback
        return value.coerceIn(0, 10)
    }

    private fun splitPair(pairKey: String): List<String> {
        val parts = pairKey.split("_").map(String::trim).filter(String::isNotEmpty)
        return when {
            parts.size >= 2 -> listOf(parts[0], parts[1])
            parts.isNotEmpty() -> listOf(parts[0], "")
            else -> listOf("", "")
        }
    }

    private fun relationTypeLabel(relation: JsonObject): String {
        relation["relationship_type"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { return it }
        val trust = metric(relation, "trust", 0)
        val affection = metric(relation, "affection", 0)
        val hostility = metric(relation, "hostility", 0)
        return when {
            hostility >= maxOf(trust, affection) && hostility >= 6 -> "对立"
            affection >= 8 && trust >= 7 -> "深情"
            trust >= 7 -> "亲近"
            hostility >= 4 -> "拉扯"
            else -> "牵连"
        }
    }

    private fun evidenceLines(relation: JsonObject): List<String> {
        val raw = relation["evidence_lines"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        if (raw.isNotEmpty()) return raw.take(3)
        return listOfNotNull(
            relation["typical_interaction"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty),
            relation["conflict_point"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty),
        ).take(2)
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, 10)

    @Suppress("UNCHECKED_CAST")
    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> if (value is Int) JsonPrimitive(value) else JsonPrimitive(value.toDouble())
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (key, item) -> put(key.toString(), toJsonElement(item)) }
        }
        is List<*> -> buildJsonArray { value.forEach { add(toJsonElement(it)) } }
        is Iterable<*> -> buildJsonArray { value.forEach { add(toJsonElement(it)) } }
        else -> JsonPrimitive(value.toString())
    }

    private fun toJavaMap(payload: JsonObject): Map<String, Any> {
        fun convert(element: JsonElement): Any = when (element) {
            is JsonObject -> element.mapValues { convert(it.value) }
            is JsonArray -> element.map { convert(it) }
            is JsonPrimitive -> element.contentOrNull?.let { content ->
                content.toIntOrNull() ?: content.toBooleanStrictOrNull() ?: content
            } ?: ""
        }
        return payload.mapValues { convert(it.value) }
    }
}
