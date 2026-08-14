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
- token 缺失或错误返回 `401`，响应为 `{"detail":"Bearer authentication is required."}`，并带 `WWW-Authenticate: Bearer`。
- 当前应用控制器的默认 token 为 `dev-token`，但调用方应使用运行时提供的 token，不应依赖该默认值。

### 1.3 编码与字段命名

- JSON 请求使用 `Content-Type: application/json; charset=utf-8`。
- JSON 字段通常使用 `snake_case`；插件清单中的部分字段因插件 API 契约使用 `camelCase`，详见插件章节。
- 服务端 JSON 配置为宽松解析并忽略未知字段；响应会编码默认字段。
- `{character}`、`{pair_key}`、插件 ID 等路径参数必须进行 URL 编码。
- 时间字段是 ISO 8601 字符串；ID 是不透明字符串，调用方不应解析其格式。

### 1.4 通用错误

路由按模块返回 `error` 或 `detail`，尚未统一：

```json
{"detail": "错误说明"}
```

或：

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

不同路由的错误字段和个别状态码存在历史差异，客户端应同时兼容 `detail` 与 `error`。

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

健康检查响应固定为：

```json
{"status":"ok","version":"0.1.0","backend":"ktor"}
```

诊断报告可能包含运行环境、存储概况与脱敏后的错误信息，不应包含模型 API Key。

## 3. 书卷与蒸馏运行

### 3.1 查询、创建、导入与生命周期

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs` | 无 | `200` `{"items":[RunManifest...]}` |
| GET | `/api/web/runs/{run_id}` | 无 | `200` `RunManifest`；人物索引注入实时 `avatar_version` |
| POST | `/api/web/runs` | `CreateRunRequest` | `201` 新运行 manifest |
| POST | `/api/web/runs/import` | `ImportRunPackageRequest` | `201` 导入结果/运行 manifest |
| POST | `/api/web/runs/{run_id}/control/stop` | 无 | `200` 停止后的运行 manifest |
| POST | `/api/web/runs/{run_id}/stop` | 无 | 同上；旧 WebUI 兼容别名 |
| DELETE | `/api/web/runs/{run_id}` | 无 | `200` 删除统计 |
| POST | `/api/web/runs/{run_id}/refresh` | 无 | `200` 刷新后的运行 manifest |

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

删除响应的主要字段：`status`、`novel_id`、`deleted_run_count`、`deleted_session_count`、`deleted_run_ids`。

### 3.2 估算、重蒸馏与恢复

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/estimate` | `EstimateSamplingRequest` | `200` 抽样、调用量、token 与耗时估算 |
| POST | `/api/web/runs/{run_id}/redistill` | `RestartRunRequest` | `200` 重蒸馏后的运行 manifest |
| POST | `/api/web/runs/{run_id}/resume-distill` | 无 | `200` 恢复后的运行 manifest |
| POST | `/api/web/runs/{run_id}/redistill/recommend` | `SuggestRedistillSegmentsRequest` | `200` 推荐原文片段 |

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
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}` | `include_transcript=true` | `200` 完整或轻量会话对象 |
| DELETE | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}` | 无 | `200 {"status":"deleted"}` |
| PATCH | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/title` | `{"title":"新标题"}` | `200` 更新后的会话对象 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/prepare` | `PrepareDialogueTurnRequest` | `200` 写入待处理用户消息后的会话对象 |

创建会话请求：

```json
{
  "mode": "observe",
  "participants": ["林黛玉", "贾宝玉"],
  "controlled_character": "",
  "scene_card_id": "",
  "scene_profile": {},
  "self_card_id": "",
  "self_profile": {}
}
```

`mode` 默认为 `observe`。参与者、受控人物和卡片组合的进一步约束由业务服务校验。

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
  "include_inner_thoughts": false,
  "operation_id": "client-generated-id"
}
```

`operation_id` 用于幂等和本轮 transcript 关联，建议由客户端每次操作生成稳定且非空的唯一值。

### 4.2 消息分页

`GET /api/web/runs/{run_id}/dialogue/sessions/{session_id}/messages`

| Query | 类型 | 默认值 | 约束/语义 |
|---|---:|---:|---|
| `offset` | int | `0` | 小于 0 时归零；`desc` 时表示跳过最新 N 条 |
| `limit` | int | `100` | `1..500` |
| `order` | string | `asc` | `asc` 或 `desc` |

响应：

```json
{
  "items": [
    {
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
  "has_more": false
}
```

### 4.3 非流式回复

`POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply`

请求为 `DialogueReplyRequest`：

```json
{
  "message": "你怎么看这件事？",
  "message_kind": "dialogue",
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
- `speaker_override` 默认为空；内置“帮我回”等可信流程可传入已蒸馏人物名，使本轮输入以该人物身份进入提示词和 transcript，服务端会拒绝未知人物名。
- `include_model_reasoning` 仅流式服务使用；非流式路由当前不会向业务服务传递该字段。
- `include_transcript=false` 时返回轻量会话（不含完整 transcript，但含 `transcript_count`）；为 `true` 时返回带完整 transcript 的会话。

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
| `status` | `phase`, `message` | 已进入生成阶段；当前 `phase=generating` |
| `reset` | `message` | 丢弃此前增量并重置可见内容 |
| `delta` | `index`, `speaker`, `role`, `field`, `text` | 某条角色响应的字段增量；`field` 常为 `message` 或 `inner_thought` |
| `complete` | `session`, `replayed`, `transcript_count`, 可选 `appended_transcript` | 生成及持久化完成 |
| `error` | `message`, `retryable` | 流中失败；HTTP 状态通常已是 `200`，应按事件处理 |

`complete` 规则：

- `include_transcript=true`：`session` 包含完整 transcript，不返回 `appended_transcript`。
- `include_transcript=false`：`session` 为轻量对象，`appended_transcript` 只含 `operation_id` 对应本轮新增项。
- 当前 `replayed` 固定返回 `false`。

服务端从模型响应体到 SSE 增量转发；模型侧主协议是每行一个完整对象的 NDJSON，但 HTTP 调用方只需要消费上述 SSE，不直接解析模型 NDJSON。

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

### 5.4 导演、纠错、关系锁与场景

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/relation-lock` | `{"pair_key":"甲::乙","locked":true}` | `200` 会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/suggest` | `{"seed_text":"...","direction":"..."}` | `200` 建议对象 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/correct-latest` | 无 | `200` 会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/deep-review` | 无 | `200` 审查结果/会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/director-options` | `DialogueDirectorRequest` | `200` 导演选项 |
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/scene-card` | `SwitchDialogueSceneRequest` | `200` 更新后的会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/scene-card/recommend` | 无 | `200` 推荐场景卡 |

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
| POST | `/avatar` | `multipart/form-data`，文件 part 名为 `file` | `200 {"character":"...","avatar_version":"..."}` |
| GET | `/avatar` | 无 | `200 image/png` |

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
| POST | `/api/web/settings/model/detect-capabilities` | `TestModelSettingsRequest` | `200 ModelCapabilityReport` |
| POST | `/api/web/settings/model/profiles/{profile_id}/activate` | 无 | `200 ModelSettings` |
| DELETE | `/api/web/settings/model/profiles/{profile_id}` | 无 | `200 ModelSettings` |

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
- 动作：生成前可用 `add_instruction`；回合结束后可用 `set_state`、`increment_state`。状态按插件、按会话隔离，同一 `turn_id` 重放不会重复修改状态。
- 规则模板支持 `{{message}}`、`{{config.<key>}}`、`{{state.<key>}}`。单插件最多 8 条规则，每条最多 6 个动作；概率由会话、回合和规则 ID 稳定计算，模型重试不会改变命中结果。

插件数据配方允许 `operation` 为 `storage_get` 或 `storage_set`：

```json
{
  "operation": "storage_set",
  "key": "notes",
  "value": "{{seed_text}}"
}
```

`key` 必须匹配 `^[A-Za-z0-9_-]+$`，文件写入 `plugins/<id>/data/<key>.txt`。`suggest`、`variants` 和 NPC 配方中还可以使用 `{{storage.<key>}}` 读取插件数据；使用前需要声明 `storage.read`，写入需要 `storage.write`。

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

服务端强制校验 `confirm_permissions=true`。更新采用备份后替换的方式，保留原插件的 `config.json`、`data/` 和 `plugin-logs.jsonl`；替换失败时恢复旧目录。安装或更新完成后插件保持关闭，需要用户再次显式启用。

### 10.4 会话内插件动作

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/actions/{action_id}` | `{"seed_text":"...","direction":"...","selection":"..."}` | `200` 建议、待选择项或动作结果 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/npc-generators/{generator_id}` | `{"direction":"..."}` | `200` 更新后的会话、NPC 和提示 |
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/enhancers/{enhancer_id}/state` | `{"enabled":true}` | `200` 更新后的 enhancer 状态/会话 |

三个执行端点都会在服务端校验插件处于启用状态，并确认请求的 action、NPC generator 或 enhancer 已在该插件清单中声明；仅依赖客户端隐藏入口不构成授权。

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
