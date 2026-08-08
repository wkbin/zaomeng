package top.wkbin.zaomeng.ktor.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * 对话建议服务
 *
 * 生成用户下一步可以输入的建议
 */
class SuggestionsService(
    private val context: Context,
    private val storageService: StorageService,
    private val llmClient: LlmClient,
    private val promptLoader: PromptLoader
) {
    companion object {
        private const val TAG = "SuggestionsService"
    }

    /**
     * 生成对话建议（流式）
     *
     * @param runId 运行 ID
     * @param sessionId 会话 ID
     * @param seedText 种子文本（用户已输入的部分）
     * @param selectedDirection 用户选择的方向（可选）
     * @return Flow<String> 建议文本增量
     */
    fun generateSuggestionStream(
        runId: String,
        sessionId: String,
        seedText: String = "",
        selectedDirection: String = ""
    ): Flow<String> = flow {
        Log.d(TAG, "Generating suggestion stream: run=$runId, session=$sessionId")

        try {
            // 1. 加载会话清单
            val sessionManifest = storageService.loadSessionManifest(runId, sessionId)

            // 2. 加载模型设置
            val modelSettings = storageService.loadModelSettings()
            val provider = modelSettings["provider"] as? String ?: "openai"
            val modelName = modelSettings["model"] as? String ?: "gpt-4"

            // 3. 构建提示词（对齐 Python build_dialogue_suggestion_llm_messages）
            val payloadBuilder = DialoguePayloadBuilder(storageService)
            val promptBuilder = DialoguePromptBuilder(promptLoader)
            val runManifest = storageService.readRunManifest(runId)
                ?: throw NoSuchElementException("Run not found: $runId")
            val payload = payloadBuilder.buildSuggestionPayload(
                runManifest = runManifest,
                session = sessionManifest,
                seedText = seedText,
                direction = selectedDirection,
            )

            // 4. 构建对话消息
            val messages = promptBuilder.buildDialogueSuggestionLlmMessages(
                payload = payload,
                retryOnEmpty = false,
            )

            // 6. 调用流式 API
            llmClient.chatCompletionStream(
                messages = messages,
                model = modelName,
                temperature = 0.8,
                maxTokens = 512
            ).collect { delta ->
                emit(delta)
            }

            Log.d(TAG, "Suggestion stream completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error generating suggestion stream", e)
            throw e
        }
    }

    /**
     * 生成对话联想选项（非流式）
     *
     * @param runId 运行 ID
     * @param sessionId 会话 ID
     * @param optionCount 选项数量（2-4）
     * @return 联想选项列表
     */
    suspend fun generateAssociations(
        runId: String,
        sessionId: String,
        optionCount: Int = 3
    ): List<AssociationOption> {
        Log.d(TAG, "Generating associations: run=$runId, session=$sessionId, count=$optionCount")

        try {
            // 1. 加载会话清单
            val sessionManifest = storageService.loadSessionManifest(runId, sessionId)

            // 2. 加载模型设置
            val modelSettings = storageService.loadModelSettings()
            val provider = modelSettings["provider"] as? String ?: "openai"
            val modelName = modelSettings["model"] as? String ?: "gpt-4"

            // 3. 构建提示词（对齐 Python build_dialogue_association_llm_messages）
            val payloadBuilder = DialoguePayloadBuilder(storageService)
            val promptBuilder = DialoguePromptBuilder(promptLoader)
            val runManifest = storageService.readRunManifest(runId)
                ?: throw NoSuchElementException("Run not found: $runId")
            val payload = payloadBuilder.buildAssociationPayload(
                runManifest = runManifest,
                session = sessionManifest,
                optionCount = optionCount,
            )

            // 4. 构建对话消息
            val messages = promptBuilder.buildDialogueAssociationLlmMessages(
                payload = payload,
                retryOnEmpty = false,
            )

            // 6. 调用 LLM API
            val response = llmClient.chatCompletion(
                messages = messages,
                model = modelName,
                temperature = 0.9,
                maxTokens = 1024
            )

            // 7. 解析响应
            val content = response.choices.firstOrNull()?.message?.content
                ?: throw IllegalStateException("Empty response from LLM")

            val parsed = parseAssociations(content)

            Log.d(TAG, "Generated ${parsed.size} associations")
            return parsed
        } catch (e: Exception) {
            Log.e(TAG, "Error generating associations", e)
            throw e
        }
    }

    /**
     * 解析联想选项
     */
    private fun parseAssociations(content: String): List<AssociationOption> {
        val json = Json { ignoreUnknownKeys = true }

        try {
            // 尝试解析为完整的 JSON 对象
            val parsed = json.parseToJsonElement(content).jsonObject
            val options = parsed["options"]?.jsonArray ?: return emptyList()

            return options.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    AssociationOption(
                        label = obj["label"]?.jsonPrimitive?.contentOrNull ?: "",
                        direction = obj["direction"]?.jsonPrimitive?.contentOrNull ?: "",
                        suggestion = obj["suggestion"]?.jsonPrimitive?.contentOrNull
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse option: $element", e)
                    null
                }
            }.filter { it.label.isNotBlank() && it.direction.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse associations JSON", e)
            return emptyList()
        }
    }
}

/**
 * 联想选项
 */
@Serializable
data class AssociationOption(
    val label: String,
    val direction: String,
    val suggestion: String? = null
)
