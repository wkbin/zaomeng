# zaomeng.skill

[中文](README.md) | [English](README.en.md)

`zaomeng` is a local novel-character toolkit.  
You can use it to distill characters from fiction, extract relationships, and run constrained in-character dialogue.

It is not a generic chatbot.  
It is closer to a character engine: the goal is not casual chatting, but making characters sound like themselves.

## What You Can Do With It

- extract character profiles from `.txt` / `.epub` novels
- build relationship graphs
- enter multi-character group chat mode and observe interactions
- enter one-to-one roleplay mode and speak as a chosen character
- correct out-of-character replies and write them into memory

## Most Common Ways To Use It

### 1. Enter a mode with natural language

The easiest way to use `zaomeng` is not to think in commands first, but to simply say what kind of interaction you want.

For example:

```text
Let me play Jia Baoyu and chat with Lin Daiyu
```

The system will enter the flow where you play Baoyu and Daiyu replies.  
Then you continue with:

```text
Sister, are you feeling well today?
```

Now the system treats that as Baoyu's actual spoken line and lets Daiyu answer.

Another example:

```text
Enter Liu Bei, Zhang Fei, Guan Yu group chat mode
```

This starts a three-character group chat flow.  
Then you can continue with:

```text
Liu Bei: Brothers, now that the fighting has briefly eased, this is a rare moment of calm.
```

The system will let Zhang Fei and Guan Yu respond from there.

If you say:

```text
Let everyone say one line about the alliance with Sun Quan
```

The system should begin that round immediately, rather than only telling you that a mode was entered.

### 2. Observe mode

Observe mode is useful when you want to watch characters interact.  
You provide a scene, a topic, or an opening line, and the system lets the relevant characters continue naturally.

Useful for:

- checking whether relationship state affects tone
- checking whether distilled personas feel close to the source
- experimenting with ensemble scenes

### 3. Act mode

Act mode is useful when you want to play one character yourself.  
You speak one line, and the other character or characters reply according to persona and relationship state.

Useful for:

- immersive roleplay
- testing how a specific character responds under a specific relationship
- running focused character interactions

## Core Capabilities

### 1. Character Distillation

Extract major characters from a novel and build structured profiles, including:

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

### 2. Relationship Extraction

Build a relationship graph from the novel. Core fields currently include:

- `trust`
- `affection`
- `power_gap`
- `conflict_point`
- `typical_interaction`

### 3. Character Chat

Two supported modes:

- `observe`
  Give a scene or prompt and let the characters interact around it
- `act`
  Control one character directly while others reply in character

During chat, the system supports:

- `/save`
- `/reflect`
- `/correct character|target|original|corrected|reason`
- `/quit`

### 4. Correction Memory

If a line is clearly out of character, you can save a correction.  
Later runs will try to avoid repeating the same kind of mistake.

### 5. Markdown Persona Bundle

Character storage is now markdown-first, not legacy JSON-first.

Each character lives under:

- `data/characters/<novel_id>/<character>/PROFILE.md`
- `data/characters/<novel_id>/<character>/NAVIGATION.md`
- `data/characters/<novel_id>/<character>/SOUL.md`
- `data/characters/<novel_id>/<character>/IDENTITY.md`
- `data/characters/<novel_id>/<character>/AGENTS.md`
- `data/characters/<novel_id>/<character>/MEMORY.md`
- `data/characters/<novel_id>/<character>/RELATIONS.md`

## Quick Start

### Step 1: distill characters and extract relations

Using *Dream of the Red Chamber* as an example:

```bash
python -m src.core.main distill --novel data/hongloumeng.txt --characters 林黛玉,贾宝玉 --force
python -m src.core.main extract --novel data/hongloumeng.txt --force
```

This creates:

- `data/characters/hongloumeng/<character>/`
- `data/relations/hongloumeng/hongloumeng_relations.md`

### Step 2: start chatting

Recommended natural-language flow:

```bash
python -m src.core.main chat --novel data/hongloumeng.txt --mode auto --message "让我扮演贾宝玉和林黛玉聊天"
```

Then continue with:

```bash
python -m src.core.main chat --novel data/hongloumeng.txt --session <session_id> --message "妹妹今日可大安了？"
```

If you want to start a group chat:

```bash
python -m src.core.main chat --novel data/sanguo.txt --mode auto --message "进入刘备、张飞、关羽群聊模式"
python -m src.core.main chat --novel data/sanguo.txt --session <session_id> --message "刘备：二位贤弟，近日战事稍歇。"
```

## Other Commands

### View a character profile

```bash
python -m src.core.main view --character 林黛玉 --novel data/hongloumeng.txt
```

### Save a correction

```bash
python -m src.core.main correct \
  --session <session_id> \
  --message "Baoyu plans to leave home and become a merchant" \
  --corrected "Baoyu has long disliked worldly ambition and would rather remain among poetry, gardens, and intimate company" \
  --character 贾宝玉
```

## Command Overview

```bash
python -m src.core.main distill --novel <path> [--characters A,B] [--output <dir>] [--force]
python -m src.core.main extract --novel <path> [--output <path>] [--force]
python -m src.core.main chat --novel <path-or-name> --mode auto|observe|act [--character <name>] [--session <id>] [--message <text>]
python -m src.core.main view --character <name> [--novel <path-or-name>]
python -m src.core.main correct --session <id> --message <raw> --corrected <fixed> [--character <name>] [--target <name>] [--reason <text>]
```

## Project Structure

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
