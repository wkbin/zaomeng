#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

TEXT_ENCODINGS = (
    "utf-8-sig",
    "utf-8",
    "gb18030",
    "gbk",
    "utf-16",
    "utf-16-le",
    "utf-16-be",
)


def _decode_score(text: str) -> tuple[int, int, int, int]:
    if not text:
        return (-10_000, 0, 0, 0)
    replacement_count = text.count("\ufffd")
    null_count = text.count("\x00")
    cjk_count = sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")
    readable_count = sum(1 for ch in text if ch.isprintable() or ch in "\r\n\t")
    return (
        cjk_count * 4 + readable_count - replacement_count * 50 - null_count * 100,
        cjk_count,
        -replacement_count,
        -null_count,
    )


def _strip_html_tags(text: str) -> str:
    text = re.sub(r"<script[\s\S]*?</script>", "", text, flags=re.IGNORECASE)
    text = re.sub(r"<style[\s\S]*?</style>", "", text, flags=re.IGNORECASE)
    text = re.sub(r"<[^>]+>", "", text)
    return re.sub(r"\s+", " ", text).strip()


def _decode_text_bytes(raw: bytes) -> str:
    for preferred in ("utf-8-sig", "utf-8"):
        try:
            decoded = raw.decode(preferred)
        except UnicodeDecodeError:
            continue
        if "\ufffd" not in decoded and "\x00" not in decoded:
            return decoded

    last_error: UnicodeError | None = None
    best_text = ""
    best_score: tuple[int, int, int, int] | None = None
    for encoding in TEXT_ENCODINGS:
        try:
            decoded = raw.decode(encoding)
        except UnicodeDecodeError as exc:
            last_error = exc
            continue
        score = _decode_score(decoded)
        if best_score is None or score > best_score:
            best_text = decoded
            best_score = score
    if best_score is not None and best_score[0] > 0:
        return best_text
    if best_text:
        return best_text
    if last_error:
        raise UnicodeError("无法识别小说文本编码，请转换为 UTF-8 或 GB18030 后重试") from last_error
    raise UnicodeError("无法读取小说文本")


def load_novel_text(path: str | Path) -> str:
    novel_path = Path(path)
    if not novel_path.exists():
        raise FileNotFoundError(f"小说文件不存在: {novel_path}")

    suffix = novel_path.suffix.lower()
    if suffix == ".txt":
        return _decode_text_bytes(novel_path.read_bytes())
    if suffix == ".epub":
        return _load_epub(novel_path)
    raise ValueError(f"不支持的文件类型: {suffix}，仅支持 .txt / .epub")


def _load_epub(path: Path) -> str:
    try:
        from ebooklib import epub
    except ImportError as exc:
        raise ImportError("读取 .epub 需要安装 ebooklib") from exc

    book = epub.read_epub(str(path))
    chunks: list[str] = []
    for item in book.get_items():
        if item.get_type() == 9:
            html = item.get_content().decode("utf-8", errors="ignore")
            text = _strip_html_tags(html)
            if text:
                chunks.append(text)
    return "\n".join(chunks)


def split_sentences(text: str) -> list[str]:
    if not text:
        return []
    parts = re.split(r"(?<=[。！？!?])\s*", text)
    return [p.strip() for p in parts if p.strip()]


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
        load_novel_text(novel_path),
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
        load_novel_text(path),
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
