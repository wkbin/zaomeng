# Python 与 Ktor 提示词构建差异对比

**更新时间**: 2026-08-07
**目的**: 对齐 Ktor 与 Python 各 LLM 接口的提示词构建，消除效果差异
**结论摘要**:
1. **system prompt 层已一致**：Ktor `PromptLoader.kt` 与 Python `prompts/loader.py` 逐函数同构，8 个 YAML 均已加载。
2. **user prompt / 上下文层差异巨大**：Python 为每条 LLM 请求构建完整的运行上下文 JSON payload（memory、persona、relation、scene_progress、history、speaker_plan 等）；Ktor 为精简实现，仅发送原始输入或简单拼接文本，**这是效果不一致的根本原因**。
3. **外部文件引用**：Python 对话系提示词不读静态外部文件，但**蒸馏/关系系**通过 `src/skill_support/prompt_payloads.py`、`src/modules/distillation_refinement.py` 读取 `zaomeng-skill/prompts/*.md` 与 `zaomeng-skill/references/*.md`；Ktor 侧无对应读取（Android 端蒸馏在 App 本地执行，不经 Ktor server）。
4. **assets**：提示词 YAML 已**直接拷贝到 `server/src/main/assets/`**（dialogue/chapters/review 三个子目录，8 个文件），server 模块自带 assets 并合并进最终 APK，真机 `context.assets.open("dialogue/director.yaml")` 可直接命中；已移除 app 模块的 `assets.srcDir(rootProject.file("../prompts"))` 编译期注入（避免双份与路径依赖）。**注意**：仓库根 `prompts/` 仍是单一来源（Python Web 端使用），修改提示词后需同步到 `server/src/main/assets/`。

---

## 〇、已实施修复（2026-08-07）

对话系（reply / stream / suggest / associations / director / deepReview）已迁移 Python 完整提示词管道：

| 新增/修改文件 | 内容 |
|---|---|
| `server/.../services/DialoguePromptRules.kt`（新增） | 迁移 `prompts/` 之外的对话 system 规则文本（原 `src/web/chat/prompt_rules.py` 全部 13 个函数） |
| `server/.../services/DialoguePromptBuilder.kt`（新增） | 迁移 `src/web/chat/helpers.py` 的 5 个 `build_*_llm_messages` + 全部 `_compact_*` 辅助（纯函数，输出 `List<ChatMessage>`） |
| `server/.../services/DialoguePayloadBuilder.kt`（新增） | 迁移 `service.py` 的 `_build_turn_payload` 管道 + `speaker_balance.py` + 运行数据读取（persona PROFILE.md、关系 md、world_memory.json、memory_ledger、transcript、turns） |
| `DialogueService.kt` | `replyDialogueTurn` 改用 `buildTurnPayload` + `buildDialogueLlmMessages`（3 消息：stable system + turn system + JSON user） |
| `DialogueStreamService.kt` | 同上 |
| `SuggestionsService.kt` | suggestion 用 `buildSuggestionPayload` + `buildDialogueSuggestionLlmMessages`；associations 用 `buildAssociationPayload` + `buildDialogueAssociationLlmMessages` |
| `DialogueAdvancedService.kt` | `suggestDialogue`/`directDialogue`/`deepReview` 改用对应完整管道（direct 上限对齐 Python 4） |

**已知保留差异（Ktor 无对应数据源，payload 相应字段为空）**：
- `scene_progress` / `character_snapshots` / `event_signals`（Python 会话有 `state` 快照，Ktor 会话无）
- `original_source_context`（Python 对 novel 原文做检索，Ktor 无）
- `knowledge_context`（Python 从 `consistency_monitor.knowledge_ledger` 读，Ktor 无）
- `retrieved_memories`（Python 长期记忆含 embedding/Pinecone 检索，Ktor 无）
- `correctLatest`（Python 走 correction 分支 + `CORRECTION_CONTEXT`，Ktor 无分支机制，保留原实现）

**验证状态**：本机无 Android SDK/JDK，无法本地编译；已做纯静态审查（发现并修复 2 处缺失 `import kotlinx.serialization.decodeFromString`、1 处类型问题、1 处 `compactJson` 加固）。需在用户环境运行 `./gradlew :server:testDebugUnitTest` 与 `assembleDebug` 验证。

## 〇·一、流式修复（2026-08-08）

真机反馈"等模型返回完毕再一次性输出"的根因与修复：

| 现象 | 根因 | 修复 |
|---|---|---|
| App 端一次性收到全部 SSE（等很久后全部同时出现） | App 端 Ktor `Logging` 插件 `LogLevel.BODY` 整体读取响应体（缓冲成 ByteArrayContent），`bodyAsChannel` 失去流式，**整个响应完成后才交给调用方** | `KtorHttpClientProvider.kt`：`LogLevel.BODY`→`LogLevel.HEADERS` |
| "等很久"（消息发出到首字输出的 TTFT 长） | Ktor 请求未携带 reasoning 控制 → DeepSeek 默认开启推理（先思考数秒到十几秒才输出首个 token）；**Python 默认 `reasoning_effort="off"` 会发 `thinking:{"type":"disabled"}` 跳过思考立即输出** | `LlmClient.kt`：默认向 DeepSeek 发 `thinking:{"type":"disabled"}`（`enableReasoning=false`）；用户开启"显示推理"开关时 `DialogueStreamService` 传 `enableReasoning=true`（不携带 thinking，保留推理流式输出） |

> 说明：曾尝试在 `DialogueStreamRoute` 对每个 delta `delay(20)` 做打字机节流，一度用于定位/模拟逐字节奏。**最终移除**：OkHttp 原生流式修复后，App 端即时收到每个事件，用户确认恢复真流式（不节流，DeepSeek 网关批量推送多少就即时显示多少）。

**三层时间戳日志定位过程**（`adb logcat -s LlmClient DialogueStreamRoute ZaomengRepository`）：
- `LlmClient emit`（读 DeepSeek）：29.077→29.183 逐条分散 → 服务端读取流式正常
- `DialogueStreamRoute write`（写 SSE）：28.673→29.183 逐条分散（reasoning 400 条 + content）→ 服务端写出流式正常
- `ZaomengRepository emit`（App 接收）：29.211→29.310 一次性 99ms 内全部 → **App 端缓冲了整条响应**；且 KtorClient 日志 `BODY START...BODY END` 同刻一次性出现，确认 Logging BODY 为根因

修复后预期：默认（不显示推理）消息发出后模型立即输出内容，TTFT 大幅缩短；开启"显示推理"时推理过程流式滚动。

**第三轮（2026-08-08，Logging HEADERS 后 App 端仍一次性/一直等待）**：
- 新日志证据：`DialogueStreamRoute write` 逐条分散（`delay(20)` 节流生效，28ms/条），但 `ZaomengRepository emit` 仍在服务端 `complete` 之后才开始（一次性）——**Logging HEADERS 后 App 端仍缓冲整条 SSE**。此前把根因归为 Logging BODY 是误判（HEADERS 只是必要条件，非充分）。
- **根因**：Ktor client `response.bodyAsChannel()` 在 Android + OkHttp 引擎实测**把整个响应体缓冲到连接关闭才交给调用方**（与 2.x 源码语义不符，疑似 Android 运行时/Okio 调度差异）。服务端 write 时间戳逐条证明数据早已到 socket，App 端却收不到 status。
- **修复**：App 端流式读取改 **OkHttp 原生**——`KtorDialogueClient.streamReply` 改用独立 `OkHttpClient`（connectTimeout 3s / readTimeout 5min / writeTimeout 30s）`execute()` 返回 `okhttp3.Response`；`ZaomengRepository.streamReply` 用 `response.body!!.source().readUtf8Line()`（okio `BufferedSource`，确定逐块流式）逐行解析 SSE，`try/finally` 关闭 Response。`KtorHttpClientProvider` 增加 `bearerToken()` 供原生请求复用同源 token。文件导出（exportRun/exportChapters）仍用 `bodyAsChannel().toInputStream()`——非流式接口，缓冲无妨。
- 与节流的关系：曾用 `delay(20)` 打字机节流（约 50 字/秒）验证 App 是否真流式；OkHttp 原生修复确认后**已移除节流**，恢复真流式（逐事件即时 write+flush，`DialogueStreamRoute` 不再 `delay`）。

**TTFT 第二轮定位（2026-08-08，关闭推理后仍 3.2s）**：
- 用户日志：`12:23:56.307` 连上 DeepSeek(443) → `12:23:59.522` 首个 content token，**3.2s 全在 DeepSeek prefill**；已确认 `thinking disabled` 生效（无 reasoning 直接 content）、App 真流式（`ZaomengRepository emit` 逐条分散）。
- **根因**：`DialoguePayloadBuilder.buildPersonaContexts` 把**完整 PROFILE.md 解析结果**（可达数 KB/人）塞进 payload——Python 用 `_compact_persona_context` 只取 12 个字段并 `_trim_text`（80-100 字符）；`world_facts`/`controlled_memories` 也无 Python 同款数量/长度上限 → prompt 过大 → prefill 慢。
- **修复**（对齐 Python compact）：
  - `compactPersonaProfile`：12 字段（core_identity/story_role/gender/age_stage/appearance_feature(100)/habit_action(80)/speech_style/temperament_type/stress_response/key_bonds/preference_like/dislike_hate）+ preview appearance_feature trim 80（对齐 `helpers.py:267-305`）
  - `loadWorldFacts`：`sortedBy(locked 优先).take(18)`、summary 240、characters ≤12×80、location 100、time_hint 80（对齐 `helpers.py:530-548`）
  - `loadControlledMemories`：`.take(20)`、text ≤500（对齐 `helpers.py:604-613`）
- 所有 payload（suggest/associations/director/consistencyReview）都经 `buildTurnPayload`→`buildPersonaContexts`，自动受益；Python 的 `compact_dialogue_suggestion_payload` 同样走 `_compact_persona_context`，行为一致。
- 预期：prompt 从"完整档案"降到"关键字段"，prefill 显著加速（目标 <1.5s）。

**第四轮（2026-08-08，开启推理时首 token 7 秒 + 设置 off 仍输出推理）**：
- 现象：模型设置 `reasoning_effort` 改为 off + 打开"显示推理"开关 → 仍输出推理（7 秒思考）；首 token 延迟随推理强度升高而变长。
- **根因**：Ktor 迁移遗漏了 `reasoning_effort` 设置传递——`LlmClient` 只按 `enableReasoning`（显示开关）二选一（thinking null / disabled），**完全忽略模型设置的 reasoning_effort**。开启显示开关 → thinking=null（DeepSeek auto，默认大思考预算）→ 首 token 慢；用户设置 off 也无效。
- **修复**（`LlmClient.kt`，对齐 Python `_apply_reasoning_controls`）：
  - `getActiveProfile()` 读入 `reasoning_effort`
  - 新增 `resolveReasoningParams(profile)`：off → DeepSeek v4 直连发 `thinking:{"type":"disabled"}`（其他模型不发）；auto/空 → 都不发；low/medium/high/xhigh → OpenAI 推理模型发原值、DeepSeek v4 发映射值（medium→low，xhigh→max）
  - `ChatCompletionRequest` 新增 `reasoning_effort` 字段（`@SerialName("reasoning_effort")`，`explicitNulls=false` 下 null 不输出）
  - 三个 LLM 方法（chatCompletion / 两个 chatCompletionStream）改由 `resolveReasoningParams` 决定请求参数；`enableReasoning` 参数保留签名但**不再影响请求**，仅透传 gate（DialogueStreamService 的 `onReasoning` 按 `includeModelReasoning` 发 model_reasoning delta）仍在调用方
- 语义说明：模型**是否思考**由设置页"推理强度（reasoning_effort）"决定；聊天页"显示推理"开关只决定**是否展示**思考过程（与 Python 一致）。推理强度与首 token 延迟正相关（off < low < auto < high < xhigh）。

**第五轮（2026-08-08，旁白+群里易触发 "Model reply is not valid JSON" 且无日志）**：
- 现象：开启旁白（场景提示）在多角色会话中回复，容易直接报 "Model reply is not valid JSON."；服务端日志不可见。
- **根因**：
  1. 流式路径（`DialogueStreamService`）解析失败**无重试**——非流式 `DialogueService.replyDialogueTurn` 有 0..1 重试循环（对齐 Python `generate_dialogue_responses`），流式路径迁移时漏掉 → 模型一次输出非 JSON（旁白+多角色场景更容易）就直接 error。
  2. `DialogueStreamRoute` 的 catch 用 `call.application.log`（SLF4J），logcat 默认不可见 → 用户无法排查。
- **修复**：
  - `DialogueStreamService` 流结束解析失败**重试一次**（对齐 Python 0..1 循环）：`retryOnEmpty=true` 重新构建消息 + `maxTokens` 翻倍到 16000（`DialogueService.DIALOGUE_RESPONSE_MAX_MAX_TOKENS` 改 public）；重试为非流式 `chatCompletion`（不重复 emit delta），第二次仍失败才抛错 → SSE error。
  - 解析失败时 `Log.e(TAG, ...)` 打印**模型完整输出前 800 字** + participants/forbidden，重试结果打印前 200 字——直接定位模型输出了什么。
  - `DialogueStreamRoute` catch 增加 `Log.e("DialogueStreamRoute", ...)`（android Log），保证 logcat 可见。

**第五轮补充（2026-08-08，日志显示真实根因：```json[ 同行）**：
- 重试日志暴露模型输出：` ```json[\n[...]\n]\n``` `——**代码围栏与 JSON 起始 `[` 在同一行**（deepseek-v4-flash 的新输出格式）。旧 `stripCodeFences` 用 `strip('`')` + 首个换行丢弃首行，把 `json[` 整行丢掉 → `[` 丢失 → "not valid JSON"。
- **修复**（`DialogueResponseParser.kt`，比 Python 更强的容错，Python 旧 `_strip_code_fence` 同样处理不了该格式）：
  - `stripCodeFences`：语言标签（```/```json）后若紧跟 `[`/`{`，保留剩余部分而非整行丢弃；while 循环处理多层围栏。
  - 新增 `balancedJsonCandidates`（对齐 Python `_balanced_json_candidates`）：括号平衡扫描提取完整 `[...]`/`{...}` 片段（含字符串转义处理、根部截断分支）。
  - `loadLlmJson` 改候选机制：原始文本 → 围栏剥离 → 平衡片段，逐个解析尝试。
- 验证：Python 移植同算法跑 6 个样例（```json[ 同行、```json 换行、无围栏、前后缀噪音、无语言标签、尾部噪音）全部 PASS。
- **提示词增强**（用户确认，2026-08-08）：`buildDialogueLlmMessages` 的 stableSystemParts 在 Python 原有"只返回 JSON 数组，每项必须包含 speaker 和 message。"之后追加一句"不要使用代码围栏或反引号（``` 或 ```json），直接输出原始 JSON，不要加任何解释或前后缀文字。"——针对 deepseek-v4-flash 常把 JSON 包进 markdown 围栏（` ```json[ ` 同行）的行为；**此为 Ktor 相对 Python 的提示词增强**（Python 侧无此句），解析器容错仍兜底。


---

## 〇-2、真机效果问题修复（2026-08-07，用户反馈后）

用户真机反馈 4 个问题，全部定位并修复：

| 问题 | 根因 | 修复 |
|---|---|---|
| ① 创建会话没自动生成场景 | 开场走 `replyDialogueTurn`，其 `parseDialogueResponses` 是简化解析器（正则 `角色名：内容`），LLM 返回 JSON 数组时解析失败 | 新建 `DialogueResponseParser.kt`（对齐 Python `parse_dialogue_responses`：JSON 数组解析、代码围栏剥离、容错定位、speaker 过滤、无有效回复抛错而非 fallback 原文） |
| ② act 模式"林冲回复" | 解析失败后 fallback 把**整个 JSON 原文**归给 `participants[0]`（林冲） | 同上（解析正确后 speaker 来自 LLM 返回；`forbiddenSpeakers` 含受控角色与用户身份，防止自回复） |
| ③ 流式变一次性输出 | `/reply/stream` 路由误接**非流式** `DialogueService`（KtorBackendController 传 `services.dialogue`），只发 `status`+`complete`，无 `delta` 增量；且 `DialogueStreamService` 内残留永远返回 null 的 `StorageService.getApiKey()` 死调用 | 路由改接 `DialogueStreamService`（真正流式）；`replyDialogueTurnStream` 流式结束后从 `parser.fullContent()` 解析 responses 并 `dialogue.commitTurn()` 保存；移除 `getApiKey` 死调用（密钥由 `LlmClient` 内部解析）；输出 App 契约的 `status`/`delta`/`complete`/`error` SSE 事件 |
| ④ 显示原始 JSON | 同 ②（fallback 原文进 transcript） | 同 ① |

**涉及文件**：`DialogueResponseParser.kt`（新增）、`DialogueService.kt`（parse 接入 + `commitTurn`）、`DialogueStreamParser.kt`（`fullContent()`）、`DialogueStreamService.kt`（重构）、`DialogueStreamRoute.kt`（重构）、`KtorBackendController.kt`/`KtorServiceGraph.kt`（接线）。

---

## 一、对话回复（reply / stream reply）

### Python 实现
- 入口：`build_dialogue_llm_messages`（src/web/chat/helpers.py:688-942）
- 消息结构：**3 条消息**
  1. system(stable)：host_prompt_brief + generation_goal + mode_rule + speaker_rule + response_style + scene_rule + output_rule + 固定 JSON 输出要求 + 读心规则（来自 `prompts/dialogue/inner_thought_rule.yaml`）+ `STATIC_CHARACTER_CONTEXT` JSON（mode/participants/scene_card/persona_contexts）
  2. system(turn)：progression_rule + plot_progression_contract + response_count_rule + group_chat_rule + mention_rule + temporary_npc_rule，条件追加 KNOWLEDGE_BOUNDARY / ORIGINAL_SOURCE_CONTEXT / CONTROLLED_MEMORIES / WORLD_FACTS / CORRECTION_CONTEXT / retry 规则
  3. user：JSON payload（helpers.py:894-928）
     - mode / message_kind / speaker / message / participants / active_participants / mention_targets
     - memory_context（controlled_memories、world_facts、event_signals 最近 3 条等）
     - knowledge_boundary（每条 fact 限 holders 知晓）
     - original_source_context（原作动态检索证据，最多 3 条，含 visibility/allowed_characters）
     - correction_context / response_limit / active_persona_state（最多 6 人）
     - speaker_plan / responder_hints / speaker_activity
     - history（最近 6 轮）/ relation_excerpt（截断 1200）/ expected_output / retry_on_empty
- payload 组装：`_build_turn_payload`（src/web/chat/service.py:2562-2878），运行数据来源：
  - session.json、turns/*.payload.json
  - 人物档案（persona_bundle.py:420-453 读 PROFILE.md / *.generated.md）
  - 关系文件（relation_excerpt.py:22）
  - world_memory.json（world_memory.py:57）
  - 小说原文 → original_knowledge.json 索引（original_knowledge.py:75/121-125）
  - 长期记忆 `{session_id}_memory.md`（session_store.py:258-265）

### Ktor 实现
- `DialogueService.replyDialogueTurn`（DialogueService.kt:79-153）：system = `getDialogueDirectorPrompt`（director YAML），user = **原始 message**。无历史、无 memory/persona/relation 上下文。
- `DialogueStreamService.replyDialogueTurnStream`（DialogueStreamService.kt:80 附近）：同上。

### 差异
| 维度 | Python | Ktor |
|---|---|---|
| 消息数 | 3（stable system + turn system + JSON user） | 2（director system + 原始 message） |
| system 模板 | 对话专用多段规则（prompt_rules.py 生成） | 误用 director 模板 |
| 历史 | 最近 6 轮 | 无 |
| memory | controlled_memories / world_facts / event_signals | 无 |
| persona | stable_persona_contexts + active_persona_state | 无 |
| relation | relations_excerpt（截断 1200） | 无 |
| scene_card | 完整传入 | 无 |
| 读心规则 | include_inner_thoughts 时追加 | 无 |

---

## 二、续写建议（suggest / suggestions stream）

### Python
- `build_dialogue_suggestion_llm_messages`（helpers.py:945-1058）
- system：suggestions YAML + **13 条硬编码规则**（helpers.py:974-997，含 user_persona 优先、mode=insert/act/observe 分支、scene_progress 转场、锚点、offstage 规则、输出格式约束）
- user payload：mode / speaker / seed_text / selected_direction / scene_card / scene_progress / memory_context / user_persona / participants / persona_contexts / history / relation_excerpt / response_shape / good_examples（act_or_insert + observe 示例）/ bad_examples（6 条）/ retry_on_empty
- payload：`build_suggestion_payload`（service.py:2056）

### Ktor
- `SuggestionsService.generateSuggestionStream`（SuggestionsService.kt:34-87）与 `DialogueAdvancedService.suggestDialogue`（:330-356）
- system：suggestions YAML（无 13 条规则）
- user：`{"mode","participants","seed_text","selected_direction"}` 或「对话记录 + 草稿 + 方向」文本

### 差异
system 缺 13 条硬编码规则；user 缺 scene_card/scene_progress/memory/user_persona/persona_contexts/history/relation_excerpt/examples。

---

## 三、剧情联想选项（associations）

### Python
- `build_dialogue_association_llm_messages`（helpers.py:1061-1153）
- system：suggestions YAML（option_count 参数化 + retry）
- user payload：mode / speaker / latest_exchange / participants / recent_completed_history / scene_card / scene_progress / memory_anchors / user_persona / persona_contexts（前 4 人）/ relation_excerpt（截断 800）/ response_shape / option_count / retry_on_empty
- 辅助 compact 函数：`_compact_association_history/scene_card/scene_progress/memory_context/user_persona/persona_context`

### Ktor
- `SuggestionsService.generateAssociations`（:97-153）
- user：`{"mode","participants","option_count"}`

### 差异
user 缺 latest_exchange/history/scene_card/scene_progress/memory_anchors/user_persona/persona_contexts/relation_excerpt/response_shape。

---

## 四、剧情导演（director）

### Python
- `build_dialogue_director_llm_messages`（helpers.py:1156-1182）
- system：director YAML（option_count 2-4 + retry）
- user payload：director_goal / director_action / mode / participants / active_participants / scene_card / scene_progress / latest_exchange / memory_context / relation_excerpt（截断 1200）/ speaker_activity / option_count
- payload：`build_director_payload`（service.py:2159）

### Ktor
- `DialogueAdvancedService.directDialogue`（:505-537）
- user：「对话记录 + 导演目标 + 动作」文本，option_count 允许 2-6（Python 上限 4）

### 差异
user 缺 scene_card/scene_progress/latest_exchange/memory/relation/speaker_activity；option_count 范围不一致（2-6 vs 2-4）。

---

## 五、深度一致性审校（deep review）

### Python
- `build_dialogue_consistency_review_messages`（helpers.py:1595-1617）
- system：consistency_review YAML
- user payload：mode / participants / scene_progress / persona_contexts / relation_context / knowledge_context / history（最近 8 轮）/ input / responses / deterministic_report
- 结果解析：`parse_dialogue_consistency_review`（helpers.py:1620-1678，严格校验 code/speaker/severity/evidence 在回复原文中）

### Ktor
- `DialogueAdvancedService.deepReview`（:468-499）
- user：「最近对话文本 + JSON 输出说明」

### 差异
user 缺场景/人物/关系/知识上下文与 deterministic_report；结果校验宽松（直接解析 JSON）。

---

## 六、修正最新回复（correct latest）

### Python
- 无独立提示词构建，走对话回复链路 + `CORRECTION_CONTEXT`（turn system 追加段，helpers.py:875-880）

### Ktor
- `DialogueAdvancedService.correctLatest`（:391-431）：system = director(1, retry)，user = 前文 + 重写要求。未使用 CORRECTION_CONTEXT 语义。

---

## 七、章节改写 / 转换（rewrite / convert）

### Python
- system：`_NOVEL_REWRITE_SYSTEM_PROMPT`（chapters.py:21 = `get_novel_rewrite_prompt()` ← novel_rewrite.yaml）
- user：`json.dumps(llm_input)`（chapters.py:358-368），llm_input 字段待核对

### Ktor
- `ChapterService.rewrite`（:31）：system 一致；user 为「请改写以下章节…+改写要求/上下文/原文」文本
- `ChapterManagementService.convert`（:310）：system 一致；user 为 JSON llmInput

### 差异
需逐字段核对 llm_input 结构（本轮未展开；建议单独对比 chapters.py:281-370 与 ChapterManagementService.convert）。

---

## 八、场景卡 / 角色卡生成

### Python
- system：scene_card_generation / self_card_generation YAML（与 Ktor 一致）
- user：`build_random_scene_card_messages`（scene_cards.py:179）/ `build_random_self_card_messages`（self_cards.py:220）内联字段清单

### Ktor
- `CardsService`（:14-20）：system 一致；user 为简短硬编码指令（「请生成一个新的原创场景卡。」），**缺字段清单**

### 差异
user 缺字段结构清单（如 scene_cards.py:180/201 的字段说明），可能影响输出结构一致性。

---

## 九、人物资料字段补全（suggest-field）

### Python
- `build_persona_field_completion_messages`（persona_completion.py:162）+ `build_persona_field_retry_messages`（:227）
- system：`get_persona_completion_prompt(mode)`，支持 knowledge_based / web_based / simple 三种
- user：人物名 + 目标字段 + **当前已知人物档案（读 profile source 文件）** + （web_based 时）**联网检索摘录**（`_fetch_text` Bing 搜索）

### Ktor
- `PersonaService.suggestField`（:107）：system = knowledge_based；user = 人物 + 目标字段 + 请求参数 currentFields。**无 web_based/simple 模式**，无检索摘录。

### 差异
mode 仅支持 knowledge_based；无联网检索（Web 端独有，Android 可接受）；当前档案来自请求参数而非文件。

---

## 十、蒸馏 / 关系（distillation / relations）

### Python
- 提示词来自 `zaomeng-skill/prompts/distill_prompt.md`、`relation_prompt.md`、`correction_prompt.md` 与 `zaomeng-skill/references/output_schema.md`、`style_differ.md`、`logic_constraint.md`、`validation_policy.md`（经 `src/skill_support/prompt_payloads.py:54`、`src/modules/distillation_refinement.py:36` 读入）
- 构建：`src/web/prompts/builders.py` / `composition.py` / `fragments.py`

### Ktor
- Android 端蒸馏由 App 本地执行（`DistillationForegroundService` 仅监控通知；人物蒸馏实际 LLM 调用不在 Ktor server）。Ktor `RunOperationsService.redistill` 仅切换 run status。
- 无 distillation YAML（prompts/README.md 亦标注「待添加」）。

### 差异
Android 端蒸馏不经过 Ktor server，无提示词差异风险；但若未来 Ktor 承担蒸馏，需补齐 zaomeng-skill md 引用与 builders.py 逻辑。

---

## 十一、assets 打包现状

| 项 | 状态 |
|---|---|
| `:server` assets | ✅ `server/src/main/assets/` 已创建，直接拷贝 8 个提示词 YAML（dialogue/ 4、chapters/ 1、review/ 3），server 模块自带 assets，AGP 默认源集自动识别并合并进最终 APK |
| `:app` assets 源集 | ✅ 已移除 `assets.srcDir(rootProject.file("../prompts"))`（app/build.gradle.kts），改为注释说明；app 无物理 assets 目录 |
| PromptLoader 读取 | assets 优先：`context.assets.open("dialogue/director.yaml")` → 命中 server 的 assets；文件系统回退路径保留（开发机用，真机基本不会走到） |
| 单一来源 | 仓库根 `prompts/`（Python Web 端与 Android 共用）；**修改提示词后需同步到 `server/src/main/assets/`**（当前两份内容一致，可用 `diff -r` 校验） |
| 风险 | 若未来提示词频繁改动，可考虑加 Gradle 同步任务（构建期从 `../prompts` 拷贝到 `server/src/main/assets/`）替代手动同步 |

---

## 十二、修复优先级建议

1. **P0（效果影响最大）**：对话回复/流式回复 —— 迁移 `build_dialogue_llm_messages` 的 3 消息结构与 user payload（memory/history/persona/relation 上下文），并停止误用 director 模板。
2. **P0**：续写建议/联想 —— 补齐 13 条规则 + user payload。
3. **P1**：导演/审校/修正 —— user payload 对齐。
4. **P1**：卡片生成 —— 补齐字段清单。
5. **P2**：人物补全 web_based/simple 模式。
6. **P2**：assets 双保险（server 模块 assets 或构建验证）。

## 十三、内置插件系统重建（2026-08-08，Kotlin 模块化）

- **背景**：main 分支的 6 个内置插件是 Python 实现（`src/builtin_plugins/*/main.py`），Ktor（JVM/Android）无法运行 → 迁移后插件列表空、动作端点抛"需要 Python 运行时"。
- **方案**（用户确认：三层架构 + 全部 6 个）：
  - `:plugins-api`（新模块，`top.wkbin.zaomeng.plugins.api`）——只定义接口：`Plugin` / `PluginHost`（invokeSuggestion / invokeVariants / invokeNpc / log）、贡献点描述（chatActions / generationEnhancers / temporaryNpcGenerators）、Manifest、请求结果模型；server 面向接口编程。
  - `:builtin-plugins`（新模块）——6 个内置插件 Kotlin 实现（迁移自 Python，含 manifest / settings / contributes / direction 文案）+ `BuiltinPlugins` 注册表。
  - `:server` 依赖两模块；`PluginService` 内置注册表（source=official，内置不可卸载，config/日志存 `plugins/.builtin/<id>/`）；`PluginHostImpl` 绑定现有服务（suggest→`DialogueAdvancedService.suggestDialogue`、variants→`SuggestionsService.generateAssociations`、npc→LLM 生成+写入会话 `temporary_npcs`+transcript）；`PluginOperationsService` 分发动作/NPC（suspend）；inner-thoughts 增强器打通（读 session `plugin_enhancer_states` → `includeInnerThoughts`）。
- **App 端**：无需改动（PluginsScreen/ChatViewModel 已支持 source=official 与三类贡献点）；共享 DTO 补 `notice` / `npc` 字段。
- **修复记录**：内置插件 id 为点分格式（com.zaomeng.*）绕过 `STORAGE_ID_PATTERN` 校验（否则 `/api/web/plugins` 500）；`appendLog` 用 compactJson（prettyPrint 写多行 jsonl 导致 `logs()` 恒空）。
- **验证**：Kotlin 2.2.10 真实编译 0 error（738 classes）；JVM 实测 list/setEnabled/getConfig/appendLog 对点分内置 id 全走通。

## 十四、蒸馏执行迁移（2026-08-08，P1-P2 最小闭环）

- **背景**：Ktor 迁移后蒸馏只切状态不执行（redistill/resume 写死 stage=starting、current_character=""/completed_count=0），无 LLM 蒸馏执行者 → progress 无人更新 → App 显示回落 fallback 文案（通知"正在整理人物与关系"、详情页"逐步浮现"），缺少 Python 版的"正在蒸馏 X（第 i/N 人）"。
- **修复**（用户确认迁移蒸馏执行到 Ktor）：
  - 新增 `DistillExecutor.kt`（server）：`start(runId, characters)` 幂等启动协程任务；逐角色串行调 `llm.chatCompletion`（蒸馏 system prompt 产出 12 字段 YAML，对齐 PersonaService 读取）；逐角色更新 progress（`正在蒸馏 X` / `X 蒸馏完成` / current_character / completed_count / completed_characters）；落盘 `artifacts/characters/<novel_id>/<name>/PROFILE.md`（`--- yaml.dump ---` frontmatter，refreshArtifactIndex 自动识别）；finalize 置 status=ready/stopped/failed + summary；支持 control.stop_requested 停止。
  - `RunOperationsService.redistill` 切状态后调 `distillExecutor.start(...)`（resume 走 redistill 自动生效）；`KtorServiceGraph` 装配。
  - 显示对齐：progress 更新后 App 端通知（currentCharacter→"正在处理 X"）、书架（progress.message→"正在蒸馏 X"）自动恢复 Python 版体验。
- **本轮范围（P1-P2）**：单角色非分块蒸馏（≤12k 字符节选采样）；关系图/分块并行/增量重蒸馏（P3-P5）延后。
- **验证**：Kotlin 2.2.10 真实编译 0 error（60 文件）。

## 十五、App 行为对齐补全（2026-08-08，P3-P5 + 对话状态）

- **蒸馏 P3-P5**（`DistillExecutor.kt` 重写）：角色聚焦节选（前/中/后段证据分桶，迁移 `novel_preparation.py`）、
  长文分块蒸馏（并行分块草稿 + LLM 汇总合并，迁移 `chunking.py/chunk_execution.py/generation.py`）、
  增量蒸馏（已有 PROFILE 时 update_mode=incremental）、人物关系图谱（单遍/分块抽取 + 落盘
  `artifacts/relations/*.relations.md` + manifest `artifact_index.relation_graph`/`has_relation_graph`）、
  progress 文案对齐（"正在分批蒸馏 X（i/N）并行 N 线程"、"正在汇总 X 的分批草稿"、"正在生成人物关系图谱"等）、
  manifest 字段对齐（events/capabilities/quality/summary/timing）、resume 跳过已完成。
  提示词 md 已打包进 `server/src/main/assets/distill/`（单一来源 `zaomeng-skill/`，修改后需同步）。
- **对话场景状态**（新增 `SceneProgressState.kt`）：迁移 `scene_signals.py/event_signals.py/scene_progress.py`
  启发式状态机（在场/离场推断、时间提示、场景成熟度、转场压力、world tension），每轮提交后写入 session
  `state` 并接入对话 payload 的 scene_progress/event_signals/character_snapshots 与
  progression_rule/plot_progression_contract；场景卡切换写入 scene_transition/time_change/atmosphere_shift 事件。
- **correct-latest**：改走完整 reply 管道并注入 CORRECTION_CONTEXT（从 consistency_monitor.latest.issues 取），
  在当会话内替换最后一轮回复并重推 scene progress。
- **卡片生成**：user prompt 补齐 Python 的字段清单与要求（scene/self 卡）。
- **章节转换**：context_summary = summary or body[:180]（对齐 Python）。
- **redistill 前置校验**：未配置模型返回 400（对齐 Python restart_run_distill）。
- **Windows 原子写入**：`writeTextAtomically` 改 `Files.move(REPLACE_EXISTING)`（原 `File.renameTo`
  在 Windows 无法覆盖已存在文件，导致集成测试 10 例失败；已修复）。
- **refresh 保留关系图**：`refreshArtifactIndex` 不再抹掉 `artifact_index.relation_graph`。
- **验证**：`:server:compileDebugKotlin`、`:app:compileDebugKotlin` 通过；KtorApiIntegrationTest 29/29 全绿。
- **仍保留差异（App 可接受/无数据源）**：original_source_context / knowledge_context / retrieved_memories
  在 Android 上本就无对应数据源（Python 侧亦为空）；蒸馏 repair/completion 二次修复、关系图 mermaid/html
  导出（WebUI 用）未迁移；第三方 Python 插件执行仍返回明确错误。

## 十六、提示词去硬编码（2026-08-08）

将 Ktor 侧残留的硬编码提示词文本提取到 `prompts/` YAML（单一来源，已同步 `server/src/main/assets/`）：

| YAML | 内容 | 原位置 |
|---|---|---|
| `dialogue/turn_system.yaml` | 回复路径 stable/turn 文本块（KNOWLEDGE_BOUNDARY/ORIGINAL_SOURCE/CORRECTION 等）、续写建议 20 条规则、联想选项默认形状 | `DialoguePromptBuilder.kt`（源自 Python helpers.py） |
| `review/card_instructions.yaml` | 场景卡/自我角色卡生成 user 指令（`{field_lines}` 由代码填充） | `CardsService.kt`（源自 review/scene_cards.py、self_cards.py） |
| `review/persona_suggest.yaml` | 人物字段补全 user 模板 | `PersonaService.suggestField` |
| `chapters/ask.yaml` | 问书卷 user 模板 | `ChapterManagementService.ask` |
| `chapters/rewrite_user.yaml` | 章节改写 user 模板 | `ChapterService.rewrite` |
| `distill/guidance.yaml` | 蒸馏/关系 guidance 文本块（CHUNK_MODE/PRIORITY_GUIDANCE/DIALOGUE_STYLE/EVIDENCE_STAGES/合并引导） | `DistillPromptBuilder.kt`（源自 builders.py） |

`PromptLoader` 新增 `getPromptText`/`getCardInstruction`/`getPersonaSuggestFieldTemplate`/
`getAskBookTemplate`/`getChapterRewriteUserTemplate`/`getDistillGuidance`/`loadRawPrompt`
（assets 优先 + 文件系统/zaomeng-skill 回退）。

**distill md 是否转 YAML 的判断**：不转。`distill_prompt.md`/`relation_prompt.md` 与
`references/*.md` 是完整技能文档、无参数化，Python 直接按文件读取；转 YAML 只是包一层字符串，
还破坏与 `zaomeng-skill/` 的直接对应。整篇文档保持 md（经 `loadRawPrompt` 统一读取），
可参数化的 guidance 片段转 YAML。

**保留在代码中的提示词（与 Python 一致，逻辑+文本混合）**：`DialoguePromptRules.kt`
的 13 个规则函数（条件分支组装文本，如 modeRule/responseStyleRule/hostPromptBrief）、
`DialoguePayloadBuilder.kt` 的 instruction 组装（generation_goal/response_count_rule/output_rule）。
如需一并抽到 YAML 模板，可后续用占位符渲染方式迁移。

**验证**：`:server:compileDebugKotlin`、`:app:compileDebugKotlin` 通过；KtorApiIntegrationTest 29/29 全绿。

## 十七、真机蒸馏卡在 fallback 的修复（2026-08-08）

现象：新建书卷后进度一直停在"人物和关系正在这台手机上逐步浮现"、可用人物一直
"正在等待第一位人物完成"，没有"正在蒸馏 X"等进度。

根因（两处）：
1. `RunManagementService.createRun` 只把 status 置为 running、progress.stage=starting，
   **从不启动蒸馏执行器**（Python create_run(auto_run=True) 会 `_start_background_run`）；
2. `DistillExecutor` 只写 `artifacts.character_dirs`，**从未刷新 `artifact_index.characters`**，
   导致 App 的可用人物列表（读 artifact_index）永远为空。

修复：
- `RunManagementService` 注入 `DistillExecutor`：非 defer 创建先校验模型已配置（对齐 Python
  create_run），`autoRun && !deferRun` 时写入 manifest 后调用 `distillExecutor.start(...)`；
  manifest 补写 `locked_characters`。
- `DistillExecutor` 新增 `refreshArtifactIndex`（扫描 artifacts/characters，保留 relation_graph），
  每完成一名角色和 finalize 时刷新 `artifact_index.characters`。
- `KtorServiceGraph` 先建 DistillExecutor 再注入 RunManagementService / RunOperationsService。

验证：`server`/`app` 编译通过；KtorApiIntegrationTest 29/29 全绿。
