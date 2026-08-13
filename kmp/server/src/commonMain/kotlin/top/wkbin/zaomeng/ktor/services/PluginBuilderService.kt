package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.PluginBuilderActionMode
import top.wkbin.zaomeng.data.api.PluginBuilderIssueDto
import top.wkbin.zaomeng.data.api.PluginBuilderPermissionDto
import top.wkbin.zaomeng.data.api.PluginBuilderSettingDraft
import top.wkbin.zaomeng.data.api.PluginBuilderSettingType
import top.wkbin.zaomeng.data.api.PluginBuilderTemplate
import top.wkbin.zaomeng.data.api.PluginBuilderValidationDto
import top.wkbin.zaomeng.data.api.PluginDraft
import top.wkbin.zaomeng.data.api.suggestPluginId
import top.wkbin.zaomeng.data.api.suggestPluginSettingKey
import top.wkbin.zaomeng.platform.ZipEntryData
import top.wkbin.zaomeng.platform.writeZipEntries

data class BuiltPluginPackage(val filename: String, val bytes: ByteArray)

/** 把面向普通用户的草稿转换成唯一、可校验、可安装的声明式插件清单。 */
class PluginBuilderService {
    private val json = Json { prettyPrint = true }

    fun validate(source: PluginDraft): PluginBuilderValidationDto {
        val draft = normalize(source)
        val issues = mutableListOf<PluginBuilderIssueDto>()
        validateBasics(draft, issues)
        validateSettings(draft.settings, issues)
        validateVariables(draft, issues)

        val permissions = permissionsFor(draft.template)
        val manifest = buildManifest(draft, permissions.map(PluginBuilderPermissionDto::permission))
        if (issues.none { it.severity == ERROR }) {
            val evaluation = DeclarativePluginLoader.evaluate(draft.id, manifest)
            if (!evaluation.executable || !evaluation.compatible) {
                issues += PluginBuilderIssueDto(
                    field = "runtime",
                    message = evaluation.capabilityNotice.ifBlank { "当前声明式运行时无法执行这个插件。" },
                )
            }
        }
        val manifestJson = json.encodeToString(JsonObject.serializer(), manifest)
        return PluginBuilderValidationDto(
            valid = issues.none { it.severity == ERROR },
            draft = draft,
            permissions = permissions,
            issues = issues,
            manifest = manifest,
            manifestJson = manifestJson,
            filename = packageFilename(draft),
        )
    }

    fun packagePlugin(source: PluginDraft): BuiltPluginPackage {
        val validation = validate(source)
        require(validation.valid) {
            validation.issues.filter { it.severity == ERROR }.joinToString("；") { it.message }
                .ifBlank { "插件草稿未通过校验。" }
        }
        val readme = buildString {
            appendLine("# ${validation.draft.name}")
            appendLine()
            appendLine(validation.draft.description.ifBlank { "由造梦插件工坊生成的声明式插件。" })
            appendLine()
            appendLine("- 插件 ID：`${validation.draft.id}`")
            appendLine("- 版本：`${validation.draft.version}`")
            appendLine("- Plugin API：`2`")
            appendLine("- 执行方式：由造梦声明式 Kotlin 宿主执行，不包含任意代码。")
        }
        val entries = mutableListOf(
            ZipEntryData("plugin.json", validation.manifestJson.encodeToByteArray()),
            ZipEntryData("README.md", readme.encodeToByteArray()),
        )
        if (validation.draft.settings.isNotEmpty()) {
            val defaults = buildJsonObject {
                validation.draft.settings.forEach { setting ->
                    put(setting.key, setting.defaultJsonValue())
                }
            }
            entries += ZipEntryData(
                "config.json",
                json.encodeToString(JsonObject.serializer(), defaults).encodeToByteArray(),
            )
        }
        return BuiltPluginPackage(
            filename = validation.filename,
            bytes = writeZipEntries(entries),
        )
    }

    private fun normalize(source: PluginDraft): PluginDraft {
        val name = source.name.trim()
        return source.copy(
            name = name,
            id = source.id.trim().ifBlank { suggestPluginId(name) },
            version = source.version.trim(),
            description = source.description.trim(),
            title = source.title.trim().ifBlank { name },
            prompt = source.prompt.trim(),
            settings = source.settings.map { setting ->
                val title = setting.title.trim()
                setting.copy(
                    title = title,
                    key = setting.key.trim().ifBlank { suggestPluginSettingKey(title) },
                    defaultValue = setting.defaultValue.trim(),
                    options = setting.options.map(String::trim).filter(String::isNotBlank).distinct(),
                )
            },
        )
    }

    private fun validateBasics(draft: PluginDraft, issues: MutableList<PluginBuilderIssueDto>) {
        if (draft.name.isBlank()) issues.error("name", "请填写插件名称。")
        if (draft.name.length > 80) issues.error("name", "插件名称不能超过 80 个字符。")
        if (!PathSafety.STORAGE_ID_PATTERN.matches(draft.id)) {
            issues.error("id", "插件 ID 只能包含英文字母、数字、连字符和下划线。")
        }
        if (!SEMVER.matches(draft.version)) issues.error("version", "版本号请使用 0.1.0 这样的格式。")
        if (draft.title.isBlank()) issues.error("title", "请填写插件在聊天中的显示名称。")
        if (draft.title.length > 48) issues.error("title", "显示名称不能超过 48 个字符。")
        if (draft.prompt.isBlank()) issues.error("prompt", "请用自然语言描述插件要完成的任务。")
        if (draft.prompt.length > 8_000) issues.error("prompt", "提示词不能超过 8000 个字符。")
    }

    private fun validateSettings(
        settings: List<PluginBuilderSettingDraft>,
        issues: MutableList<PluginBuilderIssueDto>,
    ) {
        if (settings.size > MAX_SETTINGS) issues.error("settings", "一个插件最多可以添加 $MAX_SETTINGS 个设置项。")
        val duplicateKeys = settings.groupingBy(PluginBuilderSettingDraft::key).eachCount().filterValues { it > 1 }.keys
        settings.forEachIndexed { index, setting ->
            val field = "settings.$index"
            if (setting.title.isBlank()) issues.error("$field.title", "设置项需要一个用户可见名称。")
            if (setting.title.length > 48) issues.error("$field.title", "设置名称不能超过 48 个字符。")
            if (!SETTING_KEY.matches(setting.key)) {
                issues.error("$field.key", "设置 key 必须以英文字母开头，只能包含字母、数字和下划线。")
            }
            if (setting.key in duplicateKeys) issues.error("$field.key", "设置 key「${setting.key}」重复。")
            when (setting.type) {
                PluginBuilderSettingType.Boolean -> if (setting.defaultValue !in setOf("true", "false")) {
                    issues.error("$field.defaultValue", "开关默认值必须是 true 或 false。")
                }
                PluginBuilderSettingType.Integer -> {
                    val default = setting.defaultValue.toIntOrNull()
                    if (default == null) issues.error("$field.defaultValue", "数字默认值必须是整数。")
                    else if (default !in MIN_INTEGER_SETTING..MAX_INTEGER_SETTING) {
                        issues.error("$field.defaultValue", "数字默认值必须在 $MIN_INTEGER_SETTING 到 $MAX_INTEGER_SETTING 之间。")
                    }
                }
                PluginBuilderSettingType.Enum -> {
                    if (setting.options.size < 2) issues.error("$field.options", "选项设置至少需要两个不同选项。")
                    if (setting.options.size > MAX_ENUM_OPTIONS) {
                        issues.error("$field.options", "一个设置项最多可以有 $MAX_ENUM_OPTIONS 个选项。")
                    }
                    if (setting.options.any { it.length > 48 }) {
                        issues.error("$field.options", "每个选项不能超过 48 个字符。")
                    }
                    if (setting.defaultValue !in setting.options) {
                        issues.error("$field.defaultValue", "默认选项必须存在于候选项中。")
                    }
                }
            }
        }
    }

    private fun validateVariables(draft: PluginDraft, issues: MutableList<PluginBuilderIssueDto>) {
        val settingKeys = draft.settings.map(PluginBuilderSettingDraft::key).toSet()
        PLACEHOLDER.findAll(draft.prompt).forEach { match ->
            val variable = match.groupValues[1].trim()
            when {
                variable == "seed_text" && draft.template != PluginBuilderTemplate.ChatAction ->
                    issues.error("prompt", "{{seed_text}} 只适用于聊天快捷动作。")
                variable == "seed_text" -> Unit
                variable.startsWith("config.") -> {
                    val key = variable.removePrefix("config.")
                    if (key !in settingKeys) issues.error("prompt", "变量 {{config.$key}} 没有对应的设置项。")
                }
                else -> issues.error("prompt", "不支持变量 {{$variable}}。请使用页面提供的变量标签。")
            }
        }
        val unmatchedStart = draft.prompt.countOccurrences("{{")
        val matched = PLACEHOLDER.findAll(draft.prompt).count()
        if (unmatchedStart > matched) issues.error("prompt", "提示词中存在未闭合的变量标记 {{...}}。")
    }

    private fun permissionsFor(template: PluginBuilderTemplate): List<PluginBuilderPermissionDto> = when (template) {
        PluginBuilderTemplate.ChatAction -> listOf(
            permission("chat.context.read", "读取聊天上下文", "需要理解当前场景、人物关系和最近对话。"),
            permission("chat.draft.write", "写入回复草稿", "生成内容会先放入输入框，由用户确认后发送。"),
            permission("model.invoke", "调用当前模型", "使用用户已经配置的模型完成提示词任务。"),
        )
        PluginBuilderTemplate.GenerationEnhancer -> listOf(
            permission("chat.context.read", "读取聊天上下文", "增强规则需要应用到当前会话。"),
            permission("generation.enhance", "增强生成规则", "允许把这条规则加入当前会话的生成指令。"),
            permission("model.invoke", "调用当前模型", "增强规则会在模型生成时生效。"),
        )
        PluginBuilderTemplate.TemporaryNpc -> listOf(
            permission("chat.context.read", "读取聊天上下文", "需要根据当前场景生成合适的临时人物。"),
            permission("chat.cast.write", "修改当前出场角色", "把生成的临时 NPC 加入当前会话。"),
            permission("model.invoke", "调用当前模型", "使用用户已经配置的模型生成人物。"),
        )
    }

    private fun buildManifest(draft: PluginDraft, permissions: List<String>): JsonObject = buildJsonObject {
        put("id", draft.id)
        put("name", draft.name)
        put("version", draft.version)
        put("apiVersion", "2")
        put("description", draft.description)
        put("defaultEnabled", false)
        put("permissions", buildJsonArray { permissions.forEach { add(JsonPrimitive(it)) } })
        put("settings", buildJsonArray { draft.settings.forEach { add(settingJson(it)) } })
        put("contributes", contributionsJson(draft))
        put("execution", executionJson(draft))
    }

    private fun settingJson(setting: PluginBuilderSettingDraft): JsonObject = buildJsonObject {
        put("key", setting.key)
        put("title", setting.title)
        put("type", setting.type.wireName())
        put("default", setting.defaultJsonValue())
        if (setting.type == PluginBuilderSettingType.Integer) {
            put("min", MIN_INTEGER_SETTING)
            put("max", MAX_INTEGER_SETTING)
        }
        if (setting.type == PluginBuilderSettingType.Enum) {
            put("options", buildJsonArray {
                setting.options.forEach { option ->
                    add(buildJsonObject {
                        put("value", option)
                        put("label", option)
                    })
                }
            })
        }
    }

    private fun contributionsJson(draft: PluginDraft): JsonObject = buildJsonObject {
        when (draft.template) {
            PluginBuilderTemplate.ChatAction -> put("chatActions", buildJsonArray {
                add(buildJsonObject {
                    put("id", MAIN_ACTION_ID)
                    put("title", draft.title)
                    put("placement", "composer")
                    put("icon", "sparkles")
                })
            })
            PluginBuilderTemplate.GenerationEnhancer -> put("generationEnhancers", buildJsonArray {
                add(buildJsonObject {
                    put("id", MAIN_ENHANCER_ID)
                    put("title", draft.title)
                    put("description", draft.description)
                    put("icon", "auto_awesome")
                    put("defaultActive", false)
                })
            })
            PluginBuilderTemplate.TemporaryNpc -> put("temporaryNpcGenerators", buildJsonArray {
                add(buildJsonObject {
                    put("id", MAIN_NPC_ID)
                    put("title", draft.title)
                    put("icon", "person_add")
                })
            })
        }
    }

    private fun executionJson(draft: PluginDraft): JsonObject = buildJsonObject {
        put("mode", "declarative")
        when (draft.template) {
            PluginBuilderTemplate.ChatAction -> put("chatActions", buildJsonObject {
                put(MAIN_ACTION_ID, buildJsonObject {
                    put("operation", if (draft.actionMode == PluginBuilderActionMode.Variants) "variants" else "suggest")
                    put("direction", draft.prompt)
                    put("empty_notice", "插件没有生成可用内容，请调整提示词后重试。")
                })
            })
            PluginBuilderTemplate.GenerationEnhancer -> put("generationEnhancers", buildJsonObject {
                put(MAIN_ENHANCER_ID, buildJsonObject { put("rule", draft.prompt) })
            })
            PluginBuilderTemplate.TemporaryNpc -> put("temporaryNpcGenerators", buildJsonObject {
                put(MAIN_NPC_ID, buildJsonObject {
                    put("direction", draft.prompt)
                    put("notice", "临时 NPC 已加入当前场景。")
                })
            })
        }
    }

    private fun packageFilename(draft: PluginDraft): String {
        val safeName = draft.name.replace(UNSAFE_FILENAME, "-").trim(' ', '.', '-')
            .ifBlank { draft.id }
            .take(80)
        return "$safeName-${draft.version}.zaomeng-plugin.zip"
    }

    private fun permission(permission: String, title: String, reason: String) =
        PluginBuilderPermissionDto(permission, title, reason)

    private fun MutableList<PluginBuilderIssueDto>.error(field: String, message: String) {
        if (none { it.field == field && it.message == message }) add(PluginBuilderIssueDto(field, message, ERROR))
    }

    private fun PluginBuilderSettingType.wireName(): String = when (this) {
        PluginBuilderSettingType.Boolean -> "boolean"
        PluginBuilderSettingType.Integer -> "integer"
        PluginBuilderSettingType.Enum -> "enum"
    }

    private fun PluginBuilderSettingDraft.defaultJsonValue(): JsonElement = when (type) {
        PluginBuilderSettingType.Boolean -> JsonPrimitive(defaultValue.toBooleanStrictOrNull() ?: false)
        PluginBuilderSettingType.Integer -> JsonPrimitive(defaultValue.toIntOrNull() ?: 0)
        PluginBuilderSettingType.Enum -> JsonPrimitive(defaultValue)
    }

    private fun String.countOccurrences(value: String): Int {
        if (value.isEmpty()) return 0
        var count = 0
        var start = 0
        while (true) {
            val found = indexOf(value, start)
            if (found < 0) return count
            count++
            start = found + value.length
        }
    }

    private companion object {
        const val ERROR = "error"
        const val MAIN_ACTION_ID = "main-action"
        const val MAIN_ENHANCER_ID = "main-enhancer"
        const val MAIN_NPC_ID = "main-npc"
        const val MAX_SETTINGS = 8
        const val MAX_ENUM_OPTIONS = 12
        const val MIN_INTEGER_SETTING = 0
        const val MAX_INTEGER_SETTING = 100
        val SEMVER = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
        val SETTING_KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,47}$")
        val PLACEHOLDER = Regex("\\{\\{\\s*([^}]+?)\\s*}}")
        val UNSAFE_FILENAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    }
}
