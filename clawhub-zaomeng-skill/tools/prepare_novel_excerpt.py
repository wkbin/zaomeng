#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

TOOLS_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_ROOT))

from _skill_support.novel_preparation import build_excerpt_payload


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Prepare a prompt-sized novel excerpt for prompt-first skill workflows."
    )
    parser.add_argument("--novel", required=True, help="Novel file path (.txt or .epub)")
    parser.add_argument("--max-sentences", type=int, default=80, help="Maximum sentence count")
    parser.add_argument("--max-chars", type=int, default=12000, help="Maximum character count")
    parser.add_argument("--output", help="Optional JSON output path")
    args = parser.parse_args()

    payload = build_excerpt_payload(
        args.novel,
        max_sentences=max(1, int(args.max_sentences)),
        max_chars=max(200, int(args.max_chars)),
    )
    rendered = json.dumps(payload, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
