package top.wkbin.zaomeng.plugins.builtin

import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.plugins.api.ChatActionRequest
import top.wkbin.zaomeng.plugins.api.ChatActionResult
import top.wkbin.zaomeng.plugins.api.NpcGeneratorRequest
import top.wkbin.zaomeng.plugins.api.NpcGeneratorResult
import top.wkbin.zaomeng.plugins.api.Plugin
import top.wkbin.zaomeng.plugins.api.PluginContributions
import top.wkbin.zaomeng.plugins.api.PluginHost
import top.wkbin.zaomeng.plugins.api.PluginManifest
import top.wkbin.zaomeng.plugins.api.PluginSettingDescriptor
import top.wkbin.zaomeng.plugins.api.TemporaryNpcGeneratorDescriptor

/**
 * 随机 NPC（迁移自 main 分支 src/builtin_plugins/random_npc/main.py）。
 * 按风格生成一名临时角色加入场景（写入会话 temporary_npcs）。
 */
class RandomNpcPlugin : Plugin {
    private val styleDirections = mapOf(
        "mixed" to "完全随机，但不要选择最常见、最稳妥的路人类型。",
        "mysterious" to "生成一名带着未解目的或异常线索的神秘人物。",
        "funny" to "生成一名有鲜明怪癖或反差感，但不破坏世界观的人物。",
        "troublesome" to "生成一名会给当前人物制造具体麻烦或误会的人物。",
        "helpful" to "生成一名愿意提供帮助，但有自身条件和目的的人物。",
        "dangerous" to "生成一名带来明确威胁感，但不会无理由立刻攻击的人物。",
    )

    override val manifest = PluginManifest(
        id = "com.zaomeng.random-npc",
        name = "随机 NPC",
        description = "生成一名符合世界观与当前场景的临时角色加入对话。",
        permissions = listOf("chat.context.read", "chat.draft.write", "model.invoke", "session.modify"),
        settings = listOf(
            PluginSettingDescriptor(
                key = "npcStyle",
                label = "角色风格",
                type = "enum",
                defaultValue = "mixed",
                options = listOf("mixed", "mysterious", "funny", "troublesome", "helpful", "dangerous"),
            ),
        ),
        contributes = PluginContributions(
            temporaryNpcGenerators = listOf(
                TemporaryNpcGeneratorDescriptor(id = "generate-npc", title = "随机 NPC", icon = "person_add"),
            ),
        ),
        defaultEnabled = true,
    )

    override suspend fun executeChatAction(actionId: String, request: ChatActionRequest, host: PluginHost): ChatActionResult = ChatActionResult()

    override suspend fun generateTemporaryNpc(generatorId: String, request: NpcGeneratorRequest, host: PluginHost): NpcGeneratorResult {
        if (generatorId != "generate-npc") return NpcGeneratorResult(npc = JsonObject(emptyMap()))
        val style = request.config["npcStyle"]?.toString()?.trim().orEmpty().ifBlank { "mixed" }
        var direction = styleDirections[style] ?: styleDirections.getValue("mixed")
        val userDirection = request.direction.trim()
        if (userDirection.isNotEmpty()) {
            direction = "$direction 用户补充方向：$userDirection"
        }
        val npc = host.invokeNpc(
            runId = request.runId,
            sessionId = request.sessionId,
            direction = direction,
        )
        return NpcGeneratorResult(
            npc = npc,
            notice = "已让一名临时角色加入场景。",
        )
    }
}
