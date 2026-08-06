from __future__ import annotations

import hashlib
from pathlib import Path


AVATAR_DIRECTORY = "avatars"
AVATAR_SUFFIX = ".png"


def avatar_relative_path(character: str) -> Path:
    normalized = str(character or "").strip()
    if not normalized:
        raise ValueError("Character is required.")
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
    return Path(AVATAR_DIRECTORY) / f"{digest}{AVATAR_SUFFIX}"


def avatar_path(run_dir: Path, character: str) -> Path:
    return run_dir / avatar_relative_path(character)


def avatar_version(path: Path) -> str:
    if not path.exists() or not path.is_file():
        return ""
    stat = path.stat()
    return f"{stat.st_mtime_ns}-{stat.st_size}"
