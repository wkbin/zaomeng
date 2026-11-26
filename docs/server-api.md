# 造梦 KMP 服务端接口文档

本文档描述当前主实现中由 `LocalBackendController` 实际注册的 Ktor HTTP 接口。接口实现位于 `kmp/server`，客户端与服务端共享契约位于 `kmp/core/contracts`。旧 Python/Web 后端不在本文档范围内；标为“兼容别名”的路径仅用于兼容旧调用方。

> 基线：2026-08-11 当前工作区。共 21 组路由、115 个有效的“HTTP 方法 + 路径”组合（卡片 CRUD 的三类路径分别计数）。

## 1. 通用约定

### 1.1 服务地址

服务仅监听本机回环地址：

```text
http://127.0.0.1:{动态端口}
```

应用默认传入端口 `0`，由操作系统分配可用端口。客户端应从后端控制器取得实际端口，不应硬编码。

所有业务接口以 `/api/web` 为前缀。

### 1.2 认证

除健康检查外，配置了非空 token 时，所有 `/api/web/**` 接口都要求 Bearer Token：

```http
Authorization: Bearer <token>
```

- `GET /api/web/health` 无需认证。
- token 缺失或错误返回 `401`，响应为 `{"error":"Bearer authentication is required."}`，并带 `WWW-Authenticate: Bearer`。
- 当前应用控制器的默认 token 为 `dev-token`，但调用方应使用运行时提供的 token，不应依赖该默认值。

### 1.3 编码与字段命名

- JSON 请求使用 `Content-Type: application/json; charset=utf-8`。
- JSON 字段通常使用 `snake_case`；插件清单中的部分字段因插件 API 契约使用 `camelCase`，详见插件章节。
- 服务端 JSON 配置为宽松解析并忽略未知字段；响应会编码默认字段。
- `{character}`、`{pair_key}`、插件 ID 等路径参数必须进行 URL 编码。
- 时间字段是 ISO 8601 字符串；ID 是不透明字符串，调用方不应解析其格式。

### 1.4 通用错误

所有 HTTP 路由使用统一的 `error` 字段：

```json
{"error": "错误说明"}
```

常见状态码：

| 状态码 | 含义 |
|---|---|
| `200 OK` | 查询、更新或操作成功 |
| `201 Created` | 创建书卷、导入包、创建会话或世界事实成功 |
| `400 Bad Request` | 参数缺失、请求体无效或业务校验失败 |
| `401 Unauthorized` | Bearer Token 缺失或无效 |
| `404 Not Found` | 书卷、会话、章节、人物或插件不存在 |
| `501 Not Implemented` | 明确未实现的非流式建议接口 |
| `502 Bad Gateway` | 章节/卡片等模型生成失败 |
| `500 Internal Server Error` | 未处理异常；全局兜底响应不暴露内部细节 |

客户端可继续兼容历史版本的 `detail` 字段，但当前服务端只返回 `error`。

### 1.5 标识与分页

本文用以下占位符：

| 参数 | 含义 |
|---|---|
| `{run_id}` | 书卷蒸馏运行 ID |
| `{session_id}` | 对话会话 ID |
| `{chapter_id}` | 章节 ID |
| `{character}` | 人物名，须 URL 编码 |
| `{pair_key}` | 人物关系对键，须 URL 编码 |
| `{card_id}` | 可复用卡片 ID |
| `{plugin_id}` | 插件 ID |

列表接口使用基于 offset 的分页。除消息列表外，会话列表的公共参数为：

| Query | 类型 | 默认值 | 约束 |
|---|---:|---:|---|
| `offset` | int | `0` | 小于 0 时归零 |
| `limit` | int | `50` | `1..200` |
| `q` | string | `""` | 截断到 120 字符 |
| `sort` | string | `recent` | `recent` 或 `title` |

响应形状为：

```json
{"items": [], "total": 0, "has_more": false}
```

## 2. 健康检查与诊断

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/health` | 无 | `200` `HealthResponse` |
| GET | `/api/web/diagnostics/export` | 无 | `200` 诊断 JSON；下载名 `zaomeng-diagnostics.json` |
| GET | `/api/web/diagnostics/sessions/{run_id}/{session_id}/latest-request` | 无 | `200 ModelRequestRecord`；无记录时 `404` |

健康检查响应固定为：

```json
{"status":"ok","version":"0.1.0","backend":"ktor"}
```

诊断报告 `schemaVersion=2`，可能包含运行环境、存储概况、脱敏后的错误信息和最近 40 条 `modelRequests`。请求记录包含 run/session/turn/operation 上下文、耗时、状态、usage 与缓存 diff；缓存 diff 只保存消息 slot 的 SHA-256、估算 token、第一破坏点、级联失效和截短信息，不导出提示词或模型输出明文。endpoint 仅保留脱敏展示值，模型 Key、userinfo、query 和 fragment 不导出。失败请求会展示，但不会成为下一次缓存比较的 baseline。

## 3. 书卷与蒸馏运行

### 3.1 查询、创建、导入与生命周期

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs` | 无 | `200` `{"items":[RunManifest...]}`；SillyTavern 角色卡书卷带有 `entrypoint: "sillytavern"`，包括从造梦书卷包恢复的角色卡书卷 |
| GET | `/api/web/runs/{run_id}` | 无 | `200` `RunManifest`；人物索引注入实时 `avatar_version`，角色卡书卷来源与列表一致 |
| POST | `/api/web/runs` | `CreateRunRequest` | `201` 新运行 manifest |
| POST | `/api/web/runs/import` | `ImportRunPackageRequest`，接受 ZIP 书卷包、SillyTavern JSON/PNG 角色卡或带 `target_run_id` 的 JSON 世界书 | `201` 运行 manifest，恢复角色卡书卷时保留 `entrypoint: "sillytavern"`；`400` 文件或参数无效，`404` 目标书卷不存在，`409` 独立世界书在预览后发生变化 |
| POST | `/api/web/runs/import/sillytavern/preview` | `SillyTavernPreviewRequest` | `200` 导入预览，`400` 文件或参数无效，`404` 目标书卷不存在 |
| POST | `/api/web/runs/{run_id}/control/stop` | 无 | `200` 停止后的运行 manifest |
| POST | `/api/web/runs/{run_id}/stop` | 无 | 同上；旧 WebUI 兼容别名 |
| DELETE | `/api/web/runs/{run_id}` | 无 | `200` 删除统计 |
| POST | `/api/web/runs/{run_id}/refresh` | 无 | `200` 刷新后的运行 manifest |

删除书卷时，Ktor 会先停止并等待蒸馏任务，再清理该书卷的会话及其后台任务。清理开始后，请求断开也会继续删除。

`CreateRunRequest`：

```json
{
  "novel_name": "红楼梦.txt",
  "novel_content_base64": "5paH5pysLi4u",
  "characters": ["林黛玉", "贾宝玉"],
  "max_sentences": 120,
  "max_chars": 50000,
  "auto_run": false,
  "defer_run": false
}
```

`novel_name`、`novel_content_base64`、`characters` 必填。`novel_content_base64` 是原文文件字节的 Base64，而非普通文本。

`ImportRunPackageRequest`：

```json
{
  "filename": "book.zaomeng.zip",
  "content_base64": "UEsDB...",
  "library_package": null
}
```

在线书库导入时，`library_package` 可携带 `id`、`title`、`version`、`download_url`、`sha256`。

SillyTavern 角色卡支持传统 JSON、V2/V3 JSON 和带 `chara`/`ccv3` 元数据的 PNG。导入会创建新的可用书卷并保留卡内世界书。在未指定场景的单人「加入对话」模式中，默认使用角色卡首句，也可选择备用开场。预览返回可选的 `alternate_greeting_count`。

导入角色卡的单人入场对话注入角色描述、性格、场景、玩家人设和对话示例，并展开 `{{char}}`、`{{user}}`。角色卡 `system_prompt` 可替换默认创作目标，`{{original}}` 可引用该目标。最近的用户和角色消息以各自的角色发送，`post_history_instructions` 随后注入。模型回复仍采用造梦的 NDJSON 协议。

独立 JSON 世界书必须传 `target_run_id`，会替换该书卷之前挂载的独立世界书。世界书支持禁用、常驻、主次关键词、优先级、区分大小写和整词匹配。扫描包含当前输入，默认 6 条，条目设置可覆盖书级设置，上限 32 条；深度为 0 时跳过对话和额外来源的直接扫描。整词匹配对含空白的短语仍按字面子串匹配。JSON 数据上限为 1 MB，PNG 文件上限为 5 MB。

世界书条目的 `position` 支持角色定义前（`0` 或 `before_char`）与角色定义后（`1` 或 `after_char`）。卡内条目优先读 `extensions.position`，独立世界书优先读顶层字段。先按现有优先级和预算选择条目，再按插入顺序从低到高排列各位置内的文本。未指定位置的条目继续进入普通世界设定上下文，未识别的位置同样使用该回退并在导入预览提示。

对话示例前（`5`）和对话示例后（`6`）分别报告为 `before_examples` 和 `after_examples`。条目放入对话示例区，支持 `<START>` 分块及 `{{char}}`、`{{user}}`，角色卡本身没有示例时也可生效。前置条目按优先级从高到低显示，后置条目从低到高显示。

作者注释前（`2`）与作者注释后（`3`）分别报告为 `before_author_note` 与 `after_author_note`。注释触发时，匹配条目按各自位置归并在注释内容前后，随注释一起注入。注释正文可以留空，由世界书提供注释内容。

也支持按聊天深度（`position: 4`，接口报告为 `at_depth`）。卡内条目优先读取 `extensions.depth`、`extensions.role`，独立世界书优先读取顶层字段。默认深度为 4，角色默认是系统（`0`），还支持用户（`1`）和助手（`2`）。深度从本轮输入向前数，深度 0 在本轮输入后，深度 1 在本轮输入前，超出有界聊天历史时放在最早可用消息之前。管理列表、试触发和聊天记录会返回 `injection_depth` 与 `injection_role`。

命名出口使用 `position: 7` 和 `outletName`，接口报告为 `outlet` 和 `outlet_name`。导入角色卡的单人入场对话可在角色描述、性格、场景、对话示例、系统提示和后置指令中使用 `{{outlet::名称}}`。入选的同名条目按优先级从高到低合并，名称区分大小写，缺少匹配时宏展开为空。试触发显示候选出口，聊天记录只记录实际被引用的入选条目。条目匹配后缺少有效出口名称时，试触发返回 `missing_outlet`。

独立世界书预览指定 `target_run_id` 时，返回当前挂载内容的 `previous_book_revision`。空字符串表示尚无挂载。导入时将该值原样作为 `expected_world_book_revision` 发送，服务端在写入前核对，版本不一致时返回 `409` 并保留当前内容。省略该字段沿用旧客户端的导入行为。

独立和卡内世界书都支持 `probability`，`useProbability: false` 会跳过概率筛选。通过关键词条件的条目和常驻条目均参与筛选，0% 永不进入上下文，100% 总能通过概率筛选，仍受注入上限约束。对话在同一书卷、会话、回合和条目位置上使用稳定的抽样结果，重试同一回合时不会重新抽样。每次试触发独立抽样。

延迟激活读取独立世界书的 `delay` 或卡内世界书的 `extensions.delay`。达到消息数之后，条目再按关键词、常驻或递归等条件判断是否触发。生成使用会话消息总数，计入归档和本轮实际发送的消息；自动续写指令不额外增加消息数。管理列表以 `delay` 返回阈值，未达到时试触发报告 `activation_delay`。导入预览以 1 条消息试触发。

世界书支持 `sticky`（粘滞）和 `cooldown`（冷却），单位为消息条数。卡内世界书也接受 `extensions` 中的同名字段。粘滞期间条目无需再次命中关键词或通过概率筛选，优先参加注入，仍占用注入预算。冷却期间暂停激活。两者同时设置时，粘滞结束后的第一次生成进入冷却。计时以成功提交的对话为准，分支按保留的消息恢复状态。管理页试触发是独立模拟，不读取或修改会话计时。聊天记录里的 `sticky_active: true` 表示本轮由粘滞维持注入。

卡内和独立世界书中同名的互斥分组会合并比较。支持逗号分隔的多组名。已粘滞的候选保留在同组中，同组其他候选让位；启用按命中评分的候选如果低于同组最高分，会在抽选前退出。剩余候选中，设置组内优先的条目按插入顺序值从高到低择一，否则按权重抽选，默认权重为 100。选出的候选随后接受触发概率和注入预算筛选。正式生成的组内抽选在同一回合的重试中保持稳定，试触发每次重新抽选。被分组淘汰的试触发条目会报告 `group_suppressed`。

普通条目共用最多 4 条的配额，生成时配额还受锁定世界事实和可用容量影响。独立世界书的 `ignoreBudget: true` 或卡内世界书的 `extensions.ignore_budget: true` 可以跳过普通条目配额。关键词、概率、分组和递归等匹配条件仍生效。每轮最多注入 18 条世界书条目，原始正文合计最多 4320 字符。剩余预算足够时整条注入，放不下的条目跳过，由后续候选继续竞争。试触发以 `budget` 表示普通配额已满，以 `total_budget` 表示世界书总条数已满，以 `content_budget` 表示剩余正文预算不足。匹配结果的 `excerpt` 对已注入条目包含完整正文，其余情况最多返回前 240 字符。

条目可以用 `triggers` 限定适用的生成类型，卡内世界书也接受 `extensions.triggers`。空列表表示不限，未匹配的试触发条目报告 `generation_type`。`normal` 适用于普通回复和模型开场，`continue` 适用于已有历史后的无用户输入续写，`regenerate` 适用于重新生成和修正版，`impersonate` 适用于代写，`quiet` 适用于关联建议和导演选项。`swipe` 可导入、导出及试触发。造梦的导入备选回复功能会读取已有回复，读取时不执行模型生成。

世界书可通过管理页的递归开关或顶层 `recursive_scanning: true`（也接受 `recursiveScanning`）启用递归。先处理对话直接命中的条目，再扫描已入选条目中实际注入的正文片段。开启递归的卡内与独立世界书可相互触发，保留原有注入上限，最多追加四轮扫描。支持 `excludeRecursion`、`preventRecursion` 和布尔或数字形式的 `delayUntilRecursion`，也支持卡内世界书 `extensions` 下对应的蛇形字段。延迟层级从小到大，在当前层没有新正文可传递时进入下一层。扫描深度为 0 的条目仍可由递归正文触发。未启用递归的世界书仅参加直接扫描。

预览请求包含 `filename`、`content_base64` 和 `world_book`（独立世界书传 `true`）。可选 `target_run_id` 用于查询拟替换的独立世界书，可选 `test_text`（最多 2000 字符）用于试触发。此接口只读取数据。返回人物和开场信息、可映射字段、世界书条目数及暂不支持的规则；试触发附上 `injected_count` 和最多 50 个 `tested_entries`。试触发只扫描本次输入和拟导入文件，实际对话还使用近期消息、已挂载的其他条目和锁定的世界事实。条目状态为 `injected`、`budget`、`disabled`、`empty`、`no_keyword`、`secondary`、`probability`、`recursion_only` 或 `duplicate`。`probability` 表示匹配条件成立但未通过抽样。`recursion_only` 表示条目仍在等待递归。`trigger_probability` 表示用于筛选的百分比，`recursion_depth` 为 0 时表示直接扫描，正数表示第几轮递归扫描。

卡内和独立世界书支持正文开头独占一行的 `@@activate` 和 `@@dont_activate`。前者跳过关键词条件，后者阻止触发，两者同时出现时优先强制触发。停用、生成类型、延迟、冷却、递归限制、分组、概率和预算仍按各自的规则处理。正文开头连续的 `@@` 指令行只在预览与生成时去除，编辑和导出保留原文。试触发结果用 `decorator_suppressed` 标记被正文指令禁止触发的条目，用可选布尔字段 `forced_activation` 标记由指令触发的候选，字段默认关闭。

已挂载的世界书通过以下接口管理。书卷不存在时返回 `404`，无效参数返回 `400`。

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/world-books` | 无 | `200 ManagedWorldBooksDto`，包含来源、版本、条目和 `recursion_enabled` |
| GET | `/api/web/runs/{run_id}/world-books/{source}/export` | 来源为 `character_book` 或 `world_book` | `200` SillyTavern 独立世界书 JSON 附件；来源不存在为 `404` |
| POST | `/api/web/runs/{run_id}/world-books/test` | `{"text":"测试台词","history":[],"message_count":1,"generation_type":"normal"}`，仅台词必填 | `200 WorldBookTestDto`，用已挂载的世界书试触发，最多返回 50 个条目结果；每项的 `source` 标明是 `character_book` 还是 `world_book` |
| PATCH | `/api/web/runs/{run_id}/world-books/{source}/entries/{index}` | `{"revision":"…","enabled":false}`；来源为 `character_book` 或 `world_book`，序号从 0 开始 | `200 ManagedWorldBooksDto`；书或条目不存在为 `404` |
| PATCH | `/api/web/runs/{run_id}/world-books/{source}/entries/{index}/content` | `revision` 必填，且至少传一项条目设置 | `200 ManagedWorldBooksDto`；内容无效为 `400`，书或条目不存在为 `404`，版本过期为 `409` |
| POST | `/api/web/runs/{run_id}/world-books/{source}/entries` | 必填 `revision`、`content`、`keywords`；其余条目设置可选 | `200 ManagedWorldBooksDto`；内容无效或超过条目上限为 `400`，世界书不存在为 `404`，版本过期为 `409` |
| DELETE | `/api/web/runs/{run_id}/world-books/{source}/entries/{index}?revision=…` | 无请求体；条目序号和版本取自同一次世界书列表响应 | `200 ManagedWorldBooksDto`；缺少版本或序号无效为 `400`，书或条目不存在为 `404`，版本过期为 `409` |
| PATCH | `/api/web/runs/{run_id}/world-books/{source}/recursion` | `{"revision":"…","enabled":true}`；来源为 `character_book` 或 `world_book` | `200 ManagedWorldBooksDto`；书不存在为 `404`，版本过期为 `409` |
| DELETE | `/api/web/runs/{run_id}/world-books/standalone?revision=…` | 无请求体 | `200 ManagedWorldBooksDto`；独立世界书不存在为 `404` |

修改和移除使用列表返回的 `revision`。版本不匹配时返回 `409`，客户端应重新加载。条目修改保留文件中的其他设定，移除只解绑独立世界书。管理页试触发扫描测试台词和可选的 `history`，同时评估两个已挂载来源。`history` 由早到晚排列，最多 31 条，每条与测试台词均须为 1 至 2000 字符。生成类型默认 `normal`。`message_count` 为正整数，默认 1，实际取该值和提供的消息条数（含测试台词）的较大者。

新增或编辑条目时，`delay`、`sticky`、`cooldown` 为非负整数，0 关闭对应规则。编辑时省略会保留原值。试触发的 `message_count` 仅控制延迟门槛，包含测试台词本身。

管理接口用 `group` 表示分组设置，字段为 `names`、`priority`、`weight`、`scoring`。组名在同一行内用英文逗号分隔，总长最多 512 个字符，区分大小写。权重为 0 至 10000 的整数。优先默认关闭，评分为 `null` 时保留酒馆的继承设定，在造梦内按默认关闭处理。`names` 为空时退出分组，编辑时省略 `group` 会保留原值。SillyTavern 世界书导出使用 `group`、`groupOverride`、`groupWeight`、`useGroupScoring`，角色卡的卡内世界书使用 `extensions` 中的对应字段。

新增、编辑和列表支持 `triggers`，值为生成类型 ID 数组。编辑时省略会保留原值，传入空数组可清除限制。列表通过 `has_unknown_triggers` 标明原文件是否包含未知类型，未知类型不参与匹配，编辑其他字段时会随原文件保留。

新增、编辑、列表和匹配结果支持布尔字段 `ignore_budget`，默认关闭。编辑时省略会保留原值。独立世界书导出为 `ignoreBudget`，角色卡中的条目导出到 `extensions.ignore_budget`。

删除条目后，管理列表按剩余条目的原有顺序重新编号。目标世界书继续挂载，即使删除的是最后一条。

新增条目追加到目标世界书末尾，默认启用，按关键词触发。配置次关键词时默认要求至少命中一个主关键词和一个次关键词。设置 `constant: true` 可创建常驻条目，`keywords` 可传空数组。编辑时切回关键词触发，保存后必须至少有一个主关键词。只修改 `constant` 会保留已有关键词以及概率、优先级等设置。正文须为 1 至 16000 字符，标题最多 120 字符，每组最多 32 个关键词，每个关键词最多 120 字符。

新增、编辑和列表支持 `matching`。其字段为 `scan_depth`（非负整数，实际最多 32，0 仅跳过对话消息扫描）、`case_sensitive`、`whole_words`、`use_secondary`（默认启用）和 `secondary_logic`（`and_any`、`not_all`、`not_any`、`and_all`）。扫描深度包含本轮输入。深度和两项匹配开关为 `null` 时继承默认值。编辑时省略整个 `matching` 会保留原值。

`matching.sources` 可选 `persona_description`、`character_description`、`character_personality`、`character_depth_prompt`、`scenario`、`creator_notes`，默认空列表。扫描深度大于 0 时，主关键词和次关键词都会查找所选来源。场景优先取当前会话场景，缺省时查找角色卡场景。作者备注可作为匹配输入。导出用酒馆的 `matchPersonaDescription`、`matchCharacterDescription`、`matchCharacterPersonality`、`matchCharacterDepthPrompt`、`matchScenario`、`matchCreatorNotes`，角色卡内使用 `extensions` 下的蛇形字段。

管理页试触发读取已导入角色卡的设定。`context` 可选 `persona_description` 和 `scenario`，各最多 4000 个字符，用于模拟玩家和场景。导入预览使用待导入角色卡或目标书卷的角色卡设定。

`placement.position` 可选 `before_char`、`after_char`、`before_author_note`、`after_author_note`、`before_examples`、`after_examples`、`at_depth` 或 `outlet`。仅 `at_depth` 可携带 `depth`（0 至 10000）和 `role`（`system`、`user`、`assistant`），其默认值分别为 4 和 `system`。`outlet` 必须填写 `outlet_name`，去除首尾空白后长度为 1 至 120 个字符，不允许花括号或换行。新增时省略 `placement` 使用角色定义前，编辑时省略会保留导入的原位置和扩展配置。

条目列表的 `title` 是显示标题，`comment` 是可编辑标题。将 `comment` 设为空字符串恢复自动标题。`content` 长度为 1 至 16000 字符且须有非空白内容。每组关键词可传空数组清空，最多 32 项，每项去除首尾空白后须为 1 至 120 字符。省略的字段保留原始值。

世界书导出使用 SillyTavern 的 `{"entries":{"0":{…}}}` 格式。卡内世界书也可单独导出，条目启停状态、触发词、优先级和适用的扩展规则一同带出。

删除响应的主要字段：`status`、`novel_id`、`deleted_run_count`、`deleted_session_count`、`deleted_run_ids`。

### 3.2 估算、重蒸馏与恢复

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/estimate` | `EstimateSamplingRequest` | `200` 抽样、调用量、token 与耗时估算 |
| POST | `/api/web/runs/{run_id}/redistill` | `RestartRunRequest` | `200` 重蒸馏后的运行 manifest |
| POST | `/api/web/runs/{run_id}/resume-distill` | 无 | `200` 恢复后的运行 manifest |
| POST | `/api/web/runs/{run_id}/redistill/recommend` | `SuggestRedistillSegmentsRequest` | `200` 推荐原文片段 |

旧蒸馏任务尚未退出或书卷正在删除时，重蒸馏与恢复请求返回 `400`。删除书卷会等待正在执行的蒸馏退出。
重蒸馏使用请求中的 `max_sentences`（20–300）和 `max_chars`（2,000–200,000）。重新选择已保存的同一段原文会复用文件，并将其设为本轮来源。

估算请求：

```json
{
  "char_count": 350000,
  "sentence_count": 12000,
  "character_count": 3,
  "max_sentences": 120,
  "max_chars": 50000
}
```

估算响应包含 `effective_chars`、`effective_sentences`、各类 `*_chunk_count`、`total_calls`、`token_low/token_high` 与 `time_low_seconds/time_high_seconds` 等字段。

重蒸馏请求所有字段均有默认值，可只指定要重做的人物：

```json
{"characters":["林黛玉"]}
```

片段推荐请求：

```json
{"character":"林黛玉","max_segments":3}
```

### 3.3 导出与跨作品空间

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/export` | Query：`builtin=false`、`include_dialogue` 可选 | `200 application/zip`，带附件文件名 |
| POST | `/api/web/crossover-spaces` | `CreateCrossoverSpaceRequest` | `200` 新跨作品空间 manifest |

跨作品空间请求：

```json
{
  "title": "群英会",
  "world_setting": "众人在一座与原作隔离的客栈相遇。",
  "participants": [
    {"run_id":"run-a","character":"林黛玉"},
    {"run_id":"run-b","character":"诸葛亮"}
  ]
}
```

该能力属于不稳定的 beta 功能；响应 manifest 的 `beta_feature.kind` 用于识别其来源。

## 4. 对话会话

### 4.1 会话列表与管理

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/sessions` | 公共会话分页 Query | `200 SessionsPageResponse`，跨书卷最近会话 |
| DELETE | `/api/web/sessions` | `DeleteSessionsRequest` | `200` 批量删除结果 |
| GET | `/api/web/runs/{run_id}/dialogue/sessions` | 公共会话分页 Query | `200 SessionsPageResponse` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions` | `CreateDialogueSessionRequest` | `201` 会话对象 |
| GET | `/api/web/runs/{run_id}/dialogue/card-greetings` | 无 | `200 CardGreetingsDto`，含 `character`、`options`、`revision` 和可选的 `next_alternate_index`；书卷不存在为 `404` |
| PUT | `/api/web/runs/{run_id}/dialogue/card-greetings` | `{"revision":"…","index":1,"text":"开场白"}` | 更新一条开场并返回 `200 CardGreetingsDto`。参数无效为 `400`，角色卡不存在为 `404`，版本过期为 `409` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/import-sillytavern` | `{"filename":"会话.jsonl","content_base64":"…"}` | `201` 新建轻量会话；文件或角色映射无效返回 `400`，目标不存在返回 `404` |
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/export-sillytavern` | 无 | `200 application/x-ndjson`，会话不存在返回 `404` |
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/imported-swipes/{message_index}` | `offset=0&limit=4`，每页 1–10 条 | `200 ImportedSwipePageDto`；参数无效返回 `400`，会话或迁移消息不存在返回 `404` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch-imported-swipe` | `{"message_index":"0","swipe_index":1}` | `200` 新建或复用的轻量分支会话；参数无效返回 `400`，消息或版本不存在返回 `404` |
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}` | `include_transcript=true` | `200` 完整或轻量会话对象 |
| DELETE | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}` | 无 | `200 {"status":"deleted"}` |
| PATCH | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/title` | `{"title":"新标题"}` | `200` 更新后的会话对象 |
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/author-note` | `{"text":"保持简洁。","enabled":true,"depth":4,"role":"system","interval":1,"position":"in_chat"}` | `200` 已保存的注释；内容超过 4000 字符、深度超出 0 至 10000 或间隔小于 1 返回 `400`，会话不存在返回 `404` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/prepare` | `PrepareDialogueTurnRequest` | `200` 写入待处理用户消息后的会话对象 |

单个或批量删除会话时，Ktor 会先停止并等待该会话的记忆派生和摘要任务，再移除会话。删除后，迟到的对话回执和轮次写入不会重新创建会话目录。

会话的 `author_note.interval` 默认是 1，每轮触发。大于 1 时，当该会话的用户消息总数为间隔的正整数倍，才注入作者注释及其前后的世界书条目。计入本轮实际发送的用户消息和归档中的用户消息，角色回复与自动续写不增加计数。`enabled: false` 停用注释及其前后的世界书条目。

`author_note.position` 可选 `before_prompt`（主提示词前）、`after_prompt`（主提示词后）和 `in_chat`（默认）。聊天中按 `depth` 和 `role` 注入。主提示词前后使用系统消息，并保留深度和角色设置供切回聊天中或重新导出。

创建会话请求：

```json
{
  "mode": "observe",
  "participants": ["林黛玉", "贾宝玉"],
  "controlled_character": "",
  "card_greeting_index": null,
  "scene_card_id": "",
  "scene_profile": {},
  "self_card_id": "",
  "self_profile": {}
}
```

`mode` 默认为 `observe`。参与者、受控人物和卡片组合的进一步约束由业务服务校验。

未指定场景的单人 `insert` 会话可以传 `card_greeting_index`。`0` 对应角色卡首句，正数对应卡内备用开场的原始顺序，最多读取前 20 个。省略时使用已保存的首句，首句为空则生成普通开场。无效序号或不符合会话条件时返回 `400`。使用角色卡开场后，会话详情和列表返回实际使用的索引。

开场编辑使用 GET 返回的 `revision`，单次只修改指定位置，正文最多 4000 字符。传空正文可以清空该位置，其他开场的编号照旧。新增时可用 `next_alternate_index`。读取结果的 `options` 仅包含非空开场，每项包含 `index`、`text`，原文超过 4000 字符时还有 `truncated: true`。保存后的内容用于新会话和之后的角色卡导出，已有会话保留已写入的开场消息。

SillyTavern 聊天迁移使用 UTF-8 JSONL，导入上限 8 MB、20,000 条文本消息、单条 16,000 字符。导入新建会话，支持私聊和群聊以及无文件头的群聊记录。按发言者名称映射到目标书卷人物；仅当来源与目标均只有一名角色时允许名称不同。发送者、文本、旁白、原始发送时间和备选回复随文件迁移。导出包括当前会话的归档历史。当在造梦里创建分支时，保留分支内消息的备选回复；每个文件对应一条会话时间线。

文件头 `chat_metadata` 支持作者注释的 `note_prompt`、`note_interval`、`note_depth`、`note_position` 和 `note_role`。停用导出为 `note_interval: 0`。位置 `0/1/2` 分别为主提示词后、聊天中、主提示词前，角色 `0/1/2` 分别为系统、用户、助手。未知位置、角色和超范围深度使用默认值，注释超过 4000 字符返回 `400`。无作者注释设置的文件使用会话默认值。附件和其余 SillyTavern 专用的聊天状态不在迁移范围内。

迁移的 transcript 项可以包含 `sillytavern_message_index` 和 `sillytavern_swipe_count`。完整备用回复存放在会话的独立文档中。查看接口返回 `total`、当前选中的 `selected_index` 和 `items`（含 `index`、`text`、`truncated`）；所有索引从 0 开始。查看页每个版本最多返回 16,000 字符，JSONL 导出保留原文。

从备用版本分支时，保留所选角色消息之前的记录，并在新会话里把该条回复替换为所选版本。之后的消息留在原会话。同一源会话、消息和回复版本的重复请求返回已有分支。长度超过 16,000 字符或为空的版本仍可随 JSONL 导出。

批量删除请求：

```json
{
  "items": [
    {"run_id":"run-1","session_id":"session-1"},
    {"run_id":"run-2","session_id":"session-2"}
  ]
}
```

`prepare` 请求：

```json
{
  "message": "我们去哪里？",
  "message_kind": "user_input",
  "pacing": "normal",
  "include_inner_thoughts": false,
  "operation_id": "client-generated-id"
}
```

`operation_id` 用于幂等和本轮 transcript 关联，建议由客户端每次操作生成稳定且非空的唯一值。

会话对象及轻量响应包含 `revision`（整数），表示已提交剧情历史版本。新回合、修正分支、转场等历史变更使其递增；标题、记忆和后台派生状态更新不会推进该版本。旧存档缺省为 `0`。消息分页使用它固定读取快照。

### 4.2 消息分页

`GET /api/web/runs/{run_id}/dialogue/sessions/{session_id}/messages`

| Query | 类型 | 默认值 | 约束/语义 |
|---|---:|---:|---|
| `offset` | int | `0` | 小于 0 时归零；`desc` 时表示跳过最新 N 条 |
| `limit` | int | `100` | `1..500` |
| `order` | string | `asc` | `asc` 或 `desc` |
| `revision` | long | 不固定 | 传入后要求本页仍属于该历史版本；变化时返回 `409` |

响应：

```json
{
  "items": [
    {
      "seq": 0,
      "speaker": "林黛玉",
      "message": "……",
      "inner_thought": "",
      "role": "character",
      "turn_id": "turn-1",
      "timestamp": "2026-08-11T12:00:00Z",
      "evidence": []
    }
  ],
  "total": 1,
  "has_more": false,
  "revision": 1,
  "next_offset": null
}
```

`seq` 是该 revision 下的全局零基消息位置。`next_offset` 用于同方向、同 revision 的下一页；没有下一页时为 `null`。客户端不得拼接不同 revision 的页面。

### 4.3 非流式回复

`POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply`

请求为 `DialogueReplyRequest`：

```json
{
  "message": "你怎么看这件事？",
  "message_kind": "dialogue",
  "pacing": "normal",
  "speaker_override": "",
  "suppress_transcript_message": false,
  "include_inner_thoughts": false,
  "include_model_reasoning": false,
  "include_transcript": false,
  "operation_id": "turn-uuid"
}
```

- `message` 必填。
- `message_kind` 允许 `dialogue`、`narration`、`plot`、`fourth_wall`。`fourth_wall` 表示作者从故事之外直接向角色下指令，角色可以回应、质疑、谈判、抵抗或拒绝。
- `pacing` 控制本轮回复节奏：`brief` 使用约 0.5 倍输出预算并要求每人一两句话，`normal` 保持默认节奏，`detailed` 使用约 1.5 倍预算（不超过服务端上限）并展开动作与环境；未知值按 `normal` 处理。
- `speaker_override` 默认为空；内置“帮我回”等可信流程可传入已蒸馏人物名，使本轮输入以该人物身份进入提示词和 transcript，服务端会拒绝未知人物名。
- `include_model_reasoning` 仅流式服务使用；非流式路由当前不会向业务服务传递该字段。
- `include_transcript=false` 时返回轻量会话（不含完整 transcript，但含 `transcript_count` 和 `revision`）；为 `true` 时返回带完整 transcript 的会话。
- 相同 `operation_id` 和相同请求内容会复用已提交结果，不再次调用模型；相同 ID 对应不同请求指纹时返回 `409 OPERATION_ID_CONFLICT`。生成期间历史版本变化导致提交冲突时返回 `409 SESSION_REVISION_CONFLICT`。

### 4.4 流式回复（SSE）

`POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply/stream`

请求体同 `DialogueReplyRequest`，响应类型为 `text/event-stream`。每个事件采用标准 SSE：

```text
event: delta
data: {"index":0,"speaker":"林黛玉","role":"character","field":"message","text":"我"}

```

事件序列：

| 事件 | 数据 | 含义 |
|---|---|---|
| `status` | `phase`, `message` | `generating` 表示生成，`restoring` 表示恢复已提交结果 |
| `prompt_preview` | `turn_id`, `attempt`, `messages`, `world_book_matches` | 请求设置 `include_prompt_preview: true` 时，每次正文生成前返回提示词预览 |
| `reset` | `message` | 丢弃此前增量并重置可见内容 |
| `delta` | `index`, `speaker`, `role`, `field`, `text` | 某条角色响应的字段增量；`field` 常为 `message` 或 `inner_thought` |
| `complete` | `session`, `operation_id`, `turn_id`, `replayed`, `base_revision`, `committed_revision`, `transcript_count`, 可选 `appended_transcript` | 生成及持久化完成，或重放已提交回执 |
| `error` | `code`, `operation_id`, `message`, `retryable` | 流中失败；HTTP 状态通常已是 `200`，应按事件处理 |

提示词预览默认关闭。消息保留请求顺序并包含 `role`、`text` 和可选 `truncated`。每条正文最多显示 24000 字符，本次预览合计最多显示 64000 字符，截断只影响预览。格式重试会返回 `attempt: 2`，恢复已提交结果时没有新的预览。预览内容随流传输，客户端仅在当前会话页面的内存中保留最近一次捕获；不会写入聊天历史、操作回执或日志。

`complete` 规则：

- `include_transcript=true`：`session` 包含完整 transcript，不返回 `appended_transcript`。
- `include_transcript=false`：`session` 为轻量对象，`appended_transcript` 包含 `operation_id` 对应一轮的完整条目集合。
- `replayed=true` 表示本次没有重新生成，而是返回已提交 operation 的结果。
- `base_revision` 是生成前历史版本，`committed_revision` 是本 operation 提交后的版本。客户端快速合并前应校验本地基线；不一致时重新读取当前 header 和 tail。

流式和普通对话会在本轮第一条回复的 transcript 项记录可选的 `world_book_matches`。每项含 `title`、`excerpt`、`status`（`injected`）、`keywords`（常驻或指令触发的条目为空）、`source`（`character_book` 或 `world_book`），可另带 `trigger_probability`、`recursion_depth`、`position`、`insertion_order` 和 `forced_activation`。管理条目也会返回已识别的 `position`。这里只列出实际进入该轮上下文的世界书条目。

preflight 阶段的冲突在 SSE 开始前返回 HTTP `409`，响应体包含 `code`、`message`、`retryable`。主要错误码包括 `OPERATION_ID_CONFLICT`、`OPERATION_IN_PROGRESS` 和 `SESSION_REVISION_CONFLICT`。

服务端从模型响应体到 SSE 增量转发；模型侧主协议是每行一个完整对象的 NDJSON，但 HTTP 调用方只需要消费上述 SSE，不直接解析模型 NDJSON。

### 4.5 operation 状态查询

`GET /api/web/runs/{run_id}/dialogue/sessions/{session_id}/operations/{operation_id}`

用于断线后的结果核对，不调用模型。回执不存在返回 `404`。成功响应包含：

- `status`：`generating`、`committing`、`completed`、`failed`。
- `operation_id`、`turn_id`、`base_revision`、`transcript_count`。
- `failure_code`、`failure_message`、`retryable`。
- `completed` 时另含 `committed_revision`、当前轻量 `session` 和该 operation 的完整 `appended_transcript`。

正文提交后的派生工作使用 `derived-operations` 持久回执，状态包括 `PREPARED`、`PENDING`、`RUNNING`、`RETRY_WAIT`、`SUCCEEDED`、`FAILED`、`SUPERSEDED`、`CANCELLED`。`PREPARED` 仅在对应历史提交成功后激活为 `PENDING`；启动恢复会对账实际 transcript，未提交的 intent 不会执行。场景状态、世界记忆、长期记忆和原作索引分别记录 `DONE/FAILED/SKIPPED`，单类失败不会撤销正文。

### 4.6 会话摘要 Projection

| 方法 | 路径 | 成功响应 |
|---|---|---|
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/projection` | `200 SessionProjectionDto` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/projection/rebuild` | `202 SessionProjectionDto`，后台开始或复用当前构建任务 |

`SessionProjectionDto` 包含：

- `status`：`fresh`、`stale`、`missing`、`building`、`failed`。
- `source_revision`：已发布 Projection 对应的原文 revision。
- `current_revision`：当前会话历史 revision。
- `session`：轻量会话对象，不含完整 transcript；附可重建的 `story_recap` 和 `session_memory_summary`。
- `error`：最近构建失败的有限长度原因。

正文生成只注入 `fresh` 且 `source_revision == current_revision` 的摘要；stale/missing/failed 时继续使用近期原文，不等待摘要模型。复盘可读取最后一个有效 Projection 并显示 stale/failed 状态。`rebuild` 按 latest-wins 合并连续请求，未变化的摘要卷按范围和 `sourceHash` 复用；发布前再次校验 revision 和完整 seq 范围，旧候选不能覆盖新历史。会话或书卷删除会取消并等待派生与 Projection worker，防止删除后重建 sidecar。

### 4.7 有界历史检索

正文首次生成可在近期原文之外执行可选的历史检索规划。规划请求使用单对象 JSON `{"calls":[...]}`，工具仅限 `history_search` 和 `history_read`；每次生成最多 2 次搜索、2 次按 turn 读取，所有分页读取固定在本次生成的 base revision。检索结果按 seq 去重，排除当前 turn 和已经位于近期窗口的消息，只以独立 `history_evidence` 注入消息、发言者、角色、seq 和时间，不携带旧 `inner_thought`。规划超时、普通规划失败或无检索调用时回退原正文请求，不改变正文 NDJSON/SSE 协议；正文格式重试复用同一 enriched payload，不重复检索。该能力没有单独的公开 HTTP 端点。

## 5. 对话辅助与高级能力

### 5.1 建议与联想

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/suggestions?stream=true` | `{"seedText":"...","selectedDirection":"..."}` | SSE：`delta`、`done`、`error` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/associations` | `{"optionCount":3}` | `200 {"options":[...]}` |

这两个局部请求体沿用 `camelCase`。`optionCount` 会限制到 `2..4`，默认 `3`。建议接口当前只实现流式模式；`stream=false` 返回 `501`。

建议 SSE：

- `delta`：`{"text":"..."}`
- `done`：`{"status":"completed"}`
- `error`：`{"error":"...","type":"suggestion_error"}`

### 5.2 搜索、恢复与分支

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/search` | Query：`q` 必填、1..120 字符；`limit` 默认 50、范围 1..100 | `200 {"items":[ChatSearchResult...]}` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/recover` | Query：`force=false` | `200` 恢复后的会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch` | `{"scene_index":0}` | `200` 新分支会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch-turn` | `{"turn_id":"turn-1"}` | `200` 新分支会话 |
| PATCH | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch-meta` | 见下 | `200` 更新后的会话 |

分支元数据请求字段均可选：

```json
{
  "label": "如果当时没有离开",
  "is_mainline": false,
  "locked_event_ids": ["event-1"]
}
```

### 5.3 长期记忆与质量管理

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories` | `UpsertDialogueMemoryRequest` | `200` 更新后的会话 |
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories/{memory_id}` | 同上 | `200` 更新后的会话 |
| DELETE | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories/{memory_id}` | 无 | `200` 更新后的会话 |
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memory-quality` | 无 | `200 MemoryQualityReport` |
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memory-quality/{memory_id}/status` | `{"status":"active"}` | `200 MemoryQualityReport` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memory-quality/merge-duplicates` | 无 | `200 MemoryQualityReport` |

记忆写入请求：

```json
{"text":"两人约定三日后再会。","category":"story","pinned":false,"enabled":true}
```

记忆对象包含 `memory_id`、`text`、`category`、`pinned`、`enabled`、`source`、`source_turn_id`、`status`、命中统计和重复合并信息。自动记忆状态使用 `active`、`stale` 或 `conflict`。

更新或删除不存在的 `memory_id` 返回 `400`。同一会话同时启用的可控记忆最多 20 条，超限写入返回 `400`，不会自动停用已有记忆。

### 5.4 导演、纠错、关系锁与场景

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/relation-lock` | `{"pair_key":"甲::乙","locked":true}` | `200` 会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/suggest` | `{"seed_text":"...","direction":"..."}` | `200` 建议对象 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/correct-latest` | 无 | `200` 新建的修正分支会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/deep-review` | 无 | `200` 审查结果/会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/director-options` | `DialogueDirectorRequest` | `200` 导演选项 |
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/director/tension` | 无 | `200 SceneTensionDto` |
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/director/events` | 查询参数 `category`（可选） | `200 List<PlotEventPresetDto>` |
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/scene-card` | `SwitchDialogueSceneRequest` | `200` 更新后的会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/scene-card/recommend` | 无 | `200` 推荐场景卡 |

修正最新回合（`correct-latest`）不会修改原会话：服务端在目标回合之前创建 `kind=consistency_correction` 的修正分支，沿用该回合的用户输入重新生成回复，并返回新分支会话。分支 `branch_origin` 包含 `session_id`、`turn_id`、`kind`，同时保留 `source_session_id`、`source_turn_id`、`fork_type` 兼容字段。

深度复核（`deep-review`）只检查最新角色回合，并把语义问题合并进该回合已有的确定性报告（保留 `turn_id`、重算 `status`/`score`、标记 `coverage.semantic_review`）；同一回合重复复核只保留一条历史记录。若复核期间会话已提交新回合，返回 `400` 并提示结果过期，不会覆盖最新状态。

切换场景卡时只需传 `sceneCardId`：服务端按 id 读取 `scene-cards/{card_id}/scene-card.json`，并把卡内 `fields` 写入会话；显式传入的 `sceneProfile` 覆盖同名卡字段。卡不存在返回 `400`。

导演请求：

```json
{"goal":"让两人发现共同线索","action":"advance","option_count":3}
```

`action` 允许 `advance`、`slow_emotion`、`conflict`、`viewpoint`、`fourth_wall`。`fourth_wall` 响应中的 `message_kind` 为 `fourth_wall`，选项会额外包含可选的 `resistance` 与 `price` 字段。

切换场景请求：

```json
{
  "scene_card_id": "scene-1",
  "scene_profile": {},
  "transition_message": "天色渐暗，众人回到客栈。",
  "auto_continue": false
}
```

高级对话路由通常返回会话对象，并统一补充 `transcript_count`。

## 6. 章节、检索与问书

### 6.1 章节查询与 CRUD

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/chapters` | 无 | `200 {"items":[Chapter...]}` |
| POST | `/api/web/runs/{run_id}/chapters` | `SaveChapterRequest` | `200` 新章节 |
| PUT | `/api/web/runs/{run_id}/chapters/{chapter_id}` | `SaveChapterRequest` | `200` 更新后的章节 |
| PATCH | `/api/web/runs/{run_id}/chapters/{chapter_id}/order` | `{"target_order":2}` | `200` 章节列表/排序结果 |
| DELETE | `/api/web/runs/{run_id}/chapters/{chapter_id}` | 无 | `200 {"status":"deleted","chapter_id":"..."}` |

保存章节请求：

```json
{
  "title": "雨夜来客",
  "goal": "揭示旧宅的秘密",
  "participants": ["沈照"],
  "content": "正文……"
}
```

章节主要字段为 `chapter_id`、`order`、`title`、`goal`、`participants`、`content`、`source_session_id`、`last_session_id`、`created_at`、`updated_at`。

### 6.2 从会话归档、续写与同步

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/{run_id}/chapters/archive-session` | `{"session_id":"...","title":""}` | `200` 归档章节 |
| POST | `/api/web/runs/{run_id}/chapters/convert-session` | 同上 | `200` 模型整理后的章节 |
| POST | `/api/web/runs/{run_id}/chapters/{chapter_id}/continue` | 无 | `200` 新会话/续写上下文 |
| POST | `/api/web/runs/{run_id}/chapters/{chapter_id}/sync-session` | 无 | `200` 同步后的章节 |
| POST | `/api/web/runs/{run_id}/chapters/{chapter_id}/rewrite` | `RewriteChapterRequest` | `200` 改写后的章节；模型失败可能返回 `502` |

改写请求：

```json
{"instruction":"加强环境描写，保持情节不变","context_summary":"上一章停在雨夜。"}
```

### 6.3 搜索、问书与导出

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/search` | `query` 必填、1..100 字符；`limit` 默认 30、范围 1..100 | `200 {"items":[SearchResult...]}` |
| POST | `/api/web/runs/{run_id}/ask` | `{"question":"..."}` | `200 {"answer":"...","evidence":[...]}` |
| GET | `/api/web/runs/{run_id}/chapters/export` | `format=markdown|text`，默认 `markdown` | `200 text/plain; charset=UTF-8`，附件 `.md` 或 `.txt` |

搜索结果的 `kind` 可表示 `chapter`、`persona` 或 `session`，并按类型携带 `chapter_id`、`session_id`、`character`、`title`、`preview`。

## 7. 人物、关系与知识记忆

### 7.1 人物档案

基础路径：`/api/web/runs/{run_id}/personas/{character}`。

| 方法 | 子路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `` | 无 | `200 PersonaReview` |
| PUT | `` | 任意字符串字段的 JSON 对象 | `200 PersonaReview` |
| DELETE | `` | 无 | `200 {"status":"deleted"}`；同时清理头像、关系条目与 manifest 索引 |
| GET | `/quality-report` | 无 | `200 PersonaQualityReport` |
| GET | `/repair-proposal` | 无 | `200 PersonaRepairProposal` |
| POST | `/suggest-field` | 见下 | `200` 字段建议 |
| POST | `/evolve/proposal` | `GenerateEvolutionProposalRequest`（可选包含 `recap`） | `200 PersonaEvolutionProposal` |
| POST | `/evolve/apply` | `ApplyPersonaEvolutionRequest` | `200 PersonaReview` |
| POST | `/avatar` | `multipart/form-data`，文件 part 名为 `file` | `200 {"character":"...","avatar_version":"..."}` |
| GET | `/avatar` | 无 | `200 image/png` |
| GET | `/sillytavern-card` | 无 | `200` Character Card JSON 附件，V3 来源导出 V3，其余导出 V2；人物不存在为 `404` |

角色卡导出读取当前已保存的人物档案。对于 SillyTavern 导入的人物，会保留原卡首句、备用开场和卡内世界书。JSON 文件不含头像和会话记录。

造梦客户端也可生成带头像的 PNG 角色卡。客户端读取同一个 JSON 接口和头像接口，移除已有角色数据后，将 V2 写入 `chara` 文本块，V3 写入 `ccv3` 文本块。PNG 须符合重新导入的尺寸和大小限制，最终文件最多 5 MiB，内嵌 JSON 最多 1 MiB。

小说蒸馏时同时生成人物档案和角色卡文本。导出 V2 时优先使用 `card_description`、`card_personality`、`card_scenario`、`card_first_mes` 和 `card_mes_example`，分别对应 `description`、`personality`、`scenario`、`first_mes` 和 `mes_example`。旧档案的描述、性格和场景可由已有档案字段映射。示例使用 `<START>` 及 `{{user}}:`、`{{char}}:` 区分双方台词。

导入角色卡的人物资料响应包含 `card_fields`，提供 `description`、`personality`、`scenario`、`mes_example`、`system_prompt`、`post_history_instructions` 的原始文本。可在人物资料的 PUT 请求中使用这些原生键修改对应文本，省略的字段保持原值，空字符串清空字段。两项提示词修改会用于后续单角色对话并写入导出的角色卡。此类人物的质量报告返回 `applicable: false`，造梦的蒸馏完整度评分不适用。

导入卡的 `extensions.depth_prompt` 是角色注记。在单人「加入对话」的对话轮中，`prompt` 文本按 `depth` 和 `role` 插入聊天历史，缺省为深度 4、系统角色。深度 0 位于本轮输入之后。支持角色名、玩家名和世界书 Outlet 宏，文本最多读取 4000 字符，原始扩展字段随角色卡导出。

导入角色卡的人物资料还返回 `card_metadata`，包含 `creator`、`creator_notes`、`character_version`、`tags` 和 `revision`。旧卡可用 `creatorcomment` 作为作者说明。作者和版本最多显示各 120 字符，标签去重后最多显示 32 个，每个最多 64 字符。作者说明最多返回 16000 字符。超出编辑范围的说明或标签分别通过 `creator_notes_truncated`、`tags_truncated` 标记。

`PATCH /api/web/runs/{run_id}/personas/{character}/card-metadata` 使用上次读取的 `revision` 更新作者资料或角色注记。作者字段与上述文本和标签字段同名，传入空字符串或空标签数组可清空字段，省略字段保留原值。角色注记使用 `character_note: {"text":"保持简洁。","depth":4,"role":"system"}`。文本最多 4000 字符，深度为 0 至 10000，角色为 `system`、`user` 或 `assistant`。空文本停止注入。响应中的 `character_note_truncated` 表示原卡注记超过 4000 字符，`character_note.text` 只显示前 4000 字符。成功返回 `200 SillyTavernCardMetadataDto`，参数无效为 `400`，角色卡不存在为 `404`，卡片已更新为 `409`。保存后的字段可随角色卡导出。

人物档案更新示例：

```json
{"core_identity":"旧宅书吏","speech_style":"寡言克制"}
```

字段建议请求以服务端路由内的实际模型为准：

```json
{
  "field": "speech_style",
  "current_fields": {"core_identity":"旧宅书吏"}
}
```

### 7.2 人物关系

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/relations` | 无 | `200 RelationDetails` |
| PATCH | `/api/web/runs/{run_id}/relations/{pair_key}` | `UpdateRelationDetailRequest` | `200 RelationDetails` |

更新请求中的四个数值字段必填：

```json
{
  "trust": 5,
  "affection": 3,
  "hostility": 0,
  "ambiguity": 2,
  "relationship_type": "盟友",
  "relation_change": "共同经历危险后更加信任",
  "conflict_point": "对计划风险看法不同",
  "typical_interaction": "克制地互相试探"
}
```

### 7.3 世界记忆

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/world-memory` | 无 | `200 WorldMemory` |
| POST | `/api/web/runs/{run_id}/world-memory/facts` | `SaveWorldFactRequest` | `201 WorldFact` |
| PUT | `/api/web/runs/{run_id}/world-memory/facts/{fact_id}` | 同上 | `200 WorldFact` |
| DELETE | `/api/web/runs/{run_id}/world-memory/facts/{fact_id}` | 无 | `200 {"status":"deleted"}` |

```json
{
  "category": "event",
  "summary": "旧宅在十年前曾失火。",
  "characters": ["沈照"],
  "location": "旧宅",
  "time_hint": "十年前",
  "locked": false,
  "active": true
}
```

`summary` 必填，服务端最多保留 500 字符；`location` 最多 100 字符，`time_hint` 最多 80 字符。世界记忆响应还可能包含从对话抽取的 `timeline`。

### 7.4 原文知识

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/original-knowledge` | 无 | `200` 原文知识索引；不存在时自动构建 |
| POST | `/api/web/runs/{run_id}/original-knowledge/rebuild` | 无 | `200` 重建后的索引 |
| POST | `/api/web/runs/{run_id}/original-knowledge/search` | `SearchOriginalKnowledgeRequest` | `200 {"items":[OriginalKnowledgeEntry...]}` |
| PUT | `/api/web/runs/{run_id}/original-knowledge/entries/{entry_id}/boundary` | `{"visibility":"...","knowers":[]}` | `200` 更新后的索引/条目 |
| PUT | `/api/web/runs/{run_id}/original-knowledge/entries/{entry_id}/pinned` | `{"pinned":true}` | `200` 更新后的索引/条目 |

搜索请求：

```json
{"query":"失火原因","participants":["沈照"],"limit":6,"pinned_only":false}
```

搜索条目包含 `source_id`、`title`、`excerpt`、`score`、`visibility`、`knowers`、`characters`、`boundary_source`、`pinned`、`epistemic_status`、`allowed_characters`、`denied_characters`，以及 `location.start_char/end_char`。`excerpt` 是有界原文片段。

## 8. 卡片

### 8.1 AI 生成预览

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/scene-cards/generate` | 无 | `200` 场景卡生成结果；模型失败可能 `502` |
| POST | `/api/web/self-cards/generate` | 无 | `200` 自我卡生成结果；模型失败可能 `502` |

生成结果包含可保存的字段和 `preview`，不直接持久化为卡片。

### 8.2 可复用卡片 CRUD

以下 `{kind}` 不是实际路径参数，而是三组展开后的固定路径：

| kind | 基础路径 |
|---|---|
| 场景卡 | `/api/web/scene-cards` |
| 自我卡 | `/api/web/self-cards` |
| 开场预设 | `/api/web/opening-presets` |

每组都提供：

| 方法 | 相对路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `` | 无 | `200 {"items":[ReusableCard...]}` |
| GET | `/{card_id}` | 无 | `200 ReusableCard` |
| POST | `` | 任意卡片字段 `JsonObject` | `200 ReusableCard` |
| PUT | `/{card_id}` | 任意卡片字段 `JsonObject` | `200 ReusableCard` |
| DELETE | `/{card_id}` | 无 | `200` 删除状态 |

场景卡额外提供：

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/scene-cards/recommend` | `{"mode":"observe","participants":[]}` | `200` 推荐卡片集合 |

可复用卡片主要字段为 `card_id`、`fields`、`preview`、`created_at`、`updated_at`。各类 `fields` 是开放 JSON 对象，调用方应保留未知字段。

## 9. 模型设置

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/settings/model` | 无 | `200 ModelSettings` |
| PUT | `/api/web/settings/model` | `SaveModelSettingsRequest` | `200 ModelSettings` |
| POST | `/api/web/settings/model/test` | `TestModelSettingsRequest` | `200 ModelConnectionTest` |
| POST | `/api/web/settings/model/discover` | `base_url`、`api_key`、`profile_id`（均可省略） | `200 {"models":["model-id"]}`，`400` 请求或地址无效，`502` 上游请求或解析失败，`500` 内部错误 |
| POST | `/api/web/settings/model/detect-capabilities` | `TestModelSettingsRequest` | `200 ModelCapabilityReport` |
| POST | `/api/web/settings/model/profiles/{profile_id}/activate` | 无 | `200 ModelSettings` |
| DELETE | `/api/web/settings/model/profiles/{profile_id}` | 无 | `200 ModelSettings` |

`ModelSettings` 和各档案返回 `request_timeout_seconds`。保存、测试连接和检测模型能力的请求可提供该字段，范围为 1–300 秒，越界返回 `400`。省略时沿用对应档案值，旧档案或新档案缺省时使用 60 秒。该设置用于单次非流式模型请求，包括连接测试和非流式能力探测。

模型发现向 OpenAI 兼容接口发送 `GET {base_url}/models`，读取 `data[].id` 并返回去重、排序后的模型 ID。接口地址留空时使用 `https://api.openai.com/v1`。密钥留空时，仅在指定档案的模型列表地址与请求地址一致时复用该档案的密钥。没有可用密钥时，向上游发送不带 `Authorization` 的请求，以支持公开的模型列表。上游返回 `401` 时，此接口返回 `502` 并提示填写或核对 API Key。请求不会保存模型设置。

保存请求：

```json
{
  "provider": "openai-compatible",
  "model": "model-name",
  "base_url": "https://example.com/v1",
  "api_key": "仅在请求中传输，不会出现在响应中",
  "max_tokens": 4096,
  "reasoning_effort": "off",
  "token_parameter": "auto",
  "response_format_mode": "auto",
  "profile_id": "",
  "profile_name": "默认配置",
  "create_profile": false,
  "activate_profile": true
}
```

连接测试/能力检测请求字段相同，但没有 `profile_name`、`create_profile`、`activate_profile`。响应只返回 `api_key_configured`，绝不回传明文 API Key。

能力检测响应包括连接状态、TTFT、总耗时、真实流式支持、SSE 分块统计、NDJSON 遵循度、结构化响应与 reasoning-off 支持，以及推荐的 token/响应格式设置。

## 10. 插件

### 10.1 插件列表、状态、配置与日志

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/plugins` | 无 | `200 {"items":[Plugin...]}` |
| POST | `/api/web/plugins/refresh` | 无 | `200` 当前插件列表；当前实现等价于 list |
| POST | `/api/web/plugins/{plugin_id}/enable` | 无 | `200` 更新后的插件 |
| POST | `/api/web/plugins/{plugin_id}/disable` | 无 | `200` 更新后的插件 |
| GET | `/api/web/plugins/{plugin_id}/config` | 无 | `200 {"config":{...}}` |
| PUT | `/api/web/plugins/{plugin_id}/config` | `{"config":{...}}` | `200 {"config":{...}}` |
| GET | `/api/web/plugins/{plugin_id}/logs` | 无 | `200` 插件日志列表 |
| DELETE | `/api/web/plugins/{plugin_id}` | 无 | `200` 卸载结果；内置插件不可卸载时返回 `400` |

日志接口按写入顺序返回最近 200 条可解析的日志，合并旧版日志文件和分段日志。无法解析为日志对象的行会跳过，原始文件仍保留。

插件描述中的 `apiVersion`、`defaultEnabled`、`executionMode`、`capabilityNotice` 以及 `contributes.chatActions/generationEnhancers/temporaryNpcGenerators` 使用 `camelCase`，这是插件公共契约的一部分。

当前 KMP 宿主支持两类第三方插件：

- `execution.mode = "declarative"`：外置插件不携带可执行代码，只声明聊天动作和临时 NPC 生成器如何调用宿主能力。宿主会校验每个贡献点都有对应配方，并以 `executable=true`、`executionMode="declarative-kotlin"` 返回。
- 未提供 `execution` 的旧 `entry=main.py` 包：仍可检查和保存，但 `executable=false`、`executionMode="unsupported"`，不会运行其中的 Python 或其他任意代码。

当前宿主 API 版本为 `2`，兼容声明式插件 API `1` 和 `2`。其它版本在检查阶段返回 `compatible=false` 和具体的 `blockedReason`，服务端拒绝安装；缺省 `apiVersion` 按兼容 API `1` 处理。

声明式插件最小示例：

```json
{
  "id": "example-quick-reply",
  "name": "快捷接话",
  "version": "1.0.0",
  "apiVersion": "2",
  "permissions": ["chat.context.read", "chat.draft.write", "model.invoke"],
  "contributes": {
    "chatActions": [
      {"id": "quick-reply", "title": "快捷接话", "placement": "composer", "icon": "sparkles"}
    ]
  },
  "execution": {
    "mode": "declarative",
    "chatActions": {
      "quick-reply": {
        "operation": "suggest",
        "direction": "结合当前场景生成下一句，语气为{{config.tone}}，草稿：{{seed_text}}"
      }
    }
  }
}
```

`operation` 允许 `suggest` 或 `variants`；模板支持 `{{seed_text}}`、`{{direction}}` 和 `{{config.<key>}}`。临时 NPC 生成器位于 `execution.temporaryNpcGenerators.<id>.direction`。生成增强器位于 `execution.generationEnhancers.<id>.rule`，启用后会作为当前会话的提示词规则注入每一轮主对话生成。

API 2 还允许在 `execution.rules` 中组合事件、条件和动作链。规则不会执行插件自带代码；宿主会在对话生成前或回合提交后解释执行：

```json
{
  "permissions": ["chat.context.read", "generation.enhance", "model.invoke", "chat.state.write"],
  "execution": {
    "mode": "declarative",
    "rules": [
      {
        "id": "merchant-arrives",
        "title": "神秘商人登场",
        "event": "before_generation",
        "match": {"everyTurns": 5, "chancePercent": 30},
        "actions": [
          {"type": "add_instruction", "instruction": "让一名神秘商人自然进入当前场景。"}
        ]
      },
      {
        "id": "remember-refusal",
        "title": "记录拒绝",
        "event": "after_turn",
        "match": {"keywords": ["拒绝", "不买"]},
        "actions": [
          {"type": "increment_state", "key": "refusals", "amount": 1}
        ]
      }
    ]
  }
}
```

- 事件：`before_generation`、`after_turn`。
- 条件：`keywords`（命中任一）、`everyTurns`（2–100，省略表示不限）、`chancePercent`（1–100）、配对使用的 `stateKey/stateEquals`；一条规则内的非空条件同时满足才触发。
- 动作：生成前可用 `add_instruction`；回合结束后可用 `set_state`、`increment_state`。状态按插件、按会话隔离；每条规则保留最近 64 个 `turn_id` 回执，连续重试和延迟重放都不会重复修改状态。规则状态与 transcript 在同一次历史提交中原子写入并使用原轮 `turn_count/state`，不会留下“正文已提交、规则待补写”的恢复窗口；`plugin_rule_directives` 只在本轮准备期存在，不持久化。
- 规则模板支持 `{{message}}`、`{{config.<key>}}`、`{{state.<key>}}`。单插件最多 8 条规则，每条最多 6 个动作；概率由会话、回合和规则 ID 稳定计算，模型重试不会改变命中结果。

插件数据配方允许 `operation` 为 `storage_get` 或 `storage_set`：

```json
{
  "operation": "storage_set",
  "key": "notes",
  "value": "{{seed_text}}"
}
```

`key` 必须匹配 `^[A-Za-z0-9_-]+$`。旧 raw 数据仍位于 `plugins/<id>/data/<key>.txt`；版本化写入使用独立的 `data/.versions/<key>.json`，读取优先使用版本记录，不通过 JSON 内容嗅探旧 raw 值。`suggest`、`variants` 和 NPC 配方中还可以使用 `{{storage.<key>}}` 读取插件数据；使用前需要声明 `storage.read`，写入需要 `storage.write`。

原生插件可将宿主识别为 `VersionedPluginDataHost`，按 `(pluginId,key)` 读取 `PluginDataSnapshot(value,revision)` 并调用 `compareAndSetPluginData`。缺失 key 和未迁移 raw 的 revision 为 `0`；每次成功写入（包括旧 `writePluginData`）在同一插件目录锁内把 revision 持久递增 `1`。CAS 的 `expectedRevision` 不一致时抛出 `PluginDataRevisionConflictException` 且不写入，调用方应重新读取后决定。revision 不属于 session 历史版本，也不是多 key 事务；保证范围为当前进程内的共享存储锁，不承诺跨进程互斥。声明式 `storage_get/storage_set` 的 HTTP 响应暂不暴露 revision。

网络配方允许 `operation` 为 `http_get` 或 `http_post`：

```json
{
  "operation": "http_post",
  "url": "https://example.com/api",
  "headers": {"Content-Type": "application/json"},
  "body": "{{seed_text}}"
}
```

网络请求要求声明 `network.access`，且只允许不含用户凭据的 HTTPS URL。宿主拒绝显式 localhost、私有/链路本地/保留 IP 和常见内部域名，禁止重定向以及 `Host`、`Content-Length` 等逐跳/路由请求头，设置 30 秒超时，并把响应限制为 1 MiB。响应文本会作为插件聊天动作的建议文本返回。

代角色回复配方允许 `operation = "reply_as_character"`，需要声明 `run.personas.read`：

```json
{
  "operation": "reply_as_character",
  "direction": "选择最符合当前情境的人物替用户回复"
}
```

宿主会列出当前 run 的已蒸馏人物，生成回复并返回 `character` 与 `suggestion`。客户端把 `character` 放入输入框并切换为对白模式；当前阶段仍由用户确认发送。

会话角色禁言配方允许 `operation = "mute_character"` 或 `"unmute_character"`，需要 `chat.cast.write`：

```json
{
  "operation": "mute_character",
  "character": "{{seed_text}}"
}
```

宿主把 `muted_characters` 写入 session，对话生成时会从允许回复集合中排除被禁言角色。插件动作响应会携带更新后的 `session`。

### 10.2 插件工坊校验与打包

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/plugins/builder/validate` | `{"draft": PluginDraft}` | `200 PluginBuilderValidation`；草稿不完整时仍返回 `200`，通过 `valid=false` 和 `issues` 给出实时校验结果 |
| POST | `/api/web/plugins/builder/generate` | `{"description":"自然语言玩法描述"}` | 使用当前活动模型生成安全的声明式 `PluginDraft`，经运行时校验后返回 `200 PluginBuilderValidation`；需求为空返回 `400` |
| POST | `/api/web/plugins/builder/package` | `{"draft": PluginDraft}` | `200 application/zip`；`Content-Disposition` 提供 `插件名-版本.zaomeng-plugin.zip`，校验失败返回 `400` |

`PluginDraft` 是面向 App 可视化编辑器的稳定草稿契约，主要字段如下：

```json
{
  "draft": {
    "name": "温柔接话",
    "id": "plugin-a1b2c3d4",
    "version": "0.1.0",
    "description": "生成温柔自然的回复草稿",
    "template": "chat_action",
    "title": "温柔接话",
    "prompt": "结合当前场景生成下一句。语气：{{config.tone}}。草稿：{{seed_text}}",
    "actionMode": "suggest",
    "settings": [
      {
        "key": "tone",
        "title": "语气",
        "type": "enum",
        "defaultValue": "温柔",
        "options": ["温柔", "克制", "直接"]
      }
    ]
  }
}
```

`template` 支持 `chat_action`、`generation_enhancer`、`temporary_npc`；聊天动作的 `actionMode` 支持 `suggest` 和 `variants`。设置类型支持 `boolean`、`integer`、`enum`。

校验服务会规范化 ID 和字段、自动推导 `permissions` 及其用户可见原因、检查变量引用与设置冲突、生成最终 `manifest/manifestJson`，最后复用实际的 `DeclarativePluginLoader` 判定清单是否可执行。客户端不应自行声明权限或把本地表单校验当成最终有效性判定。

打包接口会再次执行相同校验，只为有效草稿生成包含 `plugin.json`、`README.md` 的 ZIP；存在设置项时还会写入带默认值的 `config.json`，保证安装后无需先手动保存配置即可试用。生成包可直接复用 10.3 的 inspect/install 两阶段流程；打包和安装都不会运行插件携带的任意代码。

### 10.3 插件包检查与安装

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/plugins/packages/inspect` | `InspectPluginPackageRequest` | `200` 检查结果和短期安装 token |
| POST | `/api/web/plugins/packages/{token}/install` | `InstallPluginPackageRequest` | `200` 已安装插件 |

检查请求：

```json
{"filename":"plugin.zip","content_base64":"UEsDB..."}
```

检查结果包含 `token`、`plugin`、`operation`、`blockedReason`、`currentVersion`、`compatible`、`hostApiVersion`、`fileCount`、`extractedBytes`。安装前应展示权限并显式确认：

```json
{"confirm_permissions":true,"allow_update":false}
```

服务端强制校验 `confirm_permissions=true`。更新采用备份后替换的方式，保留原插件的 `config.json`、`data/`（包括 revision 记录）和 `plugin-logs.jsonl`；替换失败时恢复旧目录。插件数据读写与安装、升级、卸载通过同一插件目录生命周期锁串行化，避免状态恢复期间出现 revision 回退或成功写入被旧备份覆盖。安装包不得携带宿主管理的 `log-segments` 或 `data/.versions`。安装或更新完成后插件保持关闭，需要用户再次显式启用。

### 10.4 会话内插件动作

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/actions/{action_id}` | `{"seed_text":"...","direction":"...","selection":"..."}` | `200` 建议、待选择项或动作结果 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/npc-generators/{generator_id}` | `{"direction":"..."}` | `200` 更新后的会话、NPC 和提示 |
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/enhancers/{enhancer_id}/state` | `{"enabled":true}` | `200` 更新后的 enhancer 状态/会话 |

三个执行端点都会在服务端校验插件处于启用状态，并确认请求的 action、NPC generator 或 enhancer 已在该插件清单中声明；仅依赖客户端隐藏入口不构成授权。enhancer 状态属于会话元数据，不推进历史 revision；服务端在 latest manifest 锁内局部更新，客户端只合并 `plugin_enhancer_states`，不替换已分页加载的 transcript 或游标。

聊天动作支持两阶段选择：首次请求不传 `selection`，插件可返回 `choice_prompt` 与 `choices`（每项含 `label`、`value`、`description`）；客户端展示后把所选 `value` 作为 `selection` 再次请求同一动作。响应也可返回 `suggestion`、`suggestions`、`notice`、`character` 或更新后的 `session`。

## 11. 核心响应对象

### 11.1 RunManifest

运行 manifest 是可演进 JSON，对调用方最稳定的字段如下：

```json
{
  "run_id": "run-1",
  "novel_id": "novel-1",
  "novel_name": "作品名",
  "status": "ready",
  "success": true,
  "created_at": "...",
  "updated_at": "...",
  "locked_characters": [],
  "novel_sources": [],
  "progress": {},
  "summary": {},
  "timing": {},
  "control": {},
  "artifact_index": {"characters": []}
}
```

常见终态为 `ready`、`failed`、`stopped`、`draft`。客户端应保留未知字段，以兼容运行进度、导入来源和 beta 元数据演进。

### 11.2 DialogueSession

完整会话对象字段较多，主要分为：

- 基础信息：`session_id`、`run_id`、`title`、`mode`、`participants`、`controlled_character`、`status`、时间字段。
- 消息：`transcript`、`transcript_count`、`turn_count`、`current_turn_id`、`pending_turn_summary`。
- 场景/身份：`scene_card_id`、`scene_card`、`scene_profile`、`self_card_id`、`self_insert`、`self_profile`。
- 记忆/进展：`memory_ledger`、`scene_history`、`event_timeline`、`scene_progress`、`story_recap`。
- 关系/分支：`relation_matrix`、`relation_timeline`、`relation_locks`、`branch_graph`、`branch_origin`、`branch_meta`。
- 诊断与插件：`consistency_monitor`、`speaker_activity`、`generation_cache_stats`、`latest_context_usage`、`plugin_enhancer_states`。

会话 JSON 是增量演进的数据结构，调用方应忽略并保留未知字段。列表接口只返回轻量 `SessionListItem`，不要把它当作完整会话覆盖本地详情。

### 11.3 原文证据

对话和问书结果中的证据条目使用以下核心字段：

```json
{
  "source_id": "source-1",
  "title": "第一章",
  "excerpt": "有界原文片段",
  "score": 0.92,
  "visibility": "uncertain",
  "knowers": [],
  "characters": [],
  "pinned": false,
  "location": {"start_char": 12, "end_char": 30}
}
```

## 12. 调用示例

### 12.1 创建并查询书卷

```bash
curl -X POST "http://127.0.0.1:${PORT}/api/web/runs" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"novel_name":"demo.txt","novel_content_base64":"...","characters":["沈照"]}'
```

```bash
curl "http://127.0.0.1:${PORT}/api/web/runs/${RUN_ID}" \
  -H "Authorization: Bearer ${TOKEN}"
```

### 12.2 流式对话

```bash
curl -N -X POST \
  "http://127.0.0.1:${PORT}/api/web/runs/${RUN_ID}/dialogue/sessions/${SESSION_ID}/reply/stream" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"message":"你好","operation_id":"turn-001","include_transcript":false}'
```

## 13. 维护索引

接口注册入口：

- `kmp/app/shared/src/commonMain/kotlin/top/wkbin/zaomeng/backend/LocalBackendController.kt`
- `kmp/server/src/commonMain/kotlin/top/wkbin/zaomeng/ktor/routes/`

共享请求/响应契约：

- `kmp/core/contracts/src/commonMain/kotlin/top/wkbin/zaomeng/data/api/`
- `kmp/server/src/commonMain/kotlin/top/wkbin/zaomeng/ktor/models/`（少量服务端局部契约）

修改接口时应同步检查路由、共享 DTO、客户端 Repository/ViewModel、SSE 事件解析和回归测试。新增字段优先提供默认值；删除或改名字段需要兼容策略。
