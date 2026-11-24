from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import threading
from typing import Any
from uuid import uuid4

from src.web.chat.io_utils import read_json, write_json
from src.web.chat.text_utils import trim_summary_text
from src.web.path_safety import resolve_storage_child, validate_storage_id
from src.web.time_utils import utc_now


FACT_CATEGORIES = {
    "event",
    "location",
    "possession",
    "status",
    "commitment",
    "secret",
    "relationship",
    "setting",
}

_EVENT_CATEGORY = {
    "scene_transition": "location",
    "cast_enter": "location",
    "cast_exit": "location",
    "relationship_shift": "relationship",
    "time_change": "setting",
    "environment_change": "setting",
}


# 同一进程内可能有多个 WorldMemoryStore 指向同一个 runs_root（Workflow 与 DialogueService
# 各持一个实例）。锁必须按"文件路径"共享，否则两个实例各自的 read-modify-write 会互相覆盖。
_SHARED_LOCKS_GUARD = threading.Lock()
_SHARED_LOCKS: dict[str, threading.RLock] = {}


class WorldMemoryStore:
    VERSION = 1
    MAX_FACTS = 500
    MAX_TIMELINE_ITEMS = 500

    def __init__(self, runs_root: str | Path) -> None:
        self.runs_root = Path(runs_root)

    def _lock(self, run_id: str) -> threading.RLock:
        key = str(self._path(run_id))
        with _SHARED_LOCKS_GUARD:
            lock = _SHARED_LOCKS.get(key)
            if lock is None:
                lock = threading.RLock()
                _SHARED_LOCKS[key] = lock
            return lock

    def _path(self, run_id: str) -> Path:
        run_dir = resolve_storage_child(self.runs_root, run_id, field_name="run_id")
        return run_dir / "world_memory.json"

    @classmethod
    def _trim_facts(cls, facts: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """按上限裁剪，但不淘汰锁定/手工写入的事实（它们是用户的核心设定）。"""
        if len(facts) <= cls.MAX_FACTS:
            return facts

        def protected(item: dict[str, Any]) -> bool:
            return bool(item.get("locked")) or str(item.get("source", "")).strip() not in {"", "dialogue"}

        keep_protected = [item for item in facts if protected(item)][-cls.MAX_FACTS :]
        others = [item for item in facts if not protected(item)]
        room = max(0, cls.MAX_FACTS - len(keep_protected))
        trimmed = keep_protected + (others[-room:] if room else [])
        trimmed.sort(key=lambda item: str(item.get("created_at", "")).strip())
        return trimmed

    @classmethod
    def _empty(cls) -> dict[str, Any]:
        return {
            "version": cls.VERSION,
            "facts": [],
            "timeline": [],
            "updated_at": "",
        }

    def _read(self, run_id: str) -> dict[str, Any]:
        path = self._path(run_id)
        if not path.is_file():
            return self._empty()
        payload = read_json(path)
        payload.setdefault("version", self.VERSION)
        payload.setdefault("facts", [])
        payload.setdefault("timeline", [])
        payload.setdefault("updated_at", "")
        return payload

    def get(self, run_id: str) -> dict[str, Any]:
        with self._lock(run_id):
            return deepcopy(self._read(run_id))

    def save_fact(
        self,
        run_id: str,
        *,
        fields: dict[str, Any],
        fact_id: str = "",
    ) -> dict[str, Any]:
        summary = trim_summary_text(str(fields.get("summary", "")).strip(), 500)
        if not summary:
            raise ValueError("Fact summary must not be empty.")
        category = str(fields.get("category", "event")).strip().lower() or "event"
        if category not in FACT_CATEGORIES:
            raise ValueError("Unsupported world fact category.")
        characters = list(
            dict.fromkeys(
                str(item).strip()
                for item in list(fields.get("characters", []) or [])
                if str(item).strip()
            )
        )[:20]
        now = utc_now()
        with self._lock(run_id):
            payload = self._read(run_id)
            facts = [dict(item or {}) for item in payload.get("facts", []) if isinstance(item, dict)]
            existing: dict[str, Any] | None = None
            if fact_id:
                safe_fact_id = validate_storage_id(fact_id, field_name="fact_id")
                existing = next(
                    (item for item in facts if str(item.get("fact_id", "")).strip() == safe_fact_id),
                    None,
                )
                if existing is None:
                    raise FileNotFoundError(safe_fact_id)
            else:
                safe_fact_id = f"fact-{uuid4().hex[:12]}"
            created_at = str((existing or {}).get("created_at", "")).strip() or now
            source = str((existing or {}).get("source", "manual")).strip() or "manual"
            item = {
                **dict(existing or {}),
                "fact_id": safe_fact_id,
                "category": category,
                "summary": summary,
                "characters": characters,
                "location": trim_summary_text(str(fields.get("location", "")).strip(), 100),
                "time_hint": trim_summary_text(str(fields.get("time_hint", "")).strip(), 80),
                "source": source,
                "locked": bool(fields.get("locked", (existing or {}).get("locked", False))),
                "active": bool(fields.get("active", (existing or {}).get("active", True))),
                "created_at": created_at,
                "updated_at": now,
            }
            if existing is None:
                facts.append(item)
            else:
                facts[facts.index(existing)] = item
            payload["facts"] = self._trim_facts(facts)
            payload["updated_at"] = now
            write_json(self._path(run_id), payload)
            return deepcopy(item)

    def delete_fact(self, run_id: str, fact_id: str) -> dict[str, str]:
        safe_fact_id = validate_storage_id(fact_id, field_name="fact_id")
        with self._lock(run_id):
            payload = self._read(run_id)
            facts = [dict(item or {}) for item in payload.get("facts", []) if isinstance(item, dict)]
            remaining = [item for item in facts if str(item.get("fact_id", "")).strip() != safe_fact_id]
            if len(remaining) == len(facts):
                raise FileNotFoundError(safe_fact_id)
            payload["facts"] = remaining
            payload["updated_at"] = utc_now()
            write_json(self._path(run_id), payload)
        return {"status": "deleted", "fact_id": safe_fact_id}

    def sync_completed_turn(
        self,
        run_id: str,
        *,
        session_id: str,
        turn_id: str,
        title: str,
        participants: list[str],
        events: list[dict[str, Any]],
        location: str,
        time_hint: str,
        consistency_status: str,
        knowledge_ledger: list[dict[str, Any]],
        updated_at: str,
    ) -> dict[str, Any]:
        safe_session_id = validate_storage_id(session_id, field_name="session_id")
        safe_turn_id = validate_storage_id(turn_id, field_name="turn_id")
        now = str(updated_at).strip() or utc_now()
        turn_key = f"{safe_session_id}:{safe_turn_id}"
        clean_participants = list(
            dict.fromkeys(str(item).strip() for item in participants if str(item).strip())
        )
        with self._lock(run_id):
            payload = self._read(run_id)
            facts = [dict(item or {}) for item in payload.get("facts", []) if isinstance(item, dict)]
            by_source_key = {
                str(item.get("source_key", "")).strip(): index
                for index, item in enumerate(facts)
                if str(item.get("source_key", "")).strip()
            }
            for index, raw_event in enumerate(events):
                event = dict(raw_event or {})
                cue = trim_summary_text(str(event.get("cue", "")).strip(), 500)
                if not cue:
                    continue
                source_key = f"{turn_key}:event:{index}"
                characters = list(
                    dict.fromkeys(
                        name
                        for name in (
                            str(event.get("actor", "")).strip(),
                            str(event.get("target", "")).strip(),
                        )
                        if name
                    )
                )
                item = {
                    "fact_id": f"fact-{uuid4().hex[:12]}",
                    "category": _EVENT_CATEGORY.get(str(event.get("kind", "")).strip(), "event"),
                    "summary": cue,
                    "characters": characters,
                    "location": trim_summary_text(
                        str(event.get("location_hint", "") or location).strip(), 100
                    ),
                    "time_hint": trim_summary_text(
                        str(event.get("time_hint", "") or time_hint).strip(), 80
                    ),
                    "source_session_id": safe_session_id,
                    "source_turn_id": safe_turn_id,
                    "source_key": source_key,
                    "source": "dialogue",
                    "locked": False,
                    "active": True,
                    "created_at": now,
                    "updated_at": now,
                }
                existing_index = by_source_key.get(source_key)
                if existing_index is None:
                    by_source_key[source_key] = len(facts)
                    facts.append(item)
                elif not bool(facts[existing_index].get("locked", False)):
                    item["fact_id"] = facts[existing_index].get("fact_id", item["fact_id"])
                    item["created_at"] = facts[existing_index].get("created_at", now)
                    facts[existing_index] = item

            for raw_secret in knowledge_ledger:
                secret = dict(raw_secret or {})
                summary = trim_summary_text(
                    str(secret.get("secret", "") or secret.get("summary", "")).strip(), 500
                )
                if not summary:
                    continue
                source_key = f"knowledge:{summary.casefold()}"
                if source_key in by_source_key:
                    continue
                knowers = [
                    str(item).strip()
                    for item in list(secret.get("knowers", []) or [])
                    if str(item).strip()
                ]
                facts.append(
                    {
                        "fact_id": f"fact-{uuid4().hex[:12]}",
                        "category": "secret",
                        "summary": summary,
                        "characters": list(dict.fromkeys(knowers)),
                        "location": "",
                        "time_hint": "",
                        "source_session_id": safe_session_id,
                        "source_turn_id": safe_turn_id,
                        "source_key": source_key,
                        "source": "dialogue",
                        "locked": False,
                        "active": True,
                        "created_at": now,
                        "updated_at": now,
                    }
                )
                by_source_key[source_key] = len(facts) - 1

            timeline = [
                dict(item or {})
                for item in payload.get("timeline", [])
                if isinstance(item, dict) and str(item.get("turn_key", "")).strip() != turn_key
            ]
            timeline.append(
                {
                    "timeline_id": f"timeline-{uuid4().hex[:12]}",
                    "turn_key": turn_key,
                    "source_session_id": safe_session_id,
                    "source_turn_id": safe_turn_id,
                    "title": trim_summary_text(str(title).strip(), 160) or "剧情推进",
                    "participants": clean_participants,
                    "event_types": list(
                        dict.fromkeys(
                            str(item.get("kind", "")).strip()
                            for item in events
                            if str(item.get("kind", "")).strip()
                        )
                    ) or ["dialogue"],
                    "location": trim_summary_text(str(location).strip(), 100),
                    "time_hint": trim_summary_text(str(time_hint).strip(), 80),
                    "consistency_status": str(consistency_status).strip() or "pass",
                    "updated_at": now,
                }
            )
            payload["facts"] = self._trim_facts(facts)
            payload["timeline"] = sorted(
                timeline, key=lambda item: str(item.get("updated_at", "")).strip()
            )[-self.MAX_TIMELINE_ITEMS :]
            payload["updated_at"] = now
            write_json(self._path(run_id), payload)
            return deepcopy(payload)

    def relevant_facts(
        self,
        run_id: str,
        *,
        participants: list[str],
        message: str,
        limit: int = 18,
    ) -> list[dict[str, Any]]:
        payload = self.get(run_id)
        names = {str(item).strip() for item in participants if str(item).strip()}
        query = str(message).casefold()
        facts = [
            dict(item or {})
            for item in payload.get("facts", [])
            if isinstance(item, dict) and bool(item.get("active", True))
        ]

        def score(item: dict[str, Any]) -> tuple[int, str]:
            item_names = {str(value).strip() for value in item.get("characters", []) if str(value).strip()}
            summary = str(item.get("summary", "")).casefold()
            relevance = 100 if bool(item.get("locked", False)) else 0
            relevance += 20 * len(names & item_names)
            relevance += 10 if query and any(token in query for token in item_names) else 0
            relevance += 4 if query and summary and summary in query else 0
            return relevance, str(item.get("updated_at", "")).strip()

        ranked = sorted(facts, key=score, reverse=True)
        relevant = [item for item in ranked if score(item)[0] > 0]
        if len(relevant) < min(6, limit):
            seen = {str(item.get("fact_id", "")) for item in relevant}
            relevant.extend(item for item in ranked if str(item.get("fact_id", "")) not in seen)
        return relevant[: max(1, min(limit, 30))]


__all__ = ["FACT_CATEGORIES", "WorldMemoryStore"]
