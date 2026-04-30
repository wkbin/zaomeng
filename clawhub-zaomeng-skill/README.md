# zaomeng-skill

`zaomeng-skill` 是一个面向中文小说人物蒸馏、关系抽取、关系图谱和角色对话的 skill。

它的工作方式很直接：

- 读取小说内容
- 准备 excerpt、prompt 和 references
- 交给宿主 LLM 生成蒸馏结果、关系结果和角色回复
- 将 canonical profile 继续物化为完整人物包

这个 skill 默认运行在宿主环境中，宿主负责实际调用模型；包内 Python helper 依赖写在 `requirements.txt`。

## 概览

| 项目 | 内容 |
| --- | --- |
| 名称 | `zaomeng-skill` |
| 版本 | `4.1.3` |
| 模式 | LLM-first |
| 适用宿主 | OpenClaw、ClawHub、Hermes、其他 host-managed agent |
| 核心能力 | 人物蒸馏、关系抽取、关系图谱、角色单聊、角色群聊 |
| 许可证 | `MIT-0` |

## 它能做什么

### 1. 蒸馏人物

从小说原文中提取人物档案，并尽量覆盖完整的人设层次，例如：

- 核心身份
- 核心动机
- 性格基底
- 行为逻辑
- 人物弧光
- 关键羁绊
- 语言表达特质
- 价值取舍体系
- 深层执念与隐秘欲望
- 私下真实面貌

### 2. 抽取关系

从同框互动中提取两两关系，并输出人物关系图谱，包括：

- 关系结果 markdown
- Mermaid 源码
- HTML 可视化图谱

### 3. 进入角色聊天

支持两种主要玩法：

- `act`
  你扮演一个角色说话，可以是一对一，也可以直接加入多人群聊
- `insert`
  你以“你自己”的身份进入小说场景，不扮演书中角色，而是直接和他们互动
- `observe`
  让多个角色围绕一个场景、话题或开场白进行互动

### 4. 保存纠错

如果某句明显 OOC，可以把纠错写回记忆，后续对话继续沿用。

## 工作流

### 标准流程

1. 提供小说文件或正文
2. 生成按角色聚焦的 excerpt
3. 生成 distill 或 relation prompt payload
4. 交给宿主 LLM 完成生成
5. 若宿主落盘了 `PROFILE.generated.md`，继续物化完整人物包
6. 导出关系图谱
7. 再进入 `act`、`insert` 或 `observe`

多角色蒸馏时，不应只截取小说开头。应传入 `--characters`，让 excerpt 围绕目标角色的实际出场窗口抽取，尤其适用于角色分散出现在不同章节的长篇文本。

### 增量蒸馏

如果同一本小说下已经存在角色人物包，这个 skill 会在构建 distill payload 时自动复用已有档案，把本次蒸馏视为增量更新：

- 自动检测 `data/characters/<novel_id>/<角色名>/`
- 把已有 `PROFILE`、拆分人格文件和 `MEMORY` 合并到 `request.existing_profiles`
- 将 `request.update_mode` 标记为 `incremental`
- 把增量上下文写入 `run_manifest.json -> artifacts.distill_context`

此外，distill payload 现在会额外给出 `request.excerpt_focus`，包含：

- `requested_characters`
- `matched_characters`
- `missing_characters`
- `strategy`

宿主可以据此判断：这次 excerpt 是否真的覆盖了请求角色，是否有角色根本没在文本里命中。

### 会话摘要 JSON

面向宿主接入时，聊天链路可以额外输出一份标准化会话摘要 JSON：

```bash
py -3 -m src.cli.app chat --novel <路径> --message "<请求>" --session-summary-out <session-summary.json>
```

这份摘要用于让宿主直接拿到当前会话状态，而不必自己反解析 markdown。建议至少关注：

- `mode`
- `participants`
- `controlled_character`
- `focus_targets`
- `self_insert`
- `latest_responses`
- `artifacts.session_file`
- `artifacts.relation_snapshot_file`

如果宿主还需要当前动作结果和成功标记，也可以要求：

```bash
py -3 -m src.cli.app chat --novel <路径> --message "<请求>" --chat-result-out <chat-result.json> --chat-status-out <chat.status.json>
```

可直接参考打包样例：

- `examples/chat_session_summary.example.json`
- `examples/chat_result_single_turn.example.json`
- `examples/chat_status_complete.example.json`
- `examples/host_workflow_example.md`

### Distill Post-Process

宿主 LLM 写出 `PROFILE.generated.md` 后，不要停在单文件状态。  
应立即执行 `tools/materialize_persona_bundle.py`，把 canonical profile 物化成完整人物包，补齐：

- `SOUL.generated.md`
- `GOALS.generated.md`
- `STYLE.generated.md`
- `TRAUMA.generated.md`
- `IDENTITY.generated.md`
- `BACKGROUND.generated.md`
- `CAPABILITY.generated.md`
- `BONDS.generated.md`
- `CONFLICTS.generated.md`
- `ROLE.generated.md`
- `AGENTS.generated.md`
- `MEMORY.generated.md`
- `NAVIGATION.generated.md`

## 安装方式

### OpenClaw

```bash
openclaw skills install wkbin/zaomeng-skill
```

### ClawHub

```bash
npx clawhub@latest install zaomeng-skill
pnpm dlx clawhub@latest install zaomeng-skill
bunx clawhub@latest install zaomeng-skill
```

### 本地 skill 目录安装

```bash
python scripts/install_skill.py --skills-dir <your-skills-root>
```

## Helper Commands

```bash
py -3 tools/prepare_novel_excerpt.py --novel <路径> [--characters A,B] [--max-sentences 120] [--max-chars 50000]
py -3 tools/build_prompt_payload.py --mode distill|relation --novel <路径> [--characters A,B] [--characters-root <data/characters 或 data/characters/<novel_id>>] [--update-mode auto|create|incremental]
py -3 tools/materialize_persona_bundle.py --profile-file <角色目录/PROFILE.generated.md>
py -3 tools/export_relation_graph.py --relations-file <关系结果.md>
py -3 tools/verify_host_workflow.py --characters-root <characters/<novel_id>> [--relations-file <关系结果.md>]
```

```bash
py -3 tools/prepare_novel_excerpt.py --novel 十日终焉.txt --characters 齐夏,肖冉,章晨泽 --max-chars 50000
```

## 推荐使用方式

正确顺序不是一上来就群聊。  
**先给小说，再蒸馏人物，蒸馏完成后再进入聊天。**

最常见的路径是：

1. 提供小说文件或路径
2. 指定要蒸馏的角色
3. 宿主分阶段播报蒸馏进度和图谱生成进度
4. 查看人物档案或关系图谱
5. 进入 `act`、`insert` 或 `observe`

## 示例

### 蒸馏

```text
帮我蒸馏林黛玉和贾宝玉
```

```text
请从这本小说里提取刘备、张飞、关羽的人设
```

### 进入 act

```text
让我扮演贾宝玉和林黛玉聊天
```

```text
我来扮演宝玉，你让黛玉回我
```

### 进入 observe

```text
进入刘备、张飞、关羽群聊模式
```

```text
请让大家围绕联合孙权这件事各说一句
```

### 进入 insert

```text
让我以我自己进入红楼梦，和林黛玉、贾宝玉聊天
```

```text
把我放进三国场景里，我想以初来军中的新客身份和刘备他们说话
```

## 人物包结构

人物档案目录通常如下：

```text
runtime/data/characters/<novel_id>/<角色名>/
```

常见文件：

- `PROFILE.generated.md`
- `PROFILE.md`
- `NAVIGATION.generated.md`
- `NAVIGATION.md`
- `MEMORY.generated.md`
- `MEMORY.md`

按证据充分程度，还可能生成拆分人格文件：

- `SOUL.generated.md`
- `GOALS.generated.md`
- `STYLE.generated.md`
- `TRAUMA.generated.md`
- `IDENTITY.generated.md`
- `BACKGROUND.generated.md`
- `CAPABILITY.generated.md`
- `BONDS.generated.md`
- `CONFLICTS.generated.md`
- `ROLE.generated.md`

## 约束文件

- `references/output_schema.md`
  负责输出格式与字段定义
- `references/style_differ.md`
  负责反同质化与风格差异化
- `references/logic_constraint.md`
  负责全局人设底线与防 OOC
- `references/validation_policy.md`
  负责输出自检和校验规则
- `references/chat_contract.md`
  负责会话摘要、聊天结果和聊天状态的字段契约
- `references/capability_index.md`
  负责 distill、materialize、export_graph、verify_workflow、chat 的能力索引

## 发布内容

建议一并发布这些文件：

- `README.md`
- `README_EN.md`
- `.metadata.json`
- `SKILL.md`
- `INSTALL.md`
- `MANIFEST.md`
- `PUBLISH.md`
- `prompts/`
- `references/`
- `tools/`

## License

`MIT-0`
