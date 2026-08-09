#!/usr/bin/env python3

import unittest
from pathlib import Path


class CLIStructureTests(unittest.TestCase):
    def test_standalone_cli_layer_exists(self):
        self.assertTrue(Path("src/cli/app.py").exists())
        self.assertTrue(Path("src/cli/main.py").exists())

    def test_core_cli_modules_are_now_compatibility_wrappers(self):
        core_cli_text = Path("src/core/cli_app.py").read_text(encoding="utf-8")
        core_main_text = Path("src/core/main.py").read_text(encoding="utf-8")

        self.assertIn("from src.cli.app import ChatIntent, ZaomengCLI", core_cli_text)
        self.assertIn("from src.cli.main import ChatIntent, ZaomengCLI as _SharedZaomengCLI", core_main_text)


if __name__ == "__main__":
    unittest.main()
