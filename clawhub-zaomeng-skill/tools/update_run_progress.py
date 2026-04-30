#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

TOOLS_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_ROOT))

from _skill_support.workflow_completion import STANDARD_PROGRESS_STAGES, update_run_manifest


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Append a standardized host progress event into run_manifest.json."
    )
    parser.add_argument("--run-manifest", required=True, help="run_manifest.json path")
    parser.add_argument("--stage", required=True, choices=list(STANDARD_PROGRESS_STAGES), help="Standard progress stage")
    parser.add_argument("--status", default="running", choices=["running", "complete", "failed"], help="Stage status")
    parser.add_argument("--message", default="", help="Optional user-facing progress message")
    parser.add_argument("--character", default="", help="Current character for character_started/completed")
    parser.add_argument("--total", type=int, help="Optional total character count override")
    parser.add_argument("--graph-status", default="", choices=["", "pending", "running", "complete"], help="Optional graph status override")
    args = parser.parse_args()

    payload = update_run_manifest(
        args.run_manifest,
        stage=args.stage,
        status=args.status,
        message=args.message,
        character=args.character,
        total_characters=args.total,
        graph_status=args.graph_status,
    )
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
