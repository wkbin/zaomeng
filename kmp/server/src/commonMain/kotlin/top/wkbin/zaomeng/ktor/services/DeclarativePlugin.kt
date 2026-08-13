package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.plugins.api.ChatActionDescriptor
import top.wkbin.zaomeng.plugins.api.ChatActionRequest
import top.wkbin.zaomeng.plugins.api.ChatActionResult
import top.wkbin.zaomeng.plugins.api.GenerationEnhancerDescriptor
import top.wkbin.zaomeng.plugins.api.NpcGeneratorRequest
import top.wkbin.zaomeng.plugins.api.NpcGeneratorResult
import top.wkbin.zaomeng.plugins.api.Plugin
import top.wkbin.zaomeng.plugins.api.PluginContributions
import top.wkbin.zaomeng.plugins.api.PluginHost
import top.wkbin.zaomeng.plugins.api.PluginManifest
import top.wkbin.zaomeng.plugins.api.PluginSettingDescriptor
import top.wkbin.zaomeng.plugins.api.TemporaryNpcGeneratorDescriptor

internal class DeclarativePlugin(
    override val manifest: PluginManifest,
    private val chatRecipes: Map<String, DeclarativeChatRecipe>,
    private val npcRecipes: Map<String, DeclarativeNpcRecipe>,
) : Plugin {
    override suspend fun executeChatAction(
        actionId: String,
        request: ChatActionRequest,
        host: PluginHost,
    ): ChatActionResult {
        val recipe = chatRecipes[actionId] ?: return ChatActionResult()
        val rendered = resolveTemplate(
            template = recipe.direction,
            seedText = request.seedText,
            direction = request.direction,
            config = request.config,
            host = host,
            pluginId = manifest.id,
        )
        return when (recipe.operation) {
            "variants" -> {
                val options = host.invokeVariants(request.runId, request.sessionId, request.seedText, rendered)
                if (options.isEmpty()) {
                    ChatActionResult(notice = recipe.emptyNotice)
                } else {
                    ChatActionResult(suggestions = options)
                }
            }
            "storage_get" -> {
                val key = renderTemplate(recipe.key, request.seedText, request.direction, request.config)
                ChatActionResult(suggestion = host.readPluginData(manifest.id, key).orEmpty())
            }
            "storage_set" -> {
                val key = renderTemplate(recipe.key, request.seedText, request.direction, request.config)
                val value = resolveTemplate(
                    template = recipe.valueTemplate,
                    seedText = request.seedText,
                    direction = request.direction,
                    config = request.config,
                    host = host,
                    pluginId = manifest.id,
                )
                host.writePluginData(manifest.id, key, value)
                ChatActionResult(notice = recipe.emptyNotice.ifBlank { "已保存插件数据。" })
            }
            "http_get", "http_post" -> {
                val url = resolveTemplate(
                    template = recipe.urlTemplate,
                    seedText = request.seedText,
                    direction = request.direction,
                    config = request.config,
                    host = host,
                    pluginId = manifest.id,
                )
                val body = resolveTemplate(
                    template = recipe.bodyTemplate,
                    seedText = request.seedText,
                    direction = request.direction,
                    config = request.config,
                    host = host,
                    pluginId = manifest.id,
                )
                val headers = mutableMapOf<String, String>()
                for ((key, value) in recipe.headers) {
                    headers[key] = resolveTemplate(
                        template = value,
                        seedText = request.seedText,
                        direction = request.direction,
                        config = request.config,
                        host = host,
                        pluginId = manifest.id,
                    )
                }
                val method = if (recipe.operation == "http_get") "GET" else "POST"
                val response = host.invokeHttp(method, url, headers, body).orEmpty()
                if (response.isBlank()) {
                    ChatActionResult(notice = recipe.emptyNotice)
                } else {
                    ChatActionResult(suggestion = response)
                }
            }
            "reply_as_character" -> {
                val reply = host.invokeReplyAsCharacter(
                    runId = request.runId,
                    sessionId = request.sessionId,
                    seedText = request.seedText,
                    direction = rendered,
                )
                if (reply == null) {
                    ChatActionResult(notice = recipe.emptyNotice)
                } else {
                    ChatActionResult(suggestion = reply.text, character = reply.character)
                }
            }
            "mute_character", "unmute_character" -> {
                val character = resolveTemplate(
                    template = recipe.characterTemplate,
                    seedText = request.seedText,
                    direction = request.direction,
                    config = request.config,
                    host = host,
                    pluginId = manifest.id,
                )
                val muted = recipe.operation == "mute_character"
                val session = host.setSessionCharacterMuted(
                    runId = request.runId,
                    sessionId = request.sessionId,
                    character = character,
                    muted = muted,
                ) ?: JsonObject(emptyMap())
                ChatActionResult(
                    session = session,
                    notice = recipe.emptyNotice.ifBlank {
                        if (muted) "「$character」已被禁言。" else "「$character」已恢复发言。"
                    },
                )
            }
            else -> {
                val suggestion = host.invokeSuggestion(request.runId, request.sessionId, request.seedText, rendered)
                if (suggestion.isBlank()) {
                    ChatActionResult(notice = recipe.emptyNotice)
                } else {
                    ChatActionResult(suggestion = suggestion)
                }
            }
        }
    }

    override suspend fun generateTemporaryNpc(
        generatorId: String,
        request: NpcGeneratorRequest,
        host: PluginHost,
    ): NpcGeneratorResult {
        val recipe = npcRecipes[generatorId] ?: return NpcGeneratorResult(npc = JsonObject(emptyMap()))
        val rendered = resolveTemplate(
            template = recipe.direction,
            seedText = "",
            direction = request.direction,
            config = request.config,
            host = host,
            pluginId = manifest.id,
        )
        val npc = host.invokeNpc(request.runId, request.sessionId, rendered)
        return NpcGeneratorResult(npc = npc, notice = recipe.notice)
    }
}

internal data class DeclarativeChatRecipe(
    val operation: String,
    val direction: String,
    val key: String,
    val valueTemplate: String,
    val urlTemplate: String,
    val bodyTemplate: String,
    val headers: Map<String, String>,
    val characterTemplate: String,
    val emptyNotice: String,
)

internal data class DeclarativeNpcRecipe(
    val direction: String,
    val notice: String,
)

internal data class DeclarativeGenerationRecipe(
    val rule: String,
)

internal enum class DeclarativeRuleEvent(val wireName: String) {
    BeforeGeneration("before_generation"),
    AfterTurn("after_turn"),
}

internal data class DeclarativeRuleMatch(
    val keywords: List<String>,
    val everyTurns: Int,
    val chancePercent: Int,
    val stateKey: String,
    val stateEquals: String,
)

internal data class DeclarativeRuleAction(
    val type: String,
    val instruction: String,
    val key: String,
    val value: String,
    val amount: Int,
)

internal data class DeclarativeRuleRecipe(
    val id: String,
    val title: String,
    val event: DeclarativeRuleEvent,
    val match: DeclarativeRuleMatch,
    val actions: List<DeclarativeRuleAction>,
)

internal data class DeclarativePluginEvaluation(
    val plugin: DeclarativePlugin?,
    val executable: Boolean,
    val compatible: Boolean = true,
    val executionMode: String,
    val capabilityNotice: String,
    val generationRecipes: Map<String, DeclarativeGenerationRecipe> = emptyMap(),
    val rules: List<DeclarativeRuleRecipe> = emptyList(),
)

internal object DeclarativePluginLoader {
    const val HOST_API_VERSION = "2"
    private val supportedApiVersions = setOf("1", HOST_API_VERSION)

    fun evaluate(pluginId: String, raw: JsonObject): DeclarativePluginEvaluation {
        val apiVersion = raw["apiVersion"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "1" }
        if (apiVersion !in supportedApiVersions) {
            return DeclarativePluginEvaluation(
                plugin = null,
                executable = false,
                compatible = false,
                executionMode = "incompatible",
                capabilityNotice = "插件 API $apiVersion 与当前宿主 API $HOST_API_VERSION 不兼容。",
            )
        }
        val execution = raw["execution"]?.jsonObject
        val mode = execution?.get("mode")?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        if (execution == null || mode != "declarative") {
            return DeclarativePluginEvaluation(
                plugin = null,
                executable = false,
                executionMode = "unsupported",
                capabilityNotice = "第三方插件包当前只能保存，不能执行；造梦不会运行其中的 Python 或其他任意代码。",
            )
        }

        val manifest = parseManifest(pluginId, raw)
        val chatRecipes = parseChatRecipes(manifest, execution)
        val npcRecipes = parseNpcRecipes(manifest, execution)
        val generationRecipes = parseGenerationRecipes(manifest, execution)
        val declaredRuleElement = execution["rules"]
        if (declaredRuleElement != null && declaredRuleElement !is JsonArray) {
            return DeclarativePluginEvaluation(
                plugin = null,
                executable = false,
                executionMode = "declarative-invalid",
                capabilityNotice = "声明式 execution.rules 必须是规则数组。",
            )
        }
        val declaredRules = declaredRuleElement.orEmpty()
        val rules = parseRules(manifest, declaredRules)
        if (rules.size != declaredRules.size) {
            return DeclarativePluginEvaluation(
                plugin = null,
                executable = false,
                executionMode = "declarative-invalid",
                capabilityNotice = "声明式 execution.rules 中存在无效事件、条件、动作或缺少对应权限。",
            )
        }
        val supported = chatRecipes.isNotEmpty() || npcRecipes.isNotEmpty() || generationRecipes.isNotEmpty() || rules.isNotEmpty()
        if (!supported) {
            return DeclarativePluginEvaluation(
                plugin = null,
                executable = false,
                executionMode = "declarative-invalid",
                capabilityNotice = "声明式 execution 配置缺少可用的 chatActions 或 temporaryNpcGenerators 配方。",
            )
        }

        val missingActions = manifest.contributes.chatActions
            .map(ChatActionDescriptor::id)
            .filterNot(chatRecipes::containsKey)
        val missingNpc = manifest.contributes.temporaryNpcGenerators
            .map(TemporaryNpcGeneratorDescriptor::id)
            .filterNot(npcRecipes::containsKey)
        val missingEnhancers = manifest.contributes.generationEnhancers
            .map(GenerationEnhancerDescriptor::id)
            .filterNot(generationRecipes::containsKey)
        if (missingActions.isNotEmpty() || missingNpc.isNotEmpty() || missingEnhancers.isNotEmpty()) {
            return DeclarativePluginEvaluation(
                plugin = null,
                executable = false,
                executionMode = "declarative-invalid",
                capabilityNotice = "声明式 execution 配置缺少贡献点配方：" +
                    (missingActions + missingNpc + missingEnhancers).joinToString("、"),
            )
        }

        return DeclarativePluginEvaluation(
            plugin = DeclarativePlugin(manifest, chatRecipes, npcRecipes),
            executable = true,
            executionMode = "declarative-kotlin",
            capabilityNotice = "由声明式插件运行时执行，不加载或运行插件携带的任意代码。",
            generationRecipes = generationRecipes,
            rules = rules,
        )
    }

    private fun parseManifest(pluginId: String, raw: JsonObject): PluginManifest {
        val contributes = raw["contributes"]?.jsonObject ?: JsonObject(emptyMap())
        return PluginManifest(
            id = pluginId,
            name = raw["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { pluginId } ?: pluginId,
            version = raw["version"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "1.0.0" },
            apiVersion = raw["apiVersion"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .ifBlank { "1" },
            description = raw["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            permissions = stringArray(raw["permissions"]),
            settings = parseSettings(raw["settings"]),
            contributes = PluginContributions(
                chatActions = contributes["chatActions"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val item = element.jsonObject
                    val id = item["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (id.isBlank()) null else ChatActionDescriptor(
                        id = id,
                        title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
                        placement = item["placement"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "composer" },
                        icon = item["icon"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                },
                temporaryNpcGenerators = contributes["temporaryNpcGenerators"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val item = element.jsonObject
                    val id = item["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (id.isBlank()) null else TemporaryNpcGeneratorDescriptor(
                        id = id,
                        title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
                        icon = item["icon"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                },
                generationEnhancers = contributes["generationEnhancers"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val item = element.jsonObject
                    val id = item["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (id.isBlank()) null else GenerationEnhancerDescriptor(
                        id = id,
                        title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
                        icon = item["icon"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                },
            ),
            defaultEnabled = raw["defaultEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun parseChatRecipes(
        manifest: PluginManifest,
        execution: JsonObject,
    ): Map<String, DeclarativeChatRecipe> {
        val recipes = execution["chatActions"]?.jsonObject ?: JsonObject(emptyMap())
        return manifest.contributes.chatActions.mapNotNull { action ->
            val item = recipes[action.id]?.jsonObject ?: return@mapNotNull null
            val operation = item["operation"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
                .ifBlank { "suggest" }
            val direction = item["direction"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val key = item["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val valueTemplate = item["value"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val urlTemplate = item["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val bodyTemplate = item["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val headers = item["headers"]?.jsonObject.orEmpty()
                .mapNotNull { (headerKey, value) ->
                    value.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                        ?.let { headerKey to it }
                }.toMap()
            val characterTemplate = item["character"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val required = when (operation) {
                "storage_get" -> setOf("storage.read")
                "storage_set" -> setOf("storage.write")
                "http_get", "http_post" -> setOf("network.access")
                "reply_as_character" -> setOf("chat.context.read", "chat.draft.write", "model.invoke", "run.personas.read")
                "mute_character", "unmute_character" -> setOf("chat.cast.write")
                else -> setOf("chat.context.read", "chat.draft.write", "model.invoke")
            }.toMutableSet()
            if (
                usesStorageRead(direction, key, valueTemplate, urlTemplate, bodyTemplate) ||
                usesStorageRead(characterTemplate) ||
                usesStorageRead(*headers.values.toTypedArray())
            ) {
                required += "storage.read"
            }
            if (
                operation !in setOf(
                    "suggest",
                    "variants",
                    "storage_get",
                    "storage_set",
                    "http_get",
                    "http_post",
                    "reply_as_character",
                    "mute_character",
                    "unmute_character",
                ) ||
                ((operation == "suggest" || operation == "variants") && direction.isBlank()) ||
                ((operation == "storage_get" || operation == "storage_set") && key.isBlank()) ||
                (operation == "storage_set" && valueTemplate.isBlank()) ||
                ((operation == "http_get" || operation == "http_post") && urlTemplate.isBlank()) ||
                ((operation == "mute_character" || operation == "unmute_character") && characterTemplate.isBlank()) ||
                !manifest.permissions.containsAll(required)
            ) return@mapNotNull null
            action.id to DeclarativeChatRecipe(
                operation = operation,
                direction = direction,
                key = key,
                valueTemplate = valueTemplate,
                urlTemplate = urlTemplate,
                bodyTemplate = bodyTemplate,
                headers = headers,
                characterTemplate = characterTemplate,
                emptyNotice = item["empty_notice"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.toMap()
    }

    private fun parseNpcRecipes(
        manifest: PluginManifest,
        execution: JsonObject,
    ): Map<String, DeclarativeNpcRecipe> {
        val recipes = execution["temporaryNpcGenerators"]?.jsonObject ?: JsonObject(emptyMap())
        return manifest.contributes.temporaryNpcGenerators.mapNotNull { generator ->
            val item = recipes[generator.id]?.jsonObject ?: return@mapNotNull null
            val direction = item["direction"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val required = setOf("chat.context.read", "chat.cast.write", "model.invoke").toMutableSet()
            if (usesStorageRead(direction)) required += "storage.read"
            if (direction.isBlank() || !manifest.permissions.containsAll(required)) return@mapNotNull null
            generator.id to DeclarativeNpcRecipe(
                direction = direction,
                notice = item["notice"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.toMap()
    }

    private fun parseGenerationRecipes(
        manifest: PluginManifest,
        execution: JsonObject,
    ): Map<String, DeclarativeGenerationRecipe> {
        val recipes = execution["generationEnhancers"]?.jsonObject ?: JsonObject(emptyMap())
        return manifest.contributes.generationEnhancers.mapNotNull { enhancer ->
            val item = recipes[enhancer.id]?.jsonObject ?: return@mapNotNull null
            val rule = item["rule"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val required = setOf("chat.context.read", "generation.enhance", "model.invoke")
            if (rule.isBlank() || !manifest.permissions.containsAll(required)) return@mapNotNull null
            enhancer.id to DeclarativeGenerationRecipe(rule = rule)
        }.toMap()
    }

    private fun parseRules(
        manifest: PluginManifest,
        elements: List<JsonElement>,
    ): List<DeclarativeRuleRecipe> {
        if (elements.size > 8) return emptyList()
        val parsed = elements.mapNotNull { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = item["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (!RULE_ID.matches(id)) return@mapNotNull null
            val event = when (item["event"]?.jsonPrimitive?.contentOrNull) {
                DeclarativeRuleEvent.BeforeGeneration.wireName -> DeclarativeRuleEvent.BeforeGeneration
                DeclarativeRuleEvent.AfterTurn.wireName -> DeclarativeRuleEvent.AfterTurn
                else -> return@mapNotNull null
            }
            val matchElement = item["match"]
            if (matchElement != null && matchElement !is JsonObject) return@mapNotNull null
            val matchObject = matchElement ?: JsonObject(emptyMap())
            val keywordElement = matchObject["keywords"]
            if (keywordElement != null && keywordElement !is JsonArray) return@mapNotNull null
            val keywords = keywordElement.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
            val everyTurns = matchObject["everyTurns"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val chancePercent = matchObject["chancePercent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100
            val stateKey = matchObject["stateKey"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val stateEquals = matchObject["stateEquals"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (
                keywords.size > 12 || keywords.any { it.length > 48 } ||
                (everyTurns != 0 && everyTurns !in 2..100) || chancePercent !in 1..100 ||
                (stateKey.isBlank() != stateEquals.isBlank()) ||
                (stateKey.isNotBlank() && !STATE_KEY.matches(stateKey))
            ) return@mapNotNull null
            val actionElement = item["actions"]
            if (actionElement !is JsonArray) return@mapNotNull null
            val actions = actionElement.mapNotNull actionLoop@ { rawAction ->
                val action = runCatching { rawAction.jsonObject }.getOrNull() ?: return@actionLoop null
                val type = action["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val instruction = action["instruction"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val key = action["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val value = action["value"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val amount = action["amount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                val valid = when (type) {
                    "add_instruction" -> event == DeclarativeRuleEvent.BeforeGeneration &&
                        instruction.isNotBlank() && instruction.length <= 2_000 &&
                        "generation.enhance" in manifest.permissions
                    "set_state" -> event == DeclarativeRuleEvent.AfterTurn && STATE_KEY.matches(key) &&
                        value.isNotBlank() && value.length <= 120 && "chat.state.write" in manifest.permissions
                    "increment_state" -> event == DeclarativeRuleEvent.AfterTurn && STATE_KEY.matches(key) &&
                        amount != 0 && amount in -100..100 && "chat.state.write" in manifest.permissions
                    else -> false
                }
                if (!valid) return@actionLoop null
                DeclarativeRuleAction(type, instruction, key, value, amount)
            }
            val declaredActions = actionElement
            if (declaredActions.isEmpty() || declaredActions.size > 6 || actions.size != declaredActions.size) return@mapNotNull null
            DeclarativeRuleRecipe(
                id = id,
                title = item["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { id },
                event = event,
                match = DeclarativeRuleMatch(keywords, everyTurns, chancePercent, stateKey, stateEquals),
                actions = actions,
            )
        }
        return parsed.takeIf { it.map(DeclarativeRuleRecipe::id).distinct().size == it.size }.orEmpty()
    }

    private fun parseSettings(value: JsonElement?): List<PluginSettingDescriptor> {
        val array = value?.jsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element.jsonObject
            val key = item["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (key.isBlank()) return@mapNotNull null
            val type = item["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "string" }
            val options = item["options"]?.jsonArray.orEmpty().mapNotNull { option ->
                when (option) {
                    is JsonObject -> option["value"]?.jsonPrimitive?.contentOrNull
                        ?: option["label"]?.jsonPrimitive?.contentOrNull
                    is JsonPrimitive -> option.contentOrNull
                    else -> null
                }?.trim()?.takeIf(String::isNotBlank)
            }
            PluginSettingDescriptor(
                key = key,
                label = item["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { key },
                type = type,
                defaultValue = item["default"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                options = options,
            )
        }
    }

    private fun stringArray(value: kotlinx.serialization.json.JsonElement?): List<String> =
        value?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }

    private val RULE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,47}$")
    private val STATE_KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,47}$")

}

private val declarativePlaceholder = Regex("\\{\\{\\s*([^}]+?)\\s*\\}\\}")
private val declarativeStoragePlaceholder = Regex("\\{\\{\\s*storage\\.([^}]+?)\\s*\\}\\}")

private fun renderTemplate(
    template: String,
    seedText: String,
    direction: String,
    config: Map<String, Any?>,
): String = declarativePlaceholder.replace(template) { match ->
    val key = match.groupValues[1].trim()
    when {
        key == "seed_text" -> seedText
        key == "direction" -> direction
        key.startsWith("config.") -> config[key.removePrefix("config.")]?.toString().orEmpty()
        else -> ""
    }
}.trim()

private fun usesStorageRead(vararg templates: String): Boolean =
    templates.any { declarativeStoragePlaceholder.containsMatchIn(it) }

private suspend fun resolveTemplate(
    template: String,
    seedText: String,
    direction: String,
    config: Map<String, Any?>,
    host: PluginHost,
    pluginId: String,
): String {
    val rendered = renderTemplate(template, seedText, direction, config)
    val result = StringBuilder()
    var cursor = 0
    for (match in declarativeStoragePlaceholder.findAll(rendered)) {
        result.append(rendered, cursor, match.range.first)
        result.append(host.readPluginData(pluginId, match.groupValues[1].trim()).orEmpty())
        cursor = match.range.last + 1
    }
    result.append(rendered, cursor, rendered.length)
    return result.toString()
}
