from __future__ import annotations

import re
from pathlib import Path


_STORAGE_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]+$")
_MAX_STORAGE_ID_LENGTH = 128


class InvalidStorageIdentifier(ValueError):
    """Raised when a URL-derived identifier cannot safely name a directory."""


def validate_storage_id(value: str, *, field_name: str) -> str:
    normalized = str(value or "").strip()
    if (
        not normalized
        or len(normalized) > _MAX_STORAGE_ID_LENGTH
        or _STORAGE_ID_PATTERN.fullmatch(normalized) is None
    ):
        raise InvalidStorageIdentifier(
            f"Invalid {field_name}. Use only letters, numbers, underscores, and hyphens."
        )
    return normalized


def resolve_storage_child(root: str | Path, value: str, *, field_name: str) -> Path:
    safe_value = validate_storage_id(value, field_name=field_name)
    resolved_root = Path(root).resolve(strict=False)
    candidate = (resolved_root / safe_value).resolve(strict=False)
    try:
        candidate.relative_to(resolved_root)
    except ValueError as exc:
        raise InvalidStorageIdentifier(f"Invalid {field_name}: path escapes storage root.") from exc
    return candidate


__all__ = ["InvalidStorageIdentifier", "resolve_storage_child", "validate_storage_id"]
