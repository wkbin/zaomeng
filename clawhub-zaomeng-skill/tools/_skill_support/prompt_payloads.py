#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

from pathlib import Path

from .novel_preparation import build_excerpt_payload


def _skill_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _read_utf8(path: Path) -> str:
    return path.read_text(encoding="utf-8").strip()


def build_distill_prompt_payload(
    novel_path: str | Path,
    *,
    characters: list[str] | None = None,
    max_sentences: int = 80,
    max_chars: int = 12_000,
) -> dict[str, object]:
    skill_root = _skill_root()
    excerpt_payload = build_excerpt_payload(
        novel_path,
        max_sentences=max_sentences,
        max_chars=max_chars,
    )
    return {
        "mode": "distill",
        "prompt": _read_utf8(skill_root / "prompts" / "distill_prompt.md"),
        "references": {
            "output_schema": _read_utf8(skill_root / "references" / "output_schema.md"),
            "style_differ": _read_utf8(skill_root / "references" / "style_differ.md"),
            "logic_constraint": _read_utf8(skill_root / "references" / "logic_constraint.md"),
            "validation_policy": _read_utf8(skill_root / "references" / "validation_policy.md"),
        },
        "request": {
            "characters": list(characters or []),
            "excerpt": excerpt_payload["excerpt"],
            "source_name": excerpt_payload["source_name"],
        },
        "meta": {
            "source_path": excerpt_payload["source_path"],
            "max_sentences": max_sentences,
            "max_chars": max_chars,
        },
    }


def build_relation_prompt_payload(
    novel_path: str | Path,
    *,
    max_sentences: int = 80,
    max_chars: int = 12_000,
) -> dict[str, object]:
    skill_root = _skill_root()
    excerpt_payload = build_excerpt_payload(
        novel_path,
        max_sentences=max_sentences,
        max_chars=max_chars,
    )
    return {
        "mode": "relation",
        "prompt": _read_utf8(skill_root / "prompts" / "relation_prompt.md"),
        "references": {
            "output_schema": _read_utf8(skill_root / "references" / "output_schema.md"),
            "logic_constraint": _read_utf8(skill_root / "references" / "logic_constraint.md"),
            "validation_policy": _read_utf8(skill_root / "references" / "validation_policy.md"),
        },
        "request": {
            "excerpt": excerpt_payload["excerpt"],
            "source_name": excerpt_payload["source_name"],
        },
        "meta": {
            "source_path": excerpt_payload["source_path"],
            "max_sentences": max_sentences,
            "max_chars": max_chars,
        },
    }
