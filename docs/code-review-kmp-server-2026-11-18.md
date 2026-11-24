# 代码审查报告 · 批次 A：kmp/server

- 审查工具：alibaba/open-code-review（`ocr` v1.13.10）+ 委派模式（宿主模型执行，规则由 OCR 规则引擎解析）
- 审查时间：2026-11-18
- 审查范围：`kmp/server`（174 个可审查文件，其中 131 个非测试 Kotlin 源文件）
- 审查方式：OCR 文件选择 + 规则清单（`ocr rules check`，命中内置 `**/*.{kt,kts}` 规则）→ 全量风险模式扫描（131 个源文件）× 高价值文件逐行深读

## 覆盖度

| 项目 | 数量 |
| --- | --- |
| 模块可审查文件 | 174 |
| 模式扫描覆盖源文件 | 131（100%） |
| 逐行深读文件 | 13（约 5,400 行） |
| 交叉校验（提示词打包一致性） | `prompts/**` ↔ `kmp/server/src/androidMain/assets/**` |

深读文件：`DocumentStore.kt`、`DomainStore.kt`、`DocumentDao.kt`、`DomainDao.kt`、`DomainEntities.kt`、`ZaomengDatabase.kt`、`StorageService.kt`、`PathSafety.kt`、`SimpleLock.kt`、`PlatformSupport.jvm.kt`、`LlmClient.kt`、`DialogueStreamService.kt`、`RunPackageService.kt`。

## 结论

**0 critical / 4 high / 9 medium / 5 low**

`PathSafety` 与 `RunPackageService` 的路径穿越防护做得比较扎实（zip-slip、Windows 保留名、深度/字节配额都有），主要风险集中在**持久化层的一致性语义**、**跨平台线程模型**和**数据同步的写放大**。

---

## High

### 1. 破坏性迁移会让所有用户数据在未来任意一次版本升级时被清空

`kmp/server/storage/src/commonMain/kotlin/top/wkbin/zaomeng/db/ZaomengDatabase.kt:45`

```kotlin
.fallbackToDestructiveMigration(dropAllTables = true)
```

注释说明这是 v1（documents-only）→ v2 的一次性取舍，可以理解；但 `fallbackToDestructiveMigration` 是**没有版本上限**的：从现在起每次改动 `@Database(version = ...)` 而忘记补 `Migration`，Room 都会直接 dropAllTables，把 `documents` 表里的书卷、人物档案、会话全文一起丢掉，而且用户侧没有任何提示。

这同时违反了 `AGENTS.md`「数据结构或 Room schema 变化必须提供相应迁移或明确的破坏性升级策略，并补充测试。不要静默丢弃用户数据」。

建议：v2 起改为显式迁移。若确实要保留 v1 的兜底，用 `addMigrations(MIGRATION_1_2)` 覆盖 1→2，删除全局 destructive 兜底；或至少用 `fallbackToDestructiveMigrationFrom(1)` 只对 v1 生效，让后续版本升级直接 crash 暴露问题而不是静默清库。Schema JSON（`schemas/.../2.json`）已在仓库里，具备写迁移的条件。

### 2. `RoomDocumentStore.rename` 非原子、无事务，失败会留下半套数据

`kmp/server/storage/src/commonMain/kotlin/top/wkbin/zaomeng/db/DocumentStore.kt:171-197`

```kotlin
val moves = runBlockingPlatform { ... }
if (moves.isEmpty()) return false
runBlockingPlatform {
    moves.forEach { (from, bytes) -> dao.upsert(DocumentEntity(to, bytes, now)) }
    moves.forEach { (from, _) -> dao.delete(from) }
}
```

三个问题：

1. 写目标与删源是两个独立事务，中途异常（磁盘满、进程被杀）会得到**新旧两份数据并存**，或只搬了一半；`catch` 只返回 `false`，调用方无从判断哪些已生效。
2. `dao.bytesOf(it) ?: ByteArray(0)` 在行缺失时静默写入空字节，把「读不到」变成「长度为 0 的合法文件」，比失败更难排查。
3. 文件系统后端用 `atomicMove`，Room 后端语义完全不同，但共享同一个 `rename` 契约（`StorageService.rename` 只判断布尔），跨后端行为不一致。

建议：包进 `db.withTransaction { }`（Room 3 提供 `withTransaction`），失败回滚并抛出；读不到的源行直接跳过而不是写空字节；如果目录迁移量可能很大，按批次分批搬并在 metadata 中记录迁移进度。

### 3. `SimpleLock` 用 `runBlocking` + 非重入 `Mutex`，存在死锁与阻塞执行体的风险

`kmp/server/storage/src/commonMain/kotlin/top/wkbin/zaomeng/platform/SimpleLock.kt:11`

```kotlin
fun <T> withLock(block: () -> T): T = runBlocking { mutex.withLock { block() } }
```

1. **非重入**：`StorageService.lockFor()` 把任意路径按 `hashCode % 64` 映射到 64 个锁。一个线程持有 `lockFor(pathA)` 期间若再写一个恰好落到同一槽位的 `pathB`，`runBlocking` 会永久等待自己，**死锁**（概率约 1/64，且随嵌套写入点增多而放大）。当前 `updateSessionManifest` 的 transform 里没有写操作，所以还没踩到，但这是一个随时会被新代码触发的结构性隐患。
2. **阻塞执行体**：`runBlocking` 会阻塞当前线程。若该调用发生在主线程/单线程 dispatcher（iOS 主循环、Compose 主线程），另一个协程持有的锁永远无法被释放，同样是永久死锁；在 Android 上则直接是 ANR 风险。
3. 外层协程被取消时 `runBlocking` 不会中止，锁可能被一个已取消的请求一直持有。

建议：把 `SimpleLock` 改成 `suspend fun <T> withLock(...)`（`mutex.withLock` 本身可重入性也不满足，需要 `Mutex` + 显式不可重入使用约束），或者在同步 API 上改用平台 `synchronized`/`NSLock` 这类**同线程重入安全**的锁；`lockFor` 改成 `Map<Path, SimpleLock>`（配 LRU 上限）避免哈希槽位碰撞。至少要加一条「临界区内不得再取锁」的注释与断言。

### 4. JVM 端 YAML 解析器是共享单例，SnakeYAML 非线程安全

`kmp/server/storage/src/jvmMain/kotlin/top/wkbin/zaomeng/platform/PlatformSupport.jvm.kt:9`

```kotlin
private val jvmYamlParser = Yaml(SafeConstructor(LoaderOptions()))
```

`Yaml` 实例并非线程安全（官方明确 `Yaml` 不是 thread-safe，内部有可变状态）。它被 `parseYaml` 全局复用，而 `DomainStore.frontmatterNameOf`、人物档案解析等会在多个 Ktor 请求线程/IO 线程上并发调用，可能出现解析结果错乱或 `ConcurrentModificationException` 之类的不确定故障——这类问题偶发、难复现，属于典型的高排查成本缺陷。

建议：改为 `ThreadLocal<Yaml>`，或每次解析 `Yaml(SafeConstructor(LoaderOptions()))`（构造开销很小，YAML frontmatter 通常几十行）；`SafeConstructor` 保留，避免任意对象反序列化。

---

## Medium

### 5. 级联删除跨 4 张表，但没有事务

`kmp/server/storage/src/commonMain/kotlin/top/wkbin/zaomeng/db/DomainStore.kt:269-283`

`deleteRunCascade` / `deleteSessionCascade` 连续调用 4 个 / 2 个 DAO 方法，每个都是独立事务。中途崩溃会留下「runs 没了、messages 还在」的孤儿行，之后按 run 统计或检索会出现幽灵数据。建议 `withTransaction` 包裹，与第 2 条一并改造。

### 6. 每写一次 session_manifest 就整窗重灌 messages（写放大）

`DomainStore.kt:177-195` + `DocumentDao/DomainDao.upsertMessages`

`syncMessages` 先 `deleteMessagesFrom(startSeq)`，再把 manifest 里的整个 transcript 窗口（阈值 120 条）全量 `upsertMessages`。会话每回合都会写 manifest，等于每回合重写最多 120 行 + 一次范围删除；配合 `MessageEntity` 的 `autoGenerate` 主键与 `(runId,sessionId,seq)` 唯一索引 + `REPLACE` 策略，每次冲突都是删旧行插新行，SQLite 页碎片与 WAL 增长都会明显。

建议：只对 `seq` 超出已存在范围的增量做 insert；或让 `seq` 参与复合主键（`PrimaryKey(runId, sessionId, seq)`）去掉自增 id，配合 `OnConflictStrategy.REPLACE` 实现真正的原地更新语义。

### 7. 列表接口 N+1 + 整个 BLOB 只为取文件大小

`StorageService.kt:574-596`、`DocumentStore.kt:199-200`

`withCharacterAvatars` 对每个参与者调 `avatarVersion`，后者做 `isFile` → `updatedAtMillis` → `fileSize` 三次查询；而 `RoomDocumentStore.fileSize` 是 `dao.bytesOf(...)?.size`，**把整张头像 PNG 读进内存只为拿一个数字**。`listDialogueSessions` 会对每个会话都走一遍，50 个会话 × 5 个角色 ≈ 750 次查询 + 750 次 blob 解码，全部在同步 `runBlocking` 里。

建议：`DocumentEntity` 增加 `size` 列（写入时已知 `bytes.size`），或让 DAO 提供 `SELECT length(bytes)`；`avatarVersion` 合并成单条 `SELECT updated_at, length(bytes)`。

### 8. 重试只覆盖 `RetryableException`，网络层错误不重试

`LlmClient.kt:814-835`

`retryWithBackoff` 只 catch 自定义的 `RetryableException`（由 5xx/429/408 状态码构造）。真正的瞬时故障——连接重置、`SocketTimeoutException`、DNS 抖动——会直接抛出，不会退避重试。同时第 834 行 `return block()` 在逻辑上不可达（循环内要么 return 要么 throw），属于死代码，容易让后续维护者误以为还有一次兜底尝试。

建议：把 `IOException`/`TimeoutException`/`CancellationException`（后者必须重新抛出）分开处理，对前两者按同一策略重试；删掉不可达的 `return block()`。

### 9. 流式接口没有切换 IO dispatcher，取决于调用方线程

`LlmClient.kt:737-793`（Flow 版本）、`DialogueStreamService.kt:113`

`chatCompletionStream` 内部通过 `openStreamingHttpPost` 做**阻塞式逐行读**，但 Flow 没有 `flowOn(Dispatchers.IO)` 之类的约束（`LlmClient` 甚至没接 IO dispatcher 抽象）；`DialogueStreamService` 里直接 `.collect { }`。当前从 Ktor 路由调用时落在 IO 线程上没问题，但如果将来从 ViewModel / `viewModelScope`（Main）里直接收集，就会阻塞主线程。

建议：在 Flow 构建器内 `withContext(platformIoDispatcher)` 包住 socket 读取，或在类内固定一个 IO 上下文，让「阻塞读写必须在 IO 线程」成为不依赖调用方的类内保证（AGENTS.md 第 4 条）。

### 10. 书卷包解压全量进内存，移动端 OOM 风险

`RunPackageService.kt:61-88`

`readZipEntries(bytes)` 一次性把 128MB 包的全部条目解成 `ByteArray` 放在内存里，累计上限 512MB，随后才逐个落盘。Android 设备上这条路很容易 OOM（尤其是 `MAX_ENTRY_BYTES = 64MB` 的单个条目）。

建议：改为流式 `InputStream` → 逐条 `ZipInputStream` 落盘 + 校验，或把 `MAX_TOTAL_BYTES` 降到与移动端内存预算相符的量级。

### 11. 失败清理可能删掉「不是本次创建」的目录

`RunPackageService.kt:32-58`

第 38 行 `check(!storage.exists(target))` 若失败会在 `catch` 里执行 `deleteRecursively(target)`，把**已存在的那个目录**删掉。虽然 `newRunId` 是随机 UUID，碰撞概率极低，但这是「清理范围大于创建范围」的典型模式，一旦触发就是不可逆的数据删除。

建议：用一个 `createdTarget` 标记（或把 `exists` 检查移到 `try` 之前），只删除本次真正创建出来的目录。

### 12. 任何写入都会把字节全量解码成 UTF-8 做路径匹配

`DomainStore.kt:47-49`

`onWrite` 对每次写入都 `bytes.decodeToString()` 再走 4 个正则，哪怕写的是二进制（头像 PNG、zip）。`StorageService.writeBytesLocked` 是所有写入的唯一通道，所以这层开销是全局的：一次 5MB 头像写入会额外产生一份 5MB 的字符串并 GC。

建议：先按路径字符串判断是否属于需要同步的业务路径（`run_manifest.json`、`session_manifest.json`、`card.json`、`PROFILE*.md`），命中后才解码；或给 `writeBytesLocked` 加一个 `syncDomain: Boolean` 参数，由 `writeBinary` 走 false。

### 13. 提示词源与打包资产已经漂移

证据：`kmp/server/src/androidMain/assets/distill/` 下有 6 个 markdown 提示词在 `prompts/` 中没有可编辑源：

```
distill_prompt.md  logic_constraint.md  output_schema.md
relation_prompt.md style_differ.md      validation_policy.md
```

其余 15 个 yaml/md 文件两侧哈希一致。这违反 `AGENTS.md`「对话等内置提示词同时存在于可编辑源目录和 Android 打包 assets 中，修改以下一侧时必须同步另一侧」。

建议：把这 6 个文件的源补回 `prompts/distill/`，并加一个仓库级检查脚本（对比 `prompts/**` 与 assets 的文件集合 + 哈希）放到 `scripts/dev_checks.py` 里，避免再次漂移。

---

## Low

### 14. `parent!!` 的裸断言

`StorageService.kt:201`、`StorageService.kt:495`、`DocumentStore.kt:55-56`

`store.mkdirs(manifestFile.parent!!)` / `path.parent!!` 在正常路径下不会为 null，但一旦传入根路径就是 NPE。改用 `?: return` / `requireNotNull` 带上语义化消息，成本极低。

### 15. `RootKey` 直接拼进正则

`DomainStore.kt:32-40`

`storageRoot` 被原样拼成正则（`"^$rootKey/runs/..."`），若根路径里出现 `.`、`(`、`+` 之类字符，匹配会静默失效（同步逻辑直接不执行，数据只在 documents 表里，实体表为空）。建议 `Regex.escape(rootKey)`。

### 16. `appendText` 读全量再重写

`StorageService.kt:125-130`

每次追加都把已有内容读进内存拼接后整体重写，连续追加是 O(n²)。当前调用点不多，但如果用于日志/流水，文件的增长成本会非线性暴露。建议预留「按行追加」接口或直接改成流式。

### 17. 取消异常被当成普通错误记录

`DialogueStreamService.kt:246-249`

```kotlin
} catch (e: Exception) {
    PlatformLog.e(...)   // CancellationException 也会走到这里
    throw e
}
```

rethrow 是对的，但用户取消/断开时会打一条 error 日志，污染诊断。建议 `if (e is CancellationException) throw e` 提前分流，或在 `PlatformLog` 侧过滤。

### 18. `StorageService.getApiKey` 是永远返回 `null` 的占位实现

`StorageService.kt:526-528`

保留占位可以，但它是 public API，容易被后来者误用为「读取密钥」的入口。建议加 `@Deprecated` 或删掉，让密钥访问只经由 `ModelApiKeyService`（AGENTS.md 明令）。

---

## 未覆盖 / 下一批次

| 批次 | 模块 | 可审查文件 |
| --- | --- | --- |
| B | `kmp/core` + `kmp/data` | 94 |
| C | `kmp/feature/*` | 133 |
| D | `kmp/app` + `kmp/ui` | 149 |
| E | 旧实现 `src/`（按 AGENTS 定位为行为对照，优先级最低） | 250 |

批次 A 中以下文件仅做了模式扫描，未逐行深读，建议在 B 轮一并复查：

- `DistillExecutor.kt`（1310 行）、`DialoguePayloadBuilder.kt`（1256 行）、`DialogueAdvancedService.kt`（941 行）、`SceneProgressState.kt`（826 行）、`DialogueService.kt`（803 行）、`RunOperationsService.kt`（704 行）、`PersonaService.kt`（608 行）、`PluginHostImpl.kt`（414 行）、`PluginService.kt`（412 行）。
