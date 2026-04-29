#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import re
from pathlib import Path

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
) -> str:
    clean = str(text or "").strip()
    if not clean:
        return ""

    selected: list[str] = []
    total_chars = 0
    for sentence in split_sentences(clean):
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
    return clean[:max_chars].strip()


def load_prepared_novel_excerpt(
    novel_path: str | Path,
    *,
    max_sentences: int = 80,
    max_chars: int = 12_000,
) -> str:
    return prepare_novel_excerpt(
        load_novel_text(novel_path),
        max_sentences=max_sentences,
        max_chars=max_chars,
    )


def build_excerpt_payload(
    novel_path: str | Path,
    *,
    max_sentences: int = 80,
    max_chars: int = 12_000,
) -> dict[str, object]:
    path = Path(novel_path)
    excerpt = load_prepared_novel_excerpt(
        path,
        max_sentences=max_sentences,
        max_chars=max_chars,
    )
    return {
        "source_path": str(path),
        "source_name": path.name,
        "max_sentences": max_sentences,
        "max_chars": max_chars,
        "excerpt": excerpt,
    }
