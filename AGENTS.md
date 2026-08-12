# AGENTS.md

本文档适用于仓库根目录及所有子目录，供自动化编码代理和协作者执行开发、测试与交付任务时使用。若子目录存在更具体的 `AGENTS.md`，以距离目标文件最近的文档为准。

## 项目定位

造梦将中文小说人物蒸馏为人物包、关系和记忆，并提供多角色对话体验。

- `kmp/`：当前主实现。Compose Multiplatform 客户端内嵌 Ktor 服务和 Room 数据库，覆盖 Android、Desktop 与 iOS。
- `zaomeng-skill/`：OpenClaw / ClawHub skill 包。
- `src/`、`src/web/`：不再维护的 Python/Web 旧实现，仅用于行为对照和兼容测试。除非任务明确要求，不要在这里实现新功能。
- `prompts/`：可编辑的提示词源文件。

## KMP 模块

- `kmp/app/shared`：共享 UI、导航、ViewModel、展示层 DI 与平台入口。
- `kmp/app/androidApp`：Android 应用入口与打包配置。
- `kmp/app/desktopApp`：Desktop 应用入口。
- `kmp/core/contracts`：跨层 DTO、稳定契约和纯值类型。
- `kmp/core/domain`：领域 Gateway、UseCase 与不依赖 UI 的业务编排。
- `kmp/core/runtime`：嵌入式后端、端点、安全存储与流式 HTTP 等运行时抽象。
- `kmp/data/remote`：Ktor API client、SSE、在线书库下载、更新检查、流式文件读写与 DataStore 偏好存储。
- `kmp/data/repository`：面向应用的 Repository 实现与 domain Gateway 适配。
- `kmp/ui/shared`：主题、跨平台 UI 辅助与共享展示模型。
- `kmp/feature/*`：按业务拆分的 Screen/ViewModel 模块（bookshelf、chapters、sessions、persona、chat 等）。
- `kmp/server`：内嵌 Ktor 聚合模块，保留路由、业务服务、插件宿主与平台入口。
- `kmp/server/storage`：Room、StorageService、PathSafety 与领域模型。
- `kmp/server/llm`：LlmClient、ModelApiKeyService、提示词构建与响应解析。
- `kmp/server/http`：HTTP 插件、SSE 编码器与基础路由。
- `kmp/plugins-api`：插件公共契约；保持向后兼容。
- `kmp/builtin-plugins`：内置插件实现。
- `kmp/iosApp`：iOS Xcode 工程。

依赖统一通过 `kmp/gradle/libs.versions.toml` 管理。新增依赖前先确认是否已有版本目录项，不要在模块脚本中随意硬编码版本。

## 开发原则

1. 新功能优先落在 KMP 实现，不要同时维护 Python 旧后端，除非任务要求行为对齐。
2. 业务逻辑尽量放在 `commonMain`。`commonMain` 不得直接引用 JVM、Android 或 Apple 专属 API；平台差异通过 `expect/actual` 或已有平台接口隔离。
3. 修改共享接口时检查 Android、JVM 和 iOS 的所有 `actual` 实现。Windows 上无法完整验证 iOS，仍需保持 iOS 源码语义和 CI 可编译。
4. 保持协程非阻塞。文件、数据库和网络阻塞操作应进入平台 IO dispatcher；不要在 UI 线程执行大文件解析。
5. 不要复制大型原文或生成型静态资源。优先使用内容寻址、缓存、索引或已有资源引用。
6. 对现有脏工作区保持克制：不要覆盖、还原或删除与当前任务无关的用户改动。

## 后端与存储

- 生产持久化统一经过 `StorageService`、`DocumentStore` 和 `DomainStore`，不要绕过它们直接操作应用数据库。
- 所有 run/session 标识必须经过 `PathSafety` 校验；禁止拼接未经验证的外部路径。
- 写入业务文档使用原子写接口。新增文档路径时确认 Room 文档同步、缓存失效和级联删除语义。
- 会话生成路径只读取有界的近期 transcript。完整历史通过归档/分页路径读取，不要重新把无限增长的历史塞回生成 manifest。
- 数据结构或 Room schema 变化必须提供相应迁移或明确的破坏性升级策略，并补充测试。不要静默丢弃用户数据。
- API 密钥只通过 `ModelApiKeyService` 和平台安全存储访问，禁止写入 manifest、日志、测试快照或仓库文件。

## 模型输出与流式对话

- 多角色对话的主协议是 NDJSON：每行一个完整对象，至少包含非空 `speaker` 与 `message`，可选 `inner_thought`。
- 对话解析器需兼容旧 JSON 数组和 `responses` 包装对象，但兼容逻辑是兜底，不应成为提示词主协议。
- NDJSON 整体不是单个 JSON 对象，因此对话请求不要同时声明 `response_format=json_object`。只返回单个对象的其他模型任务应继续使用原生 JSON 结构约束。
- 流式链路必须从模型响应体到应用 UI 全程增量读取。禁止使用会把响应缓存到 EOF 的调用方式，也不要为了视觉效果伪造模型打字速度。
- 第一个可见 `message` 增量应立即进入 UI；后续可做很短的帧级合批以减少 Compose 重组。
- 解析失败重试可以保留，但不能用纯文本兜底掩盖多人场景的结构错误。
- 不要记录完整模型输出、完整提示词、密钥或超长原文。诊断日志应使用长度限制，并记录阶段耗时、TTFT、重试次数等指标。

## 提示词同步

对话等内置提示词同时存在于可编辑源目录和 Android 打包 assets 中。修改以下一侧时必须同步另一侧：

- `prompts/**`
- `kmp/server/src/androidMain/assets/**`

结构化输出提示必须明确：输出形状、必填字段、是否允许 Markdown 围栏、是否允许前后缀文字。新增 JSON 模型任务时，在调用处启用提供商支持的结构化约束，并为不支持该能力的兼容端保留严格提示词和解析校验。

## UI 与 API

- 网络 DTO 位于共享 API 模型中；服务端与客户端字段需要同步变更并保持合理默认值。
- `docs/server-api.md` 是当前 Ktor HTTP 接口文档；新增、删除或修改路由、请求/响应字段、认证、分页、流式事件或状态码时必须同步更新。
- 聊天完成事件优先返回轻量 session 和本轮增量，不要在每次发送后重新传输完整 transcript。
- ViewModel 状态更新应保持幂等，尤其注意断线重试、operation ID、流式 reset/complete 和历史分页合并。
- Compose 列表元素应使用稳定 key；避免在高频 delta 上进行昂贵的全列表重建。

## 验证命令

命令默认从 `kmp/` 目录运行。Windows 使用 `gradlew.bat`，Unix/macOS 使用 `./gradlew`。需要 JDK 17+，CI 使用 JDK 21。

快速验证服务端改动（含子模块）：

```powershell
.\gradlew.bat :server:storage:jvmTest :server:llm:jvmTest :server:http:jvmTest :server:jvmTest :server:compileKotlinJvm :server:compileAndroidMain
```

共享应用改动（涉及独立 feature 模块时追加对应 `:feature:*:jvmTest`）：

```powershell
.\gradlew.bat :app:shared:jvmTest :app:shared:compileKotlinJvm :app:shared:compileAndroidMain
```

Android 完整检查：

```powershell
.\gradlew.bat testDebugUnitTest jvmTest lintDebug assembleDebug
```

Desktop 编译：

```powershell
.\gradlew.bat :app:desktopApp:compileKotlin
```

iOS 仅在 macOS 上验证：

```bash
./gradlew :server:compileKotlinIosSimulatorArm64 \
  :app:shared:compileKotlinIosSimulatorArm64 \
  :app:shared:linkDebugFrameworkIosSimulatorArm64
```

若明确修改旧 Python 基线、脚本或仓库级检查，再从仓库根目录运行：

```bash
python scripts/dev_checks.py
```

测试范围应与风险相称：解析器、存储、幂等、并发、提示词协议和 token 预算变更必须补回归测试。提交前至少运行 `git diff --check`。

## 安全与仓库卫生

- 永远不要提交 `*.jks`、`*.keystore`、`keystore.properties`、`local.properties`、`.env*`、数据库、应用私有目录或 Gradle/IDE 缓存。
- 根目录 `android/` 是本机生成的项目镜像，包含签名配置，已被忽略；正式 Android 源码位于 `kmp/app/androidApp`。
- 不要提交模型 API 密钥、签名密码、个人访问令牌或用户数据库导出。
- 不要用破坏性 Git 命令清理用户工作区。只在用户明确要求时创建提交、推送或改写历史。
- 提交前检查 `git status`、`git diff --check` 和暂存文件列表；提交信息应概括行为变化，而不是工具操作。

## 完成标准

任务只有在以下条件满足后才算完成：

1. 实现落在正确的主模块，未无意修改旧实现或用户文件。
2. 跨平台声明与实现一致，客户端/服务端契约同步。
3. 关键失败路径有结构校验、边界处理和回归测试。
4. 相关测试和编译任务通过；无法在本机运行的平台验证已明确说明。
5. 没有密钥、构建产物或大型重复资源进入版本控制。
