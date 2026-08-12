# Server agent rules

本文件适用于 `kmp/server/`，并继承仓库根目录 `AGENTS.md`。发生冲突时，以本文件针对服务端的具体规则为准。

## 职责边界

- `:server` 是聚合模块：保留 `commonMain/ktor/routes`、`commonMain/ktor/services` 与平台 actual。
- `:server:storage` 承载 `commonMain/db`、`StorageService`、`PathSafety` 与模型 DTO。
- `:server:llm` 承载 `LlmClient`、`ModelApiKeyService`、提示词构建与响应解析。
- `:server:http` 承载 Ktor 插件、SSE 编码器与 `HealthRoute`。
- `commonMain/ktor/routes` 只负责 HTTP/SSE 参数、状态码和响应映射；业务逻辑放入 service。
- `commonMain/ktor/services` 承载模型调用编排、对话、书卷、记忆、卡片和插件业务。
- `commonMain/platform` 声明跨平台能力；Android、JVM、iOS 源码集提供对应实现。
- 不要在 route 中直接读写 Room、文件或安全存储。

## 存储规则

- 业务持久化统一经过 `StorageService`。新增路径必须使用 `PathSafety` 验证外部标识。
- 文档写入必须使用原子写接口，并检查 `DomainStore.onWrite/onDelete` 是否需要识别新路径。
- 修改 session manifest、transcript、messages 或 Room schema 时，必须验证缓存失效、分页、删除级联与旧数据行为。
- 生成请求只使用有界的近期 transcript；完整历史由归档和分页接口负责。
- 大型原文不得按轮复制或整体放入提示词。使用缓存索引检索少量片段，并记录实际注入长度。

## 模型与流式协议

- 多角色对话输出使用 NDJSON，每行一个 `{speaker,message,inner_thought?}` 对象。
- 对话请求不得启用要求“单个 JSON 对象”的 provider 参数；其他单对象任务应尽量启用原生 JSON 约束。
- 流式 HTTP 响应必须逐行/逐块消费，不能调用会缓冲到 EOF 的便捷 API。
- SSE route 每次发送可见 delta 后需要及时 flush；不要在服务端制造打字机延时。
- 解析器必须支持未闭合 `message` 字符串的增量投影，并兼容旧数组/`responses` 格式的最终解析。
- 完整输出只可在错误日志中截断记录，禁止记录密钥、完整提示词、完整原文或用户数据库内容。

## 并发与性能

- 网络和文件阻塞操作进入 `platformIoDispatcher` 或平台流式接口。
- 同一 session 的提交必须保持顺序和幂等；后台派生任务不能覆盖更新后的 manifest。
- 可以缓存不可变或带版本标识的解析结果；缓存键必须包含路径及 mtime/大小等失效条件。
- 不要在回复关键路径串行执行互不依赖的原文、记忆和世界状态加载。

## 测试要求

以下改动必须补充 JVM 回归测试：

- JSON/NDJSON 解析与流式增量边界；
- token 预算和模型请求参数；
- transcript 归档、分页、幂等提交与 Room 同步；
- 场景进度、长期记忆、原文检索和知识边界；
- 路径安全、ZIP 导入及大型资源去重。

从 `kmp/` 运行最小验证：

```powershell
.\gradlew.bat :server:storage:jvmTest :server:llm:jvmTest :server:http:jvmTest :server:jvmTest :server:compileKotlinJvm :server:compileAndroidMain
```

iOS 平台实现有变化时，必须说明是否已在 macOS CI 或本机执行 `:server:compileKotlinIosSimulatorArm64`。
