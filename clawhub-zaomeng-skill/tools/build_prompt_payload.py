#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

TOOLS_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_ROOT))

from _skill_support.prompt_payloads import build_distill_prompt_payload, build_relation_prompt_payload
from _skill_support.workflow_completion import (
    build_capability_status,
    default_status_path,
    infer_novel_id,
    update_run_manifest,
    write_json,
)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build a prompt-first payload for host-side LLM execution."
    )
    parser.add_argument("--mode", choices=["distill", "relation"], required=True, help="Prompt payload mode")
    parser.add_argument("--novel", required=True, help="Novel file path (.txt or .epub)")
    parser.add_argument("--characters", help="Comma-separated characters for distill mode")
    parser.add_argument("--max-sentences", type=int, default=80, help="Maximum sentence count")
    parser.add_argument("--max-chars", type=int, default=12000, help="Maximum character count")
    parser.add_argument("--output", help="Optional JSON output path")
    parser.add_argument("--status-output", help="Optional status JSON output path")
    parser.add_argument("--run-manifest", help="Optional run_manifest.json path")
    args = parser.parse_args()

    max_sentences = max(1, int(args.max_sentences))
    max_chars = max(200, int(args.max_chars))
    if args.mode == "distill":
        characters = [item.strip() for item in str(args.characters or "").split(",") if item.strip()]
        payload = build_distill_prompt_payload(
            args.novel,
            characters=characters,
            max_sentences=max_sentences,
            max_chars=max_chars,
        )
    else:
        payload = build_relation_prompt_payload(
            args.novel,
            max_sentences=max_sentences,
            max_chars=max_chars,
        )

    rendered = json.dumps(payload, ensure_ascii=False, indent=2)
    output_path = Path(args.output) if args.output else None
    if output_path:
        output_path.write_text(rendered + "\n", encoding="utf-8")

    capability = "distill" if args.mode == "distill" else "relation"
    status_name = capability
    status_payload = build_capability_status(
        capability,
        status="ready",
        success=True,
        novel_id=infer_novel_id(args.novel),
        inputs={
            "novel": str(Path(args.novel).resolve()),
            "characters": [item.strip() for item in str(args.characters or "").split(",") if item.strip()],
            "max_sentences": max_sentences,
            "max_chars": max_chars,
        },
        outputs={
            "payload_path": str(output_path.resolve()) if output_path else "",
            "mode": args.mode,
            "locked_characters": list(payload.get("request", {}).get("characters", []))
            if isinstance(payload.get("request", {}), dict)
            else [],
        },
        manifest_path=args.run_manifest,
        message=f"{capability} payload ready",
    )
    status_path = default_status_path(
        status_name,
        output_path=output_path,
        manifest_path=args.run_manifest,
        output_dir=output_path.parent if output_path else None,
    )
    if args.status_output:
        status_path = Path(args.status_output)
    write_json(status_path, status_payload)

    if args.run_manifest:
        artifact_key = "distill_payload" if args.mode == "distill" else "relation_payload"
        stage = "distill_payload_ready" if args.mode == "distill" else "relation_payload_ready"
        update_run_manifest(
            args.run_manifest,
            stage=stage,
            status="running",
            message=f"{capability} payload ready",
            capability="distill" if args.mode == "distill" else "",
            capability_status=status_payload if args.mode == "distill" else None,
            artifact_updates={
                "payloads": {artifact_key: str(output_path.resolve()) if output_path else ""},
                "status_files": {status_name: str(status_path.resolve())},
            },
            total_characters=len(status_payload["outputs"].get("locked_characters", []))
            if args.mode == "distill"
            else None,
        )

    if not output_path:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
