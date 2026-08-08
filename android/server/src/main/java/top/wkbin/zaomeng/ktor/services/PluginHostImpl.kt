package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.*
import top.wkbin.zaomeng.plugins.api.PluginHost
import top.wkbin.zaomeng.plugins.api.SuggestionOption
import java.time.Instant
import java.util.UUID

/**
 * 插件宿主（对齐 Python ZaomengPluginHost）：把插件需要的模型能力绑定到 Ktor 现有服务。
 * server 实现并注入给内置插件；插件本身不依赖 server 具体实现。
 */
class PluginHostImpl(
    private val storage: StorageService,
    private val llm: LlmClient,
    private val dialogueAdvanced: DialogueAdvancedService,
    private val suggestions: SuggestionsService,
    private val pluginService: PluginService,
) : PluginHost {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun invokeSuggestion(runId: String, sessionId: String, seedText: String, direction: String): String {
        val result = dialogueAdvanced.suggestDialogue(runId, sessionId, seedText, direction)
        return result["suggestion"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }

    override suspend fun invokeVariants(runId: String, sessionId: String, seedText: String, direction: String): List<SuggestionOption> {
        return suggestions.generateAssociations(runId, sessionId, optionCount = 3).map {
            SuggestionOption(label = it.label, suggestion = it.suggestion.orEmpty())
        }
    }

    override suspend fun invokeNpc(runId: String, sessionId: String, direction: String): JsonObject {
        val npc = generateNpcObject(runId, sessionId, direction)
        return addTemporaryNpc(runId, sessionId, npc)
    }

    override fun log(pluginId: String, level: String, message: String) {
        try {
            pluginService.appendLog(pluginId, level, message)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // 临时 NPC 生成（对齐 Python _generate_plugin_temporary_npc + add_temporary_npc）
    // ------------------------------------------------------------------

    private suspend fun generateNpcObject(runId: String, sessionId: String, direction: String): Map<String, String> {
        val session = storage.loadSessionManifest(runId, sessionId)
        val participants = (session["participants"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() }
        val recent = (session["transcript"]?.jsonArray ?: JsonArray(emptyList()))
            .takeLast(6).mapNotNull { it.jsonObject }
            .joinToString("\n") { entry ->
                "${entry["speaker"]?.jsonPrimitive?.contentOrNull ?: ""}：${entry["message"]?.jsonPrimitive?.contentOrNull ?: ""}"
            }
        val system = buildString {
            append("你为对话场景生成一名临时角色（NPC）。必须返回严格的 JSON 对象，只包含以下字段：\n")
            append("\"name\"（名字，2-6 字）、\"role\"（身份）、\"appearance\"（外貌）、\"personality\"（性格）、")
            append("\"speech_style\"（说话风格）、\"motive\"（动机）、\"entrance\"（入场动作描述）、\"opening_line\"（入场台词，必须有一句）\n")
            append("不要返回 JSON 以外的任何文字，不要使用代码围栏。")
        }
        val user = buildString {
            append("场景参与者：${participants.joinToString("、")}\n")
            if (recent.isNotBlank()) append("最近对话：\n$recent\n")
            append("生成要求：$direction")
        }
        val content = llm.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage(role = "system", content = system),
                LlmClient.ChatMessage(role = "user", content = user),
            ),
            temperature = 0.9,
            maxTokens = 600,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (content.isBlank()) throw IllegalArgumentException("临时 NPC 生成失败：模型返回为空。")
        val element = runCatching {
            json.parseToJsonElement(stripCodeFences(content))
        }.getOrNull() ?: throw IllegalArgumentException("临时 NPC 生成失败：模型输出不是合法 JSON。")
        val obj = element.jsonObject
        val name = (obj["name"]?.jsonPrimitive?.contentOrNull ?: "").trim()
        if (name.isEmpty() || "\n" in name) throw IllegalArgumentException("临时 NPC 必须有有效名称。")
        val reserved = setOf("user", "你", "旁白", "场景提示", "模型推理", "system", "assistant")
        if (name.lowercase() in reserved) throw IllegalArgumentException("临时 NPC 不能使用系统保留名称。")
        if (participants.any { it.lowercase() == name.lowercase() }) {
            throw IllegalArgumentException("当前会话已经存在名为“$name”的角色。")
        }
        val openingLine = (obj["opening_line"]?.jsonPrimitive?.contentOrNull ?: "").trim()
        if (openingLine.isEmpty()) throw IllegalArgumentException("临时 NPC 必须有一句入场台词。")
        return mapOf(
            "name" to name,
            "role" to (obj["role"]?.jsonPrimitive?.contentOrNull ?: "临时来客").trim().take(100),
            "appearance" to (obj["appearance"]?.jsonPrimitive?.contentOrNull ?: "").trim().take(240),
            "personality" to (obj["personality"]?.jsonPrimitive?.contentOrNull ?: "").trim().take(240),
            "speech_style" to (obj["speech_style"]?.jsonPrimitive?.contentOrNull ?: "").trim().take(240),
            "motive" to (obj["motive"]?.jsonPrimitive?.contentOrNull ?: "").trim().take(240),
            "entrance" to (obj["entrance"]?.jsonPrimitive?.contentOrNull ?: "").trim().take(500),
            "opening_line" to openingLine.take(500),
        )
    }

    /** 写入会话 temporary_npcs + participants + transcript，返回更新后的 NPC 对象（键值均为字符串）。 */
    private fun addTemporaryNpc(runId: String, sessionId: String, npc: Map<String, String>): JsonObject {
        val manifestFile = storage.getDialogueSessionManifestFile(runId, sessionId)
        val session = storage.loadSessionManifest(runId, sessionId)
        val name = npc.getValue("name")
        val now = Instant.now().toString()
        val turnId = "npc-${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val record = buildJsonObject {
            npc.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            put("introduced_at", JsonPrimitive(now))
            put("status", JsonPrimitive("active"))
            put("source", JsonPrimitive("plugin"))
        }
        val participants = (session["participants"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() }.toMutableList()
        if (participants.none { it.lowercase() == name.lowercase() }) participants.add(name)
        val transcript = (session["transcript"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        transcript.add(buildJsonObject {
            put("speaker", JsonPrimitive("场景提示"))
            put("message", JsonPrimitive(npc["entrance"]?.ifBlank { "${name}来到了现场。" } ?: "${name}来到了现场。"))
            put("role", JsonPrimitive("scene"))
            put("turn_id", JsonPrimitive(turnId))
            put("timestamp", JsonPrimitive(now))
            put("source", JsonPrimitive("temporary_npc_plugin"))
        })
        transcript.add(buildJsonObject {
            put("speaker", JsonPrimitive(name))
            put("message", JsonPrimitive(npc["opening_line"].orEmpty()))
            put("role", JsonPrimitive("character"))
            put("turn_id", JsonPrimitive(turnId))
            put("timestamp", JsonPrimitive(now))
            put("source", JsonPrimitive("temporary_npc_plugin"))
        })
        val temporaryNpcs = (session["temporary_npcs"]?.jsonObject ?: JsonObject(emptyMap())).toMutableMap()
        temporaryNpcs[name] = record
        val updated = buildJsonObject {
            session.forEach { (k, v) -> put(k, v) }
            put("participants", buildJsonArray { participants.forEach { add(JsonPrimitive(it)) } })
            put("transcript", buildJsonArray { transcript.forEach { add(it) } })
            put("temporary_npcs", buildJsonObject { temporaryNpcs.forEach { (k, v) -> put(k, v) } })
        }
        storage.writeTextAtomically(manifestFile, json.encodeToString(JsonObject.serializer(), updated))
        return buildJsonObject { npc.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
    }

    private fun stripCodeFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").removePrefix("json").trim()
            t = t.trimEnd().removeSuffix("```").trim()
        }
        return t
    }
}
