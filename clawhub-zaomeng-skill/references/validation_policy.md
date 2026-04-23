# Validation Policy (Triple Validation)

## Purpose

Increase extraction reliability and reduce OOC in roleplay by validating each major output claim.

## Validation Layers

### 1) Evidence Validation

- Every major claim must map to at least one concrete sentence evidence.
- If no evidence exists, downgrade confidence and mark as provisional.

### 2) Consistency Validation

- Trait and decision rules must not contradict `values`.
- Proposed dialogue style must align with `speech_style`.
- Relation score changes must match interaction evidence polarity.

### 3) Transfer Validation

- A claim is valid only if it can be applied to a new dialogue turn while keeping persona stable.
- If applying a claim causes OOC behavior in simulation, reject or revise the claim.

## Pass/Fail Rules

- Pass: all 3 layers pass.
- Soft fail: one layer fails -> keep output with `confidence < 0.6` and revision note.
- Hard fail: two or more layers fail -> return `needs_revision`.

