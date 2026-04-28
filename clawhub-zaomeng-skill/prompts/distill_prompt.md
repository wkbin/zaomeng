# 人物档案蒸馏提示词

## 任务

从分段小说原文中，蒸馏结构化、通用化、可落盘的人物心智档案。

## 输入

- 小说文本片段
- 候选人物列表（可选）
- 已有修正记录或用户补充（可选）
- 可选的小说级角色专属约束文件 `rules/character_hints/<novel_id>.md`（若存在）

## 输出

- 严格遵循 `references/output_schema.md` 的 Markdown 人物档案
- 优先产出 `PROFILE.generated.md`
- 如信息足够，可同步支持导航内的可选人格文件拆分：`SOUL`、`GOALS`、`STYLE`、`TRAUMA`、`IDENTITY`、`BACKGROUND`、`CAPABILITY`、`BONDS`、`CONFLICTS`、`ROLE`

## 26 维度覆盖要求

输出时必须尽量覆盖以下 26 个蒸馏维度；若原文证据不足，可留空、降置信度或明确写成“证据不足”，但禁止脑补：

1. 核心身份
2. 核心动机
3. 性格基底
4. 行为逻辑
5. 人物弧光
6. 关键羁绊
7. 符号化特征
8. 世界观适配性
9. 价值取舍体系
10. 情绪反应模式
11. 思维认知偏好
12. 语言表达特质
13. 专属能力与致命短板
14. 出身背景与生存处境
15. 深层执念与隐秘欲望
16. 行事风格倾向
17. 过往创伤与人生烙印
18. 社交相处模式
19. 内在自我矛盾
20. 剧情职能定位
21. 恐惧与避讳事物
22. 信仰与精神支柱
23. 认知局限与成长短板
24. 立场摇摆特性
25. 恩怨奖惩逻辑
26. 私下真实面貌

## 字段分组约束

输出时按以下 12 组理解字段边界，避免把相近概念反复填进多个字段：

1. 基础身份定位层：`core_identity`、`faction_position`、`story_role`、`stance_stability`、`identity_anchor`
2. 根源底层层：`background_imprint`、`life_experience`、`trauma_scar`、`taboo_topics`、`forbidden_behaviors`
3. 核心精神内核：`soul_goal`、`hidden_desire`、`core_traits`、`temperament_type`、`values`、`worldview`、`belief_anchor`、`moral_bottom_line`、`restraint_threshold`
4. 价值与内在矛盾：`inner_conflict`、`self_cognition`、`private_self`
5. 思维与认知模式：`thinking_style`、`cognitive_limits`
6. 行为决策逻辑：`decision_rules`、`action_style`、`reward_logic`
7. 情绪与应激反应：`fear_triggers`、`stress_response`、`anger_style`、`joy_style`、`grievance_style`
8. 社交模式：`social_mode`、`others_impression`、`key_bonds`
9. 语言表达特征：`speech_style`、`typical_lines`、`cadence`、`signature_phrases`、`sentence_openers`、`connective_tokens`、`sentence_endings`、`forbidden_fillers`
10. 能力与短板：`strengths`、`weaknesses`
11. 人物成长弧光：`arc_start`、`arc_mid`、`arc_end`
12. 世界规则适配：`world_rule_fit`

## 规则

1. 只提取原文有直接依据的内容，禁止编造、脑补、过度解读。
2. 所有量化分值必须使用整数；`values` 维持 `0–10` 区间。
3. 若文本内人物线索稀少、证据薄弱，降低整体置信度，并在相关字段保持克制。
4. `core_traits`、`typical_lines`、`decision_rules`、`strengths`、`weaknesses`、`fear_triggers` 等列表必须自动去重，避免同义重复。
5. 全程使用通用描述逻辑，不绑定单本小说专属黑话、专属 archetype 标签或作品内私有名词模板。
6. 若某维度无法被当前片段支持，不要用“常识”补全；保持空值或保守概括即可。
7. 人物弧光必须基于当前可见片段；若不足以判断成长变化，明确按“静态人物 / 当前片段不足以判断弧光”处理。
8. 语言表达特质必须来自说话内容、叙述描写或稳定互动习惯，不能套用万能“冷静、理智、温和”模板。
9. 对深层执念、私下真实面貌、内在冲突等高风险维度，宁缺毋滥，优先短句事实总结。
10. 如果输入中包含用户纠正或长期修正记录，应把它们视为高优先级约束，但不得覆盖原文中明确相反的硬证据。
11. `trauma_scar` 必须写“至今仍在起作用的旧伤”，不是简单重复 `life_experience`。
12. `temperament_type` 写整体气质底色，不能只把 `core_traits` 原样抄一遍。
13. `moral_bottom_line`、`restraint_threshold`、`stress_response` 必须体现触发条件或失控边界，不能只写抽象美德。
14. `self_cognition` 与 `others_impression` 可以形成反差，但两者都必须有证据，不得硬造“反差感”。
15. 若 `rules/character_hints/<novel_id>.md` 为该角色提供了专属约束，应把它视为高优先级收束条件：可用于收紧表达边界、避免同质化，但不能拿它覆盖明确相反的正文证据。

## 多角色蒸馏差分要求

当一次蒸馏多个角色时，必须把每个角色视为独立任务处理，禁止把同一段通用描述平铺给所有角色。

1. 每个角色都要明确回答“这个角色与同批其他角色最不同的地方是什么”。
2. `identity_anchor`、`soul_goal`、`background_imprint`、`social_mode`、`reward_logic`、`belief_anchor`、`temperament_type`、`stress_response` 必须优先写角色特有的驱动力、立场来源或行为偏好，禁止直接填写群体共性。
3. 像“想活下去”“保护同伴”“谨慎观察局势”这类泛化描述，只有在被进一步收紧为该角色自己的动机路径时才可保留。
4. 若多个角色共享同一事件、同一阵营或同一行动线，必须写出他们在该共享场景中的不同站位、不同收益判断或不同心理落点。
5. 若某字段与其他角色高度重合且缺少更细证据，宁可留空、降低置信度，也不要输出模板化重复内容。

## 共场景证据约束

多人共同出现的场景证据不能直接批量写入所有角色的背景与人格字段。

1. 共享场景优先用于提取 `key_bonds`、关系变化、冲突点、互动节奏。
2. `background_imprint`、`life_experience`、`identity_anchor` 等字段只应吸收明确聚焦该角色本人经历、选择、代价或自我认知的证据。
3. 如果某段文本只是多人同场行动，而没有明确显示目标角色的独特视角、台词、决策或代价，不应把它当作该角色的背景烙印。
4. 对同场景中的不同角色，应分别总结“此人做了什么、为什么这么做、显露了什么特质”，而不是复制同一段剧情摘要。

## 输出前自检

输出前至少做一次区分度自检：

1. 检查本角色与同批角色在 `identity_anchor`、`soul_goal`、`background_imprint`、`social_mode`、`reward_logic`、`belief_anchor`、`temperament_type`、`stress_response` 上是否存在明显区分。
2. 若发现多个字段可直接互换到另一个角色身上仍然成立，说明蒸馏过于泛化，需要重写。
3. `BONDS` 可以共享同一事件来源，但描述必须体现该角色对关系的主观视角，不能 99% 复用别人的关系文案。
