# 造梦.skill

[中文](README.md) | [English](README.en.md)

`zaomeng` 是一个本地小说角色工具。  
你可以用它从小说里蒸馏人物、抽取关系，并让角色按设定进行对话。

它不是普通聊天机器人。  
它更像一个“角色引擎”：重点不是陪聊，而是让人物说话更像人物本身。

## 你可以怎么用

- 从 `.txt` / `.epub` 小说里提取角色档案
- 生成角色关系图谱
- 进入多人群聊模式，观察角色互动
- 进入单人扮演模式，由你控制某个角色发言
- 对不符合人设的回复做纠错，写入记忆

## 最常用的玩法

### 1. 直接用自然语言进入玩法

现在最方便的方式，不是先想命令，而是直接说你想怎么玩。

比如你可以直接说：

```text
让我扮演贾宝玉和林黛玉聊天
```

这时系统会进入“你扮演宝玉，黛玉回应你”的玩法。  
然后你再继续说：

```text
妹妹今日可大安了？
```

系统会把这句当成贾宝玉的发言，再让林黛玉回话。

再比如你可以说：

```text
进入刘备、张飞、关羽群聊模式
```

这时系统会进入三人的群聊玩法。  
你接着再说：

```text
刘备：二位贤弟，近日战事稍歇，倒是难得清闲。
```

系统就会让张飞、关羽继续接话。

如果你说的是：

```text
请让大家围绕联合孙权这件事各说一句
```

系统会直接开始这一轮对话，而不是只告诉你“已进入某模式”。

### 2. 观察模式

观察模式适合看角色之间怎么互动。  
你给一个场景、一个话题，或者某个角色先开口，系统让其他角色自然接下去。

适合：

- 看人物关系是否会影响说话方式
- 看蒸馏出来的人设是否贴近原著
- 做群像互动、剧情试验

### 3. 扮演模式

扮演模式适合你亲自扮演某个角色。  
你说一句，系统按其他角色的人设与关系来回。

适合：

- 沉浸式角色扮演
- 测试某个角色在特定关系下会怎么回应
- 做宝黛、刘关张这类强关系角色互动

## 核心能力

### 1. 人物蒸馏

从小说中提取主要角色，输出人物档案，包括：

- `core_traits`
- `values`
- `speech_style`
- `typical_lines`
- `decision_rules`
- `identity_anchor`
- `soul_goal`
- `life_experience`
- `taboo_topics`
- `forbidden_behaviors`

### 2. 关系抽取

从小说中生成关系图谱，当前核心字段包括：

- `trust`
- `affection`
- `power_gap`
- `conflict_point`
- `typical_interaction`

### 3. 角色对话

支持两种模式：

- `observe`
  给出场景或引导语，让角色围绕它自然互动
- `act`
  由你控制一个角色发言，其余角色按设定和关系状态回话

对话过程支持：

- `/save`
- `/reflect`
- `/correct 角色|对象|原句|修正句|原因`
- `/quit`

### 4. 纠错记忆

如果某句明显不符合人物设定，可以把修正写入记忆。  
后续对话会尽量避开同类偏差。

### 5. Markdown 人格包

当前人物主存储已经是 Markdown，不再以旧版 JSON 为准。

每个角色的文件位于：

- `data/characters/<novel_id>/<角色名>/PROFILE.md`
- `data/characters/<novel_id>/<角色名>/NAVIGATION.md`
- `data/characters/<novel_id>/<角色名>/SOUL.md`
- `data/characters/<novel_id>/<角色名>/IDENTITY.md`
- `data/characters/<novel_id>/<角色名>/AGENTS.md`
- `data/characters/<novel_id>/<角色名>/MEMORY.md`
- `data/characters/<novel_id>/<角色名>/RELATIONS.md`

## 快速开始

### 第一步：先蒸馏人物、抽取关系

以《红楼梦》为例：

```bash
python -m src.core.main distill --novel data/hongloumeng.txt --characters 林黛玉,贾宝玉 --force
python -m src.core.main extract --novel data/hongloumeng.txt --force
```

这一步会生成：

- `data/characters/hongloumeng/<角色名>/`
- `data/relations/hongloumeng/hongloumeng_relations.md`

### 第二步：开始聊天

推荐直接用自然语言：

```bash
python -m src.core.main chat --novel data/hongloumeng.txt --mode auto --message "让我扮演贾宝玉和林黛玉聊天"
```

然后继续：

```bash
python -m src.core.main chat --novel data/hongloumeng.txt --session <session_id> --message "妹妹今日可大安了？"
```

如果你想直接开始群聊：

```bash
python -m src.core.main chat --novel data/sanguo.txt --mode auto --message "进入刘备、张飞、关羽群聊模式"
python -m src.core.main chat --novel data/sanguo.txt --session <session_id> --message "刘备：二位贤弟，近日战事稍歇。"
```

## 其他命令

### 查看角色档案

```bash
python -m src.core.main view --character 林黛玉 --novel data/hongloumeng.txt
```

### 保存一次纠错

```bash
python -m src.core.main correct \
  --session <session_id> \
  --message "宝玉打算离家经商" \
  --corrected "宝玉一向厌弃仕途经济，更愿留在诗酒园林之间" \
  --character 贾宝玉
```

## 命令总览

```bash
python -m src.core.main distill --novel <path> [--characters A,B] [--output <dir>] [--force]
python -m src.core.main extract --novel <path> [--output <path>] [--force]
python -m src.core.main chat --novel <path-or-name> --mode auto|observe|act [--character <name>] [--session <id>] [--message <text>]
python -m src.core.main view --character <name> [--novel <path-or-name>]
python -m src.core.main correct --session <id> --message <raw> --corrected <fixed> [--character <name>] [--target <name>] [--reason <text>]
```

## 项目结构

```text
src/core/main.py
src/modules/distillation.py
src/modules/relationships.py
src/modules/chat_engine.py
src/modules/reflection.py
src/modules/speaker.py
src/utils/
openclaw-skill/
hermes-skill/
skills/zaomeng-skill/
clawhub-zaomeng-skill/
tests/test_relation_behavior.py
```
