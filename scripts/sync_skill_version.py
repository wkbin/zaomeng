#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def _replace_once(text: str, pattern: str, replacement: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f"Pattern not found: {pattern}")
    return updated


def sync_skill_version(skill_dir: Path, version: str) -> None:
    version = str(version).strip()
    if not version:
        raise ValueError("Version cannot be empty")

    metadata_path = skill_dir / ".metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    metadata["version"] = version
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    replacements = {
        skill_dir / "SKILL.md": (r"(\| 版本 \| `)[^`]+(` \|)", rf"\g<1>{version}\g<2>"),
        skill_dir / "README.md": (r"(\| 版本 \| `)[^`]+(` \|)", rf"\g<1>{version}\g<2>"),
        skill_dir / "README_EN.md": (r"(\| Version \| `)[^`]+(` \|)", rf"\g<1>{version}\g<2>"),
        skill_dir / "PUBLISH.md": (r"(^- Version:\s*)[^\s]+(\s*$)", rf"\g<1>{version}\g<2>"),
    }

    for path, (pattern, replacement) in replacements.items():
        text = path.read_text(encoding="utf-8")
        path.write_text(_replace_once(text, pattern, replacement), encoding="utf-8")

    prompts_path = skill_dir / "examples" / "test-prompts.json"
    prompts_payload = json.loads(prompts_path.read_text(encoding="utf-8"))
    prompts_payload["version"] = version
    prompts_path.write_text(json.dumps(prompts_payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Sync the skill version across metadata, docs, and example assets.")
    parser.add_argument("--version", required=True, help="Target skill version.")
    parser.add_argument("--skill-dir", default="clawhub-zaomeng-skill", help="Skill directory relative to the repo root.")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[1]
    skill_dir = (repo_root / args.skill_dir).resolve()
    if not skill_dir.exists():
        raise FileNotFoundError(f"Missing skill directory: {skill_dir}")

    sync_skill_version(skill_dir, args.version)
    print(f"Synchronized skill version to {args.version} in {skill_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
