#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

from pathlib import Path
from typing import Optional

from src.core.config import Config
from src.utils.file_utils import ensure_dir, safe_filename


class PathProvider:
    """Centralized path resolution for runtime data and editable rule assets."""

    def __init__(self, config: Config):
        if config is None:
            raise ValueError("PathProvider requires an injected Config instance")
        self.config = config

    def project_root(self) -> Path:
        return Path(self.config.project_root)

    def rules_root(self) -> Path:
        return ensure_dir(self.config.get_path("rules"))

    def characters_root(self, novel_id: Optional[str] = None) -> Path:
        root = ensure_dir(self.config.get_path("characters"))
        return ensure_dir(root / novel_id) if novel_id else root

    def character_dir(self, novel_id: str, character_name: str) -> Path:
        return ensure_dir(self.characters_root(novel_id) / safe_filename(character_name))

    def relations_root(self, novel_id: Optional[str] = None) -> Path:
        root = ensure_dir(self.config.get_path("relations"))
        return ensure_dir(root / novel_id) if novel_id else root

    def relations_file(self, novel_id: str) -> Path:
        return self.relations_root(novel_id) / f"{novel_id}_relations.md"

    def sessions_dir(self) -> Path:
        return ensure_dir(self.config.get_path("sessions"))

    def corrections_dir(self) -> Path:
        return ensure_dir(self.config.get_path("corrections"))

    def logs_dir(self) -> Path:
        return ensure_dir(self.config.get_path("logs"))

    def prompt_file(self, filename: str) -> Path:
        return self._find_auxiliary_file("prompts", filename)

    def reference_file(self, filename: str) -> Path:
        return self._find_auxiliary_file("references", filename)

    def visualization_file(self, novel_id: str, suffix: str) -> Path:
        return self.relations_root(novel_id) / f"{novel_id}_relations{suffix}"

    def _find_auxiliary_file(self, folder: str, filename: str) -> Path:
        candidates = (
            self.project_root() / folder / filename,
            self.project_root() / "zaomeng-skill" / folder / filename,
            self.project_root().parent / folder / filename,
        )
        for path in candidates:
            if path.exists():
                return path
        return candidates[0]
