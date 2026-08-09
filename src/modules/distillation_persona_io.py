#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

from pathlib import Path
from typing import Any, Dict

from src.modules.distillation_persona import (
    export_persona_bundle,
    refresh_persona_navigation,
    render_agents_md,
    render_background_md,
    render_bonds_md,
    render_capability_md,
    render_conflicts_md,
    render_goals_md,
    render_identity_md,
    render_memory_md,
    render_navigation_generated_md,
    render_navigation_override_md,
    render_profile_md,
    render_role_md,
    render_soul_md,
    render_style_md,
    render_trauma_md,
    should_create_goals_md,
    should_create_style_md,
    should_create_trauma_md,
)


class DistillationPersonaIOMixin:
    def refresh_navigation(self, persona_dir: Path, character_name: str) -> None:
        self.refresh_persona_navigation(persona_dir, character_name)

    def _export_persona_bundle(self, out_dir: Path, profile: Dict[str, Any]) -> None:
        export_persona_bundle(
            out_dir,
            profile,
            default_nav_load_order=self.DEFAULT_NAV_LOAD_ORDER,
            persona_file_catalog=self.PERSONA_FILE_CATALOG,
        )

    @classmethod
    def refresh_persona_navigation(cls, persona_dir: Path, character_name: str) -> None:
        refresh_persona_navigation(
            persona_dir,
            character_name,
            default_nav_load_order=cls.DEFAULT_NAV_LOAD_ORDER,
            persona_file_catalog=cls.PERSONA_FILE_CATALOG,
        )

    @classmethod
    def _render_navigation_generated_md(cls, persona_dir: Path, character_name: str) -> str:
        return render_navigation_generated_md(
            persona_dir,
            character_name,
            default_nav_load_order=cls.DEFAULT_NAV_LOAD_ORDER,
            persona_file_catalog=cls.PERSONA_FILE_CATALOG,
        )

    @staticmethod
    def _render_navigation_override_md() -> str:
        return render_navigation_override_md()

    @staticmethod
    def _persona_file_exists(persona_dir: Path, base_name: str) -> bool:
        return (persona_dir / f"{base_name}.md").exists() or (persona_dir / f"{base_name}.generated.md").exists()

    @classmethod
    def _persona_file_is_active(cls, persona_dir: Path, base_name: str) -> bool:
        if not cls.PERSONA_FILE_CATALOG.get(base_name, {}).get("optional", True):
            return True
        return cls._persona_file_exists(persona_dir, base_name)

    def _render_profile_md(self, profile: Dict[str, Any]) -> str:
        return render_profile_md(profile)

    def _render_soul_md(self, profile: Dict[str, Any]) -> str:
        return render_soul_md(profile)

    def _render_goals_md(self, profile: Dict[str, Any]) -> str:
        return render_goals_md(profile)

    def _render_style_md(self, profile: Dict[str, Any]) -> str:
        return render_style_md(profile)

    def _render_trauma_md(self, profile: Dict[str, Any]) -> str:
        return render_trauma_md(profile)

    def _render_identity_md(self, profile: Dict[str, Any]) -> str:
        return render_identity_md(profile)

    def _render_background_md(self, profile: Dict[str, Any]) -> str:
        return render_background_md(profile)

    def _render_capability_md(self, profile: Dict[str, Any]) -> str:
        return render_capability_md(profile)

    def _render_bonds_md(self, profile: Dict[str, Any]) -> str:
        return render_bonds_md(profile)

    def _render_conflicts_md(self, profile: Dict[str, Any]) -> str:
        return render_conflicts_md(profile)

    def _render_role_md(self, profile: Dict[str, Any]) -> str:
        return render_role_md(profile)

    def _render_agents_md(self, profile: Dict[str, Any]) -> str:
        return render_agents_md(profile)

    def _render_memory_md(self, profile: Dict[str, Any]) -> str:
        return render_memory_md(profile)

    @staticmethod
    def _should_create_goals_md(profile: Dict[str, Any]) -> bool:
        return should_create_goals_md(profile)

    @staticmethod
    def _should_create_style_md(profile: Dict[str, Any]) -> bool:
        return should_create_style_md(profile)

    @staticmethod
    def _should_create_trauma_md(profile: Dict[str, Any]) -> bool:
        return should_create_trauma_md(profile)
