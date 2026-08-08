package top.wkbin.zaomeng.plugins.builtin

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
 * 多候选回复（迁移自 main 分支 src/builtin_plugins/reply_variants/main.py）。
 * 生成 2-4 个候选回复（label + suggestion）。
 */
class ReplyVariantsPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "com.zaomeng.reply-variants",
        name = "多候选回复",
        description = "基于当前上下文生成多个候选回复，供挑选后发送。",
        permissions = listOf("chat.context.read", "chat.draft.write", "model.invoke"),
        settings = listOf(
            PluginSettingDescriptor(
                key = "optionCount",
                label = "候选数量",
                type = "integer",
                defaultValue = "3",
            ),
        ),
        contributes = PluginContributions(
            chatActions = listOf(
                ChatActionDescriptor(id = "generate-variants", title = "多候选回复", icon = "list"),
            ),
        ),
        defaultEnabled = true,
    )

    override suspend fun executeChatAction(actionId: String, request: ChatActionRequest, host: PluginHost): ChatActionResult {
        if (actionId != "generate-variants") return ChatActionResult()
        val config = request.config
        val optionCount = (config["optionCount"]?.toString()?.toIntOrNull() ?: 3).coerceIn(2, 4)
        val options = host.invokeVariants(
            runId = request.runId,
            sessionId = request.sessionId,
            seedText = request.seedText,
            direction = request.direction,
        ).filter { it.suggestion.isNotBlank() }
            .take(optionCount)
        if (options.isEmpty()) {
            return ChatActionResult(notice = "多候选回复没有生成可用候选，请重试。")
        }
        return ChatActionResult(suggestions = options)
    }

    override suspend fun generateTemporaryNpc(generatorId: String, request: NpcGeneratorRequest, host: PluginHost): NpcGeneratorResult =
        NpcGeneratorResult(npc = kotlinx.serialization.json.JsonObject(emptyMap()))
}
