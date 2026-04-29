#!/usr/bin/env python3

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
            self.assertFalse((dst / "runtime").exists())


if __name__ == "__main__":
    unittest.main()
