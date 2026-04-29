---
name: zaomeng-skill
description: 面向中文小说人物蒸馏、关系抽取、关系图谱与角色对话的 ClawHub skill。
---

# zaomeng-skill

| 项目 | 内容 |
| --- | --- |
| 名称 | `zaomeng-skill` |
| 版本 | `4.1.3` |
| 类型 | ClawHub / Host-managed skill |
| 核心模式 | LLM-first |
| 适用场景 | 人物蒸馏、关系抽取、角色单聊、角色群聊 |
| 宿主职责 | 调用宿主 LLM，执行最终生成 |
| skill 职责 | 准备 excerpt、prompt payload、references、关系图谱与人物包后处理 |

## 1. 定位

- 这是一个由宿主 LLM 驱动的小说人物 skill。
- 宿主负责实际生成，skill 负责组织输入、约束和后处理。
- skill 不负责自建模型调用链，而是负责把人物任务组织成宿主可执行的 prompt-first 工作流。
- 角色蒸馏、关系抽取、`act`、`observe` 都由宿主 LLM 完成最终表达。

## 2. 主要资产

### prompts

- `prompts/distill_prompt.md`
- `prompts/relation_prompt.md`
- `prompts/correction_prompt.md`

### references

- `references/output_schema.md`
- `references/style_differ.md`
- `references/logic_constraint.md`
- `references/validation_policy.md`

### tools

- `tools/prepare_novel_excerpt.py`
- `tools/build_prompt_payload.py`
- `tools/materialize_persona_bundle.py`
- `tools/export_relation_graph.py`

## 3. 输入与产物

### 输入

- 小说文件路径或正文内容
- 待蒸馏角色列表
- 可选的纠错、补充说明或关系结果

### 产物

- excerpt JSON
- distill prompt payload
- relation prompt payload
- `PROFILE.generated.md`
- 拆分人格文件，如 `SOUL.generated.md`、`STYLE.generated.md`、`BONDS.generated.md`
- `NAVIGATION.generated.md`
- `*_relations.mermaid.md`
- `*_relations.html`

## 4. 标准流程

1. 读取小说内容。
2. 运行 `tools/prepare_novel_excerpt.py` 生成 excerpt。
3. 运行 `tools/build_prompt_payload.py` 生成 distill 或 relation payload。
4. 将 payload 交给宿主 LLM 生成结构化结果。
5. 若宿主已落盘 `PROFILE.generated.md`，立即运行 `tools/materialize_persona_bundle.py` 生成完整人物包。
6. 若已有关系结果，运行 `tools/export_relation_graph.py` 导出 Mermaid 与 HTML 图谱。
7. 蒸馏与关系准备完成后，再进入 `act` 或 `observe`。

## 5. 宿主执行规则

- 任何 agent 使用此 skill 时，先准备 prompt 输入，再调用宿主 LLM。
- 不把自然语言启动语直接当成角色台词。
- 不手工伪造 prompt 输出。
- 如果宿主已经生成了 `PROFILE.generated.md`，不要停在单文件状态，必须继续物化人物包。

## 6. Distill Post-Process

- 宿主 LLM 为每个角色落盘 `PROFILE.generated.md` 后，必须继续执行 `tools/materialize_persona_bundle.py`。
- 这一步负责把 canonical profile 物化为完整人物包，并补齐 `NAVIGATION.generated.md`。
- 推荐命令：

```bash
py -3 tools/materialize_persona_bundle.py --profile-file <character-dir/PROFILE.generated.md>
```

## 7. 进度播报

- 蒸馏过程中要持续给出阶段性进度，不长时间静默。
- 只向用户输出进度与结果，不输出内部思考、排障链路或长段自言自语。
- 至少播报这些节点：
  - 已锁定多少个待蒸馏角色
  - 当前正在蒸馏哪个角色
  - 当前进度是第几个 / 共几个
  - 人物蒸馏已完成
  - 正在生成人物关系图谱
  - 人物关系图谱已生成，并给出 HTML 路径
- 结束时明确告诉用户：
  - 可以查看人物档案
  - 可以查看关系图谱
  - 可以进入 `act`
  - 可以进入 `observe`
- 推荐结束语可直接写成：`你现在可以查看人物档案、关系图谱，或进入 act 模式 / observe 模式继续。`

## 8. 意图映射

### act

以下自然语言默认按 `act` 处理：

- `让我扮演 X 和 Y 聊天`
- `我来扮演 X，你让 Y 回我`
- `我说一句，Y 回一句`
- `进入 act 模式`

### observe

以下自然语言默认按 `observe` 处理：

- `进入 X、Y、Z 群聊模式`
- `请让大家围绕这件事各说一句`

## 9. Helper Commands

```bash
py -3 tools/prepare_novel_excerpt.py --novel <路径> [--max-sentences 80] [--max-chars 12000]
py -3 tools/build_prompt_payload.py --mode distill|relation --novel <路径> [--characters A,B]
py -3 tools/materialize_persona_bundle.py --profile-file <角色目录/PROFILE.generated.md>
py -3 tools/export_relation_graph.py --relations-file <关系结果.md>
```
