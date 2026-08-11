# 参与贡献

感谢你参与造梦。当前功能开发以 `kmp/` 为主；`src/` 和 `src/web/` 是旧 Python/Web 实现，除兼容或基线任务外不再新增功能。

## 开发环境

- Git
- JDK 17 或更高版本（推荐并由 CI 使用 JDK 21）
- Android SDK（Android 构建需要）
- Xcode（仅 iOS 构建需要）
- Python 3.11+（仅旧基线与仓库级开发检查需要）

首次构建前请阅读 [KMP 说明](kmp/README.md) 和 [代理开发规则](AGENTS.md)。依赖仓库会根据 `CI=true` 调整顺序；不要为解决本机网络问题提交仅适用于个人环境的仓库地址或代理配置。

## 工作方式

1. 从最新 `main` 创建短生命周期分支。
2. 将改动限制在一个清晰目标内；不要顺手格式化或重写无关文件。
3. 新功能优先修改 KMP 模块，并同步客户端/服务端契约和跨平台实现。
4. 为解析、存储、幂等、并发和协议变化补充回归测试。
5. 运行与改动范围匹配的检查，再提交 Pull Request。

建议分支名：`feat/<topic>`、`fix/<topic>`、`docs/<topic>`。

## 常用检查

从 `kmp/` 目录执行：

```powershell
# 服务端
.\gradlew.bat :server:jvmTest :server:compileKotlinJvm :server:compileAndroidMain

# 共享客户端
.\gradlew.bat :app:shared:jvmTest :app:shared:compileKotlinJvm :app:shared:compileAndroidMain

# Android CI 等价检查
.\gradlew.bat testDebugUnitTest jvmTest lintDebug assembleDebug

# Desktop
.\gradlew.bat :app:desktopApp:compileKotlin
```

macOS 上的 iOS 检查：

```bash
./gradlew :server:compileKotlinIosSimulatorArm64 \
  :app:shared:compileKotlinIosSimulatorArm64 \
  :app:shared:linkDebugFrameworkIosSimulatorArm64
```

仅当修改旧 Python 基线、脚本或仓库级检查时，从根目录执行：

```bash
python -m pip install -r requirements.txt
python scripts/dev_checks.py
```

所有提交前均应运行：

```bash
git diff --check
```

## 特殊改动要求

### 提示词

`prompts/**` 与 `kmp/server/src/androidMain/assets/**` 的对应文件必须同步。结构化输出改动需要同时检查调用方约束和解析器测试。

### 数据与 Room

修改实体、DAO、session manifest、文档路径或数据格式时，在 PR 中说明：

- 旧数据如何读取；
- 是否需要迁移或属于明确的破坏性升级；
- 删除、缓存失效和领域索引如何处理；
- 已运行哪些数据回归测试。

### 跨平台代码

修改 `expect` 声明时必须更新 Android、JVM、iOS 全部 `actual`。如果本机不能构建 iOS，请在 PR 中明确标注依赖 CI 验证。

## 提交与 PR

提交信息建议使用 Conventional Commits 风格，例如：

- `feat: add original source retrieval`
- `fix: stream model responses without buffering`
- `docs: record transcript archive decision`
- `test: cover truncated ndjson response`

PR 描述需要说明问题、行为变化、测试结果、兼容性和风险。UI 改动请附截图或录屏；协议/存储改动请引用对应 ADR 或补充新的 ADR。

## 安全

不要提交 API key、签名文件、密码、本地数据库或用户导出。安全问题不要放在公开 Issue，按 [SECURITY.md](SECURITY.md) 私下报告。
