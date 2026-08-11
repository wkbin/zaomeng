# ADR-0001：采用本地内嵌 KMP 后端

- 状态：已采纳
- 日期：2026-08-11

## 背景

造梦需要同时支持 Android、Desktop 和 iOS，并在设备上保存小说、人物资料、模型密钥和对话历史。旧 Python/Web 后端要求用户单独部署进程，也难以提供一致的移动端体验。

## 决策

使用 Kotlin Multiplatform 实现共享业务，将 Ktor 服务内嵌到应用进程，通过 localhost HTTP/SSE 与共享客户端通信；使用 Room/SQLite 和文档存储保存本地数据。平台差异通过 `expect/actual` 与平台接口隔离。

## 结果

优点：

- 用户无需部署中心服务器；
- 数据默认留在本地设备；
- 客户端与服务端可共享 DTO、序列化和测试基础；
- Android、Desktop、iOS 的业务行为更一致。

代价：

- 每个平台都要维护 HTTP engine、数据库 builder 和安全存储实现；
- iOS 只能在 macOS 完整编译验证；
- 进程内后台任务需要处理应用生命周期和恢复。

## 约束

新功能优先加入 KMP 主实现。旧 Python/Web 只保留为行为基线，除非任务明确要求，不进行双写维护。
