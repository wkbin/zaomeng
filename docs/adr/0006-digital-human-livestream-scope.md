# ADR-0006：数字人直播（livestream）暂不纳入构建

- 状态：提议（搁置，待产品决策）
- 日期：2026-11-18

## 背景

工作区里出现了一个未提交的 `kmp/feature/livestream` 模块（4 个文件：2 个 model、1 个 repository 接口、1 个 Ktor 客户端），并曾在 `kmp/settings.gradle.kts` 中被 `include`。它实现的是**数字人派对直播房间**：房间快照、在线用户、评论、礼物、充电/电力、人物资料卡，对接 `cyapi*.ideaflow.pro` / `social*.ideaflow.pro` 的 `party` / `cartoon` / `power` 接口，`Authorization` 用 `metatube-<token>`。

这与「造梦 = 中文小说人物蒸馏 + 单机多角色对话，唯一外部依赖是模型」的定位不同：它是一条指向**另一个产品后端**的网络链路，且引入 `beans`（付费豆）、送礼、连击动画等交易性概念。

同时该模块当前无法通过编译：缺 `ktor-client-content-negotiation` / `ktor-serialization-kotlinx-json` 依赖声明、`commonMain` 里用了 JVM-only 的 `KClass.java`、`decodeFromJsonElement` 传了 `Class` 而非 `DeserializationStrategy`、`LiveStreamingRepository` 没有实现者、三个 `SharedFlow` 从不 emit。它是一份跨产品移植的半成品骨架。

## 决策

1. **暂不纳入构建**：从 `kmp/settings.gradle.kts` 移除 `include(":feature:livestream")`，源码保留在工作区（不删除），避免未完成模块进入所有 CI 编译面。
2. **不静默并入产品**：是否把「角色直播」作为造梦能力、以及后端归属（自建 / 复用现有 party 服务 / 用造梦本地后端模拟），需要单独的产品与技术决策，不由这份代码默认决定。

## 结果

优点：

- 构建回到当前可编译、可测试的状态（`./gradlew build` / `testDebugUnitTest` / iOS 编译不再因该模块失败）；
- 把「一个产品的 UI 与后端被搬进另一个产品」这件事显式化，留待明确决策。

代价：

- 若最终决定做直播，需要先补 ADR、再按上面的编译问题重做一遍接入；
- 已有的 Figma 稿（`figma-screenshot.png`）与数据库字段设计暂时无处落地。

## 重新启用前提（清单）

1. 产品决策落 ADR（本文件转「已采纳」或另立新 ADR），明确后端归属与是否引入交易/付费概念。
2. 补齐 `feature/livestream/build.gradle.kts` 的 ktor 依赖声明；`commonMain` 去掉任何 JVM-only API（`KClass.java`、`java.*`）。
3. `toEnvelope` 改用 `decodeFromJsonElement<T>(...)` 或显式 `serializer<T>()`。
4. 接口与实现对齐：`LiveStreamingRepository` 有唯一实现者，三条流有真实 emit（IM/WS 或明确降级为轮询）。
5. 接入既有 `KtorHttpClientProvider` 语义（bearer 鉴权、`HttpTimeout`、`close()`），baseUrl 走 `BackendEndpointProvider` 而不是硬编码域名；token 不进 `data class` 的 `toString()`。
6. 至少补齐 repository 层单测，并通过 `:feature:livestream:jvmTest`。
