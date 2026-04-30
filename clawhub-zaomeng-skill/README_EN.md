# zaomeng-skill

`zaomeng-skill` is a host-driven skill for Chinese novel character distillation, relationship extraction, relationship graph export, and in-character dialogue.

Its operating model is simple:

- read the novel content
- prepare excerpts, prompts, and references
- hand them to the host LLM for generation
- materialize the canonical profile into a complete persona bundle

It supports two distillation modes:

- fresh distillation: create a new persona bundle for a character
- incremental distillation: if a persona bundle already exists, reuse prior profile data, memory, and user corrections, then update only where new evidence supports it

Incremental distillation is especially useful when:

- a serialized novel keeps getting new chapters and you only want to feed the latest excerpt
- a long novel is processed in batches instead of all at once
- persona quality improves through repeated user corrections that should be preserved
- an existing persona bundle should be extended, not rebuilt from scratch, every time new material appears

This skill runs inside a host-managed environment; the host performs the actual model call, and the packaged Python helper dependencies are declared in `requirements.txt`.

## Overview

| Item | Value |
| --- | --- |
| Name | `zaomeng-skill` |
| Version | `4.1.3` |
| Mode | LLM-first |
| Host Targets | OpenClaw, ClawHub, Hermes, other host-managed agents |
| Core Capabilities | character distillation with incremental updates, relation extraction, graph export, one-on-one roleplay, group chat |
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
  you play one character, either in one-on-one dialogue or by joining a group scene directly
- `insert`
  you enter the scene as yourself rather than as an existing novel character
- `observe`
  multiple characters talk around a scene, topic, or opening line

In practice:

- `act`: you are inside the scene as one of the characters
- `insert`: you are inside the scene as yourself, and the cast responds to you as an in-world visitor
- `observe`: you stay outside the scene and watch the characters carry it forward

### 4. Save Corrections

If a line is clearly out of character, the correction can be written back into memory and reused in later turns.

## Workflow

### Standard Flow

1. provide the novel file or text
2. generate a character-aware excerpt
3. build a distill or relation prompt payload
4. hand the payload to the host LLM
5. if the host writes `PROFILE.generated.md`, materialize the persona bundle
6. export the relationship graph
7. then enter `act`, `insert`, or `observe`

For multi-character distillation, do not just clip the opening of the novel. Pass `--characters` so the helper can pull windows around the requested cast, especially when one of them first appears much later in the source.

### Incremental Distillation

If a character bundle already exists for the same novel, the skill automatically treats the next distillation as an incremental update:

- detect `data/characters/<novel_id>/<character_name>/`
- merge existing `PROFILE`, split persona files, and `MEMORY` into `request.existing_profiles`
- mark `request.update_mode` as `incremental`
- write the incremental context into `run_manifest.json -> artifacts.distill_context`

This lets the host chain the workflow directly without guessing whether the run is creating a new persona or updating an existing one.

The distill payload also exposes `request.excerpt_focus`:

- `requested_characters`
- `matched_characters`
- `missing_characters`
- `strategy`

The host can use this to detect missing cast coverage before the LLM starts distilling from the wrong evidence.

### Chat Session Summary

For host-managed chat flows, the CLI can emit a host-facing session summary JSON:

```bash
py -3 -m src.cli.app chat --novel <path> --message "<request>" --session-summary-out <session-summary.json>
```

This summary keeps the host from reverse-engineering the session markdown. It includes:

- `mode`
- `participants`
- `controlled_character`
- `focus_targets`
- `self_insert` when the session is in `insert` mode
- `latest_responses` after a non-interactive turn
- `artifacts.session_file`
- `artifacts.relation_snapshot_file`

Recommended host behavior:

- read it right after setup-only chat requests
- read it again after each single-turn run
- treat it as the standard source for current mode, active cast, and self-insert identity

### Chat Result And Status

If the host also wants a direct success marker and a single payload for the current chat action, it can request:

```bash
py -3 -m src.cli.app chat --novel <path> --message "<request>" --chat-result-out <chat-result.json> --chat-status-out <chat.status.json>
```

Recommended interpretation:

- `chat-result-out`: the action payload for `setup`, `single_turn`, or `interactive_ready`
- `chat-status-out`: the capability-style success marker for the current chat call

You can also use the packaged examples directly:

- `examples/chat_session_summary.example.json`
- `examples/chat_result_single_turn.example.json`
- `examples/chat_status_complete.example.json`

The field contract is defined in:

- `references/chat_contract.md`
- `references/capability_index.md`

For a full end-to-end host example, see:

- `examples/host_workflow_example.md`

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
py -3 tools/prepare_novel_excerpt.py --novel <path> [--characters A,B] [--max-sentences 120] [--max-chars 50000]
py -3 tools/build_prompt_payload.py --mode distill|relation --novel <path> [--characters A,B] [--characters-root <data/characters or data/characters/<novel_id>>] [--update-mode auto|create|incremental]
py -3 tools/materialize_persona_bundle.py --profile-file <character-dir/PROFILE.generated.md>
py -3 tools/export_relation_graph.py --relations-file <relation-result.md>
py -3 tools/verify_host_workflow.py --characters-root <characters/<novel_id>> [--relations-file <relation-result.md>]
```

```bash
py -3 tools/prepare_novel_excerpt.py --novel shirizhongyan.txt --characters 齐夏,肖冉,章晨泽 --max-chars 50000
```

## Recommended Usage

Do not jump into chat first.  
**Provide the novel, distill the characters, then enter dialogue.**

The most common user flow is:

1. provide the novel file or path
2. specify which characters to distill
3. let the host report stage-by-stage progress
4. inspect persona files or relationship graphs
5. enter `act`, `insert`, or `observe`

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

### Enter insert

```text
Let me enter Dream of the Red Chamber as myself and talk with Lin Daiyu and Jia Baoyu
```

```text
Put me into a Three Kingdoms scene as a newly arrived guest, and let me speak with Liu Bei and the others
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
