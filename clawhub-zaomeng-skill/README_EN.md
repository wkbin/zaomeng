# zaomeng-skill

`zaomeng-skill` is a skill package for Chinese novel character distillation, relationship extraction, one-on-one roleplay, and group character chat.

Its operating model is straightforward:

- read the novel content
- prepare excerpts, prompts, and references
- hand them to the host LLM for distillation, relation extraction, and dialogue generation

License: `MIT-0` (MIT No Attribution)

## Dialogue Generation

Chat, distillation, and relationship extraction now follow the same **LLM-first** path:

- `zaomeng` prepares persona, relation, memory, and mode constraints
- a generation-capable LLM produces the final reply
- in group chat, later speakers can see replies already produced earlier in the same turn

This skill assumes a host-managed LLM execution environment.

## What It Does

### 1. Distill Characters

Extract character profiles from raw novel text and cover a richer set of dimensions, such as:

- core identity
- core motivation
- personality base
- decision logic
- character arc
- key bonds
- language expression style
- value tradeoff system
- hidden desire
- private self

### 2. Extract Relationships

Extract pairwise relationship graphs from same-scene interactions and generate both graph-level and character-side relation layers.

### 3. Enter Character Chat

Two main interaction modes are supported:

- `act`
  You control one character's line, and the other characters respond in character
- `observe`
  Multiple characters interact around a scene, topic, or opening line

### 4. Save Corrections

If a line is clearly out of character, you can write the correction back into memory and keep using that correction in later dialogue.

## Installation

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

### Install Into A Local Skills Directory

```bash
python scripts/install_skill.py --skills-dir <your-skills-root>
```

## Runtime Requirements

To run the real workflow, the host environment should support:

- local Python command execution
- the dependencies declared in [requirements.txt](requirements.txt)

The skill now exposes prompt-first helper entrypoints:

```text
tools/prepare_novel_excerpt.py
tools/build_prompt_payload.py
tools/materialize_persona_bundle.py
tools/export_relation_graph.py
```

A common prompt-first flow is to prepare an excerpt first and then build a host-side prompt payload.

For example:

```bash
py -3 tools/prepare_novel_excerpt.py --novel <path>
py -3 tools/build_prompt_payload.py --mode distill --novel <path> --characters A,B
py -3 tools/materialize_persona_bundle.py --profile-file <character-dir/PROFILE.generated.md>
py -3 tools/export_relation_graph.py --relations-file <relation-result.md>
```

After the host LLM writes a character's `PROFILE.generated.md`, run
`tools/materialize_persona_bundle.py` immediately. This post-process
materializes the split persona bundle files and `NAVIGATION.generated.md`
for that character instead of leaving the workflow at the single profile file.

## Recommended Usage Flow

The correct order is not to jump into chat immediately.  
**Provide the novel first, distill the characters, and only then enter chat.**

The most common user flow is:

1. provide the novel file or file path
2. say which characters you want distilled in natural language
3. let the host report staged distillation progress and relation-graph generation progress
4. after distillation finishes, enter `act` or `observe`

## Natural-Language Examples

### Distill

```text
Distill Lin Daiyu and Jia Baoyu for me
```

```text
Extract personas for Liu Bei, Zhang Fei, and Guan Yu from this novel
```

### Enter Act Mode

```text
Let me play Jia Baoyu and chat with Lin Daiyu
```

```text
I will play Baoyu. Let Daiyu reply to me
```

### Enter Observe Mode

```text
Enter Liu Bei, Zhang Fei, Guan Yu group chat mode
```

```text
Let everyone say one line about the alliance with Sun Quan
```

## Persona Bundle Structure

Character profile directories typically follow this shape:

```text
runtime/data/characters/<novel_id>/<character_name>/
```

Common files:

- `NAVIGATION.generated.md`
- `NAVIGATION.md`
- `PROFILE.generated.md`
- `PROFILE.md`
- `RELATIONS.generated.md`
- `RELATIONS.md`
- `MEMORY.md`

Depending on available evidence, optional focused persona files may also be generated:

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

Constraints are split into three layers:

- `references/output_schema.md`
  format and field contract
- `references/style_differ.md`
  anti-homogenization and style differentiation
- `references/logic_constraint.md`
  global persona floor, anti-OOC rules, and mode boundaries

If you are checking output quality, these three files should be read together rather than reading only the schema.

## Outputs

- character profiles
- relationship extraction results
- relationship graphs
- in-character dialogue replies

## Publishing Notes

If you publish this skill on its own, it is best to include at least:

- `README.md`
- `README_EN.md`
- `SKILL.md`
- `INSTALL.md`
- `MANIFEST.md`
- `PUBLISH.md`
- `prompts/`
- `references/`
- `tools/`

## License

`MIT-0`
