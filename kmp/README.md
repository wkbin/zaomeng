<p align="center">
  <img src="../docs/images/zaomeng_logo.png" alt="造梦" width="120">
</p>

# 造梦 KMP 客户端

Android / 桌面 / iOS 三端共享的 Compose Multiplatform 应用，内嵌 Ktor + Room 后端，数据全部保存在本机，无需部署中心服务器。

> ⚠️ **升级提示（重要）**
>
> 2.0.0 与旧版 Android（1.5.0 及更早）数据**不兼容**：本地数据不会自动迁移，安装 2.0.0 后旧书卷/会话将不可见。
> **升级前请先在旧版中导出书卷包（`.zaomeng-run.zip`）备份**，安装 2.0.0 后通过“导入书卷包”恢复。

## 平台

| 平台 | 模块 | 说明 |
| --- | --- | --- |
| Android | `app/androidApp` | 主入口，APK 分发（当前仅 `arm64-v8a`） |
| 桌面 | `app/desktopApp` | Compose Desktop（Windows / macOS / Linux） |
| iOS | `iosApp` + `app/shared` | Xcode 工程，构建脚本自动生成 shared framework |

## 技术栈

- Compose Multiplatform、Material 3、Navigation3、ViewModel
- Ktor：内嵌本地服务端 + HTTP 客户端
- Room 3（SQLite 持久化）、DataStore（偏好存储）
- Koin（DI）、Paging 3（分页）
- kotlinx.serialization / coroutines / datetime、okio
- FileKit（文件选择）、KiteArchive（ZIP）、snakeyaml-engine-kmp（YAML）

## 模块结构

```
kmp/
├── app/
│   ├── shared/          # 导航、App、ViewModel/DI 装配与平台入口
│   ├── androidApp/      # Android 入口
│   └── desktopApp/      # 桌面入口
├── core/
│   ├── contracts/       # 跨层 DTO、稳定契约和纯值类型
│   ├── domain/          # UseCase 与 Gateway 契约
│   └── runtime/         # 嵌入式后端、安全存储与流式 HTTP 等运行时抽象
├── data/
│   ├── remote/          # Ktor client、SSE、下载、更新检查与偏好存储
│   └── repository/      # 应用 Repository 实现与 Gateway 适配
├── feature/             # 各业务 Screen/ViewModel 独立模块
├── ui/
│   └── shared/          # 主题、跨平台 UI 辅助与共享展示模型
├── server/              # 内嵌 Ktor 聚合：路由 / 业务服务 / 插件 / 平台入口
│   ├── storage/         # Room、StorageService、PathSafety
│   ├── llm/             # LlmClient、提示词构建与响应解析
│   └── http/            # HTTP 插件、SSE 编码器与基础路由
├── plugins-api/         # 插件接口契约
├── builtin-plugins/     # 内置插件实现
└── iosApp/              # iOS Xcode 工程（独立于 Gradle 构建）
```

## 功能

- 书卷：导入（TXT / EPUB / 书卷包）、蒸馏、增量蒸馏、导出书卷包
- 会话：搜索、多选删除、场景推荐、自动标题
- 聊天：SSE 流式、断线恢复、分支、@ 提及、读心、群聊旁观
- 卡库：场景卡 / 自设卡 / 开局模板
- 人物：资料校对、AI 补全、头像裁剪、关系图谱、世界时间线
- 在线书库：社区书卷包浏览与下载
- 插件：内置插件、声明式第三方插件管理，以及无需编写 JSON 的插件工坊（制作、安装试用、ZIP 导出）
- 设置：模型配置、主题（深浅 / 动态取色）、字号与紧凑模式、应用更新

## 构建与运行

需要 JDK 17+（推荐 21）与 Android SDK。

```powershell
# Android debug APK
.\gradlew.bat :app:androidApp:assembleDebug

# 桌面端
.\gradlew.bat :app:desktopApp:run

# JVM 单测
.\gradlew.bat :server:storage:jvmTest :server:llm:jvmTest :server:http:jvmTest :server:jvmTest :app:shared:jvmTest
```

iOS：用 Xcode 打开 `iosApp/iosApp.xcodeproj`，构建脚本会自动调用 Gradle 生成 shared framework。

## 截图

| 手机端 | 桌面端 |
| --- | --- |
| <img src="../docs/images/mobile.jpg" width="240" alt="手机端"> | <img src="../docs/images/desktop.png" width="480" alt="桌面端"> |
