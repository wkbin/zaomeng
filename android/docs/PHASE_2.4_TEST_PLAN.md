# Phase 2.4 集成测试计划

## 目标
验证 Ktor API 端点的功能正确性和与 Python 版本的兼容性。

## 测试策略

### 1. 单元测试 ✅
- PathSafetyTest (已完成)
- 验证路径安全检查功能

### 2. 集成测试 🔄
- KtorApiIntegrationTest (进行中)
- 测试所有 API 端点
- 验证响应格式
- 验证错误处理

### 3. 兼容性测试 ⏳
- Python vs Ktor 响应对比
- 性能基准测试
- 并行运行测试

## 测试用例清单

### 健康检查 API
- [x] `GET /api/web/health` 返回正常状态
- [x] 响应包含 status、backend、version 字段
- [x] 不需要认证

### 运行列表 API
- [x] `GET /api/web/runs` 需要认证
- [x] 未认证返回 401
- [x] 返回数组格式
- [x] `GET /api/web/runs/{id}` 不存在返回 404
- [x] `GET /api/web/runs/{id}/dialogue/sessions` 不存在返回 404
- [x] `GET /api/web/runs/{id}/chapters` 不存在返回 404

### 诊断 API
- [x] `GET /api/web/diagnostics/export` 需要认证
- [x] 返回完整诊断报告
- [x] 报告包含 kind、schemaVersion、generatedAt 等字段
- [x] 响应头包含 Content-Disposition

## 测试环境

### 需要
- Java 运行环境
- Ktor Server Test 依赖
- 测试存储目录

### 可选
- Python 后端（用于对比测试）
- 性能分析工具

## 测试执行

### 运行测试
```bash
./gradlew :app:testDebugUnitTest --tests "top.wkbin.zaomeng.ktor.*"
```

### 查看报告
```bash
open app/build/reports/tests/testDebugUnitTest/index.html
```

## 预期结果

### 成功标准
- ✅ 所有测试用例通过
- ✅ 无编译错误
- ✅ 响应格式符合预期
- ✅ 错误处理正确

### 失败处理
- 记录失败原因
- 修复代码或测试
- 重新运行验证

## 下一步测试

### Phase 2.4 完整版
1. **集成测试** (当前)
   - 基础 API 端点测试
   - 错误场景测试
   - 边界条件测试

2. **兼容性测试** (计划中)
   - Python vs Ktor 并行启动
   - 相同请求对比响应
   - 性能差异分析

3. **端到端测试** (计划中)
   - 真实数据测试
   - 完整流程测试
   - 压力测试

## 当前状态

```
测试类型           完成度
─────────────────────────
单元测试          ████████████████████ 100%
集成测试          ██████████░░░░░░░░░░  50%
兼容性测试        ░░░░░░░░░░░░░░░░░░░░   0%
端到端测试        ░░░░░░░░░░░░░░░░░░░░   0%
─────────────────────────
总进度           ████████░░░░░░░░░░░░  40%
```

## 限制和已知问题

### 当前限制
1. **Java 环境**: 测试需要 Java 运行环境
2. **测试数据**: 没有真实的测试数据
3. **Python 对比**: 需要 Python 后端运行

### 已知问题
- 测试环境配置复杂
- 需要真实存储目录
- 性能测试数据不足

## 备注

虽然 Java 环境当前不可用，但测试代码已编写完成。一旦环境配置好，可以立即运行验证。

---

**创建时间**: 2026-08-07  
**状态**: 进行中  
**完成度**: 40%
