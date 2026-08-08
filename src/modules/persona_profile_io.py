from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Callable, Optional

from src.utils.file_utils import normalize_character_name, safe_filename


PERSONA_LIST_FIELDS = {
    "role_tags",
    "core_traits",
    "typical_lines",
    "decision_rules",
    "life_experience",
    "preference_like",
    "dislike_hate",
    "strengths",
    "weaknesses",
    "cognitive_limits",
    "fear_triggers",
    "key_bonds",
    "taboo_topics",
    "forbidden_behaviors",
}
PERSONA_SCALAR_FIELDS = {
    "timeline_stage",
    "speech_style",
    "identity_anchor",
    "soul_goal",
    "trauma_scar",
    "gender",
    "age_stage",
    "worldview",
    "thinking_style",
    "temperament_type",
    "core_identity",
    "faction_position",
    "world_belong",
    "background_imprint",
    "world_rule_fit",
    "rule_view",
    "plot_restriction",
    "appearance_feature",
    "habit_action",
    "social_mode",
    "carry_style",
    "hidden_desire",
    "interest_claim",
    "resource_dependence",
    "trade_principle",
    "inner_conflict",
    "story_role",
    "belief_anchor",
    "moral_bottom_line",
    "self_cognition",
    "stress_response",
    "emotion_model",
    "others_impression",
    "restraint_threshold",
    "private_self",
    "disguise_switch",
    "stance_stability",
    "reward_logic",
    "action_style",
    "arc_type",
    "arc_blocker",
    "ooc_redline",
    "evidence_source",
    "contradiction_note",
    "arc_summary",
}
PERSONA_METRIC_FIELDS = {"values"}
PERSONA_INT_FIELDS = {"arc_confidence"}
PERSONA_NESTED_FIELDS = {
    "cadence": ("speech_habits", "cadence", "scalar"),
    "signature_phrases": ("speech_habits", "signature_phrases", "list"),
    "sentence_openers": ("speech_habits", "sentence_openers", "list"),
    "connective_tokens": ("speech_habits", "connective_tokens", "list"),
    "sentence_endings": ("speech_habits", "sentence_endings", "list"),
    "forbidden_fillers": ("speech_habits", "forbidden_fillers", "list"),
    "anger_style": ("emotion_profile", "anger_style", "scalar"),
    "joy_style": ("emotion_profile", "joy_style", "scalar"),
    "grievance_style": ("emotion_profile", "grievance_style", "scalar"),
    "arc_start": ("arc", "start", "metric"),
    "arc_mid": ("arc", "mid", "metric"),
    "arc_end": ("arc", "end", "metric"),
    "description_count": ("evidence", "description_count", "int"),
    "dialogue_count": ("evidence", "dialogue_count", "int"),
    "thought_count": ("evidence", "thought_count", "int"),
    "chunk_count": ("evidence", "chunk_count", "int"),
}


def parse_navigation_markdown(path: Path) -> dict[str, Any]:
    parsed: dict[str, Any] = {"runtime": {}, "files": {}}
    current_section = ""
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line.startswith("## "):
            current_section = line[3:].strip().upper()
            if current_section and current_section != "RUNTIME":
                parsed["files"].setdefault(current_section, {})
            continue
        if not line.startswith("- ") or ":" not in line:
            continue
        key, value = line[2:].split(":", 1)
        key = key.strip()
        value = value.strip()
        if not value:
            continue
        if current_section == "RUNTIME":
            parsed["runtime"][key] = value
        elif current_section:
            parsed["files"].setdefault(current_section, {})[key] = value
    return parsed


def parse_persona_markdown(path: Path) -> dict[str, Any]:
    parsed: dict[str, Any] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line.startswith("- ") or ":" not in line:
            continue
        key, value = line[2:].split(":", 1)
        key = key.strip()
        value = value.strip()
        if not value:
            continue
        if key in parsed and parsed[key]:
            parsed[key] = f"{parsed[key]}；{value}"
        else:
            parsed[key] = value
    return parsed


def split_persona_value(value: str) -> list[str]:
    return [item.strip() for item in re.split(r"[；;]\s*", value) if item.strip()]


def split_metric_map(value: str) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for item in re.split(r"[；;]\s*", str(value or "").strip()):
        if not item or "=" not in item:
            continue
        key, raw = item.split("=", 1)
        key = key.strip()
        raw = raw.strip()
        if not key:
            continue
        result[key] = int(raw) if re.fullmatch(r"-?\d+", raw) else raw
    return result


def safe_int(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def merge_profile_item(existing: dict[str, Any] | None, incoming: dict[str, Any]) -> dict[str, Any]:
    if not existing:
        return incoming
    current_score = len(existing.get("typical_lines", [])) + len(existing.get("core_traits", []))
    incoming_score = len(incoming.get("typical_lines", [])) + len(incoming.get("core_traits", []))
    if incoming_score > current_score:
        merged = incoming.copy()
        fallback = existing
    else:
        merged = existing.copy()
        fallback = incoming

    for key in ("core_traits", "typical_lines", "decision_rules"):
        merged_values = list(merged.get(key, []))
        seen = set(merged_values)
        for item in fallback.get(key, []):
            if item not in seen:
                merged_values.append(item)
                seen.add(item)
        merged[key] = merged_values

    if not merged.get("speech_style") and fallback.get("speech_style"):
        merged["speech_style"] = fallback["speech_style"]
    if not merged.get("values") and fallback.get("values"):
        merged["values"] = fallback["values"]
    return merged


class PersonaProfileRepository:
    def __init__(
        self,
        characters_dir: Path,
        *,
        scoped_root: Callable[[str], Path],
        default_navigation_order: list[str] | tuple[str, ...],
    ) -> None:
        self.characters_dir = Path(characters_dir)
        self.scoped_root = scoped_root
        self.default_navigation_order = tuple(default_navigation_order)

    def load(self, novel_id: Optional[str] = None) -> dict[str, dict[str, Any]]:
        profiles: dict[str, dict[str, Any]] = {}
        if not self.characters_dir.exists():
            return profiles

        if novel_id:
            sources = self.collect_profile_sources(self.scoped_root(novel_id))
            if not sources:
                return profiles
        else:
            sources = self.collect_profile_sources(self.characters_dir)
            for novel_dir in sorted(path for path in self.characters_dir.iterdir() if path.is_dir()):
                sources.extend(self.collect_profile_sources(novel_dir))

        for source in sources:
            item = self.load_profile_source(source)
            if not item or not isinstance(item, dict) or not item.get("name"):
                continue
            canonical_name = normalize_character_name(item["name"])
            item["name"] = canonical_name
            if source.is_dir():
                base_dir = source.parent
            elif source.name.startswith("PROFILE"):
                base_dir = source.parent.parent
            else:
                base_dir = source.parent
            item = self.merge_persona_bundle(item, base_dir)
            profiles[canonical_name] = merge_profile_item(profiles.get(canonical_name), item)
        return profiles

    @staticmethod
    def collect_profile_sources(root: Path) -> list[Path]:
        if not root.exists():
            return []
        sources: list[Path] = []
        seen: set[Path] = set()
        for persona_dir in sorted(path for path in root.iterdir() if path.is_dir()):
            if any((persona_dir / filename).exists() for filename in ("PROFILE.md", "PROFILE.generated.md")):
                resolved = persona_dir.resolve()
                if resolved not in seen:
                    sources.append(persona_dir)
                    seen.add(resolved)
        return sources

    def load_profile_source(self, path: Path) -> Optional[dict[str, Any]]:
        if path.is_dir():
            return self.load_profile_bundle(path)
        if path.name.startswith("PROFILE"):
            return self.load_profile_markdown(path)
        return None

    def load_profile_bundle(self, persona_dir: Path) -> Optional[dict[str, Any]]:
        merged: dict[str, Any] = {}
        loaded = False
        for filename in ("PROFILE.generated.md", "PROFILE.md"):
            path = persona_dir / filename
            if not path.exists():
                continue
            current = self.load_profile_markdown(path)
            if not current:
                continue
            merged = self.merge_profile_markdown_data(merged, current) if loaded else current
            loaded = True
        return merged if loaded else None

    @staticmethod
    def empty_profile(*, path: Optional[Path] = None) -> dict[str, Any]:
        parent = path.parent if path else Path()
        grandparent = parent.parent if path else Path()
        profile: dict[str, Any] = {
            "name": parent.name if path else "",
            "novel_id": grandparent.name if path else "",
            "source_path": "",
            "speech_habits": {
                "cadence": "",
                "signature_phrases": [],
                "sentence_openers": [],
                "connective_tokens": [],
                "sentence_endings": [],
                "forbidden_fillers": [],
            },
            "emotion_profile": {
                "anger_style": "",
                "joy_style": "",
                "grievance_style": "",
            },
            "arc": {"start": {}, "mid": {}, "end": {}},
            "evidence": {
                "description_count": 0,
                "dialogue_count": 0,
                "thought_count": 0,
                "chunk_count": 0,
            },
        }
        for key in PERSONA_SCALAR_FIELDS:
            profile.setdefault(key, "")
        for key in PERSONA_LIST_FIELDS:
            profile.setdefault(key, [])
        for key in PERSONA_METRIC_FIELDS:
            profile.setdefault(key, {})
        for key in PERSONA_INT_FIELDS:
            profile.setdefault(key, 0)
        return profile

    def load_profile_markdown(self, path: Path) -> dict[str, Any]:
        parsed = parse_persona_markdown(path)
        profile = self.empty_profile(path=path)
        profile["name"] = parsed.get("name", profile["name"])
        profile["novel_id"] = parsed.get("novel_id", profile["novel_id"])
        profile["source_path"] = parsed.get("source_path", "")
        for key in PERSONA_LIST_FIELDS:
            profile[key] = split_persona_value(parsed.get(key, ""))
        for key in PERSONA_SCALAR_FIELDS:
            profile[key] = parsed.get(key, "")
        for key in PERSONA_METRIC_FIELDS:
            profile[key] = split_metric_map(parsed.get(key, ""))
        for key in PERSONA_INT_FIELDS:
            profile[key] = safe_int(parsed.get(key, 0))
        for key, (parent, child, value_type) in PERSONA_NESTED_FIELDS.items():
            bucket = dict(profile.get(parent, {}))
            raw = parsed.get(key, "")
            if value_type == "list":
                bucket[child] = split_persona_value(raw)
            elif value_type == "metric":
                bucket[child] = split_metric_map(raw)
            elif value_type == "int":
                bucket[child] = safe_int(raw)
            else:
                bucket[child] = raw
            profile[parent] = bucket
        return profile

    @staticmethod
    def merge_profile_markdown_data(base: dict[str, Any], overlay: dict[str, Any]) -> dict[str, Any]:
        merged = dict(base)
        for key, value in overlay.items():
            if value in ("", [], {}, None):
                continue
            if isinstance(value, dict) and isinstance(merged.get(key), dict):
                bucket = dict(merged.get(key, {}))
                for child_key, child_value in value.items():
                    if child_value in ("", [], {}, None):
                        continue
                    bucket[child_key] = child_value
                merged[key] = bucket
                continue
            merged[key] = value
        return merged

    def merge_persona_bundle(self, profile: dict[str, Any], base_dir: Path) -> dict[str, Any]:
        merged = dict(profile)
        persona_dir = base_dir / safe_filename(merged.get("name", ""))
        if not persona_dir.exists():
            return merged
        for base_name, source in self.resolve_persona_sources(persona_dir):
            if base_name == "RELATIONS":
                continue
            merged = self.apply_persona_overrides(merged, parse_persona_markdown(source))
        return merged

    def resolve_persona_sources(self, persona_dir: Path) -> list[tuple[str, Path]]:
        descriptor = self.load_navigation_descriptor(persona_dir)
        order = descriptor.get("runtime", {}).get("load_order", []) or list(self.default_navigation_order)
        sources: list[tuple[str, Path]] = []
        seen: set[str] = set()
        for base_name in order:
            normalized = str(base_name or "").strip().upper()
            if not normalized or normalized in seen:
                continue
            meta = descriptor.get("files", {}).get(normalized, {})
            if str(meta.get("status", "")).strip().lower() == "inactive":
                continue
            source = self.resolve_persona_file_path(persona_dir, normalized, meta)
            if source:
                sources.append((normalized, source))
                seen.add(normalized)
        for base_name in self.default_navigation_order:
            if base_name in seen:
                continue
            meta = descriptor.get("files", {}).get(base_name, {})
            if str(meta.get("status", "")).strip().lower() == "inactive":
                continue
            source = self.resolve_persona_file_path(persona_dir, base_name, meta)
            if source:
                sources.append((base_name, source))
                seen.add(base_name)
        return sources

    @staticmethod
    def resolve_persona_file_path(persona_dir: Path, base_name: str, meta: dict[str, Any]) -> Optional[Path]:
        editable_name = str(meta.get("file", f"{base_name}.md")).strip() or f"{base_name}.md"
        fallback_name = str(meta.get("fallback", f"{base_name}.generated.md")).strip() or f"{base_name}.generated.md"
        editable = persona_dir / editable_name
        if editable.exists():
            return editable
        fallback = persona_dir / fallback_name
        return fallback if fallback.exists() else None

    def load_navigation_descriptor(self, persona_dir: Path) -> dict[str, Any]:
        descriptor = self.default_navigation_descriptor()
        for source in (persona_dir / "NAVIGATION.generated.md", persona_dir / "NAVIGATION.md"):
            if source.exists():
                descriptor = self.merge_navigation_descriptor(descriptor, parse_navigation_markdown(source))
        return descriptor

    def default_navigation_descriptor(self) -> dict[str, Any]:
        files = {
            base_name: {
                "file": f"{base_name}.md",
                "fallback": f"{base_name}.generated.md",
            }
            for base_name in self.default_navigation_order
        }
        return {
            "runtime": {"load_order": list(self.default_navigation_order)},
            "files": files,
        }

    def merge_navigation_descriptor(self, base: dict[str, Any], overlay: dict[str, Any]) -> dict[str, Any]:
        merged = {
            "runtime": dict(base.get("runtime", {})),
            "files": {
                key: dict(value) if isinstance(value, dict) else {}
                for key, value in base.get("files", {}).items()
            },
        }
        runtime_overlay = overlay.get("runtime", {}) if isinstance(overlay.get("runtime", {}), dict) else {}
        if runtime_overlay.get("load_order"):
            merged["runtime"]["load_order"] = self.parse_navigation_order(runtime_overlay["load_order"])
        for key, value in runtime_overlay.items():
            if key != "load_order":
                merged["runtime"][key] = value
        files_overlay = overlay.get("files", {}) if isinstance(overlay.get("files", {}), dict) else {}
        for base_name, payload in files_overlay.items():
            entry = dict(merged["files"].get(base_name, {}))
            if isinstance(payload, dict):
                entry.update(payload)
            merged["files"][base_name] = entry
        return merged

    def parse_navigation_order(self, value: Any) -> list[str]:
        text = str(value or "").strip()
        if not text:
            return list(self.default_navigation_order)
        parts = [item.strip().upper() for item in re.split(r"->|,|\|", text) if item.strip()]
        return parts or list(self.default_navigation_order)

    @staticmethod
    def apply_persona_overrides(profile: dict[str, Any], parsed: dict[str, Any]) -> dict[str, Any]:
        merged = dict(profile)
        overlay_list_fields = PERSONA_LIST_FIELDS | {
            "user_edits",
            "notable_interactions",
            "relationship_updates",
            "canon_memory",
        }
        for key, value in parsed.items():
            if not value:
                continue
            if key == "canon_memory":
                merged["life_experience"] = split_persona_value(value)
                continue
            if key in PERSONA_NESTED_FIELDS:
                parent, child, value_type = PERSONA_NESTED_FIELDS[key]
                bucket = dict(merged.get(parent, {})) if isinstance(merged.get(parent, {}), dict) else {}
                if value_type == "list":
                    bucket[child] = split_persona_value(value)
                elif value_type == "metric":
                    bucket[child] = split_metric_map(value)
                elif value_type == "int":
                    bucket[child] = safe_int(value)
                else:
                    bucket[child] = value
                merged[parent] = bucket
            elif key in PERSONA_SCALAR_FIELDS:
                merged[key] = value
            elif key in PERSONA_METRIC_FIELDS:
                merged[key] = split_metric_map(value)
            elif key in PERSONA_INT_FIELDS:
                merged[key] = safe_int(value)
            elif key in overlay_list_fields:
                merged[key] = split_persona_value(value)
        return merged


__all__ = [
    "PERSONA_INT_FIELDS",
    "PERSONA_LIST_FIELDS",
    "PERSONA_METRIC_FIELDS",
    "PERSONA_NESTED_FIELDS",
    "PERSONA_SCALAR_FIELDS",
    "PersonaProfileRepository",
    "merge_profile_item",
    "parse_navigation_markdown",
    "parse_persona_markdown",
    "safe_int",
    "split_metric_map",
    "split_persona_value",
]
