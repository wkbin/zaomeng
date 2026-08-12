# KMP 模块迁移计划

## 目标依赖方向

```text
app:* -> app:shared -> core:domain -> core:contracts
                    -> data:*      -> core:contracts / core:runtime
app:* -> server -> core:contracts / core:runtime / plugins-api
```

- `app:shared` 只保留 Compose UI、导航、ViewModel、展示层 DI 和展示模型；不得包含 Ktor client、下载、流式文件读写、持久化 Repository 或服务端实现。
- `core:contracts` 只承载跨层 DTO、稳定契约和纯值类型，禁止依赖 UI、Ktor、Room 或平台 API。
- `core:domain` 承载用例和 Gateway；用例依赖 Gateway，不依赖具体 `ZaomengRepository`。
- `core:runtime` 承载应用运行时的跨平台抽象，如嵌入式后端控制、端点和安全存储契约。
- `data:remote` 承载 Ktor 客户端、SSE、在线书库下载、更新检查、流式文件读写和 KMP DataStore 偏好存储。
- `data:repository` 承载面向应用的 Repository 实现；它可组合 `data:remote`、运行时抽象和 domain Gateway，但不依赖 UI。
- `server` 承载嵌入式 Ktor、模型编排、Room 和本地业务服务，不能被 `core:*` 或 `data:*` 反向依赖。

## 已完成

- `core:contracts`、`core:runtime`、`core:domain` 与 `data:remote` 已建立。
- 第一批归位：在线书库下载 `OnlineLibraryRepository` 与响应体落盘工具 `StreamFiles` 已从 `app:shared` 迁入 `data:remote`。
- 第二批归位：跨平台 IO dispatcher 迁入 `core:runtime`；应用偏好、更新检查及其网络 DTO 迁入 `data:remote`，主题种子色的 Compose 展示模型保留在 UI。
- 第三批归位：新增 `data:repository`，`ZaomengRepository` 整体退出 `app:shared`；后续拆分保持其公开行为不变。
- 第四批归位：UseCase 的 Koin 装配改为请求 Gateway 接口，具体 Repository 只在数据绑定处出现。
- 第五批归位：为 `ZaomengRepository` 建立 runs、sessions、dialogue、persona、settings、cards、chapters 等领域门面接口，ViewModel 改为注入窄接口，公开行为保持不变。
- 第六批归位：`ZaomengRepository` 实现按领域迁入小型 Repository 并删除上帝类；DI 直接绑定各领域门面，Gateway 由组合适配器提供。
- 第七批归位：`feature:cards` 与 `feature:library` 独立为 Compose KMP 模块；`feature:settings` 先迁出 4 个 ViewModel，屏幕待共享 UI 模块就绪后迁移；数值格式化工具上移 `core:contracts`。
- 第八批归位：新增 `ui:shared` 承载主题、外部动作、Toast、文件导出与 `AppMetadata`；settings 已迁出设置首页、启动恢复、外观与应用支持 4 个屏幕及全部 ViewModel。
- 第九批归位：`ui:shared` 继续承接图像/zip 选择、平台图片加载、提供商标识资源与 `ChatBackgroundImage`；settings 的聊天显示、模型配置、插件 3 个屏幕迁出，第一波 `library` / `settings` / `cards` 全部独立。
- 第十批归位：建立 `server:storage`、`server:llm`、`server:http` 子模块边界；`ktor/models`、`PathSafety`、`SimpleLock` 迁入 `server:storage`，为 Room 与 `StorageService` 迁移铺路。
- 第十一批归位：`server:storage` 承接 `db`（Room 数据库与 actual）、`StorageService`、YAML/阻塞桥等平台存储工具；`server` 聚合模块改为依赖 `:server:storage`，启动入口不变。
- 第十二批归位：`PlatformLog` 下沉 `core:runtime`；`server:llm` 承接 `ModelApiKeyService`、`PromptLoader`、对话提示规则/响应解析与流式解析；`server:http` 承接 SSE 编码器。
- 第十三批归位：HTTP 流式抽象（engine / streaming POST）下沉 `core:runtime`；`server:llm` 承接 `LlmClient` 与对话/蒸馏提示词构建；`server:http` 承接认证与可观测性 Ktor 插件。
- 第十四批归位：`server:http` 迁入首个纯路由 `HealthRoute`；服务依赖型路由待业务服务层归位后继续迁移。
- 第十五批归位：用例反转依赖与第一波展示功能边界收口；`server:storage` / `server:llm` / `server:http` 拆分完成，聚合模块保留 `KtorServiceGraph` 与路由作为应用入口组合。
- 第十六批归位：`relations`、`crossover`、`update` 独立成 `feature:*` 模块，应用更新 UI 模型随功能模块迁移。
- 第十七批归位：`timeline`、`originalknowledge`、`storyrecap` 独立成 `feature:*` 模块；分享与通知权限请求的 Compose 平台辅助迁入 `ui:shared`，蒸馏/小说生成前台控制接口下沉 `core:runtime`。
- 第十八批归位：`bookshelf`、`chapters`、`sessions`、`rundetail`、`persona` 独立成 `feature:*` 模块并随迁各自 commonTest；其中 persona 的 `PersonaScreen` / `PersonaViewModel` 迁入 `:feature:persona`，`app:shared` 改为依赖新模块并在 `SharedViewModelModule` 中引用；文档选择、头像裁剪、返回手势等平台辅助迁入 `ui:shared`。
- 第十九批归位：`chat`、`importbook`、`redistill` 独立成 `feature:*` 模块并随迁测试；剪贴板、GB18030 解码与 ZIP 条目读取迁入 `ui:shared`，`feature:redistill` 复用 `feature:importbook` 的文档加载与文本统计。

## 后续批次

1. **展示功能边界**：`library`、`settings`、`cards`、`relations`、`crossover`、`update`、`timeline`、`originalknowledge`、`storyrecap`、`bookshelf`、`chapters`、`sessions`、`rundetail`、`persona`、`chat`、`importbook`、`redistill` 均已独立为 `feature:*` 模块。

## 每批验收

- 不出现从 `core:*` 或 `data:*` 指向 `app:*`、`server` 的 Gradle 依赖。
- 接口、客户端和服务端契约变更同步更新 `docs/server-api.md`。
- 涉及 Room schema 的批次必须同时提供迁移与回归测试。
- 至少执行 `git diff --check`、相关模块 JVM 测试和 Android 编译；macOS CI 补 iOS 编译。
