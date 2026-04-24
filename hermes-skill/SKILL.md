---
name: hermes-zaomeng
description: Hermes Agent adapter for zaomeng local novel distillation, relation extraction, and roleplay chat.
---

# Hermes Adapter

## Supported Tasks

1. Distill character profiles from `.txt` and `.epub` novels.
2. Build pairwise relationship graphs for same-sentence co-occurring characters.
3. Run `observe` or `act` roleplay turns.
4. Persist and retrieve correction memories to reduce repeated OOC behavior.

## Invocation Mapping

- Distillation: `python -m src.core.main distill --novel ... [--characters A,B] [--force]`
- Relationship extraction: `python -m src.core.main extract --novel ... [--force]`
- One-shot observe chat: `python -m src.core.main chat --novel ... --mode observe --message "..."`
- One-shot act chat: `python -m src.core.main chat --novel ... --mode act --character ... --message "..."`
- Continued agent turn: `python -m src.core.main chat --novel ... --mode ... [--character ...] --session <id> --message "..."`
- Interactive chat: `python -m src.core.main chat --novel ... --mode ... [--character ...]`
- Character view: `python -m src.core.main view --character ... [--novel ...]`
- Manual correction: `python -m src.core.main correct --session ... --message ... --corrected ...`

## Chat Execution Rule

- Default rule: when Hermes runs `chat`, it must use `--message`.
- Do not treat `chat` as an interactive terminal command unless the user explicitly asks for a live terminal session.
- Do not say the environment lacks PTY or interactive input before first trying `chat ... --message "..."`.
- Do not recover by scripting stdin when `--message` can express the request directly.
- For multi-turn agent workflows, keep reusing `--session <id> --message "..."` instead of switching to terminal interaction.
- If relation-aware replies are expected, ensure `extract` has already been run.
- If `act` mode is requested, require `--character`.
- Recommended starter turns:
  - `observe`: `请让大家围绕这件事各说一句。`
  - `act`: `我先表态，你们再接。`

## Behavioral Constraints

- Use chunk strategy with token window and overlap for long novels.
- Save durable artifacts under novel-scoped `data/` directories.
- Show local token/cost stats from `llm_client.py`.
- Enforce the daily budget from `config.yaml`.
- Treat `distill` and `extract` as confirmation-gated commands unless `--force` is explicitly chosen after user confirmation.
- Do not work around confirmation prompts by faking stdin unless the user explicitly asks for scripted execution.
- Do not rely on external cloud model providers.
