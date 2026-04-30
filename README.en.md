# zaomeng

[中文](README.md) | [English](README.en.md)

`zaomeng` is not a generic chatbot project.

It is a focused system for character distillation, relationship graph generation, and in-character interaction for fiction:

- distill reusable character profiles from novels
- extract relationships and generate relationship graphs
- let characters speak according to their persona, stance, bonds, and memory

If what you want is not “an AI chatting pleasantly” but “Lin Daiyu sounds like Lin Daiyu, Liu Bei sounds like Liu Bei, and a whole cast can share one scene without collapsing into one voice,” that is exactly what this project is trying to do.

## What You Can Do With It

### 1. Distill Characters

Give it a novel, and it will try to build reusable character profiles from the original text, including:

- personality tone
- core motivation
- speaking style
- decision logic
- relationship tendency
- emotional triggers
- character arc

The goal is not a one-page summary. The goal is a profile that can keep supporting later dialogue, roleplay, and correction.

The project currently supports two distillation paths:

- fresh distillation: build a persona for a character for the first time
- incremental distillation: if that character already exists, reuse prior profile data, memory, and user corrections, then keep updating from new evidence

### 2. Generate Relationship Graphs

It does not stop at structured relationship fields. It also exports visual relationship graphs so you can quickly see:

- who trusts whom
- who depends on whom
- where tension or rivalry lives

Typical outputs include:

- relationship Markdown
- Mermaid source
- HTML graph
- SVG graph

### 3. Enter Character Interaction

After distillation, there are now **3 modes**, not 2:

- `act`: you speak as one character, either in one-on-one dialogue or by joining a group scene directly
- `insert`: you do not play an existing character; you enter the scene as yourself and interact with the cast directly
- `observe`: you stay out of the scene and watch several characters carry the scene forward

The split is simple:

- use `act` when you want to step in as a role
- use `insert` when you want yourself to enter the fictional world
- use `observe` when you want to watch the cast interact without speaking as anyone

The first `insert` session creates a lightweight scene card for you, usually including:

- how the cast should address you
- what identity you have inside the scene
- whether you want natural, immersive, or probing interaction
- how much your presence should affect the scene

## How It Works Now

The current version is **LLM-first**:

- the host or runtime LLM does the actual language generation
- `zaomeng` prepares prompts, constraints, persona files, relationship context, and helper outputs
- the skill path prefers reusing the host model that already exists

The emphasis is no longer “hardcode a pile of rules and glue lines together.” The emphasis is giving the model clearer persona and relationship constraints so the output sounds more alive and more faithful to the source character.

## Recommended First-Time Flow

The simplest order is:

1. provide the novel text
2. say which characters you want distilled
3. wait for persona files and the relationship graph
4. enter `act`, `insert`, or `observe`

### Natural-Language Requests That Work Well

```text
Distill Lin Daiyu, Jia Baoyu, and Xue Baochai from this novel
```

```text
After distillation, put Lin Daiyu, Jia Baoyu, and Xue Baochai into group chat mode
```

```text
Let me play Jia Baoyu. Lin Daiyu should answer me
```

```text
Let me enter Dream of the Red Chamber as myself and talk with Lin Daiyu and Jia Baoyu
```

```text
Generate the relationship graph. I want the HTML and SVG versions
```

## Project Layout

The repository is roughly split into three layers:

- `src/`: core source code
- `clawhub-zaomeng-skill/`: the publishable skill bundle
- `tests/`: regression tests

The most important assets inside the skill bundle are:

- `prompts/`
- `references/`
- `tools/prepare_novel_excerpt.py`
- `tools/build_prompt_payload.py`
- `tools/export_relation_graph.py`

## One-Line Summary

`zaomeng` is not trying to be “an AI that can talk.”

It is trying to let fictional people speak again with their own personality, relationships, tone, and memory intact.

## License

Main project: `AGPL-3.0-only`

`clawhub-zaomeng-skill`: `MIT-0`
