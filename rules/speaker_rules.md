---
question_tokens: [是否, 要不要, 该不该, 可否, 能否]
war_tokens: [战事, 对抗, 联合, 联手, 结盟, 出兵, 守城, 攻势, 冲突]
rest_tokens: [安稳, 清闲, 小聚, 团聚, 暂歇, 太平]
view_tokens: [怎么看, 如何, 何如, 怎么想, 依你看, 依诸位看]
care_tokens: [可安, 可好, 无恙, 辛苦, 担心, 挂念]
trait_priority_map:
  谨慎: 智慧
  机变: 智慧
  聪慧: 智慧
  敏感: 善良
  克制: 责任
  勇敢: 勇气
  忠诚: 忠诚
  善良: 善良
  执拗: 正义
  傲气: 自由
  温柔: 善良
  虔诚: 忠诚
  仁厚: 责任
  豪爽: 勇气
  沉稳: 责任
  诙谐: 自由
  圆滑: 自由
fragment_stopwords: [可以, 只是, 不过, 如今, 今日, 明日, 这个, 那个, 这样, 那里, 这里, 你们, 我们, 他们]
preferred_leading_chars: [我, 这, 那, 只, 便, 却, 若, 既, 可, 原, 何, 先]
preferred_trailing_chars: [了, 吧, 呢, 么, 啊, 也]
durable_guidance_tokens: [记住, 设定, 人设, 以后, 别再, 不要再, 改成, 纠正, 必须, 不要, 应该]
single_chat_markers: [单聊, 单独聊, 单独说话]
---

# SPEAKER RULES

This file only keeps routing and parsing signals.
Final phrasing should come from the LLM instead of authored rule templates.
