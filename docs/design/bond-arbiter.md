# 设计：羁绊仲裁（Bond Arbiter）

> 状态：草案（待评审）
> 日期：2026-08
> 更新：2026-11-18（补 `mode` 边界、总开关判定顺序、`{role}::我` 隔离规则、手动路由契约、并发与幂等用例）
> 关联：docs/architecture.md、docs/server-api.md、docs/data-dictionary.md、kmp/server/AGENTS.md

## 1. 目标与定位

在「造梦」纯单机 + 模型的对话体验中，为每个已蒸馏角色建立一条**随时间累积、由模型裁决**的「角色 ↔ 用户」羁绊数值，让闲聊升级为可看到进展、有阈值反馈的养成玩法。

核心判据（本项目约束）：

- 完全单机，唯一外部依赖是模型本身；
- 模型是裁决者，不引入任何真实其他玩家或联网社区；
- 裁决**绝不阻塞**对话回复，且成本可配置；
- 复用现有关系数据（trust/affection/hostility/ambiguity）与后台派生机制，不新造存储范式。

## 2. 范围：MVP 到后续

**MVP（v1）只做：**

1. 角色 ↔ 用户的好感数值落盘（关系文件 + 会话内进度）；
2. 「深聊/恳谈」手动触发一次裁决；
3. 「每 N 回合」后台自动兜底裁决（默认 N，可配置）；
4. 达成阈值时给一条轻提示。

**后续（v2+，不在本次实现）：**

- 角色 ↔ 角色（AI 对 AI 关系漂移）裁决；
- 羁绊事件 / 特殊结局 / 图鉴徽章；
- 与动态剧本（三幕大纲）联动；
- 拆为声明式插件供工坊分发；
- `mode=settle`（场景/剧情收束时结算）：v1 **不实现**，配置里读到该值按 `manual` 处理，不得悄悄升级为自动裁决。

> MVP 的 `mode` 取值只有 `off | manual | every_n`。`settle` 保留在配置枚举里仅为 v2 预留，v1 读到即回退。

## 3. 配置项（内置玩法配置，方案 A）

挂在**顶层模型/玩法设置**（`DataModels.ModelSettings`，非 `ModelProfile`），复用 `StorageService.readModelSettings / writeModelSettings / loadModelSettings()` 读写，`SettingsManagementService` 落盘，`ModelContracts` 的 DTO 透传。

新增键（均为新增可选字段，默认值保证旧数据兼容）：

```jsonc
{
  "bond_arbiter_enabled":  true,              // 唯一总开关，默认 false（未发版即偷跑 token）
  "bond_arbiter_mode":     "manual",          // off | manual | every_n（settle 为 v2 预留，v1 回退 manual）
  "bond_arbiter_every_n":  5,                 // every_n 下每 N 回合后台裁决一次（2..20）
  "bond_arbiter_scope":    "to_user",         // to_user（v1 唯一实现值）| both（v2）
  "bond_arbiter_notify":   true               // 达成阈值时弹轻提示
}
```

**总开关语义（判定顺序固定）**

> 下方示例是用户**开启后**的状态；实际默认值为 `enabled=false`、`mode=manual`、`every_n=5`、`scope=to_user`、`notify=true`。

1. 先看 `bond_arbiter_enabled`：为 `false` 时**完全不触发**，`mode` / `every_n` / `notify` 全部忽略。这是唯一的关停手段。
2. `enabled=true` 后再看 `mode`：
   - `off`：关闭，等价于 `enabled=false`，不产生任何裁决调用。保留该值是为了让调用方能在不动总开关的情况下显式关停（例如按会话/按场景临时关闭）。
   - `manual`：只在用户点「恳谈」时触发一次，不自动裁决（零自动成本，默认值）。
   - `every_n`：后台累计满 N 回合裁决一次，不阻塞回复。
   - `settle`：v2 语义，v1 回退为 `manual`。
3. `mode=every_n` 时 `every_n` 超出 `2..20` 的非法值一律回退为 `5`；其他非法 `mode` 字符串回退为 `manual`（不能回退成 `every_n`，避免 bad 值放大 token 消耗）。

`bond_arbiter_notify` 只影响提示是否展示，不影响裁决与落盘。

## 4. 裁决协议（单对象 JSON，非对话 NDJSON）

裁决是**只返回一个 JSON 对象**的模型任务，因此使用 `LlmClient.chatCompletion(requireJsonObject = true)` 启用 provider 原生 JSON 约束；**不得**与对话主协议（NDJSON）混用 `response_format=json_object`。

裁决提示词输入（有界）：

- 本轮用户输入 `message`；
- 本轮各角色台词 `responses`（speaker + message）；
- 双方当前羁绊数值（关系文件读取）；
- 角色资料摘要 + 双方关系类型标签。

裁决输出（单对象）：

```json
{
  "deltas": [
    {"speaker": "林黛玉", "target": "我",
     "trust_delta": 1, "affection_delta": 2, "hostility_delta": 0, "reason": "……"}
  ]
}
```

约束：

- `deltas` 每项必填 `speaker/target/trust_delta/affection_delta/hostility_delta`；
- 增量取整且单次 |delta| ≤ 3（防单轮剧变）；最终值 `clamp(0..10)`；
- `target` 第一版恒为 `"我"`（to_user scope）；
- 输出不可带 Markdown 围栏或前后缀文字（结构化约束 + 严格解析校验兜底）。

## 5. 数据落地（双层）

裁决产出**同一份**结果，写到两处：

1. **run 级长期羁绊**：复用 `RelationsService` 写入 `runs/{run_id}/artifacts/relations/{novel_id}_relations.md`，更新 `trust/affection/hostility/ambiguity` 与 `updated_at`。这使羁绊**跨会话累积**，并可被会话内羁绊面板读取（不进入角色关系图，见下方隔离规则）。
   - 注意：现有 relations 文件是「角色 ↔ 角色」配对，`pair_key` 形如 `甲::乙`。MVP 为「角色 ↔ 用户」引入约定配对键 `{role}::我`，并使用哨兵常量 `BOND_USER_TARGET = "我"` 标记右侧，规则如下：
     - **写入**：只写右侧为哨兵的那一条，`{role}` 必须是当前参与角色；`role` 段不得为空、不得包含 `::`、`/`、`..`，写前经 `PathSafety` 语义校验。
     - **`splitPair`**：返回 `(left, right)` 后由调用方按 `right == BOND_USER_TARGET` 区分「角色↔我」与「角色↔角色」，不新增拆分别名。
     - **关系路由 / 关系图 / 冲突与派系统计**：默认**排除**右侧为哨兵的条目，避免「我」被当作一个角色节点污染角色间关系度数、阵营分组与冲突统计。
     - **展示**：「角色↔我」只在羁绊面板（会话内数值行 + 阈值提示）展示，「我」不进入角色关系图；如需在图里出现，必须先加显式参数（如 `include_user_bond=true`）并单独渲染为玩家节点。
     - **删除**：删除角色档案时一并清理 `{role}::我`，与角色↔角色条目同一级联语义。
2. **session 级羁绊进度**：写入 session manifest 新增字段 `bond_progress`（本轮增量 + 各角色当前羁绊值 + 最近一次裁决 turn_id），用于 UI 即时反馈与幂等去重。字段形状（全部可选，缺省视为从未裁决，兼容旧 manifest）：

```jsonc
"bond_progress": {
  "roles": {
    "林黛玉": {"trust": 6, "affection": 7, "hostility": 0, "updated_at": "2026-08-17T09:00:00Z"}
  },
  "pending_turns": 3,                  // every_n 累积的未裁决 turn 数，裁后归零
  "last_arbitrated_turn_id": "turn-0042",
  "last_operation_id": "bond-turn-001",
  "recent_operations": ["bond-turn-001"]  // 有界窗口（保留最近 20 条）用于幂等重放
}
```

`roles` 里的数值必须与 run 级 relations 同源：先算增量 → 写 relations 文件 → 用合并后的值回填 `bond_progress`，两步不一致时以 relations 文件为准。

## 6. 执行位置（不阻塞回复）

挂接点：`DialogueService.drainBackgroundPostTurns`（后台派生批处理，会话锁内串行、批合并、重放幂等），在既有 `updateKnowledgeLedger / refreshDerivedSessionState / syncWorldMemory / longTermMemory.appendTurn` 之后新增一步 `runBondArbiterIfDue(...)`。

理由：

- `commitTurn` 提交即返回，`complete` SSE 立刻可达用户，**裁决不占用回复关键路径**；
- 后台派生已是现有派生任务的落点，符合「不在回复关键路径串行加载」约束；
- 批合并天然支持 `every_n` 的「攒够 N 轮才裁一次」语义（按 turn 累积计数，会话锁内判断）。

触发判定布尔（在 batch 内，顺序同 §3）：

- `enabled == true` 且 `scope == to_user`；否则整批跳过，不看 `mode`；
- `mode == off`：跳过；
- `mode == settle`：v1 按 `manual` 处理，即不自动裁决；
- `mode == every_n`：累计未裁决 turn 数 ≥ N 才裁，裁后清零；
- `mode == manual`：仅当本轮携带「恳谈」标记（来自专用会话动作请求）才裁。

## 7. 手动「恳谈」触发（UI/接口）

复用现有**会话内插件动作**风格的轻接口，或新增一个最小 route（另行在 `docs/server-api.md` 登记）。**本方案取独立的轻 route**，不复用「第四面墙/恳请」输入：后者会占用一轮对话语义与 transcript，语义上不属于「用户对角色说话」。

### 7.1 契约

```
POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/bond/arbitrate
```

请求体（`snake_case`，对齐对话路由既有约定）：

```json
{"operation_id": "bond-turn-001"}
```

- `operation_id` 必填、非空，由客户端生成并稳定复用；缺失或空白返回 `400`。
- 服务端先判 `bond_arbiter_enabled`，再判 `mode != off`；不满足直接拒绝，不进入模型调用。
- 只读**有界**的最近 transcript（与生成路径同一窗口上限），不写新对话轮，不刷新 session transcript。

成功 `200`：

```json
{
  "operation_id": "bond-turn-001",
  "decided_turn_id": "turn-0042",
  "deltas": [
    {"speaker": "林黛玉", "target": "我",
     "trust": 1, "affection": 2, "hostility": 0,
     "trust_value": 6, "affection_value": 7, "hostility_value": 0}
  ],
  "progress": {"林黛玉": {"trust": 6, "affection": 7, "hostility": 0, "updated_at": "2026-08-17T09:00:00Z"}}
}
```

- `deltas` 与 `progress` 字段同义：前者是本轮增量 + 落盘后的当前值，后者是合并后的快照，仅供 UI 直接渲染。
- `target` 在 v1 恒为 `"我"`。

错误统一 `{"error": "..."}`：

| 状态 | 触发条件 | `error` 文案方向 |
|---|---|---|
| `400` | `operation_id` 缺失/空白 | `"operation_id is required."` |
| `404` | run 或 session 不存在 | `"Session not found."` |
| `409` | `bond_arbiter_enabled=false` 或 `mode=off` | `"Bond arbiter is disabled."` |
| `409` | 有界 transcript 无可裁决轮次 | `"No arbitrable turns in this session."` |
| `500` | 模型调用失败 / 输出不合法 | `"Bond arbitration failed."`（不返回模型原文） |

### 7.2 幂等

- 同一 `operation_id` 重放**返回首次结果**，不重复累加；服务端按 `operation_id` 落一条裁决记录（session manifest 的 `bond_progress.recent_operations`，按 turn 保留有界窗口）。
- 同一 `turn_id` 被不同 `operation_id` 请求时，以「最近裁决 turn_id」判定已裁决，直接返回当前快照、不再调模型。
- 该路由与后台 `every_n` 批任务共用会话锁，串行执行，不存在两侧同时裁决同一 turn 的窗口。

## 8. UI 展示（MVP 最小化）

- 会话内：某个轻量「羁绊」徽标/数值行，展示各参与角色对「我」的 `affection/trust` 当前值；阈值达成时弹一条提示（受 `bond_arbiter_notify` 控制）。
- 人物关系页：**不改**。按 §5 的隔离规则，「我」不是角色节点，`{role}::我` 不进入关系图、冲突统计与派系统计；羁绊数值只在会话内羁绊面板展示。若将来要让「我」出现在图里，先按 §5 加显式开关并单独渲染玩家节点。
- 设置页：新增「羁绊仲裁」分组（开关 + 模式下拉 + N 值 + 提示开关），复用 `ModelSettingsScreen` 既有表单模式。

## 9. 成本与安全

- **模型调用成本**：仅 `every_n`（每 N 轮 +1 次）与 `manual`（用户主动 +1 次）产生；默认 `manual` 保证零自动驾驶成本。裁决仍严格要求结构化 + 严格解析校验。
- **失败处理分两条路径**：后台批任务失败**静默跳过**（记录截断日志，不阻断对话，不累加 `pending_turns` 以外的状态）；手动 route 失败返回 `500 {"error":"Bond arbitration failed."}`，且不写任何进度，允许用户重试同一 `operation_id`。
- **幂等**：同一 `turn_id` 重放不重复累加（后台批任务以 turn 计数 + 最近裁决 turn_id 去重；手动 route 另以 `operation_id` 去重）。
- **路径/安全**：新配对键 `{role}::我` 经 `PathSafety` 语义校验；提示词不入日志明文，数值落盘走 `writeTextAtomically`。
- **API 密钥**：不进入关系文件或 session 进度，仍只经 `ModelApiKeyService`。

## 10. 测试清单（JVM 回归，遵循 kmp/server/AGENTS.md）

- 裁决解析：合法 `deltas`、缺字段、越界 delta、clamp 边界、Markdown 围栏兜底；
- 配置解析：默认值、bad 值回退（`mode` 非法→`manual`、`every_n` 越界→5、`settle`→`manual`）、`enabled=false` 时忽略 `mode`；
- `every_n` 计数与触发：N 回合边界、批合并、重放幂等不重复累加；
- 落盘：run 级关系文件与 session 级 `bond_progress` 一致、跨会话累积；
- 手动路由：`off` / `enabled=false` 拒绝、无 transcript 拒绝、`operation_id` 缺失 400、同一 `operation_id` 重放返回首次结果；
- 并发：同一 `turn_id` 并发两次请求只累加一次；后台 `every_n` 与手动 route 同时命中时在会话锁内串行，不出现双写；
- 关系图隔离：`{role}::我` 不出现在角色关系条目/冲突与派系统计中；删除角色时该配对键同步清理；
- 安全：非法 `pair_key`（空 `role`、含 `::`/`/`）、路径穿越拒绝。

## 11. 落地文件清单（预估）

- `kmp/server/storage/.../DataModels.kt`：`ModelSettings` 新增可选字段；
- `kmp/server/.../services/SettingsManagementService.kt`：读/写/回退；
- `kmp/core/contracts/.../ModelContracts.kt`：DTO 透传新键；
- `kmp/server/.../services/BondArbiter*.kt`：裁决提示词 + 解析 + 触发判定（新文件）；
- `kmp/server/.../services/DialogueService.kt`：后台派生挂接 `runBondArbiterIfDue`；
- `kmp/server/.../services/RelationsService.kt`：识别 `{role}::我` 配对；
- `kmp/server/.../routes/`：手动 `/bond/arbitrate` 路由；
- `kmp/app/shared/.../settings/` 与 `feature/chat/`：设置分组 + 会话内羁绊展示；
- `prompts/` + `kmp/server/src/androidMain/assets/`：裁决提示词双份同步；
- `docs/server-api.md`：在「对话辅助与高级能力」章节按既有 `| 方法 | 路径 | 请求 | 成功响应 |` 表格登记 `/bond/arbitrate`，并写明 §7.1 的状态码（400/404/409/500）与 `{"error": "..."}` 文案；与实现同一次提交，不提前登记。
- `docs/data-dictionary.md`：登记以下条目——
  - `ModelSettings` 新键：`bond_arbiter_enabled`、`bond_arbiter_mode`、`bond_arbiter_every_n`、`bond_arbiter_scope`、`bond_arbiter_notify`（含默认值与非法值回退规则）；
  - session manifest 字段 `bond_progress` 及其子字段 `roles` / `pending_turns` / `last_arbitrated_turn_id` / `last_operation_id` / `recent_operations`；
  - relations 约定配对键 `{role}::我` 与哨兵常量 `BOND_USER_TARGET`（含关系图排除语义）。
- `docs/adr/0005-bond-arbiter.md`：记录机制决策（单对象模型任务 + 后台派生 + 默认关闭），本设计稿评审通过后转为「已采纳」。
