package top.wkbin.zaomeng.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class PluginBuilderTemplate {
    @SerialName("chat_action")
    ChatAction,

    @SerialName("generation_enhancer")
    GenerationEnhancer,

    @SerialName("temporary_npc")
    TemporaryNpc,
}

@Serializable
enum class PluginBuilderActionMode {
    @SerialName("suggest")
    Suggest,

    @SerialName("variants")
    Variants,
}

@Serializable
enum class PluginBuilderSettingType {
    @SerialName("boolean")
    Boolean,

    @SerialName("integer")
    Integer,

    @SerialName("enum")
    Enum,
}

@Serializable
enum class PluginRuleEvent {
    @SerialName("before_generation")
    BeforeGeneration,

    @SerialName("after_turn")
    AfterTurn,
}

@Serializable
enum class PluginRuleActionType {
    @SerialName("add_instruction")
    AddInstruction,

    @SerialName("set_state")
    SetState,

    @SerialName("increment_state")
    IncrementState,
}

@Serializable
data class PluginRuleMatchDraft(
    val keywords: List<String> = emptyList(),
    val everyTurns: Int = 0,
    val chancePercent: Int = 100,
    val stateKey: String = "",
    val stateEquals: String = "",
)

@Serializable
data class PluginRuleActionDraft(
    val type: PluginRuleActionType = PluginRuleActionType.AddInstruction,
    val instruction: String = "",
    val key: String = "",
    val value: String = "",
    val amount: Int = 1,
)

@Serializable
data class PluginRuleDraft(
    val id: String = "",
    val title: String = "",
    val event: PluginRuleEvent = PluginRuleEvent.BeforeGeneration,
    val match: PluginRuleMatchDraft = PluginRuleMatchDraft(),
    val actions: List<PluginRuleActionDraft> = emptyList(),
)

@Serializable
data class PluginBuilderSettingDraft(
    val key: String = "",
    val title: String = "",
    val type: PluginBuilderSettingType = PluginBuilderSettingType.Enum,
    val defaultValue: String = "",
    val options: List<String> = emptyList(),
)

@Serializable
data class PluginDraft(
    val name: String = "",
    val id: String = "",
    val version: String = "0.1.0",
    val description: String = "",
    val template: PluginBuilderTemplate = PluginBuilderTemplate.ChatAction,
    val title: String = "",
    val prompt: String = "",
    val actionMode: PluginBuilderActionMode = PluginBuilderActionMode.Suggest,
    val settings: List<PluginBuilderSettingDraft> = emptyList(),
    val rules: List<PluginRuleDraft> = emptyList(),
)

@Serializable
data class ValidatePluginDraftRequest(val draft: PluginDraft)

@Serializable
data class PackagePluginDraftRequest(val draft: PluginDraft)

@Serializable
data class GeneratePluginDraftRequest(val description: String = "")

@Serializable
data class PluginBuilderIssueDto(
    val field: String = "",
    val message: String = "",
    val severity: String = "error",
)

@Serializable
data class PluginBuilderPermissionDto(
    val permission: String = "",
    val title: String = "",
    val reason: String = "",
)

@Serializable
data class PluginBuilderValidationDto(
    val valid: Boolean = false,
    val draft: PluginDraft = PluginDraft(),
    val permissions: List<PluginBuilderPermissionDto> = emptyList(),
    val issues: List<PluginBuilderIssueDto> = emptyList(),
    val manifest: JsonObject = JsonObject(emptyMap()),
    val manifestJson: String = "",
    val filename: String = "",
)

/** 根据用户可见名称生成声明式插件允许的稳定 id。 */
fun suggestPluginId(name: String): String = suggestAsciiIdentifier(name, separator = '-')
    .takeIf(String::isNotBlank)
    ?.take(64)
    ?: "plugin-${stableNameHash(name.ifBlank { "plugin" })}"

/** 设置 key 使用下划线，便于在 {{config.key}} 中阅读。 */
fun suggestPluginSettingKey(title: String): String = suggestAsciiIdentifier(title, separator = '_')
    .takeIf(String::isNotBlank)
    ?.take(48)
    ?: "option_${stableNameHash(title.ifBlank { "option" })}"

fun suggestPluginRuleId(title: String): String = suggestAsciiIdentifier(title, separator = '-')
    .takeIf(String::isNotBlank)
    ?.take(48)
    ?: "rule-${stableNameHash(title.ifBlank { "rule" })}"

private fun suggestAsciiIdentifier(value: String, separator: Char): String = buildString {
    var pendingSeparator = false
    value.trim().lowercase().forEach { char ->
        when {
            char in 'a'..'z' || char in '0'..'9' -> {
                if (pendingSeparator && isNotEmpty()) append(separator)
                append(char)
                pendingSeparator = false
            }
            else -> pendingSeparator = isNotEmpty()
        }
    }
}.trim(separator)

private fun stableNameHash(value: String): String {
    var hash = 0x811c9dc5.toInt()
    value.forEach { char -> hash = (hash xor char.code) * 16777619 }
    return hash.toUInt().toString(16).padStart(8, '0')
}
