from __future__ import annotations

import hashlib
import json
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from src.web.chat.io_utils import read_json, write_json
from src.web.path_safety import resolve_storage_child
from src.web.time_utils import utc_now


class ReplyOperationConflict(ValueError):
    """Raised when an idempotency key is reused for a different request."""


class ReplyOperationStore:
    """Small, durable idempotency ledger stored beside a dialogue session.

    The model call deliberately happens outside the session JSON transaction. Keeping
    this ledger in a sidecar lets a client reconnect after losing an HTTP response and
    recover the already committed turn without appending the user's message twice.
    """

    def __init__(self, runs_root: str | Path) -> None:
        self.runs_root = Path(runs_root)
        self._locks_guard = threading.Lock()
        self._locks: dict[tuple[str, str, str], threading.RLock] = {}
        self._active: dict[tuple[str, str, str], threading.Event] = {}

    @staticmethod
    def normalize_operation_id(operation_id: str) -> str:
        value = str(operation_id or "").strip()
        if not value:
            return ""
        if len(value) > 128 or any(ord(char) < 33 or ord(char) == 127 for char in value):
            raise ValueError("operation_id must be 1-128 visible characters.")
        return value

    @staticmethod
    def request_fingerprint(
        *,
          message: str,
          message_kind: str,
          suppress_transcript_message: bool,
          include_inner_thoughts: bool = False,
      ) -> str:
        canonical = json.dumps(
            {
                "message": str(message),
                  "message_kind": str(message_kind or "dialogue").strip().lower(),
                  "suppress_transcript_message": bool(suppress_transcript_message),
                  "include_inner_thoughts": bool(include_inner_thoughts),
              },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    @contextmanager
    def lock(
        self, run_id: str, session_id: str, operation_id: str
    ) -> Iterator[None]:
        normalized = self.normalize_operation_id(operation_id)
        key = (str(run_id).strip(), str(session_id).strip(), normalized)
        with self._locks_guard:
            operation_lock = self._locks.get(key)
            if operation_lock is None:
                operation_lock = threading.RLock()
                self._locks[key] = operation_lock
        with operation_lock:
            yield

    def claim(
        self, run_id: str, session_id: str, operation_id: str
    ) -> threading.Event | None:
        key = (
            str(run_id).strip(),
            str(session_id).strip(),
            self.normalize_operation_id(operation_id),
        )
        with self._locks_guard:
            if key in self._active:
                return None
            cancellation = threading.Event()
            self._active[key] = cancellation
            return cancellation

    def release(
        self,
        run_id: str,
        session_id: str,
        operation_id: str,
        *,
        cancellation: threading.Event | None = None,
    ) -> None:
        key = (
            str(run_id).strip(),
            str(session_id).strip(),
            self.normalize_operation_id(operation_id),
        )
        with self._locks_guard:
            if cancellation is None or self._active.get(key) is cancellation:
                self._active.pop(key, None)

    def has_active_session_operation(
        self,
        run_id: str,
        session_id: str,
        *,
        excluding_operation_id: str = "",
    ) -> bool:
        session_key = (str(run_id).strip(), str(session_id).strip())
        excluded = (
            self.normalize_operation_id(excluding_operation_id)
            if excluding_operation_id
            else ""
        )
        with self._locks_guard:
            return any(
                key[:2] == session_key and (not excluded or key[2] != excluded)
                for key in self._active
            )

    def cancel_active_session_operations(
        self,
        run_id: str,
        session_id: str,
        *,
        message: str,
    ) -> list[str]:
        """Release active operations when their client explicitly abandons a turn."""

        session_key = (str(run_id).strip(), str(session_id).strip())
        with self._locks_guard:
            active_keys = [key for key in self._active if key[:2] == session_key]
            operation_ids = [key[2] for key in active_keys]
            for key in active_keys:
                self._active.pop(key).set()
        for operation_id in operation_ids:
            record = self.load(run_id, session_id, operation_id)
            if str(record.get("status", "")).strip() != "pending":
                continue
            self.mark_failed(
                run_id,
                session_id,
                operation_id,
                fingerprint=str(record.get("request_fingerprint", "")).strip(),
                turn_id=str(record.get("turn_id", "")).strip(),
                message=message,
                retryable=True,
            )
        return operation_ids

    def find_pending_owner(
        self,
        run_id: str,
        session_id: str,
        *,
        fingerprint: str,
        turn_id: str,
        excluding_operation_id: str,
    ) -> dict[str, Any]:
        """Return another operation that can own the session's pending turn."""

        excluded = self.normalize_operation_id(excluding_operation_id)
        operation_dir = self._session_dir(run_id, session_id) / "reply_operations"
        if not operation_dir.is_dir():
            return {}
        normalized_turn_id = str(turn_id or "").strip()
        for path in operation_dir.glob("*.json"):
            record = read_json(path)
            operation_id = str(record.get("operation_id", "")).strip()
            if not operation_id or operation_id == excluded:
                continue
            if str(record.get("status", "")).strip() != "pending":
                continue
            stored_fingerprint = str(record.get("request_fingerprint", "")).strip()
            if stored_fingerprint and stored_fingerprint != fingerprint:
                continue
            stored_turn_id = str(record.get("turn_id", "")).strip()
            if stored_turn_id and normalized_turn_id and stored_turn_id != normalized_turn_id:
                continue
            return record
        return {}

    def load(
        self,
        run_id: str,
        session_id: str,
        operation_id: str,
        *,
        fingerprint: str = "",
    ) -> dict[str, Any]:
        normalized = self.normalize_operation_id(operation_id)
        if not normalized:
            return {}
        path = self._operation_file(run_id, session_id, normalized)
        if not path.is_file():
            return {}
        record = read_json(path)
        if str(record.get("operation_id", "")).strip() != normalized:
            raise ReplyOperationConflict("Stored reply operation does not match its key.")
        stored_fingerprint = str(record.get("request_fingerprint", "")).strip()
        if fingerprint and stored_fingerprint and stored_fingerprint != fingerprint:
            raise ReplyOperationConflict(
                "This operation_id was already used for a different chat message."
            )
        return record

    def mark_pending(
        self,
        run_id: str,
        session_id: str,
        operation_id: str,
        *,
        fingerprint: str,
        turn_id: str,
    ) -> dict[str, Any]:
        previous = self.load(
            run_id,
            session_id,
            operation_id,
            fingerprint=fingerprint,
        )
        now = utc_now()
        previous_turn_id = str(previous.get("turn_id", "")).strip()
        requested_turn_id = str(turn_id or "").strip()
        continues_pending_attempt = (
            str(previous.get("status", "")).strip() == "pending"
            and (
                previous_turn_id == requested_turn_id
                or (not previous_turn_id and bool(requested_turn_id))
            )
        )
        previous_attempt = int(previous.get("attempt", 0) or 0)
        record = {
            "kind": "zaomeng_dialogue_reply_operation",
            "operation_id": self.normalize_operation_id(operation_id),
            "request_fingerprint": fingerprint,
            "status": "pending",
            "turn_id": requested_turn_id,
            "attempt": max(
                1,
                previous_attempt if continues_pending_attempt else previous_attempt + 1,
            ),
            "created_at": str(previous.get("created_at", "")).strip() or now,
            "updated_at": now,
            "completed_at": "",
            "failure": {},
        }
        write_json(self._operation_file(run_id, session_id, operation_id), record)
        return record

    def mark_completed(
        self,
        run_id: str,
        session_id: str,
        operation_id: str,
        *,
        fingerprint: str,
        turn_id: str,
        session_updated_at: str = "",
    ) -> dict[str, Any]:
        previous = self.load(
            run_id,
            session_id,
            operation_id,
            fingerprint=fingerprint,
        )
        now = utc_now()
        record = {
            **previous,
            "kind": "zaomeng_dialogue_reply_operation",
            "operation_id": self.normalize_operation_id(operation_id),
            "request_fingerprint": fingerprint,
            "status": "completed",
            "turn_id": str(turn_id or previous.get("turn_id", "")).strip(),
            "created_at": str(previous.get("created_at", "")).strip() or now,
            "updated_at": now,
            "completed_at": now,
            "session_updated_at": str(session_updated_at or "").strip(),
            "failure": {},
        }
        write_json(self._operation_file(run_id, session_id, operation_id), record)
        return record

    def mark_failed(
        self,
        run_id: str,
        session_id: str,
        operation_id: str,
        *,
        fingerprint: str,
        turn_id: str,
        message: str,
        retryable: bool = True,
    ) -> dict[str, Any]:
        previous = self.load(
            run_id,
            session_id,
            operation_id,
            fingerprint=fingerprint,
        )
        now = utc_now()
        record = {
            **previous,
            "kind": "zaomeng_dialogue_reply_operation",
            "operation_id": self.normalize_operation_id(operation_id),
            "request_fingerprint": fingerprint,
            "status": "failed",
            "turn_id": str(turn_id or previous.get("turn_id", "")).strip(),
            "created_at": str(previous.get("created_at", "")).strip() or now,
            "updated_at": now,
            "completed_at": "",
            "failure": {
                "message": str(message or "Reply generation failed.").strip(),
                "retryable": bool(retryable),
                "failed_at": now,
            },
        }
        write_json(self._operation_file(run_id, session_id, operation_id), record)
        return record

    def result_exists(
        self, run_id: str, session_id: str, record: dict[str, Any]
    ) -> bool:
        turn_id = str(record.get("turn_id", "")).strip()
        if not turn_id:
            return False
        session_dir = self._session_dir(run_id, session_id)
        return (session_dir / "turns" / f"{turn_id}.result.json").is_file()

    def _operation_file(
        self, run_id: str, session_id: str, operation_id: str
    ) -> Path:
        normalized = self.normalize_operation_id(operation_id)
        digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
        return self._session_dir(run_id, session_id) / "reply_operations" / f"{digest}.json"

    def _session_dir(self, run_id: str, session_id: str) -> Path:
        run_dir = resolve_storage_child(self.runs_root, run_id, field_name="run_id")
        dialogue_dir = resolve_storage_child(
            run_dir, "dialogue", field_name="dialogue directory"
        )
        return resolve_storage_child(dialogue_dir, session_id, field_name="session_id")


__all__ = [
    "ReplyOperationConflict",
    "ReplyOperationStore",
]
