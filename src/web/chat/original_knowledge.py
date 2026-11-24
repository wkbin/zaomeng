from __future__ import annotations

from copy import deepcopy
from hashlib import sha256
from pathlib import Path
import re
import threading
from typing import Any, Iterable

from src.utils.text_parser import load_novel_text
from src.web.chat.io_utils import read_json, write_json
from src.web.chat.text_utils import trim_summary_text
from src.web.path_safety import resolve_storage_child, validate_storage_id
from src.web.time_utils import utc_now


_SENTENCE_SPLIT = re.compile(r"(?<=[。！？!?；;\n])")
_WORD = re.compile(r"[A-Za-z0-9_]{2,}|[\u3400-\u9fff]{1,}")
_SECRET_MARKERS = ("秘密", "瞒着", "隐瞒", "只有", "不得告诉", "不能让", "不知情")
_PUBLIC_MARKERS = (
    "世界",
    "规则",
    "所有人",
    "任何人",
    "人们",
    "法律",
    "制度",
    "历史",
    "城市",
    "国家",
    "组织",
    "网络",
)
_KNOWLEDGE_VERBS = (
    "说",
    "问",
    "答",
    "告诉",
    "听见",
    "听到",
    "看见",
    "看到",
    "知道",
    "明白",
    "发现",
    "记得",
    "想起",
    "心想",
    "暗想",
    "意识到",
)


class OriginalKnowledgeStore:
    """Rebuildable source-passage index with per-character epistemic boundaries."""

    VERSION = 1
    CHUNK_CHAR_LIMIT = 900
    MAX_ENTRIES = 12000

    def __init__(self, runs_root: str | Path) -> None:
        self.runs_root = Path(runs_root)
        self._locks_guard = threading.Lock()
        self._locks: dict[str, threading.RLock] = {}

    def _lock(self, run_id: str) -> threading.RLock:
        safe_run_id = validate_storage_id(run_id, field_name="run_id")
        with self._locks_guard:
            return self._locks.setdefault(safe_run_id, threading.RLock())

    def _run_dir(self, run_id: str) -> Path:
        return resolve_storage_child(self.runs_root, run_id, field_name="run_id")

    def _path(self, run_id: str) -> Path:
        return self._run_dir(run_id) / "original_knowledge.json"

    @classmethod
    def _empty(cls) -> dict[str, Any]:
        return {
            "version": cls.VERSION,
            "source": {},
            "entries": [],
            "entry_count": 0,
            "updated_at": "",
        }

    def get(self, run_id: str) -> dict[str, Any]:
        with self._lock(run_id):
            path = self._path(run_id)
            if not path.is_file():
                return self._empty()
            payload = read_json(path)
            payload.setdefault("version", self.VERSION)
            payload.setdefault("source", {})
            payload.setdefault("entries", [])
            payload["entry_count"] = len(payload["entries"])
            return payload

    def ensure(
        self,
        run_manifest: dict[str, Any],
        *,
        character_names: Iterable[str] = (),
        force: bool = False,
    ) -> dict[str, Any]:
        run_id = str(run_manifest.get("run_id", "")).strip()
        validate_storage_id(run_id, field_name="run_id")
        source_path = self._source_path(run_id, run_manifest)
        source_stat = source_path.stat()
        with self._lock(run_id):
            current = self.get(run_id)
            source = dict(current.get("source", {}) or {})
            if (
                not force
                and current.get("entries")
                and int(source.get("size", -1) or -1) == source_stat.st_size
                and int(source.get("mtime_ns", -1) or -1) == source_stat.st_mtime_ns
                and int(current.get("version", 0) or 0) == self.VERSION
            ):
                return current
            raw_bytes = source_path.read_bytes()
            digest = sha256(raw_bytes).hexdigest()
            text = load_novel_text(str(source_path)).replace("\r\n", "\n").replace("\r", "\n")
            names = list(dict.fromkeys(str(name).strip() for name in character_names if str(name).strip()))
            entries = self._build_entries(text, source_path.name, names)
            self._apply_manual_boundaries(entries, current.get("entries", []))
            now = utc_now()
            payload = {
                "version": self.VERSION,
                "source": {
                    "name": source_path.name,
                    "sha256": digest,
                    "size": source_stat.st_size,
                    "mtime_ns": source_stat.st_mtime_ns,
                    "character_count": len(text),
                },
                "entries": entries,
                "entry_count": len(entries),
                "updated_at": now,
            }
            write_json(self._path(run_id), payload)
            return payload

    @staticmethod
    def _apply_manual_boundaries(
        entries: list[dict[str, Any]], old_entries: Iterable[Any]
    ) -> None:
        """Carry user-set boundaries across a rebuild only for identical passages."""

        overrides: dict[str, tuple[str, list[str]]] = {}
        for item in old_entries:
            if not isinstance(item, dict) or item.get("boundary_source") != "manual":
                continue
            passage_hash = sha256(
                str(item.get("text", "")).strip().encode("utf-8")
            ).hexdigest()
            visibility = str(item.get("visibility", "")).strip().lower()
            if visibility not in {"public", "scene", "private", "uncertain"}:
                continue
            knowers = [
                str(name).strip()
                for name in list(item.get("knowers", []) or [])
                if str(name).strip()
            ]
            overrides[passage_hash] = (visibility, list(dict.fromkeys(knowers)))

        for entry in entries:
            passage_hash = sha256(
                str(entry.get("text", "")).strip().encode("utf-8")
            ).hexdigest()
            override = overrides.get(passage_hash)
            if override is None:
                continue
            entry["visibility"], entry["knowers"] = override
            entry["boundary_source"] = "manual"

    def update_entry(
        self,
        run_id: str,
        entry_id: str,
        *,
        visibility: str,
        knowers: Iterable[str],
    ) -> dict[str, Any]:
        safe_entry_id = validate_storage_id(entry_id, field_name="entry_id")
        normalized_visibility = str(visibility or "").strip().lower()
        if normalized_visibility not in {"public", "scene", "private", "uncertain"}:
            raise ValueError("visibility must be public, scene, private, or uncertain.")
        with self._lock(run_id):
            payload = self.get(run_id)
            entries = list(payload.get("entries", []) or [])
            target = next(
                (item for item in entries if str(item.get("entry_id", "")) == safe_entry_id),
                None,
            )
            if target is None:
                raise FileNotFoundError(safe_entry_id)
            target["visibility"] = normalized_visibility
            target["knowers"] = list(
                dict.fromkeys(str(name).strip() for name in knowers if str(name).strip())
            )
            target["boundary_source"] = "manual"
            target["updated_at"] = utc_now()
            payload["entries"] = entries
            payload["updated_at"] = target["updated_at"]
            write_json(self._path(run_id), payload)
            return deepcopy(target)

    def search(
        self,
        run_manifest: dict[str, Any],
        *,
        query: str,
        participants: Iterable[str],
        active_participants: Iterable[str],
        scene_terms: Iterable[str] = (),
        limit: int = 6,
    ) -> list[dict[str, Any]]:
        query_text = " ".join(
            item for item in [str(query).strip(), *[str(term).strip() for term in scene_terms]] if item
        )
        if not query_text:
            return []
        query_tokens = self._tokens(query_text)
        character_names = [
            str(item.get("name", "")).strip()
            for item in list(run_manifest.get("artifact_index", {}).get("characters", []) or [])
            if str(item.get("name", "")).strip()
        ]
        payload = self.ensure(run_manifest, character_names=character_names)
        names = list(dict.fromkeys(str(name).strip() for name in participants if str(name).strip()))
        active = {str(name).strip() for name in active_participants if str(name).strip()}
        participant_names = set(names)
        mentioned_active = {name for name in active if name in query_text}
        scored: list[tuple[float, dict[str, Any]]] = []
        for entry in payload.get("entries", []) or []:
            text = str(entry.get("text", ""))
            normalized_text = text.casefold()
            overlap = {token for token in query_tokens if token in normalized_text}
            score = float(sum(1.0 + min(3, len(token)) * 0.15 for token in overlap))
            if query_text in text:
                score += 16.0
            if score > 0:
                mentioned = set(entry.get("characters", []) or [])
                score += len(mentioned & active) * 2.5
                score += len(mentioned & participant_names) * 0.75
                score += len(mentioned & mentioned_active) * 3.0
                scored.append((score, entry))
        scored.sort(key=lambda pair: (pair[0], -int(pair[1].get("ordinal", 0))), reverse=True)
        selected = scored[: max(1, min(int(limit or 6), 10))]
        return [self._retrieval_payload(entry, score, names) for score, entry in selected]

    def _source_path(self, run_id: str, manifest: dict[str, Any]) -> Path:
        run_dir = self._run_dir(run_id).resolve(strict=False)
        source = Path(str(manifest.get("novel_path", "")).strip()).resolve(strict=False)
        try:
            source.relative_to(run_dir)
        except ValueError as exc:
            raise ValueError("Original source path is outside the run directory.") from exc
        if not source.is_file():
            raise FileNotFoundError(source)
        return source

    @classmethod
    def _build_entries(
        cls, text: str, source_name: str, character_names: list[str]
    ) -> list[dict[str, Any]]:
        sentences = [item.strip() for item in _SENTENCE_SPLIT.split(text) if item.strip()]
        entries: list[dict[str, Any]] = []
        buffer: list[str] = []
        buffer_length = 0
        start_char = 0
        cursor = 0

        def flush() -> None:
            nonlocal buffer, buffer_length, start_char
            chunk = "".join(buffer).strip()
            if not chunk:
                return
            ordinal = len(entries) + 1
            mentioned = [name for name in character_names if name in chunk]
            visibility, knowers = cls._infer_boundary(chunk, mentioned)
            entries.append(
                {
                    "entry_id": f"src-{ordinal:05d}",
                    "ordinal": ordinal,
                    "title": f"{source_name} · 原文片段 {ordinal}",
                    "text": chunk,
                    "start_char": start_char,
                    "end_char": start_char + len(chunk),
                    "characters": mentioned,
                    "visibility": visibility,
                    "knowers": knowers,
                    "boundary_source": "automatic",
                    "epistemic_status": "explicit_source",
                }
            )

        for sentence in sentences:
            if buffer and buffer_length + len(sentence) > cls.CHUNK_CHAR_LIMIT:
                flush()
                overlap = buffer[-1:]
                buffer = list(overlap)
                buffer_length = sum(len(item) for item in buffer)
                start_char = max(0, cursor - buffer_length)
            if not buffer:
                start_char = cursor
            buffer.append(sentence)
            buffer_length += len(sentence)
            cursor += len(sentence)
            if len(entries) >= cls.MAX_ENTRIES:
                break
        if len(entries) < cls.MAX_ENTRIES:
            flush()
        return entries

    @staticmethod
    def _infer_boundary(text: str, mentioned: list[str]) -> tuple[str, list[str]]:
        explicit_knowers = [
            name
            for name in mentioned
            if re.search(
                rf"{re.escape(name)}.{{0,8}}(?:{'|'.join(_KNOWLEDGE_VERBS)})",
                text,
            )
        ]
        if any(marker in text for marker in _SECRET_MARKERS):
            if explicit_knowers:
                return "private", explicit_knowers
            return "uncertain", []
        if any(marker in text for marker in _PUBLIC_MARKERS):
            return "public", []
        if len(explicit_knowers) > 1:
            return "scene", explicit_knowers
        if explicit_knowers:
            return "private", explicit_knowers
        return "uncertain", []

    @classmethod
    def _tokens(cls, text: str) -> set[str]:
        normalized = str(text or "").casefold()
        tokens: set[str] = set()
        for match in _WORD.finditer(normalized):
            word = match.group(0)
            if re.fullmatch(r"[\u3400-\u9fff]+", word):
                tokens.update(word[index : index + 2] for index in range(max(0, len(word) - 1)))
                if len(word) <= 4:
                    tokens.add(word)
            else:
                tokens.add(word)
        return {token for token in tokens if token.strip()}

    @staticmethod
    def _retrieval_payload(
        entry: dict[str, Any], score: float, participants: list[str]
    ) -> dict[str, Any]:
        visibility = str(entry.get("visibility", "uncertain")).strip() or "uncertain"
        knowers = [str(name).strip() for name in entry.get("knowers", []) if str(name).strip()]
        if visibility == "public":
            allowed = list(participants)
        elif visibility in {"scene", "private"}:
            allowed = [name for name in participants if name in set(knowers)]
        else:
            allowed = []
        denied = [name for name in participants if name not in set(allowed)]
        return {
            "source_id": str(entry.get("entry_id", "")),
            "title": str(entry.get("title", "")),
            "excerpt": trim_summary_text(str(entry.get("text", "")).strip(), 760),
            "location": {
                "start_char": int(entry.get("start_char", 0) or 0),
                "end_char": int(entry.get("end_char", 0) or 0),
            },
            "score": round(float(score), 4),
            "visibility": visibility,
            "allowed_characters": allowed,
            "denied_characters": denied,
            "epistemic_status": str(entry.get("epistemic_status", "explicit_source")),
        }


__all__ = ["OriginalKnowledgeStore"]
