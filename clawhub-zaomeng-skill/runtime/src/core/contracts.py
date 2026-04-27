#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List, Optional, Protocol


class CostEstimator(Protocol):
    def estimate_cost(self, prompt: str, expected_completion_ratio: float = 0.0) -> float:
        ...


class CorrectionService(Protocol):
    def detect_ooc(self, profile: Dict[str, Any], message: str) -> Any:
        ...

    def search_similar_corrections(
        self,
        text: str,
        character: Optional[str] = None,
        target: Optional[str] = None,
        top_k: int = 3,
    ) -> List[Dict[str, Any]]:
        ...

    def relation_alignment_issues(self, message: str, relation_state: Dict[str, Any]) -> List[str]:
        ...


class RuleProvider(Protocol):
    def section(self, name: str) -> Dict[str, Any]:
        ...

    def get(self, section: str, key: str, default: Any = None) -> Any:
        ...


class PathProviderLike(Protocol):
    def project_root(self) -> Path:
        ...

    def characters_root(self, novel_id: Optional[str] = None) -> Path:
        ...

    def character_dir(self, novel_id: str, character_name: str) -> Path:
        ...

    def relations_root(self, novel_id: Optional[str] = None) -> Path:
        ...

    def relations_file(self, novel_id: str) -> Path:
        ...

    def sessions_dir(self) -> Path:
        ...

    def corrections_dir(self) -> Path:
        ...

