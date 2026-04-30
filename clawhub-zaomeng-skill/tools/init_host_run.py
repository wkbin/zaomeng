#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

TOOLS_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_ROOT))

from _skill_support.workflow_completion import initialize_run_manifest


def _split_characters(value: str) -> list[str]:
    return [item.strip() for item in str(value or "").split(",") if item.strip()]


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Initialize a host-side run_manifest.json for a distillation workflow."
    )
    parser.add_argument("--novel", required=True, help="Novel file path")
    parser.add_argument("--characters", help="Optional comma-separated locked characters")
    parser.add_argument("--novel-id", help="Optional explicit novel id")
    parser.add_argument("--output", required=True, help="run_manifest.json output path")
    args = parser.parse_args()

    payload = initialize_run_manifest(
        args.output,
        novel_path=args.novel,
        characters=_split_characters(args.characters),
        novel_id=str(args.novel_id or "").strip(),
    )
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
