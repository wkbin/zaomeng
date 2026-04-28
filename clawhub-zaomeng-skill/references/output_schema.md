# 输出规范

## 人物档案

```md
# PROFILE
<!-- Canonical markdown profile storage. -->

## Meta
- name: 角色名
- novel_id: sample_novel
- source_path: data/sample_novel.txt

## Basic Positioning
- core_identity: 核心身份与定位
- faction_position: 阵营、派系、立场位置
- story_role: 剧情职能定位
- stance_stability: 立场稳定度或摇摆特性
- identity_anchor: 角色在世界中的自我定位
- world_rule_fit: 人物理念与世界观规则的契合度描述

## Root Layer
- background_imprint: 出身背景与成长烙印
- life_experience: 关键经历；重要转折；长期处境
- trauma_scar: 过往创伤留下的精神擦痕
- taboo_topics: 不能触碰的话题
- forbidden_behaviors: 绝不会做的行为

## Inner Core
- soul_goal: 长期驱动目标
- hidden_desire: 深层执念或隐秘欲望
- core_traits: 性格1；性格2
- temperament_type: 整体气质底色
- values: 勇气=0；智慧=0；善良=0；忠诚=0；野心=0；正义=0；自由=0；责任=0
- worldview: 对世界、规则、善恶、秩序的基本看法
- belief_anchor: 信仰与精神支柱
- moral_bottom_line: 道德底线与不能跨过的红线
- restraint_threshold: 欲望与情绪平时如何克制、何时会失控

## Value And Conflict
- inner_conflict: 内在矛盾
- self_cognition: 自我认知与自我定位
- private_self: 私下真实面貌
- thinking_style: 理性/感性/短视/长远等思考偏好
- cognitive_limits: 认知盲区；成长短板

## Decision Logic
- decision_rules: 条件->反应；固定决策准则
- reward_logic: 恩怨奖惩逻辑
- action_style: 行事风格倾向

## Emotion And Stress
- fear_triggers: 恐惧点；雷点；避讳触发项
- stress_response: 高压、绝境、崩溃时的应激人格
- anger_style: 生气时的表达方式
- joy_style: 开心时的表达方式
- grievance_style: 委屈/受压时的表达方式

## Social Pattern
- social_mode: 社交距离与相处模式
- others_impression: 他人观感；外界标签
- key_bonds: 关键羁绊；宿命联结

## Voice
- speech_style: 语言风格描述
- typical_lines: 代表性台词；高辨识表达
- cadence: 说话节奏
- signature_phrases: 专属口头禅；标志句式
- sentence_openers: 常见起句
- connective_tokens: 常用连接词
- sentence_endings: 常见收尾方式
- forbidden_fillers: 禁用口水词；禁用通用助词

## Capability
- strengths: 专属能力；擅长项
- weaknesses: 性格缺陷；明显短板

## Arc
- arc_start: 勇气=5；立场=6
- arc_mid: 勇气=6；trigger_event=事件
- arc_end: 勇气=7；final_state=状态

## Evidence
- description_count: 1
- dialogue_count: 2
- thought_count: 0
- chunk_count: 1
```

规则：

- `core_traits` max 10 unique items
- `typical_lines` max 8 unique items
- `decision_rules` max 8 unique items
- list-like fields use `；` as the separator in markdown scalar lines
- `values` all integers in `[0,10]`
- evidence fields store counts, not raw text arrays
- any deep persona field without solid evidence may stay empty
- `arc_start` / `arc_mid` / `arc_end` 只有在识别到稳定阶段变化时才应量化；若证据不足，应留空或仅保留 `trigger_event` / `final_state` 的未判定说明

### 易混字段收紧定义

- `identity_anchor`: 角色如何定义“我是谁、我站在哪边、我凭什么行动”。必须是该角色的自我定位，不能只写阵营口号或团队共识。
- `soul_goal`: 长期驱动力或执念，要求能解释该角色在关键选择里反复追求什么。禁止只写“活下去”“赢下来”“保护同伴”这类任何人都适用的空泛目标。
- `background_imprint`: 对当前人格与决策有持续影响的个人经历、出身或代价。共享剧情只能在明确塑造了该角色本人时写入，不能把多人同场剧情直接复制过来。
- `trauma_scar`: 不是简单的“惨经历摘要”，而是至今仍会影响其反应方式的旧伤与精神擦痕。
- `temperament_type`: 描写整体气质底色，不等同于零散性格词堆砌，要求一眼能区分人物的外在观感。
- `social_mode`: 角色如何靠近、试探、利用、保护或疏离他人，强调其稳定的相处策略，而不是笼统写“重视同伴”“谨慎社交”。
- `reward_logic`: 角色如何判断谁值得回报、谁该惩罚、何时翻脸、何时让步。要体现其个人奖惩标准，不能套通用的善恶判断。
- `belief_anchor`: 角色遇到高压局面时仍会抓住的信念、规则、执念或精神支柱。必须指向该角色自己的判断依据，而非作品级大道理。
- `moral_bottom_line`: 明确“什么情况下角色仍不会跨线”，优先写不可背叛、不可牺牲、不可自毁的底线。
- `self_cognition`: 写角色如何看待自己，可与 `others_impression` 形成反差，但不能凭空制造双面人设。
- `stress_response`: 必须描述高压、绝境、崩溃边缘时的反常面，而不是重复日常 `action_style`。
- `others_impression`: 写外人眼里的第一印象、常见标签或社交观感，不等于角色自评。
- `restraint_threshold`: 写欲望、愤怒、嫉妒或执念平时如何被压住，以及在什么条件下会失控。

### 多角色蒸馏约束

- 一次同时蒸馏多个角色时，上述字段必须体现彼此差异，默认需要回答“这个角色和同批其他角色最不同的地方是什么”。
- 若多个角色共享同一场景，关系字段可共用事件来源，但 `background_imprint`、`identity_anchor`、`soul_goal` 等字段必须只保留该角色独有的视角与后果。
- 若某字段与其他角色高度雷同且缺少更强证据，应留空或写“证据不足”，不要用模板化描述填满。

### 推荐字段分组

为避免录入时重复堆叠同义字段，推荐按以下 12 组理解和填写：

1. 基础身份定位层：`core_identity` / `faction_position` / `story_role` / `stance_stability` / `identity_anchor`
2. 根源底层层：`background_imprint` / `life_experience` / `trauma_scar` / `taboo_topics` / `forbidden_behaviors`
3. 核心精神内核：`soul_goal` / `hidden_desire` / `core_traits` / `temperament_type` / `values` / `worldview` / `belief_anchor` / `moral_bottom_line` / `restraint_threshold`
4. 价值与内在矛盾：`inner_conflict` / `self_cognition` / `private_self`
5. 思维与认知模式：`thinking_style` / `cognitive_limits`
6. 行为决策逻辑：`decision_rules` / `action_style` / `reward_logic`
7. 情绪与应激反应：`fear_triggers` / `stress_response` / `anger_style` / `joy_style` / `grievance_style`
8. 社交模式：`social_mode` / `others_impression` / `key_bonds`
9. 语言表达特征：`speech_style` / `typical_lines` / `cadence` / `signature_phrases` / `sentence_openers` / `connective_tokens` / `sentence_endings` / `forbidden_fillers`
10. 能力与短板：`strengths` / `weaknesses`
11. 人物成长弧光：`arc_start` / `arc_mid` / `arc_end`
12. 世界规则适配：`world_rule_fit`

## 人格包文件

蒸馏过程还可以在以下目录下生成可选人格包：

```text
runtime/data/characters/<novel_id>/<角色名>/
```

常见文件：

- `NAVIGATION.generated.md`: generated load order and file intent
- `NAVIGATION.md`: manual override and navigation supplement
- `PROFILE.generated.md`: canonical generated profile
- `PROFILE.md`: manual override profile
- `RELATIONS.generated.md`: generated target-specific relations
- `RELATIONS.md`: manual relation overrides
- `MEMORY.md`: durable memory and user corrections

可选的聚焦人格文件：

- `SOUL.generated.md`
- `GOALS.generated.md`
- `STYLE.generated.md`
- `TRAUMA.generated.md`
- `IDENTITY.generated.md`
- `BACKGROUND.generated.md`
- `CAPABILITY.generated.md`
- `BONDS.generated.md`
- `CONFLICTS.generated.md`
- `ROLE.generated.md`

## 关系图谱

```md
# RELATION_GRAPH

## 林黛玉_贾宝玉

- trust: 8
- affection: 9
- power_gap: 1
- conflict_point: 表达方式差异
- typical_interaction: 黛玉质问->宝玉安抚->短暂缓和
- appellation_to_target: 宝玉
- confidence: 8
```

规则：

- relation section title must use sorted key format `<A>_<B>`
- `trust` and `affection` in `[0,10]`
- `power_gap` in `[-5,5]`
- `confidence` in `[0,10]`
- 关系条目必须基于同场景互动证据

## 聊天约束（可选）

```md
# CHAT_CONSTRAINTS

- character: 林黛玉
- must_follow: 语气克制但可反讽；冲突时先防御再观察
- must_avoid: 无证据的极端背叛表述；与高忠诚值冲突的抛弃宣言
- fallback_action: rewrite_once_then_needs_revision
```

## 纠错输出

```md
# CORRECTION

- corrected_message: 修正后的台词
- correction_reason: 基于哪些人格字段收紧
- confidence: 7
```
