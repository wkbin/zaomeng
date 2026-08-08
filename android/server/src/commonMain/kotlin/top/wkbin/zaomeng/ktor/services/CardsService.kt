package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 场景卡字段清单（对齐 Python src/web/review/scene_cards.py:SCENE_CARD_FIELDS）。
 */
private val SCENE_CARD_FIELDS = listOf(
    "title", "time_hint", "location", "atmosphere", "opening_situation",
    "public_goal", "hidden_tension", "scene_drive", "expected_rhythm", "forbidden_topics",
)

private val SCENE_CARD_FIELD_LABELS = mapOf(
    "title" to "场景名",
    "time_hint" to "时间提示",
    "location" to "地点",
    "atmosphere" to "场面气氛",
    "opening_situation" to "开场局面",
    "public_goal" to "明面目标",
    "hidden_tension" to "暗线张力",
    "scene_drive" to "推进方向",
    "expected_rhythm" to "节奏手感",
    "forbidden_topics" to "不想碰的话头",
)

/**
 * 自我角色卡额外字段（对齐 Python src/web/review/self_cards.py:SELF_CARD_EXTRA_FIELDS）。
 */
private val SELF_CARD_EXTRA_FIELDS = listOf("display_name", "scene_identity", "interaction_style")

/**
 * 人物审校字段（对齐 Python src/web/review/persona.py:PERSONA_REVIEW_FIELDS）。
 */
private val PERSONA_REVIEW_FIELDS = listOf(
    "core_identity", "story_role", "identity_anchor", "temperament_type", "gender", "age_stage",
    "appearance_feature", "habit_action", "soul_goal", "hidden_desire", "inner_conflict",
    "self_cognition", "private_self", "speech_style", "cadence", "typical_lines",
    "signature_phrases", "sentence_openers", "sentence_endings", "social_mode", "thinking_style",
    "decision_rules", "reward_logic", "worldview", "belief_anchor", "moral_bottom_line",
    "restraint_threshold", "core_traits", "key_bonds", "preference_like", "dislike_hate",
    "forbidden_behaviors", "stress_response", "emotion_model", "anger_style", "joy_style",
    "grievance_style", "others_impression",
)

private val PERSONA_REVIEW_FIELD_LABELS = mapOf(
    "core_identity" to "核心身份",
    "story_role" to "故事位置",
    "identity_anchor" to "身份锚点",
    "temperament_type" to "气质底色",
    "gender" to "性别",
    "age_stage" to "年龄阶段",
    "appearance_feature" to "外貌辨识",
    "habit_action" to "习惯动作",
    "soul_goal" to "灵魂目标",
    "hidden_desire" to "隐秘渴望",
    "inner_conflict" to "内在冲突",
    "self_cognition" to "自我认知",
    "private_self" to "私下的一面",
    "speech_style" to "说话方式",
    "cadence" to "语句节奏",
    "typical_lines" to "代表句",
    "signature_phrases" to "口头禅",
    "sentence_openers" to "起句习惯",
    "sentence_endings" to "句尾习惯",
    "social_mode" to "社交模式",
    "thinking_style" to "思考方式",
    "decision_rules" to "决策规则",
    "reward_logic" to "回报逻辑",
    "worldview" to "世界观",
    "belief_anchor" to "信念支点",
    "moral_bottom_line" to "道德底线",
    "restraint_threshold" to "失控阈值",
    "core_traits" to "核心特质",
    "key_bonds" to "重要牵系",
    "preference_like" to "偏好喜好",
    "dislike_hate" to "明显厌恶",
    "forbidden_behaviors" to "不会做的事",
    "stress_response" to "应激反应",
    "emotion_model" to "情绪底模",
    "anger_style" to "发怒方式",
    "joy_style" to "开心方式",
    "grievance_style" to "委屈方式",
    "others_impression" to "他人观感",
)

class CardsService(
    @Suppress("UNUSED_PARAMETER") private val storage: StorageService,
    private val llm: LlmClient,
    private val prompts: PromptLoader,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun generateSceneCard(): JsonObject = generate(
        prompts.getSceneCardGenerationPrompt(),
        buildSceneCardInstruction()
    )

    suspend fun generateSelfCard(): JsonObject = generate(
        prompts.getSelfCardGenerationPrompt(),
        buildSelfCardInstruction()
    )

    /**
     * 场景卡 user prompt（对齐 Python build_random_scene_card_messages）。
     */
    private fun buildSceneCardInstruction(): String {
        val fieldLines = SCENE_CARD_FIELDS.joinToString("\n") { field ->
            "- $field: ${SCENE_CARD_FIELD_LABELS[field] ?: field}"
        }
        return prompts.getCardInstruction("scene_card", fieldLines)
    }

    /**
     * 自我角色卡 user prompt（对齐 Python build_random_self_card_messages）。
     */
    private fun buildSelfCardInstruction(): String {
        val fields = SELF_CARD_EXTRA_FIELDS + PERSONA_REVIEW_FIELDS
        val labels = SELF_CARD_EXTRA_FIELDS.associateWith { field ->
            when (field) {
                "display_name" -> "角色名"
                "scene_identity" -> "入场身份"
                else -> "互动气氛"
            }
        } + PERSONA_REVIEW_FIELD_LABELS
        val fieldLines = fields.joinToString("\n") { field ->
            "- $field: ${labels[field] ?: field}"
        }
        return prompts.getCardInstruction("self_card", fieldLines)
    }

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
