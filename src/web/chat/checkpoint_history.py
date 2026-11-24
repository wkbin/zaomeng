"""Store turn checkpoint histories in reusable, session-local chunks."""

from __future__ import annotations

import json
import re
from hashlib import sha256
from pathlib import Path
from typing import Any

from .io_utils import read_json, write_json

_CHUNK_SIZE = 32
_CHUNK_ID = re.compile(r"[0-9a-f]{64}")


def _chunk_id(entries: list[Any]) -> str:
    encoded = json.dumps(entries, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return sha256(encoded.encode("utf-8")).hexdigest()


def archive_checkpoint_history(session_dir: Path, checkpoint: dict[str, Any]) -> dict[str, Any]:
    history = checkpoint.get("history")
    if not isinstance(history, list) or not history:
        return checkpoint
    directory = session_dir / "checkpoint-history"
    chunk_ids = []
    for offset in range(0, len(history), _CHUNK_SIZE):
        entries = history[offset : offset + _CHUNK_SIZE]
        chunk_id = _chunk_id(entries)
        path = directory / f"{chunk_id}.json"
        if not path.is_file():
            write_json(path, {"entries": entries})
        chunk_ids.append(chunk_id)
    return {
        **{key: value for key, value in checkpoint.items() if key != "history"},
        "history_chunks": chunk_ids,
    }


def restore_checkpoint_history(session_dir: Path, checkpoint: dict[str, Any]) -> dict[str, Any]:
    if "history_chunks" not in checkpoint or "history" in checkpoint:
        return checkpoint
    chunks = checkpoint["history_chunks"]
    if not isinstance(chunks, list):
        raise ValueError("Invalid turn checkpoint history.")
    history = []
    for chunk_id in chunks:
        if not isinstance(chunk_id, str) or not _CHUNK_ID.fullmatch(chunk_id):
            raise ValueError("Invalid turn checkpoint history chunk id.")
        try:
            entries = read_json(session_dir / "checkpoint-history" / f"{chunk_id}.json").get("entries")
        except FileNotFoundError:
            raise ValueError("A turn checkpoint history chunk is missing.") from None
        if not isinstance(entries, list) or not 0 < len(entries) <= _CHUNK_SIZE or _chunk_id(entries) != chunk_id:
            raise ValueError("Invalid turn checkpoint history chunk.")
        history.extend(entries)
    return {**checkpoint, "history": history}
