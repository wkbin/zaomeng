package top.wkbin.zaomeng.feature.storyrecap

import top.wkbin.zaomeng.data.api.StoryCharacterArcDto
import top.wkbin.zaomeng.data.api.StoryEventDto
import top.wkbin.zaomeng.data.api.StoryMetricChangeDto
import top.wkbin.zaomeng.data.api.StoryQuoteDto
import top.wkbin.zaomeng.data.api.StoryRecapDto
import top.wkbin.zaomeng.data.api.StoryRelationChangeDto
import top.wkbin.zaomeng.data.api.StoryResponseDto
import kotlin.test.Test
import kotlin.test.assertTrue

class RecapTheatreFormatterTest {

    private val sampleRecap = StoryRecapDto(
        title = "潇湘夜雨探病容",
        summary = "宝玉冒雨前往潇湘馆探望黛玉，二人因诗社与旧帕互诉衷肠，心结稍解。",
        location = "潇湘馆",
        timeHint = "秋夜更深",
        atmosphere = "幽咽温存",
        participants = listOf("贾宝玉", "林黛玉", "紫鹃"),
        eventCount = 2,
        events = listOf(
            StoryEventDto(
                title = "冒雨登门",
                location = "潇湘馆竹林外",
                timeHint = "一更时分",
                participants = listOf("贾宝玉", "紫鹃"),
                responses = listOf(
                    StoryResponseDto(speaker = "旁白", message = "院外竹影摇曳，夜雨潇潇。"),
                    StoryResponseDto(speaker = "贾宝玉", message = "紫鹃姐姐，妹妹今日可进过饮食？"),
                ),
            ),
            StoryEventDto(
                title = "灯下对坐",
                location = "潇湘馆内室",
                participants = listOf("贾宝玉", "林黛玉"),
                responses = listOf(
                    StoryResponseDto(speaker = "林黛玉", message = "你又冒失赶来，淋坏了身子又是我的不是。"),
                ),
            ),
        ),
        relations = listOf(
            StoryRelationChangeDto(
                pairKey = "贾宝玉:林黛玉",
                label = "宝黛情愫",
                characters = listOf("贾宝玉", "林黛玉"),
                changes = listOf(
                    StoryMetricChangeDto(metric = "trust", label = "信任度", delta = 2),
                    StoryMetricChangeDto(metric = "affection", label = "亲密度", delta = 3),
                ),
                reason = "雨夜深谈旧帕，解开猜忌",
            ),
        ),
        characterArcs = listOf(
            StoryCharacterArcDto(
                name = "林黛玉",
                growthSummary = "从猜忌多虑转为坦然，接纳了宝玉的关怀。",
            ),
        ),
        quotes = listOf(
            StoryQuoteDto(
                speaker = "林黛玉",
                message = "你既来了，怎么又站着不进来？",
            ),
        ),
        hooks = listOf("秋夜凉意渐重，黛玉旧疾是否再犯？"),
        nextHint = "次日宝钗来访，蘅芜苑暗流涌动。",
        shareText = "宝玉雨夜探潇湘，情丝暗扣。",
    )

    @Test
    fun formatAsTheatreScript_containsScenesAndDialogue() {
        val script = RecapTheatreFormatter.formatAsTheatreScript(sampleRecap)
        assertTrue(script.contains("剧本：《潇湘夜雨探病容》"))
        assertTrue(script.contains("【主要登场人物】"))
        assertTrue(script.contains("贾宝玉、 林黛玉、 紫鹃"))
        assertTrue(script.contains("【第 1 场 · 冒雨登门】"))
        assertTrue(script.contains("（舞台提示：院外竹影摇曳，夜雨潇潇。）"))
        assertTrue(script.contains("贾宝玉："))
        assertTrue(script.contains("紫鹃姐姐，妹妹今日可进过饮食？"))
        assertTrue(script.contains("【经典台词辑录】"))
        assertTrue(script.contains("林黛玉：“你既来了，怎么又站着不进来？”"))
        assertTrue(script.contains("【下回预告】"))
        assertTrue(script.contains("次日宝钗来访"))
    }

    @Test
    fun formatAsMarkdownReport_containsStructuredSectionsAndTables() {
        val markdown = RecapTheatreFormatter.formatAsMarkdownReport(sampleRecap)
        assertTrue(markdown.contains("# 📜 剧情战报 · 潇湘夜雨探病容"))
        assertTrue(markdown.contains("## 📖 故事概要"))
        assertTrue(markdown.contains("## 🎬 核心事件脉络"))
        assertTrue(markdown.contains("## 🔗 人物羁绊与关系演变"))
        assertTrue(markdown.contains("| **宝黛情愫** | 信任度 +2，亲密度 +3 | 雨夜深谈旧帕，解开猜忌 |"))
        assertTrue(markdown.contains("## 🌱 角色成长与心境弧光"))
        assertTrue(markdown.contains("林黛玉**：从猜忌多虑转为坦然"))
        assertTrue(markdown.contains("## 💬 名场面金句"))
        assertTrue(markdown.contains("## ❓ 未解伏笔"))
        assertTrue(markdown.contains("## 🔮 后续剧情前瞻"))
    }

    @Test
    fun formatAsHtmlCard_producesValidSelfContainedHtml() {
        val html = RecapTheatreFormatter.formatAsHtmlCard(sampleRecap)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<title>潇湘夜雨探病容 - 造梦战报</title>"))
        assertTrue(html.contains("潇湘夜雨探病容"))
        assertTrue(html.contains("冒雨登门"))
        assertTrue(html.contains("林黛玉"))
        assertTrue(html.contains("</html>"))
    }
}
