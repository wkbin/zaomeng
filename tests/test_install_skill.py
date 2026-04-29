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
        self.assertIn(".metadata.json", entries)
        self.assertIn("SKILL.md", entries)
        self.assertIn("requirements.txt", entries)
        self.assertIn("tools", entries)
        self.assertNotIn("runtime", entries)

    def test_copy_skill_bundle_installs_prompt_first_payload_by_default(self):
        repo_root = Path(__file__).resolve().parents[1]
        packaged_src = repo_root / "clawhub-zaomeng-skill"

        with tempfile.TemporaryDirectory() as tmpdir:
            dst = copy_skill_bundle(packaged_src, Path(tmpdir), "zaomeng-skill")
            self.assertTrue((dst / ".metadata.json").exists())
            self.assertTrue((dst / "SKILL.md").exists())
            self.assertTrue((dst / "requirements.txt").exists())
            self.assertTrue((dst / "prompts").exists())
            self.assertTrue((dst / "references").exists())
            self.assertTrue((dst / "tools" / "prepare_novel_excerpt.py").exists())
            self.assertTrue((dst / "tools" / "build_prompt_payload.py").exists())
            self.assertTrue((dst / "tools" / "export_relation_graph.py").exists())
            self.assertTrue((dst / "tools" / "materialize_persona_bundle.py").exists())
            self.assertTrue((dst / "tools" / "_skill_support" / "novel_preparation.py").exists())
            self.assertTrue((dst / "tools" / "_skill_support" / "persona_bundle.py").exists())
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
                    "--output",
                    str(tmp_root / "excerpt.json"),
                ],
                cwd=dst,
                check=True,
                capture_output=True,
            )

            self.assertEqual(result.returncode, 0)
            payload = json.loads((tmp_root / "excerpt.json").read_text(encoding="utf-8"))
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
                    "--output",
                    str(tmp_root / "relation_graph.json"),
                ],
                cwd=dst,
                check=True,
                capture_output=True,
            )

            self.assertEqual(result.returncode, 0)
            payload = json.loads((tmp_root / "relation_graph.json").read_text(encoding="utf-8"))
            html_path = Path(payload["html_path"])
            mermaid_path = Path(payload["mermaid_path"])
            self.assertTrue(html_path.exists())
            self.assertTrue(mermaid_path.exists())
            self.assertIn("svg_path", payload)
            if payload["svg_path"]:
                self.assertTrue(Path(payload["svg_path"]).exists())
            self.assertIn("mini_relations.html", payload["html_path"])
            self.assertIn("mini_relations.mermaid.md", payload["mermaid_path"])
            mermaid_text = mermaid_path.read_text(encoding="utf-8")
            html_text = html_path.read_text(encoding="utf-8")
            self.assertIn("linkStyle 0", mermaid_text)
            self.assertNotIn(";;", mermaid_text)
            self.assertIn("关系类型", html_text)
            self.assertIn("最低信任值", html_text)
            self.assertIn("关系卡片", html_text)
            if payload["svg_path"]:
                self.assertIn(".svg", html_text)

    def test_installed_persona_bundle_tool_materializes_split_files_from_profile_markdown(self):
        repo_root = Path(__file__).resolve().parents[1]
        packaged_src = repo_root / "clawhub-zaomeng-skill"

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_root = Path(tmpdir)
            dst = copy_skill_bundle(packaged_src, tmp_root, "zaomeng-skill")
            persona_dir = tmp_root / "data" / "characters" / "hongloumeng" / "林黛玉"
            persona_dir.mkdir(parents=True, exist_ok=True)
            profile_path = persona_dir / "PROFILE.generated.md"
            profile_path.write_text(
                "# PROFILE\n"
                "## Meta\n"
                "- name: 林黛玉\n"
                "- novel_id: hongloumeng\n"
                "- source_path: C:/novels/红楼梦.txt\n"
                "## Basic Positioning\n"
                "- core_identity: 贾府寄居的闺秀\n"
                "- faction_position: 贾府内眷\n"
                "- story_role: 核心主角\n"
                "- identity_anchor: 我以真心照人，也最怕真心被轻慢\n"
                "## Root Layer\n"
                "- life_experience: 寄人篱下；敏感多思\n"
                "- taboo_topics: 被轻视；被比较\n"
                "## Inner Core\n"
                "- soul_goal: 求得一份不被辜负的真情\n"
                "- core_traits: 敏感；聪慧；清傲\n"
                "- temperament_type: 清冷而锋利\n"
                "- values: 真情=10；体面=8\n"
                "- worldview: 世情热闹，真心却稀薄\n"
                "- belief_anchor: 真情不可欺\n"
                "- moral_bottom_line: 不肯以假意换安稳\n"
                "## Value And Conflict\n"
                "- self_cognition: 知道自己多心，却也不愿装作不在意\n"
                "- thinking_style: 先感受再判断\n"
                "- decision_rules: 先辨真心；再决定亲疏\n"
                "## Emotion And Stress\n"
                "- fear_triggers: 被冷落；被误解\n"
                "- stress_response: 越委屈越克制，话反而更尖\n"
                "- grievance_style: 受了委屈会拐着弯试探\n"
                "## Social Pattern\n"
                "- social_mode: 慢热而挑剔\n"
                "- others_impression: 才情高，心事也重\n"
                "- key_bonds: 贾宝玉；紫鹃\n"
                "## Voice\n"
                "- speech_style: 轻声细语里带刺\n"
                "- typical_lines: 你既这么说，我也无话可回\n"
                "- cadence: 先缓后紧\n"
                "- signature_phrases: 也罢；倒也未必\n"
                "## Capability\n"
                "- strengths: 诗才；洞察力\n"
                "- weaknesses: 多疑；内耗\n"
                "## Arc\n"
                "- arc_end: 真情=10；自保=7\n"
                "## Evidence\n"
                "- description_count: 3\n"
                "- dialogue_count: 6\n"
                "- thought_count: 2\n"
                "- chunk_count: 4\n",
                encoding="utf-8",
            )

            result = subprocess.run(
                [
                    sys.executable,
                    str(dst / "tools" / "materialize_persona_bundle.py"),
                    "--profile-file",
                    str(profile_path),
                    "--output",
                    str(tmp_root / "persona_bundle.json"),
                ],
                cwd=dst,
                check=True,
                capture_output=True,
            )

            self.assertEqual(result.returncode, 0)
            payload = json.loads((tmp_root / "persona_bundle.json").read_text(encoding="utf-8"))
            self.assertEqual(payload["character"], "林黛玉")
            self.assertEqual(Path(payload["persona_dir"]), persona_dir.resolve())
            self.assertTrue((persona_dir / "SOUL.generated.md").exists())
            self.assertTrue((persona_dir / "IDENTITY.generated.md").exists())
            self.assertTrue((persona_dir / "BACKGROUND.generated.md").exists())
            self.assertTrue((persona_dir / "CAPABILITY.generated.md").exists())
            self.assertTrue((persona_dir / "BONDS.generated.md").exists())
            self.assertTrue((persona_dir / "CONFLICTS.generated.md").exists())
            self.assertTrue((persona_dir / "ROLE.generated.md").exists())
            self.assertTrue((persona_dir / "GOALS.generated.md").exists())
            self.assertTrue((persona_dir / "STYLE.generated.md").exists())
            self.assertTrue((persona_dir / "TRAUMA.generated.md").exists())
            self.assertTrue((persona_dir / "AGENTS.generated.md").exists())
            self.assertTrue((persona_dir / "MEMORY.generated.md").exists())
            self.assertTrue((persona_dir / "NAVIGATION.generated.md").exists())
            nav_text = (persona_dir / "NAVIGATION.generated.md").read_text(encoding="utf-8")
            self.assertIn("SOUL -> GOALS -> STYLE", nav_text)


if __name__ == "__main__":
    unittest.main()
