from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Any

from src.modules.chat_relation_state import ChatRelationResolver
from src.modules.persona_profile_io import PersonaProfileRepository


class FakePathProvider:
    def __init__(self, root: Path) -> None:
        self.root = root

    def relations_root(self, novel_id: str | None = None) -> Path:
        path = self.root / "relations"
        if novel_id:
            path /= novel_id
        path.mkdir(parents=True, exist_ok=True)
        return path

    def relations_file(self, novel_id: str) -> Path:
        return self.relations_root(novel_id) / f"{novel_id}_relations.md"

    def characters_root(self, novel_id: str | None = None) -> Path:
        path = self.root / "characters"
        if novel_id:
            path /= novel_id
        path.mkdir(parents=True, exist_ok=True)
        return path

    def character_dir(self, novel_id: str, character_name: str) -> Path:
        path = self.characters_root(novel_id) / character_name
        path.mkdir(parents=True, exist_ok=True)
        return path


class FakeRelationStore:
    def __init__(self, payload: dict[str, Any]) -> None:
        self.payload = payload

    def load_relations(self, novel_id: str, default: Any = None) -> Any:
        return self.payload


class ChatRelationResolverTests(unittest.TestCase):
    def test_editable_relation_overlay_updates_disk_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            path_provider = FakePathProvider(Path(tmp))
            relation_file = path_provider.relations_file("novel-a")
            relation_file.write_text("# RELATION_GRAPH\n", encoding="utf-8")
            persona_dir = path_provider.character_dir("novel-a", "林黛玉")
            (persona_dir / "RELATIONS.md").write_text(
                "## 贾宝玉\n- trust: 9\n- conflict_point: 真心太重\n- appellation_to_target: 宝玉\n",
                encoding="utf-8",
            )
            profiles = PersonaProfileRepository(
                path_provider.characters_root(),
                scoped_root=path_provider.characters_root,
                default_navigation_order=["RELATIONS"],
            )
            resolver = ChatRelationResolver(
                path_provider=path_provider,
                relation_store=FakeRelationStore(
                    {
                        "relations": {
                            "林黛玉_贾宝玉": {
                                "trust": 4,
                                "affection": 8,
                                "ambiguity": 6,
                            }
                        }
                    }
                ),
                persona_profiles=profiles,
            )

            relation = resolver.get_from_disk("林黛玉", "贾宝玉", "novel-a")

            self.assertEqual(relation["trust"], 9)
            self.assertEqual(relation["affection"], 8)
            self.assertEqual(relation["conflict_point"], "真心太重")
            self.assertEqual(relation["appellations"]["林黛玉->贾宝玉"], "宝玉")

    def test_inactive_relation_file_does_not_override_session_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            path_provider = FakePathProvider(Path(tmp))
            persona_dir = path_provider.character_dir("novel-a", "林黛玉")
            (persona_dir / "NAVIGATION.md").write_text(
                "## RELATIONS\n- status: inactive\n",
                encoding="utf-8",
            )
            (persona_dir / "RELATIONS.md").write_text(
                "## 贾宝玉\n- trust: 10\n",
                encoding="utf-8",
            )
            profiles = PersonaProfileRepository(
                path_provider.characters_root(),
                scoped_root=path_provider.characters_root,
                default_navigation_order=["RELATIONS"],
            )
            resolver = ChatRelationResolver(
                path_provider=path_provider,
                relation_store=FakeRelationStore({}),
                persona_profiles=profiles,
            )
            session = {
                "novel_id": "novel-a",
                "state": {
                    "relation_matrix": {
                        resolver.pair_key("林黛玉", "贾宝玉"): {"trust": 6},
                    }
                },
            }

            relation = resolver.get_session_state(session, "林黛玉", "贾宝玉")

            self.assertEqual(relation["trust"], 6)


if __name__ == "__main__":
    unittest.main()
