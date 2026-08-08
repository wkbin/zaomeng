from __future__ import annotations

from functools import wraps
import inspect
from pathlib import Path
import threading

from src.web.path_safety import resolve_storage_child, validate_storage_id


def with_session_lock(method):
    signature = inspect.signature(method)

    @wraps(method)
    def locked(self, *args, **kwargs):
        bound = signature.bind(self, *args, **kwargs)
        run_id = str(bound.arguments.get("run_id", "")).strip()
        if not run_id:
            run_manifest = dict(bound.arguments.get("run_manifest", {}) or {})
            run_id = str(run_manifest.get("run_id", "")).strip()
        session_id = str(bound.arguments.get("session_id", "")).strip()
        with self.session_lock(run_id, session_id):
            return method(self, *args, **kwargs)

    return locked


class SessionFileStore:
    def __init__(self, runs_root: str | Path) -> None:
        self.runs_root = Path(runs_root)
        self._locks_guard = threading.Lock()
        self._locks: dict[tuple[str, str], threading.RLock] = {}

    def sessions_root(self, run_id: str) -> Path:
        run_dir = resolve_storage_child(self.runs_root, run_id, field_name="run_id")
        return resolve_storage_child(run_dir, "dialogue", field_name="dialogue directory")

    def session_dir(self, run_id: str, session_id: str) -> Path:
        return resolve_storage_child(self.sessions_root(run_id), session_id, field_name="session_id")

    def session_file(self, run_id: str, session_id: str) -> Path:
        return self.session_dir(run_id, session_id) / "session.json"

    def lock(self, run_id: str, session_id: str) -> threading.RLock:
        safe_run_id = validate_storage_id(run_id, field_name="run_id")
        safe_session_id = validate_storage_id(session_id, field_name="session_id")
        key = (safe_run_id, safe_session_id)
        with self._locks_guard:
            lock = self._locks.get(key)
            if lock is None:
                lock = threading.RLock()
                self._locks[key] = lock
            return lock


__all__ = ["SessionFileStore", "with_session_lock"]
