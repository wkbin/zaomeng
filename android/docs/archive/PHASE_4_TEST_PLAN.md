# Phase 4 测试计划

**阶段**: Phase 4 - 写入 API 和状态管理  
**测试范围**: 会话管理、运行管理、设置管理  
**编写时间**: 2026-08-06

---

## 测试概览

Phase 4 引入了所有写入类 API，需要验证：
1. 数据正确创建和持久化
2. 参数验证正常工作
3. 错误处理符合预期
4. 与 Python 版本 API 兼容

---

## 1. 会话管理测试

### 1.1 创建对话会话

**端点**: `POST /api/web/runs/{run_id}/dialogue/sessions`

**测试用例**:

```kotlin
@Test
fun `创建会话 - 成功`() {
    val request = CreateDialogueSessionRequest(
        mode = "observe",
        participants = listOf("角色A", "角色B")
    )
    
    val response = client.post("/api/web/runs/test_run/dialogue/sessions") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.Created, response.status)
    val result = response.body<Map<String, Any>>()
    assertNotNull(result["session_id"])
    assertEquals("observe", result["mode"])
    assertEquals(listOf("角色A", "角色B"), result["participants"])
}

@Test
fun `创建会话 - 无效模式`() {
    val request = CreateDialogueSessionRequest(mode = "invalid")
    
    val response = client.post("/api/web/runs/test_run/dialogue/sessions") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.BadRequest, response.status)
}

@Test
fun `创建会话 - 运行不存在`() {
    val request = CreateDialogueSessionRequest(mode = "observe")
    
    val response = client.post("/api/web/runs/nonexistent/dialogue/sessions") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.BadRequest, response.status)
}
```

### 1.2 更新会话标题

**端点**: `PATCH /api/web/runs/{run_id}/dialogue/sessions/{session_id}/title`

**测试用例**:

```kotlin
@Test
fun `更新标题 - 成功`() {
    val request = UpdateDialogueSessionTitleRequest(title = "新标题")
    
    val response = client.patch("/api/web/runs/test_run/dialogue/sessions/session1/title") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.OK, response.status)
    val result = response.body<Map<String, Any>>()
    assertEquals("新标题", result["title"])
}

@Test
fun `更新标题 - 标题过长`() {
    val request = UpdateDialogueSessionTitleRequest(title = "a".repeat(81))
    
    val response = client.patch("/api/web/runs/test_run/dialogue/sessions/session1/title") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.BadRequest, response.status)
}
```

### 1.3 准备对话轮次

**端点**: `POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/prepare`

**测试用例**:

```kotlin
@Test
fun `准备轮次 - 成功`() {
    val request = mapOf(
        "message" to "你好",
        "message_kind" to "dialogue"
    )
    
    val response = client.post("/api/web/runs/test_run/dialogue/sessions/session1/prepare") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.OK, response.status)
    val result = response.body<Map<String, Any>>()
    assertNotNull(result["turn_id"])
    assertEquals("你好", result["message"])
    assertEquals("pending", result["status"])
}

@Test
fun `准备轮次 - 空消息`() {
    val request = mapOf("message" to "")
    
    val response = client.post("/api/web/runs/test_run/dialogue/sessions/session1/prepare") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.BadRequest, response.status)
}
```

---

## 2. 运行管理测试

### 2.1 创建运行

**端点**: `POST /api/web/runs`

**测试用例**:

```kotlin
@Test
fun `创建运行 - 成功`() {
    val novelContent = "第一章\n这是小说内容。"
    val request = CreateRunRequest(
        novelName = "测试小说",
        novelContentBase64 = Base64.getEncoder().encodeToString(novelContent.toByteArray()),
        characters = listOf("主角", "配角")
    )
    
    val response = client.post("/api/web/runs") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.Created, response.status)
    val result = response.body<Map<String, Any>>()
    assertNotNull(result["run_id"])
    assertEquals("测试小说", result["novel_name"])
}

@Test
fun `创建运行 - 角色列表为空`() {
    val request = CreateRunRequest(
        novelName = "测试小说",
        novelContentBase64 = "dGVzdA==",
        characters = emptyList()
    )
    
    val response = client.post("/api/web/runs") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.BadRequest, response.status)
}

@Test
fun `创建运行 - 参数超出范围`() {
    val request = CreateRunRequest(
        novelName = "测试小说",
        novelContentBase64 = "dGVzdA==",
        characters = listOf("主角"),
        maxSentences = 10  // 小于 20
    )
    
    val response = client.post("/api/web/runs") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.BadRequest, response.status)
}
```

### 2.2 停止运行

**端点**: `POST /api/web/runs/{run_id}/control/stop`

**测试用例**:

```kotlin
@Test
fun `停止运行 - 成功`() {
    val response = client.post("/api/web/runs/test_run/control/stop") {
        bearerAuth(token)
    }
    
    assertEquals(HttpStatusCode.OK, response.status)
    val result = response.body<Map<String, Any>>()
    assertEquals("stopped", result["status"])
}

@Test
fun `停止运行 - 运行不存在`() {
    val response = client.post("/api/web/runs/nonexistent/control/stop") {
        bearerAuth(token)
    }
    
    assertEquals(HttpStatusCode.NotFound, response.status)
}
```

### 2.3 删除运行

**端点**: `DELETE /api/web/runs/{run_id}`

**测试用例**:

```kotlin
@Test
fun `删除运行 - 成功`() {
    val response = client.delete("/api/web/runs/test_run") {
        bearerAuth(token)
    }
    
    assertEquals(HttpStatusCode.OK, response.status)
    val result = response.body<Map<String, Any>>()
    assertEquals(true, result["deleted"])
}
```

---

## 3. 设置管理测试

### 3.1 保存模型设置

**端点**: `PUT /api/web/settings/model`

**测试用例**:

```kotlin
@Test
fun `保存设置 - 成功`() {
    val request = SaveModelSettingsRequest(
        provider = "openai",
        model = "gpt-4",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "sk-test123"
    )
    
    val response = client.put("/api/web/settings/model") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.OK, response.status)
    val result = response.body<Map<String, Any>>()
    assertEquals("openai", result["provider"])
    assertEquals("gpt-4", result["model"])
}

@Test
fun `保存设置 - provider 为空`() {
    val request = SaveModelSettingsRequest(
        provider = "",
        model = "gpt-4"
    )
    
    val response = client.put("/api/web/settings/model") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    
    assertEquals(HttpStatusCode.BadRequest, response.status)
}
```

### 3.2 获取模型设置

**端点**: `GET /api/web/settings/model`

**测试用例**:

```kotlin
@Test
fun `获取设置 - 成功`() {
    val response = client.get("/api/web/settings/model") {
        bearerAuth(token)
    }
    
    assertEquals(HttpStatusCode.OK, response.status)
    val result = response.body<Map<String, Any>>()
    assertNotNull(result["profiles"])
}
```

---

## 4. 数据持久化测试

### 4.1 会话清单持久化

```kotlin
@Test
fun `会话清单正确写入文件系统`() {
    val sessionId = sessionService.createDialogueSession(
        runId = "test_run",
        mode = "observe"
    )["session_id"] as String
    
    val manifestFile = File(storageService.getDialogueSessionsDirectory("test_run"), 
                            "$sessionId/session_manifest.json")
    assertTrue(manifestFile.exists())
    
    val manifest = Json.decodeFromString<Map<String, Any>>(manifestFile.readText())
    assertEquals(sessionId, manifest["session_id"])
    assertEquals("observe", manifest["mode"])
}
```

### 4.2 运行清单持久化

```kotlin
@Test
fun `运行清单正确写入文件系统`() {
    val runId = runService.createRun(
        novelName = "测试",
        novelContentBase64 = Base64.getEncoder().encodeToString("内容".toByteArray()),
        characters = listOf("角色")
    )["run_id"] as String
    
    val manifestFile = storageService.getRunManifestPath(runId)
    assertTrue(manifestFile.exists())
    
    val manifest = storageService.readRunManifest(runId)
    assertNotNull(manifest)
    assertEquals(runId, manifest!!.runId)
}
```

---

## 5. 兼容性测试

### 5.1 Python 版本对比

**测试方法**:
1. 启动 Python 后端
2. 启动 Ktor 后端
3. 对同一个请求，对比两个后端的响应

```kotlin
@Test
fun `会话创建响应格式与 Python 版本一致`() {
    val request = CreateDialogueSessionRequest(mode = "observe")
    
    // Python 版本响应
    val pythonResponse = pythonClient.post(...) 
    
    // Ktor 版本响应
    val ktorResponse = ktorClient.post(...)
    
    // 对比字段
    val pythonJson = pythonResponse.body<JsonObject>()
    val ktorJson = ktorResponse.body<JsonObject>()
    
    assertEquals(pythonJson.keys, ktorJson.keys)  // 字段名一致
    // 验证值类型一致
}
```

---

## 6. 错误处理测试

### 6.1 认证失败

```kotlin
@Test
fun `无 token 访问返回 401`() {
    val response = client.post("/api/web/runs") {
        // 不设置 bearerAuth
        contentType(ContentType.Application.Json)
        setBody(CreateRunRequest(...))
    }
    
    assertEquals(HttpStatusCode.Unauthorized, response.status)
}
```

### 6.2 文件系统错误

```kotlin
@Test
fun `磁盘空间不足时返回 500`() {
    // 模拟磁盘满
    // ...
}
```

---

## 7. 性能测试

### 7.1 并发创建会话

```kotlin
@Test
fun `并发创建 100 个会话`() = runBlocking {
    val jobs = (1..100).map {
        async {
            client.post("/api/web/runs/test_run/dialogue/sessions") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(CreateDialogueSessionRequest(mode = "observe"))
            }
        }
    }
    
    val responses = jobs.awaitAll()
    val successCount = responses.count { it.status == HttpStatusCode.Created }
    assertEquals(100, successCount)
}
```

---

## 执行计划

### 阶段 1: 单元测试（1-2 天）
- 实现所有服务层测试
- 覆盖率目标: 80%+

### 阶段 2: 集成测试（1-2 天）
- 实现所有 API 端点测试
- 数据持久化验证

### 阶段 3: 兼容性测试（1 天）
- 与 Python 版本对比
- 修复发现的差异

### 阶段 4: 性能测试（1 天）
- 并发测试
- 压力测试

---

## 测试工具

- **JUnit 5**: 单元测试框架
- **ktor-server-test-host**: Ktor 测试支持
- **MockK**: Kotlin mocking 库
- **Turbine**: Flow 测试库

---

**编写时间**: 2026-08-06  
**文档版本**: 1.0
