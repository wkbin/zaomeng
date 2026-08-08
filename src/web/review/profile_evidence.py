from __future__ import annotations

import json
import re
import unicodedata
from pathlib import Path
from typing import Any, Callable

from src.utils.text_parser import split_sentences


PERSONA_EVIDENCE_FILENAME = "EVIDENCE.generated.json"
PERSONA_EVIDENCE_SCHEMA_VERSION = "persona-evidence/v1"
_REFERENCE_LIMIT = 18
_STAGE_LABELS = {
    "start": "前段",
    "mid": "中段",
    "end": "后段",
    "excerpt": "正文片段",
}
_KIND_LABELS = {
    "description": "描写",
    "dialogue": "对白",
    "thought": "心理活动",
}


def finalize_generated_profile_source(
    source_path: Path,
    *,
    payload: dict[str, Any],
    chunk_count: int,
    load_profile_source: Callable[[Path], dict[str, Any]],
    render_profile_md: Callable[[dict[str, Any]], str],
) -> None:
    try:
        profile = load_profile_source(source_path)
    except Exception:
        return
    evidence_bundle = build_profile_evidence_bundle(
        payload,
        character=str(profile.get("name", "")).strip(),
        chunk_count=chunk_count,
    )
    evidence = dict(evidence_bundle.get("counts", {}) or {})
    profile["description_count"] = int(evidence["description_count"])
    profile["dialogue_count"] = int(evidence["dialogue_count"])
    profile["thought_count"] = int(evidence["thought_count"])
    profile["chunk_count"] = int(evidence["chunk_count"])
    profile["evidence"] = {
        "description_count": int(evidence["description_count"]),
        "dialogue_count": int(evidence["dialogue_count"]),
        "thought_count": int(evidence["thought_count"]),
        "chunk_count": int(evidence["chunk_count"]),
    }
    if not str(profile.get("evidence_source", "")).strip():
        profile["evidence_source"] = str(evidence["evidence_source"]).strip()
    rendered = render_profile_md(profile).strip()
    if rendered:
        source_path.write_text(rendered + "\n", encoding="utf-8")
        source_path.with_name(PERSONA_EVIDENCE_FILENAME).write_text(
            json.dumps(evidence_bundle, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )


def profile_evidence_from_payload(payload: dict[str, Any], *, chunk_count: int) -> dict[str, Any]:
    request = dict(payload.get("request", {}) or {})
    excerpt = str(request.get("excerpt", "")).strip()
    sentences = [item.strip() for item in split_sentences(excerpt) if item.strip()]
    if not sentences and excerpt:
        sentences = [item.strip() for item in excerpt.splitlines() if item.strip()]

    description_count = 0
    dialogue_count = 0
    thought_count = 0
    for sentence in sentences:
        if looks_like_thought_or_evaluation_sentence(sentence):
            thought_count += 1
        elif looks_like_dialogue_sentence(sentence):
            dialogue_count += 1
        else:
            description_count += 1

    excerpt_stages = dict(request.get("excerpt_stages", {}) or {})
    stage_refs: list[str] = []
    for stage_key in ("start", "mid", "end"):
        if str(excerpt_stages.get(stage_key, "")).strip():
            stage_refs.append(f"excerpt:{stage_key}")
    strategy = str((request.get("excerpt_focus", {}) or {}).get("strategy", "")).strip()
    if strategy:
        stage_refs.append(f"strategy:{strategy}")

    return {
        "description_count": description_count,
        "dialogue_count": dialogue_count,
        "thought_count": thought_count,
        "chunk_count": max(1, int(chunk_count or 1)),
        "evidence_source": "；".join(stage_refs),
    }


def build_profile_evidence_bundle(
    payload: dict[str, Any],
    *,
    character: str,
    chunk_count: int,
) -> dict[str, Any]:
    counts = profile_evidence_from_payload(payload, chunk_count=chunk_count)
    request = dict(payload.get("request", {}) or {})
    candidates: list[tuple[int, int, dict[str, Any]]] = []
    seen_quotes: set[str] = set()
    order = 0
    for stage, block in _evidence_blocks(request):
        for quote in _evidence_sentences(block):
            normalized_quote = re.sub(r"\s+", " ", quote).strip()
            if not normalized_quote or normalized_quote in seen_quotes:
                continue
            seen_quotes.add(normalized_quote)
            kind = _evidence_kind(normalized_quote)
            mentions_character = _mentions_character(normalized_quote, character)
            reference = {
                "id": f"evidence-{stage}-{order + 1}",
                "stage": stage,
                "stage_label": _STAGE_LABELS.get(stage, stage),
                "kind": kind,
                "kind_label": _KIND_LABELS[kind],
                "quote": normalized_quote,
                "source": f"excerpt:{stage}",
                "mentions_character": mentions_character,
            }
            candidates.append(
                (
                    _reference_priority(kind=kind, mentions_character=mentions_character),
                    order,
                    reference,
                )
            )
            order += 1

    selected = sorted(candidates, key=lambda item: (item[0], item[1]))[:_REFERENCE_LIMIT]
    selected.sort(key=lambda item: item[1])
    references = [item[2] for item in selected]
    return {
        "schema_version": PERSONA_EVIDENCE_SCHEMA_VERSION,
        "character": str(character or "").strip(),
        "counts": counts,
        "reference_count": len(references),
        "references": references,
    }


def _evidence_blocks(request: dict[str, Any]) -> list[tuple[str, str]]:
    excerpt_stages = dict(request.get("excerpt_stages", {}) or {})
    blocks = [
        (stage, str(excerpt_stages.get(stage, "")).strip())
        for stage in ("start", "mid", "end")
        if str(excerpt_stages.get(stage, "")).strip()
    ]
    if blocks:
        return blocks
    excerpt = str(request.get("excerpt", "")).strip()
    return [("excerpt", excerpt)] if excerpt else []


def _evidence_sentences(block: str) -> list[str]:
    sentences = [item.strip() for item in split_sentences(block) if item.strip()]
    if not sentences and block:
        sentences = [item.strip() for item in block.splitlines() if item.strip()]
    merged: list[str] = []
    for sentence in sentences:
        if merged and re.fullmatch(r"[”’」』》】）)\]]+[。！？!?…]*", sentence):
            merged[-1] += sentence
        else:
            merged.append(sentence)
    return merged


def _evidence_kind(text: str) -> str:
    if looks_like_thought_or_evaluation_sentence(text):
        return "thought"
    if looks_like_dialogue_sentence(text):
        return "dialogue"
    return "description"


def _reference_priority(*, kind: str, mentions_character: bool) -> int:
    if mentions_character and kind in {"dialogue", "thought"}:
        return 0
    if mentions_character:
        return 1
    if kind in {"dialogue", "thought"}:
        return 2
    return 3


def _mentions_character(text: str, character: str) -> bool:
    normalized_character = _normalize_match_text(character)
    if not normalized_character:
        return False
    tokens = [normalized_character]
    if len(normalized_character) >= 3:
        tokens.extend((normalized_character[1:], normalized_character[-2:]))
    normalized_text = _normalize_match_text(text)
    return any(len(token) >= 2 and token in normalized_text for token in tokens)


def _normalize_match_text(text: str) -> str:
    normalized = unicodedata.normalize("NFKC", str(text or "")).lower()
    return re.sub(r"[^\w\u3400-\u9fff]+", "", normalized)


def looks_like_dialogue_sentence(text: str) -> bool:
    sample = str(text or "").strip()
    if not sample:
        return False
    if any(token in sample for token in ('"', "“", "”", "「", "」")):
        return True
    return bool(re.search(r"(说道|笑道|问道|答道|道：|道:|喊道|喝道|骂道|低声道|轻声道)", sample))


def looks_like_thought_or_evaluation_sentence(text: str) -> bool:
    sample = str(text or "").strip()
    if not sample:
        return False
    return bool(
        re.search(
            r"(心想|心道|心里|想着|只觉|觉得|不禁|暗想|思忖|寻思|素来|向来|一向|生性|性子|为人|看似|其实|原是|本就)",
            sample,
        )
    )
