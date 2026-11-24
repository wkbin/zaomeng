package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.data.api.WorldBookGenerationType

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path
import okio.ByteString.Companion.encodeUtf8
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto

/**
 * 对话高级功能服务
 *
 * 对应 Python 的 WebRunService 中的高级对话操作：
 * 会话搜索/恢复、分支、分支元数据、关系锁定、记忆管理、
 * 续写建议、修正、深度审校、剧情导演、场景卡切换与推荐。
 *
 * 会话清单（session_manifest.json）采用无损 JSON 更新：读取后仅修改
 * 相关字段并保留其余未知字段，保证与 App 端 DTO 兼容。
 */
class DialogueAdvancedService(
    private val storage: StorageService,
    private val llm: LlmClient?,
    private val prompts: PromptLoader?,
    private val derivedScheduler: DialogueDerivedScheduler? = null,
    private val projections: SessionProjectionService? = null,
) {
    companion object {
        private const val TAG = "DialogueAdvanced"
        private val MEMORY_CATEGORIES = setOf("story", "short_term", "long_term", "relationship")
        private const val MAX_ENABLED_MEMORIES = 20
        private const val MAX_LEDGER_ENTRIES = 100
        private const val MAX_BRANCH_META_LABEL = 80
    }

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val swipeForkLock = SimpleLock()
    private val chatTransfer = SillyTavernChatTransfer(storage)
    private val longTermMemory = LongTermMemoryService(storage)
    private val tensionService = SceneTensionService()

    // ------------------------------------------------------------------
    // 只读操作
    // ------------------------------------------------------------------

    /**
     * 获取当前场景的剧情张力评估指数。
     */
    fun getSceneTension(runId: String, sessionId: String): top.wkbin.zaomeng.data.api.SceneTensionDto {
        val session = requireSession(runId, sessionId)
        val transcript = transcriptOf(session)
        val recentMessages = transcript.takeLast(10).mapNotNull {
            it["message"]?.jsonPrimitive?.contentOrNull
        }
        return tensionService.evaluateTension(recentMessages)
    }

    /**
     * 获取内置突发事件预设列表。
     */
    fun getPresetEvents(category: String? = null): List<top.wkbin.zaomeng.data.api.PlotEventPresetDto> {
        return tensionService.getPresetEvents(category)
    }

    /**
     * 搜索会话内容（transcript + 长期记忆）。
     * 对应 Python: search_dialogue_session
     */
    fun search(runId: String, sessionId: String, query: String, limit: Int): JsonArray {
        val session = requireSession(runId, sessionId)
        val needle = query.trim().lowercase()
        val results = mutableListOf<JsonObject>()
        if (needle.isBlank()) return buildJsonArray { }
        val revision = sessionHistoryRevision(session)
        var offset = 0
        val archivedBefore = transcriptStartOf(session)
        while (results.size < limit) {
            val page = storage.transcriptReader.readPage(
                runId = runId,
                sessionId = sessionId,
                offset = offset,
                limit = 200,
                order = TranscriptOrder.DESC,
                expectedRevision = revision,
            )
            page.items.forEach { entry ->
                val item = entry.value
                val speaker = item["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val message = item["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (results.size < limit && (speaker.lowercase().contains(needle) || message.lowercase().contains(needle))) {
                    results.add(
                        buildJsonObject {
                            put("speaker", speaker)
                            put("message", message)
                            put("role", item["role"]?.jsonPrimitive?.contentOrNull ?: "character")
                            item["turn_id"]?.let { put("turn_id", it) }
                            item["timestamp"]?.let { put("timestamp", it) }
                            put("archived", entry.seq < archivedBefore)
                            put("score", 1.0)
                        },
                    )
                }
            }
            if (!page.hasMore || page.nextOffset == null || page.items.isEmpty()) break
            offset = requireNotNull(page.nextOffset)
        }
        if (results.size < limit) {
            val fromMemory = memoryLedgerOf(session)
                .filter { memory ->
                    val text = memory["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    text.lowercase().contains(needle)
                }
                .take(limit - results.size)
                .map { memory ->
                    buildJsonObject {
                        put("speaker", "长期记忆")
                        put("message", memory["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put("role", "memory")
                        put("memory_id", memory["memory_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put("archived", true)
                        put("score", 0.6)
                    }
                }
            results.addAll(fromMemory)
        }
        return buildJsonArray { results.forEach(::add) }
    }

    /**
     * 恢复会话。Ktor 后端无跨进程活跃操作概念，直接返回当前会话。
     * 对应 Python: recover_dialogue_session
     */
    fun recover(runId: String, sessionId: String, force: Boolean): JsonObject {
        val session = requireSession(runId, sessionId)
        return if (!force) session else markSessionRecovered(session)
    }

    // ------------------------------------------------------------------
    // 分支
    // ------------------------------------------------------------------

    /**
     * 从指定场景索引创建分支。
     * 对应 Python: branch_dialogue_session_from_scene
     */
    fun branchFromScene(runId: String, sessionId: String, sceneIndex: Int): JsonObject {
        require(sceneIndex >= 0) { "scene_index 不能为负数" }
        val session = requireFullSession(runId, sessionId)
        val transcript = transcriptOf(session)
        val sceneStarts = transcript.mapIndexedNotNull { index, item ->
            val role = item["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (role == "scene") index else null
        }
        val cutoff = if (sceneStarts.isNotEmpty()) {
            sceneStarts.getOrNull(sceneIndex) ?: sceneStarts.last()
        } else {
            // 无场景条目时退化为按轮次截断：以每个 turn 的第一条消息作为边界。
            val turnBoundaries = buildList {
                var previousTurn: String? = null
                transcript.forEachIndexed { index, item ->
                    val turn = item["turn_id"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank) ?: return@forEachIndexed
                    if (turn != previousTurn) {
                        add(index)
                        previousTurn = turn
                    }
                }
            }
            turnBoundaries.getOrNull(sceneIndex) ?: transcript.size
        }
        // 分支来源轮次取「最后一个保留下来的轮次」；无场景回退时这样才不会多算一轮世界事实。
        val sourceTurnId = transcript.take(cutoff).lastOrNull {
            it["turn_id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        }?.get("turn_id")?.jsonPrimitive?.contentOrNull
            ?: transcript.getOrNull(cutoff)?.get("turn_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        return forkSession(
            runId = runId,
            source = session,
            sourceTurnId = sourceTurnId,
            keepFrom = cutoff,
            kind = "scene_timeline",
            sceneIndex = sceneIndex,
        )
    }

    /**
     * 从指定 turn 创建分支。
     * 对应 Python: branch_dialogue_session_from_turn
     */
    fun branchFromTurn(runId: String, sessionId: String, turnId: String): JsonObject {
        require(turnId.isNotBlank()) { "turn_id 不能为空" }
        val session = requireFullSession(runId, sessionId)
        val transcript = transcriptOf(session)
        val lastIndexOfTurn = transcript.indexOfLast { it["turn_id"]?.jsonPrimitive?.contentOrNull == turnId }
        if (lastIndexOfTurn < 0) {
            throw NoSuchElementException("Turn not found: $turnId")
        }
        return forkSession(
            runId = runId,
            source = session,
            sourceTurnId = turnId,
            keepFrom = lastIndexOfTurn + 1,
        )
    }

    fun branchFromImportedSwipe(runId: String, sessionId: String, messageIndex: String, swipeIndex: Int): JsonObject =
        swipeForkLock.withLock {
            chatTransfer.validateMessageIndex(messageIndex)
            require(swipeIndex >= 0) { "回复版本序号无效。" }
            val source = requireFullSession(runId, sessionId)
            val transcript = transcriptOf(source)
            val cutoff = transcript.indexOfFirst { (it["sillytavern_message_index"] as? JsonPrimitive)?.contentOrNull == messageIndex }
            val original = transcript.getOrNull(cutoff) ?: throw NoSuchElementException("迁移消息不存在。")
            require(original["role"]?.jsonPrimitive?.contentOrNull == "character") { "仅角色回复可以用备用版本创建分支。" }
            val selection = chatTransfer.selectSwipe(runId, sessionId, messageIndex, swipeIndex)
            val branchId = "sess-swipe-" + "$sessionId:$messageIndex:$swipeIndex".encodeUtf8().sha256().hex().take(32)
            val targetManifest = storage.getDialogueSessionManifestFile(runId, branchId)
            if (storage.isFile(targetManifest)) {
                return@withLock leanSession(storage.withCharacterAvatars(storage.loadSessionManifest(runId, branchId), runId))
            }
            val replacement = buildJsonObject {
                original.forEach { (key, value) ->
                    if (key !in setOf("evidence", "inner_thought", "world_book_matches")) put(key, value)
                }
                put("message", selection.text)
                if (selection.timestamp.isNotBlank()) put("timestamp", selection.timestamp)
            }
            val retained = transcript.take(cutoff) + replacement
            val staticFields = setOf(
                "run_id", "novel_id", "session_id", "mode", "participants", "controlled_character",
                "self_card_id", "self_profile", "title", "branch_graph",
            )
            val branchSource = JsonObject(source.filterKeys { it in staticFields } + ("transcript" to JsonArray(retained)))
            val sourceTurnId = original["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            try {
                forkSession(
                    runId = runId,
                    source = branchSource,
                    sourceTurnId = sourceTurnId,
                    keepFrom = retained.size,
                    kind = "imported_swipe",
                    swipe = ImportedSwipeFork(branchId, selection),
                )
            } catch (error: Throwable) {
                if (!storage.isFile(targetManifest)) {
                    runCatching { storage.deleteRecursively(requireNotNull(targetManifest.parent)) }
                }
                throw error
            }
            leanSession(storage.withCharacterAvatars(storage.loadSessionManifest(runId, branchId), runId))
        }

    /**
     * 更新分支元数据。
     * 对应 Python: update_dialogue_branch_metadata
     */
    fun updateBranchMeta(
        runId: String,
        sessionId: String,
        label: String?,
        isMainline: Boolean?,
        lockedEventIds: List<String>?,
    ): JsonObject {
        label?.let {
            require(it.length <= MAX_BRANCH_META_LABEL) { "label 最多 $MAX_BRANCH_META_LABEL 个字符" }
        }
        lockedEventIds?.let {
            require(it.size <= 100) { "locked_event_ids 最多 100 个" }
        }
        requireSession(runId, sessionId)
        // is_mainline=true 时清除同一 run 内其他会话的 is_mainline（只改这一个字段）
        if (isMainline == true) {
            storage.listDialogueSessionIds(runId).forEach { otherId ->
                if (otherId != sessionId) {
                    val otherMeta = runCatching {
                        storage.loadSessionManifest(runId, otherId)["branch_meta"]?.jsonObject
                    }.getOrNull() ?: return@forEach
                    if (otherMeta["is_mainline"]?.jsonPrimitive?.booleanOrNull != true) return@forEach
                    editSession(runId, otherId) { latest ->
                        val meta = latest["branch_meta"]?.jsonObject ?: JsonObject(emptyMap())
                        buildJsonObject {
                            put("branch_meta", buildJsonObject {
                                meta.forEach { (key, value) -> put(key, value) }
                                put("is_mainline", false)
                            })
                        }
                    }
                }
            }
        }
        return editSession(runId, sessionId) { latest ->
            val existing = latest["branch_meta"]?.jsonObject ?: JsonObject(emptyMap())
            buildJsonObject {
                put("branch_meta", buildJsonObject {
                    existing.forEach { (key, value) -> put(key, value) }
                    label?.let { put("label", it) }
                    isMainline?.let { put("is_mainline", it) }
                    lockedEventIds?.let { ids ->
                        put("locked_event_ids", buildJsonArray { ids.forEach { id -> add(JsonPrimitive(id)) } })
                    }
                })
            }
        }
    }

    // ------------------------------------------------------------------
    // 关系锁定 / 记忆管理
    // ------------------------------------------------------------------

    /**
     * 锁定/解锁角色关系。
     * 对应 Python: set_dialogue_relation_lock
     */
    fun setRelationLock(runId: String, sessionId: String, pairKey: String, locked: Boolean): JsonObject {
        require(pairKey.isNotBlank()) { "pair_key 不能为空" }
        requireSession(runId, sessionId)
        return editSession(runId, sessionId) { latest ->
            val existing = latest["relation_locks"]?.jsonObject ?: JsonObject(emptyMap())
            buildJsonObject {
                put("relation_locks", buildJsonObject {
                    existing.forEach { (key, value) -> if (key != pairKey) put(key, value) }
                    if (locked) put(pairKey, true)
                })
            }
        }
    }

    /**
     * 创建或更新对话记忆。
     * 对应 Python: save_dialogue_memory（POST 无 memory_id，PUT 带 memory_id）
     */
    fun saveMemory(
        runId: String,
        sessionId: String,
        memoryId: String,
        text: String,
        category: String,
        pinned: Boolean,
        enabled: Boolean,
    ): JsonObject {
        require(text.isNotBlank() && text.length <= 500) { "记忆文本需为 1-500 字" }
        require(category in MEMORY_CATEGORIES) { "无效的记忆分类: $category" }
        requireSession(runId, sessionId)
        val timestamp = nowIsoString()
        val targetId = memoryId.ifBlank { "mem-" + randomUuid().replace("-", "").take(10) }
        return editSession(runId, sessionId) { latest ->
            val ledger = memoryLedgerOf(latest)
            val existingIndex = ledger.indexOfFirst {
                it["memory_id"]?.jsonPrimitive?.contentOrNull == targetId
            }
            // 对齐 Python：显式指定 memory_id 表示更新，目标不存在时不能静默新建。
            if (memoryId.isNotBlank() && existingIndex < 0) {
                throw IllegalArgumentException("指定的记忆不存在。")
            }
            val updatedLedger = ledger.toMutableList().apply {
                if (existingIndex >= 0) {
                    val old = this[existingIndex]
                    this[existingIndex] = buildJsonObject {
                        old.forEach { (key, value) -> put(key, value) }
                        put("text", text)
                        put("category", category)
                        put("pinned", pinned)
                        put("enabled", enabled)
                        put("updated_at", timestamp)
                    }
                } else {
                    add(
                        buildJsonObject {
                            put("memory_id", targetId)
                            put("text", text)
                            put("category", category)
                            put("pinned", pinned)
                            put("enabled", enabled)
                            put("created_at", timestamp)
                            put("updated_at", timestamp)
                        },
                    )
                }
            }
            // enabled 上限与 Python 一致：超限直接拒绝，不能静默停用用户的旧记忆。
            val enabledCount = updatedLedger.count {
                it["enabled"]?.jsonPrimitive?.booleanOrNull != false
            }
            require(enabledCount <= MAX_ENABLED_MEMORIES) {
                "同时启用的可控记忆最多为 $MAX_ENABLED_MEMORIES 条，请先停用一条。"
            }
            // ledger 截断到最近 100 条
            val trimmed = updatedLedger.takeLast(MAX_LEDGER_ENTRIES)
            buildJsonObject {
                put("memory_ledger", buildJsonArray { trimmed.forEach(::add) })
            }
        }
    }

    /**
     * 删除对话记忆。
     * 对应 Python: delete_dialogue_memory
     */
    fun deleteMemory(runId: String, sessionId: String, memoryId: String): JsonObject {
        require(memoryId.isNotBlank()) { "memory_id 不能为空" }
        requireSession(runId, sessionId)
        return editSession(runId, sessionId) { latest ->
            val ledger = memoryLedgerOf(latest)
            // 对齐 Python：删除不存在的记忆是调用方错误，不能静默成功。
            if (ledger.none { it["memory_id"]?.jsonPrimitive?.contentOrNull == memoryId }) {
                throw IllegalArgumentException("指定的记忆不存在。")
            }
            val filtered = ledger.filterNot {
                it["memory_id"]?.jsonPrimitive?.contentOrNull == memoryId
            }
            buildJsonObject {
                put("memory_ledger", buildJsonArray { filtered.forEach(::add) })
            }
        }
    }

    fun memoryQuality(runId: String, sessionId: String): MemoryQualityReportDto {
        requireSession(runId, sessionId)
        return longTermMemory.qualityReport(runId, sessionId)
    }

    fun updateAutomaticMemoryStatus(
        runId: String,
        sessionId: String,
        memoryId: String,
        status: String,
    ): MemoryQualityReportDto {
        requireSession(runId, sessionId)
        return longTermMemory.updateStatus(runId, sessionId, memoryId, status)
    }

    fun mergeDuplicateMemories(runId: String, sessionId: String): MemoryQualityReportDto {
        requireSession(runId, sessionId)
        return longTermMemory.mergeDuplicates(runId, sessionId)
    }

    // ------------------------------------------------------------------
    // LLM 功能
    // ------------------------------------------------------------------

    /**
     * 生成续写建议。
     * 对应 Python: suggest_dialogue_turn
     */
    suspend fun suggestDialogue(
        runId: String,
        sessionId: String,
        seedText: String,
        direction: String,
        speakerOverride: String = "",
    ): JsonObject {
        val session = requireSession(runId, sessionId)
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置" }
        val runManifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val payload = DialoguePayloadBuilder(storage).buildSuggestionPayload(
            runManifest = runManifest,
            session = session,
            seedText = seedText,
            direction = direction,
            speakerOverride = speakerOverride,
        )
        val messages = DialoguePromptBuilder(loader).buildDialogueSuggestionLlmMessages(
            payload = payload,
            retryOnEmpty = false,
        )
        val content = client.chatCompletion(
            messages = messages,
            temperature = 0.8,
            maxTokens = 512,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (content.isBlank()) throw IllegalStateException("续写建议为空")
        val suggestion = parseSuggestion(content)
        return buildJsonObject { put("suggestion", suggestion) }
    }

    /**
     * 解析 LLM 续写建议输出：
     * 优先提取 JSON 中的 suggestion / options[0].suggestion，
     * 否则回退为去除引号后的原始文本。
     */
    private fun parseSuggestion(content: String): String {
        val fenceStripped = stripCodeFences(content)
        val fromJson = runCatching {
            val element = json.parseToJsonElement(fenceStripped)
            if (element is JsonObject) {
                val direct = element["suggestion"]?.jsonPrimitive?.contentOrNull
                if (direct != null) {
                    direct
                } else {
                    element["options"]?.jsonArray?.firstOrNull()?.jsonObject?.get("suggestion")?.jsonPrimitive?.contentOrNull
                }
            } else {
                null
            }
        }.getOrNull()
        if (fromJson != null) return fromJson.trim()
        return fenceStripped
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
            .trim()
            .takeIf(String::isNotBlank)
            ?: throw IllegalStateException("续写建议为空")
    }

    private fun stripCodeFences(content: String): String =
        content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

    /**
     * 修正最新回复：为最后一个角色回合生成修正版，并**新建修正分支**。
     *
     * 对应 Python: correct_latest_dialogue_turn（create_correction_branch + prepare_turn + 生成）：
     * 源会话保持不变；分支在目标回合之前截断，随后以同一个用户输入重新生成回复，
     * 并写出 `branch_origin={session_id, turn_id, kind:"consistency_correction"}`。
     */
    suspend fun correctLatest(runId: String, sessionId: String): JsonObject {
        val session = requireFullSession(runId, sessionId)
        val runManifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val transcript = transcriptOf(session)
        val lastRoleIndex = transcript.indexOfLast {
            val role = it["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            role != "user" && role != "scene" && role != "memory"
        }
        if (lastRoleIndex < 0) throw IllegalArgumentException("当前会话还没有可修正的回复")
        val target = transcript[lastRoleIndex]
        val targetTurnId = target["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val participants = (session["participants"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonPrimitive.contentOrNull }.filter(String::isNotBlank)
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置" }

        // 原始用户输入（对齐 Python：取 target turn 的 user 条目）
        val userEntry = if (targetTurnId.isNotEmpty()) {
            transcript.lastOrNull { it["turn_id"]?.jsonPrimitive?.contentOrNull == targetTurnId && it["role"]?.jsonPrimitive?.contentOrNull == "user" }
        } else {
            null
        }
        val originalMessage = userEntry?.get("message")?.jsonPrimitive?.contentOrNull?.trim()
            ?: session["last_entry_preview"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (originalMessage.isBlank()) {
            throw IllegalArgumentException("最新回合缺少可修正的用户输入")
        }

        // 分支截断点：目标回合的第一条记录（通常是用户输入）之前。
        val targetTurnStart = if (targetTurnId.isNotEmpty()) {
            transcript.indexOfFirst { it["turn_id"]?.jsonPrimitive?.contentOrNull == targetTurnId }
        } else {
            -1
        }
        val cutoff = if (targetTurnStart in 0 until lastRoleIndex) targetTurnStart else lastRoleIndex
        val baseTranscript = transcript.take(cutoff)

        // 生成上下文只包含目标回合之前的内容，避免模型看到待修正的旧回复。
        val baseSession = mergeJsonFields(session, buildJsonObject {
            put("transcript", buildJsonArray { baseTranscript.forEach(::add) })
            put("transcript_start", 0)
            put("transcript_count", baseTranscript.size)
            put(WorldBookTiming.FIELD, WorldBookTiming.fromTranscript(baseTranscript))
            put("state", JsonObject(emptyMap()))
        })
        val baseState = SceneProgressState.deriveSceneProgressState(
            session = baseSession,
            transcript = baseTranscript.mapNotNull { entry ->
                runCatching {
                    entry.mapValues { (_, value) ->
                        if (value is JsonObject) value else value.jsonPrimitive.contentOrNull
                    }
                }.getOrNull()
            },
            updatedAt = nowIsoString(),
        )
        val generationSession = mergeJsonFields(baseSession, buildJsonObject {
            put("state", SceneProgressState.stateToJsonObject(baseState))
        })

        // 一致性审校问题（对齐 Python create_correction_branch：从 consistency_monitor.latest.issues 取）
        val monitor = session["consistency_monitor"]?.jsonObject ?: JsonObject(emptyMap())
        val latestReview = monitor["latest"]?.jsonObject ?: JsonObject(emptyMap())
        val issues = latestReview["issues"]?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()
        val correctionContext = buildJsonObject {
            put("source_session_id", sessionId)
            if (targetTurnId.isNotEmpty()) put("source_turn_id", targetTurnId)
            put("message", originalMessage)
            put("message_kind", "dialogue")
            if (targetTurnId.isNotEmpty()) put("turn_id", targetTurnId)
            put(
                "issues",
                buildJsonArray {
                    issues.forEach { add(it) }
                },
            )
            put(
                "original_responses",
                buildJsonArray {
                    transcript.filter {
                        it["turn_id"]?.jsonPrimitive?.contentOrNull == targetTurnId &&
                            it["role"]?.jsonPrimitive?.contentOrNull != "user"
                    }.forEach { add(it) }
                },
            )
        }

        // 走完整 reply 管道（对齐 Python _generate_dialogue_responses），注入 CORRECTION_CONTEXT
        val payloadBuilder = DialoguePayloadBuilder(storage)
        val promptBuilder = DialoguePromptBuilder(loader)
        val turnId = "correct-" + randomUuid().replace("-", "").take(10)
        val payload = payloadBuilder.buildTurnPayload(
            runManifest = runManifest,
            session = generationSession,
            turnId = turnId,
            message = originalMessage,
            generationType = WorldBookGenerationType.Regenerate,
        ).toMutableMap()
        payload["correction_context"] = correctionContext.mapValues { (_, value) -> jsonValueToAny(value) }
        val messages = promptBuilder.buildDialogueLlmMessages(payload = payload, retryOnEmpty = true)
        val responseLimit = ((payload["host_action"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.get("response_limit_hint") as? Number)?.toInt() ?: 2
        val maxTokens = DialogueService.resolveDialogueMaxTokens(
            responseLimitHint = responseLimit,
            reasoningEffort = storage.loadModelSettings()["reasoning_effort"] as? String ?: "auto",
        )
        val corrected = client.chatCompletion(
            messages = messages,
            temperature = 0.7,
            maxTokens = maxTokens,
            // 修正走与常规回复相同的多角色 NDJSON 协议：不能启用“单个 JSON 对象”的 provider 约束，
            // 否则模型被迫输出一个对象，多角色回合会被压成一条或直接解析失败。
            requireJsonObject = false,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (corrected.isBlank()) throw IllegalStateException("修正结果为空")
        val forbidden = listOf(
            session["controlled_character"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            "User",
            "我",
        ).filter(String::isNotBlank)
        val correctedResponses = DialogueResponseParser.parse(
            content = corrected,
            allowedSpeakers = participants + listOf("旁白", "场景提示"),
            forbiddenSpeakers = forbidden,
        )
        if (correctedResponses.isEmpty()) throw IllegalStateException("修正结果为空")

        // 生成成功后再落盘分支：模型失败时不会留下空分支。原会话完全不受影响。
        val branch = forkSession(
            runId = runId,
            source = session,
            sourceTurnId = targetTurnId,
            keepFrom = cutoff,
            kind = "consistency_correction",
        )
        val branchId = branch["session_id"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("修正分支创建失败")
        val timestamp = nowIsoString()
        val correctedText = correctedResponses.lastOrNull()?.message.orEmpty()
        var pendingCorrection: ReplyOperationResult? = null
        storage.mutateSessionHistory(runId, branchId) { history ->
            val latest = history.manifest
            val updatedTranscript = transcriptOf(latest).toMutableList()
            // 重新写入触发修正的用户输入，保证分支从同一输入继续。
            updatedTranscript.add(buildJsonObject {
                userEntry?.forEach { (key, value) -> put(key, value) }
                if (userEntry == null) {
                    put("speaker", "User")
                    put("role", "user")
                }
                put("message", originalMessage)
                put("turn_id", turnId)
                put("timestamp", timestamp)
            })
            val timingDelta = payload[WorldBookTiming.DELTA] as? JsonObject
            correctedResponses.forEachIndexed { index, response ->
                updatedTranscript.add(buildJsonObject {
                    put("speaker", response.speaker)
                    put("message", response.message)
                    response.innerThought?.let { put("inner_thought", it) }
                    put(
                        "role",
                        if (response.speaker in setOf("旁白", "场景提示")) "scene" else "character",
                    )
                    put("turn_id", turnId)
                    put("timestamp", timestamp)
                    put("corrected_at", timestamp)
                    if (index == 0 && timingDelta != null) put(WorldBookTiming.DELTA, timingDelta)
                })
            }
            val lastPreview = updatedTranscript.lastOrNull { entry ->
                entry["role"]?.jsonPrimitive?.contentOrNull.orEmpty() != "user"
            }?.get("message")?.jsonPrimitive?.contentOrNull ?: correctedText
            // 分支尚未审校：清掉从源会话继承的 latest，避免把旧问题算到新分支上。
            val branchMonitor = latest["consistency_monitor"]?.jsonObject
            val monitorFields = branchMonitor?.let { current ->
                buildJsonObject {
                    current.forEach { (key, value) -> put(key, value) }
                    put("latest", JsonObject(emptyMap()))
                    put("issue_count", 0)
                }
            } ?: buildJsonObject {
                put("latest", JsonObject(emptyMap()))
                put("issue_count", 0)
            }
            val merged = mergeJsonFields(latest, buildJsonObject {
                put("transcript", buildJsonArray { updatedTranscript.forEach(::add) })
                put("transcript_count", transcriptStartOf(latest) + updatedTranscript.size)
                put("turn_count", updatedTranscript.mapNotNull {
                    it["turn_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                }.distinct().size)
                put("current_turn_id", turnId)
                put("last_entry_preview", lastPreview)
                put("updated_at", timestamp)
                put("consistency_monitor", monitorFields)
                if (timingDelta != null) put(WorldBookTiming.FIELD, WorldBookTiming.apply(latest, timingDelta))
            })
            // 修正分支重推 scene progress，避免继承源会话后续轮次的场景事实。
            val transcriptMaps = updatedTranscript.mapNotNull { entry ->
                runCatching {
                    entry.mapValues { (_, value) ->
                        if (value is JsonObject) value else value.jsonPrimitive.contentOrNull
                    }
                }.getOrNull()
            }
            val state = SceneProgressState.deriveSceneProgressState(merged, transcriptMaps, updatedAt = timestamp)
            val updated = mergeJsonFields(merged, buildJsonObject {
                put("state", SceneProgressState.stateToJsonObject(state))
            })
            val result = ReplyOperationResult(
                turnId = turnId,
                committedRevision = history.revision + 1,
                transcriptCountAtCommit = transcriptStartOf(latest) + updatedTranscript.size,
                appendedTranscript = updatedTranscript.mapIndexedNotNull { index, item ->
                    if (item["turn_id"]?.jsonPrimitive?.contentOrNull != turnId) {
                        null
                    } else {
                        buildJsonObject {
                            item.forEach { (key, value) -> put(key, value) }
                            put("seq", transcriptStartOf(latest) + index)
                        }
                    }
                },
                message = originalMessage,
                messageKind = "dialogue",
                responses = correctedResponses,
            )
            derivedScheduler?.prepareDerivedResult(runId, branchId, result, DerivedOperationKind.CORRECTION)
            pendingCorrection = result
            SessionHistoryMutation(updated, Unit)
        }
        val finalBranch = storage.getDialogueSession(runId, branchId)
        val scheduler = derivedScheduler
        if (scheduler != null) {
            scheduler.scheduleDerivedResult(
                runId,
                branchId,
                requireNotNull(pendingCorrection),
                DerivedOperationKind.CORRECTION,
            )
            projections?.rebuild(runId, branchId)
        } else {
            longTermMemory.replaceTurn(
                runId = runId,
                sessionId = branchId,
                turnId = turnId,
                message = originalMessage,
                responses = correctedResponses.map { response ->
                    mapOf("speaker" to response.speaker, "message" to response.message)
                },
            )
        }
        return finalBranch
    }

    /**
     * 深度审校最新回复：调用 LLM 生成一致性审校结果并写入 consistency_monitor。
     * 对应 Python: deep_review_latest_dialogue_turn
     */
    suspend fun deepReview(runId: String, sessionId: String): JsonObject {
        val session = requireSession(runId, sessionId)
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置" }
        val runManifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val transcript = transcriptOf(session)
        // 只复核最新角色回合，不能把最近若干条来自不同回合的回复混在一起。
        val targetIndex = transcript.indexOfLast {
            val role = it["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            role != "user" && role != "scene" && role != "memory"
        }
        if (targetIndex < 0) throw IllegalArgumentException("当前会话还没有可复核的回复")
        val expectedTurnId = transcript[targetIndex]["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val responses = transcript.filter { entry ->
            val role = entry["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            role != "user" && role != "scene" && role != "memory" &&
                (expectedTurnId.isBlank() ||
                    entry["turn_id"]?.jsonPrimitive?.contentOrNull == expectedTurnId)
        }.map { entry ->
            mapOf<String, Any?>(
                "speaker" to (entry["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                "message" to (entry["message"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            )
        }
        val payload = DialoguePayloadBuilder(storage).buildConsistencyReviewPayload(
            runManifest = runManifest,
            session = session,
            responses = responses,
        )
        val messages = DialoguePromptBuilder(loader).buildDialogueConsistencyReviewMessages(payload)
        val content = client.chatCompletion(
            messages = messages,
            temperature = 0.3,
            maxTokens = 1200,
            requireJsonObject = true,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        val review = parseJsonObject(content)
        // 解析失败时不能把空对象写成 latest 并挤掉上一份有效报告。
        if (review.isEmpty()) throw IllegalStateException("深度复核结果为空")
        val timestamp = nowIsoString()
        return editSession(runId, sessionId) { latest ->
            // LLM 调用期间可能已提交新回合：旧回合的复核结论不能覆盖最新状态。
            val latestTurnId = transcriptOf(latest).lastOrNull { entry ->
                val role = entry["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
                role != "scene" && role != "memory"
            }?.get("turn_id")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (expectedTurnId.isNotBlank() && latestTurnId.isNotBlank() && latestTurnId != expectedTurnId) {
                throw IllegalArgumentException("会话已进入新一轮，本次深度复核结果已过期，请重新复核最新一轮。")
            }
            val previous = latest["consistency_monitor"]?.jsonObject ?: JsonObject(emptyMap())
            val previousLatest = previous["latest"]?.jsonObject ?: JsonObject(emptyMap())
            val mergedReview = mergeSemanticReview(
                previous = previousLatest,
                review = review,
                turnId = expectedTurnId,
                reviewedAt = timestamp,
            )
            val newIssueCount = mergedReview["issues"]?.jsonArray?.size ?: 0
            val oldIssueCount = previousLatest["issues"]?.jsonArray?.size ?: 0
            val history = (previous["history"]?.jsonArray ?: JsonArray(emptyList()))
                .mapNotNull { runCatching { it.jsonObject }.getOrNull() }.toMutableList()
            val previousTurnId = previousLatest["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            // 上一份报告若属于更早的回合则保留为历史；同回合重复复核只保留合并后的最新报告。
            if (previousLatest.isNotEmpty() && previousTurnId.ifBlank { expectedTurnId } != expectedTurnId) {
                history.add(0, previousLatest)
            }
            if (expectedTurnId.isNotBlank()) {
                history.removeAll {
                    it["turn_id"]?.jsonPrimitive?.contentOrNull == expectedTurnId
                }
            }
            history.add(0, mergedReview)
            buildJsonObject {
                put("consistency_monitor", buildJsonObject {
                    previous.forEach { (key, value) -> put(key, value) }
                    put("latest", mergedReview)
                    put("history", buildJsonArray { history.take(20).forEach(::add) })
                    put("issue_count", maxOf(
                        0,
                        (previous["issue_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: oldIssueCount) +
                            newIssueCount - oldIssueCount,
                    ))
                    put("semantic_review_count", (previous["semantic_review_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) + 1)
                    put("reviewed_at", timestamp)
                })
            }
        }
    }

    /**
     * 把语义复核结果合并进已有的确定性报告（对齐 Python merge_semantic_review）：
     * 去重同类问题、重算状态与分数，并标记语义复核覆盖。
     */
    private fun mergeSemanticReview(
        previous: JsonObject,
        review: JsonObject,
        turnId: String,
        reviewedAt: String,
    ): JsonObject {
        fun JsonObject.text(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

        val baseIssues = (previous["issues"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .filter { it.text("source") != "semantic_review" }
        val seen = baseIssues.map {
            Triple(it.text("code"), it.text("speaker"), it.text("evidence"))
        }.toMutableSet()
        val semanticIssues = (review["issues"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .mapNotNull { issue ->
                val code = issue.text("code")
                if (code.isBlank()) return@mapNotNull null
                val key = Triple(code, issue.text("speaker"), issue.text("evidence"))
                if (!seen.add(key)) return@mapNotNull null
                buildJsonObject {
                    issue.forEach { (field, value) -> put(field, value) }
                    put("source", "semantic_review")
                }
            }
        val issues = baseIssues + semanticIssues
        val errorCount = issues.count { it.text("severity") == "error" }
        val warningCount = issues.size - errorCount
        val summary = review.text("summary").ifBlank {
            if (issues.isEmpty()) {
                "深度复核未发现明确的语义一致性问题。"
            } else {
                "深度复核后共发现 ${issues.size} 个潜在一致性问题。"
            }
        }
        val coverage = previous["coverage"]?.jsonObject ?: JsonObject(emptyMap())
        return buildJsonObject {
            previous.forEach { (key, value) -> put(key, value) }
            put("issues", buildJsonArray { issues.forEach(::add) })
            put("status", if (issues.isEmpty()) "pass" else "warning")
            put("score", maxOf(0, 100 - errorCount * 25 - warningCount * 10))
            put("summary", summary)
            if (turnId.isNotBlank()) put("turn_id", turnId)
            put("coverage", buildJsonObject {
                coverage.forEach { (key, value) -> put(key, value) }
                put("semantic_review", true)
                put("semantic_reviewed_at", reviewedAt)
            })
            put("reviewed_at", reviewedAt)
        }
    }

    /**
     * 剧情导演：生成若干推进方案。
     * 对应 Python: direct_dialogue_turn
     */
    suspend fun directDialogue(
        runId: String,
        sessionId: String,
        goal: String,
        action: String,
        optionCount: Int,
    ): JsonObject {
        require(goal.isNotBlank() && goal.length <= 240) { "goal 需为 1-240 字" }
        val normalizedAction = action.trim().lowercase().ifBlank { "advance" }
        val session = requireSession(runId, sessionId)
        val count = optionCount.coerceIn(2, 4)
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置" }
        val runManifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val payload = DialoguePayloadBuilder(storage).buildDirectorPayload(
            runManifest = runManifest,
            session = session,
            goal = goal,
            action = normalizedAction,
            optionCount = count,
        )
        val messages = DialoguePromptBuilder(loader).buildDialogueDirectorLlmMessages(
            payload = payload,
            retryOnEmpty = false,
        )
        val content = client.chatCompletion(
            messages = messages,
            temperature = 0.9,
            maxTokens = 1600,
            requireJsonObject = true,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        val parsed = parseJsonObject(content)
        val options = parsed["options"]?.jsonArray ?: return buildJsonObject { put("options", JsonArray(emptyList())) }
        return buildJsonObject {
            put("message_kind", if (normalizedAction == "fourth_wall") "fourth_wall" else "plot")
            put("options", buildJsonArray { options.take(count).forEach(::add) })
        }
    }

    // ------------------------------------------------------------------
    // 场景卡
    // ------------------------------------------------------------------

    /**
     * 切换场景卡。
     * 对应 Python: switch_dialogue_scene_card
     */
    fun switchScene(
        runId: String,
        sessionId: String,
        sceneCardId: String,
        sceneProfile: JsonObject,
        transitionMessage: String,
        autoContinue: Boolean,
    ): JsonObject {
        requireSession(runId, sessionId)
        // 客户端只传 card_id 时，必须按 id 读取场景卡文件；否则 fields 恒为空、场景状态推导失真。
        val resolvedProfile = if (sceneCardId.isNotBlank()) {
            val safeCardId = PathSafety.validateStorageId(sceneCardId.trim(), "card_id")
            val cardFile = storage.getStorageRoot() / "scene-cards" / safeCardId / "scene-card.json"
            if (!storage.exists(cardFile)) throw IllegalArgumentException("所选场景卡不存在。")
            val card = runCatching {
                json.parseToJsonElement(storage.readText(cardFile)).jsonObject
            }.getOrElse { throw IllegalArgumentException("所选场景卡不存在。") }
            val fields = card["fields"]?.jsonObject ?: JsonObject(emptyMap())
            // 显式传入的 scene_profile 优先级高于卡文件，并回写 card_id（对齐 Python）。
            buildJsonObject {
                fields.forEach { (key, value) -> put(key, value) }
                sceneProfile.forEach { (key, value) -> put(key, value) }
                put("scene_card_id", card["card_id"]?.jsonPrimitive?.contentOrNull ?: safeCardId)
            }
        } else {
            sceneProfile
        }
        val timestamp = nowIsoString()
        val sceneCard = buildJsonObject {
            if (sceneCardId.isNotBlank()) put("card_id", sceneCardId)
            put("fields", resolvedProfile)
            put("preview", buildJsonObject {
                resolvedProfile["title"]?.let { put("title", it) }
                resolvedProfile["location"]?.let { put("location", it) }
                resolvedProfile["time_hint"]?.let { put("time_hint", it) }
            })
        }
        val transitionEntry = if (transitionMessage.isNotBlank()) {
            buildJsonObject {
                put("scene_card_id", sceneCardId)
                put("transition_message", transitionMessage)
                put("switched_at", timestamp)
            }
        } else {
            null
        }
        val sceneProfileMap = resolvedProfile.mapValues { (_, value) ->
            value.jsonPrimitive.contentOrNull
        }
        // 场景切换的三次写入合并为一次锁内读改写：避免覆盖期间提交的新回合，也少两次全量写。
        val transform: (JsonObject) -> JsonObject = { latest ->
            val sceneHistory = (latest["scene_history"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
            transitionEntry?.let { sceneHistory.add(it) }
            var merged = mergeJsonFields(latest, buildJsonObject {
                put("scene_card_id", sceneCardId)
                put("scene_card", sceneCard)
                put("scene_history", buildJsonArray { sceneHistory.forEach(::add) })
                put("updated_at", timestamp)
            })
            if (autoContinue && transitionMessage.isNotBlank()) {
                // auto_continue：将转场消息作为场景提示写入 transcript
                val transcript = transcriptOf(merged).toMutableList()
                transcript.add(
                    buildJsonObject {
                        put("speaker", "场景提示")
                        put("message", transitionMessage)
                        put("role", "scene")
                        put("turn_id", "scene-" + randomUuid().replace("-", "").take(10))
                        put("timestamp", timestamp)
                    },
                )
                merged = mergeJsonFields(merged, buildJsonObject {
                    put("transcript", buildJsonArray { transcript.forEach(::add) })
                    put("transcript_count", transcriptStartOf(merged) + transcript.size)
                })
            }
            // 对齐 Python switch_scene_card：写入场景切换事件信号并推导 scene progress state
            val state = SceneProgressState.deriveAfterSceneSwitch(
                session = merged,
                sceneProfile = sceneProfileMap,
                transitionMessage = transitionMessage,
                switchedAt = timestamp,
            )
            mergeJsonFields(merged, buildJsonObject {
                put("state", SceneProgressState.stateToJsonObject(state))
            })
        }
        if (autoContinue && transitionMessage.isNotBlank()) {
            storage.mutateSessionHistory(runId, sessionId) { history ->
                SessionHistoryMutation(transform(history.manifest), Unit)
            }
        } else {
            storage.updateSessionManifest(runId, sessionId, transform)
        }
        if (autoContinue && transitionMessage.isNotBlank()) projections?.rebuild(runId, sessionId)
        return storage.loadSessionManifest(runId, sessionId)
    }

    /**
     * 推荐场景卡（规则引擎，不调 LLM）。
     * 对应 Python: recommend_dialogue_scene_card
     */
    fun recommendScene(runId: String, sessionId: String): JsonObject {
        val session = requireSession(runId, sessionId)
        val cardsDir = storage.getStorageRoot() / "scene-cards"
        val candidates = mutableListOf<Pair<String, JsonObject>>()
        if (storage.exists(cardsDir)) {
            storage.listFiles(cardsDir).filter { storage.isDirectory(it) }.forEach { dir ->
                val cardFile = dir / "scene-card.json"
                if (storage.exists(cardFile)) {
                    runCatching {
                        val fields = json.parseToJsonElement(storage.readText(cardFile)).jsonObject
                        candidates.add(dir.name to fields)
                    }
                }
            }
        }
        val participants = session["participants"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        }.orEmpty()
        val mode = session["mode"]?.jsonPrimitive?.contentOrNull ?: "observe"
        val currentId = session["scene_card_id"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val scored = candidates.mapNotNull { (id, fields) ->
            if (id == currentId) return@mapNotNull null
            var score = 0
            val text = buildString {
                fields.forEach { (_, value) -> value.jsonPrimitive.contentOrNull?.let { append(it) } }
            }
            if (fields.containsKey("scene_drive")) score += 3
            if (fields.containsKey("opening_situation")) score += 2
            if (fields.containsKey("atmosphere")) score += 1
            if (participants.size >= 3 && text.any { it in "厅堂席宴城" }) score += 2
            if (participants.size == 2 && text.contains("对坐")) score += 1
            if (mode == "insert" && text.contains("插入")) score += 1
            if (mode == "observe" && text.any { it in "试探摊牌转折" }) score += 1
            val preview = buildJsonObject {
                fields["title"]?.let { put("title", it) }
                fields["location"]?.let { put("location", it) }
                fields["time_hint"]?.let { put("time_hint", it) }
            }
            Triple(id, score, preview)
        }.sortedByDescending { it.second }

        val recommended = scored.firstOrNull()
        return buildJsonObject {
            put("recommended_card_id", recommended?.first.orEmpty())
            put("items", buildJsonArray {
                scored.forEach { (id, _, preview) ->
                    add(
                        buildJsonObject {
                            put("card_id", id)
                            put("fields", preview)
                        },
                    )
                }
            })
            put("mode", mode)
            put("participants", buildJsonArray { participants.forEach { add(JsonPrimitive(it)) } })
            put("recommended_auto_continue_message", "")
        }
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private fun requireSession(runId: String, sessionId: String): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        return runCatching { storage.loadSessionManifest(runId, sessionId) }.getOrNull()
            ?: throw NoSuchElementException("Session not found: $sessionId")
    }

    private fun requireFullSession(runId: String, sessionId: String): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        return runCatching { storage.getDialogueSession(runId, sessionId) }.getOrNull()
            ?: throw NoSuchElementException("Session not found: $sessionId")
    }

    /**
     * 只把本次编辑涉及的字段合并进**最新的**会话清单。
     *
     * 旧实现用调用方早先读到的整份快照覆盖写：`correctLatest`/`deepReview`/`switchScene`
     * 要跨 LLM 调用，期间提交的新回合会被旧快照整片抹掉。这里在存储路径锁内基于 latest
     * 计算，只覆盖 [fields] 返回的键，其余字段（transcript / turn_count / current_turn_id 等）
     * 保持最新值。
     */
    private fun editSession(
        runId: String,
        sessionId: String,
        fields: (latest: JsonObject) -> JsonObject,
    ): JsonObject {
        return storage.updateSessionManifest(runId, sessionId) { latest ->
            val merged = mergeJsonFields(latest, fields(latest))
            mergeJsonFields(merged, buildJsonObject { put("updated_at", nowIsoString()) })
        }
    }

    private fun mergeJsonFields(base: JsonObject, fields: JsonObject): JsonObject = buildJsonObject {
        base.forEach { (key, value) -> put(key, value) }
        fields.forEach { (key, value) -> put(key, value) }
    }

    private fun transcriptOf(session: JsonObject): List<JsonObject> =
        session["transcript"]?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()

    /** manifest 中最近 transcript 之前已归档的条数，用于重算 transcript_count。 */
    private fun transcriptStartOf(manifest: JsonObject): Int =
        manifest["transcript_start"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun jsonValueToAny(value: kotlinx.serialization.json.JsonElement?): Any? = when (value) {
        null -> null
        is JsonObject -> value.mapValues { jsonValueToAny(it.value) }
        is JsonArray -> value.map { jsonValueToAny(it) }
        is kotlinx.serialization.json.JsonPrimitive ->
            if (value.isString) value.content else value.contentOrNull ?: value.toString()
    }

    private fun memoryLedgerOf(session: JsonObject): List<JsonObject> =
        session["memory_ledger"]?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()

    /**
     * 创建分支会话：复制源会话并截断 transcript。
     */
    private data class ImportedSwipeFork(val id: String, val selection: SillyTavernChatTransfer.SwipeSelection)

    private fun forkSession(
        runId: String,
        source: JsonObject,
        sourceTurnId: String,
        keepFrom: Int,
        kind: String = "event_timeline",
        sceneIndex: Int? = null,
        swipe: ImportedSwipeFork? = null,
    ): JsonObject {
        val transcript = transcriptOf(source)
        val kept = transcript.take(keepFrom)
        val retainedTurnIds = kept.mapNotNull {
            it["turn_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        }.toSet()
        // 一轮会追加多条角色消息，turn_count 必须按 turn_id 去重，不能按消息条数统计。
        val turnCount = retainedTurnIds.size.takeIf { it > 0 } ?: kept.count { item ->
            val message = item["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val role = item["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            message.isNotBlank() && role !in setOf("scene", "director", "loading")
        }
        val branchId = swipe?.id ?: "sess-" + randomUuid().replace("-", "").take(12)
        val timestamp = nowIsoString()
        val swipeLabel = swipe?.selection?.let { "回复版本 ${it.index + 1}" }
        val branchOrigin = buildJsonObject {
            put("source_session_id", source["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
            if (sourceTurnId.isNotBlank()) put("source_turn_id", sourceTurnId)
            put("created_at", timestamp)
            put("fork_type", if (swipe == null) "branch" else "imported_swipe")
            // 同时写出 Python create_session(branch_origin=...) 的字段名：
            // 客户端断线恢复（session_id/kind/turn_id/scene_index）和旧存档都依赖这套结构。
            put("session_id", source["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("kind", kind)
            if (sourceTurnId.isNotBlank()) put("turn_id", sourceTurnId)
            sceneIndex?.let { put("scene_index", it) }
            swipe?.selection?.let {
                put("sillytavern_message_index", it.messageIndex)
                put("sillytavern_swipe_index", it.index)
            }
        }
        val parentGraph = source["branch_graph"]?.jsonObject ?: JsonObject(emptyMap())
        val graph = buildJsonObject {
            parentGraph.forEach { (key, value) -> put(key, value) }
            put(
                branchId,
                buildJsonObject {
                    put("parent_id", source["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("source_turn_id", sourceTurnId)
                    put("created_at", timestamp)
                },
            )
        }
        val branch = buildJsonObject {
            source.forEach { (key, value) -> put(key, value) }
            put("session_id", branchId)
            val title = source["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "分支会话" }
            put("title", swipeLabel?.let { "${title.take(96)} · $it" } ?: title)
            swipe?.selection?.let { put("last_entry_preview", it.text.take(200)) }
            put("created_at", timestamp)
            put("updated_at", timestamp)
            put("transcript", buildJsonArray { kept.forEach(::add) })
            put("transcript_start", 0)
            put("transcript_count", kept.size)
            put(WorldBookTiming.FIELD, WorldBookTiming.fromTranscript(kept))
            put("revision", if (kept.isEmpty()) 0 else 1)
            put("branch_origin", branchOrigin)
            put("branch_graph", graph)
            put("branch_meta", buildJsonObject {
                put("label", swipeLabel ?: "分支")
                put("is_mainline", false)
            })
            put("status", "ready")
            put("turn_count", turnCount)
            put("current_turn_id", kept.lastOrNull()?.get("turn_id")?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        val branchForState = buildJsonObject {
            branch.forEach { (key, value) -> put(key, value) }
            // Re-derive state from the retained transcript; the parent's final state may contain future facts.
            put("state", JsonObject(emptyMap()))
        }
        val retainedTranscript = kept.mapNotNull { item ->
            runCatching {
                item.jsonObject.mapValues { (_, value) ->
                    when (value) {
                        is JsonObject -> value
                        else -> value.jsonPrimitive.contentOrNull
                    }
                }
            }.getOrNull()
        }
        val branchState = SceneProgressState.deriveSceneProgressState(
            session = branchForState,
            transcript = retainedTranscript,
            updatedAt = timestamp,
        )
        val branchWithState = buildJsonObject {
            branch.forEach { (key, value) -> put(key, value) }
            put("state", SceneProgressState.stateToJsonObject(branchState))
            branch["consistency_monitor"]?.jsonObject?.let { monitor ->
                put("consistency_monitor", buildJsonObject {
                    monitor.forEach { (key, value) -> put(key, value) }
                    val ledger = monitor["knowledge_ledger"]?.jsonArray.orEmpty()
                    put("knowledge_ledger", buildJsonArray {
                        ledger.forEach { item ->
                            val entry = runCatching { item.jsonObject }.getOrNull()
                            val entryTurnId = entry?.get("turn_id")?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (entryTurnId.isBlank() || entryTurnId in retainedTurnIds) add(item)
                        }
                    })
                })
            }
        }
        val dir = storage.getDialogueSessionsDirectory(runId) / branchId
        storage.mkdirs(dir)
        val compacted = storage.compactSessionTranscript(
            runId, branchId, branchWithState, JsonArray(kept),
        )
        val branchManifest = buildJsonObject {
            branchWithState.forEach { (key, value) -> put(key, value) }
            compacted.writeFields(this)
        }
        val file = dir / "session_manifest.json"
        chatTransfer.copyBranchMetadata(
            runId, source["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty(), branchId, kept,
            selection = swipe?.selection,
        )
        storage.writeTextAtomically(file, json.encodeToString(JsonObject.serializer(), branchManifest))
        longTermMemory.copyForBranch(
            runId = runId,
            sourceSessionId = source["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            targetSessionId = branchId,
            retainedTurnIds = if (swipe == null) retainedTurnIds else retainedTurnIds - sourceTurnId,
        )
        projections?.rebuild(runId, branchId)
        return branchWithState
    }

    private fun markSessionRecovered(session: JsonObject): JsonObject {
        // Ktor 后端无挂起操作，恢复仅刷新恢复标记
        return buildJsonObject {
            session.forEach { (key, value) -> put(key, value) }
            put("recovered_at", nowIsoString())
            put("status", "ready")
        }
    }

    private fun parseJsonObject(content: String): JsonObject {
        val normalized = stripCodeFences(content)
        return try {
            json.parseToJsonElement(normalized).jsonObject
        } catch (error: Exception) {
            // 只记录截断切片与长度：完整模型输出可能包含整章原文或用户隐私数据。
            PlatformLog.e(
                TAG,
                "LLM returned non-JSON content (${content.length} chars, first 500): ${content.take(500)}",
                error,
            )
            JsonObject(emptyMap())
        }
    }
}
