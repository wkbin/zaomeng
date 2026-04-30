#!/usr/bin/env python3

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from scripts.skill_metadata import read_skill_version


class PackageSkillScriptTests(unittest.TestCase):
    def test_package_skill_archive_uses_versioned_filename_and_expected_entries(self):
        repo_root = Path(__file__).resolve().parents[1]
        script_path = repo_root / "scripts" / "package_skill.py"
        version = read_skill_version(repo_root / "clawhub-zaomeng-skill")

        with tempfile.TemporaryDirectory() as tmpdir:
            output_dir = Path(tmpdir) / "dist"
            subprocess.run(
                [
                    sys.executable,
                    str(script_path),
                    "--output-dir",
                    str(output_dir),
                ],
                cwd=repo_root,
                check=True,
            )

            archive_path = output_dir / f"zaomeng-{version}.skill.zip"
            self.assertTrue(archive_path.exists())

            with ZipFile(archive_path) as zf:
                names = set(zf.namelist())

            self.assertIn("zaomeng-skill/SKILL.md", names)
            self.assertIn("zaomeng-skill/README.md", names)
            self.assertIn("zaomeng-skill/PUBLISH.md", names)
            self.assertIn("zaomeng-skill/requirements.txt", names)
            self.assertIn("zaomeng-skill/tools/prepare_novel_excerpt.py", names)
            self.assertIn("zaomeng-skill/tools/build_prompt_payload.py", names)
            self.assertIn("zaomeng-skill/tools/export_relation_graph.py", names)
            self.assertIn("zaomeng-skill/tools/_skill_support/novel_preparation.py", names)
            self.assertIn("zaomeng-skill/prompts/distill_prompt.md", names)
            self.assertIn("zaomeng-skill/references/output_schema.md", names)
            self.assertIn("zaomeng-skill/examples/sample_character_profile.md", names)


if __name__ == "__main__":
    unittest.main()
