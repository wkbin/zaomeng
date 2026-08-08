# 造梦 Android

造梦 Android 内置 Kotlin Ktor 服务端（`:server` 模块），业务接口只监听设备回环地址，书卷、人物和会话数据保存在 App 私有目录中，不需要部署中心服务器。模型推理仍会按用户配置访问对应的模型供应商；除这类模型请求外，Android 界面与 Ktor 服务之间的通信都在手机本机完成。

## 运行架构

1. `ZaomengApplication` 通过 Koin 初始化依赖注入；Ktor 服务端代码位于独立 `:server` Android library module（routes/services/models/plugins/认证）。
2. `KtorBackendController` 在随机 `127.0.0.1` 端口启动内嵌 Ktor (CIO) 服务，数据根目录设为 App 私有目录；服务端日志由 `CallLogging` 和 `StatusPages` 统一处理。
3. 每次安装生成的本机接口 Token 由 Android Keystore 保护。健康检查通过后，`LocalApiFactory` 才会按实际端口创建 Retrofit/OkHttp 或 Ktor Client 并附加认证信息。
4. Compose 界面采用 ViewModel 和 Repository 分层；Koin 提供 Repository、ViewModel、本机后端控制器及其他依赖。
5. Navigation Compose 使用类型安全路由连接书架、导入、模型设置、书卷详情、人物资料、关系校对、卡片库、增量蒸馏、会话列表和聊天页面。
6. Preferences DataStore 保存导入默认人物、自动蒸馏选项、聊天字号、紧凑显示模式以及最近访问的书卷和会话等轻量偏好；敏感的本机接口 Token 不存入 DataStore。
7. 长时间蒸馏由 Android 前台服务监控，在通知中显示进度并可直接停止全部任务；结束、停止或失败时会发送结果通知，点击可回到书架。进程意外退出后，遗留任务会在下次启动时标记为已中断，并在书架提示可从未完成人物继续蒸馏，避免一直停留在运行中。

对话、审校等 LLM 功能使用 `prompts/` 目录下的提示词 YAML 配置，该目录已打包进 APK 的 assets 供真机读取；`builtin_novels/` 仅供 Web 端使用，不会打进 Android APK。

## 已实现功能

- 模型设置：常用服务商内置接口地址，只需选择模型并填写 API Key；自定义兼容接口仍可手动填写服务商、模型和地址。
- 应用更新：设置页会检查 GitHub Release 正式版；发现新版后可通过系统下载器下载 `arm64-v8a` APK，并由系统通知引导安装。
- 导入：从系统文件选择器导入 UTF-8、UTF-16 或 GB18030 编码的 TXT 小说，以及本机提取正文的 EPUB；也可恢复 `.zaomeng-run.zip` 书卷包。可以立即蒸馏，也可以在未配置模型时先保存为“待蒸馏”书卷。
- 书架与书卷详情：查看书卷、正文来源历史、蒸馏状态和进度；停止任务、删除书卷、按原人物重跑，或换入新章节继续增量蒸馏。
- 书卷导出：通过系统文件选择器流式导出 `.zaomeng-run.zip` 书卷包，避免大包整体常驻内存。
- 人物资料：校对完整人物字段、查看质量报告，并用 AI 补全缺失字段。
- 人物关系：查看冲突提示，调整四项关系数值、关系类型、变化、冲突点和典型互动。
- 可复用卡片：创建、编辑、生成和删除场景卡、自设卡与开场模板；会话创建时可以直接选用。
- 会话：查看全局会话或指定书卷的会话，按书名、人物或最近消息搜索，并按最近活跃或书名排序；支持创建三种模式的会话、按当前筛选结果多选批量删除，并可让系统推荐场景。
- 聊天：SSE 流式显示人物回复；支持记录搜索、消息复制、重新生成、从消息处分支、人物 `@` 提及、回到底部和新消息提示。发送使用持久化操作 ID，断线重试会恢复同一次生成而不会重复追加回复；同时支持三类消息、高级导演工具和可调字号/紧凑显示。
- 高级会话状态：查看并跳转分支图，检查一致性、人物状态弧线、发言节奏、关系时间线和近期事件信号。
- 恢复与稳定性：冷启动会验证并恢复最近书卷或会话；待处理轮次可恢复，发送结果不确定时先核对服务端状态，创建开场失败不会留下空会话。

## 构建

需要 JDK 17+（如 Android Studio 自带的 JBR）与 Android SDK。命令行构建：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

Debug APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

## 当前限制

- EPUB 仅提取未加密 EPUB 中的 XHTML/HTML 正文，不支持 DRM 加密文件或复杂版式还原。
- Android 客户端当前接受最大 24 MB 的 TXT，以及最大 64 MB 的压缩书卷包；书卷包展开后的安全限制由内嵌服务端继续校验。
- 常规书卷导出会包含聊天记录和章节草稿；发布为内置书卷时会自动排除聊天记录，避免带入个人创作数据。
- Android 13 及以上建议允许通知权限，以便在通知栏持续看到后台蒸馏进度；拒绝通知不会阻止用户在前台启动任务。
- 当前发布包只包含 `arm64-v8a`，适用于主流 64 位 Android 真机，不包含 `x86_64` 模拟器运行时。
