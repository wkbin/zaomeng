from __future__ import annotations

import os
import tempfile
from pathlib import Path
from threading import RLock


class ProtectedSecretStore:
    """Small local secret store with restrictive filesystem permissions."""

    def __init__(self, root: str | Path) -> None:
        self.root = Path(root)

    def read(self, name: str) -> str:
        path = self._path(name)
        if not path.exists():
            return ""
        return path.read_text(encoding="utf-8").strip()

    def write(self, name: str, value: str) -> None:
        normalized = str(value or "").strip()
        if not normalized:
            return
        self.root.mkdir(parents=True, exist_ok=True)
        self._restrict_permissions(self.root, 0o700)
        path = self._path(name)
        temp_name = ""
        try:
            with tempfile.NamedTemporaryFile(
                "w",
                encoding="utf-8",
                dir=self.root,
                prefix=f".{path.name}.",
                suffix=".tmp",
                delete=False,
            ) as temp_file:
                temp_name = temp_file.name
                temp_file.write(normalized + "\n")
                temp_file.flush()
                os.fsync(temp_file.fileno())
            self._restrict_permissions(Path(temp_name), 0o600)
            os.replace(temp_name, path)
            self._restrict_permissions(path, 0o600)
        finally:
            if temp_name:
                temp_path = Path(temp_name)
                if temp_path.exists():
                    temp_path.unlink()

    def delete(self, name: str) -> None:
        path = self._path(name)
        try:
            path.unlink()
        except FileNotFoundError:
            return

    def _path(self, name: str) -> Path:
        normalized = str(name or "").strip()
        if not normalized or not normalized.replace("_", "").replace("-", "").isalnum():
            raise ValueError("Invalid secret name.")
        return self.root / normalized

    @staticmethod
    def _restrict_permissions(path: Path, mode: int) -> None:
        try:
            path.chmod(mode)
        except OSError:
            # Permission narrowing is best effort on filesystems without POSIX modes.
            pass


class InMemorySecretStore:
    """Process-local secret store used by embedded clients with a native vault."""

    def __init__(self, values: dict[str, str] | None = None) -> None:
        self._lock = RLock()
        self._values = {
            str(name).strip(): str(value).strip()
            for name, value in dict(values or {}).items()
            if str(name).strip() and str(value).strip()
        }

    def read(self, name: str) -> str:
        with self._lock:
            return self._values.get(str(name or "").strip(), "")

    def write(self, name: str, value: str) -> None:
        normalized_name = str(name or "").strip()
        normalized_value = str(value or "").strip()
        if not normalized_name or not normalized_value:
            return
        with self._lock:
            self._values[normalized_name] = normalized_value

    def delete(self, name: str) -> None:
        with self._lock:
            self._values.pop(str(name or "").strip(), None)


__all__ = ["InMemorySecretStore", "ProtectedSecretStore"]
