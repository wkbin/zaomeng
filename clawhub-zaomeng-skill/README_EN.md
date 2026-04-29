# zaomeng-skill

`zaomeng-skill` is a host-driven skill for Chinese novel character distillation, relationship extraction, relationship graph export, and in-character dialogue.

Its operating model is simple:

- read the novel content
- prepare excerpts, prompts, and references
- hand them to the host LLM for generation
- materialize the canonical profile into a complete persona bundle

This skill runs inside a host-managed environment; the host performs the actual model call, and the packaged Python helper dependencies are declared in `requirements.txt`.

## Overview

| Item | Value |
| --- | --- |
| Name | `zaomeng-skill` |
| Version | `4.1.3` |
| Mode | LLM-first |
| Host Targets | OpenClaw, ClawHub, Hermes, other host-managed agents |
| Core Capabilities | character distillation, relation extraction, graph export, one-on-one roleplay, group chat |
| License | `MIT-0` |

## What It Does

### 1. Distill Characters

Extract structured character profiles from novel text, covering richer dimensions such as:

- core identity
- core motivation
- personality base
- decision logic
- character arc
- key bonds
- speech and expression style
- value tradeoffs
- hidden desire
- private self

### 2. Extract Relationships

Extract pairwise relationships from same-scene interactions and output:

- relationship markdown
- Mermaid source
- HTML visualization

### 3. Enter Character Chat

Two main modes are supported:

- `act`
  you play one character, and the others respond in character
- `observe`
  multiple characters talk around a scene, topic, or opening line

### 4. Save Corrections

If a line is clearly out of character, the correction can be written back into memory and reused in later turns.

## Workflow

### Standard Flow

1. provide the novel file or text
2. generate an excerpt
3. build a distill or relation prompt payload
4. hand the payload to the host LLM
5. if the host writes `PROFILE.generated.md`, materialize the persona bundle
6. export the relationship graph
7. then enter `act` or `observe`

### Distill Post-Process

Once the host LLM writes `PROFILE.generated.md`, do not stop at that single file.  
Run `tools/materialize_persona_bundle.py` immediately to materialize the canonical profile into a complete persona bundle, including:

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

## Installation

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

### Install Into A Local Skills Directory

```bash
python scripts/install_skill.py --skills-dir <your-skills-root>
```

## Helper Commands

```bash
py -3 tools/prepare_novel_excerpt.py --novel <path> [--max-sentences 80] [--max-chars 12000]
py -3 tools/build_prompt_payload.py --mode distill|relation --novel <path> [--characters A,B]
py -3 tools/materialize_persona_bundle.py --profile-file <character-dir/PROFILE.generated.md>
py -3 tools/export_relation_graph.py --relations-file <relation-result.md>
```

## Recommended Usage

Do not jump into chat first.  
**Provide the novel, distill the characters, then enter dialogue.**

The most common user flow is:

1. provide the novel file or path
2. specify which characters to distill
3. let the host report stage-by-stage progress
4. inspect persona files or relationship graphs
5. enter `act` or `observe`

## Examples

### Distill

```text
Distill Lin Daiyu and Jia Baoyu from this novel
```

```text
Extract character profiles for Liu Bei, Zhang Fei, and Guan Yu
```

### Enter act

```text
Let me play Jia Baoyu and have Lin Daiyu reply
```

```text
I will speak as Baoyu, and you let Daiyu answer me
```

### Enter observe

```text
Start a Liu Bei, Zhang Fei, and Guan Yu group chat
```

```text
Let everyone say one line about whether to ally with Sun Quan
```

## Persona Bundle Layout

The character directory usually looks like this:

```text
runtime/data/characters/<novel_id>/<character_name>/
```

Common files:

- `PROFILE.generated.md`
- `PROFILE.md`
- `NAVIGATION.generated.md`
- `NAVIGATION.md`
- `MEMORY.generated.md`
- `MEMORY.md`

Depending on available evidence, split persona files may also exist:

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

## Constraint Files

- `references/output_schema.md`
  output format and field definition
- `references/style_differ.md`
  anti-homogenization and style differentiation
- `references/logic_constraint.md`
  persona boundaries and anti-OOC rules
- `references/validation_policy.md`
  output validation and self-check policy

## Release Payload

Recommended release contents:

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
