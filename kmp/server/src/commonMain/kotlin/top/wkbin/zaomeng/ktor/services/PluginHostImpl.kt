package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.plugins.api.PluginHost
import top.wkbin.zaomeng.plugins.api.PluginPersonaSummary
import top.wkbin.zaomeng.plugins.api.PluginReplyAsCharacterResult
import top.wkbin.zaomeng.plugins.api.PluginSessionCharacterSummary
import top.wkbin.zaomeng.plugins.api.SuggestionOption
import top.wkbin.zaomeng.platform.createHttpClientEngine
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid
import top.wkbin.zaomeng.platform.SimpleLock

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
    private val httpClient by lazy {
        HttpClient(createHttpClientEngine()) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
        }
    }
    private val sessionActionLocks = mutableMapOf<String, SimpleLock>()
    private val sessionActionLocksGuard = SimpleLock()

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

    override suspend fun readPluginData(pluginId: String, key: String): String? =
        pluginService.readData(pluginId, key)

    override suspend fun writePluginData(pluginId: String, key: String, value: String) {
        pluginService.writeData(pluginId, key, value)
    }

    override suspend fun invokeHttp(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
    ): String? {
        val normalizedMethod = method.trim().uppercase()
        if (normalizedMethod !in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) {
            throw IllegalArgumentException("不支持的插件网络方法：$normalizedMethod")
        }
        val normalizedUrl = validatePluginHttpUrl(url)
        val safeHeaders = sanitizePluginHttpHeaders(headers)
        val response = httpClient.request(normalizedUrl) {
            this.method = HttpMethod.parse(normalizedMethod)
            safeHeaders.forEach { (key, value) -> header(key, value) }
            if (body.isNotBlank()) {
                if (safeHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    contentType(ContentType.Text.Plain)
                }
                setBody(body)
            }
        }
        val bytes = response.bodyAsChannel()
            .readRemaining(MAX_PLUGIN_HTTP_RESPONSE_BYTES + 1L)
            .readByteArray()
        require(bytes.size <= MAX_PLUGIN_HTTP_RESPONSE_BYTES) {
            "插件网络响应超过 ${MAX_PLUGIN_HTTP_RESPONSE_BYTES / 1024} KiB 限制。"
        }
        return bytes.decodeToString()
    }

    override suspend fun listRunPersonas(runId: String): List<PluginPersonaSummary> {
        val manifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val characters = manifest["artifact_index"]?.jsonObject?.get("characters")?.jsonArray.orEmpty()
        return characters.mapNotNull { element ->
            val item = element.jsonObject
            val name = item["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val profileContext = readPersonaProfileContext(runId, name)
            val preview = item["preview"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: item["core_identity"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: profileContext.lineSequence()
                    .map(String::trim)
                    .filter { it.isNotBlank() && it != "---" && !it.startsWith("#") }
                    .take(3)
                    .joinToString(" · ")
            PluginPersonaSummary(name = name, preview = preview.take(240))
        }
    }

    override suspend fun listOffScenePersonas(runId: String, sessionId: String): List<PluginPersonaSummary> {
        val session = storage.loadSessionManifest(runId, sessionId)
        val present = currentSceneCharacters(session).map { it.lowercase() }.toSet()
        return listRunPersonas(runId).filterNot { it.name.lowercase() in present }
    }

    override suspend fun listSessionCharacters(
        runId: String,
        sessionId: String,
    ): List<PluginSessionCharacterSummary> {
        val session = storage.loadSessionManifest(runId, sessionId)
        val muted = stringArray(session, "muted_characters").map { it.lowercase() }.toSet()
        val controlled = session["controlled_character"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val mode = session["mode"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return currentSceneCharacters(session).map { name ->
            PluginSessionCharacterSummary(
                name = name,
                muted = name.lowercase() in muted,
                canMute = mode != "act" || !name.equals(controlled, ignoreCase = true),
            )
        }
    }

    override suspend fun invokeReplyAsCharacter(
        runId: String,
        sessionId: String,
        character: String,
        seedText: String,
        direction: String,
    ): PluginReplyAsCharacterResult? {
        val personas = listOffScenePersonas(runId, sessionId)
        if (personas.isEmpty()) return null
        val selected = character.trim().takeIf(String::isNotBlank)?.let { requested ->
            personas.firstOrNull { it.name.equals(requested, ignoreCase = true) }
                ?: throw IllegalArgumentException("「$requested」不是当前场景外的已蒸馏人物。")
        } ?: personas.first()
        val selectionDirection = buildString {
            append("请由已蒸馏人物「${selected.name}」直接回应聊天记录中最近一位其他人物说的话。")
            append("不要把当前受控人物当作回复对象，也不要模仿最近说话者；必须严格保持「${selected.name}」的身份、语气和认知边界。")
            val personaContext = readPersonaProfileContext(runId, selected.name).ifBlank { selected.preview }
            if (personaContext.isNotBlank()) append("人物档案（只用于模仿该人物）：$personaContext。")
            if (direction.isNotBlank()) {
                append("附加要求：")
                append(direction)
            }
            append("最终只输出「${selected.name}」要说的自然回复文本，不要添加人物名，不要解释过程。")
        }
        val suggestion = dialogueAdvanced.suggestDialogue(
            runId = runId,
            sessionId = sessionId,
            seedText = seedText,
            direction = selectionDirection,
            speakerOverride = selected.name,
        )["suggestion"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (suggestion.isBlank()) return null
        return PluginReplyAsCharacterResult(character = selected.name, text = suggestion)
    }

    override suspend fun setSessionCharacterMuted(
        runId: String,
        sessionId: String,
        character: String,
        muted: Boolean,
    ): JsonObject? {
        val normalized = character.trim()
        if (normalized.isBlank()) throw IllegalArgumentException("请指定需要禁言的人物。")
        val lock = sessionActionLocksGuard.withLock {
            sessionActionLocks.getOrPut("$runId:$sessionId") { SimpleLock() }
        }
        return lock.withLock {
            storage.updateSessionManifest(runId, sessionId) { session ->
                val present = currentSceneCharacters(session)
                val canonicalName = present.firstOrNull { it.equals(normalized, ignoreCase = true) }
                    ?: throw IllegalArgumentException("「$normalized」不在当前场景中。")
                val mode = session["mode"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val controlled = session["controlled_character"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (muted && mode == "act" && canonicalName.equals(controlled, ignoreCase = true)) {
                    throw IllegalArgumentException("不能禁言当前受控角色「$canonicalName」。")
                }
                val mutedSet = (session["muted_characters"]?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() }.toMutableSet()
                mutedSet.removeAll { it.equals(canonicalName, ignoreCase = true) }
                if (muted) mutedSet += canonicalName
                buildJsonObject {
                    session.forEach { (key, value) -> if (key != "muted_characters") put(key, value) }
                    put("muted_characters", buildJsonArray { mutedSet.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("updated_at", JsonPrimitive(nowIsoString()))
                }
            }
        }
    }

    private fun currentSceneCharacters(session: JsonObject): List<String> {
        val scenePresent = runCatching {
            session["scene_progress"]?.jsonObject?.get("present_participants")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter(String::isNotBlank)
        }.getOrNull().orEmpty()
        val statePresent = runCatching {
            session["state"]?.jsonObject?.get("presence")?.jsonObject
                ?.get("present_participants")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter(String::isNotBlank)
        }.getOrNull().orEmpty()
        return scenePresent.ifEmpty { statePresent }.ifEmpty { stringArray(session, "participants") }.distinct()
    }

    private fun stringArray(source: JsonObject, key: String): List<String> =
        runCatching {
            source[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter(String::isNotBlank)
        }.getOrNull().orEmpty()

    private fun readPersonaProfileContext(runId: String, character: String): String {
        val runRoot = storage.getRunDirectory(runId).normalized()
        val item = storage.readRunManifest(runId)?.get("artifact_index")?.jsonObject
            ?.get("characters")?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            ?.firstOrNull {
                it["name"]?.jsonPrimitive?.contentOrNull?.equals(character, ignoreCase = true) == true
            } ?: return ""
        val candidates = buildList {
            item["profile_file"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { raw ->
                add(resolveManifestPath(runRoot, raw))
            }
            item["persona_dir"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { raw ->
                val directory = resolveManifestPath(runRoot, raw)
                add(directory / "PROFILE.md")
                add(directory / "PROFILE.generated.md")
            }
        }
        return candidates.firstOrNull { candidate ->
            candidate.isWithin(runRoot) && storage.isFile(candidate)
        }?.let(storage::readText)?.take(MAX_PLUGIN_PERSONA_CONTEXT_CHARS).orEmpty()
    }

    private fun resolveManifestPath(runRoot: Path, raw: String): Path {
        val path = raw.toPath()
        return (if (path.isAbsolute) path else runRoot / path).normalized()
    }

    private fun Path.isWithin(root: Path): Boolean {
        val relative = runCatching { normalized().relativeTo(root) }.getOrNull() ?: return false
        val value = relative.toString().replace('\\', '/')
        return value != ".." && !value.startsWith("../")
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
            requireJsonObject = true,
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
        val now = nowIsoString()
        val turnId = "npc-${randomUuid().replace("-", "").take(10)}"
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

internal const val MAX_PLUGIN_HTTP_RESPONSE_BYTES = 1024 * 1024
internal const val MAX_PLUGIN_PERSONA_CONTEXT_CHARS = 4_000

internal fun validatePluginHttpUrl(rawUrl: String): String {
    val normalized = rawUrl.trim()
    val parsed = runCatching { Url(normalized) }
        .getOrElse { throw IllegalArgumentException("插件网络请求 URL 无效。", it) }
    require(parsed.protocol.name == "https") { "插件网络请求只允许 HTTPS URL。" }
    require(parsed.user.isNullOrBlank() && parsed.password.isNullOrBlank()) { "插件网络请求 URL 不允许包含用户凭据。" }
    require(!isForbiddenPluginHttpHost(parsed.host)) { "插件网络请求不允许访问本机、局域网或保留地址。" }
    return parsed.toString()
}

internal fun sanitizePluginHttpHeaders(headers: Map<String, String>): Map<String, String> {
    val forbidden = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization")
    return headers.mapKeys { (key, _) -> key.trim() }.onEach { (key, value) ->
        require(key.isNotBlank() && key.lowercase() !in forbidden) { "插件网络请求包含不允许的请求头：$key" }
        require('\r' !in key && '\n' !in key && '\r' !in value && '\n' !in value) {
            "插件网络请求头不能包含换行符。"
        }
    }
}

private fun isForbiddenPluginHttpHost(rawHost: String): Boolean {
    val host = rawHost.trim().trimEnd('.').lowercase()
    if (host.isBlank()) return true
    if (
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
        host.endsWith(".internal") || host.endsWith(".home.arpa") || host == "metadata.google.internal"
    ) return true
    if ('.' !in host) return true
    // KMP commonMain 无法可靠做跨平台 DNS 解析；IPv6 字面量全部拒绝，避免本地/链路地址绕过。
    if (':' in host) return true
    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) {
        return host.all { it.isDigit() || it == '.' }
    }
    val a = octets[0]!!
    val b = octets[1]!!
    return a == 0 || a == 10 || a == 127 || a >= 224 ||
        (a == 100 && b in 64..127) ||
        (a == 169 && b == 254) ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 198 && b in 18..19)
}
