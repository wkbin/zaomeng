#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

TOOLS_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_ROOT))

from _skill_support.workflow_completion import (
    build_relation_completion_status,
    verify_host_workflow,
)


def _split_characters(value: str) -> list[str]:
    return [item.strip() for item in str(value or "").split(",") if item.strip()]


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify whether a host-driven distillation run produced a complete artifact set."
    )
    parser.add_argument("--characters-root", required=True, help="Novel character root directory")
    parser.add_argument("--characters", help="Optional comma-separated character names")
    parser.add_argument("--relations-file", help="Optional relation markdown file to validate graph artifacts")
    parser.add_argument("--output", help="Optional JSON output path")
    args = parser.parse_args()

    relation_status = None
    if args.relations_file:
        relation_path = Path(args.relations_file)
        base_name = relation_path.stem
        relation_status = build_relation_completion_status(
            relation_path,
            novel_id=relation_path.parent.name,
            html_path=relation_path.parent / f"{base_name}.html",
            mermaid_path=relation_path.parent / f"{base_name}.mermaid.md",
            svg_path=relation_path.parent / f"{base_name}.svg",
        )

    payload = verify_host_workflow(
        args.characters_root,
        characters=_split_characters(args.characters),
        relation_status=relation_status,
    )
    rendered = json.dumps(payload, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0 if payload.get("status") == "complete" else 1


if __name__ == "__main__":
    raise SystemExit(main())
