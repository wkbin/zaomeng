#!/usr/bin/env python3

import re
import tempfile
import unittest
from pathlib import Path

from src.modules.relationships import RelationshipExtractor
from src.skill_support.relation_graph_export import export_relation_graph
from src.utils.file_utils import save_markdown_data


class RelationGraphExportTests(unittest.TestCase):
    def test_mermaid_graph_uses_unique_ids_and_escaped_labels(self):
        extractor = RelationshipExtractor.__new__(RelationshipExtractor)
        graph = extractor._render_mermaid_graph(
            {
                "A-B_共同": {"trust": 7, "affection": 6, "hostility": 1},
                "A B_共同": {"trust": 6, "affection": 6, "hostility": 1},
                '引"号\n者_共同': {"trust": 5, "affection": 5, "hostility": 2},
            }
        )

        dash = re.search(r'(n\d+)\["A-B"\]', graph)
        space = re.search(r'(n\d+)\["A B"\]', graph)
        self.assertIsNotNone(dash)
        self.assertIsNotNone(space)
        self.assertNotEqual(dash.group(1), space.group(1))
        self.assertIn('引\\"号 者', graph)
        self.assertNotIn('引"号\n者', graph)

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

            html_text = html_path.read_text(encoding="utf-8")
            self.assertIn(
                '<meta name="zaomeng-relation-ui-version" content="2" />',
                html_text,
            )
            self.assertTrue(
                "RELATION EXPLORER" in html_text or "关系洞察工作台" in html_text,
                "关系图谱应带有新版探索工作台标记",
            )
            self.assertIn("font-family:system-ui", html_text)
            self.assertIn("sans-serif", html_text)
            self.assertNotIn("Noto Serif SC", html_text)
            self.assertNotIn("Source Han Serif SC", html_text)
            for label in ("关系数量", "高信任关系", "高冲突关系", "人物分组"):
                with self.subTest(kpi_label=label):
                    self.assertIn(label, html_text)

            for marker in (
                'id="relation-graph-viewport"',
                'id="relation-graph-stage"',
                'id="relation-graph-scale"',
                'data-graph-action="out"',
                'data-graph-action="in"',
                'data-graph-action="fit"',
            ):
                with self.subTest(canvas_marker=marker):
                    self.assertIn(marker, html_text)

            self.assertRegex(
                html_text,
                r"(?:html,\s*body|body)\s*\{[^}]*overflow-x:\s*hidden",
            )
            self.assertRegex(
                html_text,
                r"@media\s*\(max-width:\s*[3-7]\d{2}px\)",
            )
            self.assertRegex(
                html_text,
                r"\.(?:table-scroll|table-shell)\s*\{[^}]*overflow-x:\s*auto",
            )
            self.assertTrue((html_path.parent / "mermaid-11.14.0.min.js").exists())
            self.assertIn("mermaid-11.14.0.min.js", html_text)
            self.assertNotIn("cdn.jsdelivr.net", html_text)

    def test_export_relation_graph_tolerates_ellipsis_metrics(self):
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
                            "trust": "...",
                            "affection": "",
                            "hostility": "...",
                            "power_gap": "...",
                        }
                    },
                },
                title="RELATION_GRAPH",
            )
            exported = export_relation_graph(relations_file, config_path=str(config_path), novel_id="mini")
            self.assertTrue(Path(exported["html_path"]).exists())
            self.assertTrue(Path(exported["mermaid_path"]).exists())


if __name__ == "__main__":
    unittest.main()
