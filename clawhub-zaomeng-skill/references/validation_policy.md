# Validation Policy (Triple Validation)

## Purpose

Increase extraction reliability and reduce OOC behavior by validating each major output claim.

## Validation Layers

### 1) Evidence Validation

- Every major claim must map to at least one concrete sentence-level source snippet.
- If no evidence exists, the claim should not be finalized.

### 2) Consistency Validation

- Traits and decision rules must not contradict `values`.
- Proposed dialogue style must align with `speech_style`.
- Relation score changes must match interaction evidence polarity.

### 3) Transfer Validation

- A claim is valid only if it can be reused in a new dialogue turn while keeping persona stable.
- If applying a claim causes OOC behavior in simulation, reject or revise the claim.

## Pass/Fail Rules

- Pass: all 3 layers pass.
- Soft fail: 1 layer fails -> revise before final output.
- Hard fail: 2 or more layers fail -> return `needs_revision`.
