# Technical Roadmap

This roadmap replaces the earlier "embedded runtime mirror inside the skill"
direction with a cleaner separation:

- the skill becomes a prompt-first package with lightweight helper scripts
- the CLI becomes an independent application entrypoint
- shared Python code lives in reusable core modules instead of inside the skill

The target is to stop treating the skill as a bundled executable runtime while
still preserving the useful shared logic for text preparation, storage,
prompting, parsing, and result shaping.

## Architectural Direction

Target boundaries:

- `skill`
  - `SKILL.md`
  - prompts and reference docs
  - lightweight helper scripts such as text loading, chunking, transcoding,
    excerpt preparation, and output shaping
- `cli`
  - argparse / subcommands
  - interactive session UX
  - local developer workflow and standalone automation
- `shared core`
  - config and contracts that are not CLI-specific
  - text loading / parsing / chunking
  - prompt assembly helpers
  - persona / relation storage helpers
  - result parsing and post-processing
  - host LLM adaptation

Non-target:

- the skill should not permanently carry a mirrored CLI runtime as its primary
  execution model

## P0: Reset The Boundary

Status: completed

Goals:
- Replace the old roadmap assumptions before more code accumulates on them.
- Document the new skill / CLI / shared-core split clearly.
- Mark the embedded runtime path as transitional rather than strategic.

Tasks:
1. Rewrite the roadmap around prompt-first skill packaging.
2. Update packaging language so "skill = packaged runtime" is no longer the
   long-term story.
3. Add the first lightweight skill helper script that does useful preprocessing
   without depending on the CLI shell.
4. Start carving out shared helper modules for skill-side utilities.

## P1: Extract Skill Helpers From CLI Assumptions

Status: completed

Goals:
- Let the skill call prompts plus helper scripts directly.
- Move preprocessing responsibilities out of the embedded runtime mindset.

Tasks:
1. Create a dedicated shared helper area for novel text loading, decoding,
   sentence splitting, chunking, and excerpt preparation.
2. Ship those helpers inside the skill as standalone scripts the host can call
   directly.
3. Add helper scripts for:
   - loading and transcoding raw novel text
   - building prompt-sized excerpts
   - normalizing output file names and directories
   - exporting compact structured payloads for prompts
4. Keep helper scripts stateless and file-oriented so hosts can compose them
   without importing the CLI.
5. Add focused tests for helper behavior on Chinese text, encoding edge cases,
   and excerpt boundaries.

## P2: Shrink The Skill Package

Status: completed

Goals:
- Remove the skill's dependency on a bundled CLI runtime.
- Keep only prompt assets, references, examples, and helper scripts.

Tasks:
1. Stop documenting `runtime/zaomeng_cli.py` as the preferred skill execution
   path.
2. Remove runtime-mirror assumptions from packaging docs and tests.
3. Replace runtime-oriented manifest entries with prompt/helper-oriented ones.
4. Move any still-useful runtime utilities into shared core or skill helpers.
5. Remove runtime mirror tooling after the skill package no longer ships a
   bundled runtime.

## P3: Make CLI A Standalone Product Layer

Status: completed

Goals:
- Let the CLI evolve independently from the skill package.
- Keep its UX, subcommands, and local automation without forcing those concerns
  into the skill bundle.

Tasks:
1. Move argparse entrypoints and session-oriented orchestration behind a
   dedicated CLI layer.
2. Keep interactive chat/session UX as CLI-only behavior.
3. Make the CLI consume shared prompts/helpers/core modules rather than owning
   duplicated logic.
4. Split CLI packaging, docs, and tests from skill packaging checks.

## P4: Consolidate Shared Core

Status: completed

Goals:
- Reuse domain logic without coupling it to either the skill shell or the CLI
  shell.
- Keep the genuinely reusable pieces in one place.

Tasks:
1. Separate CLI-only modules from reusable core modules.
2. Keep host LLM integration in shared core so both CLI and host-driven skill
   flows can reuse it.
3. Centralize prompt assembly, output parsing, storage helpers, and relation
   rendering helpers.
4. Continue improving contracts for sessions, relations, visualization, and
   persistence only where they remain useful outside the CLI.

## P5: Quality And Reliability

Status: completed

Goals:
- Preserve current behavior quality while the architecture shifts.
- Keep regressions visible as responsibilities move.

Tasks:
1. Expand regression coverage for:
   - text decoding
   - excerpt preparation
   - profile completeness
   - cross-character differentiation
   - relation extraction quality
   - persona fidelity in chat
2. Keep LLM availability as a hard prerequisite for real generation flows.
3. Continue reducing authored fallback prose and keep rules structural.
4. Validate that skill docs, release docs, and packaging docs all describe the
   same architecture.

## Next Focus: Productization Priorities

The architecture reset is largely done. The next phase is no longer about
separating skill and CLI boundaries; it is about making the host-driven flow
stable, high-quality, and release-ready.

The highest-value work now is:

1. make the host execution path complete by default
2. keep multi-character distillation differentiated
3. make exported artifacts stable and easy to inspect
4. turn regression coverage into true workflow coverage
5. reduce manual release and version drift

## P6: Host Workflow Completion

Status: completed

Goals:
- Make the host-driven path succeed end-to-end without manual glue steps.
- Ensure a completed distillation run leaves a complete, usable artifact set.

Tasks:
1. Make the expected host sequence explicit:
   - excerpt
   - prompt payload
   - host generation
   - persona bundle materialization
   - relation graph export
   - chat-ready state
2. Add clear success and failure markers for each stage so users do not confuse
   partial output with completion.
3. Ensure `PROFILE.generated.md` is never treated as the final state when split
   persona files are expected.
4. Tighten host-facing docs so persona materialization and graph export are
   described as standard post-process steps, not optional cleanup.
5. Add workflow checks that verify the complete artifact set after a host run.

## P7: Distillation Quality And Differentiation

Status: completed

Goals:
- Reduce cross-character homogenization.
- Improve character uniqueness without drifting away from source evidence.

Tasks:
1. Keep multi-character distillation isolated per character rather than sharing
   a blended generation context.
2. Strengthen differentiation checks for:
   - identity anchor
   - soul goal
   - background imprint
   - social mode
   - reward logic
   - belief anchor
   - temperament type
   - stress response
3. Add post-generation overlap warnings when multiple characters converge on
   near-identical descriptions.
4. Prefer empty / evidence-insufficient outputs over generic filler text when
   character-specific support is weak.
5. Add fixed regression fixtures for a few known novels and character sets to
   catch quality collapse early.

## P8: Graph Export Stability And UX

Status: completed

Goals:
- Make relationship graph outputs reliable when opened directly by users.
- Keep the visualization useful even when local browser policies are strict.

Tasks:
1. Treat static SVG as the preferred display artifact when available.
2. Keep Mermaid source as a reference/debug artifact rather than the only
   rendering path.
3. Continue hardening HTML output for `file://` opening, browser script
   restrictions, and local rendering differences.
4. Keep graph layout readable:
   - no table/card horizontal squeeze
   - predictable mobile fallback
   - graceful empty/error states
5. Add regression coverage for exported HTML/SVG structure and artifact paths.

## P9: End-To-End Regression Coverage

Status: completed

Goals:
- Test the product the way users actually use it.
- Catch workflow regressions instead of only local helper regressions.

Tasks:
1. Add end-to-end regression cases that cover:
   - excerpt generation
   - payload generation
   - host output ingestion
   - persona bundle completion
   - relation graph export
   - chat-ready persona loading
2. Keep at least one fixture focused on profile completeness.
3. Keep at least one fixture focused on relationship graph generation.
4. Keep at least one fixture focused on persona fidelity in `act` / `observe`
   flows.
5. Prefer stable fixture assertions over brittle wording checks where possible.

## P10: Versioning, Metadata, And Release Discipline

Status: completed

Goals:
- Reduce manual drift across docs, metadata, examples, and release assets.
- Make the skill package look and behave like a polished published artifact.

Tasks:
1. Keep machine-readable metadata as a first-class part of the skill package.
2. Reduce version drift between:
   - `SKILL.md`
   - `README.md`
   - `README_EN.md`
   - `PUBLISH.md`
   - examples
   - metadata
3. Consider introducing a single version source plus a sync step for release
   files.
4. Keep packaging docs, install docs, and skill metadata aligned.
5. Keep release packaging deterministic and easy to validate before publish.

## P11: Host Capability Contract

Status: in progress

Goals:
- Make the skill's host-facing abilities explicit and stable.
- Let hosts chain the workflow by reading standard outputs rather than guessing
  which step should happen next.

Tasks:
1. Define the four host-facing capabilities clearly:
   - `distill`
   - `materialize`
   - `export_graph`
   - `verify_workflow`
2. Give each capability a standard contract:
   - expected inputs
   - output JSON shape
   - sidecar status file
   - success marker
3. Introduce a shared `run_manifest.json` so the host can track the whole run
   from one canonical file.
4. Make helper scripts update the manifest and their own status files directly.
5. Document the capability contract in the skill itself so hosts can implement
   the flow without reverse-engineering helper behavior.

## P12: Distill UX And Artifact Index

Status: in progress

Goals:
- Standardize progress reporting during distillation.
- Make final artifacts easy to find and inspect from one place.

Tasks:
1. Standardize progress stages for host-driven runs:
   - locked characters
   - current character
   - completed count
   - graph exporting
   - graph completed
2. Keep those progress events written into `run_manifest.json`, not only printed
   to the terminal.
3. Ensure the final manifest directly points to:
   - character directories
   - relation graph HTML
   - relation graph SVG
   - capability status files
4. Add a lightweight host helper to append progress events without depending on
   CLI-only orchestration.
5. Add regression coverage for manifest updates across the standard host flow.

## Non-Goals For Now

- No heavy DI framework.
- No Git submodule split.
- No plugin system redesign in this phase.
- No service-style metrics stack while the project is still CLI/skill oriented.
