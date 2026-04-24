---
name: zaomeng-skill
description: zaomeng local skill for character distillation, relationship extraction, and roleplay chat workflows.
---

# Zaomeng Skill

Use this skill to run zaomeng's local workflow without cloud model dependencies.

## Commands

- `python -m src.core.main distill --novel <path> [--characters A,B] [--force]`
- `python -m src.core.main extract --novel <path> [--output <path>] [--force]`
- `python -m src.core.main chat --novel <path-or-name> --mode observe|act [--character <name>]`
- `python -m src.core.main view --character <name> [--novel <path-or-name>]`
- `python -m src.core.main correct --session <id> --message <raw> --corrected <fixed> [--character <name>]`

## Interactive Chat Rule

- `chat` is interactive and should be launched only after user-facing setup is clear.
- Before running `chat`, first confirm:
  - novel path or novel name
  - mode: `observe` or `act`
  - controlled character in `act` mode
  - whether `distill` has already generated profiles
  - whether `extract` has already generated relations if relation-aware chat is expected
- Before entering the session, tell the user what to type as the first turn.
- If the user does not specify one, offer a preset starter:
  - `observe`: `请让大家围绕这件事各说一句。`
  - `act`: `我先表态，你们再接。`
- Do not treat interactive input failure as a reason to simulate stdin or auto-play the conversation unless the user explicitly asks for scripted interaction.

## Data Paths

- `data/characters/<novel_id>/`
- `data/relations/<novel_id>/`
- `data/sessions/`
- `data/corrections/`

## Notes

- The workflow is local-first and does not require OpenAI API access.
- The host project only needs Python plus this repository's CLI entrypoints.
- `distill` and `extract` ask for confirmation by default; in agent-driven runs, confirm with the user first and then use `--force`.
- Do not simulate stdin to bypass confirmation prompts unless the user explicitly wants scripted execution.
