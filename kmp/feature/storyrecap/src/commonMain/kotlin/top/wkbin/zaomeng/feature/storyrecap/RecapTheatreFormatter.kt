package top.wkbin.zaomeng.feature.storyrecap

import top.wkbin.zaomeng.data.api.StoryRecapDto

/**
 * 剧情复盘与剧场排版格式化工具。
 * 支持转换为专业剧场剧本、结构化 Markdown 战报以及自包含高质感 HTML 卡片。
 */
object RecapTheatreFormatter {

    /**
     * 转换为专业舞台剧本格式。
     */
    fun formatAsTheatreScript(recap: StoryRecapDto): String = buildString {
        appendLine("==================================================")
        appendLine("           剧本：《${recap.title.ifBlank { "未命名剧目" }}》")
        appendLine("==================================================")
        appendLine()
        if (recap.summary.isNotBlank()) {
            appendLine("【剧目梗概】")
            appendLine(recap.summary)
            appendLine()
        }
        if (recap.participants.isNotEmpty()) {
            appendLine("【主要登场人物】")
            appendLine(recap.participants.joinToString("、 "))
            appendLine()
        }
        if (recap.location.isNotBlank() || recap.timeHint.isNotBlank() || recap.atmosphere.isNotBlank()) {
            appendLine("【基调与景况】")
            val tags = listOfNotNull(
                recap.timeHint.takeIf(String::isNotBlank)?.let { "时间：$it" },
                recap.location.takeIf(String::isNotBlank)?.let { "地点：$it" },
                recap.atmosphere.takeIf(String::isNotBlank)?.let { "氛围：$it" },
            )
            appendLine(tags.joinToString(" | "))
            appendLine()
        }

        appendLine("--------------------------------------------------")
        appendLine("                     正 文                        ")
        appendLine("--------------------------------------------------")
        appendLine()

        if (recap.events.isEmpty()) {
            appendLine("（暂无分场事件记录）")
        } else {
            recap.events.forEachIndexed { index, event ->
                appendLine("【第 ${index + 1} 场 · ${event.title.ifBlank { "幕次 ${index + 1}" }}】")
                val sceneMeta = listOfNotNull(
                    event.timeHint.takeIf(String::isNotBlank)?.let { "时间：$it" },
                    event.location.takeIf(String::isNotBlank)?.let { "地点：$it" },
                    event.participants.takeIf(List<String>::isNotEmpty)?.let { "登场：${it.joinToString("、")}" },
                )
                if (sceneMeta.isNotEmpty()) {
                    appendLine("〔${sceneMeta.joinToString(" | ")}〕")
                }
                appendLine()

                if (event.responses.isEmpty()) {
                    appendLine("（本场无对白细节）")
                } else {
                    event.responses.forEach { resp ->
                        val speaker = resp.speaker.ifBlank { "旁白" }
                        if (speaker == "旁白" || speaker == "场景" || speaker == "导演") {
                            appendLine("（舞台提示：${resp.message}）")
                        } else {
                            appendLine("$speaker：")
                            appendLine("    ${resp.message}")
                        }
                    }
                }
                appendLine()
            }
        }

        if (recap.quotes.isNotEmpty()) {
            appendLine("--------------------------------------------------")
            appendLine("【经典台词辑录】")
            recap.quotes.forEach { quote ->
                appendLine("• ${quote.speaker}：“${quote.message}”")
            }
            appendLine()
        }

        if (recap.nextHint.isNotBlank()) {
            appendLine("【下回预告】")
            appendLine(recap.nextHint)
            appendLine()
        }
        appendLine("====================== 剧终 ======================")
    }

    /**
     * 转换为结构化 Markdown 战报。
     */
    fun formatAsMarkdownReport(recap: StoryRecapDto): String = buildString {
        appendLine("# 📜 剧情战报 · ${recap.title.ifBlank { "剧情复盘" }}")
        appendLine()
        val metaBadges = listOfNotNull(
            recap.timeHint.takeIf(String::isNotBlank)?.let { "⏱ **时间**：$it" },
            recap.location.takeIf(String::isNotBlank)?.let { "📍 **地点**：$it" },
            recap.atmosphere.takeIf(String::isNotBlank)?.let { "🎭 **基调**：$it" },
        )
        if (metaBadges.isNotEmpty()) {
            appendLine(metaBadges.joinToString(" | "))
            appendLine()
        }

        if (recap.summary.isNotBlank()) {
            appendLine("## 📖 故事概要")
            appendLine(recap.summary)
            appendLine()
        }

        if (recap.participants.isNotEmpty()) {
            appendLine("## 👥 参演角色")
            appendLine(recap.participants.joinToString(" · ") { "`$it`" })
            appendLine()
        }

        if (recap.events.isNotEmpty()) {
            appendLine("## 🎬 核心事件脉络")
            recap.events.forEachIndexed { index, event ->
                appendLine("### ${index + 1}. ${event.title.ifBlank { "关键事件" }}")
                val locTime = listOfNotNull(
                    event.timeHint.takeIf(String::isNotBlank),
                    event.location.takeIf(String::isNotBlank),
                ).joinToString(" · ")
                if (locTime.isNotBlank()) {
                    appendLine("> 📌 *$locTime*")
                }
                event.responses.forEach { resp ->
                    appendLine("- **${resp.speaker.ifBlank { "旁白" }}**：${resp.message}")
                }
                appendLine()
            }
        }

        if (recap.relations.isNotEmpty()) {
            appendLine("## 🔗 人物羁绊与关系演变")
            appendLine("| 关系对 | 状态/类型 | 变动依据 |")
            appendLine("|:---|:---|:---|")
            recap.relations.forEach { rel ->
                val label = rel.label.ifBlank { rel.pairKey }
                val changes = rel.changes.joinToString("，") {
                    "${it.label} ${if (it.delta >= 0) "+${it.delta}" else "${it.delta}"}"
                }.ifBlank { "平稳" }
                val reason = rel.reason.ifBlank { rel.evidence }.ifBlank { "剧情推进" }
                appendLine("| **$label** | $changes | $reason |")
            }
            appendLine()
        }

        if (recap.characterArcs.isNotEmpty()) {
            appendLine("## 🌱 角色成长与心境弧光")
            recap.characterArcs.forEach { arc ->
                appendLine("- **${arc.name}**：${arc.growthSummary}")
            }
            appendLine()
        }

        if (recap.quotes.isNotEmpty()) {
            appendLine("## 💬 名场面金句")
            recap.quotes.forEach { quote ->
                appendLine("> **${quote.speaker}**：“${quote.message}”")
            }
            appendLine()
        }

        if (recap.hooks.isNotEmpty()) {
            appendLine("## ❓ 未解伏笔")
            recap.hooks.forEach { hook ->
                appendLine("- $hook")
            }
            appendLine()
        }

        if (recap.nextHint.isNotBlank()) {
            appendLine("## 🔮 后续剧情前瞻")
            appendLine(recap.nextHint)
            appendLine()
        }
    }

    /**
     * 生成自带精美离线样式的独立 HTML 战报卡片。
     */
    fun formatAsHtmlCard(recap: StoryRecapDto): String = buildString {
        val title = escapeHtml(recap.title.ifBlank { "剧情复盘与战报" })
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"zh-CN\">")
        appendLine("<head>")
        appendLine("<meta charset=\"UTF-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        appendLine("<title>$title - 造梦战报</title>")
        appendLine("<style>")
        appendLine("""
            :root {
                --bg: #0f141c;
                --card-bg: #18202c;
                --accent: #d4af37;
                --text: #e2e8f0;
                --text-muted: #94a3b8;
                --border: #2d3748;
                --quote-bg: #1e293b;
            }
            @media (prefers-color-scheme: light) {
                :root {
                    --bg: #f8fafc;
                    --card-bg: #ffffff;
                    --accent: #b45309;
                    --text: #1e293b;
                    --text-muted: #64748b;
                    --border: #e2e8f0;
                    --quote-bg: #f1f5f9;
                }
            }
            body {
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
                background-color: var(--bg);
                color: var(--text);
                margin: 0;
                padding: 24px 16px;
                line-height: 1.6;
            }
            .container {
                max-width: 680px;
                margin: 0 auto;
                background: var(--card-bg);
                border: 1px solid var(--border);
                border-radius: 16px;
                padding: 28px 24px;
                box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.2);
            }
            .header {
                text-align: center;
                border-bottom: 2px solid var(--accent);
                padding-bottom: 18px;
                margin-bottom: 24px;
            }
            .header h1 {
                margin: 0 0 8px 0;
                font-size: 24px;
                color: var(--accent);
                letter-spacing: 1px;
            }
            .meta {
                font-size: 13px;
                color: var(--text-muted);
            }
            .section {
                margin-bottom: 24px;
            }
            .section-title {
                font-size: 16px;
                font-weight: 600;
                color: var(--accent);
                margin-bottom: 10px;
                display: flex;
                align-items: center;
            }
            .summary-box {
                background: var(--quote-bg);
                border-left: 4px solid var(--accent);
                padding: 12px 16px;
                border-radius: 0 8px 8px 0;
                font-size: 14px;
            }
            .event-card {
                background: var(--quote-bg);
                border-radius: 8px;
                padding: 12px 14px;
                margin-bottom: 10px;
                border: 1px solid var(--border);
            }
            .event-title {
                font-weight: 600;
                font-size: 14px;
                margin-bottom: 4px;
            }
            .dialogue-item {
                font-size: 13px;
                margin-top: 4px;
            }
            .speaker {
                font-weight: 600;
                color: var(--accent);
            }
            .quote-item {
                font-style: italic;
                padding: 8px 12px;
                margin: 6px 0;
                background: var(--quote-bg);
                border-radius: 6px;
                font-size: 13px;
            }
            .footer {
                text-align: center;
                font-size: 12px;
                color: var(--text-muted);
                margin-top: 28px;
                border-top: 1px dashed var(--border);
                padding-top: 14px;
            }
        """.trimIndent())
        appendLine("</style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("<div class=\"container\">")

        // Header
        appendLine("<div class=\"header\">")
        appendLine("<h1>$title</h1>")
        val metaParts = listOfNotNull(
            recap.timeHint.takeIf(String::isNotBlank)?.let { "时间：${escapeHtml(it)}" },
            recap.location.takeIf(String::isNotBlank)?.let { "地点：${escapeHtml(it)}" },
            recap.atmosphere.takeIf(String::isNotBlank)?.let { "基调：${escapeHtml(it)}" },
        )
        if (metaParts.isNotEmpty()) {
            appendLine("<div class=\"meta\">${metaParts.joinToString(" &nbsp;|&nbsp; ")}</div>")
        }
        appendLine("</div>")

        // Summary
        if (recap.summary.isNotBlank()) {
            appendLine("<div class=\"section\">")
            appendLine("<div class=\"section-title\">📖 故事概要</div>")
            appendLine("<div class=\"summary-box\">${escapeHtml(recap.summary)}</div>")
            appendLine("</div>")
        }

        // Events
        if (recap.events.isNotEmpty()) {
            appendLine("<div class=\"section\">")
            appendLine("<div class=\"section-title\">🎬 核心事件</div>")
            recap.events.forEachIndexed { i, ev ->
                appendLine("<div class=\"event-card\">")
                appendLine("<div class=\"event-title\">${i + 1}. ${escapeHtml(ev.title)}</div>")
                ev.responses.forEach { resp ->
                    appendLine("<div class=\"dialogue-item\"><span class=\"speaker\">${escapeHtml(resp.speaker.ifBlank { "旁白" })}：</span>${escapeHtml(resp.message)}</div>")
                }
                appendLine("</div>")
            }
            appendLine("</div>")
        }

        // Quotes
        if (recap.quotes.isNotEmpty()) {
            appendLine("<div class=\"section\">")
            appendLine("<div class=\"section-title\">💬 名场面金句</div>")
            recap.quotes.forEach { q ->
                appendLine("<div class=\"quote-item\">“${escapeHtml(q.message)}” —— <span class=\"speaker\">${escapeHtml(q.speaker)}</span></div>")
            }
            appendLine("</div>")
        }

        // Character Arcs
        if (recap.characterArcs.isNotEmpty()) {
            appendLine("<div class=\"section\">")
            appendLine("<div class=\"section-title\">🌱 角色心境成长</div>")
            recap.characterArcs.forEach { arc ->
                appendLine("<div style=\"font-size:13px;margin-bottom:4px;\"><strong>${escapeHtml(arc.name)}</strong>：${escapeHtml(arc.growthSummary)}</div>")
            }
            appendLine("</div>")
        }

        // Next Hint
        if (recap.nextHint.isNotBlank()) {
            appendLine("<div class=\"section\">")
            appendLine("<div class=\"section-title\">🔮 后续前瞻</div>")
            appendLine("<div style=\"font-size:13px;color:var(--text-muted);\">${escapeHtml(recap.nextHint)}</div>")
            appendLine("</div>")
        }

        // Footer
        appendLine("<div class=\"footer\">由 造梦 (Zaomeng) 智能蒸馏与会话引擎生成</div>")

        appendLine("</div>")
        appendLine("</body>")
        appendLine("</html>")
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
