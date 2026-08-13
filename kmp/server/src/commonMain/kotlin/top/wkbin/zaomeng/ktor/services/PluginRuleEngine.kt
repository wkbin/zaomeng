package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.ktor.models.DialogueResponse
import top.wkbin.zaomeng.platform.nowIsoString

class PluginRuleEngine(
    private val storage: StorageService,
    private val plugins: PluginService,
) {
    private val json = Json { prettyPrint = true }

    fun beforeGeneration(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
        session: JsonObject,
    ): JsonObject {
        val turnNumber = (session["turn_count"]?.jsonPrimitive?.intOrNull ?: 0) + 1
        val directives = linkedMapOf<String, String>()
        plugins.activeDeclarativeRules().forEach { active ->
            val state = pluginState(session, active.pluginId)
            active.rules.filter { it.event == DeclarativeRuleEvent.BeforeGeneration }.forEach { rule ->
                if (!matches(rule, active.pluginId, "$runId/$sessionId", turnId, message, turnNumber, state)) return@forEach
                val instructions = rule.actions.filter { it.type == "add_instruction" }
                    .map { renderRuleTemplate(it.instruction, message, active.config, state) }
                    .filter(String::isNotBlank)
                if (instructions.isNotEmpty()) directives["${active.pluginId}/${rule.id}"] = instructions.joinToString("\n")
            }
        }
        return if (directives.isEmpty()) session else buildJsonObject {
            session.forEach { (key, value) -> put(key, value) }
            put("plugin_rule_directives", buildJsonObject {
                directives.forEach { (key, value) -> put(key, value) }
            })
        }
    }

    fun afterTurn(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
        responses: List<DialogueResponse>,
    ) {
        val session = storage.loadSessionManifest(runId, sessionId)
        val turnNumber = session["turn_count"]?.jsonPrimitive?.intOrNull ?: 0
        val combinedText = buildString {
            append(message)
            responses.forEach { append('\n'); append(it.message) }
        }
        val allStates = session["plugin_rule_states"]?.jsonObject ?: JsonObject(emptyMap())
        val updatedStates = allStates.toMutableMap()
        val lastTurns = (session["plugin_rule_last_turns"]?.jsonObject ?: JsonObject(emptyMap())).toMutableMap()
        var changed = false
        plugins.activeDeclarativeRules().forEach { active ->
            val state = pluginState(session, active.pluginId).toMutableMap()
            active.rules.filter { it.event == DeclarativeRuleEvent.AfterTurn }.forEach { rule ->
                val firingKey = "${active.pluginId}/${rule.id}"
                if (lastTurns[firingKey]?.jsonPrimitive?.contentOrNull == turnId) return@forEach
                if (!matches(rule, active.pluginId, "$runId/$sessionId", turnId, combinedText, turnNumber, state)) return@forEach
                var fired = false
                rule.actions.forEach { action ->
                    when (action.type) {
                        "set_state" -> {
                            state[action.key] = renderRuleTemplate(action.value, message, active.config, state)
                            changed = true
                            fired = true
                        }
                        "increment_state" -> {
                            val current = state[action.key]?.toIntOrNull() ?: 0
                            state[action.key] = (current + action.amount).coerceIn(-1_000_000, 1_000_000).toString()
                            changed = true
                            fired = true
                        }
                    }
                }
                if (fired) lastTurns[firingKey] = JsonPrimitive(turnId)
            }
            if (state.isNotEmpty()) updatedStates[active.pluginId] = buildJsonObject {
                state.forEach { (key, value) -> put(key, value) }
            }
        }
        if (!changed) return
        val updated = buildJsonObject {
            session.forEach { (key, value) -> put(key, value) }
            put("plugin_rule_states", JsonObject(updatedStates))
            put("plugin_rule_last_turns", JsonObject(lastTurns))
            put("updated_at", nowIsoString())
        }
        storage.writeTextAtomically(
            storage.getDialogueSessionManifestFile(runId, sessionId),
            json.encodeToString(JsonObject.serializer(), updated),
        )
    }

    private fun pluginState(session: JsonObject, pluginId: String): Map<String, String> =
        (session["plugin_rule_states"]?.jsonObject?.get(pluginId)?.jsonObject ?: JsonObject(emptyMap()))
            .mapNotNull { (key, value) -> value.jsonPrimitive.contentOrNull?.let { key to it } }
            .toMap()

    private fun matches(
        rule: DeclarativeRuleRecipe,
        pluginId: String,
        sessionId: String,
        turnId: String,
        text: String,
        turnNumber: Int,
        state: Map<String, String>,
    ): Boolean {
        val match = rule.match
        if (match.keywords.isNotEmpty() && match.keywords.none { text.contains(it, ignoreCase = true) }) return false
        if (match.everyTurns > 0 && turnNumber % match.everyTurns != 0) return false
        if (match.stateKey.isNotBlank() && state[match.stateKey].orEmpty() != match.stateEquals) return false
        if (match.chancePercent < 100) {
            val bucket = stableBucket("$pluginId|$sessionId|$turnId|${rule.id}")
            if (bucket >= match.chancePercent) return false
        }
        return true
    }

    private fun stableBucket(value: String): Int {
        var hash = 0x811c9dc5.toInt()
        value.forEach { hash = (hash xor it.code) * 16777619 }
        return (hash.toUInt() % 100u).toInt()
    }
}

private val rulePlaceholder = Regex("\\{\\{\\s*([^}]+?)\\s*\\}\\}")

private fun renderRuleTemplate(
    template: String,
    message: String,
    config: Map<String, String>,
    state: Map<String, String>,
): String = rulePlaceholder.replace(template) { match ->
    val key = match.groupValues[1].trim()
    when {
        key == "message" -> message
        key.startsWith("config.") -> config[key.removePrefix("config.")].orEmpty()
        key.startsWith("state.") -> state[key.removePrefix("state.")].orEmpty()
        else -> ""
    }
}.trim()
