# zaomeng-skill

`zaomeng-skill` 是一个面向中文小说人物蒸馏、关系抽取、角色单聊与群聊的技能包。

它的工作方式很直接：

- 读取小说内容
- 准备 excerpt、prompt 和 references
- 交给宿主 LLM 生成蒸馏结果、关系结果和角色回复

许可证：`MIT-0`（MIT No Attribution）

## 对话生成

当前聊天、蒸馏和关系抽取都走 **LLM-first** 路径：

- `zaomeng` 先整理人物约束、关系约束、记忆约束与模式约束
- 再由可生成 LLM 负责最终表达
- 群聊里后发言角色可以看到本轮已生成的前文

这个 skill 默认运行在宿主环境中，宿主负责实际调用模型。

## 它能做什么

### 1. 蒸馏人物

从小说原文中提取人物档案，尽量覆盖更完整的人物维度，例如：

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

从同框互动中提取两两关系，输出关系图谱和角色侧关系层。

### 3. 进入角色聊天

支持两种主要玩法：

- `act`
  你扮演一个角色说话，其他角色按设定回应
- `observe`
  让多个角色围绕一个场景、话题或开场白进行互动

### 4. 保存纠错

如果某句明显 OOC，可以把纠错写回记忆，后续对话继续沿用。

## 安装方式

### OpenClaw

```bash
openclaw skills install wkbin/zaomeng-skill
```

### ClawHub

```bash
npx clawhub@latest install zaomeng-skill
```

```bash
pnpm dlx clawhub@latest install zaomeng-skill
```

```bash
bunx clawhub@latest install zaomeng-skill
```

### 本地 skill 目录安装

```bash
python scripts/install_skill.py --skills-dir <your-skills-root>
```

## 运行前提

要跑真实工作流，宿主环境至少需要满足这些条件：

- 能执行本地 Python 命令
- 已安装 [requirements.txt](requirements.txt) 中声明的依赖

skill 目前已经提供 prompt-first helper 入口：

```text
tools/prepare_novel_excerpt.py
tools/build_prompt_payload.py
tools/export_relation_graph.py
```

常见的 prompt-first 调用方式是先准备 excerpt，再组装 prompt payload。

例如：

```bash
py -3 tools/prepare_novel_excerpt.py --novel <路径>
py -3 tools/build_prompt_payload.py --mode distill --novel <路径> --characters A,B
py -3 tools/export_relation_graph.py --relations-file <关系结果.md>
```

## 推荐用法

正确顺序不是一上来就群聊。  
**先给小说，再蒸馏人物，蒸馏完成后再进入聊天。**

最常见的使用路径是：

1. 提供小说文件，或指定小说路径
2. 用自然语言说要蒸馏谁
3. 宿主按阶段播报蒸馏进度与关系图谱生成进度
4. 蒸馏完成后，再进入 `act` 或 `observe`

## 自然语言示例

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

## 人格包结构

人物档案目录通常如下：

```text
runtime/data/characters/<novel_id>/<角色名>/
```

常见文件：

- `NAVIGATION.generated.md`
- `NAVIGATION.md`
- `PROFILE.generated.md`
- `PROFILE.md`
- `RELATIONS.generated.md`
- `RELATIONS.md`
- `MEMORY.md`

按人物证据情况，还可能生成可选拆分文件：

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

约束分为三层：

- `references/output_schema.md`
  负责输出格式与字段规范
- `references/style_differ.md`
  负责防同质化与风格差异化
- `references/logic_constraint.md`
  负责全局人设底线、防 OOC 与模式边界

如果你在检查输出质量，这三份文件应该一起看，而不是只看 schema。

## 产物

- 人物档案
- 人物关系结果
- 人物关系图谱
- 角色对话回复

## 发布提示

如果你要把这个 skill 单独发布，建议至少一起带上这些文件：

- `README.md`
- `SKILL.md`
- `INSTALL.md`
- `MANIFEST.md`
- `PUBLISH.md`
- `prompts/`
- `references/`
- `tools/`

## Distill Post-Process

- 宿主 LLM 写出 `PROFILE.generated.md` 之后，要立即执行 `tools/materialize_persona_bundle.py`
- 这一步会把 canonical profile 物化成完整人物包，补齐 `SOUL.generated.md`、`GOALS.generated.md`、`STYLE.generated.md`、`TRAUMA.generated.md`、`IDENTITY.generated.md`、`BACKGROUND.generated.md`、`CAPABILITY.generated.md`、`BONDS.generated.md`、`CONFLICTS.generated.md`、`ROLE.generated.md`、`AGENTS.generated.md`、`MEMORY.generated.md` 和 `NAVIGATION.generated.md`
- 示例：`py -3 tools/materialize_persona_bundle.py --profile-file <角色目录/PROFILE.generated.md>`

## License

`MIT-0`
