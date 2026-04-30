# Host Workflow Example

This example shows one complete host-side flow:

1. initialize `run_manifest.json`
2. build distill payload
3. call the host LLM and write `PROFILE.generated.md`
4. materialize persona bundles
5. export the relationship graph
6. verify workflow completeness
7. hand off to host-driven dialogue

## 1. Initialize The Run

```bash
py -3 tools/init_host_run.py --novel data/hongloumeng.txt --characters 林黛玉,贾宝玉,薛宝钗 --output runtime/run_manifest.json
```

Host expectation:

- `runtime/run_manifest.json` is created
- locked characters are recorded
- progress starts at `characters_locked`

## 2. Build Distill Payload

```bash
py -3 tools/build_prompt_payload.py --mode distill --novel data/hongloumeng.txt --characters 林黛玉,贾宝玉,薛宝钗 --output runtime/distill_payload.json --run-manifest runtime/run_manifest.json
```

Host expectation:

- `runtime/distill_payload.json` is ready
- the host LLM reads this payload
- `run_manifest.json` records payload paths and status updates

## 3. Generate Canonical Profiles

The host LLM consumes `runtime/distill_payload.json` and writes one canonical file per character:

```text
runtime/data/characters/hongloumeng/林黛玉/PROFILE.generated.md
runtime/data/characters/hongloumeng/贾宝玉/PROFILE.generated.md
runtime/data/characters/hongloumeng/薛宝钗/PROFILE.generated.md
```

Recommended host progress updates during generation:

```bash
py -3 tools/update_run_progress.py --run-manifest runtime/run_manifest.json --stage character_started --character 林黛玉 --message "正在蒸馏林黛玉"
py -3 tools/update_run_progress.py --run-manifest runtime/run_manifest.json --stage character_completed --character 林黛玉 --message "林黛玉已完成"
```

Repeat for each character.

## 4. Materialize Persona Bundles

```bash
py -3 tools/materialize_persona_bundle.py --profile-file runtime/data/characters/hongloumeng/林黛玉/PROFILE.generated.md --run-manifest runtime/run_manifest.json
py -3 tools/materialize_persona_bundle.py --profile-file runtime/data/characters/hongloumeng/贾宝玉/PROFILE.generated.md --run-manifest runtime/run_manifest.json
py -3 tools/materialize_persona_bundle.py --profile-file runtime/data/characters/hongloumeng/薛宝钗/PROFILE.generated.md --run-manifest runtime/run_manifest.json
```

Host expectation:

- split persona files appear
- `ARTIFACT_STATUS.generated.json` appears in each character directory
- `run_manifest.json` records each character directory

## 5. Generate Relation Result And Export Graph

First, the host LLM writes the relationship markdown:

```text
runtime/data/relations/hongloumeng_relations.md
```

Then export the graph:

```bash
py -3 tools/export_relation_graph.py --relations-file runtime/data/relations/hongloumeng_relations.md --run-manifest runtime/run_manifest.json
```

Host expectation:

- `*_relations.html`
- `*_relations.svg`
- `*_relations.mermaid.md`
- graph status JSON

## 6. Verify Workflow

```bash
py -3 tools/verify_host_workflow.py --characters-root runtime/data/characters/hongloumeng --relations-file runtime/data/relations/hongloumeng_relations.md --run-manifest runtime/run_manifest.json
```

Host expectation:

- workflow verification JSON is written
- `run_manifest.json` ends in a complete state

## 7. Hand Off To Dialogue

At this point, the host already has everything needed to enter `act`, `insert`, or `observe`:

- character directories
- `PROFILE.md`
- split persona files
- `MEMORY.md`
- relation markdown
- graph HTML / SVG
- `run_manifest.json`

The host now drives the dialogue directly with its own LLM.

## Recommended UI / Agent Surfacing

After the workflow completes, a host can safely surface:

- character directories
- relationship graph HTML / SVG
- workflow summary from `run_manifest.json`
- a clear invitation to enter `act`, `insert`, or `observe`

## Cross References

- `references/capability_index.md`
- `references/chat_contract.md`
