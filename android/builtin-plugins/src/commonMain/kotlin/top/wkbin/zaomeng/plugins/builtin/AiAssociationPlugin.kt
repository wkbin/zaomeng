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

/**
 * AI 联想（迁移自 main 分支 src/builtin_plugins/ai_association/main.py）。
 * 结合当前对话上下文生成一段可发送的草稿。
 */
class AiAssociationPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "com.zaomeng.ai-association",
        name = "AI 联想",
        description = "结合当前角色、关系和场景，生成一段可发送的对话草稿。",
        permissions = listOf("chat.context.read", "chat.draft.write", "model.invoke"),
        contributes = PluginContributions(
            chatActions = listOf(
                ChatActionDescriptor(id = "suggest-turn", title = "AI 联想", icon = "auto_awesome"),
            ),
        ),
        defaultEnabled = true,
    )

    override suspend fun executeChatAction(actionId: String, request: ChatActionRequest, host: PluginHost): ChatActionResult {
        if (actionId != "suggest-turn") return ChatActionResult()
        val suggestion = host.invokeSuggestion(
            runId = request.runId,
            sessionId = request.sessionId,
            seedText = request.seedText,
            direction = request.direction,
        ).trim()
        return ChatActionResult(suggestion = suggestion)
    }

    override suspend fun generateTemporaryNpc(generatorId: String, request: NpcGeneratorRequest, host: PluginHost): NpcGeneratorResult =
        NpcGeneratorResult(npc = JsonObject(emptyMap()))
}
