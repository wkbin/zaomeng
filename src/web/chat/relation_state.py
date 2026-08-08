from __future__ import annotations

from typing import Any


def pair_key(left: str, right: str) -> str:
    first = str(left or "").strip()
    second = str(right or "").strip()
    return "_".join(sorted([first, second])) if first and second else ""


def default_relation_entry() -> dict[str, Any]:
    return {
        "trust": 5,
        "affection": 5,
        "hostility": 0,
        "ambiguity": 3,
        "evidence_lines": [],
    }


def normalize_relation_entry(raw: dict[str, Any] | None) -> dict[str, Any]:
    source = dict(raw or {})
    normalized = default_relation_entry()
    for field in ("trust", "affection", "hostility", "ambiguity"):
        try:
            normalized[field] = int(source.get(field, normalized[field]) or normalized[field])
        except Exception:
            continue
    for field in ("conflict_point", "typical_interaction", "hidden_attitude", "relation_change", "appellation_to_target", "last_event"):
        value = str(source.get(field, "")).strip()
        if value:
            normalized[field] = value
    evidence_lines = source.get("evidence_lines", [])
    if isinstance(evidence_lines, list):
        normalized["evidence_lines"] = [str(item).strip() for item in evidence_lines if str(item).strip()][:10]
    return normalized


def seed_relation_matrix(relations: dict[str, Any], participants: list[str]) -> dict[str, Any]:
    selected = [str(item).strip() for item in list(participants or []) if str(item).strip()]
    if len(selected) < 2:
        return {}
    keys: dict[str, Any] = {}
    for index, left in enumerate(selected):
        for right in selected[index + 1 :]:
            key = pair_key(left, right)
            if not key:
                continue
            keys[key] = normalize_relation_entry(dict(relations.get(key, {}) or {}))
    return keys


def merged_relation_matrix(
    relation_matrix: dict[str, Any],
    relation_delta: dict[str, Any],
    participants: list[str],
) -> dict[str, Any]:
    base = {
        str(key).strip(): normalize_relation_entry(dict(value or {}))
        for key, value in relation_matrix.items()
        if str(key).strip()
    }
    selected = [str(item).strip() for item in list(participants or []) if str(item).strip()]
    for index, left in enumerate(selected):
        for right in selected[index + 1 :]:
            key = pair_key(left, right)
            if key and key not in base:
                base[key] = default_relation_entry()
    for key, delta in relation_delta.items():
        normalized_key = str(key).strip()
        if not normalized_key:
            continue
        merged = dict(base.get(normalized_key, default_relation_entry()))
        delta_payload = dict(delta or {})
        for field in ("trust", "affection", "hostility", "ambiguity"):
            try:
                step = int(delta_payload.get(field, 0) or 0)
            except Exception:
                step = 0
            fallback = default_relation_entry()[field]
            baseline = int(merged.get(field, fallback) or fallback)
            merged[field] = max(0, min(10, baseline + step))
        for field in ("last_event", "relation_change", "typical_interaction", "last_actor", "last_target", "updated_at"):
            value = str(delta_payload.get(field, "")).strip()
            if value:
                merged[field] = value
        if "momentum" in delta_payload:
            try:
                merged["momentum"] = int(delta_payload.get("momentum", 0) or 0)
            except Exception:
                pass
        evidence_lines = list(merged.get("evidence_lines", []) or [])
        for item in list(delta_payload.get("evidence_lines", []) or []):
            text = str(item).strip()
            if text:
                evidence_lines.append(text)
        if evidence_lines:
            merged["evidence_lines"] = evidence_lines[-10:]
        base[normalized_key] = merged
    return base


__all__ = [
    "default_relation_entry",
    "merged_relation_matrix",
    "normalize_relation_entry",
    "pair_key",
    "seed_relation_matrix",
]
