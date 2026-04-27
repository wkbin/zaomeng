---
default_nav_load_order: [SOUL, GOALS, STYLE, TRAUMA, IDENTITY, AGENTS, RELATIONS, MEMORY]
persona_file_catalog:
  SOUL:
    optional: false
    role: "core values, worldview, boundaries"
    behaviors: "stance, taboo, refusal, value judgment"
    write_policy: "manual_edit"
  GOALS:
    optional: true
    role: "long-term drive, unfinished desire, decision priority"
    behaviors: "strategic preference, ambition, long arc pressure"
    write_policy: "manual_edit"
  STYLE:
    optional: true
    role: "signature phrasing, cadence, surface emotion, sample lines"
    behaviors: "word choice, sentence length, tone, signature wording"
    write_policy: "manual_edit"
  TRAUMA:
    optional: true
    role: "pain points, scars, taboo triggers, never-do rules"
    behaviors: "trigger reactions, avoidance, hard boundaries"
    write_policy: "manual_edit"
  IDENTITY:
    optional: false
    role: "background, lived experience, habits, emotion profile"
    behaviors: "self-reference, memory framing, habit-driven reactions"
    write_policy: "manual_edit"
  AGENTS:
    optional: false
    role: "runtime behavior rules, silence policy, group chat routing"
    behaviors: "when to speak, when to hold back, how to engage others"
    write_policy: "manual_edit"
  RELATIONS:
    optional: true
    role: "target-specific trust, affection, appellations, friction points"
    behaviors: "tone toward each other character, appellations, conflict framing"
    write_policy: "manual_edit"
  MEMORY:
    optional: false
    role: "stable notes plus runtime write-back from user guidance and corrections"
    behaviors: "persistent user constraints, correction carry-over, mutable notes"
    write_policy: "runtime_append"
---

# PERSONA RULES

Editable persona bundle schema and navigation metadata.

