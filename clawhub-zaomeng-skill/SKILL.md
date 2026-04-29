---
name: zaomeng-skill
description: ClawHub 技能包，用于中文小说人物蒸馏、关系抽取与角色对话。
---

# zaomeng 技能（ClawHub）

## 核心定位

- `zaomeng` 是一个以宿主 LLM 为核心的小说人物技能。
- 这个 skill 负责准备小说片段、提示词、参考约束和结构化结果。
- 宿主负责实际生成：蒸馏、关系抽取、单聊、群聊都由宿主 LLM 完成。

## 主要资产

- `prompts/`
- `references/`
- `tools/prepare_novel_excerpt.py`
- `tools/build_prompt_payload.py`
- `tools/export_relation_graph.py`

## 标准流程

1. 读取小说内容。
2. 用 `tools/prepare_novel_excerpt.py` 生成 excerpt。
3. 用 `tools/build_prompt_payload.py` 组装 distill 或 relation payload。
4. 将 payload 交给宿主 LLM 生成结果。
5. 用 `tools/export_relation_graph.py` 生成 Mermaid 与 HTML 人物关系图谱。
6. 蒸馏完成后，再进入 `act` 或 `observe`。

## 进度播报

- skill 在蒸馏过程中应主动给出阶段性进度，不要长时间静默。
- 至少要播报：
  - 已锁定多少个待蒸馏角色
  - 当前正在蒸馏哪个角色
  - 当前进度是第几个 / 共几个
  - 人物蒸馏已完成
  - 正在生成人物关系图谱
  - 人物关系图谱已生成，并给出 HTML 图谱路径或链接
- 结束时要明确告诉用户：
  - 可以查看关系图谱
  - 可以进入 `act` 模式
  - 可以进入 `observe` 模式

## Chat 调用规则

- 任何 agent 使用这个 skill 时，都应先准备 prompt 输入，再由宿主 LLM 生成结果。
- 群聊与单聊一旦满足执行条件，就直接进入人物对话流程。
- 不要把自然语言启动语直接当成角色台词。
- 不要手工模拟 prompt 输出；按 skill 资产组织宿主调用。

## 自然语言意图映射

- `让我扮演X和Y聊天`
- `我来扮演X，你让Y回我`
- `我说一句，Y回一句`
- `进入 act 模式`

以上按 `act` 启动意图处理。

- `进入刘备、张飞、关羽群聊模式`
- `请让大家围绕这件事各说一句`

以上按 `observe` 启动意图处理。

## 面向用户的表达

用户要求蒸馏人物时：

```text
我先按 zaomeng 的 skill 流程处理这本小说：先准备 excerpt 和 prompt payload，再交给宿主 LLM 做蒸馏。
```

蒸馏过程中推荐这样播报：

```text
已锁定 3 个待蒸馏角色：林黛玉、贾宝玉、薛宝钗。
正在蒸馏 1/3：林黛玉。
正在蒸馏 2/3：贾宝玉。
正在蒸馏 3/3：薛宝钗。
人物蒸馏完成，正在生成人物关系图谱。
人物关系图谱已生成：<图谱路径或链接>。
你现在可以查看关系图谱，或进入 act 模式 / observe 模式继续。
```

蒸馏完成后，用户要求进入 `act` 或 `observe` 时：

```text
人物档案已经可用，我现在按 zaomeng 的聊天流程进入对应模式。
```

如果是 `act` 启动语：

```text
我先为你建立 act 会话。接下来你说一句角色台词，我再让对方角色按设定回应。
```

如果是 `observe` 启动语：

```text
我先为你建立群聊会话。接下来你可以给场景、话题，或者让某个角色先开口。
```

## Helper 命令

- 准备 excerpt：`py -3 tools/prepare_novel_excerpt.py --novel <路径> [--max-sentences 80] [--max-chars 12000]`
- 组装 prompt payload：`py -3 tools/build_prompt_payload.py --mode distill|relation --novel <路径> [--characters A,B]`
- 导出关系图谱：`py -3 tools/export_relation_graph.py --relations-file <关系结果.md>`

## 产物

- excerpt JSON
- distill prompt payload
- relation prompt payload
- 宿主生成的人物档案
- 宿主生成的人物关系结果
- 人物关系图谱
