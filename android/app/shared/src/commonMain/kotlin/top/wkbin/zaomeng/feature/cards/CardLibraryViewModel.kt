package top.wkbin.zaomeng.feature.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ReusableCardKind
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.ReusableCardDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class CardDraft(
    val cardId: String = "",
    val values: Map<String, String> = emptyMap(),
    val original: JsonObject = JsonObject(emptyMap()),
)

data class CardLibraryUiState(
    val kind: ReusableCardKind = ReusableCardKind.Scene,
    val cards: List<ReusableCardDto> = emptyList(),
    val sceneCards: List<ReusableCardDto> = emptyList(),
    val selfCards: List<ReusableCardDto> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val generating: Boolean = false,
    val deletingCardId: String = "",
    val editorVisible: Boolean = false,
    val draft: CardDraft = CardDraft(),
    val error: String = "",
    val message: String = "",
)

class CardLibraryViewModel(
    private val repository: ZaomengRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CardLibraryUiState())
    val state: StateFlow<CardLibraryUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
    }

    fun selectKind(kind: ReusableCardKind) {
        if (kind == state.value.kind || state.value.saving || state.value.generating) return
        mutableState.update {
            it.copy(
                kind = kind,
                cards = emptyList(),
                editorVisible = false,
                draft = CardDraft(),
                error = "",
                message = "",
            )
        }
        load()
    }

    fun load() {
        loadJob?.cancel()
        val kind = state.value.kind
        loadJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = it.cards.isEmpty(),
                    refreshing = it.cards.isNotEmpty(),
                    error = "",
                )
            }
            try {
                val cards = repository.listReusableCards(kind)
                val sceneCards = if (kind == ReusableCardKind.Opening) {
                    repository.listReusableCards(ReusableCardKind.Scene)
                } else {
                    state.value.sceneCards
                }
                val selfCards = if (kind == ReusableCardKind.Opening) {
                    repository.listReusableCards(ReusableCardKind.Self)
                } else {
                    state.value.selfCards
                }
                if (state.value.kind == kind) {
                    mutableState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            cards = cards,
                            sceneCards = sceneCards,
                            selfCards = selfCards,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.message ?: "卡片列表读取失败。",
                    )
                }
            }
        }
    }

    fun createCard() {
        mutableState.update {
            val values = definitions(it.kind).associate { field -> field.key to "" }.toMutableMap()
            if (it.kind == ReusableCardKind.Opening) values["mode"] = "observe"
            it.copy(
                editorVisible = true,
                draft = CardDraft(values = values),
                error = "",
                message = "",
            )
        }
    }

    fun editCard(card: ReusableCardDto) {
        val kind = state.value.kind
        val values = definitions(kind).associate { definition ->
            definition.key to when (definition.key) {
                "participants" -> card.fields[definition.key]
                    ?.let { element -> runCatching { element.jsonArray }.getOrNull() }
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.joinToString("、")
                    .orEmpty()
                else -> card.fields[definition.key]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }
        mutableState.update {
            it.copy(
                editorVisible = true,
                draft = CardDraft(cardId = card.cardId, values = values, original = card.fields),
                error = "",
                message = "",
            )
        }
    }

    fun updateField(field: String, value: String) {
        if (state.value.saving) return
        mutableState.update {
            it.copy(
                draft = it.draft.copy(values = it.draft.values + (field to value)),
                error = "",
            )
        }
    }

    fun closeEditor() {
        if (state.value.saving) return
        mutableState.update { it.copy(editorVisible = false, draft = CardDraft(), error = "") }
    }

    fun save() {
        val snapshot = state.value
        if (!snapshot.editorVisible || snapshot.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                val fields = if (snapshot.kind == ReusableCardKind.Opening) {
                    buildOpeningFields(snapshot)
                } else {
                    buildFields(snapshot.kind, snapshot.draft)
                }
                val saved = repository.saveReusableCard(
                    snapshot.kind,
                    snapshot.draft.cardId,
                    fields,
                )
                mutableState.update {
                    it.copy(
                        saving = false,
                        editorVisible = false,
                        draft = CardDraft(),
                        cards = listOf(saved) + it.cards.filterNot { card -> card.cardId == saved.cardId },
                        message = "${snapshot.kind.displayName}已保存。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(saving = false, error = error.message ?: "卡片保存失败。")
                }
            }
        }
    }

    fun generate() {
        val kind = state.value.kind
        if (kind == ReusableCardKind.Opening || state.value.generating) return
        viewModelScope.launch {
            mutableState.update { it.copy(generating = true, error = "", message = "") }
            try {
                val generatedDraft = repository.generateReusableCard(kind)
                val saved = repository.saveReusableCard(kind, cardId = "", fields = generatedDraft.fields)
                if (state.value.kind == kind) {
                    mutableState.update {
                        it.copy(
                            generating = false,
                            cards = listOf(saved) + it.cards.filterNot { card -> card.cardId == saved.cardId },
                            message = "已生成并保存一张${kind.displayName}，可以继续编辑。",
                        )
                    }
                } else {
                    mutableState.update { it.copy(generating = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(generating = false, error = error.message ?: "自动生成失败。")
                }
            }
        }
    }

    fun delete(cardId: String) {
        val snapshot = state.value
        if (cardId.isBlank() || snapshot.deletingCardId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(deletingCardId = cardId, error = "", message = "") }
            try {
                repository.deleteReusableCard(snapshot.kind, cardId)
                mutableState.update {
                    it.copy(
                        deletingCardId = "",
                        cards = it.cards.filterNot { card -> card.cardId == cardId },
                        message = "${snapshot.kind.displayName}已删除。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(deletingCardId = "", error = error.message ?: "卡片删除失败。")
                }
            }
        }
    }

    fun dismissNotice() {
        mutableState.update { it.copy(error = "", message = "") }
    }

    private fun buildFields(kind: ReusableCardKind, draft: CardDraft): JsonObject = buildJsonObject {
        draft.original.forEach { (key, value) -> put(key, value) }
        definitions(kind).forEach { definition ->
            val value = draft.values[definition.key].orEmpty().trim()
            if (definition.key == "participants") {
                val participants = value
                    .split(',', '，', '、', ';', '；', '\n')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .map(::JsonPrimitive)
                put(definition.key, JsonArray(participants))
            } else {
                put(definition.key, JsonPrimitive(value))
            }
        }
    }

    private suspend fun buildOpeningFields(state: CardLibraryUiState): JsonObject {
        val base = buildFields(ReusableCardKind.Opening, state.draft)
        val sceneCardId = state.draft.values["scene_card_id"].orEmpty().trim()
        val selfCardId = state.draft.values["self_card_id"].orEmpty().trim()
        val sceneCard = sceneCardId.takeIf(String::isNotBlank)?.let { selectedId ->
            state.sceneCards.firstOrNull { it.cardId == selectedId }
                ?: repository.getReusableCard(ReusableCardKind.Scene, selectedId)
        }
        val selfCard = selfCardId.takeIf(String::isNotBlank)?.let { selectedId ->
            state.selfCards.firstOrNull { it.cardId == selectedId }
                ?: repository.getReusableCard(ReusableCardKind.Self, selectedId)
        }
        return buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            put("scene_card", sceneCard?.toSnapshot() ?: JsonObject(emptyMap()))
            put("self_card", selfCard?.toSnapshot() ?: JsonObject(emptyMap()))
        }
    }
}

private fun ReusableCardDto.toSnapshot(): JsonObject = buildJsonObject {
    put("card_id", JsonPrimitive(cardId))
    put("fields", fields)
    put("preview", preview)
}

data class CardFieldDefinition(
    val key: String,
    val label: String,
    val required: Boolean = false,
    val singleLine: Boolean = false,
)

internal fun definitions(kind: ReusableCardKind): List<CardFieldDefinition> = when (kind) {
    ReusableCardKind.Scene -> sceneFields
    ReusableCardKind.Self -> selfFields
    ReusableCardKind.Opening -> openingFields
}

internal val ReusableCardKind.displayName: String
    get() = when (this) {
        ReusableCardKind.Scene -> "场景卡"
        ReusableCardKind.Self -> "自设卡"
        ReusableCardKind.Opening -> "开场预设"
    }

private val sceneFields = listOf(
    CardFieldDefinition("title", "场景名", required = true, singleLine = true),
    CardFieldDefinition("time_hint", "时间提示", singleLine = true),
    CardFieldDefinition("location", "地点", required = true, singleLine = true),
    CardFieldDefinition("atmosphere", "场面气氛", required = true),
    CardFieldDefinition("opening_situation", "开场局面", required = true),
    CardFieldDefinition("public_goal", "明面目标"),
    CardFieldDefinition("hidden_tension", "暗线张力"),
    CardFieldDefinition("scene_drive", "推进方向", required = true),
    CardFieldDefinition("expected_rhythm", "节奏手感"),
    CardFieldDefinition("forbidden_topics", "不想碰的话头"),
)

private val selfFields = listOf(
    CardFieldDefinition("display_name", "角色名", true, true),
    CardFieldDefinition("scene_identity", "入场身份", false, true),
    CardFieldDefinition("interaction_style", "互动气氛"),
    CardFieldDefinition("core_identity", "核心身份", true),
    CardFieldDefinition("story_role", "故事定位", true),
    CardFieldDefinition("identity_anchor", "身份锚点", true),
    CardFieldDefinition("gender", "性别", false, true),
    CardFieldDefinition("age_stage", "年龄阶段", false, true),
    CardFieldDefinition("appearance_feature", "外貌特征"),
    CardFieldDefinition("habit_action", "习惯动作"),
    CardFieldDefinition("preference_like", "偏好"),
    CardFieldDefinition("dislike_hate", "厌恶"),
    CardFieldDefinition("temperament_type", "气质类型", true),
    CardFieldDefinition("soul_goal", "灵魂目标", true),
    CardFieldDefinition("hidden_desire", "隐藏欲望"),
    CardFieldDefinition("inner_conflict", "内在冲突"),
    CardFieldDefinition("self_cognition", "自我认知"),
    CardFieldDefinition("private_self", "私下状态"),
    CardFieldDefinition("speech_style", "说话风格", true),
    CardFieldDefinition("cadence", "语速节奏"),
    CardFieldDefinition("typical_lines", "典型台词"),
    CardFieldDefinition("signature_phrases", "标志用语"),
    CardFieldDefinition("sentence_openers", "常用开头"),
    CardFieldDefinition("sentence_endings", "常用结尾"),
    CardFieldDefinition("social_mode", "社交方式"),
    CardFieldDefinition("thinking_style", "思考方式"),
    CardFieldDefinition("decision_rules", "决策规则"),
    CardFieldDefinition("reward_logic", "奖惩逻辑"),
    CardFieldDefinition("worldview", "世界观", true),
    CardFieldDefinition("belief_anchor", "信念锚点", true),
    CardFieldDefinition("moral_bottom_line", "道德底线", true),
    CardFieldDefinition("restraint_threshold", "克制阈值", true),
    CardFieldDefinition("core_traits", "核心特质", true),
    CardFieldDefinition("key_bonds", "关键羁绊", true),
    CardFieldDefinition("forbidden_behaviors", "禁止行为"),
    CardFieldDefinition("stress_response", "压力反应", true),
    CardFieldDefinition("emotion_model", "情绪模型"),
    CardFieldDefinition("anger_style", "愤怒方式"),
    CardFieldDefinition("joy_style", "喜悦方式"),
    CardFieldDefinition("grievance_style", "委屈方式"),
    CardFieldDefinition("others_impression", "他人印象"),
)

private val openingFields = listOf(
    CardFieldDefinition("title", "预设名称", true, true),
    CardFieldDefinition("note", "备注"),
    CardFieldDefinition("mode", "模式：observe / act / insert", true, true),
    CardFieldDefinition("participants", "参与人物", true),
    CardFieldDefinition("controlled_character", "扮演人物", false, true),
    CardFieldDefinition("scene_card_id", "场景卡 ID", false, true),
    CardFieldDefinition("self_card_id", "自设卡 ID", false, true),
    CardFieldDefinition("self_name", "入场名字", false, true),
    CardFieldDefinition("self_identity", "入场身份"),
    CardFieldDefinition("self_style", "互动风格"),
)
