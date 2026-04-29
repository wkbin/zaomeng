#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

TOOLS_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_ROOT))

from _skill_support.persona_bundle import load_profile_source, materialize_persona_bundle


def _default_output_dir(source: Path, profile: dict[str, object]) -> Path:
    if source.suffix.lower() == ".json":
        name = str(profile.get("name", "")).strip() or source.stem
        return source.parent / name
    if source.name.startswith("PROFILE"):
        return source.parent
    return source.parent / (str(profile.get("name", "")).strip() or source.stem)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Materialize split persona bundle files from a canonical profile source."
    )
    parser.add_argument(
        "--profile-file",
        required=True,
        help="Canonical profile source (.md or .json). PROFILE.generated.md is supported.",
    )
    parser.add_argument(
        "--output-dir",
        help="Optional explicit character output directory. Defaults to the source character folder.",
    )
    parser.add_argument("--output", help="Optional JSON output path")
    args = parser.parse_args()

    source = Path(args.profile_file)
    profile = load_profile_source(source)
    output_dir = Path(args.output_dir) if args.output_dir else _default_output_dir(source, profile)
    target_dir = materialize_persona_bundle(output_dir, profile)

    payload = {
        "character": profile.get("name", ""),
        "novel_id": profile.get("novel_id", ""),
        "profile_source": str(source.resolve()),
        "persona_dir": str(target_dir.resolve()),
        "generated_files": sorted(path.name for path in target_dir.glob("*.generated.md")),
        "editable_files": sorted(path.name for path in target_dir.glob("*.md") if not path.name.endswith(".generated.md")),
    }
    rendered = json.dumps(payload, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
