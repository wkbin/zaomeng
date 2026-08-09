# 快速启动指南 - Python to Ktor 迁移

## 当前状态

**分支**: `migrate-python-to-ktor`  
**进度**: 82%（Ktor 唯一后端，Retrofit 兼容桥清理中）
**状态**: 已移除 Chaquopy/Python 运行时，继续完成 Retrofit API 迁移

服务端模块：`android/server`（Ktor routes/services/models/plugins/utils）
服务端请求日志：使用 Logcat 过滤 `io.ktor` 或 `top.wkbin.zaomeng` 查看请求状态和异常堆栈。

## 已完成阶段

- ✅ Phase 0: 准备阶段 (100%)
- ✅ Phase 1: 核心基础设施 (100%)
- ✅ Phase 2: 只读 API (100%)
- ✅ Phase 3: LLM 集成 (100%)
- ✅ Phase 4: 写入 API 和状态管理 (100%)
- ✅ Phase 5: 流式响应和实时功能 (100%)
- ✅ Phase 6: 高级功能 (100%)

## 下一步：Phase 7-8 - 兼容性和客户端迁移

### 任务概览

```
Phase 7-8: 兼容性和客户端迁移
├── 4.1 会话管理
│   ├── POST /api/web/runs/{run_id}/dialogue/sessions（创建会话）
│   └── PATCH /api/web/runs/{run_id}/dialogue/sessions/{session_id}/*（更新会话）
├── 4.2 对话轮次写入
│   ├── POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/turns（写入用户输入）
│   └── Manifest 更新逻辑
├── 4.3 运行管理
│   ├── POST /api/web/runs（创建新运行）
│   ├── PATCH /api/web/runs/{run_id}/control（停止运行）
│   └── 实现中断恢复机制
├── Ktor 服务图和 Koin 注入（已完成）
├── Retrofit API 客户端迁移（按 API 分组进行中；模型设置和插件管理已迁移）
└── Ktor 默认后端切换（待完成兼容性验证）
```

## 快速命令

### 查看项目状态
```bash
# 查看当前分支和提交
git log --oneline -5

# 查看与 main 的差异
git diff main..HEAD --stat

# 查看迁移计划
cat MIGRATION_PLAN.md

# 查看项目状态
cat docs/PROJECT_STATUS.md
```

### 继续开发
```bash
# 确保在正确的分支
git checkout migrate-python-to-ktor

# 查看最新状态
git status

# 开始 Phase 3 工作
# (继续在当前分支上开发)
```

### 测试和构建
```bash
# 编译项目
./gradlew :app:androidApp:assembleDebug

# 运行测试
./gradlew :app:shared:allTests :server:jvmTest

# 查看测试报告
open app/build/reports/tests/testDebugUnitTest/index.html
```

## 关键文件位置

### 核心代码
```
app/src/main/java/top/wkbin/zaomeng/
├── backend/
│   └── BackendManager.kt              # 后端管理器（Python/Ktor 切换）
└── ktor/
    ├── KtorBackendController.kt       # Ktor 主控制器
    ├── plugins/Security.kt            # 认证插件
    ├── routes/                        # API 路由
    │   ├── HealthRoute.kt
    │   ├── RunsRoute.kt
    │   └── DiagnosticsRoute.kt
    ├── services/                      # 业务服务
    │   ├── PathSafety.kt
    │   ├── StorageService.kt
    │   └── DiagnosticsService.kt
    └── models/
        └── DataModels.kt              # 数据模型
```

### 测试代码
```
app/src/test/java/top/wkbin/zaomeng/ktor/
├── PathSafetyTest.kt                  # 路径安全测试
└── KtorApiIntegrationTest.kt          # 集成测试
```

### 配置文件
```
prompts/                               # 提示词配置
├── dialogue.yaml
├── distillation.yaml
├── review.yaml
└── README.md

app/build.gradle.kts                   # 构建配置（Ktor 依赖）
```

### 文档
```
android/
├── MIGRATION_PLAN.md                  # 完整迁移计划
├── README_MIGRATION.md                # 项目 README
├── QUICK_START.md                     # 本文件
└── docs/
    ├── PROJECT_STATUS.md              # 实时状态
    ├── PHASE_0_SUMMARY.md             # 各阶段总结
    ├── PHASE_1_SUMMARY.md
    ├── PHASE_2.2_SUMMARY.md
    ├── PHASE_2_COMPLETE_SUMMARY.md
    ├── PHASE_2.4_TEST_PLAN.md
    ├── DAILY_LOG_2026-08-06.md        # 工作日志
    └── DAILY_LOG_2026-08-07.md
```

## Phase 3 实现指南

### 3.1 模型 API 密钥管理

**目标**: 从 Android Keystore 读取 API 密钥并注入到 Ktor 服务

**参考 Python 代码**:
- `src/web/auth/api_keys.py` - API 密钥管理

**需要实现**:
1. `ModelApiKeyService.kt` - 从 Keystore 读取密钥
2. 密钥注入到 HTTP 客户端
3. 测试密钥读取逻辑

**预计代码量**: ~100 行

### 3.2 HTTP 客户端配置

**目标**: 配置 ktor-client 调用 LLM API

**参考 Python 代码**:
- `src/web/llm/client.py` - LLM 客户端

**需要实现**:
1. `LlmClient.kt` - HTTP 客户端配置
2. 重试逻辑（类似 Python 的 `tenacity`）
3. 超时控制
4. 流式响应支持

**需要的依赖**:
```kotlin
// 已在 build.gradle.kts 中
implementation("io.ktor:ktor-client-core:$ktor")
implementation("io.ktor:ktor-client-okhttp:$ktor")
```

**预计代码量**: ~200 行

### 3.3 提示词系统集成

**目标**: 从 YAML 加载提示词并渲染模板

**参考 Python 代码**:
- `src/web/prompts/builders.py` - 提示词构建器

**需要实现**:
1. `PromptLoader.kt` - 从 YAML 加载提示词
2. `PromptBuilder.kt` - 模板渲染（变量替换）
3. 测试提示词加载

**预计代码量**: ~150 行

### 3.4 实现简单对话端点

**目标**: 实现基础对话功能

**参考 Python 代码**:
- `src/web/chat/endpoints.py` - 对话端点

**需要实现**:
1. `DialogueRoute.kt` - 对话 API 路由
2. `DialogueService.kt` - 对话业务逻辑
3. 调用 LLM API
4. 保存对话轮次

**API 端点**:
```
POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply
{
  "content": "用户输入",
  "stream": false
}
```

**预计代码量**: ~250 行

## 注意事项

### Ktor 版本
当前使用 **Ktor 3.5.2**

如果需要升级到 3.5.2：
```kotlin
// gradle/libs.versions.toml
ktor = "3.5.2"
```

### Java 环境要求
**重要**: 编译需要 Java 环境
- 需要设置 `JAVA_HOME` 环境变量
- 推荐使用 JDK 17 或更高版本
- 验证安装：`java -version`
- 编译命令：`./gradlew :app:androidApp:compileDebugKotlin`

### Phase 3 已知问题
1. **API 密钥集成待完善**
   - `StorageService.getApiKey()` 返回 null（占位实现）
   - 需要集成 `ModelApiKeyStore` 从 Android Keystore 读取密钥
   
2. **编译验证**
   - Phase 3 代码已完成但未编译验证（缺少 Java 环境）
   - 需要配置 Java 环境后运行编译验证

### 编译错误修复（已完成）

**Phase 2 修复**:
1. `EmbeddedServer` 类型不匹配 ✅ 已修复
   - 修改 `server` 变量类型为完整的 `EmbeddedServer` 类型
   
2. `JsonElementSerializer` 访问权限 ✅ 已修复
   - 使用公共的 `JsonElement` 类型替代内部序列化器
   
3. 内嵌服务引擎改用 CIO，避免 Android API 26 字节码问题 ✅ 已完成
   - 添加 `packaging {}` 配置排除重复的 META-INF 文件

**Phase 3 修复**（2026-08-06）:
1. DialogueRoute 参数缺失 ✅ 已修复
   - LlmClient 实例化时添加必需的 context、modelApiKeyService、storageService 参数
   
2. DialogueService 类型错误 ✅ 已修复
   - 使用 `ChatMessage` 替代不存在的 `Message` 类型
   - 调整 `chatCompletion` 方法调用参数
   
3. LlmClient 访问私有字段 ✅ 已修复
   - StorageService 添加 `getStorageRoot()` 公共方法
   
4. StorageService.loadModelSettings() 类型不匹配 ✅ 已修复
   - 适配新的 ModelSettings 数据结构（activeProfileId + profiles）
   
5. KtorBackendController context 访问错误 ✅ 已修复
   - 将 androidContext 作为参数传递给 configureKtorApp

**详细修复记录**: 查看 `COMPILATION_FIXES.md`

### Python 服务启动问题修复

**问题**: "No module named 'prompts'"

**原因**: `prompts/` 目录没有被同步到 Android 应用的 Python 环境

**修复**: 已更新 `app/build.gradle.kts` 中的 `syncSharedPythonSources` 任务，添加了 `prompts/**`

**操作**: 在 Android Studio 中重新构建项目（Build -> Rebuild Project）

### Python 代码参考

关键的 Python 文件位于：
```
../../zaomeng/src/web/
├── auth/api_keys.py           # API 密钥管理
├── llm/client.py              # LLM 客户端
├── prompts/builders.py        # 提示词构建
└── chat/endpoints.py          # 对话端点

../../zaomeng/prompts/          # 提示词配置（已提取）
├── dialogue.yaml
├── distillation.yaml
├── review.yaml
└── README.md
```

## 开发流程建议

### 每日工作流程
1. **开始工作**
   - 查看 `QUICK_START.md`（本文件）
   - 回顾 `MIGRATION_PLAN.md`
   - 查看上一次的工作日志

2. **开发过程**
   - 实现一个子任务
   - 编写对应的测试
   - 提交代码（清晰的 commit message）
   - 更新文档

3. **结束工作**
   - 更新 `MIGRATION_PLAN.md`
   - 创建今日工作日志
   - 提交所有改动
   - 推送到远程（可选）

### Commit Message 规范

遵循 Conventional Commits：
```
feat(ktor): 实现 LLM 客户端配置
test(ktor): 添加提示词加载测试
docs: 更新 Phase 3 进度
fix(ktor): 修复类型不匹配问题
```

### 代码质量标准
- ✅ 编译通过（无错误）
- ✅ 测试覆盖核心功能
- ✅ 代码格式化
- ✅ 文档注释
- ✅ 与 Python 版本兼容

## 预期成果（Phase 3 完成后）

### 代码产出（已完成）
- `ModelApiKeyService.kt` (~92 行) ✅
- `LlmClient.kt` (~270 行) ✅
- `PromptLoader.kt` (~117 行) ✅
- `DialogueService.kt` (~239 行) ✅
- `DialogueRoute.kt` (~103 行) ✅
- `DialogueModels.kt` (~76 行) ✅

**总计**: ~897 行

### API 端点（已实现）
- `POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply` ✅

### 依赖添加
- `ktor-client-okhttp` (HTTP 客户端引擎) ✅
- `snakeyaml` (YAML 解析) ✅

### 文档
- Phase 3 更新至 MIGRATION_PLAN.md ✅
- QUICK_START.md 更新 ✅

### 进度
- Phase 3: 100% ✅
- 总进度: 33% → 44% ✅

## Phase 4 预期成果

### 代码产出（预计）
- `SessionManagementService.kt` (~200 行)
- `SessionManagementRoute.kt` (~150 行)
- `TurnWriteService.kt` (~180 行)
- `RunManagementService.kt` (~250 行)
- `RunManagementRoute.kt` (~150 行)
- `SettingsService.kt` (~120 行)
- `SettingsRoute.kt` (~100 行)
- 测试代码 (~300 行)

**总计**: ~1,450 行

### API 端点（预计）
- `POST /api/web/runs/{run_id}/dialogue/sessions`
- `PATCH /api/web/runs/{run_id}/dialogue/sessions/{session_id}/*`
- `POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/turns`
- `POST /api/web/runs`
- `PATCH /api/web/runs/{run_id}/control`
- `GET/PUT /api/web/settings/model`

### 进度（预计）
- Phase 4: 100%
- 总进度: 44% → 56%

## 联系和参考

### 关键决策
- **Ktor 版本**: 3.5.2
- **认证方式**: Bearer Token
- **数据格式**: JSON (kotlinx.serialization)
- **测试框架**: JUnit + ktor-server-test

### 架构原则
- 三层架构（Controller - Service - Data）
- 依赖注入
- 单一职责
- 100% Python API 兼容

## 问题和建议

如有疑问，参考：
1. `MIGRATION_PLAN.md` - 完整计划
2. `docs/PROJECT_STATUS.md` - 当前状态
3. Phase 总结文档 - 已完成工作的详细说明
4. Python 源代码 - 业务逻辑参考

---

**最后更新**: 2026-08-07  
**更新人**: Migration Team  
**版本**: 1.0

🚀 准备好了！继续冲刺 Phase 3！
