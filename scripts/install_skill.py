#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


PROMPT_FIRST_FILES = (
    ".metadata.json",
    "SKILL.md",
    "README.md",
    "README_EN.md",
    "INSTALL.md",
    "MANIFEST.md",
    "PUBLISH.md",
    "requirements.txt",
)
PROMPT_FIRST_DIRS = (
    "prompts",
    "references",
    "examples",
    "tools",
)


def iter_skill_entries() -> tuple[str, ...]:
    return (*PROMPT_FIRST_FILES, *PROMPT_FIRST_DIRS)


def copy_skill_bundle(src: Path, dst_root: Path, skill_name: str) -> Path:
    dst = dst_root / skill_name
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True, exist_ok=True)

    for entry_name in iter_skill_entries():
        source = src / entry_name
        if not source.exists():
            continue
        target = dst / entry_name
        if source.is_dir():
            shutil.copytree(source, target)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    return dst


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Install the zaomeng skill as a prompt-first package for OpenClaw, "
            "Hermes Agent, or a local project skills directory"
        )
    )
    parser.add_argument("--openclaw-dir", help="OpenClaw skills root directory")
    parser.add_argument("--hermes-dir", help="Hermes Agent skills root directory")
    parser.add_argument("--skills-dir", help="Generic skills root directory for your own project")
    parser.add_argument(
        "--project-root",
        help="Local project root; installs the generic skill into <project-root>/skills/",
    )
    parser.add_argument(
        "--skill-name",
        default="zaomeng-skill",
        help="Target skill folder name for generic installs (default: zaomeng-skill)",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[1]
    packaged_src = repo_root / "zaomeng-skill"

    if not packaged_src.exists():
        raise FileNotFoundError("Missing zaomeng-skill/ directory")

    if not any([args.openclaw_dir, args.hermes_dir, args.skills_dir, args.project_root]):
        print("No target provided. Use --openclaw-dir, --hermes-dir, --skills-dir, or --project-root")
        return 1

    if args.openclaw_dir:
        target = copy_skill_bundle(
            packaged_src,
            Path(args.openclaw_dir),
            "zaomeng-skill",
        )
        print(f"Installed OpenClaw skill to: {target}")

    if args.hermes_dir:
        target = copy_skill_bundle(
            packaged_src,
            Path(args.hermes_dir),
            "zaomeng-skill",
        )
        print(f"Installed Hermes skill to: {target}")

    if args.skills_dir:
        target = copy_skill_bundle(
            packaged_src,
            Path(args.skills_dir),
            args.skill_name,
        )
        print(f"Installed generic skill to: {target}")

    if args.project_root:
        project_skills_root = Path(args.project_root) / "skills"
        target = copy_skill_bundle(
            packaged_src,
            project_skills_root,
            args.skill_name,
        )
        print(f"Installed project skill to: {target}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
