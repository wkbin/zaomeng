# Python to Ktor 迁移进度报告

**更新时间**: 2026-08-07  
**当前分支**: `migrate-python-to-ktor`  
**状态**: Ktor 运行时已完成切换，Retrofit 兼容桥已移除

---

## 📊 总体进度

```
Phase 0: 准备阶段              ████████████████████ 100% ✅
Phase 1: 核心基础设施          ████████████████████ 100% ✅
Phase 2: 只读 API              ████████████████████ 100% ✅
Phase 3: LLM 集成              ████████████████████ 100% ✅
Phase 4: 写入 API              ████████████████████ 100% ✅
Phase 5: 流式响应              ████████████████████ 100% ✅
Phase 6: 高级功能              ████████████████████ 100% ✅
Phase 7: 性能优化              ██████████████░░░░░░  70% 🚧
Phase 8: WebUI 适配            ██████████████░░░░░░  70% 🚧
Phase 9: 清理和最终化          ░░░░░░░░░░░░░░░░░░░░   0%

总进度: ██████████████████░░  90%
```

**提示词效果对齐（2026-08-07 追加）**：系统对比 Python 与 Ktor 提示词构建后完成对话系全量迁移——新增 `server/.../services/DialoguePromptRules.kt`（迁移 `prompt_rules.py` 规则文本）、`DialoguePromptBuilder.kt`（迁移 `helpers.py` 的 5 个 `build_*_llm_messages` 与 `_compact_*` 辅助）、`DialoguePayloadBuilder.kt`（迁移 `service.py` 的 `_build_turn_payload` 管道 + `speaker_balance.py` + persona/关系/world_memory/memory_ledger 数据读取）；`DialogueService`/`DialogueStreamService`/`SuggestionsService`/`DialogueAdvancedService` 四个服务接入。reply/stream 恢复 3 消息结构（stable system + turn system + JSON user payload），建议/联想/导演/审校补齐 Python 同构 payload。差异清单与保留差异见 [PROMPT_DIFF_ANALYSIS.md](./PROMPT_DIFF_ANALYSIS.md)。**本机无 Android SDK 未能编译，需在构建环境运行 `./gradlew :server:testDebugUnitTest` 与 `assembleDebug` 验证**。

**真机效果修复（2026-08-07 追加）**：真机反馈 4 个问题全部定位修复——①创建会话无自动场景、②act 模式受控角色错误回复、③流式变一次性输出、④显示原始 JSON。根因：`DialogueService.parseDialogueResponses` 是简化解析器（LLM 返回 JSON 数组时 fallback 原文）；`/reply/stream` 路由误接非流式 `DialogueService`。修复：新增 `DialogueResponseParser.kt`（对齐 Python JSON 数组解析）、`DialogueStreamService` 重构为真流式（流结束 `commitTurn` 保存、移除 `getApiKey` 死调用）、`DialogueStreamRoute` 输出 `status/delta/complete/error` SSE、transcript role 对齐 Python（旁白/场景提示 → scene/director）。

**SSE 容错修复（2026-08-07 追加）**：真机反馈"旁白和普通对话都发不出去"——DeepSeek 等 reasoning 模型流式 chunk 的 `delta.content` 为 null（内容在 `reasoning_content`），`LlmClient` 的 `ChatMessage.content: String` 非空声明导致 `JsonDecodingException`、整 chunk 被跳过（流式无输出）。修复：`ChatMessage.content` 改 `String? = null`，流式解析（callback 与 Flow 两处）用局部变量 `deltaContent != null && isNotBlank()` smart-cast 后 append/emit。场景提示/旁白的展示样式（流式 role=scene、transcript role=scene/director、App 端 `TranscriptBubble` 旁白分支）已确认对齐 Python，无需改动。

**max_tokens 对齐与空回复重试（2026-08-07 追加）**：真机反馈创建场景失败 `"Model returned an empty reply."`——根因：Ktor `replyDialogueTurn`/`DialogueStreamService` 固定 `maxTokens=2000`，而 Python 明确（helpers.py:20-24）推理模型把 `reasoning_content` 计入输出预算、对话回复默认至少 **8192**（上限 16000），DeepSeek 在 2000 预算内耗尽 → content 为空。修复：`DialogueService.companion.resolveDialogueMaxTokens(responseLimitHint)` 对齐 Python `_resolve_dialogue_max_tokens`（默认 8192，按 response_limit 估算）；`replyDialogueTurn` 增加**解析失败重试一次**（maxTokens 翻倍 + `retryOnEmpty=true` 提示词，对齐 Python `generate_dialogue_responses`）；`DialogueStreamService` 流式同样用 8192 预算。其余接口（suggestion 512/association 768/director ≤900/deepReview ≤900）Ktor 已 ≥ Python，无需改。

**流式逐 token 输出修复（2026-08-08 追加）**：真机反馈"不是流式输出，像一下子出来"。根因：Ktor `LlmClient` 两处流式解析用 `response.bodyAsText().lineSequence()`——`bodyAsText()` **一次性读完整流**再逐行处理，LLM 全部事件瞬间到达；而 Python（`src/core/llm_client.py:1502`）用 `requests.post(stream=True)` + `iter_lines(chunk_size=1)` **逐字节即时消费**（注释明确："consume SSE/NDJSON as soon as bytes arrive"）。修复：`LlmClient.chatCompletionStream`/`chatCompletionStream`（callback 与 Flow 两处）改为 `response.bodyAsChannel().toInputStream().bufferedReader().use { readLine() }` 逐行即时处理（`BufferedReader.readLine` 读到 `\n` 即返回，不等缓冲满），并补 `import io.ktor.utils.io.jvm.javaio.toInputStream`。错误处理中的 `bodyAsText()` 保留（错误响应小，一次性读合理）。

**模型推理过程透传（2026-08-08 追加）**：真机反馈"打开模型推理但没显示推理过程"。Python（dialogue.py:772-967）经 `on_reasoning` 回调把 `reasoning_content` 转成 SSE `delta`（role=reasoning, field=model_reasoning）并显示推理开始 `status`；Ktor 缺失该链路。修复：①`LlmClient` 流式解析改用独立 DTO（`StreamChunk`/`StreamChoice`/`StreamDelta`，含 `reasoning_content`，彻底隔离流式与非流式）；②两个 `chatCompletionStream` 增加 `onReasoning` 回调（Flow 版为 `suspend (String) -> Unit?`）；③`DialogueStreamService` 在 `onReasoning` 回调中按 `includeModelReasoning` 开关把推理增量 emit 为 `StreamEvent(role="reasoning", field="model_reasoning")`；④`DialogueStreamRoute` 透传 `request.includeModelReasoning`。App 端（ChatViewModel:752）已支持 `field=model_reasoning` → ModelReasoningBlock 显示，无需改动。

最近完成（2026-08-07）：修复约 58 个 404 端点——Ktor server 补齐对话高级功能（15）、章节管理（11）、卡片 CRUD（16）、人物关系（2）、运行/蒸馏（9）、插件包（5）全部端点；KtorApiIntegrationTest 26/26 全绿；prompts 提示词目录打包进 assets 供真机 LLM 使用；用真实 DeepSeek 模型验证 director/suggest/reply 链路并修复 LLM JSON 输出解析。

同日追加修复：
- **角色头像链路**：Ktor 响应实时注入 `character_avatars`（会话创建/获取/更新/流式 complete）与 `avatar_version`（run 详情/刷新，对齐 Python `_serialize_session`/`_serialize_manifest`）；头像文件 sha256 文件名与 mtime-大小版本语义与 Python 一致；导入/导出书卷包均保留 avatars/ 目录。
- **人物资料字段**：`PersonaService.loadProfile` 支持 Markdown 档案（`- key: value` 列表，对齐 Python `parse_persona_markdown`），修复书卷档案字段（核心身份/故事位置/身份锚点/气质底色等）不显示问题。
- **JSON 反序列化兜底**：`DialogueSessionDto` 补齐 `scene_profile`/`self_profile`/`turns`/`turn_count`/`current_turn_id` 字段；新增严格模式回归测试；设置页新增「版本信息」行（v1.5.0(7)·构建时间）便于确认 APK 新旧。
- **安全加固**：修复 `DialogueService.saveTurn` 与 `PersonaService.resolveProfile` 的路径穿越；统一头像 sha256 为 UTF-8 编码；`readRunManifest` 解析容错；review + security-review 双轮审查通过。
- 最终验证：`lintDebug` 无 error；全量测试 118 tests 仅 1 个既有无关失败（`ModelReasoningCapabilitiesTest` 模型目录断言）；`assembleDebug` 构建成功。
- **Retrofit 已完全移除**：`LocalApiFactory`/`ZaomengApi`/`requireApi()` 全部删除；流式回复 `streamReply`、两个前台服务（蒸馏/章节转换）、Repository 全部 API 调用均已迁移到对应 Ktor 客户端；`retrofit2` 依赖与 `AppModule` 装配已清理；`KtorCardsClient`/`KtorRelationsClient`/`KtorRunOpsClient`/`KtorWorldMemoryClient` 补齐关系、运行操作、world memory 等方法。

次日追加修复（真机日志定位）：
- **服务端 manifest 解析根因**：`DialogueService`/`DialogueStreamService` 解析 `session_manifest.json` 时私有数据类缺 `run_id` 字段且用严格 `Json` → 回复/流式直接抛 `unknown key 'run_id'`。修复：manifest 数据类补 `@SerialName("run_id")`，解析改用 `Json { ignoreUnknownKeys = true }`（真机 logcat `KtorClient` 拦截器定位到错误实际发生在服务端）。
- **创建会话自动开场（对齐 Python）**：新增 `SessionManagementService.openDialogueSession()`——创建会话后按 Python `build_scene_opening_message` 语义构造开场消息（act/insert/observe 三分支，含场景卡 title/location/atmosphere/开场局面/推进方向）并调用 LLM 生成场景与第一轮对白写入 transcript；开场失败删除会话（与 Python `create_dialogue_session_payload` 一致）。App 端「自动发消息」方案已回滚，服务端 `POST /dialogue/sessions` 走 `openDialogueSession`。
- **安全加固（security-review）**：manifest 解析去掉 `isLenient`（仅保留 `ignoreUnknownKeys`）；`buildOpeningMessage` 参与者/场景/自我字段加长度截断（take(500)/take(200)）缓解 LLM 指令注入面；`DialogueService` llm/prompt 改可空（测试可注入，章节续写内部创建不受影响）。
- 最终验证：`:server:testDebugUnitTest` ✅；全量测试 119 tests 仅 1 个既有无关失败（`ModelReasoningCapabilitiesTest`）；`assembleDebug` 构建成功。

客户端迁移起点：已加入共享 `KtorHttpClientProvider`；Retrofit 暂时保留，后续按 API 分组逐步替换。

已迁移调用：Ktor 后端启动健康检查现在直接使用 Ktor Client，不再经过 Retrofit。
Ktor 已成为唯一 Android 后端，Chaquopy、Python 控制器和 Python 前台服务依赖已移除；Retrofit 仅暂作未迁移 API 的兼容桥。
服务端代码已迁移到独立 `:server` 模块；App 模块只负责 Ktor 服务生命周期、客户端和 UI。
运行清单已改为无损 JSON 存储，导入书卷包后可直接进入详情，列表、详情和停止操作不再截断复杂清单字段。
服务端已启用请求日志和未捕获异常日志，记录方法、URI、状态码及异常堆栈。
本阶段新增：模型设置、模型连接测试及 profile 激活/删除改用 Ktor Client；其余 API 继续通过 Retrofit，确保迁移可回退。
插件列表、刷新、启停及配置更新也已接入 Ktor Client。
插件卸载和日志读取也已接入 Ktor Client，插件管理 API 组迁移完成。
运行列表只读接口已接入 Ktor Client。
运行详情、停止和删除接口已接入 Ktor Client。
会话列表（按运行）、创建、详情和标题更新接口已接入 Ktor Client；近期会话列表仍保留 Retrofit，等待对应 Ktor 路由。
创建运行接口已接入 Ktor Client。
章节列表只读接口已接入 Ktor Client。
诊断报告导出已接入 Ktor Client，并保留 Retrofit 流式回退。
场景卡和自我角色卡生成已接入 Ktor Client。
人物资料字段建议已接入 Ktor Client。
非流式对话回复已统一为返回更新后的会话清单，并接入 Ktor Client。
```

**预计时间**:
- ✅ 已完成: 2 天
- 🔄 进行中: 0 天
- ⏳ 剩余: ~43-61 天（约 2-3 个月）

---

## ✅ 已完成阶段

### Phase 0: 准备阶段 (2026-08-06 完成)

**工作内容**:
1. ✅ 提取 8 个硬编码提示词到 YAML 配置
2. ✅ 添加 Ktor 3.5.2 依赖
3. ✅ 创建 Ktor 模块基础结构

**成果**:
- 📁 8 个 YAML 提示词配置文件
- 📦 9 个 Ktor 依赖包
- 🏗️ 完整的包结构（routes/, services/, models/）
- 🔧 Python 加载器 (prompts/loader.py)

**详细文档**: [PHASE_0_SUMMARY.md](./PHASE_0_SUMMARY.md)

### Phase 1: 核心基础设施 (2026-08-06 完成)

**工作内容**:
1. ✅ Ktor 服务器启动逻辑
2. ✅ 健康检查端点 (`/api/web/health`)
3. ✅ Bearer token 认证中间件
4. ✅ Python/Ktor 运行时切换机制

**成果**:
- 🚀 `KtorBackendController` (完整实现)
- 🏥 健康检查端点（JSON 响应）
- 🔐 认证插件 (`Security.kt`)
- 🔀 `BackendManager`（支持后端切换）
- ⚙️ `BuildConfig.USE_KTOR_BACKEND` 开关

**详细文档**: [PHASE_1_SUMMARY.md](./PHASE_1_SUMMARY.md)

---

## 📈 代码统计

### 提交历史
```
8c03ad0 docs: add Phase 1 completion summary
87bf25b docs: mark Phase 1 as completed in migration plan
4cbf651 feat(ktor): implement Phase 1 core infrastructure
e3cb597 docs: add Phase 0 completion summary
9ffaa6e feat(ktor): add Ktor dependencies and basic infrastructure
3103fe5 feat(prompts): extract hardcoded prompts to YAML configs
```

### 文件统计
- **新增文件**: 17 个
  - Python 提示词: 8 个 YAML + 1 个 loader
  - Kotlin 后端: 4 个 Ktor 文件 + 1 个 BackendManager
  - 文档: 3 个 Markdown
- **修改文件**: 12 个
- **代码行数**: 
  - Python: ~100 行
  - Kotlin: ~400 行
  - 配置: ~30 行
  - 文档: ~500 行
  - **总计**: ~1,030 行

### Ktor 模块规模
```
ktor/
├── KtorBackendController.kt    (127 行)
├── plugins/
│   └── Security.kt              (58 行)
├── routes/
│   └── HealthRoute.kt           (27 行)
├── services/                    (待实现)
└── models/                      (待实现)

backend/
└── BackendManager.kt            (88 行)

总计: 300+ 行
```

---

## 🏗️ 技术架构

### 后端切换机制

```
┌─────────────────────────────────────┐
│        ZaomengRepository            │
│  (使用 BackendManager 抽象接口)      │
└──────────────┬──────────────────────┘
               │
               ▼
       ┌───────────────┐
       │ BackendManager│
       └───────┬───────┘
               │
      ┌────────┴────────┐
      ▼                 ▼
┌──────────────┐  ┌──────────────┐
│  Python      │  │    Ktor      │
│  (已移除)    │  │  (CIO)       │
└──────────────┘  └──────────────┘
```

**切换方式**:
```kotlin
// build.gradle.kts
buildConfigField("boolean", "USE_KTOR_BACKEND", "false")  // Python
buildConfigField("boolean", "USE_KTOR_BACKEND", "true")   // Ktor
```

### 依赖关系

```
App Layer (UI)
    ↓
Repository Layer (ZaomengRepository)
    ↓
Backend Layer (BackendManager)
    ↓
    ├─→ Python (EmbeddedBackendController + Chaquopy)
    └─→ Ktor (KtorBackendController + CIO)
```

---

## 🎯 下一阶段计划

### Phase 2: 只读 API (预计 5-7 天)

**目标**: 实现数据读取功能，不修改任何数据

**任务列表**:
1. [ ] **文件系统抽象层**
   - 实现 Kotlin 版本的存储访问
   - 读取 `run_manifest.json`, `model_settings.json`
   - 路径安全检查（防止目录穿越）

2. [ ] **诊断 API**
   - `GET /api/web/runs/{run_id}/diagnostics/*`
   - 读取运行状态、日志

3. [ ] **列表 API**
   - `GET /api/web/runs`
   - `GET /api/web/runs/{run_id}/dialogue/sessions`
   - `GET /api/web/runs/{run_id}/chapters`

4. [ ] **并行测试**
   - Python 和 Ktor 同时运行在不同端口
   - 对比 API 响应一致性
   - 编写集成测试

**成功标准**:
- ✅ Ktor 能读取所有运行数据
- ✅ 响应格式与 Python 100% 兼容
- ✅ 所有只读端点测试通过

---

## 🚧 风险与挑战

### 已识别风险

| 风险 | 级别 | 缓解措施 | 状态 |
|------|------|---------|------|
| 提示词管理 | ⚠️ 高 | 提取到 YAML 配置 | ✅ 已解决 |
| 依赖冲突 | ⚠️ 中 | 验证 Ktor 兼容性 | ✅ 已解决 |
| 状态管理 | ⚠️ 中 | 兼容现有数据格式 | 🔄 进行中 |
| 流式响应 | ⚠️ 中 | 使用 Ktor SSE | ⏳ 待处理 |
| 性能回归 | ⚠️ 低 | 性能对比测试 | ⏳ 待处理 |

### 缓解策略

1. **并存策略**: Python 和 Ktor 可同时存在，随时切换
2. **增量迁移**: 逐个 API 迁移，每个都独立验证
3. **自动化测试**: Phase 2 开始编写集成测试
4. **回滚能力**: 保留 Python 后端作为备份

---

## 📝 开发日志

### 2026-08-07：人物校对与头像 API 完整迁移

- `GET/PUT /api/web/runs/{run_id}/personas/{character}` 已由 Ktor Server 提供。
- `GET /quality-report` 支持读取既有报告，并在缺失时生成兼容报告。
- `POST/GET /avatar` 支持 PNG/JPEG/WebP、5 MB 限制和二进制回读。
- 上传头像后同步更新 `run_manifest.json` 中的 `artifact_index.characters[].avatar_version`。
- App 的人物读取、保存、质量报告、头像上传和下载均已切换到 `KtorPersonaClient`。
- 集成测试覆盖导入包风格的绝对路径档案、人物编辑、multipart 上传、头像回读和清单版本更新。

### 2026-08-07：会话创建与聊天路由修复

- 会话创建支持 App 实际使用的 `observe`、`act`、`insert` 模式，并保留旧模式兼容。
- 会话列表和详情路由移除重复注册，统一由 `SessionManagementRoute` 管理。
- 普通回复只由 `DialogueRoute` 处理，不再与流式路由竞争并误回 501。
- 流式回复改为 App 契约中的 `POST .../reply/stream`，返回 `status` 和 `complete/error` SSE 事件。
- 移除聊天前错误调用永远返回空值的 `StorageService.getApiKey()`；密钥统一由 `LlmClient` 从活动模型配置和 Keystore 解析。
- 回复完成后同步更新会话 `transcript`、`turn_count`、`last_entry_preview` 和当前轮次。
- 集成测试覆盖三种 App 会话模式的创建与详情读取。

### 2026-08-06

**工作时间**: 全天  
**完成内容**: Phase 0 + Phase 1  

**亮点**:
- ✨ 成功提取所有硬编码提示词
- ✨ Ktor 服务器可正常启动和停止
- ✨ 认证中间件完全兼容 Python 版本
- ✨ 后端切换机制工作正常

**遇到的问题**:
1. YAML 格式错误 → 修复为正确的层级结构
2. 导入路径错误 → 调整 import 语句
3. Git 路径问题 → 修正文件路径

**解决方案**:
- 使用正确的 YAML 语法和缩进
- 按照 Kotlin 包结构组织代码
- 在正确的工作目录执行 git 命令

**明天计划**:
- 开始 Phase 2：实现文件系统抽象层
- 实现第一个只读 API 端点
- 编写基础的集成测试

---

## 📚 文档索引

- [迁移计划](../MIGRATION_PLAN.md) - 完整的分步骤计划
- [Phase 0 总结](./PHASE_0_SUMMARY.md) - 准备阶段详情
- [Phase 1 总结](./PHASE_1_SUMMARY.md) - 核心基础设施详情
- [提示词配置](../../prompts/README.md) - YAML 配置说明

---

## 🎉 里程碑

- ✅ 2026-08-06: Phase 0 完成 - 准备工作就绪
- ✅ 2026-08-06: Phase 1 完成 - 核心基础设施到位
- ⏳ 预计 2026-08-13: Phase 2 完成 - 只读 API 可用
- ⏳ 预计 2026-08-20: Phase 3 完成 - LLM 集成完成
- ⏳ 预计 2026-09-30: 所有阶段完成 - 可切换到 Ktor

---

**维护者**: AI Assistant  
**项目**: zaomeng Android  
**仓库**: migrate-python-to-ktor 分支
