# Capability Index

## Purpose

This document is the host-side index for the standard `zaomeng` capabilities.

Use it as the first stop when the host needs to answer:

- which command should I call
- what files should I expect
- how do I know whether this capability succeeded

## Capability List

| Capability | Entry | Primary Output | Success Marker |
| --- | --- | --- | --- |
| `distill` | `tools/build_prompt_payload.py --mode distill` | distill payload JSON | capability status with `status=ready` and `success=true` |
| `materialize` | `tools/materialize_persona_bundle.py` | persona bundle files | `ARTIFACT_STATUS.generated.json` plus capability status |
| `export_graph` | `tools/export_relation_graph.py` | relationship graph HTML / SVG / Mermaid | graph `.status.json` plus capability status |
| `verify_workflow` | `tools/verify_host_workflow.py` | workflow verification JSON | capability status with `status=complete` and `success=true` |
| `chat` | `py -3 -m src.cli.app chat` | session summary / chat result / chat status JSON | `chat-status-out` with `status=complete` and `success=true` |

## 1. Distill

Entry:

```bash
py -3 tools/build_prompt_payload.py --mode distill --novel <path> --characters A,B --output <distill_payload.json> --run-manifest <run_manifest.json>
```

Standard outputs:

- distill payload JSON
- optional capability status JSON
- optional `run_manifest.json` updates

Host responsibility after this step:

- hand the payload to the host LLM
- write `PROFILE.generated.md`

Reference:

- `references/output_schema.md`

## 2. Materialize

Entry:

```bash
py -3 tools/materialize_persona_bundle.py --profile-file <character-dir/PROFILE.generated.md> --run-manifest <run_manifest.json>
```

Standard outputs:

- split persona markdown files
- `ARTIFACT_STATUS.generated.json`
- optional capability status JSON

Reference:

- `references/output_schema.md`

## 3. Export Graph

Entry:

```bash
py -3 tools/export_relation_graph.py --relations-file <relations.md> --run-manifest <run_manifest.json>
```

Standard outputs:

- `*_relations.html`
- `*_relations.svg`
- `*_relations.mermaid.md`
- graph `.status.json`

Reference:

- `examples/sample_relations.md`

## 4. Verify Workflow

Entry:

```bash
py -3 tools/verify_host_workflow.py --characters-root <characters/<novel_id>> --relations-file <relations.md> --run-manifest <run_manifest.json>
```

Standard outputs:

- workflow verification JSON
- capability status JSON

Recommended use:

- run after materialize and export graph
- treat it as the final host-side completeness check

## 5. Chat

Entry:

```bash
py -3 -m src.cli.app chat --novel <path> --message "<request>" --session-summary-out <session-summary.json> --chat-result-out <chat-result.json> --chat-status-out <chat.status.json>
```

Standard outputs:

- `session-summary-out`
- `chat-result-out`
- `chat-status-out`

Reference:

- `references/chat_contract.md`
- `examples/chat_session_summary.example.json`
- `examples/chat_result_single_turn.example.json`
- `examples/chat_status_complete.example.json`

## Read Order

Recommended host read order across the whole workflow:

1. read capability status first
2. read the primary output for the current capability
3. if present, read `run_manifest.json` for the updated cross-step index

For `chat`, use the chat-specific order from `references/chat_contract.md`.

## End-To-End Example

For one complete host-side chain from run initialization to chat outputs, see:

- `examples/host_workflow_example.md`
