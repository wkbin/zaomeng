from __future__ import annotations

from pathlib import Path
from typing import Any, Optional

from src.core.contracts import PathProviderLike, RelationStore
from src.modules.persona_profile_io import PersonaProfileRepository
from src.utils.file_utils import normalize_character_name, normalize_relation_key


class ChatRelationResolver:
    def __init__(
        self,
        *,
        path_provider: PathProviderLike,
        relation_store: RelationStore,
        persona_profiles: PersonaProfileRepository,
    ) -> None:
        self.path_provider = path_provider
        self.relation_store = relation_store
        self.persona_profiles = persona_profiles
        self.relations_dir = path_provider.relations_root()

    @staticmethod
    def pair_key(left: str, right: str) -> str:
        return "_".join(sorted([left, right]))

    def relation_hint(self, speaker: str, all_characters: list[str], novel_id: Optional[str]) -> str:
        hints: list[str] = []
        for other in all_characters:
            if other == speaker:
                continue
            item = self.get_from_disk(speaker, other, novel_id)
            if item:
                hints.append(
                    f"{other}(trust={item.get('trust', 5)},aff={item.get('affection', 5)},"
                    f"host={item.get('hostility', max(0, 5 - item.get('affection', 5)))})"
                )
        return "; ".join(hints[:3])

    def relation_file_for_novel(self, novel_id: Optional[str]) -> Optional[Path]:
        if novel_id:
            scoped = self.path_provider.relations_file(novel_id)
            if scoped.exists():
                return scoped
            legacy = self.relations_dir / f"{novel_id}_relations.md"
            if legacy.exists():
                return legacy
        files = sorted(
            self.relations_dir.glob("*.md"),
            key=lambda path: path.stat().st_mtime,
            reverse=True,
        )
        return files[0] if files else None

    def build_matrix(self, characters: list[str], novel_id: Optional[str]) -> dict[str, dict[str, Any]]:
        matrix: dict[str, dict[str, Any]] = {}
        for speaker in characters:
            for target in characters:
                if speaker == target:
                    continue
                disk = self.get_from_disk(speaker, target, novel_id) or {}
                state = {
                    "trust": int(disk.get("trust", 5)),
                    "affection": int(disk.get("affection", 5)),
                    "hostility": int(disk.get("hostility", max(0, 5 - int(disk.get("affection", 5))))),
                    "ambiguity": int(disk.get("ambiguity", 3)),
                }
                for key in (
                    "conflict_point",
                    "typical_interaction",
                    "relation_change",
                    "hidden_attitude",
                    "appellations",
                ):
                    if key in disk:
                        state[key] = disk[key]
                matrix[self.pair_key(speaker, target)] = state
        return matrix

    def get_from_disk(
        self,
        speaker: str,
        target: str,
        novel_id: Optional[str] = None,
    ) -> dict[str, Any]:
        relation_file = self.relation_file_for_novel(novel_id)
        if not relation_file:
            base: dict[str, Any] = {}
        else:
            payload = self.relation_store.load_relations(relation_file.parent.name, default={}) or {}
            relations = payload.get("relations", {}) if isinstance(payload, dict) else {}
            normalized = {normalize_relation_key(key): value for key, value in relations.items()}
            pair_key = self.pair_key(
                normalize_character_name(speaker),
                normalize_character_name(target),
            )
            base = normalized.get(pair_key, {})
        return self.merge_overlay(base, speaker, target, novel_id)

    def merge_overlay(
        self,
        relation_state: dict[str, Any],
        speaker: str,
        target: str,
        novel_id: Optional[str],
    ) -> dict[str, Any]:
        merged = dict(relation_state or {})
        overlay = self.load_markdown_overlay(speaker, target, novel_id)
        if not overlay:
            return merged
        for key in ("trust", "affection", "power_gap"):
            if key in overlay:
                try:
                    merged[key] = int(overlay[key])
                except (TypeError, ValueError):
                    pass
        for key in ("conflict_point", "typical_interaction", "relation_change", "hidden_attitude"):
            if overlay.get(key):
                merged[key] = overlay[key]
        appellation = overlay.get("appellation_to_target", "")
        if appellation:
            appellations = (
                dict(merged.get("appellations", {}))
                if isinstance(merged.get("appellations", {}), dict)
                else {}
            )
            appellations[f"{speaker}->{target}"] = appellation
            merged["appellations"] = appellations
        return merged

    def load_markdown_overlay(
        self,
        speaker: str,
        target: str,
        novel_id: Optional[str],
    ) -> dict[str, str]:
        if not novel_id:
            return {}
        persona_dir = self.path_provider.character_dir(
            novel_id,
            normalize_character_name(speaker),
        )
        descriptor = (
            self.persona_profiles.load_navigation_descriptor(persona_dir)
            if persona_dir.exists()
            else self.persona_profiles.default_navigation_descriptor()
        )
        meta = descriptor.get("files", {}).get("RELATIONS", {})
        if str(meta.get("status", "")).strip().lower() == "inactive":
            return {}
        path = (
            self.persona_profiles.resolve_persona_file_path(persona_dir, "RELATIONS", meta)
            if persona_dir.exists()
            else None
        )
        if not path:
            return {}
        parsed = self.parse_relation_markdown(path)
        return parsed.get(normalize_character_name(target), {})

    @staticmethod
    def parse_relation_markdown(path: Path) -> dict[str, dict[str, str]]:
        result: dict[str, dict[str, str]] = {}
        current_target = ""
        for raw_line in path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if line.startswith("## "):
                current_target = normalize_character_name(line[3:].strip())
                result.setdefault(current_target, {})
                continue
            if not current_target or not line.startswith("- ") or ":" not in line:
                continue
            key, value = line[2:].split(":", 1)
            result[current_target][key.strip()] = value.strip()
        return result

    def get_session_state(
        self,
        session: dict[str, Any],
        speaker: str,
        target: str,
    ) -> dict[str, Any]:
        if not target:
            return {}
        matrix = session["state"].setdefault("relation_matrix", {})
        state = dict(matrix.get(self.pair_key(speaker, target), {}))
        novel_id = session.get("novel_id")
        return self.merge_overlay(state, speaker, target, novel_id) if novel_id else state


__all__ = ["ChatRelationResolver"]
