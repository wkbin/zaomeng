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
    if args.output:
        Path(args.output).write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
