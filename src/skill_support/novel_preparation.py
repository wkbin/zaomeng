#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

from pathlib import Path
from typing import Any

from src.utils.text_parser import load_novel_text, split_sentences


def prepare_novel_excerpt(
    text: str,
    *,
    max_sentences: int = 80,
    max_chars: int = 12_000,
    characters: list[str] | None = None,
) -> str:
    return build_excerpt_payload_from_text(
        text,
        max_sentences=max_sentences,
        max_chars=max_chars,
        characters=characters,
    )["excerpt"]


def build_excerpt_payload_from_text(
    text: str,
    *,
    max_sentences: int = 80,
    max_chars: int = 12_000,
    characters: list[str] | None = None,
) -> dict[str, Any]:
    clean = str(text or "").strip()
    if not clean:
        return {
            "excerpt": "",
            "requested_characters": _normalize_characters(characters),
            "matched_characters": [],
            "missing_characters": _normalize_characters(characters),
            "excerpt_strategy": "empty",
        }

    sentences = split_sentences(clean)
    requested = _normalize_characters(characters)
    if requested:
        payload = _character_focused_excerpt(
            sentences,
            requested,
            max_sentences=max_sentences,
            max_chars=max_chars,
        )
        if payload["excerpt"]:
            return payload

    return {
        "excerpt": _leading_excerpt(sentences, max_sentences=max_sentences, max_chars=max_chars),
        "requested_characters": requested,
        "matched_characters": [],
        "missing_characters": requested,
        "excerpt_strategy": "leading_sentences",
    }


def load_prepared_novel_excerpt(
    novel_path: str | Path,
    *,
    max_sentences: int = 80,
    max_chars: int = 12_000,
    characters: list[str] | None = None,
) -> str:
    return prepare_novel_excerpt(
        load_novel_text(str(novel_path)),
        max_sentences=max_sentences,
        max_chars=max_chars,
        characters=characters,
    )


def build_excerpt_payload(
    novel_path: str | Path,
    *,
    max_sentences: int = 80,
    max_chars: int = 12_000,
    characters: list[str] | None = None,
) -> dict[str, object]:
    path = Path(novel_path)
    excerpt_payload = build_excerpt_payload_from_text(
        load_novel_text(str(path)),
        max_sentences=max_sentences,
        max_chars=max_chars,
        characters=characters,
    )
    return {
        "source_path": str(path),
        "source_name": path.name,
        "max_sentences": max_sentences,
        "max_chars": max_chars,
        "requested_characters": list(excerpt_payload["requested_characters"]),
        "matched_characters": list(excerpt_payload["matched_characters"]),
        "missing_characters": list(excerpt_payload["missing_characters"]),
        "excerpt_strategy": str(excerpt_payload["excerpt_strategy"]),
        "excerpt": str(excerpt_payload["excerpt"]),
    }


def _normalize_characters(characters: list[str] | None) -> list[str]:
    ordered: list[str] = []
    seen: set[str] = set()
    for item in list(characters or []):
        name = str(item or "").strip()
        if not name or name in seen:
            continue
        seen.add(name)
        ordered.append(name)
    return ordered


def _leading_excerpt(sentences: list[str], *, max_sentences: int, max_chars: int) -> str:
    selected: list[str] = []
    total_chars = 0
    for sentence in sentences:
        if len(selected) >= max_sentences:
            break
        projected = total_chars + len(sentence) + (1 if selected else 0)
        if selected and projected > max_chars:
            break
        if not selected and len(sentence) > max_chars:
            return sentence[:max_chars].strip()
        selected.append(sentence)
        total_chars = projected

    if selected:
        return "\n".join(selected).strip()
    return "".join(sentences)[:max_chars].strip()


def _character_focused_excerpt(
    sentences: list[str],
    characters: list[str],
    *,
    max_sentences: int,
    max_chars: int,
) -> dict[str, Any]:
    character_hits: dict[str, list[int]] = {name: [] for name in characters}
    all_hit_indices: list[int] = []
    seen_hits: set[int] = set()

    for idx, sentence in enumerate(sentences):
        for name in characters:
            if name and name in sentence:
                character_hits[name].append(idx)
                if idx not in seen_hits:
                    seen_hits.add(idx)
                    all_hit_indices.append(idx)

    matched = [name for name, hits in character_hits.items() if hits]
    missing = [name for name in characters if not character_hits[name]]
    if not matched:
        return {
            "excerpt": "",
            "requested_characters": characters,
            "matched_characters": [],
            "missing_characters": missing,
            "excerpt_strategy": "leading_sentences",
        }

    mandatory_indices: list[int] = []
    for name in characters:
        hits = character_hits.get(name, [])
        if not hits:
            continue
        mandatory_indices.extend(_window_indices(hits[0], len(sentences)))

    candidate_indices: list[int] = []
    seen_candidates: set[int] = set()
    for idx in mandatory_indices:
        if idx not in seen_candidates:
            seen_candidates.add(idx)
            candidate_indices.append(idx)
    for hit_idx in all_hit_indices:
        for idx in _window_indices(hit_idx, len(sentences)):
            if idx not in seen_candidates:
                seen_candidates.add(idx)
                candidate_indices.append(idx)

    selected_indices: list[int] = []
    used_indices: set[int] = set()
    total_chars = 0
    for idx in candidate_indices:
        if len(selected_indices) >= max_sentences or idx in used_indices:
            continue
        sentence = sentences[idx]
        projected = total_chars + len(sentence) + (1 if selected_indices else 0)
        if selected_indices and projected > max_chars:
            continue
        if not selected_indices and len(sentence) > max_chars:
            selected_indices.append(idx)
            used_indices.add(idx)
            total_chars = max_chars
            break
        selected_indices.append(idx)
        used_indices.add(idx)
        total_chars = projected

    selected_indices.sort()
    selected_sentences = [sentences[idx][:max_chars].strip() if i == 0 and len(sentences[idx]) > max_chars else sentences[idx] for i, idx in enumerate(selected_indices)]
    excerpt = "\n".join(item for item in selected_sentences if item.strip()).strip()
    return {
        "excerpt": excerpt,
        "requested_characters": characters,
        "matched_characters": matched,
        "missing_characters": missing,
        "excerpt_strategy": "character_windows",
    }


def _window_indices(center: int, total: int, radius: int = 1) -> list[int]:
    start = max(0, center - radius)
    end = min(total, center + radius + 1)
    return list(range(start, end))
