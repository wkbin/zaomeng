#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List

from src.utils.file_utils import canonical_aliases, load_markdown_data, normalize_character_name, safe_filename


class DistillationHintsMixin:
    def _load_novel_character_hints(self, novel_id: str) -> Dict[str, Any]:
        rules_root = self.path_provider.rules_root() if hasattr(self.path_provider, "rules_root") else None
        if not rules_root:
            return {}
        hint_path = Path(rules_root) / "character_hints" / f"{safe_filename(novel_id)}.md"
        payload = load_markdown_data(hint_path, default={}) or {}
        if not isinstance(payload, dict):
            return {}
        hints = payload.get("character_hints", payload)
        return dict(hints) if isinstance(hints, dict) else {}

    def _resolve_character_hint(self, name: str) -> Dict[str, Any]:
        merged_hint_map: Dict[str, Any] = {}
        if isinstance(self.global_character_hints, dict):
            merged_hint_map.update(self.global_character_hints)
        if isinstance(self._active_character_hints, dict):
            merged_hint_map.update(self._active_character_hints)
        if not merged_hint_map:
            return {}

        candidates = [str(name or "").strip(), normalize_character_name(name)]
        candidates.extend(canonical_aliases(name))
        normalized_candidates: List[str] = []
        seen = set()
        for candidate in candidates:
            clean = str(candidate or "").strip()
            if not clean or clean in seen:
                continue
            normalized_candidates.append(clean)
            seen.add(clean)

        for candidate in normalized_candidates:
            payload = merged_hint_map.get(candidate)
            if isinstance(payload, dict):
                return dict(payload)

        for raw_name, payload in merged_hint_map.items():
            if not isinstance(payload, dict):
                continue
            configured_aliases = [str(item).strip() for item in payload.get("aliases", []) if str(item).strip()]
            all_names = [str(raw_name).strip(), normalize_character_name(str(raw_name))]
            all_names.extend(configured_aliases)
            normalized_names = {normalize_character_name(item) for item in all_names if item}
            if any(normalize_character_name(candidate) in normalized_names for candidate in normalized_candidates):
                return dict(payload)
        return {}

    def _apply_character_hint(self, profile: Dict[str, Any], hint: Dict[str, Any]) -> Dict[str, Any]:
        if not hint:
            return profile
        refined = dict(profile)
        list_fields = {
            "core_traits",
            "typical_lines",
            "decision_rules",
            "life_experience",
            "taboo_topics",
            "forbidden_behaviors",
            "strengths",
            "weaknesses",
            "cognitive_limits",
            "fear_triggers",
            "key_bonds",
        }
        direct_fields = {
            "identity_anchor",
            "soul_goal",
            "speech_style",
            "worldview",
            "thinking_style",
            "core_identity",
            "faction_position",
            "background_imprint",
            "world_rule_fit",
            "social_mode",
            "hidden_desire",
            "inner_conflict",
            "story_role",
            "belief_anchor",
            "private_self",
            "stance_stability",
            "reward_logic",
            "action_style",
            "trauma_scar",
            "temperament_type",
            "moral_bottom_line",
            "self_cognition",
            "stress_response",
            "others_impression",
            "restraint_threshold",
        }
        for key in list_fields:
            values = [str(item).strip() for item in hint.get(key, []) if str(item).strip()]
            if values:
                refined[key] = values
        for key in direct_fields:
            value = str(hint.get(key, "")).strip()
            if value:
                refined[key] = value
        return refined

    @staticmethod
    def _render_character_hint(name: str, hint: Dict[str, Any]) -> str:
        if not hint:
            return "- no character-specific hint"
        lines = [f"# CHARACTER HINT FOR {name}"]
        scalar_fields = (
            "identity_anchor",
            "soul_goal",
            "temperament_type",
            "trauma_scar",
            "moral_bottom_line",
            "self_cognition",
            "stress_response",
            "others_impression",
            "restraint_threshold",
        )
        for field in scalar_fields:
            value = str(hint.get(field, "")).strip()
            if value:
                lines.append(f"- {field}: {value}")
        for field in ("distinct_from", "evidence_focus", "avoid_generic"):
            values = [str(item).strip() for item in hint.get(field, []) if str(item).strip()]
            if values:
                joined_values = ", ".join(values)
                lines.append(f"- {field}: {joined_values}")
        notes = [str(item).strip() for item in hint.get("notes", []) if str(item).strip()]
        if notes:
            joined_notes = ", ".join(notes)
            lines.append(f"- notes: {joined_notes}")
        return "\n".join(lines).rstrip() + "\n"
