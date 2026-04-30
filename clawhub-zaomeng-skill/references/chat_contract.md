# Chat Contract

## Purpose

This document defines the host-facing contract for `chat` execution.

The goal is simple:

- the host should not infer success from console text
- the host should not reverse-engineer session markdown
- the host should have stable machine-readable outputs for `act`, `insert`, and `observe`

## Supported Modes

- `act`
  the user speaks as one existing character
- `insert`
  the user enters the scene as themselves
- `observe`
  the user stays outside the scene and watches the cast continue

## Recommended Invocation

```bash
py -3 -m src.cli.app chat \
  --novel <novel-path-or-name> \
  --message "<request-or-user-turn>" \
  --session-summary-out <session-summary.json> \
  --chat-result-out <chat-result.json> \
  --chat-status-out <chat.status.json>
```

## Input Expectations

Minimum required inputs:

- `--novel`
- either:
  - `--message`
  - or `--session` for an existing session

Optional host-facing outputs:

- `--session-summary-out`
- `--chat-result-out`
- `--chat-status-out`

## Output Files

### 1. Session Summary

`session-summary-out` is the long-lived state snapshot for the current session.

Minimum fields:

- `status`
- `session_id`
- `novel_id`
- `mode`
- `participants`
- `controlled_character`
- `focus_targets`
- `history_count`
- `artifacts.session_file`
- `artifacts.relation_snapshot_file`

Conditional fields:

- `self_insert`
  only for `insert`
- `latest_responses`
  present after non-interactive single-turn execution

Reference example:

- `examples/chat_session_summary.example.json`

### 2. Chat Result

`chat-result-out` is the action payload for the current `chat` call.

Required fields:

- `kind = "zaomeng_chat_result"`
- `action = "setup" | "single_turn" | "interactive_ready"`
- `success`
- `mode`
- `session_id`
- `novel_id`
- `participants`
- `responses`
- `summary`

Reference example:

- `examples/chat_result_single_turn.example.json`

### 3. Chat Status

`chat-status-out` is the capability-style success marker for the current `chat` call.

Required fields:

- `kind = "host_capability_status"`
- `capability = "chat"`
- `status = "complete" | "error"`
- `success = true | false`
- `message`
- `inputs`
- `outputs`

Reference example:

- `examples/chat_status_complete.example.json`

## Action Semantics

### `setup`

Used when the request only establishes mode / participants / self-insert card and does not yet run a reply turn.

Expected behavior:

- a session is created or updated
- `responses` is empty
- `session-summary-out` is already meaningful and should be read immediately

### `single_turn`

Used when a non-interactive turn is executed immediately from `--message`.

Expected behavior:

- `responses` contains the current turn output
- `summary.latest_responses` should align with `responses`
- the host can render the result directly from `chat-result-out`

### `interactive_ready`

Used when the CLI enters interactive mode and the host wants an initial machine-readable snapshot before the first turn.

Expected behavior:

- `responses` is empty
- `summary` describes the initial mode and participants

## Recommended Read Order

1. read `chat-status-out`
   confirm whether the current `chat` call succeeded
2. read `chat-result-out`
   consume the immediate result of this action
3. read `session-summary-out`
   keep tracking the latest session state

## Host Integration Notes

- Treat JSON outputs as the source of truth for orchestration
- Treat markdown session files as persistence, not as the primary integration contract
- Prefer `session-summary-out` for UI state and session continuity
- Prefer `chat-result-out` for rendering the latest action result
- Prefer `chat-status-out` for capability success/failure checks
