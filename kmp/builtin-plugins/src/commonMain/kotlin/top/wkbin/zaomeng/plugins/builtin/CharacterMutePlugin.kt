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

/** 管理当前场景人物的禁言状态；被禁言者会从后续生成参与者中排除。 */
class CharacterMutePlugin : Plugin {
    override val manifest = PluginManifest(
        id = "com.zaomeng.character-mute",
        name = "人物禁言",
        description = "选择当前场景中的人物禁言，或恢复已禁言人物的发言。",
        permissions = listOf("chat.context.read", "session.modify"),
        contributes = PluginContributions(
            chatActions = listOf(
                ChatActionDescriptor(id = ACTION_ID, title = "人物禁言", icon = "volume_off"),
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
            val choices = host.listSessionCharacters(request.runId, request.sessionId)
                .filter { character -> character.muted || character.canMute }
                .map { character ->
                    ChatActionChoice(
                        label = if (character.muted) "${character.name} · 已禁言" else character.name,
                        value = "${if (character.muted) UNMUTE_PREFIX else MUTE_PREFIX}${character.name}",
                        description = if (character.muted) "点击恢复发言" else "点击禁言，不再参与后续生成",
                    )
                }
            return if (choices.isEmpty()) {
                ChatActionResult(notice = "当前场景没有可管理的人物。")
            } else {
                ChatActionResult(
                    choicePrompt = "管理人物发言状态",
                    choices = choices,
                )
            }
        }
        val muted = when {
            request.selection.startsWith(MUTE_PREFIX) -> true
            request.selection.startsWith(UNMUTE_PREFIX) -> false
            else -> throw IllegalArgumentException("无效的人物发言状态操作。")
        }
        val character = request.selection.substringAfter(':').trim()
        val session = host.setSessionCharacterMuted(
            runId = request.runId,
            sessionId = request.sessionId,
            character = character,
            muted = muted,
        ) ?: return ChatActionResult(notice = "会话状态更新失败，请重试。")
        return ChatActionResult(
            session = session,
            notice = if (muted) {
                "「$character」已禁言，不会参与后续生成。"
            } else {
                "「$character」已恢复发言。"
            },
        )
    }

    override suspend fun generateTemporaryNpc(
        generatorId: String,
        request: NpcGeneratorRequest,
        host: PluginHost,
    ): NpcGeneratorResult = NpcGeneratorResult(npc = JsonObject(emptyMap()))

    private companion object {
        const val ACTION_ID = "manage-character-mute"
        const val MUTE_PREFIX = "mute:"
        const val UNMUTE_PREFIX = "unmute:"
    }
}
