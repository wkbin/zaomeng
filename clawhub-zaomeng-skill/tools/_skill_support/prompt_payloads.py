#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .novel_preparation import build_excerpt_payload
from .persona_bundle import load_existing_persona_bundle


def _skill_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _read_utf8(path: Path) -> str:
    return path.read_text(encoding="utf-8").strip()


def build_distill_prompt_payload(
    novel_path: str | Path,
    *,
    characters: list[str] | None = None,
    max_sentences: int = 120,
    max_chars: int = 50_000,
    characters_root: str | Path | None = None,
    manifest_path: str | Path | None = None,
    update_mode: str = "auto",
) -> dict[str, object]:
    skill_root = _skill_root()
    excerpt_payload = build_excerpt_payload(
        novel_path,
        characters=characters,
        max_sentences=max_sentences,
        max_chars=max_chars,
    )
    requested_characters = [str(item).strip() for item in list(characters or []) if str(item).strip()]
    novel_id = Path(novel_path).stem.strip()
    existing_profiles, existing_profile_paths, characters_root_path = _collect_existing_profiles(
        novel_path,
        novel_id=novel_id,
        characters=requested_characters,
        characters_root=characters_root,
        manifest_path=manifest_path,
    )
    resolved_update_mode = _resolve_update_mode(update_mode, existing_profiles)

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
            "characters": requested_characters,
            "excerpt": excerpt_payload["excerpt"],
            "source_name": excerpt_payload["source_name"],
            "excerpt_focus": {
                "requested_characters": excerpt_payload["requested_characters"],
                "matched_characters": excerpt_payload["matched_characters"],
                "missing_characters": excerpt_payload["missing_characters"],
                "strategy": excerpt_payload["excerpt_strategy"],
            },
            "update_mode": resolved_update_mode,
            "existing_profiles": existing_profiles,
        },
        "meta": {
            "novel_id": novel_id,
            "source_path": excerpt_payload["source_path"],
            "max_sentences": max_sentences,
            "max_chars": max_chars,
            "characters_root": str(characters_root_path) if characters_root_path else "",
            "existing_profile_paths": existing_profile_paths,
            "existing_character_count": len(existing_profiles),
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


def _collect_existing_profiles(
    novel_path: str | Path,
    *,
    novel_id: str,
    characters: list[str],
    characters_root: str | Path | None,
    manifest_path: str | Path | None,
) -> tuple[dict[str, dict[str, Any]], dict[str, str], Path | None]:
    root = _resolve_characters_root(novel_path, novel_id=novel_id, characters_root=characters_root, manifest_path=manifest_path)
    if root is None:
        return {}, {}, None

    existing_profiles: dict[str, dict[str, Any]] = {}
    existing_profile_paths: dict[str, str] = {}
    for name in characters:
        persona_dir = root / name
        if not persona_dir.exists():
            continue
        try:
            profile = load_existing_persona_bundle(persona_dir)
        except FileNotFoundError:
            continue
        existing_profiles[name] = profile
        existing_profile_paths[name] = str(persona_dir.resolve())
    return existing_profiles, existing_profile_paths, root


def _resolve_characters_root(
    novel_path: str | Path,
    *,
    novel_id: str,
    characters_root: str | Path | None,
    manifest_path: str | Path | None,
) -> Path | None:
    explicit = _normalize_characters_root(characters_root, novel_id) if characters_root else None
    if explicit and explicit.exists():
        return explicit

    manifest_candidate = _characters_root_from_manifest(manifest_path, novel_id)
    if manifest_candidate and manifest_candidate.exists():
        return manifest_candidate

    novel_parent_candidate = Path(novel_path).resolve().parent / "data" / "characters" / novel_id
    if novel_parent_candidate.exists():
        return novel_parent_candidate

    cwd_candidate = Path.cwd() / "data" / "characters" / novel_id
    if cwd_candidate.exists():
        return cwd_candidate
    return explicit


def _normalize_characters_root(value: str | Path, novel_id: str) -> Path:
    root = Path(value).resolve()
    nested = root / novel_id
    if root.name != novel_id and nested.exists():
        return nested
    return root


def _characters_root_from_manifest(manifest_path: str | Path | None, novel_id: str) -> Path | None:
    if not manifest_path:
        return None
    manifest_file = Path(manifest_path).resolve()
    if not manifest_file.exists():
        candidate = manifest_file.parent / "data" / "characters" / novel_id
        return candidate

    payload = json.loads(manifest_file.read_text(encoding="utf-8"))
    character_dirs = payload.get("artifacts", {}).get("character_dirs", {})
    if isinstance(character_dirs, dict) and character_dirs:
        first_dir = next(iter(character_dirs.values()), "")
        if first_dir:
            return Path(first_dir).resolve().parent
    return manifest_file.parent / "data" / "characters" / novel_id


def _resolve_update_mode(update_mode: str, existing_profiles: dict[str, dict[str, Any]]) -> str:
    mode = str(update_mode or "auto").strip().lower()
    if mode == "auto":
        return "incremental" if existing_profiles else "create"
    if mode == "incremental":
        return "incremental"
    return "create"
