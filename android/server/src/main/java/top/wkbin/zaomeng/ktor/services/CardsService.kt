package top.wkbin.zaomeng.ktor.services

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class CardsService(context: Context) {
    private val storage = StorageService(context)
    private val llm = LlmClient(context, ModelApiKeyService(context), storage)
    private val prompts = PromptLoader(context)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun generateSceneCard(): JsonObject = generate(
        prompts.getSceneCardGenerationPrompt(),
        "请生成一个新的原创场景卡。"
    )

    suspend fun generateSelfCard(): JsonObject = generate(
        prompts.getSelfCardGenerationPrompt(),
        "请生成一个新的原创自我角色卡。"
    )

    private suspend fun generate(system: String, instruction: String): JsonObject {
        val response = llm.chatCompletion(
            messages = listOf(
                LlmClient.ChatMessage("system", system),
                LlmClient.ChatMessage("user", instruction)
            ),
            temperature = 0.9,
            maxTokens = 2200
        )
        val content = response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (content.isBlank()) throw IllegalStateException("LLM returned empty card")
        val normalized = content.removePrefix("```").removeSuffix("```").trim()
            .removePrefix("json").trim()
        return try {
            json.decodeFromString(JsonObject.serializer(), normalized)
        } catch (error: Exception) {
            throw IllegalStateException("LLM returned invalid card JSON", error)
        }
    }
}
