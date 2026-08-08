package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.time.Instant
import java.util.UUID

/**
 * 章节管理服务
 *
 * 对应 Python src/web/service_facades/chapters.py 的 ChapterServiceMixin。
 * 存储保持 Ktor server 既有布局 chapters/{chapter_id}.json（单章一文件），
 * 但对外行为与 Python 一致：创建/更新/归档/转换/继续/同步/重排/导出。
 */
class ChapterManagementService(
    private val storage: StorageService,
    private val sessionManagement: SessionManagementService,
    private val llm: LlmClient?,
    private val prompts: PromptLoader?,
) {
    companion object {
        private const val TAG = "ChapterManagement"
        private const val MIN_DIALOGUE_TURNS_FOR_CHAPTER = 6
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_CONTENT_LENGTH = 300_000
        private const val NOVEL_CHAPTER_MAX_CHARS = 3500
        private const val NOVEL_CONTEXT_PREVIOUS_CHARS = 1000
    }

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    /** 列出章节（按 order 排序，重排 order 连续）。 */
    fun list(runId: String): List<JsonObject> {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val chapters = storage.listChapters(runId)
            .sortedBy { it["order"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
        return normalize(chapters)
    }

    /** 搜索书卷内容：章节 + 人物 + 会话。对应 Python search_run_content。 */
    fun search(runId: String, query: String, limit: Int): JsonArray {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return JsonArray(emptyList())
        val results = mutableListOf<JsonObject>()
        for (chapter in list(runId)) {
            val haystack = listOfNotNull(
                chapter["title"]?.jsonPrimitive?.contentOrNull,
                chapter["goal"]?.jsonPrimitive?.contentOrNull,
                chapter["content"]?.jsonPrimitive?.contentOrNull,
            ).joinToString("\n")
            if (!haystack.lowercase().contains(needle)) continue
            results.add(
                buildJsonObject {
                    put("kind", "chapter")
                    put("chapter_id", chapter["chapter_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("session_id", "")
                    put("title", "第 ${chapter["order"]?.jsonPrimitive?.intOrNull ?: 0} 章 · ${chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
                    put("preview", searchPreview(haystack, needle))
                },
            )
            if (results.size >= limit) return buildJsonArray { results.forEach(::add) }
        }
        val manifest = storage.readRunManifest(runId)
        val personas = manifest?.get("artifact_index")?.jsonObject?.get("characters")?.jsonArray
        personas?.forEach { raw ->
            val persona = runCatching { raw.jsonObject }.getOrNull() ?: return@forEach
            val character = persona["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (character.isEmpty()) return@forEach
            val preview = persona["preview"]?.jsonObject ?: JsonObject(emptyMap())
            val previewValues = preview.mapNotNull { (_, value) -> value.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            val haystack = listOf(character) + previewValues
            if (!haystack.joinToString("\n").lowercase().contains(needle)) return@forEach
            results.add(
                buildJsonObject {
                    put("kind", "persona")
                    put("chapter_id", "")
                    put("session_id", "")
                    put("character", character)
                    put("title", "人物 · $character")
                    put("preview", searchPreview(haystack.joinToString("\n"), needle))
                },
            )
            if (results.size >= limit) return buildJsonArray { results.forEach(::add) }
        }
        for (session in storage.listDialogueSessions(runId).take(100)) {
            val sessionId = session["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (sessionId.isEmpty()) continue
            val transcript = transcriptText(session)
            if (transcript.isEmpty()) continue
            if (!transcript.lowercase().contains(needle)) continue
            results.add(
                buildJsonObject {
                    put("kind", "session")
                    put("chapter_id", "")
                    put("session_id", sessionId)
                    put("title", session["last_entry_preview"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                        ?: "会话 ${sessionId.takeLast(6)}")
                    put("preview", searchPreview(transcript, needle))
                },
            )
            if (results.size >= limit) break
        }
        return buildJsonArray { results.forEach(::add) }
    }

    /** 问书卷。对应 Python answer_book_question。 */
    suspend fun ask(runId: String, question: String): JsonObject {
        val normalized = question.trim()
        if (normalized.isEmpty()) throw IllegalArgumentException("问题不能为空。")
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val evidence = mutableListOf<JsonObject>()
        val queries = mutableListOf<String>()
        val manifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val characterNames = manifest["artifact_index"]?.jsonObject?.get("characters")?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
            ?.filter { it.isNotEmpty() && normalized.contains(it) }.orEmpty()
        queries.addAll(if (characterNames.isNotEmpty()) characterNames else listOf(normalized))
        for (query in queries) {
            search(runId, query, 8).forEach { element ->
                runCatching { element.jsonObject }.getOrNull()?.let { item ->
                    if (!evidence.contains(item)) evidence.add(item)
                }
            }
        }
        if (evidence.isEmpty()) {
            return buildJsonObject {
                put("answer", "没有在当前书卷中找到可引用的证据。请换用角色名、章节标题或更具体的关键词。")
                put("evidence", JsonArray(emptyList()))
            }
        }
        val sourceText = evidence.take(12).mapIndexed { index, item ->
            "[${index + 1}] ${item["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}\n${item["preview"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
        }.joinToString("\n\n")
        val userPrompt = requireNotNull(prompts) { "Prompt loader is unavailable" }
            .getAskBookTemplate()
            .replace("{question}", normalized)
            .replace("{evidence}", sourceText)
        val content = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage(
                    "user",
                    userPrompt,
                ),
            ),
            temperature = 0.2,
            maxTokens = 900,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        return buildJsonObject {
            put("answer", content)
            put("evidence", buildJsonArray { evidence.take(12).forEach(::add) })
        }
    }

    /** 创建或更新章节。对应 Python save_chapter。 */
    fun save(
        runId: String,
        chapterId: String,
        title: String,
        goal: String,
        participants: List<String>,
        content: String,
        sourceSessionId: String = "",
        contextSummary: String = "",
    ): JsonObject {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isEmpty()) throw IllegalArgumentException("章节标题不能为空。")
        if (normalizedTitle.length > MAX_TITLE_LENGTH) throw IllegalArgumentException("章节标题不能超过 $MAX_TITLE_LENGTH 个字符。")
        val normalizedContent = content.trim()
        if (normalizedContent.length > MAX_CONTENT_LENGTH) throw IllegalArgumentException("章节草稿不能超过 30 万个字符。")
        val normalizedParticipants = participants.map(String::trim).filter(String::isNotBlank).distinct().take(40)
        val now = Instant.now().toString()
        val chapters = list(runId)
        val targetId = chapterId.trim()
        val existingIndex = chapters.indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == targetId }
        if (targetId.isNotEmpty() && existingIndex < 0) throw NoSuchElementException("Chapter not found: $targetId")
        val chapter: JsonObject
        val updatedChapters: List<JsonObject>
        if (existingIndex >= 0) {
            val old = chapters[existingIndex]
            chapter = buildJsonObject {
                old.forEach { (key, value) -> put(key, value) }
                put("title", normalizedTitle)
                put("goal", goal.trim())
                put("participants", buildJsonArray { normalizedParticipants.forEach { add(JsonPrimitive(it)) } })
                put("content", normalizedContent)
                if (sourceSessionId.isNotEmpty()) put("source_session_id", sourceSessionId.trim())
                if (contextSummary.isNotEmpty()) put("context_summary", contextSummary.trim())
                put("updated_at", now)
            }
            updatedChapters = chapters.toMutableList().apply { this[existingIndex] = chapter }
        } else {
            chapter = buildJsonObject {
                put("chapter_id", "chapter-" + UUID.randomUUID().toString().replace("-", "").take(12))
                put("order", chapters.size + 1)
                put("title", normalizedTitle)
                put("goal", goal.trim())
                put("participants", buildJsonArray { normalizedParticipants.forEach { add(JsonPrimitive(it)) } })
                put("content", normalizedContent)
                put("source_session_id", sourceSessionId.trim())
                put("context_summary", contextSummary.trim())
                put("last_session_id", "")
                put("synced_transcript_count", 0)
                put("created_at", now)
                put("updated_at", now)
            }
            updatedChapters = chapters + chapter
        }
        writeAll(runId, normalize(updatedChapters))
        return chapter
    }

    /** 归档会话为章节。对应 Python archive_dialogue_session_as_chapter。 */
    fun archive(runId: String, sessionId: String, title: String = ""): JsonObject {
        val session = requireSession(runId, sessionId)
        val transcript = transcriptOf(session)
        if (transcript.isEmpty()) throw IllegalArgumentException("这个会话还没有可归档的内容。")
        val dialogueTurns = meaningfulTurnCount(transcript)
        if (dialogueTurns < MIN_DIALOGUE_TURNS_FOR_CHAPTER) {
            throw IllegalArgumentException("当前只有 $dialogueTurns 轮有效对话，至少需要 $MIN_DIALOGUE_TURNS_FOR_CHAPTER 轮才能转为小说章节。")
        }
        val content = transcript.joinToString("\n\n") { item ->
            "${item["speaker"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: "旁白"}：${item["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
        }.trim()
        if (content.isEmpty()) throw IllegalArgumentException("这个会话还没有可归档的内容。")
        val chapters = list(runId)
        val now = Instant.now().toString()
        val chapterTitle = title.trim().ifEmpty {
            session["title"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: "第 ${chapters.size + 1} 章"
        }
        val chapter = buildJsonObject {
            put("chapter_id", "chapter-" + UUID.randomUUID().toString().replace("-", "").take(12))
            put("order", chapters.size + 1)
            put("title", chapterTitle)
            put("goal", session["scene_progress"]?.jsonObject?.get("current_goal")?.jsonPrimitive?.contentOrNull.orEmpty())
            put("participants", session["participants"]?.jsonArray ?: JsonArray(emptyList()))
            put("content", content)
            put("source_session_id", session["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: sessionId)
            put("context_summary", "")
            put("last_session_id", "")
            put("synced_transcript_count", 0)
            put("created_at", now)
            put("updated_at", now)
        }
        writeAll(runId, normalize(chapters + chapter))
        return chapter
    }

    /** 对话转小说。对应 Python convert_dialogue_session_to_novel。 */
    suspend fun convert(runId: String, sessionId: String, title: String = ""): JsonObject {
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置" }
        val session = requireSession(runId, sessionId)
        val transcript = transcriptOf(session)
        val dialogueTurns = meaningfulTurnCount(transcript)
        if (dialogueTurns < MIN_DIALOGUE_TURNS_FOR_CHAPTER) {
            throw IllegalArgumentException("当前只有 $dialogueTurns 轮有效对话，至少需要 $MIN_DIALOGUE_TURNS_FOR_CHAPTER 轮才能转为小说章节。")
        }
        val chapters = list(runId)
        val previous = chapters.lastOrNull()
        val previousContent = previous?.get("content")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val storyRecap = session["story_recap"]?.jsonObject ?: JsonObject(emptyMap())
        val contextParts = mutableListOf<String>()
        if (previous != null && previousContent.isNotEmpty()) {
            val summary = previous["context_summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (summary.isNotEmpty()) contextParts.add("上一章摘要：$summary")
            contextParts.add("上一章《${previous["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}》结尾：\n${previousContent.takeLast(NOVEL_CONTEXT_PREVIOUS_CHARS)}")
        }
        val summary = storyRecap["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (summary.isNotEmpty()) contextParts.add("本段剧情摘要：$summary")
        val scene = listOfNotNull(
            storyRecap["time_hint"]?.jsonPrimitive?.contentOrNull,
            storyRecap["location"]?.jsonPrimitive?.contentOrNull,
            storyRecap["atmosphere"]?.jsonPrimitive?.contentOrNull,
            session["scene_card"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull,
        ).joinToString("，")
        val dialogues = transcript.mapNotNull { item ->
            val role = item["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (role in setOf("scene", "director", "loading")) return@mapNotNull null
            val speaker = item["speaker"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val message = item["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (speaker.isEmpty() || message.isEmpty()) return@mapNotNull null
            val dialogue = buildJsonObject {
                put("speaker", speaker)
                put("message", message)
                item["inner_thought"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { put("inner_thought", it) }
            }
            dialogue
        }
        if (dialogues.isEmpty()) throw IllegalArgumentException("这个会话还没有可转成小说的有效对话。")
        val system = loader.getNovelRewritePrompt()
        val llmInput = buildJsonObject {
            put("context", contextParts.joinToString("\n\n").trim())
            put("scene", scene)
            put("point_of_view", "第三人称限知")
            put("style", "自然、克制、有画面感")
            put("chapter_length", "目标约 2500 字，最多不超过 $NOVEL_CHAPTER_MAX_CHARS 字。")
            put("dialogues", buildJsonArray { dialogues.forEach(::add) })
        }
        val raw = client.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", system),
                LlmClient.ChatMessage("user", llmInput.toString()),
            ),
            temperature = 0.55,
            maxTokens = 4096,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (raw.isEmpty()) throw IllegalStateException("模型没有返回小说正文，请稍后重试。")
        val rawLines = raw.lines()
        val generatedTitle = rawLines.firstOrNull { it.isNotBlank() }?.trim(' ', '#', '《', '》', '「', '」', '"', '\'') ?: ""
        var body = rawLines.drop(1).joinToString("\n").trim()
        if (body.isEmpty()) body = raw
        if (body.length > NOVEL_CHAPTER_MAX_CHARS) body = trimChapterContent(body, NOVEL_CHAPTER_MAX_CHARS)
        val chapterTitle = title.trim().ifEmpty {
            generatedTitle.ifEmpty {
                storyRecap["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifEmpty { "第 ${chapters.size + 1} 章" }
            }
        }
        // 对齐 Python：context_summary = summary or body[:180]
        val contextSummary = summary.ifBlank { body.take(180) }
        val participants = session["participants"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return save(
            runId,
            chapterId = "",
            title = chapterTitle,
            goal = "由对话改写成小说正文。",
            participants = participants,
            content = body,
            sourceSessionId = session["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: sessionId,
            contextSummary = contextSummary,
        )
    }

    /** 删除章节。对应 Python delete_chapter。 */
    fun delete(runId: String, chapterId: String): JsonObject {
        val chapters = list(runId)
        val remaining = chapters.filterNot { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
        if (remaining.size == chapters.size) throw NoSuchElementException("Chapter not found: $chapterId")
        writeAll(runId, normalize(remaining))
        return buildJsonObject {
            put("status", "deleted")
            put("chapter_id", chapterId)
        }
    }

    /** 从章节继续写作：创建续写会话。对应 Python continue_chapter_writing。 */
    fun continueWriting(runId: String, chapterId: String): JsonObject {
        val chapters = list(runId)
        val chapter = chapters.firstOrNull { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
            ?: throw NoSuchElementException("Chapter not found: $chapterId")
        var participants = chapter["participants"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter(String::isNotBlank).orEmpty()
        if (participants.isEmpty()) {
            val manifest = storage.readRunManifest(runId)
            participants = (manifest?.get("artifact_index")?.jsonObject?.get("characters")?.jsonArray
                ?.mapNotNull { runCatching { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
                ?.filter(String::isNotBlank)).orEmpty().take(4)
        }
        if (participants.isEmpty()) throw IllegalArgumentException("请先为章节填写至少一位出场人物。")
        val draftExcerpt = chapter["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().takeLast(4_000)
        val sceneProfile = buildJsonObject {
            put("title", chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put(
                "opening_situation",
                if (draftExcerpt.isNotEmpty()) "正在续写本章。已有草稿如下：\n$draftExcerpt" else "正在续写一个新章节。",
            )
            put("scene_drive", chapter["goal"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: "推进本章剧情，并保持人物一致。")
            put("expected_rhythm", "延续已有草稿，自然推进。")
        }
        val session = sessionManagement.createDialogueSession(
            runId = runId,
            mode = "observe",
            participants = participants,
            sceneProfile = sceneProfile,
        )
        val sessionId = session["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val updated = buildJsonObject {
            chapter.forEach { (key, value) -> put(key, value) }
            put("last_session_id", sessionId)
            put("updated_at", Instant.now().toString())
        }
        val updatedChapters = chapters.toMutableList().apply {
            val index = indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
            if (index >= 0) this[index] = updated
        }
        writeAll(runId, normalize(updatedChapters))
        return session
    }

    /** 同步会话最新轮次到章节。对应 Python sync_latest_session_to_chapter。 */
    fun sync(runId: String, chapterId: String): JsonObject {
        val chapters = list(runId)
        val index = chapters.indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
        if (index < 0) throw NoSuchElementException("Chapter not found: $chapterId")
        val chapter = chapters[index]
        val sessionId = chapter["last_session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sessionId.isEmpty()) throw IllegalArgumentException("请先从这个章节进入一次继续写作。")
        val session = requireSession(runId, sessionId)
        val transcript = transcriptOf(session).filter { it["message"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }
        val syncedCount = (chapter["synced_transcript_count"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val freshEntries = transcript.drop(syncedCount)
        if (freshEntries.isEmpty()) return chapter
        val addition = freshEntries.joinToString("\n\n") { item ->
            "${item["speaker"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: "旁白"}：${item["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
        }
        val existing = chapter["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val updated = buildJsonObject {
            chapter.forEach { (key, value) -> put(key, value) }
            put("content", listOf(existing, addition).filter(String::isNotBlank).joinToString("\n\n"))
            put("synced_transcript_count", transcript.size)
            put("updated_at", Instant.now().toString())
        }
        val updatedChapters = chapters.toMutableList().apply { this[index] = updated }
        writeAll(runId, normalize(updatedChapters))
        return updated
    }

    /** 重排章节顺序。对应 Python reorder_chapter，返回 items。 */
    fun reorder(runId: String, chapterId: String, targetOrder: Int): JsonObject {
        val chapters = list(runId)
        val index = chapters.indexOfFirst { it["chapter_id"]?.jsonPrimitive?.contentOrNull == chapterId }
        if (index < 0) throw NoSuchElementException("Chapter not found: $chapterId")
        val targetIndex = targetOrder.coerceIn(1, chapters.size) - 1
        val mutable = chapters.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(targetIndex, item)
        val normalized = normalize(mutable)
        writeAll(runId, normalized)
        return buildJsonObject {
            put("items", buildJsonArray { normalized.forEach(::add) })
        }
    }

    /** 导出章节手稿。对应 Python render_chapter_manuscript。 */
    fun render(runId: String, format: String): String {
        val normalizedFormat = if (format == "text") "text" else "markdown"
        val manifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val title = (manifest["title"]?.jsonPrimitive?.contentOrNull ?: manifest["novel_id"]?.jsonPrimitive?.contentOrNull)
            ?.takeIf(String::isNotBlank) ?: "未命名书卷"
        val chapters = list(runId)
        val lines = mutableListOf<String>()
        if (normalizedFormat == "markdown") {
            lines.add("# $title")
            lines.add("")
            for (chapter in chapters) {
                lines.add("## ${chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
                lines.add("")
                chapter["goal"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { goal ->
                    lines.add("> 本章目标：$goal")
                    lines.add("")
                }
                chapter["content"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { content ->
                    lines.add(content.trim())
                    lines.add("")
                }
            }
        } else {
            lines.add(title)
            lines.add("")
            for (chapter in chapters) {
                lines.add(chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
                lines.add("")
                chapter["goal"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { goal ->
                    lines.add("本章目标：$goal")
                    lines.add("")
                }
                chapter["content"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { content ->
                    lines.add(content.trim())
                    lines.add("")
                }
            }
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private fun requireSession(runId: String, sessionId: String): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        return runCatching { storage.getDialogueSession(runId, sessionId) }.getOrNull()
            ?: throw NoSuchElementException("Session not found: $sessionId")
    }

    private fun transcriptOf(session: JsonObject): List<JsonObject> =
        session["transcript"]?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()

    private fun transcriptText(session: JsonObject): String =
        transcriptOf(session).joinToString("\n") { item ->
            "${item["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty()}：${item["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
        }

    private fun meaningfulTurnCount(transcript: List<JsonObject>): Int {
        val meaningful = transcript.filter { item ->
            item["message"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true &&
                (item["role"]?.jsonPrimitive?.contentOrNull ?: "").let { it !in setOf("scene", "director", "loading") }
        }
        val turnIds = meaningful.mapNotNull { it["turn_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }.distinct()
        return if (turnIds.isNotEmpty()) turnIds.size else meaningful.size
    }

    private fun normalize(chapters: List<JsonObject>): List<JsonObject> {
        val normalized = mutableListOf<JsonObject>()
        chapters.forEachIndexed { index, raw ->
            if (raw["chapter_id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return@forEachIndexed
            normalized.add(
                buildJsonObject {
                    raw.forEach { (key, value) -> put(key, value) }
                    put("order", index + 1)
                    put("title", raw["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank) ?: "第 ${index + 1} 章")
                    put("goal", raw["goal"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("participants", raw["participants"]?.jsonArray ?: JsonArray(emptyList()))
                    put("content", raw["content"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("source_session_id", raw["source_session_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("context_summary", raw["context_summary"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("last_session_id", raw["last_session_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("synced_transcript_count", (raw["synced_transcript_count"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0))
                },
            )
        }
        return normalized
    }

    private fun writeAll(runId: String, chapters: List<JsonObject>) {
        // 删除不再存在的章节文件，写入/更新现有文件
        val existingIds = storage.listChapterIds(runId).toSet()
        val newIds = chapters.mapNotNull { it["chapter_id"]?.jsonPrimitive?.contentOrNull }.toSet()
        existingIds.filterNot { it in newIds }.forEach { orphan ->
            storage.getChapterFile(runId, orphan).takeIf { it.exists() }?.delete()
        }
        for (chapter in chapters) {
            val chapterId = chapter["chapter_id"]?.jsonPrimitive?.contentOrNull ?: continue
            val file = storage.getChapterFile(runId, chapterId)
            storage.writeTextAtomically(file, json.encodeToString(JsonObject.serializer(), chapter))
        }
    }

    private fun searchPreview(text: String, needle: String): String {
        val normalized = text.trim()
        val index = normalized.lowercase().indexOf(needle)
        if (index < 0) return normalized.take(180)
        val start = (index - 56).coerceAtLeast(0)
        val end = (index + needle.length + 100).coerceAtMost(normalized.length)
        return (if (start > 0) "…" else "") + normalized.substring(start, end).replace("\n", " ") + (if (end < normalized.length) "…" else "")
    }

    private fun trimChapterContent(content: String, limit: Int): String {
        val text = content.trim()
        if (text.length <= limit) return text
        val candidate = text.take((limit - 1).coerceAtLeast(1))
        val boundary = maxOf(
            candidate.lastIndexOf("。"),
            candidate.lastIndexOf("！"),
            candidate.lastIndexOf("？"),
            candidate.lastIndexOf("”"),
            candidate.lastIndexOf("\n"),
        )
        val trimmed = if (boundary > limit * 0.6) candidate.take(boundary + 1) else candidate
        return trimmed.trimEnd() + "…"
    }
}
