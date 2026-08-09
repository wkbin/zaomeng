package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid

/**
 * 可复用卡片管理服务
 *
 * 对应 Python src/web/review/{scene_cards,self_cards,opening_presets}.py：
 * 场景卡（scene-cards/）、自我角色卡（self-cards/）、开局预设（opening-presets/）
 * 的列表/读取/创建/更新/删除，以及场景卡推荐（规则引擎）。
 *
 * 存储布局与 Python 一致：{root}/{kind}/{card_id}/card.json（元信息）+ 字段文件。
 */
class CardsManagementService(private val storage: StorageService) {
    companion object {
        private val SCENE_REQUIRED_FIELDS = listOf("title", "location", "atmosphere", "opening_situation", "scene_drive")
        private val SCENE_PREVIEW_FIELDS = listOf("title", "time_hint", "location", "atmosphere", "opening_situation", "scene_drive", "expected_rhythm")
        private val GROUP_SCENE_TOKENS = "厅堂席宴城殿宫"
        private val DUO_SCENE_TOKENS = listOf("对坐", "独处", "夜谈", "相顾")
        private val INSERT_SCENE_TOKENS = listOf("插入", "切入", "乱入")
        private val PLOT_PUSH_TOKENS = listOf("试探", "摊牌", "转折", "对峙")
    }

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    /** 列出某类卡片（按 updated_at 倒序）。 */
    fun list(kind: String): JsonArray {
        val root = cardsRoot(kind)
        if (!storage.exists(root)) return JsonArray(emptyList())
        val items = storage.listFiles(root)
            .filter { storage.isDirectory(it) }
            .mapNotNull { dir -> load(kind, dir.name) }
            .sortedWith(compareByDescending<JsonObject> { it["updated_at"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                .thenByDescending { it["card_id"]?.jsonPrimitive?.contentOrNull.orEmpty() })
        return buildJsonArray { items.forEach(::add) }
    }

    /** 读取单张卡片。 */
    fun get(kind: String, cardId: String): JsonObject =
        load(kind, cardId) ?: throw NoSuchElementException("Card not found: $cardId")

    /** 创建或更新卡片。对应 Python save_*_card_payload。 */
    fun save(kind: String, cardId: String, fields: JsonObject): JsonObject {
        val normalizedKind = normalizeKind(kind)
        val root = cardsRoot(normalizedKind)
        val resolvedId = cardId.trim().ifEmpty {
            prefixFor(normalizedKind) + randomUuid().replace("-", "").take(10)
        }
        val cardDir = root / resolvedId
        if (cardId.isNotBlank() && !storage.exists(cardDir)) throw NoSuchElementException("Card not found: $cardId")
        storage.mkdirs(cardDir)
        val now = nowIsoString()
        val metaFile = cardDir / "card.json"
        val existingMeta = if (storage.exists(metaFile)) {
            runCatching { json.parseToJsonElement(storage.readText(metaFile)).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        } else {
            JsonObject(emptyMap())
        }
        val createdAt = existingMeta["created_at"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: now

        val normalizedFields = normalizeFields(normalizedKind, fields)
        if (normalizedKind == "scene") {
            val missing = SCENE_REQUIRED_FIELDS.filter { normalizedFields[it]?.jsonPrimitive?.contentOrNull?.isBlank() != false }
            if (missing.isNotEmpty()) throw IllegalArgumentException("请先补全这些必填项：${missing.joinToString("、")}")
        }
        val dataFile = cardDir / dataFileName(normalizedKind)
        storage.writeTextAtomically(dataFile, json.encodeToString(JsonObject.serializer(), normalizedFields))
        storage.writeTextAtomically(
            metaFile,
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("card_id", resolvedId)
                    put("created_at", createdAt)
                    put("updated_at", now)
                },
            ),
        )
        return buildJsonObject {
            put("card_id", resolvedId)
            put("fields", normalizedFields)
            put("preview", previewOf(normalizedKind, normalizedFields))
            put("created_at", createdAt)
            put("updated_at", now)
        }
    }

    /** 删除卡片。 */
    fun delete(kind: String, cardId: String): JsonObject {
        val root = cardsRoot(kind)
        val cardDir = root / cardId
        if (!storage.exists(cardDir)) throw NoSuchElementException("Card not found: $cardId")
        storage.deleteRecursively(cardDir)
        return buildJsonObject {
            put("status", "deleted")
            put("card_id", cardId)
        }
    }

    /**
     * 场景卡推荐（规则引擎，不调 LLM）。
     * 对应 Python src/skill_support/scene_recommendations.py。
     */
    fun recommend(mode: String, participants: List<String>): JsonObject {
        val root = cardsRoot("scene")
        val candidates = mutableListOf<JsonObject>()
        if (storage.exists(root)) {
            storage.listFiles(root).filter { storage.isDirectory(it) }.forEach { dir ->
                load("scene", dir.name)?.let { candidates.add(it) }
            }
        }
        val scored = candidates.mapNotNull { card ->
            val fields = card["fields"]?.jsonObject ?: return@mapNotNull null
            val text = fields.mapNotNull { (_, value) -> value.jsonPrimitive.contentOrNull }.joinToString("")
            var score = 0
            if (!fields["scene_drive"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) score += 3
            if (!fields["opening_situation"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) score += 2
            if (!fields["atmosphere"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) score += 1
            if (participants.size >= 3 && text.any { it in GROUP_SCENE_TOKENS }) score += 2
            if (participants.size == 2 && DUO_SCENE_TOKENS.any { text.contains(it) }) score += 1
            if (mode == "insert" && INSERT_SCENE_TOKENS.any { text.contains(it) }) score += 1
            if (mode == "observe" && PLOT_PUSH_TOKENS.any { text.contains(it) }) score += 1
            Triple(card, score, text)
        }.sortedWith(compareByDescending<Triple<JsonObject, Int, String>> { it.second }
            .thenByDescending { it.first["updated_at"]?.jsonPrimitive?.contentOrNull.orEmpty() })

        val recommended = scored.firstOrNull()
        val recommendedAutoContinue = if (recommended != null && mode == "observe") {
            "场景已切换，请继续推动剧情。"
        } else {
            ""
        }
        return buildJsonObject {
            put("recommended_card_id", recommended?.first?.get("card_id")?.jsonPrimitive?.contentOrNull.orEmpty())
            put("items", buildJsonArray {
                scored.forEach { (card, _, _) -> add(card) }
            })
            put("mode", mode)
            put("participants", buildJsonArray { participants.forEach { add(JsonPrimitive(it)) } })
            put("recommended_auto_continue_message", recommendedAutoContinue)
        }
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private fun cardsRoot(kind: String): Path {
        val normalized = normalizeKind(kind)
        val dirName = when (normalized) {
            "scene" -> "scene-cards"
            "self" -> "self-cards"
            else -> "opening-presets"
        }
        val root = storage.getStorageRoot() / dirName
        storage.mkdirs(root)
        return root
    }

    private fun normalizeKind(kind: String): String = when (kind.lowercase()) {
        "scene", "scene-cards", "scene_card" -> "scene"
        "self", "self-cards", "self_card" -> "self"
        else -> "opening"
    }

    private fun prefixFor(kind: String): String = when (kind) {
        "scene" -> "scene-"
        "self" -> "card-"
        else -> "opening-"
    }

    private fun dataFileName(kind: String): String = when (kind) {
        "scene" -> "scene-card.json"
        "self" -> "self-card.json"
        else -> "opening-preset.json"
    }

    private fun load(kind: String, cardId: String): JsonObject? {
        val cardDir = cardsRoot(kind) / cardId
        val metaFile = cardDir / "card.json"
        val dataFile = cardDir / dataFileName(kind)
        if (!storage.exists(cardDir) || !storage.exists(metaFile)) return null
        val meta = runCatching { json.parseToJsonElement(storage.readText(metaFile)).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val fields = if (storage.exists(dataFile)) {
            runCatching { json.parseToJsonElement(storage.readText(dataFile)).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        } else {
            JsonObject(emptyMap())
        }
        if (fields.isEmpty()) return null
        return buildJsonObject {
            put("card_id", cardId)
            put("fields", fields)
            put("preview", previewOf(kind, fields))
            put("created_at", meta["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("updated_at", meta["updated_at"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }

    private fun normalizeFields(kind: String, fields: JsonObject): JsonObject {
        val allowed = when (kind) {
            "scene" -> SCENE_REQUIRED_FIELDS + listOf("time_hint", "public_goal", "hidden_tension", "forbidden_topics")
            else -> null // self / opening 保留全部传入字段
        }
        return buildJsonObject {
            if (allowed != null) {
                allowed.forEach { field ->
                    put(field, fields[field]?.jsonPrimitive?.contentOrNull?.trim() ?: "")
                }
            } else {
                fields.forEach { (key, value) -> put(key, value) }
            }
        }
    }

    private fun previewOf(kind: String, fields: JsonObject): JsonObject = buildJsonObject {
        when (kind) {
            "scene" -> SCENE_PREVIEW_FIELDS.forEach { field ->
                fields[field]?.let { put(field, it) }
            }
            "self" -> listOf("display_name", "name", "scene_identity", "interaction_style").forEach { field ->
                fields[field]?.let { put(field, it) }
            }
            else -> listOf("title", "mode", "participants").forEach { field ->
                fields[field]?.let { put(field, it) }
            }
        }
    }
}
