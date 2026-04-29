#!/usr/bin/env python3

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.install_skill import copy_skill_bundle, iter_skill_entries


class InstallSkillTests(unittest.TestCase):
    def test_iter_skill_entries_only_lists_prompt_first_assets(self):
        entries = iter_skill_entries()
        self.assertIn("SKILL.md", entries)
        self.assertIn("requirements.txt", entries)
        self.assertIn("tools", entries)
        self.assertNotIn("runtime", entries)

    def test_copy_skill_bundle_installs_prompt_first_payload_by_default(self):
        repo_root = Path(__file__).resolve().parents[1]
        packaged_src = repo_root / "clawhub-zaomeng-skill"

        with tempfile.TemporaryDirectory() as tmpdir:
            dst = copy_skill_bundle(packaged_src, Path(tmpdir), "zaomeng-skill")
            self.assertTrue((dst / "SKILL.md").exists())
            self.assertTrue((dst / "requirements.txt").exists())
            self.assertTrue((dst / "prompts").exists())
            self.assertTrue((dst / "references").exists())
            self.assertTrue((dst / "tools" / "prepare_novel_excerpt.py").exists())
            self.assertTrue((dst / "tools" / "build_prompt_payload.py").exists())
            self.assertTrue((dst / "tools" / "export_relation_graph.py").exists())
            self.assertTrue((dst / "tools" / "_skill_support" / "novel_preparation.py").exists())
            self.assertFalse((dst / "runtime").exists())

    def test_installed_prepare_excerpt_tool_runs_without_repo_src(self):
        repo_root = Path(__file__).resolve().parents[1]
        packaged_src = repo_root / "clawhub-zaomeng-skill"

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_root = Path(tmpdir)
            dst = copy_skill_bundle(packaged_src, tmp_root, "zaomeng-skill")
            novel_path = tmp_root / "红楼梦.txt"
            novel_path.write_text("宝玉。黛玉。宝钗。", encoding="utf-8")

            result = subprocess.run(
                [
                    sys.executable,
                    str(dst / "tools" / "prepare_novel_excerpt.py"),
                    "--novel",
                    str(novel_path),
                    "--max-sentences",
                    "2",
                    "--max-chars",
                    "100",
                ],
                cwd=dst,
                check=True,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            payload = json.loads(result.stdout)
            self.assertEqual(payload["source_name"], "红楼梦.txt")
            self.assertEqual(payload["excerpt"], "宝玉。\n黛玉。")

    def test_installed_relation_graph_tool_exports_files_without_repo_src(self):
        repo_root = Path(__file__).resolve().parents[1]
        packaged_src = repo_root / "clawhub-zaomeng-skill"

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_root = Path(tmpdir)
            dst = copy_skill_bundle(packaged_src, tmp_root, "zaomeng-skill")
            relation_dir = tmp_root / "data" / "relations" / "mini"
            relation_dir.mkdir(parents=True, exist_ok=True)
            relations_file = relation_dir / "mini_relations.md"
            relations_file.write_text(
                "# RELATION_GRAPH\n\n"
                "- novel_id: mini\n\n"
                "## 刘备_关羽\n"
                "- trust: 9\n"
                "- affection: 8\n"
                "- power_gap: 0\n"
                "- conflict_point: 取舍先后\n"
                "- typical_interaction: 先问进退，再议轻重\n"
                "- hidden_attitude: 嘴上克制，私下更依赖对方\n"
                "- relation_change: 升温\n"
                "- confidence: 8\n",
                encoding="utf-8",
            )
            liubei_dir = tmp_root / "data" / "characters" / "mini" / "刘备"
            liubei_dir.mkdir(parents=True, exist_ok=True)
            (liubei_dir / "PROFILE.generated.md").write_text(
                "# PROFILE\n- faction_position: 蜀汉\n- story_role: 主君\n",
                encoding="utf-8",
            )

            result = subprocess.run(
                [
                    sys.executable,
                    str(dst / "tools" / "export_relation_graph.py"),
                    "--relations-file",
                    str(relations_file),
                ],
                cwd=dst,
                check=True,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            payload = json.loads(result.stdout)
            html_path = Path(payload["html_path"])
            mermaid_path = Path(payload["mermaid_path"])
            self.assertTrue(html_path.exists())
            self.assertTrue(mermaid_path.exists())
            self.assertIn("mini_relations.html", payload["html_path"])
            self.assertIn("mini_relations.mermaid.md", payload["mermaid_path"])
            mermaid_text = mermaid_path.read_text(encoding="utf-8")
            html_text = html_path.read_text(encoding="utf-8")
            self.assertIn("linkStyle 0", mermaid_text)
            self.assertNotIn(";;", mermaid_text)
            self.assertIn("关系类型", html_text)
            self.assertIn("最低信任值", html_text)
            self.assertIn("关系卡片", html_text)


if __name__ == "__main__":
    unittest.main()
