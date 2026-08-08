package top.wkbin.zaomeng.plugins.builtin

import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.plugins.api.ChatActionDescriptor
import top.wkbin.zaomeng.plugins.api.ChatActionRequest
import top.wkbin.zaomeng.plugins.api.ChatActionResult
import top.wkbin.zaomeng.plugins.api.NpcGeneratorRequest
import top.wkbin.zaomeng.plugins.api.NpcGeneratorResult
import top.wkbin.zaomeng.plugins.api.Plugin
import top.wkbin.zaomeng.plugins.api.PluginContributions
import top.wkbin.zaomeng.plugins.api.PluginHost
import top.wkbin.zaomeng.plugins.api.PluginManifest
import top.wkbin.zaomeng.plugins.api.PluginSettingDescriptor

/**
 * 口吻润色（迁移自 main 分支 src/builtin_plugins/voice_polish/main.py）。
 * 把输入框草稿按当前受控角色口吻润色（light/balanced/strong）。
 */
class VoicePolishPlugin : Plugin {
    private val strengthDirections = mapOf(
        "light" to "只做轻微措辞调整，尽量保留原句结构。",
        "balanced" to "明显贴合角色口吻，但保持草稿原有表达节奏。",
        "strong" to "充分使用角色标志性措辞与节奏，可重组句式，但不得改变原意。",
    )

    override val manifest = PluginManifest(
        id = "com.zaomeng.voice-polish",
        name = "口吻润色",
        description = "把草稿按当前受控角色的口吻润色后放回输入框。",
        permissions = listOf("chat.context.read", "chat.draft.write", "model.invoke"),
        settings = listOf(
            PluginSettingDescriptor(
                key = "strength",
                label = "润色强度",
                type = "enum",
                defaultValue = "balanced",
                options = listOf("light", "balanced", "strong"),
            ),
        ),
        contributes = PluginContributions(
            chatActions = listOf(
                ChatActionDescriptor(id = "polish-draft", title = "口吻润色", icon = "brush"),
            ),
        ),
        defaultEnabled = true,
    )

    override suspend fun executeChatAction(actionId: String, request: ChatActionRequest, host: PluginHost): ChatActionResult {
        if (actionId != "polish-draft") return ChatActionResult()
        val seedText = request.seedText.trim()
        if (seedText.isEmpty()) {
            return ChatActionResult(notice = "请先在输入框写下需要润色的草稿。")
        }
        val strength = request.config["strength"]?.toString()?.trim().orEmpty().ifBlank { "balanced" }
        val strengthDirection = strengthDirections[strength] ?: strengthDirections.getValue("balanced")
        val direction = "把输入草稿改写成当前受控角色真正会说或会做的成品文本。" +
            "严格保留原意、事实和行动意图，不新增剧情前提；只返回润色结果。" + strengthDirection
        val suggestion = host.invokeSuggestion(
            runId = request.runId,
            sessionId = request.sessionId,
            seedText = seedText,
            direction = direction,
        ).trim()
        return ChatActionResult(suggestion = suggestion)
    }

    override suspend fun generateTemporaryNpc(generatorId: String, request: NpcGeneratorRequest, host: PluginHost): NpcGeneratorResult =
        NpcGeneratorResult(npc = JsonObject(emptyMap()))
}
