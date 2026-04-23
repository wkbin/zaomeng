---
name: zaomeng-skill
description: 自包含的小说角色蒸馏与关系构建技能。用于从小说片段提取角色心智档案、关系网和群聊行为约束，输出标准 JSON，并通过一致性和安全门槛校验。
---

# Zaomeng Skill (ClawHub)

## Scope

Execute four tasks in one consistent format:
- Character distillation
- Relationship extraction
- Dialogue behavior constraints for roleplay
- OOC correction memory alignment

This skill is local-first and self-contained at the skill-definition level.
Do not require runtime download of external repositories during execution.

## Phase Workflow

Phase 0: Input normalization
- Accept `.txt` / `.epub` extracted text or pasted chapters.
- Split by chapter; if unavailable, split by token window (`8000` with `200` overlap).
- Keep character full names and unique aliases; avoid generic pronouns unless adjacent-name anchored.

Phase 1: Evidence extraction
- For each chunk, output all major characters in that chunk.
- Capture three evidence types per character:
- Description snippets
- Dialogue snippets
- Inner-thought snippets

Phase 2: Character profile synthesis
- Merge chunk evidence by character name.
- Deduplicate list fields.
- Build final profile using the schema in `references/output_schema.md`.
- Enforce limits:
- `core_traits` <= 10
- `typical_lines` <= 8
- `decision_rules` <= 8
- `values` integers in `[0,10]`

Phase 3: Relationship graph synthesis
- Only process chunks where two or more characters co-occur.
- Use sorted key format `<A>_<B>`.
- Aggregate pair-level stats and one-line interaction summary.

Phase 4: Quality gates
- Gate A: Schema validity (all required keys present)
- Gate B: Evidence coverage (each profile has at least 1 evidence item)
- Gate C: Consistency check (speech style and decision rules do not contradict values)
- Gate D: Safety policy check via `references/safety_policy.md`
- Gate E: Triple validation via `references/validation_policy.md`

If any gate fails, return a `needs_revision` result with missing items.

## Triple Validation Rules

For every major extracted claim (trait, relation, dialogue constraint), run:

1. Evidence Validation
- Must cite at least one direct sentence-level evidence from source text.

2. Consistency Validation
- Must not conflict with `values`, `speech_style`, and `decision_rules`.

3. Transfer Validation
- Must be reusable in a new dialogue turn without changing core persona.

## Output Contract

Produce:
- Character profiles JSON object list
- Relationship graph JSON object
- Optional correction memory JSON entries

Follow exact keys and ranges in `references/output_schema.md`.

## Behavioral Safety Rules

- Do not execute network download or arbitrary shell commands.
- Do not request secrets (API keys, tokens, credentials).
- Do not claim certainty when evidence is weak; label as low-confidence.
- If user asks for real-time external execution, require explicit operator confirmation.

## Prompt Triggers

Trigger this skill when user asks for:
- 小说人物蒸馏 / 角色人设提取
- 角色关系网生成
- 角色群聊设定或 OOC 纠错规则
- 输出结构化 JSON 角色档案

Natural-language trigger mapping:
- "帮我蒸馏这本小说人物" -> `distill`
- "抽关系网" / "角色关系图" -> `extract_relations`
- "按人设群聊" / "角色模拟对话" -> `chat_constraints`
- "这句 OOC 了，帮我纠正" -> `correct_ooc`

Command-style trigger mapping:
- `/distill` -> run Phase 0~2 and output profiles
- `/extract` -> run Phase 0,3 and output relations
- `/chat` -> run dialogue constraints and correction memory retrieval
- `/correct` -> append correction memory with rationale

Pre-response check for `/chat`:
- Check candidate reply against profile (`speech_style`, `values`, `decision_rules`).
- If mismatch: rewrite once with stricter persona constraints.
- If still mismatch: return `needs_revision` with mismatch reasons.

## Update Workflow

Use this workflow when user asks to "update/迭代/修订" the skill output:

1. Diff request
- Identify which target changes: schema, extraction logic, or safety rule.

2. Minimal patch
- Apply smallest valid change while preserving output compatibility.
- Prefer editing `references/` and `prompts/` first, then adjust SKILL steps.

3. Regression check
- Re-run against sample artifacts in `examples/`.
- Ensure keys/ranges still satisfy `references/output_schema.md`.
- Re-run `examples/test-prompts.json` and ensure no new validation failures.

4. Version note
- Add a one-line release note in `PUBLISH.txt` version section.

## Example Artifacts

- `examples/sample_input_excerpt.txt`
- `examples/sample_character_profile.json`
- `examples/sample_relations.json`
- `examples/test-prompts.json`

## Prompt Templates

- `prompts/distill_prompt.txt`
- `prompts/relation_prompt.txt`
- `prompts/correction_prompt.txt`
