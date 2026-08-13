package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
import top.wkbin.zaomeng.data.api.PluginRuleActionDraft
import top.wkbin.zaomeng.data.api.PluginRuleActionType
import top.wkbin.zaomeng.data.api.PluginRuleDraft
import top.wkbin.zaomeng.data.api.PluginRuleEvent
import top.wkbin.zaomeng.data.api.PluginBuilderSettingDraft
import top.wkbin.zaomeng.data.api.PluginBuilderSettingType
import top.wkbin.zaomeng.data.api.PluginBuilderTemplate
import top.wkbin.zaomeng.data.api.PluginBuilderValidationDto
import top.wkbin.zaomeng.data.api.PluginDraft
import top.wkbin.zaomeng.data.api.suggestPluginId
import top.wkbin.zaomeng.data.api.suggestPluginRuleId
import top.wkbin.zaomeng.data.api.suggestPluginSettingKey
import top.wkbin.zaomeng.platform.ZipEntryData
import top.wkbin.zaomeng.platform.writeZipEntries

data class BuiltPluginPackage(val filename: String, val bytes: ByteArray)

/** 把面向普通用户的草稿转换成唯一、可校验、可安装的声明式插件清单。 */
class PluginBuilderService(
    private val draftCompletion: (suspend (List<LlmClient.ChatMessage>) -> String)? = null,
) {
    private val json = Json { prettyPrint = true }
    private val generatedDraftJson = Json { ignoreUnknownKeys = false; isLenient = false }

    suspend fun generate(description: String): PluginBuilderValidationDto {
        val requirement = description.trim()
        require(requirement.isNotBlank()) { "请先用一句话描述想制作的插件。" }
        require(requirement.length <= MAX_GENERATION_DESCRIPTION) { "插件需求不能超过 $MAX_GENERATION_DESCRIPTION 个字符。" }
        val complete = draftCompletion ?: error("请先在模型设置中配置并启用一个可用模型。")
        var feedback = ""
        var lastError = "模型没有生成可用的插件草稿。"
        repeat(MAX_GENERATION_ATTEMPTS) {
            val raw = complete(generationMessages(requirement, feedback))
            val draft = runCatching { decodeGeneratedDraft(raw) }.getOrElse { error ->
                lastError = "模型返回的不是有效插件 JSON：${error.message.orEmpty().take(160)}"
                feedback = lastError
                return@repeat
            }
            val validation = validate(
                draft.copy(
                    id = suggestPluginId(draft.name),
                    version = "0.1.0",
                ),
            )
            if (validation.valid) return validation
            lastError = validation.issues.joinToString("；") { it.message }.take(600)
            feedback = "上一次草稿未通过校验：$lastError。请完整重做 JSON。"
        }
        throw IllegalStateException(lastError)
    }

    fun validate(source: PluginDraft): PluginBuilderValidationDto {
        val draft = normalize(source)
        val issues = mutableListOf<PluginBuilderIssueDto>()
        validateBasics(draft, issues)
        validateSettings(draft.settings, issues)
        validateVariables(draft, issues)
        validateRules(draft, issues)

        val permissions = permissionsFor(draft)
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
            rules = source.rules.map { rule ->
                val title = rule.title.trim()
                rule.copy(
                    id = rule.id.trim().ifBlank { suggestPluginRuleId(title) },
                    title = title,
                    match = rule.match.copy(
                        keywords = rule.match.keywords.map(String::trim).filter(String::isNotBlank).distinct(),
                        stateKey = rule.match.stateKey.trim(),
                        stateEquals = rule.match.stateEquals.trim(),
                    ),
                    actions = rule.actions.map { action ->
                        action.copy(
                            instruction = action.instruction.trim(),
                            key = action.key.trim(),
                            value = action.value.trim(),
                        )
                    },
                )
            },
        )
    }

    private fun generationMessages(requirement: String, feedback: String): List<LlmClient.ChatMessage> {
        val system = """
            你是造梦 Plugin API 2 的插件设计助手。把普通用户的一句话需求转换成一个安全、声明式、无需代码的插件草稿。

            只输出一个 JSON 对象，不允许 Markdown 围栏、解释、前后缀文字或额外字段。JSON 必须包含：
            {
              "name": "2-20 字的中文插件名",
              "id": "留空字符串",
              "version": "0.1.0",
              "description": "一句话说明用途",
              "template": "chat_action | generation_enhancer | temporary_npc",
              "title": "聊天界面显示名",
              "prompt": "给模型的完整自然语言指令",
              "actionMode": "suggest | variants",
              "settings": [
                {"key":"英文标识","title":"中文名","type":"boolean | integer | enum","defaultValue":"字符串","options":[]}
              ],
              "rules": [
                {
                  "id":"英文规则标识",
                  "title":"规则名称",
                  "event":"before_generation | after_turn",
                  "match":{"keywords":[],"everyTurns":0,"chancePercent":100,"stateKey":"","stateEquals":""},
                  "actions":[
                    {"type":"add_instruction | set_state | increment_state","instruction":"","key":"","value":"","amount":1}
                  ]
                }
              ]
            }

            简单的按钮、持续增强或临时人物可继续使用 template；涉及回合数、概率、关键词、状态变化或多个连续效果时必须生成 rules，可同时包含多条规则和动作链。
            before_generation 发生在本轮模型生成前，适合 add_instruction；after_turn 发生在回复保存后，适合 set_state 和 increment_state。
            match 中多个非空条件必须同时满足。keywords 表示用户本轮文本包含任一关键词；everyTurns 为 0 或 2-100；chancePercent 为 1-100；stateKey/stateEquals 必须成对出现。
            add_instruction 只允许用于 before_generation 且 instruction 非空；set_state 需要 key/value；increment_state 需要 key，amount 范围 -100..100 且不能为 0。
            规则最多 8 条，每条最多 6 个动作。若 rules 非空，template 仍选择最接近的主要交互；没有额外按钮需求时优先用 generation_enhancer。
            chat_action 的 prompt 可使用 {{seed_text}}；任何模板都可使用已声明设置对应的 {{config.key}}。不得使用其他变量。
            设置最多 4 项，只在确有必要时添加。boolean 默认值只能是 "true" 或 "false"；integer 默认值为 0-100；enum 至少两个 options，defaultValue 必须在 options 中。
            prompt 不得要求执行代码、访问文件、绕过权限或泄露密钥。不要声称插件拥有模板之外的能力。
        """.trimIndent()
        val user = buildString {
            append("用户需求：")
            append(requirement)
            if (feedback.isNotBlank()) {
                append("\n\n")
                append(feedback)
            }
        }
        return listOf(
            LlmClient.ChatMessage(role = "system", content = system),
            LlmClient.ChatMessage(role = "user", content = user),
        )
    }

    private fun decodeGeneratedDraft(raw: String): PluginDraft {
        val trimmed = raw.trim().take(MAX_GENERATED_RESPONSE)
        val withoutFence = trimmed
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = withoutFence.indexOf('{')
        val end = withoutFence.lastIndexOf('}')
        require(start >= 0 && end >= start) { "响应中没有 JSON 对象" }
        return generatedDraftJson.decodeFromString(withoutFence.substring(start, end + 1))
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

    private fun validateRules(draft: PluginDraft, issues: MutableList<PluginBuilderIssueDto>) {
        val rules = draft.rules
        val settingKeys = draft.settings.map(PluginBuilderSettingDraft::key).toSet()
        if (rules.size > MAX_RULES) issues.error("rules", "一个插件最多可以包含 $MAX_RULES 条玩法规则。")
        val duplicateIds = rules.groupingBy(PluginRuleDraft::id).eachCount().filterValues { it > 1 }.keys
        rules.forEachIndexed { index, rule ->
            val field = "rules.$index"
            if (!RULE_ID.matches(rule.id)) issues.error("$field.id", "规则 ID 只能包含英文字母、数字、连字符和下划线。")
            if (rule.id in duplicateIds) issues.error("$field.id", "规则 ID「${rule.id}」重复。")
            if (rule.title.isBlank()) issues.error("$field.title", "规则需要一个名称。")
            if (rule.title.length > 64) issues.error("$field.title", "规则名称不能超过 64 个字符。")
            if (rule.match.keywords.size > MAX_RULE_KEYWORDS) issues.error("$field.match.keywords", "每条规则最多 $MAX_RULE_KEYWORDS 个关键词。")
            if (rule.match.keywords.any { it.length > 48 }) issues.error("$field.match.keywords", "单个关键词不能超过 48 个字符。")
            if (rule.match.everyTurns != 0 && rule.match.everyTurns !in 2..100) {
                issues.error("$field.match.everyTurns", "回合间隔必须为 0 或 2 到 100。")
            }
            if (rule.match.chancePercent !in 1..100) issues.error("$field.match.chancePercent", "触发概率必须为 1% 到 100%。")
            if (rule.match.stateKey.isBlank() != rule.match.stateEquals.isBlank()) {
                issues.error("$field.match.state", "状态条件的 key 和期望值必须同时填写。")
            }
            if (rule.match.stateKey.isNotBlank() && !STATE_KEY.matches(rule.match.stateKey)) {
                issues.error("$field.match.stateKey", "状态 key 必须以英文字母开头，只能包含字母、数字和下划线。")
            }
            if (rule.actions.isEmpty()) issues.error("$field.actions", "规则至少需要一个动作。")
            if (rule.actions.size > MAX_RULE_ACTIONS) issues.error("$field.actions", "每条规则最多 $MAX_RULE_ACTIONS 个动作。")
            rule.actions.forEachIndexed { actionIndex, action ->
                val actionField = "$field.actions.$actionIndex"
                when (action.type) {
                    PluginRuleActionType.AddInstruction -> {
                        if (rule.event != PluginRuleEvent.BeforeGeneration) {
                            issues.error("$actionField.type", "追加生成指令只能用于生成前事件。")
                        }
                        if (action.instruction.isBlank()) issues.error("$actionField.instruction", "追加生成指令不能为空。")
                        if (action.instruction.length > MAX_RULE_INSTRUCTION) {
                            issues.error("$actionField.instruction", "单条生成指令不能超过 $MAX_RULE_INSTRUCTION 个字符。")
                        }
                        validateRuleTemplate(action.instruction, "$actionField.instruction", settingKeys, issues)
                    }
                    PluginRuleActionType.SetState -> {
                        if (rule.event != PluginRuleEvent.AfterTurn) {
                            issues.error("$actionField.type", "记录状态只能用于回合结束后事件。")
                        }
                        validateStateAction(action, actionField, issues)
                        if (action.value.length > 120) issues.error("$actionField.value", "状态值不能超过 120 个字符。")
                        validateRuleTemplate(action.value, "$actionField.value", settingKeys, issues)
                    }
                    PluginRuleActionType.IncrementState -> {
                        if (rule.event != PluginRuleEvent.AfterTurn) {
                            issues.error("$actionField.type", "增减状态只能用于回合结束后事件。")
                        }
                        validateStateAction(action, actionField, issues)
                        if (action.amount == 0 || action.amount !in -100..100) {
                            issues.error("$actionField.amount", "状态增量必须在 -100 到 100 之间且不能为 0。")
                        }
                    }
                }
            }
        }
    }

    private fun validateRuleTemplate(
        template: String,
        field: String,
        settingKeys: Set<String>,
        issues: MutableList<PluginBuilderIssueDto>,
    ) {
        PLACEHOLDER.findAll(template).forEach { match ->
            val variable = match.groupValues[1].trim()
            when {
                variable == "message" -> Unit
                variable.startsWith("config.") && variable.removePrefix("config.") in settingKeys -> Unit
                variable.startsWith("state.") && STATE_KEY.matches(variable.removePrefix("state.")) -> Unit
                else -> issues.error(field, "规则模板不支持变量 {{$variable}}。")
            }
        }
        if (template.countOccurrences("{{") > PLACEHOLDER.findAll(template).count()) {
            issues.error(field, "规则模板中存在未闭合的变量标记 {{...}}。")
        }
    }

    private fun validateStateAction(
        action: PluginRuleActionDraft,
        field: String,
        issues: MutableList<PluginBuilderIssueDto>,
    ) {
        if (!STATE_KEY.matches(action.key)) issues.error("$field.key", "状态 key 必须以英文字母开头，只能包含字母、数字和下划线。")
        if (action.type == PluginRuleActionType.SetState && action.value.isBlank()) issues.error("$field.value", "写入的状态值不能为空。")
    }

    private fun permissionsFor(draft: PluginDraft): List<PluginBuilderPermissionDto> {
        val permissions = when (draft.template) {
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
        }.toMutableList()
        if (draft.rules.any { rule -> rule.actions.any { it.type == PluginRuleActionType.AddInstruction } }) {
            permissions += permission("generation.enhance", "按规则影响生成", "满足玩法条件时，把对应指令加入本轮生成。")
        }
        if (draft.rules.any { rule -> rule.actions.any { it.type != PluginRuleActionType.AddInstruction } }) {
            permissions += permission("chat.state.write", "记录会话玩法状态", "在当前会话内记录计数或状态，不影响其他会话。")
        }
        return permissions.distinctBy(PluginBuilderPermissionDto::permission)
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
        if (draft.rules.isNotEmpty()) put("rules", buildJsonArray {
            draft.rules.forEach { rule -> add(ruleJson(rule)) }
        })
    }

    private fun ruleJson(rule: PluginRuleDraft): JsonObject = buildJsonObject {
        put("id", rule.id)
        put("title", rule.title)
        put("event", when (rule.event) {
            PluginRuleEvent.BeforeGeneration -> "before_generation"
            PluginRuleEvent.AfterTurn -> "after_turn"
        })
        put("match", buildJsonObject {
            if (rule.match.keywords.isNotEmpty()) put("keywords", buildJsonArray {
                rule.match.keywords.forEach { add(JsonPrimitive(it)) }
            })
            if (rule.match.everyTurns > 0) put("everyTurns", rule.match.everyTurns)
            if (rule.match.chancePercent < 100) put("chancePercent", rule.match.chancePercent)
            if (rule.match.stateKey.isNotBlank()) {
                put("stateKey", rule.match.stateKey)
                put("stateEquals", rule.match.stateEquals)
            }
        })
        put("actions", buildJsonArray {
            rule.actions.forEach { action ->
                add(buildJsonObject {
                    put("type", JsonPrimitive(when (action.type) {
                        PluginRuleActionType.AddInstruction -> "add_instruction"
                        PluginRuleActionType.SetState -> "set_state"
                        PluginRuleActionType.IncrementState -> "increment_state"
                    }))
                    if (action.instruction.isNotBlank()) put("instruction", action.instruction)
                    if (action.key.isNotBlank()) put("key", action.key)
                    if (action.value.isNotBlank()) put("value", action.value)
                    if (action.type == PluginRuleActionType.IncrementState) put("amount", action.amount)
                })
            }
        })
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
        const val MAX_GENERATION_DESCRIPTION = 1_000
        const val MAX_GENERATED_RESPONSE = 24_000
        const val MAX_GENERATION_ATTEMPTS = 2
        const val MAX_RULES = 8
        const val MAX_RULE_ACTIONS = 6
        const val MAX_RULE_KEYWORDS = 12
        const val MAX_RULE_INSTRUCTION = 2_000
        val SEMVER = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
        val SETTING_KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,47}$")
        val STATE_KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,47}$")
        val RULE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,47}$")
        // Android's ICU regex engine treats an unescaped `}` as a syntax error.
        // Escape both template delimiters so constructing the service cannot
        // fail during class initialization on Android release builds.
        val PLACEHOLDER = Regex("\\{\\{\\s*([^}]+?)\\s*\\}\\}")
        val UNSAFE_FILENAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    }
}
