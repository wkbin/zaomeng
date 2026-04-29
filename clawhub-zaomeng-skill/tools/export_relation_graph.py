#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

from src.skill_support.relation_graph_export import export_relation_graph


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Export Mermaid and HTML relation graph files from a relation markdown result."
    )
    parser.add_argument("--relations-file", required=True, help="Relation markdown file path")
    parser.add_argument("--novel-id", help="Optional explicit novel id")
    parser.add_argument("--config", help="Optional config.yaml path for custom data directories")
    parser.add_argument("--output", help="Optional JSON output path")
    args = parser.parse_args()

    payload = export_relation_graph(
        args.relations_file,
        novel_id=args.novel_id,
        config_path=args.config,
    )
    rendered = json.dumps(payload, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
