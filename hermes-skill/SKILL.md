---
name: hermes-zaomeng
description: Hermes Agent adapter for zaomeng local novel distillation, relation extraction, and roleplay chat.
---

# Hermes Adapter

## Supported Tasks

1. Distill character profiles from `.txt` and `.epub` novels.
2. Build pairwise relationship graphs for same-sentence co-occurring characters.
3. Run immersive `observe` or `act` chat sessions.
4. Persist and retrieve correction memories to reduce repeated OOC behavior.

## Invocation Mapping

- Distillation: `python -m src.core.main distill --novel ...`
- Relationship extraction: `python -m src.core.main extract --novel ...`
- Session chat: `python -m src.core.main chat --novel ... --mode ...`
- Character view: `python -m src.core.main view --character ... [--novel ...]`
- Manual correction: `python -m src.core.main correct --session ... --message ... --corrected ...`

## Interactive Chat Rule

- `chat` must be treated as an interactive terminal session.
- Before running it, first confirm the novel, mode, and controlled character for `act` mode.
- Also confirm whether character profiles already exist, and whether relation extraction has already been run if relation-aware chat is expected.
- Before entering the session, tell the user what to type as the first turn.
- If the user has no preference, offer a starter turn:
  - `observe`: `请让大家围绕这件事各说一句。`
  - `act`: `我先表态，你们再接。`
- Do not try to recover by faking PTY input or writing an auto-input script unless the user explicitly requests scripted chat.

## Behavioral Constraints

- Use chunk strategy with token window and overlap for long novels.
- Save durable artifacts under novel-scoped `data/` directories.
- Show local token/cost stats from `llm_client.py`.
- Enforce the daily budget from `config.yaml`.
- Treat `distill` and `extract` as confirmation-gated commands unless `--force` is explicitly chosen after user confirmation.
- Do not work around confirmation prompts by faking stdin unless the user explicitly asks for scripted execution.
- Do not rely on external cloud model providers.
