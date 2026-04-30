# 校验策略

## 用途

通过分层校验，约束蒸馏输出的人设真实性、区分度和可演绎性，减少脑补、同质化和 OOC。

## 校验层级

### 1. 证据校验

- 每个关键结论都必须能对应到至少一条原作正文证据。
- `evidence_source` 优先记录章节、段落、片段索引，不复制大段原文。
- 如果没有直接证据，结论不得写实锤，应留空或写 `证据不足`。

### 2. 一致性校验

- 性格特征、决策规则、语言风格不得与 `values`、`belief_anchor`、`moral_bottom_line` 明显冲突。
- `decision_rules` 与 `action_style` 必须区分：前者解释判断逻辑，后者解释执行风格。
- `self_cognition` 与 `others_impression` 可以形成反差，但两者都必须有依据。
- `arc_*`、`timeline_stage`、`contradiction_note` 必须彼此对得上，不能前后混用。

### 3. 原作优先校验

- 只采信原作正文中的描写、行为、对话、心理活动与明确叙述。
- 不采信同人、影视改编附会、读者脑补和“合理推测式补全”。
- `appearance_feature`、`plot_restriction`、`ooc_redline` 这类高风险字段必须更加保守。

### 4. 权重校验

- 长期稳定性格 > 一时冲动
- 核心底色 > 阶段性反常
- 成长烙印 > 临时环境刺激
- 若角色前后割裂明显，必须通过 `timeline_stage` 或 `contradiction_note` 显式说明

### 5. 差分校验

- 多角色同批蒸馏时，`identity_anchor`、`soul_goal`、`temperament_type`、`social_mode`、`reward_logic`、`interest_claim`、`resource_dependence` 不得大面积同质化。
- 共享场景可以复用为关系证据，但不能直接平铺成多人共同的背景和精神内核。
- 如果某条描述轻易能替换到另一角色身上仍成立，应判为泛化过度，需要重写。

### 6. 公平蒸馏校验

- 对反派、灰色人物、失败者保持客观，不洗白，不丑化，不道德审判。
- 只记录其逻辑、利益、创伤、欲望、边界与代价。

## 通过 / 失败规则

- 通过：证据、一致性、原作优先、权重、差分五层均通过
- 软失败：仅 1 层不通过，需要修正后再输出
- 硬失败：2 层及以上不通过，返回 `needs_revision`
