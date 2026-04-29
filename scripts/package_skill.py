#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

try:
    from .install_skill import iter_skill_entries
except ImportError:
    from install_skill import iter_skill_entries


VERSION_PATTERN = re.compile(r"^- Version:\s*(?P<version>[^\s]+)\s*$", re.MULTILINE)


def read_skill_version(publish_path: Path) -> str:
    text = publish_path.read_text(encoding="utf-8")
    match = VERSION_PATTERN.search(text)
    if not match:
        raise ValueError(f"Could not find skill version in {publish_path}")
    return match.group("version")


def iter_files(root: Path):
    for path in sorted(root.rglob("*")):
        if path.is_file() and "__pycache__" not in path.parts:
            yield path


def build_archive(
    skill_dir: Path,
    output_dir: Path,
    *,
    version: str,
    package_name: str = "zaomeng",
    archive_root: str = "zaomeng-skill",
) -> Path:
    archive_name = f"{package_name}-{version}.skill.zip"
    archive_path = output_dir / archive_name
    output_dir.mkdir(parents=True, exist_ok=True)

    with ZipFile(archive_path, "w", compression=ZIP_DEFLATED) as zf:
        for entry_name in iter_skill_entries():
            source = skill_dir / entry_name
            if not source.exists():
                continue

            if source.is_dir():
                for file_path in iter_files(source):
                    relative = file_path.relative_to(skill_dir)
                    zf.write(file_path, Path(archive_root) / relative)
            else:
                zf.write(source, Path(archive_root) / entry_name)

    return archive_path


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Package clawhub-zaomeng-skill into a versioned .skill.zip archive."
    )
    parser.add_argument(
        "--skill-dir",
        default="clawhub-zaomeng-skill",
        help="Skill source directory relative to the repo root.",
    )
    parser.add_argument(
        "--output-dir",
        default="dist",
        help="Directory where the packaged .skill.zip archive will be written.",
    )
    parser.add_argument(
        "--version",
        help="Optional version override. Defaults to the Version value in clawhub-zaomeng-skill/PUBLISH.md.",
    )
    parser.add_argument(
        "--package-name",
        default="zaomeng",
        help="Archive filename prefix. Default: zaomeng",
    )
    parser.add_argument(
        "--archive-root",
        default="zaomeng-skill",
        help="Top-level folder name inside the zip archive. Default: zaomeng-skill",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[1]
    skill_dir = (repo_root / args.skill_dir).resolve()
    output_dir = (repo_root / args.output_dir).resolve()

    if not skill_dir.exists():
        raise FileNotFoundError(f"Missing skill directory: {skill_dir}")

    version = args.version or read_skill_version(skill_dir / "PUBLISH.md")
    archive_path = build_archive(
        skill_dir,
        output_dir,
        version=version,
        package_name=args.package_name,
        archive_root=args.archive_root,
    )
    print(f"Packaged skill archive: {archive_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
