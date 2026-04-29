#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path

from src.skill_support.relation_graph_export import export_relation_graph
from src.utils.file_utils import save_markdown_data


class RelationGraphExportTests(unittest.TestCase):
    def test_export_relation_graph_creates_html_and_mermaid_outputs(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            config_path = root / "config.yaml"
            config_path.write_text(
                "paths:\n"
                "  characters: data/characters\n"
                "  relations: data/relations\n"
                "  sessions: data/sessions\n"
                "  corrections: data/corrections\n"
                "  logs: logs\n"
                "  rules: rules\n",
                encoding="utf-8",
            )
            relation_dir = root / "data" / "relations" / "mini"
            relation_dir.mkdir(parents=True, exist_ok=True)
            relations_file = relation_dir / "mini_relations.md"
            save_markdown_data(
                relations_file,
                {
                    "novel_id": "mini",
                    "relations": {
                        "刘备_关羽": {
                            "trust": 9,
                            "affection": 8,
                            "hostility": 1,
                            "power_gap": 0,
                            "conflict_point": "取舍先后",
                            "typical_interaction": "先问进退，再议轻重",
                        }
                    },
                },
                title="RELATION_GRAPH",
            )
            liubei_dir = root / "data" / "characters" / "mini" / "刘备"
            liubei_dir.mkdir(parents=True, exist_ok=True)
            (liubei_dir / "PROFILE.generated.md").write_text(
                "# PROFILE\n- faction_position: 蜀汉\n- story_role: 主君\n",
                encoding="utf-8",
            )
            guanyu_dir = root / "data" / "characters" / "mini" / "关羽"
            guanyu_dir.mkdir(parents=True, exist_ok=True)
            (guanyu_dir / "PROFILE.generated.md").write_text(
                "# PROFILE\n- story_role: 先锋\n",
                encoding="utf-8",
            )

            exported = export_relation_graph(relations_file, config_path=str(config_path), novel_id="mini")

            html_path = Path(exported["html_path"])
            mermaid_path = Path(exported["mermaid_path"])
            self.assertTrue(html_path.exists())
            self.assertTrue(mermaid_path.exists())
            self.assertIn("mini_relations.html", exported["html_path"])
            self.assertIn("mini_relations.mermaid.md", exported["mermaid_path"])


if __name__ == "__main__":
    unittest.main()
