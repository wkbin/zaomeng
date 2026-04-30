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
from _skill_support.workflow_completion import build_capability_status, default_status_path, update_run_manifest, write_json


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
    parser.add_argument("--run-manifest", help="Optional run_manifest.json path")
    args = parser.parse_args()

    source = Path(args.profile_file)
    profile = load_profile_source(source)
    output_dir = Path(args.output_dir) if args.output_dir else _default_output_dir(source, profile)
    target_dir = materialize_persona_bundle(output_dir, profile)

    payload = {
        "capability": "materialize",
        "status": "complete",
        "success": True,
        "character": profile.get("name", ""),
        "novel_id": profile.get("novel_id", ""),
        "profile_source": str(source.resolve()),
        "persona_dir": str(target_dir.resolve()),
        "status_path": str((target_dir / "ARTIFACT_STATUS.generated.json").resolve()),
        "generated_files": sorted(path.name for path in target_dir.glob("*.generated.md")),
        "editable_files": sorted(path.name for path in target_dir.glob("*.md") if not path.name.endswith(".generated.md")),
    }
    output_path = Path(args.output) if args.output else None
    capability_status_path = default_status_path(
        "materialize",
        output_path=output_path,
        manifest_path=args.run_manifest,
        output_dir=target_dir,
        character=str(profile.get("name", "")).strip(),
    )
    capability_status = build_capability_status(
        "materialize",
        status=payload["status"],
        success=bool(payload["success"]),
        novel_id=str(payload["novel_id"]),
        character=str(payload["character"]),
        inputs={"profile_file": str(source.resolve())},
        outputs={
            "persona_dir": payload["persona_dir"],
            "artifact_status_path": payload["status_path"],
            "generated_files": payload["generated_files"],
        },
        manifest_path=args.run_manifest,
        message="persona bundle materialized",
    )
    write_json(capability_status_path, capability_status)
    payload["capability_status_path"] = str(capability_status_path.resolve())
    if args.run_manifest:
        update_run_manifest(
            args.run_manifest,
            stage="character_completed",
            status="running",
            message=f"{payload['character']} materialized",
            character=str(payload["character"]),
            capability="materialize",
            capability_status=capability_status,
            artifact_updates={
                "character_dirs": {str(payload["character"]): payload["persona_dir"]},
                "status_files": {"materialize": str(capability_status_path.resolve())},
            },
            status_file=capability_status_path,
        )

    rendered = json.dumps(payload, ensure_ascii=False, indent=2)
    if output_path:
        output_path.write_text(rendered + "\n", encoding="utf-8")

    if not output_path:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
