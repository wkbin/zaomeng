package top.wkbin.zaomeng.plugins.builtin

import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.plugins.api.ChatActionChoice
import top.wkbin.zaomeng.plugins.api.ChatActionDescriptor
import top.wkbin.zaomeng.plugins.api.ChatActionRequest
import top.wkbin.zaomeng.plugins.api.ChatActionResult
import top.wkbin.zaomeng.plugins.api.NpcGeneratorRequest
import top.wkbin.zaomeng.plugins.api.NpcGeneratorResult
import top.wkbin.zaomeng.plugins.api.Plugin
import top.wkbin.zaomeng.plugins.api.PluginContributions
import top.wkbin.zaomeng.plugins.api.PluginHost
import top.wkbin.zaomeng.plugins.api.PluginManifest

/** 选择一名不在当前场景中的已蒸馏人物，让其代用户生成回复草稿。 */
class ReplyAsCharacterPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "com.zaomeng.reply-as-character",
        name = "帮我回",
        description = "从场外的已蒸馏人物中选择一位，让他结合当前对话帮你拟一条回复。",
        permissions = listOf("chat.context.read", "chat.draft.write", "model.invoke", "run.personas.read"),
        contributes = PluginContributions(
            chatActions = listOf(
                ChatActionDescriptor(id = ACTION_ID, title = "帮我回", icon = "person_search"),
            ),
        ),
        defaultEnabled = true,
    )

    override suspend fun executeChatAction(
        actionId: String,
        request: ChatActionRequest,
        host: PluginHost,
    ): ChatActionResult {
        if (actionId != ACTION_ID) return ChatActionResult()
        if (request.selection.isBlank()) {
            val choices = host.listOffScenePersonas(request.runId, request.sessionId).map { persona ->
                ChatActionChoice(
                    label = persona.name,
                    value = persona.name,
                    description = persona.preview,
                )
            }
            return if (choices.isEmpty()) {
                ChatActionResult(notice = "当前没有可选的场外已蒸馏人物。")
            } else {
                ChatActionResult(choicePrompt = "选择谁来帮你回复", choices = choices)
            }
        }
        val reply = host.invokeReplyAsCharacter(
            runId = request.runId,
            sessionId = request.sessionId,
            character = request.selection,
            seedText = request.seedText,
            direction = request.direction,
        ) ?: return ChatActionResult(notice = "没有生成可用回复，请重试。")
        return ChatActionResult(
            suggestion = reply.text,
            character = reply.character,
            notice = "「${reply.character}」已帮你拟好回复，可修改后发送。",
        )
    }

    override suspend fun generateTemporaryNpc(
        generatorId: String,
        request: NpcGeneratorRequest,
        host: PluginHost,
    ): NpcGeneratorResult = NpcGeneratorResult(npc = JsonObject(emptyMap()))

    private companion object {
        const val ACTION_ID = "reply-as-character"
    }
}
