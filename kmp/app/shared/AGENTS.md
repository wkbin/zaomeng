# Shared app agent rules

本文件适用于 `kmp/app/shared/`，并继承仓库根目录 `AGENTS.md`。

## 分层

- Compose screen/composable 负责展示与用户事件，不直接访问 Ktor、Room 或平台文件系统。
- ViewModel 负责页面状态、任务生命周期和幂等合并；网络调用通过 Repository/API client。
- Repository 负责 DTO、HTTP 错误映射、SSE 事件和缓存边界，不包含界面文案布局逻辑。
- 平台能力放入对应源码集的 `actual` 实现，`commonMain` 保持平台无关。

## 状态与流式 UI

- UI state 使用不可变数据，状态更新必须基于当前 operation/session，避免旧协程覆盖新会话。
- 第一个可见对话 delta 立即刷新；后续允许短时间合批，但 complete/reset/failure 前必须处理或清空待刷新数据。
- 流式完成后用 `turn_id`/operation ID 幂等合并本轮增量，不得重新追加完整历史。
- 历史采用分页懒加载。轻量 session 的 `transcript_count` 是总数，不等于当前已加载列表长度。
- 取消发送、切换会话、重试和断线恢复必须取消旧 Job，并保留可判断结果是否已提交的信息。

## Compose

- 长列表使用稳定 key，避免在每个 token 到达时重建完整 transcript。
- 可组合函数不要执行阻塞 IO、启动无生命周期约束的协程或直接修改外部集合。
- 新增颜色、字号或间距优先复用主题 token；需要跨平台资源时使用 Compose Resources。
- 用户可见错误使用可读文案，同时保留可诊断的异常类型或状态码。

## API 契约

- 服务端 DTO 变化必须同步共享客户端模型，并为旧字段缺失提供合理默认值。
- SSE 事件至少正确处理 status、delta、reset、complete 和 failure。
- Android/JVM 平台 HTTP 流式实现不得使用已知会缓存完整 body 的路径。
- 不在客户端日志、state 或错误提示中暴露 API key、Authorization header 或完整模型提示词。

## 验证

从 `kmp/` 运行：

```powershell
.\gradlew.bat :app:shared:jvmTest :app:shared:compileKotlinJvm :app:shared:compileAndroidMain
```

涉及 Android 页面、资源或清单时追加：

```powershell
.\gradlew.bat :app:androidApp:testDebugUnitTest :app:androidApp:lintDebug :app:androidApp:assembleDebug
```

涉及 iOS `actual` 或共享 Compose API 时，需要在 macOS 验证 iOS framework 链接，或明确交由 iOS CI 验证。
