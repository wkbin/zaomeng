package top.wkbin.zaomeng.feature.persona

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.PersonaIssueDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaRepairChangeDto
import top.wkbin.zaomeng.data.api.PersonaRepairProposalDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonaFieldSpec(
    val key: String,
    val label: String,
    val supportsSuggestion: Boolean = false,
)

data class PersonaFieldGroup(
    val key: String,
    val title: String,
    val description: String,
    val fields: List<PersonaFieldSpec>,
)

enum class PersonaFeedbackKind {
    Loading,
    Success,
    Error,
}

data class PersonaFieldFeedback(
    val kind: PersonaFeedbackKind,
    val message: String,
)

data class PersonaNotice(
    val id: Long,
    val message: String,
)

data class PersonaUiState(
    val runId: String = "",
    val character: String = "",
    val fields: Map<String, String> = emptyMap(),
    val editableProfilePath: String = "",
    val generatedProfilePath: String = "",
    val reviewNote: String = "",
    val quality: PersonaQualityReportDto? = null,
    val qualityError: String = "",
    val repairProposal: PersonaRepairProposalDto? = null,
    val repairError: String = "",
    val appliedRepairFields: Set<String> = emptySet(),
    val fieldFeedback: Map<String, PersonaFieldFeedback> = emptyMap(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val suggestingField: String? = null,
    val hasLoaded: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val deleted: Boolean = false,
    val loadError: String = "",
    val notice: PersonaNotice? = null,
) {
    val isBusy: Boolean
        get() = isLoading || isSaving || isDeleting || suggestingField != null

    fun issueFor(field: String): PersonaIssueDto? = quality?.issues
        ?.firstOrNull { field in it.fields }
}

class PersonaViewModel(
    private val repository: ZaomengRepository,
) : ViewModel() {
    private var noticeSequence = 0L
    private val _uiState = MutableStateFlow(PersonaUiState())
    val uiState: StateFlow<PersonaUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun load(runId: String, character: String) {
        val normalizedRunId = runId.trim()
        val normalizedCharacter = character.trim()
        if (normalizedRunId.isEmpty() || normalizedCharacter.isEmpty()) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    hasLoaded = false,
                    loadError = "缺少书卷或人物信息，暂时无法打开人物资料。",
                )
            }
            return
        }

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                PersonaUiState(
                    runId = normalizedRunId,
                    character = normalizedCharacter,
                    isLoading = true,
                )
            }
            try {
                val review = repository.getPersona(normalizedRunId, normalizedCharacter)
                val completeFields = completePersonaFields(review.fields)
                _uiState.update { current ->
                    if (!current.matches(normalizedRunId, normalizedCharacter)) return@update current
                    current.copy(
                        // Keep the route character as the stable API identity. The payload name is
                        // display data and can be normalized differently by imported packages.
                        character = normalizedCharacter,
                        fields = completeFields,
                        editableProfilePath = review.editableProfilePath,
                        generatedProfilePath = review.generatedProfilePath,
                        isLoading = false,
                        hasLoaded = true,
                        loadError = "",
                    )
                }
                refreshQuality(normalizedRunId, normalizedCharacter, announceFailure = false)
                refreshRepairProposal(normalizedRunId, normalizedCharacter)
            } catch (error: Throwable) {
                _uiState.update { current ->
                    if (!current.matches(normalizedRunId, normalizedCharacter)) return@update current
                    current.copy(
                        isLoading = false,
                        hasLoaded = false,
                        loadError = readableError(error, "人物资料载入失败，请稍后重试。"),
                    )
                }
            }
        }
    }

    fun updateField(field: String, value: String) {
        if (field !in PERSONA_FIELD_KEYS) return
        _uiState.update { current ->
            val nextFields = current.fields.toMutableMap().apply { put(field, value) }
            current.copy(
                fields = nextFields,
                fieldFeedback = current.fieldFeedback - field,
                hasUnsavedChanges = true,
            )
        }
    }

    fun updateReviewNote(value: String) {
        _uiState.update { it.copy(reviewNote = value, hasUnsavedChanges = true) }
    }

    fun applyRepairChange(change: PersonaRepairChangeDto) {
        val snapshot = _uiState.value
        val proposal = snapshot.repairProposal ?: return
        if (change.field !in PERSONA_FIELD_KEYS || change !in proposal.changes) return
        _uiState.update { current ->
            current.copy(
                fields = current.fields.toMutableMap().apply { put(change.field, change.after) },
                appliedRepairFields = current.appliedRepairFields + change.field,
                fieldFeedback = current.fieldFeedback + (
                    change.field to PersonaFieldFeedback(PersonaFeedbackKind.Success, "已应用有原文依据的修复建议，保存前仍可编辑。")
                    ),
                hasUnsavedChanges = true,
            )
        }
    }

    fun applyAllRepairChanges() {
        val proposal = _uiState.value.repairProposal ?: return
        proposal.changes.forEach(::applyRepairChange)
    }

    fun save() {
        val snapshot = _uiState.value
        if (!snapshot.hasLoaded || snapshot.isSaving || snapshot.runId.isBlank() || snapshot.character.isBlank()) return

        val runId = snapshot.runId
        val character = snapshot.character
        val completeFields = completePersonaFields(snapshot.fields)
        val reviewNote = snapshot.reviewNote.trim()
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val saved = repository.savePersona(
                    runId = runId,
                    character = character,
                    completeFields = completeFields,
                    reviewNote = reviewNote,
                )
                _uiState.update { current ->
                    if (!current.matches(runId, character)) return@update current
                    current.copy(
                        character = character,
                        fields = completePersonaFields(saved.fields),
                        editableProfilePath = saved.editableProfilePath,
                        generatedProfilePath = saved.generatedProfilePath,
                        reviewNote = "",
                        fieldFeedback = emptyMap(),
                        isSaving = false,
                        hasUnsavedChanges = false,
                        notice = notice("人物资料已保存，正在刷新质量报告。"),
                    )
                }
                refreshQuality(runId, character, announceFailure = true)
            } catch (error: Throwable) {
                _uiState.update { current ->
                    if (!current.matches(runId, character)) return@update current
                    current.copy(
                        isSaving = false,
                        notice = notice(readableError(error, "人物资料保存失败，请稍后重试。")),
                    )
                }
            }
        }
    }

    fun delete() {
        val snapshot = _uiState.value
        if (!snapshot.hasLoaded || snapshot.isDeleting || snapshot.isSaving || snapshot.suggestingField != null) return
        val runId = snapshot.runId
        val character = snapshot.character
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            try {
                repository.deletePersona(runId, character)
                _uiState.update { current ->
                    if (!current.matches(runId, character)) return@update current
                    current.copy(isDeleting = false, deleted = true)
                }
            } catch (error: Throwable) {
                _uiState.update { current ->
                    if (!current.matches(runId, character)) return@update current
                    current.copy(
                        isDeleting = false,
                        notice = notice(readableError(error, "人物删除失败，请稍后重试。")),
                    )
                }
            }
        }
    }

    fun suggestField(field: String) {
        val snapshot = _uiState.value
        val spec = PERSONA_FIELD_GROUPS.asSequence()
            .flatMap { it.fields.asSequence() }
            .firstOrNull { it.key == field }
        if (spec?.supportsSuggestion != true) {
            _uiState.update { it.copy(notice = notice("这个字段暂时不支持 AI 补全。")) }
            return
        }
        if (!snapshot.hasLoaded || snapshot.isSaving || snapshot.suggestingField != null) return

        val runId = snapshot.runId
        val character = snapshot.character
        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    suggestingField = field,
                    fieldFeedback = current.fieldFeedback + (
                        field to PersonaFieldFeedback(PersonaFeedbackKind.Loading, "正在生成补全内容…")
                        ),
                )
            }
            try {
                val result = repository.suggestPersonaField(runId, character, field)
                val filled = result.status == "filled" && result.value.isNotBlank()
                _uiState.update { current ->
                    if (!current.matches(runId, character)) return@update current
                    if (filled) {
                        current.copy(
                            fields = current.fields.toMutableMap().apply { put(field, result.value) },
                            suggestingField = null,
                            hasUnsavedChanges = true,
                            fieldFeedback = current.fieldFeedback + (
                                field to PersonaFieldFeedback(
                                    PersonaFeedbackKind.Success,
                                    result.message.ifBlank { "补全内容已填入，确认后请保存。" },
                                )
                                ),
                        )
                    } else {
                        val message = result.message.ifBlank {
                            result.reason.ifBlank { "现有资料不足，暂时无法可靠补全。" }
                        }
                        current.copy(
                            suggestingField = null,
                            fieldFeedback = current.fieldFeedback + (
                                field to PersonaFieldFeedback(PersonaFeedbackKind.Error, message)
                                ),
                            notice = notice(message),
                        )
                    }
                }
            } catch (error: Throwable) {
                val message = readableError(error, "AI 补全失败，请稍后重试或手动填写。")
                _uiState.update { current ->
                    if (!current.matches(runId, character)) return@update current
                    current.copy(
                        suggestingField = null,
                        fieldFeedback = current.fieldFeedback + (
                            field to PersonaFieldFeedback(PersonaFeedbackKind.Error, message)
                            ),
                        notice = notice(message),
                    )
                }
            }
        }
    }

    fun dismissNotice(id: Long) {
        _uiState.update { current ->
            if (current.notice?.id == id) current.copy(notice = null) else current
        }
    }

    private suspend fun refreshQuality(
        runId: String,
        character: String,
        announceFailure: Boolean,
    ) {
        try {
            val report = repository.getPersonaQuality(runId, character)
            _uiState.update { current ->
                if (!current.matches(runId, character)) return@update current
                current.copy(quality = report, qualityError = "")
            }
        } catch (error: Throwable) {
            val message = readableError(error, "质量报告暂时不可用。")
            _uiState.update { current ->
                if (!current.matches(runId, character)) return@update current
                current.copy(
                    qualityError = message,
                    notice = if (announceFailure) notice("资料已保存，但$message") else current.notice,
                )
            }
        }
    }

    private suspend fun refreshRepairProposal(runId: String, character: String) {
        try {
            val proposal = repository.getPersonaRepairProposal(runId, character)
            _uiState.update { current ->
                if (!current.matches(runId, character)) return@update current
                val alreadyApplied = proposal.changes.filter { change ->
                    current.fields[change.field].orEmpty().trim() == change.after.trim()
                }.map(PersonaRepairChangeDto::field).toSet()
                current.copy(
                    repairProposal = proposal,
                    repairError = "",
                    appliedRepairFields = alreadyApplied,
                )
            }
        } catch (error: Throwable) {
            _uiState.update { current ->
                if (!current.matches(runId, character)) return@update current
                current.copy(repairError = readableError(error, "自动修复建议暂时不可用。"))
            }
        }
    }

    private fun PersonaUiState.matches(runId: String, character: String): Boolean =
        this.runId == runId && this.character == character

    private fun notice(message: String): PersonaNotice = PersonaNotice(
        id = ++noticeSequence,
        message = message,
    )

    private fun readableError(error: Throwable, fallback: String): String =
        generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: fallback

}

private fun completePersonaFields(source: Map<String, String>): Map<String, String> =
    LinkedHashMap<String, String>().apply {
        PERSONA_FIELD_KEYS.forEach { key -> put(key, source[key].orEmpty()) }
        source.forEach { (key, value) -> if (key !in this) put(key, value) }
    }

val PERSONA_FIELD_GROUPS: List<PersonaFieldGroup> = listOf(
    PersonaFieldGroup(
        key = "identity",
        title = "身份与外在",
        description = "人物是谁、如何看待自己，以及别人第一眼如何认识他。",
        fields = listOf(
            PersonaFieldSpec("core_identity", "核心身份", true),
            PersonaFieldSpec("story_role", "故事位置", true),
            PersonaFieldSpec("identity_anchor", "身份锚点", true),
            PersonaFieldSpec("temperament_type", "气质底色", true),
            PersonaFieldSpec("gender", "性别", true),
            PersonaFieldSpec("age_stage", "年龄阶段", true),
            PersonaFieldSpec("appearance_feature", "外貌辨识", true),
            PersonaFieldSpec("habit_action", "习惯动作", true),
            PersonaFieldSpec("self_cognition", "自我认知", true),
            PersonaFieldSpec("private_self", "私下的一面", true),
            PersonaFieldSpec("others_impression", "他人观感", true),
        ),
    ),
    PersonaFieldGroup(
        key = "motivation",
        title = "动机与判断",
        description = "人物真正想要什么，以及在关键选择前遵循怎样的内在规则。",
        fields = listOf(
            PersonaFieldSpec("soul_goal", "灵魂目标", true),
            PersonaFieldSpec("hidden_desire", "隐秘渴望", true),
            PersonaFieldSpec("inner_conflict", "内在冲突", true),
            PersonaFieldSpec("thinking_style", "思考方式", true),
            PersonaFieldSpec("decision_rules", "决策规则"),
            PersonaFieldSpec("reward_logic", "回报逻辑"),
            PersonaFieldSpec("worldview", "世界观", true),
            PersonaFieldSpec("belief_anchor", "信念支点", true),
            PersonaFieldSpec("moral_bottom_line", "道德底线", true),
        ),
    ),
    PersonaFieldGroup(
        key = "voice",
        title = "对白声音",
        description = "约束人物说话的口气、节奏和反复出现的语言习惯。",
        fields = listOf(
            PersonaFieldSpec("speech_style", "说话方式", true),
            PersonaFieldSpec("cadence", "语句节奏"),
            PersonaFieldSpec("typical_lines", "代表句"),
            PersonaFieldSpec("signature_phrases", "口头禅"),
            PersonaFieldSpec("sentence_openers", "起句习惯"),
            PersonaFieldSpec("sentence_endings", "句尾习惯"),
        ),
    ),
    PersonaFieldGroup(
        key = "behavior",
        title = "关系、行为与情绪",
        description = "人物如何靠近或排斥他人，以及在压力与情绪中会变成什么样。",
        fields = listOf(
            PersonaFieldSpec("social_mode", "社交模式", true),
            PersonaFieldSpec("restraint_threshold", "失控阈值"),
            PersonaFieldSpec("core_traits", "核心特质", true),
            PersonaFieldSpec("key_bonds", "重要牵系", true),
            PersonaFieldSpec("preference_like", "偏好喜好", true),
            PersonaFieldSpec("dislike_hate", "明显厌恶", true),
            PersonaFieldSpec("forbidden_behaviors", "不会做的事"),
            PersonaFieldSpec("stress_response", "应激反应"),
            PersonaFieldSpec("emotion_model", "情绪底模"),
            PersonaFieldSpec("anger_style", "发怒方式"),
            PersonaFieldSpec("joy_style", "开心方式"),
            PersonaFieldSpec("grievance_style", "委屈方式"),
        ),
    ),
)

val PERSONA_FIELD_KEYS: List<String> = PERSONA_FIELD_GROUPS.flatMap { group ->
    group.fields.map(PersonaFieldSpec::key)
}
