from __future__ import annotations

from collections import OrderedDict
from pathlib import Path
from threading import RLock
from typing import Any

from src.web.chat.text_utils import trim_summary_text


_RELATION_TEXT_CACHE_MAX_SIZE = 64
_RELATION_TEXT_CACHE: OrderedDict[Path, tuple[tuple[int, int], str]] = OrderedDict()
_RELATION_TEXT_CACHE_LOCK = RLock()


def _relation_file_signature(path: Path) -> tuple[int, int]:
    stat = path.stat()
    return stat.st_mtime_ns, stat.st_size


def _read_relation_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_text_excerpt(path_text: str, *, limit: int) -> str:
    path = Path(str(path_text or ""))
    if not path.exists() or not path.is_file():
        return ""
    resolved = path.resolve()
    try:
        signature = _relation_file_signature(resolved)
    except OSError:
        with _RELATION_TEXT_CACHE_LOCK:
            _RELATION_TEXT_CACHE.pop(resolved, None)
        return ""

    with _RELATION_TEXT_CACHE_LOCK:
        cached = _RELATION_TEXT_CACHE.get(resolved)
        if cached is not None and cached[0] == signature:
            _RELATION_TEXT_CACHE.move_to_end(resolved)
            return cached[1][:limit].strip()

    try:
        text = _read_relation_text(resolved)
    except OSError:
        with _RELATION_TEXT_CACHE_LOCK:
            _RELATION_TEXT_CACHE.pop(resolved, None)
        return ""

    with _RELATION_TEXT_CACHE_LOCK:
        _RELATION_TEXT_CACHE[resolved] = (signature, text)
        _RELATION_TEXT_CACHE.move_to_end(resolved)
        while len(_RELATION_TEXT_CACHE) > _RELATION_TEXT_CACHE_MAX_SIZE:
            _RELATION_TEXT_CACHE.popitem(last=False)
    return text[:limit].strip()


def build_relation_excerpt(
    path_text: str,
    *,
    participants: list[str],
    active_participants: list[str],
    message: str,
    scene_card: dict[str, Any],
) -> str:
    raw_excerpt = load_text_excerpt(
        path_text,
        limit=choose_relation_excerpt_scan_limit(
            participants=participants,
            active_participants=active_participants,
        ),
    )
    if not raw_excerpt:
        return ""
    excerpt_limit = choose_relation_excerpt_limit(
        participants=participants,
        active_participants=active_participants,
    )
    if len(raw_excerpt) <= excerpt_limit:
        return raw_excerpt

    focus_terms: list[str] = []
    for item in [*active_participants, *participants]:
        normalized = str(item).strip()
        if normalized and normalized not in focus_terms:
            focus_terms.append(normalized)
    for item in (
        str(scene_card.get("title", "")).strip(),
        str(scene_card.get("location", "")).strip(),
        str(scene_card.get("scene_drive", "")).strip(),
    ):
        if item and item not in focus_terms:
            focus_terms.append(item)
    trimmed_message = trim_summary_text(message, 48)
    if trimmed_message:
        focus_terms.append(trimmed_message)

    relevant = extract_relevant_relation_excerpt(raw_excerpt, focus_terms, excerpt_limit)
    if relevant:
        return relevant
    return trim_summary_text(raw_excerpt, excerpt_limit)


def choose_relation_excerpt_limit(*, participants: list[str], active_participants: list[str]) -> int:
    active_count = max(1, len([item for item in active_participants if str(item).strip()]))
    participant_count = max(active_count, len([item for item in participants if str(item).strip()]))
    return min(3200, 1200 + active_count * 500 + max(0, participant_count - active_count) * 180)


def choose_relation_excerpt_scan_limit(*, participants: list[str], active_participants: list[str]) -> int:
    return min(
        8000,
        choose_relation_excerpt_limit(
            participants=participants,
            active_participants=active_participants,
        )
        * 2,
    )


def extract_relevant_relation_excerpt(text: str, focus_terms: list[str], limit: int) -> str:
    cleaned_terms = [term for term in (str(item).strip() for item in focus_terms) if len(term) >= 2]
    if not cleaned_terms:
        return ""

    lines = [line.strip() for line in str(text or "").splitlines()]
    kept: list[str] = []
    seen: set[str] = set()
    for index, line in enumerate(lines):
        if not line:
            continue
        if not any(term in line for term in cleaned_terms):
            continue
        for neighbor in range(max(0, index - 1), min(len(lines), index + 2)):
            candidate = lines[neighbor].strip()
            if not candidate or candidate in seen:
                continue
            seen.add(candidate)
            kept.append(candidate)
            joined = "\n".join(kept)
            if len(joined) >= limit:
                return trim_summary_text(joined, limit)
    if kept:
        return trim_summary_text("\n".join(kept), limit)
    return ""


__all__ = [
    "build_relation_excerpt",
    "choose_relation_excerpt_limit",
    "choose_relation_excerpt_scan_limit",
    "extract_relevant_relation_excerpt",
    "load_text_excerpt",
]
