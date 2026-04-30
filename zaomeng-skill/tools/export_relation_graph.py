#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

TOOLS_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_ROOT))

from _skill_support.relation_graph_export import export_relation_graph
from _skill_support.workflow_completion import update_run_manifest


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Export Mermaid and HTML relation graph files from a relation markdown result."
    )
    parser.add_argument("--relations-file", required=True, help="Relation markdown file path")
    parser.add_argument("--novel-id", help="Optional explicit novel id")
    parser.add_argument("--config", help="Optional config.yaml path for custom data directories")
    parser.add_argument("--output", help="Optional JSON output path")
    parser.add_argument("--run-manifest", help="Optional run_manifest.json path")
    args = parser.parse_args()

    if args.run_manifest:
        update_run_manifest(
            args.run_manifest,
            stage="graph_export_started",
            status="running",
            message="relation graph export started",
            capability="export_graph",
            graph_status="running",
        )

    payload = export_relation_graph(
        args.relations_file,
        novel_id=args.novel_id,
        config_path=args.config,
        manifest_path=args.run_manifest,
    )
    rendered = json.dumps(payload, ensure_ascii=True, indent=2)
    if args.output:
        Path(args.output).write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
