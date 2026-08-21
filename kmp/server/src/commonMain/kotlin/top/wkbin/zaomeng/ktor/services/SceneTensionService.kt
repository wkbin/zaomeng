package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.data.api.PlotEventPresetDto
import top.wkbin.zaomeng.data.api.SceneTensionDto

/**
 * 剧情张力评估与突发事件生成服务。
 * 负责实时评估多角色对白的剧情冲突度、张力指数（0~100）与节奏阶段，
 * 并提供四大类小说经典突发事件（外部异动、秘密揭露、情感转折、危机临近）辅助导演干预。
 */
class SceneTensionService {

    companion object {
        private val CONFLICT_KEYWORDS = setOf(
            "怒", "冷笑", "拔剑", "质问", "凭什么", "住口", "休想", "退后", "警惕",
            "杀", "死", "慌", "颤", "疑", "骗", "背叛", "真相", "不公", "拔刀", "逼问", "休怪",
        )

        private val CALM_KEYWORDS = setOf(
            "坐", "笑", "饮茶", "淡然", "闲谈", "缓缓", "点头", "无妨", "安好", "微风", "月色", "漫步",
        )

        private val PRESET_EVENTS = listOf(
            // 外部异动
            PlotEventPresetDto(
                id = "ext-1",
                category = "external",
                categoryLabel = "外部异动",
                title = "突传急促马蹄声",
                event = "门外长街上突然传来急促由远及近的马蹄声与勒马嘶鸣，打断了室内的交谈。",
                recommendedAction = "advance",
            ),
            PlotEventPresetDto(
                id = "ext-2",
                category = "external",
                categoryLabel = "外部异动",
                title = "窗外骤降暴雨",
                event = "夜空骤亮一道惨白闪电，紧接着滚雷炸响，倾盆暴雨猛烈拍打窗棂。",
                recommendedAction = "advance",
            ),
            PlotEventPresetDto(
                id = "ext-3",
                category = "external",
                categoryLabel = "外部异动",
                title = "烛火蓦然熄灭",
                event = "一阵穿堂阴风掠过，案头烛火猛烈摇曳后骤然熄灭，室内陷入短暂昏暗。",
                recommendedAction = "advance",
            ),

            // 秘密揭露
            PlotEventPresetDto(
                id = "sec-1",
                category = "secret",
                categoryLabel = "秘密揭露",
                title = "暗格信函失手跌落",
                event = "衣袖拂动间，袖中暗藏的一封未拆信函（或带有特殊血契的信物）滑落掷地。",
                recommendedAction = "conflict",
            ),
            PlotEventPresetDto(
                id = "sec-2",
                category = "secret",
                categoryLabel = "秘密揭露",
                title = "无意触及旧案细节",
                event = "话语间无意提及多年前那场未解的旧案关键细节，对方神色骤然一变。",
                recommendedAction = "conflict",
            ),
            PlotEventPresetDto(
                id = "sec-3",
                category = "secret",
                categoryLabel = "秘密揭露",
                title = "察觉字迹暗记有异",
                event = "借着光线细看文书时，猛然察觉落款处的暗记印信与往日截然不同。",
                recommendedAction = "conflict",
            ),

            // 情感转折
            PlotEventPresetDto(
                id = "emo-1",
                category = "emotion",
                categoryLabel = "情感转折",
                title = "克制后退半步",
                event = "在视线交汇的刹那，对方眼神微颤，下意识移开目光并克制地后退了半步。",
                recommendedAction = "slow_emotion",
            ),
            PlotEventPresetDto(
                id = "emo-2",
                category = "emotion",
                categoryLabel = "情感转折",
                title = "直截试探底线",
                event = "语气忽然放缓，直截了当地问出了那句一直被彼此心照不宣回避的关键问题。",
                recommendedAction = "conflict",
            ),
            PlotEventPresetDto(
                id = "emo-3",
                category = "emotion",
                categoryLabel = "情感转折",
                title = "触碰深埋痛处",
                event = "对白不经意触及对方深埋已久的痛处，室内空气瞬间陷入死一般的凝固。",
                recommendedAction = "slow_emotion",
            ),

            // 危机临近
            PlotEventPresetDto(
                id = "cri-1",
                category = "crisis",
                categoryLabel = "危机临近",
                title = "巡夜锣声逼近",
                event = "更夫急促的铜锣声与杂乱的脚步声正由远及近地朝院门逼近。",
                recommendedAction = "advance",
            ),
            PlotEventPresetDto(
                id = "cri-2",
                category = "crisis",
                categoryLabel = "危机临近",
                title = "窗纸人影驻足",
                event = "窗纸上隐隐映出一个悄无声息驻足倾听的黑色人影，刀鞘在月光下泛着冷光。",
                recommendedAction = "advance",
            ),
        )
    }

    /**
     * 获取内置突发事件预设列表。
     */
    fun getPresetEvents(category: String? = null): List<PlotEventPresetDto> {
        if (category.isNullOrBlank()) return PRESET_EVENTS
        return PRESET_EVENTS.filter { it.category.equals(category, ignoreCase = true) }
    }

    /**
     * 根据近期对话历史评估剧情张力指数与节奏建议。
     */
    fun evaluateTension(recentMessages: List<String>): SceneTensionDto {
        if (recentMessages.isEmpty()) {
            return SceneTensionDto(
                score = 30,
                pacing = "Calm",
                label = "平缓蓄势",
                suggestion = "当前剧情刚开启或对话平稳，可引入外部异动或交代关键背景。",
                dominantEmotion = "neutral",
                conflictIndicator = 0.15f,
            )
        }

        val sample = recentMessages.takeLast(8).joinToString(" ")
        var conflictCount = 0
        var calmCount = 0

        for (word in CONFLICT_KEYWORDS) {
            if (sample.contains(word)) conflictCount++
        }
        for (word in CALM_KEYWORDS) {
            if (sample.contains(word)) calmCount++
        }

        // 标点符号情绪密度
        val exclamations = sample.count { it == '!' || it == '！' }
        val questions = sample.count { it == '?' || it == '？' }
        val ellipses = sample.windowed(2).count { it == ".." || it == "……" }

        var rawScore = 32 + (conflictCount * 8) - (calmCount * 5) + (exclamations * 4) + (questions * 3) + (ellipses * 2)
        val score = rawScore.coerceIn(5, 98)

        val conflictIndicator = ((score - 20).toFloat() / 80f).coerceIn(0f, 1f)

        return when {
            score <= 35 -> SceneTensionDto(
                score = score,
                pacing = "Calm",
                label = "平缓蓄势",
                suggestion = "对话节奏平稳，适合引入外部异动或抛出秘密打破平静。",
                dominantEmotion = if (calmCount > conflictCount) "calm" else "neutral",
                conflictIndicator = conflictIndicator,
            )
            score <= 65 -> SceneTensionDto(
                score = score,
                pacing = "Building",
                label = "张力积聚",
                suggestion = "情绪与矛盾正在上升，建议推进关键对话或迫使一方明确表态。",
                dominantEmotion = "anticipation",
                conflictIndicator = conflictIndicator,
            )
            score <= 82 -> SceneTensionDto(
                score = score,
                pacing = "Climax",
                label = "高潮爆发",
                suggestion = "冲突处于白热化，适合正面决断、摊牌或突发危机干预。",
                dominantEmotion = "tense",
                conflictIndicator = conflictIndicator,
            )
            else -> SceneTensionDto(
                score = score,
                pacing = "Intense",
                label = "极端紧绷",
                suggestion = "情绪逼近顶点，可准备戏剧性转折或引入外部力量进入回落余波。",
                dominantEmotion = "explosive",
                conflictIndicator = conflictIndicator,
            )
        }
    }
}
