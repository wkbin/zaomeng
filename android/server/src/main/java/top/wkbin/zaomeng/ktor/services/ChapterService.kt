package top.wkbin.zaomeng.ktor.services

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChapterService(
    private val storage: StorageService,
    context: Context
) {
    private val llm = LlmClient(context, ModelApiKeyService(context), storage)
    private val prompts = PromptLoader(context)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    suspend fun rewrite(runId: String, chapterId: String, instruction: String, contextSummary: String): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val file = storage.getChapterFile(runId, chapterId)
        if (!file.exists()) throw NoSuchElementException("Chapter not found: $chapterId")
        val chapter = json.decodeFromString<JsonObject>(file.readText())
        val original = chapter["content"]?.let { (it as? JsonPrimitive)?.content }.orEmpty().trim()
        if (original.isBlank()) throw IllegalArgumentException("Chapter content is empty")
        val instructionLine = if (instruction.isNotBlank()) "改写要求：${instruction.trim()}\n" else ""
        val contextLine = if (contextSummary.isNotBlank()) "上下文：${contextSummary.trim()}\n" else ""
        val user = prompts.getChapterRewriteUserTemplate()
            .replace("{instruction_line}", instructionLine)
            .replace("{context_line}", contextLine)
            .replace("{original}", original)
        val result = llm.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", prompts.getNovelRewritePrompt()),
                LlmClient.ChatMessage("user", user)
            ),
            maxTokens = 4096,
            temperature = 0.7
        )
        val rewritten = result.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (rewritten.isBlank()) throw IllegalStateException("LLM returned empty chapter")
        val updated = buildJsonObject {
            chapter.forEach { (key, value) -> put(key, value) }
            put("content", rewritten)
            put("updated_at", System.currentTimeMillis())
        }
        storage.writeTextAtomically(file, json.encodeToString(JsonObject.serializer(), updated))
        return buildJsonObject {
            put("chapter_id", chapterId)
            put("content", rewritten)
            put("previous_content", original)
        }
    }
}
