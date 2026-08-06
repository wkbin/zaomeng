from __future__ import annotations

import re
import unittest
from pathlib import Path


class RuntimeRequirementsTests(unittest.TestCase):
    def test_all_web_requirement_sets_include_multipart_parser(self):
        repo_root = Path(__file__).resolve().parents[1]

        for filename in (
            "requirements.txt",
            "requirements.runtime.txt",
            "requirements.termux.txt",
        ):
            with self.subTest(filename=filename):
                requirements = (repo_root / filename).read_text(encoding="utf-8")
                self.assertRegex(
                    requirements,
                    r"(?im)^\s*python-multipart==0\.0\.20\s*$",
                )

    def test_full_requirements_include_testclient_compat_dependency(self):
        repo_root = Path(__file__).resolve().parents[1]
        requirements = (repo_root / "requirements.txt").read_text(encoding="utf-8")

        self.assertRegex(requirements, r"(?im)^\s*httpx2>=2\.0\.0,<3\.0\.0\s*$")

    def test_runtime_requirements_do_not_force_epub_stack(self):
        repo_root = Path(__file__).resolve().parents[1]
        requirements = (repo_root / "requirements.runtime.txt").read_text(encoding="utf-8")

        self.assertIsNone(re.search(r"(?im)^\s*ebooklib(?:\[.*\])?\s*(?:[<>=!~].*)?$", requirements))
        self.assertIsNone(re.search(r"(?im)^\s*tiktoken(?:\[.*\])?\s*(?:[<>=!~].*)?$", requirements))
        self.assertIsNone(re.search(r"(?im)^\s*httpx2(?:\[.*\])?\s*(?:[<>=!~].*)?$", requirements))
        self.assertIn("Optional input support", requirements)
        self.assertIn("Optional token tooling", requirements)
        self.assertRegex(requirements, r"(?im)^\s*fastapi>=0\.104\.0,<1\.0\.0\s*$")
        self.assertRegex(requirements, r"(?im)^\s*pydantic>=2\.0\.0,<3\.0\.0\s*$")

    def test_termux_requirements_avoid_rust_extensions(self):
        repo_root = Path(__file__).resolve().parents[1]
        requirements = (repo_root / "requirements.termux.txt").read_text(
            encoding="utf-8"
        )

        self.assertRegex(requirements, r"(?im)^\s*fastapi>=0\.104\.0,<0\.120\.0\s*$")
        self.assertRegex(requirements, r"(?im)^\s*pydantic>=1\.10\.20,<2\.0\.0\s*$")
        self.assertIsNone(
            re.search(
                r"(?im)^\s*(?:pydantic-core|maturin)(?:[<>=!~].*)?$",
                requirements,
            )
        )

    def test_web_schemas_support_pydantic_v1_and_v2_validator_imports(self):
        repo_root = Path(__file__).resolve().parents[1]
        schemas = (repo_root / "src" / "web" / "api" / "schemas.py").read_text(
            encoding="utf-8"
        )

        self.assertIn("from pydantic import field_validator", schemas)
        self.assertIn("from pydantic import validator as field_validator", schemas)
        self.assertNotIn(
            "items: list[SessionRef] = Field(..., min_length=1)", schemas
        )

    def test_root_readmes_describe_full_and_runtime_dependency_sets(self):
        repo_root = Path(__file__).resolve().parents[1]

        for filename in ("README.md", "README.en.md"):
            with self.subTest(filename=filename):
                readme = (repo_root / filename).read_text(encoding="utf-8")

                self.assertIn("requirements.txt", readme)
                self.assertIn("requirements.runtime.txt", readme)
                self.assertIn("requirements.termux.txt", readme)
                self.assertIn("httpx2", readme)
                self.assertIn("EPUB", readme)

    def test_webui_launcher_points_runtime_users_to_runtime_requirements(self):
        repo_root = Path(__file__).resolve().parents[1]
        launcher = (repo_root / "scripts" / "run_webui.py").read_text(encoding="utf-8")

        self.assertIn("requirements.runtime.txt", launcher)
        self.assertIn("requirements.txt", launcher)


if __name__ == "__main__":
    unittest.main()
