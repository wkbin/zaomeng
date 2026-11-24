# 代码审查报告 · 全局（全仓）

- 审查工具：alibaba/open-code-review（`ocr` v1.13.10），委派模式（`ocr` 文件选择 + 内置规则清单，规则由 `ocr rules check` 解析；LLM 网关 120.46.87.99:8080 本次返回 502，故未走 `ocr scan` 的 LLM 路径）
- 审查时间：2026-11-18
- 审查范围：全仓 881 个可审文件（`kmp/server` 一并重审），覆盖 Python 旧后端、Web 旧前端、KMP 客户端、Ktor 服务端、Room 存储、skill 包、脚本与 CI
- 审查方式：按模块切成 18 个审查单元并行全量通读（逐文件完整读取，大文件分段读完），汇总后对 Critical 逐条回源核验，再修复
- 结论：**18 Critical / 约 79 High / 约 45 Medium**；Critical 已全部修复（18/18），High 已修 18 项，其余按风险排序留待确认

## 覆盖度

| 单元 | 范围 | 文件 | 行数 | 读取 |
| --- | --- | --- | --- | --- |
| U1 | `src/web/chat` | 30 | 13,381 | 100% |
| U2 | `src/web/service_facades` | 18 | 5,875 | 100% |
| U3 | `src/web` 其余（pipeline/prompts/review/run_ops/manifest/根级） | 52 | 9,023 | 100% |
| U4 | `src/web/static/js` 核心（main/core/bootstrap/state-store/webui-api 等） | 10 | 7,334 | 100% |
| U5 | `src/web/static/js` 其余（dialogue/run-detail/vue-island 等） | 35 | 11,853 | 100% |
| U6 | `src/web/static` HTML 片段与 CSS | 13 | 10,308 | 100% |
| U7 | `src/{core,modules,cli,plugin_system,skill_support,utils,builtin_plugins}` | 56 | 13,999 | 100% |
| U8 | `kmp/server/{storage,llm,http,schemas}` + build 脚本 | 41 | 5,343 | 100% |
| U9 | `kmp/server` 对话主链（Dialogue*/Session*） | 12 | 5,787 | 100% |
| U10 | `kmp/server` 蒸馏/人物/记忆/关系 | 15 | 5,019 | 100% |
| U11 | `kmp/server` 运行/章节/卡片/插件/设置/诊断 | 33 | 7,109 | 100% |
| U12 | `kmp/feature/chat` | 45 | 9,378 | 100% |
| U13 | `kmp/feature/{settings,sessions,rundetail,persona,pluginbuilder}` | 28 | 10,711 | 100% |
| U14 | `kmp/feature` 其余 13 模块 | 33 | 9,136 | 100% |
| U15 | `kmp/data` + `kmp/core` | 89 | 约 7,400 | 100% |
| U16 | `kmp/{ui,app,builtin-plugins,plugins-api,gradle,iosApp}` | 130 | 5,540 | 约 99% |
| U17 | `zaomeng-skill` | 27 | 7,481 | 100% |
| U18 | `scripts` + `website` + CI + `prompts` + 根配置 | 65 | 6,558 | 100% |

## 一、已修复（本次改动）

### Critical

| # | 位置 | 问题 | 修复 |
| --- | --- | --- | --- |
| 1 | `kmp/server/.../CardsManagementService.kt:105` | `card_id` 未校验即拼路径并 `deleteRecursively`，`DELETE /scene-cards/..` 可删掉整个 storageRoot | 新增 `requireCardId()`，`get/save/delete` 统一走 `PathSafety.validateStorageId` |
| 2 | `kmp/server/.../DialogueStreamService.kt:64` | 请求体 `operation_id` 直接当 `turnId` 拼 `turns/<id>`，可穿越写文件 | `prepareSessionForGeneration` 与 `saveTurn` 均先 `PathSafety.validateStorageId(turnId)` |
| 3 | `kmp/server/storage/.../DomainStore.kt:32` | 存储根未 `Regex.escape` 即拼正则，路径含 `(`/`+`/`[` 时实体同步全部静默失效 | 引入 `rootKeyPattern = Regex.escape(rootKey)`，6 处正则全部改用 |
| 4 | `kmp/server/llm/.../DialogueResponseParser.kt:132` | 多行 NDJSON 中任一行不完整就回退到整文档解析，只取第一个对象，后续角色台词静默丢失 | 改为"只要有完整行就返回全部完整行"，无完整行才走旧格式解析 |
| 5 | `kmp/data/repository/.../RuntimeRepositories.kt:62` | 保存模型设置时无条件把 `apiKey` 写回安全存储，空值会覆盖已有密钥 | 仅在 `apiKey.isNotBlank()` 时写入 |
| 6 | `src/web/review/scene_cards.py:159`、`self_cards.py:195`、`opening_presets.py:231` | 卡片 id 未校验即 `rmtree`，`..` 可删除 storage_root | 三处统一改用 `resolve_storage_child`（读写删全部覆盖） |
| 7 | `zaomeng-skill/tools/_skill_support/persona_review.py:516` | 同上，`--card-id ..` 可删除卡根之外目录树 | 新增 `_resolve_card_dir()`（`^[A-Za-z0-9_-]{1,128}$` + 父目录校验） |
| 8 | `src/utils/file_utils.py:74` | `text.split("---", 2)` 取 frontmatter，正文含 `---` 会截断 YAML，静默丢历史或抛 `ScannerError` | 改为只认独占一行的起始/结束分隔行正则 |
| 9 | `src/web/service_facades/dialogue.py:444/488/545` | 增强器配置无条件覆盖调用方传入的 `include_inner_thoughts`，内心独白丢失且幂等指纹错位 | 改为 `bool(入参) or bool(增强器)` |
| 10 | `src/web/chat/world_memory.py:46` | 两个 `WorldMemoryStore` 实例各持私有锁读写同一文件，并发下互相覆盖（用户在 UI 改的事实被对话回合整表覆盖） | 锁表提升为模块级、按文件路径共享 |
| 11 | `src/web/static/js/relation-details-legacy.js:16` | 关系名/类型未转义拼 `innerHTML`，导入的小说包可注入脚本 | 改用 `escapeHtml` 包裹 |
| 12 | `kmp/feature/chat/.../ChatViewModel.kt:214` | "加载更早消息"无请求/会话校验，与刷新并发时旧页前插到新会话，`transcriptCount` 与条目数错位→重复 key 崩溃 | 新增 `earlierRequestId`，`load()` 时作废在途分页；落地前校验请求号+run/session；按条目身份去重 |
| 13 | `kmp/server/.../DialogueService.kt:372` | `commitTurn` 无 `turn_id` 去重：同 `operation_id` 重放会再追加整轮 transcript 并把 `turn_count` 再加一 | 锁内先判重（`current_turn_id` / 最近 transcript / 归档物化），重放直接返回当前 manifest，且不再重复派生记忆与场景状态 |
| 14 | `src/web/service_facades/dialogue.py:430` | 删除会话不取消在途生成：生成线程在提交阶段会 `mkdir` 重建会话目录、把 `session.json` 或 `reply_operations/*.json` 写回（目录/残骸复活） | 删除前先 `cancel_active_session_operations`；提交后、`mark_completed` 前再补一次 `ensure_not_cancelled()`，取消后错误分支不再落任何文件 |
| 15 | `kmp/server/.../DialogueAdvancedService.kt:852` | `saveSession` 用调用方早先读到的整份快照覆盖写 manifest：`correctLatest`/`deepReview`/`switchScene` 跨 LLM 调用期间提交的新回合被静默抹掉 | 改为 `editSession { latest -> 本次字段 }`，在 `storage.updateSessionManifest` 的路径锁内基于 latest 增量合并；7 个编辑入口全部迁移，`switchScene` 三次写入合并为一次 |
| 16 | `scripts/install.sh:598` | `zaomeng uninstall` 无确认直接 `rm -rf` 安装目录与数据目录；`ZAOMENG_STORAGE_DIR` 指向家目录时会清空家目录 | 增加 `--yes`/`--purge-data`：默认**保留数据**，非交互必须显式 `--yes`；删除前拒绝空路径、`/`、`$HOME` 本身；支持 `-h`，未知参数退出码 2 |
| 17 | `src/plugin_system/runtime.py:499` | 第三方插件（`storage_root/plugins`）写 `defaultEnabled: true` 即可在启动时被 `exec` 进主进程，等同免确认执行任意代码 | `defaultEnabled` 只对内置插件生效；第三方插件必须在 UI 里显式启用（写入 `plugin-state.json`）后才会加载。`website/plugin-development.html` 本就如此描述，代码此前未落实，现已对齐 |
| 18 | `src/web/static/styles/base.css:130` | 移动端会话抽屉命中 `sidebar-collapsed` 的 `opacity:0 / translateX(-16px)`，且祖先 `.app-shell` 也是 `opacity:0`，只出遮罩看不到侧栏 | 在 `responsive.css` 的抽屉规则里显式复位 `opacity/transform/pointer-events`，并放开 `.dialogue-layout` 下 `.app-shell` 的透明度 |

### High

| # | 位置 | 问题 | 修复 |
| --- | --- | --- | --- |
| 1 | `kmp/server/.../ChapterService.kt:43` | `updated_at` 写 epoch 数字，`ChapterDto.updatedAt: String` 反序列化失败，整份章节列表不可用 | 改 `nowIsoString()` |
| 2 | `kmp/data/remote/.../KtorChapterClient.kt:110` | 全书搜索发 `q`，服务端读 `query`，功能恒定 400 | 改为 `query` |
| 3 | `kmp/data/remote/.../KtorHttpClientProvider.kt:36` | `LogLevel.HEADERS` 未脱敏，Bearer token 明文进 logcat/stdout | 增加 `sanitizeHeader("***") { it == HttpHeaders.Authorization }` |
| 4 | `kmp/feature/chat/.../ChatViewModel.kt:574` | `recoverPending` 已解决但未提交时清空 `failedOperationId`，气泡显示"可重试"但点击无反应 | 仅在 `responseWasCommitted` 时清空 |
| 5–7 | `ChatPersonaAvatar.kt:162`、`SessionsScreen.kt:881`、`RunDetailScreen.kt:1202` | 头像在组合期同步整图解码且未 `remember`，流式 delta 期间主线程反复解码 | 统一 `remember(bytes) { decodeImageBitmap(it) }` |
| 8 | `kmp/feature/importbook/.../ImportBookScreen.kt:65` | 取消选文件或读取失败后 `readingFile` 永不复位，导入入口全部禁用 | `rememberDocumentPicker` 增加 `onCancelled` 回调并接线 |
| 9 | `kmp/feature/importbook/.../ImportDocumentLoader.kt:43` | 声明的 24MB/64MB 上限从未校验，整文件先读进内存 | 入口按 kind 校验 `bytes.size` 并复用既有 `fileTooLargeMessage` |
| 10 | `src/web/chat/world_memory.py:138/292` | 事实按位置截断到 500 条，会把 `locked`/手工事实一起静默淘汰 | 新增 `_trim_facts`：保护 locked/manual，只淘汰对话产生的最新以外条目 |
| 11 | `scripts/install_skill.py:34` | `--skill-name` 未校验，空串等于目标根目录 → 删除整个 skills 目录 | 名称白名单 + 解析后父目录校验 |
| 12 | `kmp/server/.../DialogueAdvancedService.kt:827` | 编辑/校对路径回写物化后的全量 transcript，长会话每次编辑都把归档塞回 manifest，并让下次分片重建归档 | 随第 15 项一并修复：编辑只写最近 transcript 窗口，保留 `transcript_start`/归档；`correctLatest`/`switchScene` 追加条目后重算 `transcript_count` |
| 13 | `kmp/server/storage/.../StorageService.kt:136` | `deleteFile`/`deleteRecursively` 不取 `lockFor(path)`，与写入路径互不排斥：删除后 manifest 被写回（幽灵文件/实体行） | 两条删除路径都进同一分片锁；注释说明目录删除只锁目标自身、子文件"复活"依赖写入侧先读后写（`updateRunManifest`/`loadSessionManifest` 缺文件即失败） |
| 14 | `kmp/server/storage/.../StorageService.kt:199` | 只有 `writeRunManifest` 盲写，蒸馏进度、停止请求、用户操作互相整份覆盖 | 新增 `updateRunManifest { transform }`（路径锁内读-改-写，缺清单抛 `NoSuchElementException`、不重建目录）；`writeRunManifest` 收窄为仅新建 run 使用，并迁移 10 处读改写调用点 |
| 15 | `kmp/server/llm/.../DialoguePromptBuilder.kt:561` | user payload 的 `expected_output` 是 `{"responses":[...]}`，与同请求 `json_only_rule`（必须 NDJSON、禁止 responses 包装）直接冲突 | 改为 `{"format":"ndjson","example":"<逐行对象文本>"}`；示例优先复用 payload 中按 `message_kind` 生成的版本（`DialoguePayloadBuilder` 同步改成 NDJSON 文本，plot 场景为两行），无 payload 时回退到单行通用示例 |
| 16 | `kmp/server/.../DialogueStreamService.kt:150` | 解析失败先用"单角色纯文本兜底"直接提交回合：跳过重试、丢掉 `speaker`，把结构错误静默写进 transcript | 删掉该分支，失败一律走一次 `retryOnEmpty` 重试（maxTokens 翻倍）；第二次仍失败抛 `IllegalStateException`（带两次尝试摘要），由 SSE `error` 事件返回给客户端，不再提交任何回合 |
| 17 | `kmp/server/.../DialogueAdvancedService.kt:1016` | `parseJsonObject` 失败时把完整模型输出打进 `PlatformLog.e`：整章原文/用户数据随日志落盘、撑爆 logcat | 改为 `"LLM returned non-JSON content (<长度> chars, first 500): <前 500 字符>"`，并保留异常对象用于堆栈 |
| 18 | `kmp/server/.../DialogueAdvancedService.kt:525` | `correctLatest` 向 provider 传 `requireJsonObject = true`（强制单对象 `response_format`），与多角色 NDJSON 主协议冲突：模型被逼输出一个对象，多角色回合被压成一条或解析失败 | 改为 `false`，与 `DialogueService` 常规回复一致；`deepReview`/`suggestions` 这类真正的单对象任务仍保留 `true` |

### 验证

- Python：`python -m py_compile` 全部通过；`pytest tests/{test_dialogue_streaming,test_android_dialogue_recovery}.py` 20 项通过，`pytest tests/{test_web_dialogue_behavior,test_web_dialogue_state,test_web_scene_cards,test_world_memory}.py` 57 项通过；`pytest tests/test_plugin_system.py` 20 通过（另 2 项失败是测试内写死的旧路径 `kmp/app/shared/.../SettingsHomeScreen.kt`，文件已迁到 `kmp/feature/settings/`，与本次改动无关）
- 功能回归脚本：
  - frontmatter 含 `---` 的会话写读往返一致
  - `delete_{scene,self,opening}_card_payload(root, ".."/"."/""/"a/b")` 全部抛 `InvalidStorageIdentifier`，根目录与无关文件保持完好
  - `copy_skill_bundle` 对 `""/"."/".."/"../x"/"a/b"/绝对路径` 全部抛 `ValueError`，合法名称复制成功
  - 删除会话竞态：用真实 `ReplyOperationStore` + `DialogueServiceMixin` 桩验证——`delete_session` 被调用时取消事件已置位、已无活跃操作，删除后目录无残留
  - 插件默认启用策略：同一 root 组合下 `official.demo` 自动启用、`third.demo` 保持停用，显式 `enable()` 后才为 `true`
- Shell：`bash -n scripts/install.sh` 与"展开后的 launcher" `bash -n` 均通过；`run_uninstall` 用临时目录跑了 6 组用例——默认（`--yes`）删安装目录/启动器并保留数据、非交互无 `--yes` 退出 1 且不删任何东西、`--purge-data` 连数据一起删、`INSTALL_ROOT=$HOME` 与 `STORAGE_ROOT=/` 均拒绝、未知参数退出 2 且不删
- KMP 编译：`:server:storage:compileKotlinJvm`、`:server:llm:compileKotlinJvm`、`:server:compileKotlinJvm`、`:server:compileAndroidMain`、`:data:remote:compileKotlinJvm`、`:data:repository:compileKotlinJvm`、`:ui:shared:compileKotlinJvm`、`:feature:{chat,sessions,rundetail,importbook,redistill}:compileKotlinJvm` 全部 BUILD SUCCESSFUL

> 第 13/14 项的验证范围：只做了编译（JVM + Android），没有运行时测试——`:server:jvmTest` 在本机仍因 JVM 25 崩溃（见局限 3）。锁语义与 `updateRunManifest` 的读-改-写一致性靠代码路径核对：10 处原「读→改→整份写」调用点已全部迁移，`writeRunManifest` 只剩 `RunManagementService.createRun` 一处新建路径。

> 第 15 项同样是编译验证：`expected_output` 不再含 `responses` 键，改为 `format` + 逐行示例文本；两处生成点（prompt 侧回退、payload 侧 `ndjsonExample`）已用 grep 确认，未跑 JVM 单测。

> 第 16 项：`:server:compileKotlinJvm` + `:server:compileAndroidMain` 通过（另跑一次 `-q :server:compileKotlinJvm` 确认 exit 0）；`parseSingleSpeakerPlainText` 已无生产调用点（仅保留函数本体与其 3 个解析器单测，作为旧格式兼容/对照），流式链路不再引用。因 `:server:jvmTest` 在本机崩溃（局限 3），"解析失败 → 重试 → 抛错"的端到端 SSE 行为仍只有代码路径核对，未做运行时断言。

> 第 17 项：`:server:compileKotlinJvm` + `:server:compileAndroidMain` 通过（`-q` 运行 exit 0）；grep 确认 `DialogueAdvancedService` 全文件只剩这一处 `PlatformLog` 调用，且不再拼接完整 `content`。日志截断长度（500 字符）与 `DialogueStreamService` 现有的 800 字符错误日志同属"错误路径只记切片"策略，未加单测。

> 第 18 项：`:server:compileKotlinJvm` + `:server:compileAndroidMain` 通过（`-q` 运行 exit 0）；grep 确认该文件剩余 `requireJsonObject = true` 只有 `deepReview`（一致性审校，单对象）与 `suggestions`（选项列表，单对象）两处，均属应当启用的场景。修正链路的解析仍走 `DialogueResponseParser.parse`，与常规回复共用同一 NDJSON 解析器。因 `:server:jvmTest` 在本机崩溃（局限 3），"provider 收到 `response_format` 后输出多行 NDJSON"未有运行时断言。
- CSS（第 18 项）：只做了层叠关系核对（`.mobile-session-drawer-open .app-shell.sidebar-collapsed .library-rail` 优先级高于 base 的 3 类选择器，且 `dialogue-layout` 下 `.app-shell` 的 `opacity` 已显式复位）；本机无浏览器可渲染，**未做窄屏实机回归**

> 已在第 13 项改动中标注边界：`commitTurn` 的判重以会话内容为准（而非 `turn.json` 是否存在），因此"saveTurn 成功但 manifest 未写"的崩溃窗口仍可安全重提；代价是 `saveTurn` 与 manifest 之间崩溃会覆盖同 id 的 turn 文件（内容相同，无副作用）。

> 第 15 项的边界：`correctLatest` 只在"需要替换既有回合"（`targetTurnId` 非空）时要求该回合仍在最近 transcript 内，否则返回 400 而不是把修正内容追加成一条无来源的重复回合；`switchScene`/`correctLatest` 追加条目后会按 `transcript_start + 最近条数` 重算 `transcript_count`，归档分片保持不动。

## 二、未修复的 Critical

无。18 项 Critical 已全部修复。

仍保留的相关风险（不计入 Critical）：

- 插件入口仍在**当前进程**内 `exec`（第 17 项只解决了"免确认自动启用"），已启用插件的权限隔离、签名/摘要校验、崩溃隔离仍未做；需要插件生态策略与进程隔离方案。
- `zaomeng update` 仍以 `bash` 直接执行远端 ref 的 `install.sh`，全链路无校验和（High，见下）。

## 三、未修复的 High（按模块，均为审查结论，未逐条回源复核）

### KMP 服务端

- `kmp/server/.../DialoguePayloadBuilder.kt:758` 场景卡永远取 `scene_profile`（默认 `{}` 非 null），切换场景卡对生成不生效
- `kmp/server/.../SessionManagementService.kt:325` `/api/web/sessions` 对全部 run×session 物化全量 transcript，开销无上限
- `kmp/server/.../DialogueStreamRoute.kt:86` 未传 `operation_id` 时 `appended_transcript` 恒空且不返回 turnId
- `kmp/server/.../DistillExecutor.kt:1162` `patchManifest` 无锁 read-modify-write，与 `stopRun` 互相覆盖，停止请求可能丢失
- `kmp/server/.../DistillExecutor.kt:473` 证据白名单取被截断的 excerpt，分块却用未截断 stages，长文会误判"引用不存在的证据"并整卷失败
- `kmp/server/.../DistillExecutor.kt:243` 单角色异常直接终止整卷，已完成角色无法以"部分完成"呈现
- `kmp/server/.../DistillExecutor.kt:686` 分块并行 `awaitAll`，任一块失败取消其余；空块被静默丢弃却仍报"完整"
- `kmp/server/.../WorldMemoryService.kt:46` `saveFact/deleteFact` 不持 `lockFor(runId)`，与 `syncCompletedTurn` 互相冲刷
- `kmp/server/.../WorldMemoryService.kt:245` 事实按 `takeLast` 截断，可能丢弃 `locked` 事实
- `kmp/server/.../LongTermMemoryService.kt:319` 解析失败静默返回空 payload，下一次 `appendTurn` 把整份记忆覆盖成只剩新条目
- `kmp/server/.../LongTermMemoryService.kt:41` 混合检索对每条记忆重复做 query 归一化/分词，约 O(entries × 文本长度)
- `kmp/server/.../ChapterManagementService.kt:544` 保存章节用旧快照差集删除"孤儿"文件，并发新建章节被物理删除
- `kmp/server/.../RunManagementService.kt:206` 删除 run 不停止在跑的蒸馏，后台任务把目录与 manifest 写回来
- `kmp/server/.../RunOperationsService.kt:425` `redistill` 不检查 running，而 `DistillExecutor.start` 静默 no-op，清单与产物不一致
- `kmp/server/.../PluginHostImpl.kt:376` 临时 NPC 用旧 session 快照整份回写，覆盖并发 transcript
- `kmp/server/.../PluginService.kt:373` 禁用插件全库扫描所有 run×session 并逐个无锁回写
- `kmp/server/.../backend/LocalBackendController.kt:52` 本地后端 token 是硬编码默认值（`dev-token` / `desktop-dev-token`），认证形同虚设
- `kmp/server/.../ChapterManagementService.kt:549` 每次保存章节重写该书卷全部章节文件
- `kmp/server/.../ChapterManagementService.kt:103` 全文检索逐会话读取完整 transcript（最多 100 个）再 `lowercase`
- `kmp/server/.../RunOperationsService.kt:667` `refresh` 用旧快照整份写 manifest，覆盖并发进度
- `kmp/server/.../routes/SuggestionsRoute.kt:77` SSE 出错后仍发 `done/completed`，客户端把失败当成功

### KMP 客户端

- `kmp/feature/sessions/.../SessionsPagingSource.kt:40` `getRefreshKey` 把页起始 offset 当页号 `+1`，刷新窗口重叠（重复/丢首项）
- `kmp/feature/chat/.../ChatToolsDelegate.kt:572` `sessionMutation` 把"会话与快照不同"当成功，真实失败被伪装成成功
- `kmp/feature/chat/.../ChatStreamEngine.kt`（Medium 级）delta 冲刷任务挂在 viewModelScope，`cancel()` 只取消 sendJob
- `kmp/ui/shared/.../PlatformImageLoader.android.kt:14` `rememberPlatformImage` 在组合期同步读整文件并整幅解码（三端同）
- `kmp/ui/shared/.../PlatformTts.android.kt:123` TTS 只 `stop()` 不 `shutdown()`，反复进出聊天页持续累积引擎
- `kmp/ui/shared/.../ShareText.android.kt:17` Android 11+ 无 `<queries>` 时 `resolveActivity` 返回 null，分享点击无反应
- `kmp/app/androidApp/.../MainActivity.kt:50` `onCreate` 在 `super.onCreate` 之前主线程 `runBlocking` 读 DataStore
- `kmp/app/.../desktop/Main.kt:46` `startKoin` 写在 `application {}` 组合体内，重组即二次启动崩溃
- `kmp/ui/shared/.../ImagePicker.kt:22` 文件选择回调在 Main 调度器整文件 `readBytes` 并写盘

### Python 旧后端

- `src/web/chat/service.py:2673` 每次构造回合 payload 都深拷贝全量原作索引并全量扫描（空查询也扫 1.2 万条）
- `src/web/chat/service.py:3614` 序列化会话都对长期记忆做整库向量检索，`list_sessions` 每个会话各做一次
- `src/web/chat/service.py:2510` 每个 turn result 内嵌全量 history 快照，读取时全量解析，整体 O(T²)
- `src/web/chat/service.py:2343` 长期记忆在会话落盘前写入，后续失败残留"幽灵记忆"，重试重复追加
- `src/web/chat/service.py:2487` 世界记忆同步异常以"回复失败"上报且副作用永久丢失
- `src/web/chat/service.py:255` 单个 `session.json` 损坏导致整个会话列表/分支图 500
- `src/web/service_facades/runtime_support.py:49` `_start_background_run` 不检查已有存活线程，同一 run 可双管线
- `src/web/service_facades/runs.py:486` "继续蒸馏"把 `locked_characters` 收窄成未完成子集并清零 `completed_characters`
- `src/web/service_facades/chapters.py:199` `chapter_book.json` 读改写无锁，并发保存丢失整章
- `src/web/service_facades/artifacts.py:187` `save_persona_review` 绕过加锁读改写，整份覆盖 manifest
- `src/web/pipeline/automatic_steps.py:306` 关系修复结果不校验即覆盖唯一产物，可能用残缺图替换好图并导致整轮失败
- `src/core/llm_client.py:495` 预算检查在请求完成后才做，拦不住花钱也记不上账
- `src/core/session_store.py:253` `session_id` 未清洗即拼文件名，`--session ../../x` 可读写目录外文件
- `src/utils/file_utils.py:117` `decode_escaped_text` 对整串做 `unicode_escape`，含中文时解成乱码
- `src/modules/chat_engine.py`（Medium 级）关系更新每条历史每个 pair 一次整文件读+写

### Web 旧前端

- `src/web/static/js/run-detail.js:1198` 1.8s 轮询无条件重置面板开关并 `resetDialogueView()`，清空草稿与聊天记录
- `src/web/static/js/main.js:3467` 迟到的会话回复覆盖当前会话并清空新会话草稿
- `src/web/static/js/main.js:3000` 等待回复期间可继续输入，成功后整段草稿被清空
- `src/web/static/js/main.js:588` 推荐片段响应无 run 归属校验，会把 A 卷正文当作 B 卷新书段
- `src/web/static/js/dialogue.js:2993` 侧边栏快速切换无请求令牌，后返回者覆盖先点的会话
- `src/web/static/js/character-overview-vue-island.js:219` 保存单字段后用服务端字段整体覆盖本地草稿，其它未保存编辑被丢弃
- `src/web/static/js/persona-review-vue-island.js:257` island 挂载后 legacy 表单仍可见可提交，改动被忽略
- `src/web/static/js/redistill-vue-island.js:51` island 与 legacy 摘要同时渲染且判断依据不同，出现互相矛盾文案
- `src/web/static/js/work-overview-legacy-render.js:57` 人物卡 innerHTML 拼接未转义（容器虽隐藏仍会执行）
- `src/web/static/styles/responsive.css:346` ≤1180px 隐藏"一致性"唯一入口，功能不可达
- `src/web/static/styles/workspace.css:1822` 加载态 `>` 选择器隐藏骨架屏祖先，`#work-overview-loading` 永不可见

### skill / 脚本 / CI

- `zaomeng-skill/tools/materialize_persona_bundle.py:21` 输出目录由不可信 `name` 拼接，`../` 可写出人物目录之外
- `zaomeng-skill/tools/_skill_support/novel_preparation.py:215` 整本小说无大小上限读入内存，最多 7 次全量解码打分
- `zaomeng-skill/tools/prepare_novel_excerpt.py:63` 11 个 CLI 入口零 `try/except`，失败输出 traceback 并泄漏绝对路径
- `zaomeng-skill/tools/_skill_support/workflow_completion.py:394` chunk 部分更新会清零计数、清空 mode、翻转 merge 标记
- `zaomeng-skill/prompts/distill_prompt.md:60` 要求 `[S000001]` 证据 ID，但 Python 摘录器从不生成
- `scripts/install.sh:372` `append_path_line` 硬编码 `$HOME/.local/bin`，自定义 `BIN_DIR` 时命令不可用
- `scripts/install.sh:55` 版本判断用 `major>=3 && minor>=10`，Python 4.x 会被误判过低
- `scripts/install.sh:159` 包管理命令字符串拼接后交 `sh -c`，参数未引号
- `scripts/install.sh:419` 安装/自更新全链路无校验和或签名
- `scripts/bump_web_assets.ps1:11` 不检查 `$LASTEXITCODE`，脚本恒以 0 退出
- `prompts/dialogue/turn_system.yaml:6` 规则禁止 `responses` 包装，user payload 的 `expected_output` 却是包装对象
- `.github/workflows/tests.yml:9` PR 上执行仓库代码的 job 未声明 `permissions`

## 四、Medium 摘要（代表性条目）

- 路径/资源：`PathSafety.sanitizePathComponent` 未拦 Windows 保留名与尾点；`ZipEntries.jvm.kt` 解压无总量/条目上限
- 数据一致性：`DomainStore` 消息索引"先删后插"跨两个事务；`DocumentStore` 文件后端替换失败会连旧文件一起删
- 并发：`RelationsService.update` 无锁读改写；`SessionManagementService` 标题更新与回合提交并发丢写
- 性能：`DialoguePayloadBuilder.fileCache` 无界 HashMap；`OriginalKnowledgeService` 每次检索 12k×token `contains`；`DistillExcerptBuilder` 每次候选重渲染 50k 字
- 契约：`SessionManagementService.prepareDialogueTurn` 的 `turns` 无界追加；`forkSession` 沿用父会话 `transcript_count`
- 旧前端：`StateStore` legacy 绑定永不安装（三份状态并存）；`showAppConfirm` 覆盖 resolver 导致 Promise 永久挂起；输入事件每次按键全量快照深拷贝
- CSS/可访问性：设置弹窗 grid 行导致内容裁切无法滚动；主输入框 contenteditable 无可访问名称；若干失效选择器

## 五、局限与未验证项

1. **OCR 的 LLM 审查未执行**：网关 `120.46.87.99:8080/v1` 返回 502（`ocr llm test` 同样失败），因此本次是"OCR 规则 + 宿主模型"委派模式，与 `ocr scan` 的行级评论不完全等价，未产出 `suggestion_code`。
2. **High/Medium 未逐条回源**：Critical 全部回源核验并修复；High 以下为审查单元结论，存在误报可能（历史经验约 1/3），修改前需按条复核。
3. **KMP 单测未跑通**：`:server:llm:jvmTest` 在本机 JVM 25 下以 `non-zero exit value 268435466` 崩溃；已用 `git stash` 回退同一文件复现，确认与本次修改无关（环境问题）。其余模块只做了编译验证，未跑 jvmTest。
4. **iOS 未验证**：本机无 macOS，`iosMain` 与 Xcode 工程仅静态阅读。
5. **未覆盖**：`src/web/static/vendor/vue.global.prod.js`、`zaomeng-skill/assets/vendor/mermaid-*.min.js`、图片与二进制资源、`tests/` 由 OCR 默认规则排除；`kmp/feature/livestream` 未被 `settings.gradle.kts` include，未参与编译验证（该模块本身存在无法编译的调用，见 U14 结论）。
6. **移动端抽屉 CSS 未经真机回归**：只核对了层叠优先级，需要在 ≤1180px 视口实际点开"最近会话"确认侧栏可见、可滚动、遮罩点击能关闭。