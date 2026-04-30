#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


REQUIRED_PERSONA_FILES = (
    "PROFILE.generated.md",
    "SOUL.generated.md",
    "IDENTITY.generated.md",
    "BACKGROUND.generated.md",
    "CAPABILITY.generated.md",
    "BONDS.generated.md",
    "CONFLICTS.generated.md",
    "ROLE.generated.md",
    "AGENTS.generated.md",
    "MEMORY.generated.md",
    "NAVIGATION.generated.md",
)


def write_json(path: Path, payload: dict[str, Any]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def build_persona_completion_status(persona_dir: str | Path, *, name: str = "", novel_id: str = "") -> dict[str, Any]:
    root = Path(persona_dir)
    present_files = sorted(path.name for path in root.iterdir() if path.is_file()) if root.exists() else []
    missing_required = [filename for filename in REQUIRED_PERSONA_FILES if not (root / filename).exists()]
    return {
        "kind": "persona_bundle",
        "character": name or root.name,
        "novel_id": novel_id,
        "persona_dir": str(root.resolve()) if root.exists() else str(root),
        "status": "complete" if not missing_required else "incomplete",
        "required_files": list(REQUIRED_PERSONA_FILES),
        "missing_required_files": missing_required,
        "present_files": present_files,
    }


def build_relation_completion_status(
    relations_file: str | Path,
    *,
    novel_id: str,
    html_path: str | Path,
    mermaid_path: str | Path,
    svg_path: str | Path | None = None,
) -> dict[str, Any]:
    relation_path = Path(relations_file)
    html_file = Path(html_path)
    mermaid_file = Path(mermaid_path)
    svg_file = Path(svg_path) if svg_path else None
    required = [str(mermaid_file.name), str(html_file.name)]
    missing = []
    if not mermaid_file.exists():
        missing.append(mermaid_file.name)
    if not html_file.exists():
        missing.append(html_file.name)
    return {
        "kind": "relation_graph",
        "novel_id": novel_id,
        "relations_file": str(relation_path.resolve()) if relation_path.exists() else str(relation_path),
        "status": "complete" if not missing else "incomplete",
        "required_files": required,
        "missing_required_files": missing,
        "html_path": str(html_file.resolve()) if html_file.exists() else str(html_file),
        "mermaid_path": str(mermaid_file.resolve()) if mermaid_file.exists() else str(mermaid_file),
        "svg_path": str(svg_file.resolve()) if svg_file and svg_file.exists() else "",
        "svg_generated": bool(svg_file and svg_file.exists()),
    }


def verify_host_workflow(
    characters_root: str | Path,
    *,
    characters: list[str] | None = None,
    relation_status: dict[str, Any] | None = None,
) -> dict[str, Any]:
    root = Path(characters_root)
    if characters:
        names = list(characters)
    elif root.exists():
        names = sorted(path.name for path in root.iterdir() if path.is_dir())
    else:
        names = []

    persona_statuses = [
        build_persona_completion_status(root / name, name=name, novel_id=root.name if root.exists() else "")
        for name in names
    ]
    missing_characters = [name for name in names if not (root / name).exists()]
    ok = not missing_characters and all(item["status"] == "complete" for item in persona_statuses)
    if relation_status is not None:
        ok = ok and relation_status.get("status") == "complete"

    return {
        "kind": "host_workflow",
        "characters_root": str(root.resolve()) if root.exists() else str(root),
        "status": "complete" if ok else "incomplete",
        "characters": persona_statuses,
        "missing_character_dirs": missing_characters,
        "relation_graph": relation_status or {},
    }
