# Python to Ktor 迁移计划

**分支**: `migrate-python-to-ktor`  
**开始时间**: 2026-08-06  
**状态**: 进行中

## 当前进度总结

✅ **Phase 0: 准备阶段 (已完成)**
- 提取了 8 个硬编码提示词到 YAML 配置
- 添加了 Ktor 3.5.2 依赖
- 创建了 Ktor 模块基础结构
- 修复了 prompts 模块的同步问题
- Python 和 Ktor 可并存运行

✅ **Phase 1: 核心基础设施 (已完成)**
- Ktor 服务器启动逻辑
- 健康检查端点
- 认证中间件
- 运行时切换机制

✅ **Phase 2: 只读 API (已完成)**
- 文件系统抽象层
- 诊断 API
- 列表 API
- 集成测试

✅ **Phase 3: LLM 集成 (已完成)**
- 已完成代码编写
- 已修复所有编译错误
- LLM 客户端和提示词系统集成

✅ **Phase 4: 写入 API 和状态管理 (已完成)**
- 会话管理、运行管理、设置管理
- 所有写入 API 端点实现

✅ **Phase 5: 流式响应和实时功能 (已完成)**
- SSE 编码器和流式解析器
- 流式对话回复
- 对话建议系统

✅ **Phase 6: 高级功能 (已完成)**
- 章节生成、角色卡、场景卡
- 人物资料补全、插件系统

**下一步**: 按 API 分组继续迁移 Android 客户端 HTTP 层，完成兼容性验证。**当前待办**：在构建环境运行 `./gradlew :server:testDebugUnitTest` 与 `assembleDebug` 验证提示词对齐改动编译通过，真机对比对话回复/建议/导演/审校效果；修改提示词 YAML 后需同步到 `server/src/main/assets/`（仓库根 `prompts/` 为单一来源）。

**重大进展（2026-08-07）**: 修复约 58 个 404 端点——Ktor server 补齐了对话高级功能（15）、章节管理（11）、卡片 CRUD（16）、人物关系（2）、运行/蒸馏（9）、插件包（5）全部端点；prompts 提示词目录已打包进 assets 供真机 LLM 使用；KtorApiIntegrationTest 26/26 全绿。

**重大进展（2026-08-08，App 行为对齐）**: 蒸馏 P3-P5 迁移完成（分块+合并+关系图+增量+resume 跳过+进度文案对齐）；
对话场景状态机迁移（scene_progress/event_signals/character_snapshots 每轮推导并进 payload）；correct-latest 走
CORRECTION_CONTEXT 分支；卡片生成字段清单、章节转换 context_summary、redistill 模型配置校验补齐；
修复 Windows 原子写入（renameTo→Files.move）与 refresh 抹掉关系图的问题。KtorApiIntegrationTest 29/29 全绿。
详见 `docs/PROMPT_DIFF_ANALYSIS.md` 第十五节。

**提示词对齐（2026-08-07 追加）**: 系统对比 Python 与 Ktor 提示词构建后，对话系（reply/stream/suggest/associations/direct/deepReview）已迁移 Python 完整提示词管道到 Ktor——新增 `DialoguePromptRules.kt`（prompt_rules.py 规则文本）、`DialoguePromptBuilder.kt`（helpers.py 的 5 个 build_*_messages + compact 辅助）、`DialoguePayloadBuilder.kt`（service.py `_build_turn_payload` 管道 + speaker_balance + persona/关系/world_memory/memory_ledger 数据读取）；修改 4 个服务接入。差异清单见 `docs/PROMPT_DIFF_ANALYSIS.md`。已知保留差异：scene_progress/original_source_context/knowledge_context/长期记忆检索（Ktor 无对应数据源，payload 传空）。本机无 Android SDK 未能编译，需在构建环境运行 `./gradlew :server:testDebugUnitTest` 验证。

---

## 进度概览

- [x] Phase 0: 准备阶段（1-2 天）✅ 完成于 2026-08-06
- [x] Phase 1: 核心基础设施（3-5 天）✅ 完成于 2026-08-06
- [x] Phase 2: 只读 API（5-7 天）✅ 完成于 2026-08-07
- [x] Phase 3: LLM 集成（5-7 天）✅ 完成于 2026-08-06
- [x] Phase 4: 写入 API 和状态管理（7-10 天）✅ 完成于 2026-08-06
- [x] Phase 5: 流式响应和实时功能（5-7 天）✅ 完成于 2026-08-06
- [x] Phase 6: 高级功能（7-10 天）
- [ ] Phase 7: 性能优化和测试（5-7 天，部分完成）
- [ ] Phase 8: WebUI 适配和上线（3-5 天，部分完成）
- [ ] Phase 9: 清理和最终化（2-3 天）

**当前进度**: 90%（Ktor 运行时已成为唯一后端，Retrofit 兼容桥清理中；约 58 个此前 404 的端点已全部补齐并测试）

---

## Phase 0: 准备阶段

### 0.1 提取硬编码提示词到独立配置 ✅ 已完成
- [x] 创建 `prompts/` 目录结构
- [x] 提取对话相关提示词（helpers.py）
  - director.yaml (对话导演)
  - suggestions.yaml (对话建议)
  - consistency_review.yaml (一致性审校)
  - inner_thought_rule.yaml (读心功能)
- [x] 提取蒸馏相关提示词（builders.py） - 待后续处理
- [x] 提取审校相关提示词（persona_completion.py, scene_cards.py, self_cards.py）
  - persona_completion.yaml (人物资料补全)
  - scene_card_generation.yaml (场景卡生成)
  - self_card_generation.yaml (角色卡生成)
- [x] 提取章节改写提示词（chapters.py）
  - novel_rewrite.yaml (章节改写)
- [x] 修改 Python 代码从配置加载
  - src/web/chat/helpers.py - 使用 loader
  - src/web/service_facades/chapters.py - 使用 loader
  - src/web/review/persona_completion.py - 使用 loader
  - src/web/review/scene_cards.py - 使用 loader
  - src/web/review/self_cards.py - 使用 loader
- [x] 创建 prompts/loader.py 加载器
- [x] 运行测试验证无回归 - 所有模块正常导入

**完成时间**: 2026-08-06
**成果**: 8 个 YAML 配置文件，Python 代码已迁移至配置加载

### 0.2 添加 Ktor 依赖 ✅ 已完成
- [x] 更新 `gradle/libs.versions.toml` 添加 Ktor 版本 (3.5.2)
- [x] 在 `app/build.gradle.kts` 添加 Ktor 依赖
  - ktor-server-core, ktor-server-cio
  - ktor-server-content-negotiation
  - ktor-serialization-kotlinx-json
  - ktor-server-auth
  - ktor-client-core, ktor-client-okhttp
  - ktor-client-content-negotiation
- [x] Sync 项目验证依赖正常

**完成时间**: 2026-08-06

### 0.3 创建 Ktor 模块结构 ✅ 已完成
- [x] 创建 `ktor/` 包结构
- [x] 创建 `KtorBackendController.kt` 骨架
- [x] 创建 `routes/` 子包 (HealthRoute)
- [x] 创建 `services/` 子包 (业务逻辑)
- [x] 创建 `models/` 子包 (数据模型)

**完成时间**: 2026-08-06
**状态**: Phase 0 准备阶段完成，已具备 Ktor 基础设施

---

## Phase 1: 核心基础设施 ✅ 已完成

### 1.1 实现 Ktor 服务器启动逻辑 ✅
- [x] 实现 `KtorBackendController` 基础框架
- [x] 动态端口分配
- [x] 后台线程启动
- [x] 完善启动错误捕获
- [x] 添加日志记录

### 1.2 实现健康检查端点 ✅
- [x] `/api/web/health` 基础路由
- [x] JSON 响应格式与 Python 兼容
- [x] 添加详细的健康状态信息

### 1.3 实现认证中间件 ✅
- [x] Bearer token 验证插件
- [x] 与 `InstallationTokenStore` 集成
- [x] 保护所有非健康检查端点

### 1.4 实现运行时切换机制 ✅
- [x] 创建 `BackendManager`
- [x] 添加 `USE_KTOR_BACKEND` BuildConfig
- [x] 实现后端选择逻辑
- [x] 更新 DI 配置

**完成时间**: 2026-08-06
**成果**: 
- BackendManager 支持 Python/Ktor 切换
- 完整的认证和健康检查
- 可通过 BuildConfig 控制后端选择

---

## Phase 2: 只读 API ✅ 已完成

### 2.1 文件系统抽象层 ✅ 已完成
- [x] 实现存储路径管理
- [x] 实现 JSON 文件读取
- [x] 实现路径安全检查
- [x] 创建 StorageService.kt
- [x] 创建 PathSafety.kt
- [x] 创建数据模型 DataModels.kt

**完成时间**: 2026-08-06

### 2.2 迁移诊断 API ✅ 已完成
- [x] `GET /api/web/diagnostics/export`
- [x] DiagnosticsService 实现
- [x] 系统诊断报告生成

**完成时间**: 2026-08-07

### 2.3 迁移列表 API ✅ 已完成
- [x] `GET /api/web/runs`
- [x] `GET /api/web/runs/{run_id}`
- [x] `GET /api/web/runs/{run_id}/dialogue/sessions`
- [x] `GET /api/web/runs/{run_id}/chapters`

**完成时间**: 2026-08-06

### 2.4 测试并行运行 ✅ 已完成
- [x] 编写集成测试（KtorApiIntegrationTest）
- [x] API 响应一致性验证
- [x] 错误处理测试

**完成时间**: 2026-08-07

**Phase 2 总结**: 实现了所有只读 API，包括文件系统抽象、诊断导出、运行列表等功能。测试代码已编写完成。

---

## Phase 3: LLM 集成 ✅ 已完成

### 3.1 模型 API 密钥管理 ✅
- [x] 从 Keystore 读取密钥
- [x] 密钥注入到 Ktor

### 3.2 HTTP 客户端配置 ✅
- [x] ktor-client 配置
- [x] 重试和超时
- [x] 流式响应支持（基础实现）

### 3.3 提示词系统集成 ✅
- [x] 加载提示词配置
- [x] 模板渲染逻辑

### 3.4 实现简单对话端点 ✅
- [x] 非流式 reply 端点

**完成时间**: 2026-08-06
**成果**: 
- ModelApiKeyService (92 行)
- LlmClient (270 行)
- PromptLoader (117 行)
- DialogueService (239 行)
- DialogueRoute (103 行)
- DialogueModels (76 行)
- 总计：~897 行新代码
- API 端点：POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply

---

## Phase 4: 写入 API 和状态管理 ✅ 已完成

### 4.1 会话管理 ✅
- [x] 创建会话 API (`POST /api/web/runs/{run_id}/dialogue/sessions`)
- [x] 更新会话 API (`PATCH /api/web/runs/{run_id}/dialogue/sessions/{session_id}/title`)
- [x] SessionManagementService 实现

### 4.2 对话轮次写入 ✅
- [x] 写入用户输入 (`POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/prepare`)
- [x] Manifest 更新逻辑

### 4.3 运行管理 ✅
- [x] 创建运行 (`POST /api/web/runs`)
- [x] 停止运行 (`POST /api/web/runs/{run_id}/control/stop`)
- [x] 删除运行 (`DELETE /api/web/runs/{run_id}`)
- [x] RunManagementService 实现

### 4.4 设置管理 ✅
- [x] 模型配置读写 (`GET/PUT /api/web/settings/model`)
- [x] 测试模型设置 (`POST /api/web/settings/model/test`)
- [x] SettingsManagementService 实现

**完成时间**: 2026-08-06
**成果**: 
- SessionManagementService (202 行)
- RunManagementService (184 行)
- SettingsManagementService (170 行)
- SessionManagementRoute (115 行)
- RunManagementRoute (83 行)
- SettingsManagementRoute (69 行)
- SessionModels.kt (70 行)
- 总计: ~893 行

---

## Phase 5: 流式响应和实时功能 ✅ 已完成

### 5.1 SSE 流式响应 ✅
- [x] SSE 编码器
- [x] 流式 reply

### 5.2 对话建议流 ✅
- [x] 建议端点

### 5.3 蒸馏进度流 ⏸️
- [ ] 蒸馏端点（待 Phase 6）

**完成时间**: 2026-08-06
**成果**: 
- SseEncoder.kt (56 行)
- DialogueStreamParser.kt (211 行)
- DialogueStreamService.kt (117 行)
- DialogueStreamRoute.kt (132 行)
- SuggestionsService.kt (184 行)
- SuggestionsRoute.kt (168 行)
- LlmClient.kt 更新 (+93 行)
- StorageService.kt 更新 (+19 行)
- 总计: ~984 行

---

## Phase 6: 高级功能

### 6.1 章节生成
- [x] 章节改写 API

### 6.2 角色卡和场景卡
- [x] 场景卡生成
- [x] 角色卡生成

### 6.3 人物资料补全
- [x] 补全 API

### 6.4 插件系统
- [x] 插件元数据加载与启停

---

## Phase 7: 性能优化和测试

### 7.1 性能对比
- [x] 文件写入采用锁和临时文件替换
- [ ] 启动时间测试
- [ ] 内存占用测试
- [ ] API 延迟测试

### 7.2 集成测试
- [x] Phase 6 路由集成测试
- [x] 原子文件写入单元测试
- [ ] 完整测试套件

### 7.3 错误处理
- [x] 修复 JsonObject 构建器字符串数组编译错误
- [x] 设置 API 使用可序列化 JSON 响应
- [x] 运行管理 API 使用可序列化 JSON 响应
- [x] 会话管理 API 使用可序列化 JSON 响应
- [x] 建议服务读取 JsonObject 会话清单
- [x] 准备对话轮次使用类型化请求模型
- [x] 对话服务统一会话清单路径和时间戳类型
- [x] LLM 客户端按 active profile 读取模型设置和 API 密钥
- [x] Python 蛇形字段映射到 Ktor 数据模型
- [x] 对话回复更新清单时保留扩展字段
- [x] 新建会话初始化 `turn_count`
- [x] 模型设置测试执行真实连通性检查
- [ ] 统一错误格式
- [ ] 结构化日志

### 7.4 资源清理
- [ ] 移除未使用依赖

---

## Phase 8: WebUI 适配

### 8.1 兼容性验证
- [x] 停止运行兼容路径验证
- [x] 运行、会话和章节列表返回 `items` 包装结构
- [x] 章节列表保留标题和正文元数据
- [ ] API 格式验证

### 8.2 默认切换
- [ ] 设置默认为 Ktor
- [x] Ktor 服务图由 Koin 提供
- [x] 对话、会话、运行和设置路由使用共享服务实例
- [x] 流式对话和建议路由使用共享服务实例
- [x] 模型 profile 激活和删除 API
- [x] 插件配置、日志和卸载 API
- [x] 抽出共享 Ktor Client 配置，作为 Retrofit 替换基础
- [x] 后端启动健康检查迁移到 Ktor Client
- [x] 移除 Chaquopy/Python Android 运行时，Ktor 成为唯一后端
- [x] 创建独立 `:server` Android library module，集中承载 Ktor 服务端代码
- [x] 统一运行清单为无损 JSON 契约，修复导入书卷后的详情读取与停止覆盖问题
- [x] 服务端加入 CallLogging/StatusPages 请求日志和未捕获异常日志
- [x] 模型设置及 profile 管理接口迁移到 Ktor Client（保留 Retrofit 回退）
- [x] 插件列表、刷新、启停和配置接口迁移到 Ktor Client（保留 Retrofit 回退）
- [x] 插件卸载和日志接口迁移到 Ktor Client（保留 Retrofit 回退）
- [x] 运行列表接口迁移到 Ktor Client（保留 Retrofit 回退）
- [x] 运行详情、停止和删除接口迁移到 Ktor Client（保留 Retrofit 回退）
- [x] 会话列表、创建、详情和标题更新接口迁移到 Ktor Client（保留 Retrofit 回退）
- [x] 创建运行接口迁移到 Ktor Client（保留 Retrofit 回退）
- [x] 章节列表接口迁移到 Ktor Client（保留 Retrofit 回退）
- [x] 诊断报告导出接口迁移到 Ktor Client（保留 Retrofit 回退）
- [x] 场景卡和自我角色卡生成接口迁移到 Ktor Client（保留 Retrofit 回退）
- [x] 人物资料字段建议接口迁移到 Ktor Client（保留 Retrofit 回退）
- [x] 非流式对话回复返回会话契约，并迁移到 Ktor Client（保留 Retrofit 回退）
- [x] profile/plugin 兼容性回归测试

### 8.3 文档
- [x] 持续更新迁移进度与端点状态

### 8.4 Beta 发布
- [ ] 测试反馈

---

## Phase 9: 清理

### 9.1 移除 Chaquopy
- [ ] 移除插件
- [ ] 删除 Python 任务

### 9.2 清理代码
- [ ] 删除旧后端

### 9.3 最终测试
- [ ] 完整回归

---

## 当前 API 端点总览

### 健康检查
- `GET /api/web/health`

### 运行管理
- `GET /api/web/runs` - 列出所有运行
- `POST /api/web/runs` - 创建新运行
- `GET /api/web/runs/{run_id}` - 获取运行详情
- `POST /api/web/runs/{run_id}/control/stop` - 停止运行
- `DELETE /api/web/runs/{run_id}` - 删除运行

### 会话管理
- `GET /api/web/runs/{run_id}/dialogue/sessions` - 列出会话
- `POST /api/web/runs/{run_id}/dialogue/sessions` - 创建会话
- `GET /api/web/runs/{run_id}/dialogue/sessions/{session_id}` - 获取会话详情
- `PATCH /api/web/runs/{run_id}/dialogue/sessions/{session_id}/title` - 更新标题
- `POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/prepare` - 准备轮次

### 对话
- `POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply` - 对话回复（非流式）
- `POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply/stream` - 对话回复（SSE 流式兼容接口）

### 对话高级功能（2026-08-07 补齐）
- `GET .../sessions/{session_id}/search` - 会话搜索
- `POST .../sessions/{session_id}/recover` - 恢复会话
- `POST .../sessions/{session_id}/branch`、`branch-turn` - 分支
- `PATCH .../sessions/{session_id}/branch-meta` - 分支元数据
- `PUT .../sessions/{session_id}/relation-lock` - 关系锁定
- `POST/PUT/DELETE .../sessions/{session_id}/memories[/{memory_id}]` - 记忆管理
- `POST .../sessions/{session_id}/suggest` - 续写建议（LLM）
- `POST .../sessions/{session_id}/correct-latest` - 修正最新回复（LLM）
- `POST .../sessions/{session_id}/deep-review` - 深度审校（LLM）
- `POST .../sessions/{session_id}/director-options` - 剧情导演（LLM）
- `PUT .../sessions/{session_id}/scene-card`、`POST .../scene-card/recommend` - 场景卡切换/推荐

### 建议系统
- `POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/suggestions?stream=true` - 流式建议
- `POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/associations` - 联想选项

### 设置
- `GET /api/web/settings/model` - 获取模型设置
- `PUT /api/web/settings/model` - 保存模型设置
- `POST /api/web/settings/model/test` - 测试模型设置

### 诊断
- `GET /api/web/diagnostics/export` - 导出诊断报告

### 章节（2026-08-07 补齐管理端点）
- `GET /api/web/runs/{run_id}/chapters` - 列出章节
- `GET /api/web/runs/{run_id}/search` - 书卷搜索
- `POST /api/web/runs/{run_id}/ask` - 问书卷（LLM）
- `POST/PUT /api/web/runs/{run_id}/chapters[/{chapter_id}]` - 创建/更新章节
- `PATCH /api/web/runs/{run_id}/chapters/{chapter_id}/order` - 重排
- `POST .../chapters/archive-session`、`convert-session` - 归档/转换会话
- `DELETE /api/web/runs/{run_id}/chapters/{chapter_id}` - 删除章节
- `POST .../chapters/{chapter_id}/continue`、`sync-session` - 继续写作/同步
- `GET /api/web/runs/{run_id}/chapters/export` - 导出手稿
- `POST /api/web/runs/{run_id}/chapters/{chapter_id}/rewrite` - 章节改写（LLM）

### 人物与头像
- `GET /api/web/runs/{run_id}/personas/{character}` - 读取人物校对字段
- `PUT /api/web/runs/{run_id}/personas/{character}` - 保存人物校对字段
- `GET /api/web/runs/{run_id}/personas/{character}/quality-report` - 读取人物质量报告
- `POST /api/web/runs/{run_id}/personas/{character}/avatar` - 上传人物头像
- `GET /api/web/runs/{run_id}/personas/{character}/avatar` - 读取人物头像
- `POST /api/web/runs/{run_id}/personas/{character}/suggest-field` - 字段建议

### 人物关系（2026-08-07 补齐）
- `GET /api/web/runs/{run_id}/relations` - 列出关系
- `PATCH /api/web/runs/{run_id}/relations/{pair_key}` - 更新关系

### 卡片库（2026-08-07 补齐）
- `GET/POST /api/web/scene-cards`、`GET/PUT/DELETE /api/web/scene-cards/{card_id}`、`POST /api/web/scene-cards/recommend`、`POST /api/web/scene-cards/generate`
- `GET/POST /api/web/self-cards`、`GET/PUT/DELETE /api/web/self-cards/{card_id}`、`POST /api/web/self-cards/generate`
- `GET/POST /api/web/opening-presets`、`GET/PUT/DELETE /api/web/opening-presets/{card_id}`

### 运行/蒸馏（2026-08-07 补齐）
- `GET /api/web/builtin-novels`、`POST /api/web/builtin-novels/{package_id}/clone`
- `POST /api/web/runs/estimate` - 采样估算
- `POST /api/web/crossover-spaces` - 共演空间
- `GET /api/web/runs/{run_id}/export` - 导出书卷包
- `POST /api/web/runs/{run_id}/redistill`、`resume-distill` - 重新蒸馏/恢复
- `POST /api/web/runs/{run_id}/redistill/recommend` - 蒸馏片段推荐
- `POST /api/web/runs/{run_id}/refresh` - 刷新运行清单

### 插件包（2026-08-07 补齐）
- `POST /api/web/plugins/packages/inspect`、`POST /api/web/plugins/packages/{token}/install`
- `POST .../sessions/{session_id}/plugins/{plugin_id}/actions/{action_id}` - 插件动作（需 Python 运行时，返回明确错误）
- `POST .../npc-generators/{generator_id}` - 临时 NPC（需 Python 运行时，返回明确错误）
- `PUT .../plugins/{plugin_id}/enhancers/{enhancer_id}/state` - 增强器状态

---

## 风险点

1. ✅ ~~**提示词管理** - 硬编码在 8+ 文件中~~ (Phase 0 已解决)
2. ⚠️ **依赖差异** - Python 生态 vs Kotlin 生态
3. ⚠️ **状态管理** - 文件系统兼容性
4. ✅ ~~**流式响应** - SSE 实现差异~~ (Phase 5 已解决)
5. ✅ ~~**LLM API** - HTTP 客户端迁移~~ (Phase 3 已解决)

## 预期收益

- 安装包减少 20-30MB
- 启动快 2-3 秒
- 内存减少 50-100MB
- 代码维护性提升
- WebUI 可独立部署
