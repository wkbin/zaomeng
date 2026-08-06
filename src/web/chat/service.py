from __future__ import annotations

import random
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Protocol
from uuid import uuid4

from src.core.config import Config
from src.core.path_provider import PathProvider
from src.core.session_store import MarkdownSessionStore
from src.web.manifest.compat import (
    normalized_parts,
    relative_candidates,
    relative_to_run_dir,
)
import src.web.chat.consistency as _consistency
import src.web.chat.chapter_outline as _chapter_outline
import src.web.chat.character_arc as _character_arc
import src.web.chat.event_signals as _event_signals
from src.web.chat.cache_stats import (
    empty_generation_cache_stats,
    record_generation_cache_observation,
)
from src.web.chat.io_utils import read_json, write_json
import src.web.chat.memory_summary as _memory_summary
import src.web.chat.persona_context as _persona_context
import src.web.chat.relation_excerpt as _relation_excerpt
import src.web.chat.prompt_rules as _prompt_rules
import src.web.chat.relation_state as _relation_state
import src.web.chat.runtime_overview as _runtime_overview
import src.web.chat.scene_progress as _scene_progress
import src.web.chat.scene_signals as _scene_signals
import src.web.chat.speaker_balance as _speaker_balance
import src.web.chat.story_recap as _story_recap
from src.web.chat.session_storage import SessionFileStore, with_session_lock
from src.web.chat.original_knowledge import OriginalKnowledgeStore
from src.web.chat.world_memory import WorldMemoryStore
import src.web.chat.session_views as _session_views
import src.web.chat.state_utils as _state_utils
import src.web.chat.text_utils as _text_utils
import src.web.chat.turn_memory as _turn_memory
from src.web.artifacts.ingest import load_relations_source
from src.web.path_safety import InvalidStorageIdentifier, validate_storage_id
from src.web.time_utils import utc_now as _utc_now
from src.web.persona_avatars import avatar_path, avatar_version


class _SessionStateOwner(Protocol):
    def _ensure_session_state(self, session: dict[str, Any]) -> dict[str, Any]: ...


_SessionStateGetter = Callable[[dict[str, Any]], dict[str, Any]]
_SessionStateSetter = Callable[
    [dict[str, Any], dict[str, Any] | None],
    None,
]
_SessionGetterMethod = Callable[
    [_SessionStateOwner, dict[str, Any]],
    dict[str, Any],
]
_SessionSetterMethod = Callable[
    [_SessionStateOwner, dict[str, Any], dict[str, Any] | None],
    None,
]


def _session_state_accessors(
    name: str,
    getter: _SessionStateGetter,
    setter: _SessionStateSetter,
) -> tuple[_SessionGetterMethod, _SessionSetterMethod]:
    """Build a named getter/setter pair over the canonical session state."""

    def get_session_state(
        self: _SessionStateOwner, session: dict[str, Any]
    ) -> dict[str, Any]:
        return getter(self._ensure_session_state(session))

    def set_session_state(
        self: _SessionStateOwner,
        session: dict[str, Any],
        payload: dict[str, Any] | None,
    ) -> None:
        setter(self._ensure_session_state(session), payload)

    get_session_state.__name__ = f"_session_{name}"
    set_session_state.__name__ = f"_set_session_{name}"
    return get_session_state, set_session_state


class DialogueService:
    SESSION_STATE_VERSION = 1
    PENDING_TURN_STALE_SECONDS = 30 * 60

    def __init__(
        self,
        runs_root: str | Path,
        *,
        memory_store_resolver: Callable[[str], MarkdownSessionStore] | None = None,
    ) -> None:
        self._session_files = SessionFileStore(runs_root)
        self.runs_root = self._session_files.runs_root
        self._memory_store_resolver = memory_store_resolver
        self._memory_stores: dict[str, MarkdownSessionStore] = {}
        self._world_memory = WorldMemoryStore(self.runs_root)
        self._original_knowledge = OriginalKnowledgeStore(self.runs_root)

    def get_world_memory(self, run_id: str) -> dict[str, Any]:
        return self._world_memory.get(run_id)

    def save_world_fact(self, run_id: str, *, fields: dict[str, Any], fact_id: str = "") -> dict[str, Any]:
        return self._world_memory.save_fact(run_id, fields=fields, fact_id=fact_id)

    def delete_world_fact(self, run_id: str, fact_id: str) -> dict[str, str]:
        return self._world_memory.delete_fact(run_id, fact_id)

    def get_original_knowledge(self, run_id: str) -> dict[str, Any]:
        return self._original_knowledge.get(run_id)

    def rebuild_original_knowledge(
        self, run_manifest: dict[str, Any]
    ) -> dict[str, Any]:
        names = [item.get("name", "") for item in self._character_index(run_manifest)]
        return self._original_knowledge.ensure(
            run_manifest, character_names=names, force=True
        )

    def search_original_knowledge(
        self,
        run_manifest: dict[str, Any],
        *,
        query: str,
        participants: list[str],
        limit: int = 6,
    ) -> list[dict[str, Any]]:
        return self._original_knowledge.search(
            run_manifest,
            query=query,
            participants=participants,
            active_participants=participants,
            limit=limit,
        )

    def update_original_knowledge_boundary(
        self,
        run_id: str,
        entry_id: str,
        *,
        visibility: str,
        knowers: list[str],
    ) -> dict[str, Any]:
        return self._original_knowledge.update_entry(
            run_id,
            entry_id,
            visibility=visibility,
            knowers=knowers,
        )

    @classmethod
    def _empty_session_state(cls) -> dict[str, Any]:
        return _state_utils.empty_session_state(cls.SESSION_STATE_VERSION)

    def _ensure_session_state(self, session: dict[str, Any]) -> dict[str, Any]:
        return _state_utils.ensure_session_state(
            session, version=self.SESSION_STATE_VERSION
        )

    def _session_scene_progress(self, session: dict[str, Any]) -> dict[str, Any]:
        state = self._ensure_session_state(session)
        return _state_utils.session_scene_progress(state)

    def _set_session_scene_progress(
        self, session: dict[str, Any], scene_progress: dict[str, Any] | None
    ) -> None:
        state = self._ensure_session_state(session)
        payload = dict(scene_progress or {})
        updated_at = str(payload.get("updated_at", "")).strip() or _utc_now()
        _state_utils.set_session_scene_progress(state, payload, updated_at=updated_at)
        self._sync_character_runtime_cards(session, payload, updated_at=updated_at)

    _session_relation_matrix, _set_session_relation_matrix = (
        _session_state_accessors(
            "relation_matrix",
            _state_utils.relation_matrix,
            _state_utils.set_relation_matrix,
        )
    )
    _session_relation_delta, _set_session_relation_delta = _session_state_accessors(
        "relation_delta",
        _state_utils.relation_delta,
        _state_utils.set_relation_delta,
    )
    _session_character_snapshots, _set_session_character_snapshots = (
        _session_state_accessors(
            "character_snapshots",
            _state_utils.character_snapshots,
            _state_utils.set_character_snapshots,
        )
    )

    def _sync_character_runtime_cards(
        self,
        session: dict[str, Any],
        scene_progress: dict[str, Any] | None,
        *,
        updated_at: str,
    ) -> None:
        state = self._ensure_session_state(session)
        snapshots = dict(state.get("characters", {}).get("snapshots", {}) or {})
        progress = dict(scene_progress or {})
        participants = [
            str(item).strip()
            for item in list(session.get("participants", []) or [])
            if str(item).strip()
        ]
        present = {
            str(item).strip()
            for item in list(progress.get("present_participants", []) or [])
            if str(item).strip()
        }
        location = str(progress.get("location", "")).strip()
        time_hint = str(progress.get("time_hint", "")).strip()
        for name in participants:
            current = dict(snapshots.get(name, {}) or {})
            current["present_state"] = "onstage" if name in present else "offstage"
            if location:
                current["scene_location"] = location
            if time_hint:
                current["time_hint"] = time_hint
            current["updated_at"] = updated_at
            snapshots[name] = current
        state.setdefault("characters", {})["snapshots"] = snapshots

    _session_event_signals, _set_session_event_signals = _session_state_accessors(
        "event_signals",
        _state_utils.event_signals,
        _state_utils.set_event_signals,
    )
    _session_memory_summary_state, _set_session_memory_summary_state = (
        _session_state_accessors(
            "memory_summary_state",
            _state_utils.memory_summary,
            _state_utils.set_memory_summary,
        )
    )

    def list_sessions(self, run_id: str) -> list[dict[str, Any]]:
        root = self._sessions_root(run_id)
        items: list[dict[str, Any]] = []
        if not root.exists():
            return items
        for path in sorted(root.glob("*/session.json"), reverse=True):
            payload = self._read_json(path)
            items.append(self._serialize_session(run_id, payload))
        items.sort(key=lambda item: item.get("updated_at", ""), reverse=True)
        return items

    def _branch_family_payloads(
        self, run_id: str, session_id: str
    ) -> list[dict[str, Any]]:
        """Return the raw sessions connected to ``session_id`` as one branch tree."""

        root = self._sessions_root(run_id)
        payloads: dict[str, dict[str, Any]] = {}
        if not root.exists():
            return []
        for path in root.glob("*/session.json"):
            item = self._read_json(path)
            item_id = str(item.get("session_id", "")).strip()
            if item_id:
                payloads[item_id] = item
        if session_id not in payloads:
            return []

        parents = {
            item_id: str(dict(item.get("branch_origin", {}) or {}).get("session_id", "")).strip()
            for item_id, item in payloads.items()
        }
        family_ids = {session_id}
        cursor = session_id
        while parents.get(cursor) and parents[cursor] in payloads:
            cursor = parents[cursor]
            if cursor in family_ids:
                break
            family_ids.add(cursor)
        changed = True
        while changed:
            changed = False
            for item_id, parent_id in parents.items():
                if parent_id in family_ids and item_id not in family_ids:
                    family_ids.add(item_id)
                    changed = True
        return [payloads[item_id] for item_id in family_ids]

    @staticmethod
    def _branch_display_label(session: dict[str, Any]) -> str:
        meta = dict(session.get("branch_meta", {}) or {})
        explicit = str(meta.get("label", "")).strip()
        if explicit:
            return explicit
        origin = dict(session.get("branch_origin", {}) or {})
        origin_label = str(
            origin.get("event_title", "") or origin.get("scene_title", "")
        ).strip()
        if origin_label:
            return f"分支：{origin_label}"
        scene_title = str(dict(session.get("scene_card", {}) or {}).get("title", "")).strip()
        return scene_title or "主剧情"

    def _turn_file(
        self,
        run_id: str,
        session_id: str,
        turn_id: str,
        artifact: str,
    ) -> Path:
        safe_turn_id = validate_storage_id(turn_id, field_name="turn_id")
        if artifact not in {"payload", "result"}:
            raise ValueError("Unsupported turn artifact.")
        return (
            self._session_dir(run_id, session_id)
            / "turns"
            / f"{safe_turn_id}.{artifact}.json"
        )

    def _read_pending_turn_payload(
        self,
        run_id: str,
        session_id: str,
        pending: dict[str, Any],
    ) -> dict[str, Any]:
        turn_id = validate_storage_id(
            str(pending.get("turn_id", "")).strip(), field_name="turn_id"
        )
        turn_dir = self._session_dir(run_id, session_id) / "turns"
        canonical_path = self._turn_file(
            run_id, session_id, turn_id, "payload"
        )
        if canonical_path.is_file():
            return self._read_json(canonical_path)
        stored_path_text = str(pending.get("payload_path", "")).strip()
        if stored_path_text:
            stored_path = Path(stored_path_text)
            try:
                stored_is_local = stored_path.resolve().is_relative_to(
                    turn_dir.resolve()
                )
            except (OSError, RuntimeError, ValueError):
                stored_is_local = False
            if stored_is_local and stored_path.is_file():
                return self._read_json(stored_path)
        return {}

    def _branch_relation_changes(
        self, baseline: dict[str, Any], candidate: dict[str, Any]
    ) -> list[dict[str, Any]]:
        baseline_matrix = self._merged_relation_matrix(
            baseline, list(baseline.get("participants", []) or [])
        )
        candidate_matrix = self._merged_relation_matrix(
            candidate, list(candidate.get("participants", []) or [])
        )
        changes: list[dict[str, Any]] = []
        for pair_key in sorted(set(baseline_matrix) | set(candidate_matrix)):
            before = dict(baseline_matrix.get(pair_key, {}) or {})
            after = dict(candidate_matrix.get(pair_key, {}) or {})
            for metric in ("trust", "affection", "hostility", "ambiguity"):
                old_value = int(before.get(metric, 0) or 0)
                new_value = int(after.get(metric, 0) or 0)
                if old_value == new_value:
                    continue
                changes.append(
                    {
                        "pair_key": pair_key,
                        "metric": metric,
                        "before": old_value,
                        "after": new_value,
                        "delta": new_value - old_value,
                    }
                )
        return changes

    def _build_branch_graph(
        self,
        run_id: str,
        current: dict[str, Any],
        *,
        current_records: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        current_id = str(current.get("session_id", "")).strip()
        family = self._branch_family_payloads(run_id, current_id)
        nodes: list[dict[str, Any]] = []
        for item in family:
            item_id = str(item.get("session_id", "")).strip()
            origin = dict(item.get("branch_origin", {}) or {})
            meta = dict(item.get("branch_meta", {}) or {})
            nodes.append(
                {
                    "session_id": item_id,
                    "parent_session_id": str(origin.get("session_id", "")).strip(),
                    "label": self._branch_display_label(item),
                    "is_current": item_id == current_id,
                    "is_mainline": bool(meta.get("is_mainline", False)),
                    "origin_kind": str(origin.get("kind", "") or "root").strip(),
                    "origin_title": str(
                        origin.get("event_title", "") or origin.get("scene_title", "")
                    ).strip(),
                    "updated_at": str(item.get("updated_at", "")).strip(),
                    "event_count": len(
                        self._serialize_event_timeline(
                            run_id,
                            item,
                            records=(
                                current_records if item_id == current_id else None
                            ),
                        )
                    ),
                    "relation_changes": self._branch_relation_changes(current, item),
                }
            )
        nodes.sort(
            key=lambda item: (
                0 if bool(item.get("is_mainline")) else 1,
                str(item.get("updated_at", "")),
                str(item.get("session_id", "")),
            )
        )
        return {"current_session_id": current_id, "nodes": nodes}

    def _serialize_character_arcs(
        self,
        run_id: str,
        session: dict[str, Any],
        *,
        records: list[dict[str, Any]] | None = None,
    ) -> list[dict[str, Any]]:
        return _character_arc.build_character_arcs(
            list(session.get("participants", []) or []),
            (
                records
                if records is not None
                else self._completed_turn_records(
                    run_id, str(session.get("session_id", "")).strip()
                )
            ),
            inherited_arcs=list(session.get("inherited_character_arcs", []) or []),
        )

    @with_session_lock
    def update_branch_metadata(
        self,
        run_id: str,
        session_id: str,
        *,
        label: str | None = None,
        is_mainline: bool | None = None,
        locked_event_ids: list[str] | None = None,
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        meta = dict(session.get("branch_meta", {}) or {})
        if label is not None:
            meta["label"] = _text_utils.trim_summary_text(str(label).strip(), 80)
        if locked_event_ids is not None:
            available_ids = {
                str(item.get("turn_id", "")).strip()
                for item in self._serialize_event_timeline(run_id, session)
                if str(item.get("turn_id", "")).strip()
            }
            normalized_ids = list(
                dict.fromkeys(
                    str(item).strip() for item in locked_event_ids if str(item).strip()
                )
            )
            if len(normalized_ids) > 100:
                raise ValueError("最多锁定 100 个主线事件。")
            if any(item not in available_ids for item in normalized_ids):
                raise ValueError("要锁定的剧情事件不在当前分支中。")
            meta["locked_event_ids"] = normalized_ids
        if is_mainline is not None:
            meta["is_mainline"] = bool(is_mainline)
            if is_mainline:
                for relative in self._branch_family_payloads(run_id, session_id):
                    relative_id = str(relative.get("session_id", "")).strip()
                    if not relative_id or relative_id == session_id:
                        continue
                    relative_meta = dict(relative.get("branch_meta", {}) or {})
                    if not relative_meta.get("is_mainline"):
                        continue
                    relative_meta["is_mainline"] = False
                    relative["branch_meta"] = relative_meta
                    self._write_json(self._session_file(run_id, relative_id), relative)
        session["branch_meta"] = meta
        session["updated_at"] = _utc_now()
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    def create_session(
        self,
        run_manifest: dict[str, Any],
        *,
        mode: str,
        participants: list[str],
        controlled_character: str = "",
        scene_profile: dict[str, str] | None = None,
        self_profile: dict[str, str] | None = None,
        carried_memory_summary: dict[str, str] | None = None,
        branch_origin: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        run_id = str(run_manifest.get("run_id", "")).strip()
        novel_id = str(run_manifest.get("novel_id", "")).strip()
        available = self._character_index(run_manifest)
        available_names = [item["name"] for item in available]
        selected = [name for name in participants if name in available_names]
        if not selected:
            selected = available_names
        if not selected:
            raise ValueError("No persona bundles available for dialogue.")
        if mode not in {"act", "insert", "observe"}:
            raise ValueError("Unsupported dialogue mode.")
        if mode == "act" and controlled_character not in selected:
            raise ValueError(
                "Controlled character must be one of the selected participants."
            )

        session_id = f"dlg-{uuid4().hex[:10]}"
        root = self._session_dir(run_id, session_id)
        root.mkdir(parents=True, exist_ok=True)
        payload = {
            "kind": "zaomeng_dialogue_session",
            "session_id": session_id,
            "run_id": run_id,
            "novel_id": novel_id,
            "mode": mode,
            "participants": selected,
            "controlled_character": controlled_character if mode == "act" else "",
            "scene_card": dict(scene_profile or {}),
            "scene_card_id": str(
                (scene_profile or {}).get("scene_card_id", "")
            ).strip(),
            "scene_history": [],
            "self_insert": dict(self_profile or {}) if mode == "insert" else {},
            "self_card_id": (
                str((self_profile or {}).get("self_card_id", "")).strip()
                if mode == "insert"
                else ""
            ),
            "carried_memory_summary": dict(carried_memory_summary or {}),
            "branch_origin": dict(branch_origin or {}),
            "branch_meta": {
                "label": "",
                "is_mainline": not bool(branch_origin),
                "locked_event_ids": [],
            },
            "relation_locks": {},
            "memory_ledger": [],
            "latest_context_usage": {},
            "history": [],
            "pending_turn": {},
            "aborted_turns": [],
            "generation_cache_stats": empty_generation_cache_stats(),
            "state": self._empty_session_state(),
            "created_at": _utc_now(),
            "updated_at": _utc_now(),
            "status": "ready",
        }
        self._set_session_relation_matrix(
            payload, self._seed_relation_matrix(run_manifest, selected)
        )
        if dict(scene_profile or {}):
            initial_summary = self._build_session_memory_summary(run_id, payload, [])
            payload["scene_history"] = [
                self._build_scene_history_entry(
                    scene_profile or {},
                    transition_message="",
                    memory_summary=initial_summary,
                )
            ]
        self._set_session_scene_progress(
            payload, self._derive_scene_progress_state(payload, [])
        )
        self._write_json(root / "session.json", payload)
        if carried_memory_summary:
            session_store = self._resolve_memory_store(run_id)
            if session_store is not None:
                session_store.append_long_term_memory(
                    session_id,
                    _memory_summary.branch_memory_seed_text(carried_memory_summary),
                    metadata={
                        "run_id": run_id,
                        "kind": "branch_summary",
                        "speaker": "分支摘要",
                        "target": "",
                        "ts": _utc_now(),
                    },
                )
        return self._serialize_session(run_id, payload)

    @with_session_lock
    def get_session(self, run_id: str, session_id: str) -> dict[str, Any]:
        payload = self._read_json(self._session_file(run_id, session_id))
        return self._serialize_session(run_id, payload)

    @with_session_lock
    def set_plugin_enhancer_state(
        self,
        run_id: str,
        session_id: str,
        enhancer_key: str,
        enabled: bool,
    ) -> dict[str, Any]:
        normalized_key = str(enhancer_key or "").strip()
        if not normalized_key or len(normalized_key) > 200:
            raise ValueError("Invalid generation enhancer state key.")
        session = self._read_json(self._session_file(run_id, session_id))
        states = dict(session.get("plugin_enhancer_states", {}) or {})
        states[normalized_key] = bool(enabled)
        session["plugin_enhancer_states"] = states
        session["updated_at"] = _utc_now()
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def add_temporary_npc(
        self,
        run_id: str,
        session_id: str,
        npc: dict[str, Any],
    ) -> dict[str, Any]:
        """Validate and add one plugin-generated NPC to the current session."""
        session = self._read_json(self._session_file(run_id, session_id))
        if dict(session.get("pending_turn", {}) or {}):
            raise ValueError("当前回复尚未完成，暂时不能让新 NPC 入场。")

        def clean(key: str, limit: int) -> str:
            return _text_utils.trim_summary_text(
                str(dict(npc or {}).get(key, "")).strip(), limit
            )

        name = clean("name", 40)
        if not name or "\n" in name or "\r" in name:
            raise ValueError("临时 NPC 必须有有效名称。")
        reserved = {
            "user",
            "你",
            "旁白",
            "场景提示",
            "模型推理",
            "system",
            "assistant",
        }
        if name.casefold() in reserved:
            raise ValueError("临时 NPC 不能使用系统保留名称。")
        participants = [
            str(item).strip()
            for item in list(session.get("participants", []) or [])
            if str(item).strip()
        ]
        if name.casefold() in {item.casefold() for item in participants}:
            raise ValueError(f"当前会话已经存在名为“{name}”的角色。")

        role = clean("role", 100) or "临时来客"
        appearance = clean("appearance", 240)
        personality = clean("personality", 240)
        speech_style = clean("speech_style", 240)
        motive = clean("motive", 240)
        entrance = clean("entrance", 500) or f"{name}来到了现场。"
        opening_line = clean("opening_line", 500)
        if not opening_line:
            raise ValueError("临时 NPC 必须有一句入场台词。")

        now = _utc_now()
        turn_id = f"npc-{uuid4().hex[:10]}"
        record = {
            "name": name,
            "role": role,
            "appearance": appearance,
            "personality": personality,
            "speech_style": speech_style,
            "motive": motive,
            "entrance": entrance,
            "opening_line": opening_line,
            "introduced_at": now,
            "status": "active",
            "source": "plugin",
        }
        catalogue = dict(session.get("temporary_npcs", {}) or {})
        catalogue[name] = record
        participants.append(name)
        session["participants"] = participants
        session["temporary_npcs"] = catalogue
        session.setdefault("history", []).extend(
            [
                {
                    "speaker": "场景提示",
                    "message": entrance,
                    "target": "",
                    "ts": now,
                    "turn_id": turn_id,
                    "source": "temporary_npc_plugin",
                },
                {
                    "speaker": name,
                    "message": opening_line,
                    "target": "",
                    "ts": now,
                    "turn_id": turn_id,
                    "source": "temporary_npc_plugin",
                },
            ]
        )

        progress = self._session_scene_progress(session)
        present = [
            str(item).strip()
            for item in list(progress.get("present_participants", []) or [])
            if str(item).strip()
        ]
        if name not in present:
            present.append(name)
        progress["present_participants"] = present
        progress["offstage_participants"] = [
            item
            for item in list(progress.get("offstage_participants", []) or [])
            if str(item).strip() != name
        ]
        progress["updated_at"] = now
        self._set_session_scene_progress(session, progress)

        event_signals = self._session_event_signals(session)
        recent = [
            dict(item or {})
            for item in list(event_signals.get("recent", []) or [])
            if isinstance(item, dict)
        ]
        recent.append(
            {
                "turn_id": turn_id,
                "kind": "cast_enter",
                "actor": name,
                "target": "",
                "cue": entrance,
                "updated_at": now,
            }
        )
        event_signals["recent"] = recent[-20:]
        self._set_session_event_signals(session, event_signals)
        session["updated_at"] = now
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    def search_session_transcript(
        self,
        run_id: str,
        session_id: str,
        *,
        query: str,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        normalized_query = str(query or "").strip()
        if not normalized_query:
            return []
        result_limit = max(1, min(int(limit or 50), 100))
        with self.session_lock(run_id, session_id):
            session = self._read_json(self._session_file(run_id, session_id))
            serialized = self._serialize_session(run_id, session)
            completed_records = self._completed_turn_records(run_id, session_id)
        query_key = normalized_query.casefold()
        items: list[dict[str, Any]] = []
        seen: set[tuple[str, str]] = set()

        for entry in reversed(list(serialized.get("transcript", []) or [])):
            speaker = str(entry.get("speaker", "")).strip()
            message = str(entry.get("message", "")).strip()
            if query_key not in speaker.casefold() and query_key not in message.casefold():
                continue
            key = (speaker, message)
            if key in seen:
                continue
            seen.add(key)
            items.append(
                {
                    "speaker": speaker,
                    "message": message,
                    "role": str(entry.get("role", "character")).strip() or "character",
                    "turn_id": str(entry.get("turn_id", "")).strip(),
                    "timestamp": str(entry.get("timestamp", "")).strip(),
                    "archived": False,
                    "score": 1.0,
                }
            )
            if len(items) >= result_limit:
                return items

        memory_store = self._resolve_memory_store(run_id)
        if memory_store is None:
            return items
        memory_hits = memory_store.search_long_term_memory(
            session_id,
            normalized_query,
            top_k=min(50, result_limit),
        )
        turn_lookup: dict[tuple[str, str], str] = {}
        for record in completed_records:
            turn_id = str(record.get("turn_id", "")).strip()
            payload = dict(record.get("payload", {}) or {})
            input_payload = dict(payload.get("input", {}) or {})
            candidates = [input_payload]
            candidates.extend(
                item
                for item in list(dict(record.get("result", {}) or {}).get("responses", []) or [])
                if isinstance(item, dict)
            )
            for candidate in candidates:
                speaker = str(candidate.get("speaker", "")).strip()
                message = str(candidate.get("message", "")).strip()
                if speaker and message and turn_id:
                    turn_lookup[(speaker, message)] = turn_id

        controlled = str(session.get("controlled_character", "")).strip()
        self_insert_name = str(
            dict(session.get("self_insert", {}) or {}).get("display_name", "")
        ).strip()
        mode = str(session.get("mode", "observe")).strip() or "observe"
        for hit in memory_hits:
            metadata = dict(hit.get("metadata", {}) or {})
            speaker = str(metadata.get("speaker", hit.get("speaker", ""))).strip()
            text = str(hit.get("text", "")).strip()
            message = text.split(": ", 1)[1].strip() if ": " in text else text
            key = (speaker, message)
            if not message or key in seen:
                continue
            seen.add(key)
            role = "character"
            if speaker in {"旁白", "场景提示"}:
                role = "director" if mode == "observe" else "scene"
            elif mode == "act" and speaker == controlled:
                role = "user"
            elif mode == "insert" and speaker == self_insert_name:
                role = "user"
            elif mode == "observe" and speaker == "User":
                role = "director"
            items.append(
                {
                    "speaker": speaker,
                    "message": message,
                    "role": role,
                    "turn_id": str(metadata.get("turn_id", hit.get("turn_id", ""))).strip()
                    or turn_lookup.get(key, ""),
                    "timestamp": str(metadata.get("ts", hit.get("ts", ""))).strip(),
                    "archived": True,
                    "score": float(hit.get("score", 0.0) or 0.0),
                }
            )
            if len(items) >= result_limit:
                break
        return items

    @with_session_lock
    def delete_session(self, run_id: str, session_id: str) -> None:
        session_dir = self._session_dir(run_id, session_id)
        if not session_dir.exists():
            raise FileNotFoundError(str(session_dir))
        shutil.rmtree(session_dir)

    @with_session_lock
    def update_scene_card(
        self,
        run_id: str,
        session_id: str,
        *,
        scene_profile: dict[str, str] | None = None,
        transition_message: str = "",
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        pending = dict(session.get("pending_turn", {}) or {})
        if pending:
            stale_reason = self._stale_pending_turn_reason(
                run_id, session_id, pending
            )
            if stale_reason:
                self._abort_pending_turn_state(
                    session, reason=stale_reason, aborted_at=_utc_now()
                )
        if session.get("pending_turn"):
            raise ValueError("当前还有一轮待收口，请先等这拍结束再转场。")
        normalized_scene = dict(scene_profile or {})
        session["scene_card"] = normalized_scene
        session["scene_card_id"] = str(
            normalized_scene.get("scene_card_id", "")
        ).strip()
        scene_note = self._build_scene_switch_note(normalized_scene, transition_message)
        switched_at = _utc_now()
        if scene_note:
            session.setdefault("history", []).append(
                {
                    "speaker": "场景提示",
                    "message": scene_note,
                    "target": "",
                    "ts": switched_at,
                }
            )
        scene_title = str(normalized_scene.get("title", "")).strip()
        scene_location = str(normalized_scene.get("location", "")).strip()
        scene_time = str(normalized_scene.get("time_hint", "")).strip()
        scene_atmosphere = str(normalized_scene.get("atmosphere", "")).strip()
        scene_cue = scene_note or scene_title or scene_location or "切换到新场景"
        transition_events: list[dict[str, Any]] = []
        if normalized_scene:
            transition_events.append(
                {
                    "kind": "scene_transition",
                    "scope": "scene",
                    "actor": "场景提示",
                    "cue": scene_cue,
                    "source": "scene_card_switch",
                    "location_hint": scene_location,
                    "ts": switched_at,
                }
            )
        if scene_time:
            transition_events.append(
                {
                    "kind": "time_change",
                    "scope": "scene",
                    "actor": "场景提示",
                    "cue": f"新场景时间：{scene_time}",
                    "source": "scene_card_switch",
                    "time_hint": scene_time,
                    "ts": switched_at,
                }
            )
        if scene_atmosphere:
            transition_events.append(
                {
                    "kind": "atmosphere_shift",
                    "scope": "scene",
                    "actor": "场景提示",
                    "cue": scene_atmosphere,
                    "source": "scene_card_switch",
                    "ts": switched_at,
                }
            )
        if transition_events:
            self._set_session_event_signals(
                session,
                self._merge_event_signals_state(session, transition_events),
            )
        derived_progress = self._derive_scene_progress_state(
            session, self._serialize_transcript(session)
        )
        if scene_location:
            derived_progress["location"] = scene_location
        if scene_time:
            derived_progress["time_hint"] = scene_time
        if scene_atmosphere:
            derived_progress["atmosphere_summary"] = scene_atmosphere
        derived_progress.update(
            {
                "progression_note": "",
                "should_offer_scene_shift": False,
                "scene_shift_reason": "",
                "turns_in_current_scene": 0,
                "beat_maturity": 0,
                "world_tension_summary": "",
                "updated_at": switched_at,
            }
        )
        self._set_session_scene_progress(
            session,
            derived_progress,
        )
        transcript = self._serialize_transcript(session)
        memory_summary = self._build_session_memory_summary(run_id, session, transcript)
        scene_history = list(session.get("scene_history", []) or [])
        scene_history.append(
            self._build_scene_history_entry(
                normalized_scene,
                transition_message=transition_message,
                memory_summary=memory_summary,
            )
        )
        session["scene_history"] = scene_history
        session["updated_at"] = _utc_now()
        session["status"] = "ready"
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def update_scene_progress_state(
        self,
        run_id: str,
        session_id: str,
        scene_progress: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        self._set_session_scene_progress(
            session,
            self._merge_scene_progress_state(
                session,
                dict(scene_progress or {}),
            ),
        )
        session["updated_at"] = _utc_now()
        self._refresh_latest_turn_checkpoint(run_id, session_id, session)
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def branch_session_from_scene(
        self,
        run_manifest: dict[str, Any],
        session_id: str,
        *,
        scene_index: int,
    ) -> dict[str, Any]:
        run_id = str(run_manifest.get("run_id", "")).strip()
        session = self._read_json(self._session_file(run_id, session_id))
        scene_history = list(session.get("scene_history", []) or [])
        if scene_index < 0 or scene_index >= len(scene_history):
            raise ValueError("指定的场景时间线节点不存在。")
        target = dict(scene_history[scene_index] or {})
        scene_profile = dict(target.get("scene_card", {}) or {})
        if not scene_profile:
            scene_profile = {
                "scene_card_id": str(target.get("scene_card_id", "")).strip(),
                "title": str(target.get("title", "")).strip(),
                "location": str(target.get("location", "")).strip(),
                "atmosphere": str(target.get("atmosphere", "")).strip(),
            }
        memory_summary = dict(target.get("memory_summary", {}) or {})
        branch = self.create_session(
            run_manifest,
            mode=str(session.get("mode", "observe")).strip() or "observe",
            participants=list(session.get("participants", []) or []),
            controlled_character=str(session.get("controlled_character", "")).strip(),
            scene_profile=scene_profile,
            self_profile=dict(session.get("self_insert", {}) or {}),
            carried_memory_summary=memory_summary,
            branch_origin={
                "session_id": str(session.get("session_id", "")).strip(),
                "scene_index": scene_index,
                "scene_title": str(target.get("title", "")).strip(),
                "kind": "scene_timeline",
            },
        )
        branch_id = str(branch.get("session_id", "")).strip()
        branch_payload = self._read_json(self._session_file(run_id, branch_id))
        branch_payload["relation_locks"] = dict(
            session.get("relation_locks", {}) or {}
        )
        branch_payload["memory_ledger"] = [
            dict(item or {})
            for item in list(session.get("memory_ledger", []) or [])
            if isinstance(item, dict)
        ]
        target_ts = str(target.get("ts", "")).strip()
        inherited_arcs: list[dict[str, Any]] = []
        for arc in self._serialize_character_arcs(run_id, session):
            copied = dict(arc or {})
            copied["points"] = [
                {**dict(point or {}), "inherited": True}
                for point in list(arc.get("points", []) or [])
                if not target_ts
                or not str(point.get("updated_at", "")).strip()
                or str(point.get("updated_at", "")).strip() <= target_ts
            ]
            if copied["points"]:
                copied["current"] = dict(copied["points"][-1].get("state", {}) or {})
            inherited_arcs.append(copied)
        branch_payload["inherited_character_arcs"] = inherited_arcs
        branch_payload["updated_at"] = _utc_now()
        self._write_json(self._session_file(run_id, branch_id), branch_payload)
        return self._serialize_session(run_id, branch_payload)

    @with_session_lock
    def branch_session_from_turn(
        self,
        run_manifest: dict[str, Any],
        session_id: str,
        *,
        turn_id: str,
    ) -> dict[str, Any]:
        """Create a non-destructive branch immediately after a completed turn."""

        run_id = str(run_manifest.get("run_id", "")).strip()
        source = self._read_json(self._session_file(run_id, session_id))
        records = self._completed_turn_records(run_id, session_id)
        target_index = next(
            (
                index
                for index, record in enumerate(records)
                if str(record.get("turn_id", "")).strip() == str(turn_id).strip()
            ),
            -1,
        )
        if target_index < 0:
            inherited_target = next(
                (
                    dict(item or {})
                    for item in list(source.get("inherited_event_timeline", []) or [])
                    if str(dict(item or {}).get("turn_id", "")).strip()
                    == str(turn_id).strip()
                ),
                {},
            )
            origin_session_id = str(
                inherited_target.get("source_session_id", "")
            ).strip()
            if origin_session_id and origin_session_id != session_id:
                return self.branch_session_from_turn(
                    run_manifest,
                    origin_session_id,
                    turn_id=turn_id,
                )
            raise ValueError("指定的剧情事件节点不存在。")
        target = records[target_index]
        checkpoint = dict(target.get("checkpoint", {}) or {})
        if not checkpoint:
            checkpoint = self._build_legacy_turn_checkpoint(
                source,
                records,
                target_index,
            )
        history = [
            dict(item or {})
            for item in list(checkpoint.get("history", []) or [])
            if isinstance(item, dict)
        ]
        if not history:
            raise ValueError("该旧剧情节点缺少可恢复的对话记录。")

        scene_card = dict(
            checkpoint.get("scene_card", {}) or source.get("scene_card", {}) or {}
        )
        carried_summary = dict(
            checkpoint.get("memory_summary", {})
            or target.get("memory_summary", {})
            or {}
        )
        branch = self.create_session(
            run_manifest,
            mode=str(source.get("mode", "observe")).strip() or "observe",
            participants=list(source.get("participants", []) or []),
            controlled_character=str(source.get("controlled_character", "")).strip(),
            scene_profile=scene_card,
            self_profile=dict(source.get("self_insert", {}) or {}),
            carried_memory_summary=carried_summary,
            branch_origin={
                "session_id": session_id,
                "turn_id": str(target.get("turn_id", "")).strip(),
                "kind": "event_timeline",
                "event_title": str(target.get("title", "")).strip(),
            },
        )
        branch_id = str(branch.get("session_id", "")).strip()
        payload = self._read_json(self._session_file(run_id, branch_id))
        if list(checkpoint.get("participants", []) or []):
            payload["participants"] = list(checkpoint.get("participants", []) or [])
        payload["temporary_npcs"] = dict(
            checkpoint.get("temporary_npcs", {}) or {}
        )
        payload["history"] = history
        payload["scene_card"] = scene_card
        payload["scene_card_id"] = str(
            checkpoint.get("scene_card_id", scene_card.get("scene_card_id", ""))
        ).strip()
        if list(checkpoint.get("scene_history", []) or []):
            payload["scene_history"] = list(checkpoint.get("scene_history", []) or [])
        if dict(checkpoint.get("scene_progress", {}) or {}):
            self._set_session_scene_progress(
                payload, dict(checkpoint.get("scene_progress", {}) or {})
            )
        if dict(checkpoint.get("character_snapshots", {}) or {}):
            self._set_session_character_snapshots(
                payload, dict(checkpoint.get("character_snapshots", {}) or {})
            )
        self._set_session_relation_delta(
            payload, dict(checkpoint.get("relation_delta", {}) or {})
        )
        if dict(checkpoint.get("relation_matrix", {}) or {}):
            self._set_session_relation_matrix(
                payload, dict(checkpoint.get("relation_matrix", {}) or {})
            )
        self._set_session_event_signals(
            payload, dict(checkpoint.get("event_signals", {}) or {})
        )
        self._set_session_memory_summary_state(
            payload, dict(checkpoint.get("memory_summary_state", {}) or {})
        )
        payload["consistency_monitor"] = dict(
            checkpoint.get("consistency_monitor", {}) or {}
        )
        payload["relation_locks"] = dict(
            checkpoint.get("relation_locks", {})
            or source.get("relation_locks", {})
            or {}
        )
        payload["memory_ledger"] = [
            dict(item or {})
            for item in list(
                checkpoint.get("memory_ledger", source.get("memory_ledger", []))
                or []
            )
            if isinstance(item, dict)
        ]
        source_event_timeline = self._serialize_event_timeline(run_id, source)
        inherited_events: list[dict[str, Any]] = []
        for item in source_event_timeline:
            inherited_events.append(dict(item or {}))
            if (
                str(item.get("turn_id", "")).strip()
                == str(target.get("turn_id", "")).strip()
                and str(item.get("source_session_id", session_id)).strip()
                == session_id
            ):
                break
        payload["inherited_event_timeline"] = inherited_events
        inherited_event_ids = {
            str(item.get("turn_id", "")).strip()
            for item in inherited_events
            if str(item.get("turn_id", "")).strip()
        }
        source_locked_ids = list(
            dict(source.get("branch_meta", {}) or {}).get("locked_event_ids", []) or []
        )
        payload["branch_meta"] = {
            **dict(payload.get("branch_meta", {}) or {}),
            "locked_event_ids": [
                str(item).strip()
                for item in source_locked_ids
                if str(item).strip() in inherited_event_ids
            ],
        }
        source_relation_timeline = self._serialize_relation_timeline(run_id, source)
        inherited_relations: list[dict[str, Any]] = []
        for relation in source_relation_timeline:
            copied = dict(relation or {})
            copied_points: list[dict[str, Any]] = []
            found_target = False
            for point in list(relation.get("points", []) or []):
                copied_points.append(dict(point or {}))
                if str(point.get("turn_id", "")).strip() == str(
                    target.get("turn_id", "")
                ).strip():
                    found_target = True
                    break
            if not found_target:
                continue
            copied["points"] = copied_points
            if copied_points:
                copied["current"] = dict(copied_points[-1].get("values", {}) or {})
            inherited_relations.append(copied)
        payload["inherited_relation_timeline"] = inherited_relations
        target_updated_at = str(target.get("updated_at", "")).strip()
        inherited_arcs: list[dict[str, Any]] = []
        for arc in self._serialize_character_arcs(run_id, source):
            copied = dict(arc or {})
            copied_points = [
                {**dict(point or {}), "inherited": True}
                for point in list(arc.get("points", []) or [])
                if not target_updated_at
                or not str(point.get("updated_at", "")).strip()
                or str(point.get("updated_at", "")).strip() <= target_updated_at
            ]
            copied["points"] = copied_points
            if copied_points:
                copied["current"] = dict(copied_points[-1].get("state", {}) or {})
            inherited_arcs.append(copied)
        payload["inherited_character_arcs"] = inherited_arcs
        payload["pending_turn"] = {}
        payload["status"] = "ready"
        payload["updated_at"] = _utc_now()
        self._write_json(self._session_file(run_id, branch_id), payload)
        return self._serialize_session(run_id, payload)

    @with_session_lock
    def create_correction_branch(
        self,
        run_manifest: dict[str, Any],
        session_id: str,
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        """Fork immediately before the latest inconsistent turn."""

        run_id = str(run_manifest.get("run_id", "")).strip()
        source = self._read_json(self._session_file(run_id, session_id))
        monitor = dict(source.get("consistency_monitor", {}) or {})
        latest = dict(monitor.get("latest", {}) or {})
        issues = [
            dict(item or {})
            for item in list(latest.get("issues", []) or [])
            if isinstance(item, dict)
        ]
        turn_id = str(latest.get("turn_id", "")).strip()
        if not turn_id or not issues:
            raise ValueError("Latest turn has no consistency issue to correct.")

        turn_payload = self._read_json(
            self._turn_file(run_id, session_id, turn_id, "payload")
        )
        turn_result = self._read_json(
            self._turn_file(run_id, session_id, turn_id, "result")
        )
        original_responses = [
            dict(item or {})
            for item in list(turn_result.get("responses", []) or [])
            if isinstance(item, dict)
        ]
        input_payload = dict(turn_payload.get("input", {}) or {})
        original_message = str(input_payload.get("message", "")).strip()
        if not original_message or not original_responses:
            raise ValueError("Latest turn does not contain enough data to correct.")

        branch = self.create_session(
            run_manifest,
            mode=str(source.get("mode", "observe")).strip() or "observe",
            participants=list(source.get("participants", []) or []),
            controlled_character=str(source.get("controlled_character", "")).strip(),
            scene_profile=dict(source.get("scene_card", {}) or {}),
            self_profile=dict(source.get("self_insert", {}) or {}),
            carried_memory_summary=dict(
                dict(turn_payload.get("memory_context", {}) or {}).get(
                    "session_summary", {}
                )
                or {}
            ),
            branch_origin={
                "session_id": session_id,
                "turn_id": turn_id,
                "kind": "consistency_correction",
            },
        )
        branch_id = str(branch.get("session_id", "")).strip()
        branch_payload = self._read_json(self._session_file(run_id, branch_id))
        checkpoint_before = dict(turn_payload.get("checkpoint_before", {}) or {})
        if list(checkpoint_before.get("participants", []) or []):
            branch_payload["participants"] = list(
                checkpoint_before.get("participants", []) or []
            )
        branch_payload["temporary_npcs"] = dict(
            checkpoint_before.get("temporary_npcs", {}) or {}
        )
        branch_payload["relation_locks"] = dict(
            checkpoint_before.get("relation_locks", {})
            or source.get("relation_locks", {})
            or {}
        )
        branch_payload["memory_ledger"] = [
            dict(item or {})
            for item in list(
                checkpoint_before.get("memory_ledger", source.get("memory_ledger", []))
                or []
            )
            if isinstance(item, dict)
        ]
        branch_payload["inherited_event_timeline"] = [
            dict(item or {})
            for item in self._serialize_event_timeline(run_id, source)
            if str(item.get("turn_id", "")).strip() != turn_id
        ]
        inherited_event_ids = {
            str(item.get("turn_id", "")).strip()
            for item in branch_payload["inherited_event_timeline"]
            if str(item.get("turn_id", "")).strip()
        }
        source_locked_ids = list(
            dict(source.get("branch_meta", {}) or {}).get("locked_event_ids", []) or []
        )
        branch_payload["branch_meta"] = {
            **dict(branch_payload.get("branch_meta", {}) or {}),
            "locked_event_ids": [
                str(item).strip()
                for item in source_locked_ids
                if str(item).strip() in inherited_event_ids
            ],
        }
        inherited_relations: list[dict[str, Any]] = []
        for relation in self._serialize_relation_timeline(run_id, source):
            copied = dict(relation or {})
            copied["points"] = [
                dict(point or {})
                for point in list(relation.get("points", []) or [])
                if str(point.get("turn_id", "")).strip() != turn_id
            ]
            if copied["points"]:
                copied["current"] = dict(
                    copied["points"][-1].get("values", {}) or {}
                )
            inherited_relations.append(copied)
        branch_payload["inherited_relation_timeline"] = inherited_relations
        inherited_arcs: list[dict[str, Any]] = []
        for arc in self._serialize_character_arcs(run_id, source):
            copied = dict(arc or {})
            copied["points"] = [
                {**dict(point or {}), "inherited": True}
                for point in list(arc.get("points", []) or [])
                if str(point.get("turn_id", "")).strip() != turn_id
            ]
            if copied["points"]:
                copied["current"] = dict(copied["points"][-1].get("state", {}) or {})
            inherited_arcs.append(copied)
        branch_payload["inherited_character_arcs"] = inherited_arcs

        history = [
            dict(item or {})
            for item in list(checkpoint_before.get("history", []) or [])
            if isinstance(item, dict)
        ]
        if not history:
            history = [
                dict(item or {}) for item in list(source.get("history", []) or [])
            ]
            for response in reversed(original_responses):
                if not history:
                    break
                tail = history[-1]
                if (
                    str(tail.get("speaker", "")).strip()
                    == str(response.get("speaker", "")).strip()
                    and str(tail.get("message", "")).strip()
                    == str(response.get("message", "")).strip()
                ):
                    history.pop()
            original_speaker = str(input_payload.get("speaker", "")).strip()
            if history and (
                str(history[-1].get("speaker", "")).strip() == original_speaker
                and str(history[-1].get("message", "")).strip()
                == original_message
            ):
                history.pop()
        branch_payload["history"] = history

        restored_scene_card = dict(checkpoint_before.get("scene_card", {}) or {})
        if restored_scene_card:
            branch_payload["scene_card"] = restored_scene_card
            branch_payload["scene_card_id"] = str(
                checkpoint_before.get(
                    "scene_card_id", restored_scene_card.get("scene_card_id", "")
                )
            ).strip()
        restored_scene_history = [
            dict(item or {})
            for item in list(checkpoint_before.get("scene_history", []) or [])
            if isinstance(item, dict)
        ]
        if restored_scene_history:
            branch_payload["scene_history"] = restored_scene_history

        restored_progress = dict(
            checkpoint_before.get("scene_progress", {})
            or turn_payload.get("scene_progress", {})
            or {}
        )
        if restored_progress:
            self._set_session_scene_progress(branch_payload, restored_progress)
        restored_snapshots = dict(
            checkpoint_before.get("character_snapshots", {})
            or input_payload.get("character_snapshots", {})
            or {}
        )
        if restored_snapshots:
            self._set_session_character_snapshots(branch_payload, restored_snapshots)
        memory_context = dict(turn_payload.get("memory_context", {}) or {})
        self._set_session_relation_delta(
            branch_payload,
            dict(
                checkpoint_before.get("relation_delta", {})
                or memory_context.get("relation_delta", {})
                or {}
            ),
        )
        restored_relation_matrix = dict(
            checkpoint_before.get("relation_matrix", {}) or {}
        )
        if restored_relation_matrix:
            self._set_session_relation_matrix(
                branch_payload, restored_relation_matrix
            )
        restored_event_state = dict(
            checkpoint_before.get("event_signals", {}) or {}
        )
        if restored_event_state:
            self._set_session_event_signals(
                branch_payload,
                restored_event_state,
            )
        else:
            restored_events = [
                dict(item or {})
                for item in list(memory_context.get("event_signals", []) or [])
                if isinstance(item, dict)
            ]
            if restored_events:
                self._set_session_event_signals(
                    branch_payload,
                    self._merge_event_signals_state(branch_payload, restored_events),
                )
        restored_memory_state = dict(
            checkpoint_before.get("memory_summary_state", {})
            or memory_context.get("archived_summary", {})
            or {}
        )
        if restored_memory_state:
            self._set_session_memory_summary_state(
                branch_payload, restored_memory_state
            )
        prior_monitor = dict(
            checkpoint_before.get("consistency_monitor", {}) or {}
        )
        branch_payload["consistency_monitor"] = prior_monitor or {
            "latest": {},
            "history": [],
            "checked_turns": 0,
            "issue_count": 0,
            "knowledge_ledger": list(
                turn_payload.get("knowledge_context", []) or []
            ),
        }
        branch_payload["updated_at"] = _utc_now()
        self._write_json(self._session_file(run_id, branch_id), branch_payload)

        correction_context = {
            "source_session_id": session_id,
            "source_turn_id": turn_id,
            "issues": issues,
            "original_responses": original_responses,
            "message": original_message,
            "message_kind": str(input_payload.get("message_kind", "dialogue")).strip()
            or "dialogue",
        }
        return self._serialize_session(run_id, branch_payload), correction_context

    def build_consistency_review_payload(
        self,
        run_id: str,
        session_id: str,
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        monitor = dict(session.get("consistency_monitor", {}) or {})
        latest = dict(monitor.get("latest", {}) or {})
        turn_id = str(latest.get("turn_id", "")).strip()
        if not turn_id:
            raise ValueError("No completed turn is available for deep review.")
        turn_payload = self._read_json(
            self._turn_file(run_id, session_id, turn_id, "payload")
        )
        turn_result = self._read_json(
            self._turn_file(run_id, session_id, turn_id, "result")
        )
        responses = [
            dict(item or {})
            for item in list(turn_result.get("responses", []) or [])
            if isinstance(item, dict)
        ]
        if not responses:
            raise ValueError("Latest turn has no responses to review.")
        return {
            "session_id": session_id,
            "turn_id": turn_id,
            "mode": str(session.get("mode", "observe")).strip() or "observe",
            "participants": list(session.get("participants", []) or []),
            "scene_progress": dict(turn_payload.get("scene_progress", {}) or {}),
            "persona_contexts": list(turn_payload.get("persona_contexts", []) or []),
            "relation_context": dict(turn_payload.get("relation_context", {}) or {}),
            "knowledge_context": list(turn_payload.get("knowledge_context", []) or []),
            "history": list(turn_payload.get("history", []) or []),
            "input": dict(turn_payload.get("input", {}) or {}),
            "responses": responses,
            "deterministic_report": latest,
        }

    @with_session_lock
    def apply_semantic_consistency_review(
        self,
        run_id: str,
        *,
        session_id: str,
        review: dict[str, Any],
        expected_turn_id: str = "",
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        monitor = dict(session.get("consistency_monitor", {}) or {})
        latest = dict(monitor.get("latest", {}) or {})
        if not latest:
            raise ValueError("No consistency report is available to update.")
        expected = str(expected_turn_id or "").strip()
        latest_turn_id = str(latest.get("turn_id", "")).strip()
        if expected and latest_turn_id != expected:
            raise ValueError(
                "会话已进入新一轮，本次深度复核结果已过期，请重新复核最新一轮。"
            )
        old_issue_count = len(list(latest.get("issues", []) or []))
        merged = _consistency.merge_semantic_review(
            latest,
            review,
            reviewed_at=_utc_now(),
        )
        history = [dict(item or {}) for item in list(monitor.get("history", []) or [])]
        turn_id = str(merged.get("turn_id", "")).strip()
        replaced = False
        for index, item in enumerate(history):
            if str(item.get("turn_id", "")).strip() == turn_id:
                history[index] = merged
                replaced = True
        if not replaced:
            history.append(merged)
        history = history[-20:]
        new_issue_count = len(list(merged.get("issues", []) or []))
        monitor.update(
            {
                "latest": merged,
                "history": history,
                "issue_count": max(
                    0,
                    int(monitor.get("issue_count", 0) or 0)
                    + new_issue_count
                    - old_issue_count,
                ),
                "semantic_review_count": int(
                    monitor.get("semantic_review_count", 0) or 0
                )
                + 1,
                "metrics": _consistency.build_monitor_metrics(history),
            }
        )
        session["consistency_monitor"] = monitor
        session["updated_at"] = _utc_now()
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def set_relation_lock(
        self,
        run_id: str,
        session_id: str,
        *,
        pair_key: str,
        locked: bool,
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        normalized_key = str(pair_key or "").strip()
        valid_keys = set(
            self._merged_relation_matrix(
                session, list(session.get("participants", []) or [])
            )
        )
        if normalized_key not in valid_keys:
            raise ValueError("指定的人物关系不存在。")
        locks = dict(session.get("relation_locks", {}) or {})
        if locked:
            locks[normalized_key] = True
        else:
            locks.pop(normalized_key, None)
        session["relation_locks"] = locks
        session["updated_at"] = _utc_now()
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def upsert_controlled_memory(
        self,
        run_id: str,
        session_id: str,
        *,
        text: str,
        category: str = "story",
        pinned: bool = False,
        enabled: bool = True,
        memory_id: str = "",
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        normalized_text = _text_utils.trim_summary_text(str(text or "").strip(), 500)
        if not normalized_text:
            raise ValueError("记忆内容不能为空。")
        normalized_category = str(category or "story").strip().lower()
        if normalized_category not in {"short_term", "long_term", "story", "relationship"}:
            raise ValueError("不支持的记忆类型。")
        normalized_id = str(memory_id or "").strip()
        ledger = [
            dict(item or {})
            for item in list(session.get("memory_ledger", []) or [])
            if isinstance(item, dict)
        ]
        now = _utc_now()
        existing_index = next(
            (
                index
                for index, item in enumerate(ledger)
                if str(item.get("memory_id", "")).strip() == normalized_id
            ),
            -1,
        )
        entry = {
            "memory_id": normalized_id or f"mem-{uuid4().hex[:10]}",
            "text": normalized_text,
            "category": normalized_category,
            "pinned": bool(pinned),
            "enabled": bool(enabled),
            "created_at": (
                str(ledger[existing_index].get("created_at", "")).strip()
                if existing_index >= 0
                else now
            )
            or now,
            "updated_at": now,
        }
        if normalized_id and existing_index < 0:
            raise ValueError("指定的记忆不存在。")
        if existing_index >= 0:
            ledger[existing_index] = entry
        else:
            ledger.append(entry)
        if sum(1 for item in ledger if bool(item.get("enabled", True))) > 20:
            raise ValueError("同时启用的可控记忆最多为 20 条，请先停用一条。")
        session["memory_ledger"] = ledger[-100:]
        session["updated_at"] = now
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def delete_controlled_memory(
        self, run_id: str, session_id: str, *, memory_id: str
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        normalized_id = str(memory_id or "").strip()
        ledger = [
            dict(item or {})
            for item in list(session.get("memory_ledger", []) or [])
            if isinstance(item, dict)
        ]
        filtered = [
            item
            for item in ledger
            if str(item.get("memory_id", "")).strip() != normalized_id
        ]
        if len(filtered) == len(ledger):
            raise ValueError("指定的记忆不存在。")
        session["memory_ledger"] = filtered
        session["updated_at"] = _utc_now()
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def prepare_turn(
        self,
        run_manifest: dict[str, Any],
        *,
        session_id: str,
        message: str,
        message_kind: str = "dialogue",
        speaker_override: str = "",
        transcript_message: str | None = None,
        include_inner_thoughts: bool = False,
        _serialize_result: bool = True,
    ) -> dict[str, Any]:
        run_id = str(run_manifest.get("run_id", "")).strip()
        session = self._read_json(self._session_file(run_id, session_id))
        pending = dict(session.get("pending_turn", {}) or {})
        if pending:
            stale_reason = self._stale_pending_turn_reason(
                run_id, session_id, pending
            )
            if stale_reason:
                self._abort_pending_turn_state(
                    session, reason=stale_reason, aborted_at=_utc_now()
                )
        if session.get("pending_turn"):
            raise ValueError("当前已有一轮等待回复，请勿重复提交。")
        normalized_message_kind = self._normalize_message_kind(message_kind)
        effective_speaker_override = str(speaker_override or "").strip()
        if normalized_message_kind in {"narration", "plot"} and not effective_speaker_override:
            effective_speaker_override = "场景提示"
        turn_id = f"turn-{uuid4().hex[:8]}"
        payload = self._build_turn_payload(
            run_manifest,
            session,
            turn_id=turn_id,
            message=message,
            speaker_override=effective_speaker_override,
            message_kind=normalized_message_kind,
            include_inner_thoughts=include_inner_thoughts,
        )
        turn_dir = self._session_dir(run_id, session_id) / "turns"
        turn_dir.mkdir(parents=True, exist_ok=True)
        turn_payload_path = self._turn_file(
            run_id, session_id, turn_id, "payload"
        )
        self._write_json(turn_payload_path, payload)
        session["pending_turn"] = {
            "turn_id": turn_id,
            "user_message": message,
            "transcript_message": (
                message if transcript_message is None else transcript_message
            ),
            "message_kind": normalized_message_kind,
            "speaker": payload["input"]["speaker"],
            "mode": payload["mode"],
            "participants": list(payload["input"]["participants"]),
            "active_participants": list(
                payload["input"].get("active_participants", [])
            ),
            "response_limit_hint": payload["host_action"]["response_limit_hint"],
            "payload_path": str(turn_payload_path.resolve()),
            "created_at": _utc_now(),
        }
        session["updated_at"] = _utc_now()
        session["status"] = "waiting_for_host_reply"
        self._write_json(self._session_file(run_id, session_id), session)
        if not _serialize_result:
            return {
                "pending_turn_summary": self._build_pending_turn_summary(session)
            }
        return self._serialize_session(run_id, session)

    def _stale_pending_turn_reason(
        self, run_id: str, session_id: str, pending: dict[str, Any]
    ) -> str:
        turn_id = str(pending.get("turn_id", "")).strip()
        if not turn_id:
            return "missing_turn_id"
        try:
            result_path = self._turn_file(
                run_id, session_id, turn_id, "result"
            )
            canonical_payload = self._turn_file(
                run_id, session_id, turn_id, "payload"
            )
        except InvalidStorageIdentifier:
            return "invalid_turn_id"
        turn_dir = self._session_dir(run_id, session_id) / "turns"
        if result_path.exists():
            return "result_already_exists"
        stored_path_text = str(pending.get("payload_path", "")).strip()
        stored_payload_exists = False
        if stored_path_text:
            stored_path = Path(stored_path_text)
            try:
                stored_payload_exists = stored_path.resolve().is_relative_to(
                    turn_dir.resolve()
                ) and stored_path.is_file()
            except (OSError, RuntimeError, ValueError):
                stored_payload_exists = False
        if not canonical_payload.is_file() and not stored_payload_exists:
            return "payload_missing"
        created_at = str(pending.get("created_at", "")).strip()
        if not created_at:
            return "missing_created_at"
        try:
            created = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
            if created.tzinfo is None:
                created = created.replace(tzinfo=timezone.utc)
            age_seconds = (datetime.now(timezone.utc) - created).total_seconds()
        except (TypeError, ValueError):
            return "invalid_created_at"
        if age_seconds >= self.PENDING_TURN_STALE_SECONDS:
            return "pending_timeout"
        return ""

    @staticmethod
    def _abort_pending_turn_state(
        session: dict[str, Any], *, reason: str, aborted_at: str
    ) -> None:
        pending = dict(session.get("pending_turn", {}) or {})
        if pending:
            aborted = [
                dict(item or {})
                for item in list(session.get("aborted_turns", []) or [])
                if isinstance(item, dict)
            ]
            aborted.append(
                {
                    "turn_id": str(pending.get("turn_id", "")).strip(),
                    "reason": str(reason or "aborted").strip(),
                    "created_at": str(pending.get("created_at", "")).strip(),
                    "aborted_at": aborted_at,
                }
            )
            session["aborted_turns"] = aborted[-20:]
        session["pending_turn"] = {}
        session["status"] = "ready"

    @with_session_lock
    def abort_pending_turn(
        self,
        run_id: str,
        session_id: str,
        *,
        expected_turn_id: str = "",
        reason: str = "generation_failed",
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        pending = dict(session.get("pending_turn", {}) or {})
        expected = str(expected_turn_id or "").strip()
        current = str(pending.get("turn_id", "")).strip()
        if not pending or (expected and current != expected):
            return self._serialize_session(run_id, session)
        self._abort_pending_turn_state(
            session, reason=reason, aborted_at=_utc_now()
        )
        session["updated_at"] = _utc_now()
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def reconcile_turn_result(
        self,
        run_id: str,
        session_id: str,
        *,
        turn_id: str,
    ) -> dict[str, Any] | None:
        """Return or restore a turn which crossed the result/session commit boundary."""

        normalized_turn_id = validate_storage_id(turn_id, field_name="turn_id")
        session = self._read_json(self._session_file(run_id, session_id))
        history = [
            dict(item or {})
            for item in list(session.get("history", []) or [])
            if isinstance(item, dict)
        ]
        if any(
            str(item.get("turn_id", "")).strip() == normalized_turn_id
            for item in history
        ):
            return self._serialize_session(run_id, session)

        pending = dict(session.get("pending_turn", {}) or {})
        if str(pending.get("turn_id", "")).strip() != normalized_turn_id:
            return None
        result_path = self._turn_file(
            run_id,
            session_id,
            normalized_turn_id,
            "result",
        )
        if not result_path.is_file():
            return None
        result = self._read_json(result_path)
        if str(result.get("turn_id", "")).strip() != normalized_turn_id:
            return None
        checkpoint = dict(result.get("checkpoint", {}) or {})
        checkpoint_history = list(checkpoint.get("history", []) or [])
        if not checkpoint_history or not any(
            str(dict(item or {}).get("turn_id", "")).strip() == normalized_turn_id
            for item in checkpoint_history
            if isinstance(item, dict)
        ):
            return None

        for key in (
            "participants",
            "temporary_npcs",
            "history",
            "scene_card",
            "scene_card_id",
            "scene_history",
            "scene_progress",
            "character_snapshots",
            "relation_delta",
            "relation_matrix",
            "event_signals",
            "memory_summary_state",
            "consistency_monitor",
            "relation_locks",
            "memory_ledger",
            "inherited_event_timeline",
            "inherited_relation_timeline",
            "generation_cache_stats",
        ):
            if key in checkpoint:
                session[key] = checkpoint[key]
        session["pending_turn"] = {}
        session["latest_context_usage"] = dict(result.get("context_usage", {}) or {})
        session["status"] = "ready"
        session["updated_at"] = str(result.get("updated_at", "")).strip() or _utc_now()
        self._write_json(self._session_file(run_id, session_id), session)
        return self._serialize_session(run_id, session)

    @with_session_lock
    def build_suggestion_payload(
        self,
        run_manifest: dict[str, Any],
        *,
        session_id: str,
        seed_text: str = "",
        direction: str = "",
    ) -> dict[str, Any]:
        run_id = str(run_manifest.get("run_id", "")).strip()
        session = self._read_json(self._session_file(run_id, session_id))
        payload = self._build_turn_payload(
            run_manifest,
            session,
            turn_id=f"suggest-{uuid4().hex[:8]}",
            message=seed_text,
        )
        mode = str(payload.get("mode", "observe")).strip() or "observe"
        speaker = str(payload.get("input", {}).get("speaker", "")).strip()
        participants = list(payload.get("input", {}).get("participants", []))
        payload["kind"] = "zaomeng_dialogue_suggestion"
        selected_direction = str(direction or "").strip()
        if selected_direction:
            payload["selected_direction"] = selected_direction
        scene_progress = dict(payload.get("scene_progress", {}) or {})
        session_summary = dict(
            dict(payload.get("memory_context", {}) or {}).get("session_summary", {})
            or {}
        )
        payload["user_persona"] = self._build_user_suggestion_persona(
            mode,
            session,
            payload.get("persona_contexts", []),
            scene_progress=scene_progress,
            session_summary=session_summary,
        )
        payload["instructions"] = {
            "mode": mode,
            "generation_goal": (
                "Draft one complete, natural, directly sendable next user message that fits the current scene, "
                "relationships, and persona voices. Use one to three sentences when the selected direction needs room to land."
            ),
            "mode_rule": self._suggestion_mode_rule(mode),
            "speaker_rule": self._speaker_rule(mode, session),
            "response_style": self._suggestion_style_rule(mode),
        }
        payload["host_action"] = {
            "expected_output": {"suggestion": "一段完整、可直接发送的文案"},
            "output_rule": "Keep it complete, in-scene, directly sendable, and never explanatory.",
        }
        payload["host_prompt_brief"] = self._host_suggestion_prompt_brief(
            mode,
            speaker,
            participants,
            scene_progress=scene_progress,
        )
        payload["updated_at"] = _utc_now()
        return payload

    def build_association_payload(
        self,
        run_manifest: dict[str, Any],
        *,
        session_id: str,
        option_count: int = 3,
    ) -> dict[str, Any]:
        run_id = str(run_manifest.get("run_id", "")).strip()
        session = self._read_json(self._session_file(run_id, session_id))
        payload = self.build_suggestion_payload(
            run_manifest,
            session_id=session_id,
        )
        count = max(2, min(int(option_count or 3), 4))
        payload["kind"] = "zaomeng_dialogue_associations"
        payload["latest_exchange"] = self._build_latest_exchange(session)
        payload["instructions"] = {
            "generation_goal": (
                "Propose distinct user-facing next directions that continue directly from "
                "the completed latest exchange. Treat older scene and relationship context "
                "as background only."
            ),
            "option_count": count,
        }
        payload["host_action"] = {
            "expected_output": {
                "options": [
                    {
                        "label": "4-10字的推进选项",
                        "direction": "供下一步代写使用的明确剧情方向",
                        "suggestion": "一至三句可直接发送的成品文案",
                        "anchor_speaker": "该方向所依据的最新回复角色",
                        "anchor_quote": "从该角色最新回复中原样摘录的4-20字",
                    }
                ],
            },
            "output_rule": (
                "Return exactly the requested number of options as JSON. "
                "Every option must cite an exact anchor from the latest replies. "
                "Never present a completed event as a future direction or invent a new fact."
            ),
        }
        payload["updated_at"] = _utc_now()
        return payload

    def build_director_payload(
        self,
        run_manifest: dict[str, Any],
        *,
        session_id: str,
        goal: str,
        action: str = "advance",
        option_count: int = 3,
    ) -> dict[str, Any]:
        normalized_goal = _text_utils.trim_summary_text(str(goal or "").strip(), 240)
        if not normalized_goal:
            raise ValueError("请先填写导演目标。")
        normalized_action = str(action or "advance").strip().lower()
        if normalized_action not in {"advance", "slow_emotion", "conflict", "viewpoint"}:
            raise ValueError("不支持的导演操作。")
        payload = self.build_suggestion_payload(
            run_manifest,
            session_id=session_id,
        )
        session = self._read_json(
            self._session_file(str(run_manifest.get("run_id", "")).strip(), session_id)
        )
        payload["kind"] = "zaomeng_dialogue_director_options"
        payload["director_goal"] = normalized_goal
        payload["director_action"] = normalized_action
        payload["option_count"] = max(2, min(int(option_count or 3), 4))
        payload["latest_exchange"] = self._build_latest_exchange(session)
        payload["updated_at"] = _utc_now()
        return payload

    def _build_latest_exchange(self, session: dict[str, Any]) -> dict[str, Any]:
        transcript = [
            item
            for item in self._serialize_transcript(session)
            if str(item.get("message", "")).strip()
        ]
        mode = str(session.get("mode", "observe")).strip() or "observe"
        anchor_roles = {"user"} if mode in {"act", "insert"} else {"director"}
        anchor_index = -1
        for index in range(len(transcript) - 1, -1, -1):
            if str(transcript[index].get("role", "")).strip() in anchor_roles:
                anchor_index = index
                break

        if anchor_index >= 0:
            exchange = transcript[anchor_index:]
            user_turn = dict(exchange[0])
            replies = [dict(item) for item in exchange[1:]]
        else:
            user_turn = {}
            replies = [dict(item) for item in transcript[-4:]]
        if len(replies) > 6:
            replies = replies[-6:]

        scene_progress = self._session_scene_progress(session)
        participants = [
            str(item).strip()
            for item in list(session.get("participants", []) or [])
            if str(item).strip()
        ]
        present = [
            str(item).strip()
            for item in list(scene_progress.get("present_participants", []) or [])
            if str(item).strip()
        ] or participants
        offstage = [
            str(item).strip()
            for item in list(scene_progress.get("offstage_participants", []) or [])
            if str(item).strip()
        ]
        return {
            "status": "completed",
            "user_turn": user_turn,
            "replies": replies,
            "latest_reply": dict(replies[-1]) if replies else {},
            "speakers_who_just_replied": [
                str(item.get("speaker", "")).strip()
                for item in replies
                if str(item.get("speaker", "")).strip()
            ],
            "present_participants": present,
            "offstage_participants": offstage,
        }

    @with_session_lock
    def ingest_turn_responses(
        self,
        run_id: str,
        *,
        session_id: str,
        responses: list[dict[str, str]],
        remember_turn_memory: bool = False,
        generation_cache: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        session = self._read_json(self._session_file(run_id, session_id))
        pending = dict(session.get("pending_turn", {}) or {})
        if not pending:
            raise ValueError("No pending turn to ingest.")
        session_store = (
            self._resolve_memory_store(run_id) if remember_turn_memory else None
        )
        pending_payload = self._read_pending_turn_payload(
            run_id, session_id, pending
        )
        original_entries = list(
            dict(pending_payload.get("original_source_context", {}) or {}).get(
                "entries", []
            )
            or []
        )
        clean_responses = []
        for item in responses:
            speaker = str(item.get("speaker", "")).strip()
            message = str(item.get("message", "")).strip()
            inner_thought = str(item.get("inner_thought", "")).strip()
            if not speaker or not message:
                continue
            clean_responses.append(
                {
                    "speaker": speaker,
                    "message": message,
                    "ts": _utc_now(),
                    **({"inner_thought": inner_thought} if inner_thought else {}),
                }
            )
        if not clean_responses:
            raise ValueError("No valid responses provided.")
        if not pending_payload:
            pending_payload = {
                "turn_id": str(pending.get("turn_id", "")).strip(),
                "mode": str(pending.get("mode", "")).strip(),
                "input": {
                    "participants": list(pending.get("participants", []) or []),
                    "active_participants": list(
                        pending.get("active_participants", []) or []
                    ),
                    "controlled_character": str(
                        session.get("controlled_character", "")
                    ).strip(),
                },
            }
        self._register_temporary_npc_speakers(
            session,
            clean_responses,
            pending_payload,
        )
        if not clean_responses:
            raise ValueError("No valid responses provided after speaker validation.")
        context_usage = self._build_context_usage(pending_payload)
        consistency_report = _consistency.evaluate_turn_consistency(
            pending_payload,
            clean_responses,
            checked_at=_utc_now(),
        )
        current_consistency_monitor = dict(
            session.get("consistency_monitor", {}) or {}
        )
        session["consistency_monitor"] = _consistency.update_monitor_state(
            current_consistency_monitor,
            consistency_report,
        )
        session["consistency_monitor"]["knowledge_ledger"] = (
            _consistency.update_knowledge_ledger(
                list(
                    current_consistency_monitor.get("knowledge_ledger", [])
                    or []
                ),
                pending_payload,
                clean_responses,
                recorded_at=_utc_now(),
            )
        )
        transcript_message = str(
            pending.get("transcript_message", pending.get("user_message", ""))
        ).strip()
        pending_turn_id = str(pending.get("turn_id", "")).strip()
        if transcript_message:
            user_entry = {
                "speaker": pending.get("speaker", "User"),
                "message": transcript_message,
                "target": "",
                "ts": pending.get("created_at", _utc_now()),
                "turn_id": pending_turn_id,
            }
            if session_store is not None:
                session_store.append_long_term_memory(
                    session_id,
                    self._entry_to_memory_text(user_entry),
                    metadata={
                        "run_id": run_id,
                        "kind": self._normalize_message_kind(
                            str(pending.get("message_kind", "")).strip()
                        ),
                        "speaker": str(user_entry.get("speaker", "")).strip(),
                        "target": "",
                        "ts": user_entry.get("ts", ""),
                        "turn_id": pending_turn_id,
                    },
                )
                user_entry["memory_archived"] = True
            session.setdefault("history", []).append(user_entry)
        remembered_responses = []
        pending_speaker = str(pending.get("speaker", "")).strip()
        active_participants = [
            str(item).strip()
            for item in pending.get("active_participants", [])
            if str(item).strip()
        ]
        for response in clean_responses:
            response["turn_id"] = pending_turn_id
        session["history"].extend(clean_responses)
        for item in clean_responses:
            response_entry = item
            if session_store is not None:
                target = (
                    pending_speaker
                    if pending_speaker not in {"", "User", "场景提示", "旁白"}
                    else ""
                )
                if not target:
                    pool = [
                        name
                        for name in active_participants
                        if name
                        and name != str(response_entry.get("speaker", "")).strip()
                    ]
                    target = pool[0] if pool else ""
                session_store.append_long_term_memory(
                    session_id,
                    self._entry_to_memory_text(response_entry),
                    metadata={
                        "run_id": run_id,
                        "kind": "dialogue",
                        "speaker": str(response_entry.get("speaker", "")).strip(),
                        "target": target,
                        "ts": response_entry.get("ts", ""),
                        "turn_id": pending_turn_id,
                    },
                )
                response_entry["memory_archived"] = True
            remembered_responses.append(response_entry)
        if remembered_responses:
            session["history"][-len(remembered_responses) :] = remembered_responses
        session["pending_turn"] = {}
        session["latest_context_usage"] = context_usage
        completed_at = _utc_now()
        session["updated_at"] = completed_at
        session["status"] = "ready"
        if generation_cache is not None:
            record_generation_cache_observation(
                session,
                generation_cache,
                turn_id=str(pending.get("turn_id", "")).strip(),
                updated_at=completed_at,
            )
        if session_store is not None:
            session_store.compress_context(session)
        result_path = self._turn_file(
            run_id,
            session_id,
            str(pending.get("turn_id", "")).strip(),
            "result",
        )
        result_payload = {
            "kind": "zaomeng_dialogue_result",
            "session_id": session_id,
            "turn_id": pending.get("turn_id", ""),
            "responses": clean_responses,
            "consistency_report": consistency_report,
            "context_usage": context_usage,
            "source_trace": {
                "retrieved": [
                    {
                        "source_id": str(item.get("source_id", "")).strip(),
                        "title": str(item.get("title", "")).strip(),
                        "location": dict(item.get("location", {}) or {}),
                        "visibility": str(item.get("visibility", "")).strip(),
                    }
                    for item in original_entries
                ],
                "tracking_mode": "retrieved_context_only",
            },
            "checkpoint": self._build_turn_checkpoint(session),
            "updated_at": completed_at,
        }
        if generation_cache is not None:
            result_payload["generation_cache"] = dict(
                session.get("generation_cache_stats", {}).get("latest", {}) or {}
            )
        self._write_json(result_path, result_payload)
        self._write_json(self._session_file(run_id, session_id), session)
        current_turn_events = [
            dict(event or {})
            for event in self._build_session_event_excerpt(session)
            if str(dict(event or {}).get("turn_id", "")).strip() == pending_turn_id
        ]
        temporary_names = {
            str(name).strip()
            for name in dict(session.get("temporary_npcs", {}) or {})
            if str(name).strip()
        }
        world_events = [
            event
            for event in current_turn_events
            if not self._event_mentions_temporary_npc(event, temporary_names)
        ]
        world_knowledge = [
            item
            for item in list(
                session.get("consistency_monitor", {}).get("knowledge_ledger", [])
                or []
            )
            if not self._knowledge_mentions_temporary_npc(item, temporary_names)
        ]
        world_title = str(pending.get("user_message", "")).strip()[:160]
        if any(name in world_title for name in temporary_names):
            world_title = "临时人物互动"
        self._world_memory.sync_completed_turn(
            run_id,
            session_id=session_id,
            turn_id=str(pending.get("turn_id", "")).strip(),
            title=world_title,
            participants=[
                name
                for name in list(session.get("participants", []) or [])
                if str(name).strip() not in temporary_names
            ],
            events=world_events,
            location=str(dict(pending_payload.get("scene_progress", {}) or {}).get("location", "")),
            time_hint="",
            consistency_status=str(consistency_report.get("status", "pass")),
            knowledge_ledger=world_knowledge,
            updated_at=completed_at,
        )
        return self._serialize_session(run_id, session)

    def _build_turn_checkpoint(self, session: dict[str, Any]) -> dict[str, Any]:
        return {
            "participants": list(session.get("participants", []) or []),
            "temporary_npcs": dict(session.get("temporary_npcs", {}) or {}),
            "history": [dict(item or {}) for item in list(session.get("history", []) or [])],
            "scene_card": dict(session.get("scene_card", {}) or {}),
            "scene_card_id": str(session.get("scene_card_id", "")).strip(),
            "scene_history": [
                dict(item or {}) for item in list(session.get("scene_history", []) or [])
            ],
            "scene_progress": self._session_scene_progress(session),
            "character_snapshots": self._session_character_snapshots(session),
            "relation_delta": self._session_relation_delta(session),
            "relation_matrix": self._session_relation_matrix(session),
            "event_signals": self._session_event_signals(session),
            "memory_summary_state": self._session_memory_summary_state(session),
            "consistency_monitor": dict(session.get("consistency_monitor", {}) or {}),
            "relation_locks": dict(session.get("relation_locks", {}) or {}),
            "memory_ledger": [
                dict(item or {})
                for item in list(session.get("memory_ledger", []) or [])
                if isinstance(item, dict)
            ],
            "generation_cache_stats": dict(
                session.get("generation_cache_stats", {}) or {}
            ),
            "inherited_event_timeline": [
                dict(item or {})
                for item in list(session.get("inherited_event_timeline", []) or [])
            ],
            "inherited_relation_timeline": [
                dict(item or {})
                for item in list(session.get("inherited_relation_timeline", []) or [])
            ],
        }

    def _refresh_latest_turn_checkpoint(
        self, run_id: str, session_id: str, session: dict[str, Any]
    ) -> None:
        records = self._completed_turn_records(run_id, session_id)
        if not records:
            return
        latest = records[-1]
        turn_id = str(latest.get("turn_id", "")).strip()
        if not turn_id:
            return
        result_path = self._turn_file(
            run_id, session_id, turn_id, "result"
        )
        if not result_path.exists():
            return
        result = dict(latest.get("result", {}) or {})
        result["checkpoint"] = self._build_turn_checkpoint(session)
        result["checkpoint_refreshed_at"] = _utc_now()
        self._write_json(result_path, result)

    def _build_turn_payload(
        self,
        run_manifest: dict[str, Any],
        session: dict[str, Any],
        *,
        turn_id: str,
        message: str,
        message_kind: str = "dialogue",
        speaker_override: str = "",
        include_inner_thoughts: bool = False,
    ) -> dict[str, Any]:
        participants = list(session.get("participants", []))
        mode = str(session.get("mode", "observe")).strip() or "observe"
        normalized_message_kind = self._normalize_message_kind(message_kind)
        speaker = str(speaker_override or "").strip() or (
            session.get("controlled_character", "")
            if mode == "act"
            else (
                session.get("self_insert", {}).get("display_name", "你")
                if mode == "insert"
                else "User"
            )
        )
        character_index = self._character_index(run_manifest)
        persona_map = {item["name"]: item for item in character_index}
        temporary_npcs = dict(session.get("temporary_npcs", {}) or {})
        for name in participants:
            if name in persona_map or name not in temporary_npcs:
                continue
            npc = dict(temporary_npcs.get(name, {}) or {})
            role = str(npc.get("role", "")).strip() or "Temporary NPC"
            personality = str(npc.get("personality", "")).strip()
            persona_map[name] = {
                "name": name,
                "preview": {
                    "display_name": name,
                    "core_identity": role,
                    "appearance_feature": str(npc.get("appearance", "")).strip(),
                    "speech_style": str(npc.get("speech_style", "")).strip(),
                },
                "profile": {
                    "display_name": name,
                    "core_identity": "；".join(
                        item for item in (role, personality) if item
                    ),
                    "story_role": role,
                    "appearance_feature": str(npc.get("appearance", "")).strip(),
                    "speech_style": str(npc.get("speech_style", "")).strip(),
                    "soul_goal": str(npc.get("motive", "")).strip(),
                },
            }
        relation_graph = dict(
            run_manifest.get("artifact_index", {}).get("relation_graph", {}) or {}
        )
        full_history = list(session.get("history", []))
        scene_progress = self._session_scene_progress(session)
        character_snapshots = self._session_character_snapshots(session)
        active_participants = self._resolve_active_participants(
            participants, full_history, mode, speaker, scene_progress
        )
        mentionable_participants = [
            name
            for name in active_participants
            if name and name != str(session.get("controlled_character", "")).strip()
        ]
        mention_targets = _speaker_balance.extract_mention_targets(
            mentionable_participants,
            message,
        )
        scene_card = dict(session.get("scene_card", {}) or {})
        transcript = self._serialize_transcript(session)

        persona_contexts = self._build_persona_contexts(
            participants=participants,
            active_participants=active_participants,
            persona_map=persona_map,
            mode=mode,
            controlled_character=str(session.get("controlled_character", "")).strip(),
            character_snapshots=character_snapshots,
        )

        latest_history = full_history[-8:]
        relation_excerpt = self._build_relation_excerpt(
            relation_graph.get("relations_file", ""),
            participants=participants,
            active_participants=active_participants,
            message=message,
            scene_card=scene_card,
        )
        session_relation_excerpt = self._build_session_relation_excerpt(
            session,
            participants=participants,
            active_participants=active_participants,
        )
        if session_relation_excerpt:
            relation_excerpt = (
                f"{relation_excerpt}\n\n# SESSION_RELATION_STATE\n{session_relation_excerpt}".strip()
                if relation_excerpt
                else f"# SESSION_RELATION_STATE\n{session_relation_excerpt}"
            )
        memory_context = self._build_turn_memory_context(
            run_id=str(run_manifest.get("run_id", "")).strip(),
            session=session,
            transcript=transcript,
            speaker=speaker,
            message=message,
            participants=participants,
            active_participants=active_participants,
            scene_card=scene_card,
            scene_progress=scene_progress,
        )
        original_source_context = self._build_original_source_context(
            run_manifest,
            message=message,
            participants=participants,
            active_participants=active_participants,
        )
        controlled_character_name = str(session.get("controlled_character", "")).strip()
        response_limit_hint = self._choose_response_limit_hint(
            mode=mode,
            active_count=len(active_participants),
            turn_id=turn_id,
            message_kind=normalized_message_kind,
        )
        required_response_slots = len(mention_targets) + (
            1 if normalized_message_kind == "plot" else 0
        )
        if required_response_slots:
            response_limit_hint = max(response_limit_hint, required_response_slots)
        response_count_rule = (
            f"Return 1-{response_limit_hint} in-world replies. "
            "Let only characters who are currently present respond; do not force every participant to speak each turn."
        )
        if normalized_message_kind == "plot":
            character_reply_limit = max(0, response_limit_hint - 1)
            response_count_rule = (
                "Return exactly one concrete scene-level beat first as 场景提示 or 旁白"
                + (
                    f", followed by 1-{character_reply_limit} present-character reactions."
                    if character_reply_limit
                    else "."
                )
            )
            if mode == "act" and controlled_character_name and character_reply_limit:
                response_count_rule += (
                    f" Other participants besides {controlled_character_name} must react when present; "
                    "do not return only the controlled character's line."
                )
        elif normalized_message_kind == "narration" and mode == "act" and controlled_character_name:
            response_lower_bound = min(
                response_limit_hint,
                max(1, min(2, len(active_participants))),
            )
            response_count_rule = (
                f"Return {response_lower_bound}-{response_limit_hint} in-world replies "
                f"when multiple cast members are present. Other participants besides {controlled_character_name} must speak; "
                "do not return only the controlled character's line."
            )
        instructions = {
            "mode": mode,
            "generation_goal": (
                "Materially advance the story while keeping every reply faithful to the persona bundle, relationship context, and scene mode."
                if normalized_message_kind == "plot"
                else "Keep every reply faithful to the persona bundle, relationship context, and scene mode."
            ),
            "mode_rule": self._mode_rule(mode, normalized_message_kind, controlled_character_name),
            "speaker_rule": self._speaker_rule(mode, session, normalized_message_kind),
            "response_style": self._response_style_rule(
                mode,
                normalized_message_kind,
                controlled_character_name,
            ),
            "scene_rule": self._scene_rule(scene_card),
            "progression_rule": self._scene_progress_rule(scene_progress),
            "plot_progression_contract": self._plot_progression_contract(
                normalized_message_kind,
                scene_progress,
            ),
            "response_count_rule": response_count_rule,
            "mention_rule": (
                f"The user directly addressed {', '.join(mention_targets)} with @. Every mentioned character is present and must reply in this turn before optional unmentioned cast members."
                if mention_targets
                else ""
            ),
            "temporary_npc_rule": (
                "When the scene genuinely needs a brief third-party intervention, you may introduce one named temporary NPC and give them a short in-character reply. "
                "Use a specific role-bearing name such as '店小二' or '巡夜人'; do not invent a protagonist, secret backstory, or a second NPC. "
                "Once introduced, that NPC remains available for later in-scene interaction until they leave."
            ),
        }
        include_inner_thoughts = bool(include_inner_thoughts)
        speaker_activity = _speaker_balance.build_speaker_activity(
            participants,
            self._completed_turn_records(
                str(run_manifest.get("run_id", "")).strip(),
                str(session.get("session_id", "")).strip(),
            ),
        )
        speaker_plan = _speaker_balance.build_speaker_plan(
            activity=speaker_activity,
            active_participants=active_participants,
            message=message,
            mode=mode,
            input_speaker=speaker,
            controlled_character=controlled_character_name,
            message_kind=normalized_message_kind,
            response_limit=response_limit_hint,
        )
        instructions["group_chat_rule"] = str(speaker_plan.get("rule", "")).strip()
        responder_hints = self._responder_hints(
            mode,
            active_participants,
            speaker,
            normalized_message_kind,
            controlled_character_name,
        )
        responder_hints = _speaker_balance.apply_plan_to_hints(
            responder_hints, speaker_plan
        )

        return {
            "kind": "zaomeng_dialogue_turn",
            "include_inner_thoughts": include_inner_thoughts,
            "run_id": run_manifest.get("run_id", ""),
            "session_id": session.get("session_id", ""),
            "turn_id": turn_id,
            "novel_id": run_manifest.get("novel_id", ""),
            "mode": mode,
            "input": {
                "speaker": speaker,
                "message": message,
                "message_kind": normalized_message_kind,
                "participants": participants,
                "active_participants": active_participants,
                "mention_targets": mention_targets,
                "controlled_character": session.get("controlled_character", ""),
                "scene_card": scene_card,
                "scene_progress": scene_progress,
                "character_snapshots": character_snapshots,
                "self_insert": dict(session.get("self_insert", {})),
            },
            "history": latest_history,
            "scene_card": scene_card,
            "memory_context": memory_context,
            "original_source_context": original_source_context,
            "knowledge_context": list(
                dict(session.get("consistency_monitor", {}) or {}).get(
                    "knowledge_ledger", []
                )
                or []
            ),
            "checkpoint_before": self._build_turn_checkpoint(session),
            "scene_progress": scene_progress,
            "persona_contexts": persona_contexts,
            "relation_context": {
                "graph": relation_graph,
                "relations_excerpt": relation_excerpt,
            },
            "instructions": instructions,
            "responder_hints": responder_hints,
            "speaker_activity": speaker_activity,
            "speaker_plan": speaker_plan,
            "host_action": {
                "expected_output": (
                    [
                        {
                            "speaker": "场景提示",
                            "message": "A concrete event or state change happening now.",
                        },
                        {
                            "speaker": "CharacterName",
                            "message": "An in-character reaction with a next hook.",
                            **(
                                {"inner_thought": "What the character thinks but does not say."}
                                if include_inner_thoughts
                                else {}
                            ),
                        },
                    ]
                    if normalized_message_kind == "plot"
                    else [
                        {
                            "speaker": "CharacterName",
                            "message": "...",
                            **(
                                {"inner_thought": "What the character thinks but does not say."}
                                if include_inner_thoughts
                                else {}
                            ),
                        }
                    ]
                ),
                "response_limit_hint": response_limit_hint,
                "output_rule": (
                    (
                        "Return the required scene-level beat first, then in-world character reactions. "
                        "The scene beat must materially change the situation; do not use it for a minor gesture or a summary of existing dialogue. "
                    )
                    if normalized_message_kind == "plot"
                    else (
                        "Return only in-world character replies. "
                        "Do not split obvious small actions into standalone narration; keep them inside the speaking character's line with brief parenthetical action. "
                    )
                )
                + (
                    "Do not explain the workflow or mention prompts."
                ),
            },
            "host_prompt_brief": self._host_prompt_brief(
                mode,
                speaker,
                participants,
                normalized_message_kind,
                controlled_character_name,
            ),
            "updated_at": _utc_now(),
        }

    _mode_rule = staticmethod(_prompt_rules._mode_rule)
    _speaker_rule = staticmethod(_prompt_rules._speaker_rule)
    _response_style_rule = staticmethod(_prompt_rules._response_style_rule)
    _scene_rule = staticmethod(_prompt_rules._scene_rule)
    _scene_progress_rule = staticmethod(_prompt_rules._scene_progress_rule)
    _plot_progression_contract = staticmethod(_prompt_rules._plot_progression_contract)
    _suggestion_mode_rule = staticmethod(_prompt_rules._suggestion_mode_rule)
    _suggestion_style_rule = staticmethod(_prompt_rules._suggestion_style_rule)
    _build_user_suggestion_persona = staticmethod(
        _prompt_rules._build_user_suggestion_persona
    )
    _responder_hints = staticmethod(_prompt_rules._responder_hints)
    _host_prompt_brief = staticmethod(_prompt_rules._host_prompt_brief)
    _host_suggestion_prompt_brief = staticmethod(
        _prompt_rules._host_suggestion_prompt_brief
    )
    _normalize_message_kind = staticmethod(_prompt_rules._normalize_message_kind)

    @classmethod
    def _resolve_active_participants(
        cls,
        participants: list[str],
        history: list[dict[str, Any]],
        mode: str,
        speaker: str,
        scene_progress: dict[str, Any] | None = None,
    ) -> list[str]:
        deduped: list[str] = []
        seen: set[str] = set()
        for name in participants:
            normalized = str(name or "").strip()
            if not normalized or normalized in seen:
                continue
            seen.add(normalized)
            deduped.append(normalized)
        if not deduped:
            return []

        state_present = [
            str(item).strip()
            for item in list(
                dict(scene_progress or {}).get("present_participants", []) or []
            )
            if str(item).strip() in deduped
        ]
        state_offstage = {
            str(item).strip()
            for item in list(
                dict(scene_progress or {}).get("offstage_participants", []) or []
            )
            if str(item).strip() in deduped
        }
        departed = _scene_signals.infer_departed_participants(deduped, history)
        if state_present:
            active = [
                name
                for name in state_present
                if name not in state_offstage and name not in departed
            ]
            if mode == "act":
                active = [name for name in active if name != speaker]
            if active:
                return active

        active = [name for name in deduped if name not in departed]
        if mode == "act":
            active = [name for name in active if name != speaker]
        if active:
            return active
        # Never end up with an empty speaker pool.
        fallback = [name for name in deduped if not (mode == "act" and name == speaker)]
        return fallback or deduped[:1]

    @staticmethod
    def _register_temporary_npc_speakers(
        session: dict[str, Any],
        responses: list[dict[str, Any]],
        pending_payload: dict[str, Any],
    ) -> list[str]:
        """Promote generated walk-on speakers into this session's cast only."""
        participants = [
            str(item).strip()
            for item in list(session.get("participants", []) or [])
            if str(item).strip()
        ]
        known = set(participants)
        input_payload = dict(pending_payload.get("input", {}) or {})
        narrative_speakers = {"旁白", "场景提示"}
        forbidden_speakers = {
            "User",
            "你",
            "模型推理",
            "system",
            "assistant",
            str(session.get("controlled_character", "")).strip(),
            str(input_payload.get("speaker", "")).strip(),
        }
        forbidden_speakers = {
            name.casefold() for name in forbidden_speakers if name
        }
        catalogue = dict(session.get("temporary_npcs", {}) or {})
        added: list[str] = []
        accepted: list[dict[str, Any]] = []
        for response in responses:
            name = str(response.get("speaker", "")).strip()
            normalized_name = name.casefold()
            if (
                not name
                or len(name) > 40
                or "\n" in name
                or "\r" in name
            ):
                continue
            if name in known or name in narrative_speakers:
                accepted.append(response)
                continue
            if normalized_name in forbidden_speakers or added:
                continue
            participants.append(name)
            known.add(name)
            added.append(name)
            accepted.append(response)
            catalogue[name] = {
                "name": name,
                "introduced_at": _utc_now(),
                "status": "active",
            }
        responses[:] = accepted
        if not added:
            return []

        session["participants"] = participants
        session["temporary_npcs"] = catalogue
        input_payload["participants"] = list(participants)
        active = [
            str(item).strip()
            for item in list(input_payload.get("active_participants", []) or [])
            if str(item).strip()
        ]
        input_payload["active_participants"] = [*active, *added]
        pending_payload["input"] = input_payload
        return added

    @staticmethod
    def _event_mentions_temporary_npc(
        event: dict[str, Any], temporary_names: set[str]
    ) -> bool:
        if not temporary_names:
            return False
        fields = (
            str(event.get("actor", "")).strip(),
            str(event.get("target", "")).strip(),
            str(event.get("cue", "")).strip(),
        )
        return any(
            name and any(name in field for field in fields)
            for name in temporary_names
        )

    @staticmethod
    def _knowledge_mentions_temporary_npc(
        item: dict[str, Any], temporary_names: set[str]
    ) -> bool:
        if not temporary_names:
            return False
        holders = {
            str(name).strip()
            for key in ("holders", "knowers")
            for name in list(dict(item or {}).get(key, []) or [])
            if str(name).strip()
        }
        summary = str(
            dict(item or {}).get("secret", "")
            or dict(item or {}).get("summary", "")
        ).strip()
        return bool(holders & temporary_names) or any(
            name in summary for name in temporary_names
        )

    def _merge_scene_progress_state(self, session: dict[str, Any], incoming: dict[str, Any]) -> dict[str, Any]:
        return _scene_progress.merge_scene_progress_state(
            session,
            incoming,
            transcript=self._serialize_transcript(session),
            state_version=self.SESSION_STATE_VERSION,
        )

    def _derive_scene_progress_state(
        self,
        session: dict[str, Any],
        transcript: list[dict[str, Any]],
    ) -> dict[str, Any]:
        return _scene_progress.derive_scene_progress_state(
            session,
            transcript,
            state_version=self.SESSION_STATE_VERSION,
        )


    @staticmethod
    def _choose_response_limit_hint(
        *, mode: str, active_count: int, turn_id: str, message_kind: str
    ) -> int:
        if active_count <= 0:
            return 1
        seed = sum(ord(ch) for ch in str(turn_id or ""))
        rng = random.Random(seed)
        if message_kind == "plot" and active_count == 1:
            return 2
        if mode == "observe":
            if message_kind == "plot":
                upper = min(5, max(3, active_count + 1))
                lower = min(upper, 2 if active_count <= 2 else 3)
            else:
                upper = min(4, max(2, active_count))
                lower = 3 if active_count >= 4 else 2
                if message_kind == "narration":
                    upper = min(5, max(upper, 3))
                    lower = min(upper, 2 if active_count <= 2 else 3)
            return rng.randint(lower, upper)
        if message_kind == "plot" and mode in {"act", "insert"}:
            upper = min(5, max(2, active_count + 1))
            lower = 3 if active_count >= 2 else 2
            return rng.randint(lower, upper)
        if message_kind == "narration" and mode in {"act", "insert"}:
            upper = min(4, max(1, active_count))
            lower = 2 if active_count >= 2 else 1
            return rng.randint(lower, upper)
        upper = min(3, max(1, active_count))
        lower = 1 if active_count <= 1 else 2
        return rng.randint(lower, upper)

    @staticmethod
    def _load_text_excerpt(path_text: str, *, limit: int) -> str:
        return _relation_excerpt.load_text_excerpt(path_text, limit=limit)

    @staticmethod
    def _pair_key(left: str, right: str) -> str:
        return _relation_state.pair_key(left, right)

    @staticmethod
    def _default_relation_entry() -> dict[str, Any]:
        return _relation_state.default_relation_entry()

    @classmethod
    def _normalize_relation_entry(cls, raw: dict[str, Any] | None) -> dict[str, Any]:
        return _relation_state.normalize_relation_entry(raw)

    def _seed_relation_matrix(
        self, run_manifest: dict[str, Any], participants: list[str]
    ) -> dict[str, Any]:
        relation_graph = dict(
            run_manifest.get("artifact_index", {}).get("relation_graph", {}) or {}
        )
        relation_path = Path(str(relation_graph.get("relations_file", "")).strip())
        if not relation_path.exists():
            return {}
        try:
            payload = load_relations_source(relation_path)
        except Exception:
            return {}
        relations = dict(payload.get("relations", {}) or {})
        return _relation_state.seed_relation_matrix(relations, participants)

    def _merged_relation_matrix(
        self, session: dict[str, Any], participants: list[str]
    ) -> dict[str, Any]:
        return _relation_state.merged_relation_matrix(
            self._session_relation_matrix(session),
            self._session_relation_delta(session),
            participants,
        )

    @staticmethod
    def _empty_event_signals_state() -> dict[str, Any]:
        return _state_utils.empty_event_signals_state()

    def _merge_event_signals_state(
        self, session: dict[str, Any], incoming: list[dict[str, Any]]
    ) -> dict[str, Any]:
        return _event_signals.merge_event_signals_state(
            self._session_event_signals(session),
            incoming,
            participants=list(session.get("participants", []) or []),
            updated_at=_utc_now(),
        )

    def _latest_event_signal(
        self, session: dict[str, Any], *kinds: str
    ) -> dict[str, Any]:
        return _event_signals.latest_event_signal(
            self._session_event_signals(session), *kinds
        )

    def _build_session_relation_excerpt(
        self,
        session: dict[str, Any],
        *,
        participants: list[str],
        active_participants: list[str],
    ) -> str:
        deltas = self._session_relation_delta(session)
        if not deltas:
            return ""
        merged = self._merged_relation_matrix(session, participants)
        focus_keys: list[str] = []
        focus_names = [
            str(item).strip()
            for item in [*active_participants, *participants]
            if str(item).strip()
        ]
        for index, left in enumerate(focus_names):
            for right in focus_names[index + 1 :]:
                pair_key = self._pair_key(left, right)
                if pair_key and pair_key not in focus_keys:
                    focus_keys.append(pair_key)
        lines: list[str] = []
        for pair_key in focus_keys:
            delta = dict(deltas.get(pair_key, {}) or {})
            if not delta:
                continue
            relation = dict(merged.get(pair_key, {}) or {})
            metric_bits: list[str] = []
            for field, label in (
                ("trust", "信任"),
                ("affection", "好感"),
                ("hostility", "敌意"),
                ("ambiguity", "暧昧/摇摆"),
            ):
                change = int(delta.get(field, 0) or 0)
                if change:
                    metric_bits.append(f"{label}{change:+d}")
            if not metric_bits:
                continue
            status_bits = [
                f"trust={int(relation.get('trust', 5) or 5)}",
                f"affection={int(relation.get('affection', 5) or 5)}",
                f"hostility={int(relation.get('hostility', 0) or 0)}",
                f"ambiguity={int(relation.get('ambiguity', 3) or 3)}",
            ]
            line = f"## {pair_key}\n- session_delta: {', '.join(metric_bits)}\n- merged_state: {', '.join(status_bits)}"
            last_event = str(delta.get("last_event", "")).strip()
            if last_event:
                line = (
                    f"{line}\n- last_event: {self._trim_summary_text(last_event, 120)}"
                )
            last_actor = str(delta.get("last_actor", "")).strip()
            last_target = str(delta.get("last_target", "")).strip()
            if last_actor or last_target:
                line = f"{line}\n- drift: {self._trim_summary_text(' -> '.join([item for item in (last_actor, last_target) if item]), 80)}"
            lines.append(line)
            if len("\n".join(lines)) >= 1200:
                break
        return "\n".join(lines).strip()

    def _build_session_event_excerpt(
        self, session: dict[str, Any]
    ) -> list[dict[str, Any]]:
        return _event_signals.build_session_event_excerpt(
            self._session_event_signals(session)
        )

    def _build_persona_contexts(
        self,
        *,
        participants: list[str],
        active_participants: list[str],
        persona_map: dict[str, dict[str, Any]],
        mode: str,
        controlled_character: str,
        character_snapshots: dict[str, Any] | None = None,
    ) -> list[dict[str, Any]]:
        return _persona_context.build_persona_contexts(
            participants=participants,
            active_participants=active_participants,
            persona_map=persona_map,
            mode=mode,
            controlled_character=controlled_character,
            character_snapshots=character_snapshots,
        )

    @staticmethod
    def _load_persona_profile(meta: dict[str, Any]) -> tuple[dict[str, Any], Path]:
        return _persona_context.load_persona_profile(meta)

    @staticmethod
    def _persona_preview_payload(
        meta: dict[str, Any], normalized_profile: dict[str, Any]
    ) -> dict[str, Any]:
        return _persona_context.persona_preview_payload(meta, normalized_profile)

    @staticmethod
    def _persona_profile_payload(
        normalized_profile: dict[str, Any], *, detailed: bool
    ) -> dict[str, Any]:
        return _persona_context.persona_profile_payload(
            normalized_profile, detailed=detailed
        )

    @staticmethod
    def _persona_snapshot_payload(
        snapshot: dict[str, Any], *, detailed: bool
    ) -> dict[str, Any]:
        return _persona_context.persona_snapshot_payload(snapshot, detailed=detailed)

    def _build_relation_excerpt(
        self,
        path_text: str,
        *,
        participants: list[str],
        active_participants: list[str],
        message: str,
        scene_card: dict[str, Any],
    ) -> str:
        return _relation_excerpt.build_relation_excerpt(
            path_text,
            participants=participants,
            active_participants=active_participants,
            message=message,
            scene_card=scene_card,
        )

    @staticmethod
    def _choose_relation_excerpt_limit(
        *, participants: list[str], active_participants: list[str]
    ) -> int:
        return _relation_excerpt.choose_relation_excerpt_limit(
            participants=participants,
            active_participants=active_participants,
        )

    @staticmethod
    def _choose_relation_excerpt_scan_limit(
        *, participants: list[str], active_participants: list[str]
    ) -> int:
        return _relation_excerpt.choose_relation_excerpt_scan_limit(
            participants=participants,
            active_participants=active_participants,
        )

    @staticmethod
    def _extract_relevant_relation_excerpt(
        text: str, focus_terms: list[str], limit: int
    ) -> str:
        return _relation_excerpt.extract_relevant_relation_excerpt(
            text, focus_terms, limit
        )

    def _build_turn_memory_context(
        self,
        *,
        run_id: str,
        session: dict[str, Any],
        transcript: list[dict[str, Any]],
        speaker: str,
        message: str,
        participants: list[str],
        active_participants: list[str],
        scene_card: dict[str, Any],
        scene_progress: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        session_summary = self._build_session_memory_summary(run_id, session, transcript)
        context = _turn_memory.build_turn_memory_context(
            state_summary=self._session_memory_summary_state(session),
            scene_progress=scene_progress,
            character_snapshots=self._session_character_snapshots(session),
            relation_delta=self._session_relation_delta(session),
            event_signals=self._build_session_event_excerpt(session),
            session_summary=session_summary,
            memory_hits=[],
        )
        context["retrieved_memories"] = self._search_turn_memory_hits(
            run_id=run_id,
            session_id=str(session.get("session_id", "")).strip(),
            speaker=speaker,
            message=message,
            participants=participants,
            active_participants=active_participants,
            scene_card=scene_card,
            session_summary=session_summary,
            scene_progress=dict(context.get("scene_progress", {}) or {}),
        )
        controlled_memories = [
            dict(item or {})
            for item in list(session.get("memory_ledger", []) or [])
            if isinstance(item, dict)
            and bool(item.get("enabled", True))
            and str(item.get("text", "")).strip()
        ]
        controlled_memories.sort(
            key=lambda item: (
                0 if bool(item.get("pinned", False)) else 1,
                str(item.get("updated_at", "")),
            )
        )
        context["controlled_memories"] = [
            {
                "memory_id": str(item.get("memory_id", "")).strip(),
                "text": _text_utils.trim_summary_text(
                    str(item.get("text", "")).strip(), 500
                ),
                "category": str(item.get("category", "story")).strip() or "story",
                "pinned": bool(item.get("pinned", False)),
            }
            for item in controlled_memories[:20]
        ]
        context["world_facts"] = self._world_memory.relevant_facts(
            run_id, participants=participants, message=message, limit=18
        )
        return context

    def _build_original_source_context(
        self,
        run_manifest: dict[str, Any],
        *,
        message: str,
        participants: list[str],
        active_participants: list[str],
    ) -> dict[str, Any]:
        try:
            entries = self._original_knowledge.search(
                run_manifest,
                query=message,
                participants=participants,
                active_participants=active_participants,
                limit=3,
            )
        except (FileNotFoundError, OSError, UnicodeError, ValueError):
            entries = []
        return {
            "entries": entries,
            "policy": {
                "grounding": "Prefer explicit source evidence over model prior knowledge.",
                "character_boundary": (
                    "A character may assert a passage only when listed in allowed_characters. "
                    "Passages with visibility=uncertain are narration-only and must not become character knowledge."
                ),
                "citation": "Track retrieved context internally; never expose source text in replies.",
            },
        }

    @staticmethod
    def _build_context_usage(payload: dict[str, Any]) -> dict[str, Any]:
        memory_context = dict(payload.get("memory_context", {}) or {})
        input_payload = dict(payload.get("input", {}) or {})
        controlled = list(memory_context.get("controlled_memories", []) or [])
        retrieved = list(memory_context.get("retrieved_memories", []) or [])
        knowledge = list(payload.get("knowledge_context", []) or [])
        original_entries = list(
            dict(payload.get("original_source_context", {}) or {}).get("entries", [])
            or []
        )
        relation_delta = dict(memory_context.get("relation_delta", {}) or {})
        character_snapshots = dict(
            memory_context.get("character_snapshots", {}) or {}
        )
        event_signals = list(memory_context.get("event_signals", []) or [])
        archived = dict(memory_context.get("archived_summary", {}) or {})
        session_summary = dict(memory_context.get("session_summary", {}) or {})
        sources = [
            {
                "kind": "short_term",
                "label": "近期对话",
                "count": min(len(list(payload.get("history", []) or [])), 6),
            },
            {
                "kind": "long_term",
                "label": "长期记忆检索",
                "count": min(len(retrieved), 2),
                "items": [str(item.get("text", "")).strip() for item in retrieved[:2]],
            },
            {
                "kind": "controlled",
                "label": "用户管理的记忆",
                "count": min(len(controlled), 20),
                "items": [str(item.get("text", "")).strip() for item in controlled[:20]],
            },
            {
                "kind": "story",
                "label": "剧情与场景状态",
                "count": min(len(event_signals), 3)
                + int(bool(dict(memory_context.get("scene_progress", {}) or {}))),
            },
            {
                "kind": "relationship",
                "label": "人物与关系状态",
                "count": len(relation_delta) + len(character_snapshots),
            },
            {
                "kind": "knowledge",
                "label": "知识边界",
                "count": len(knowledge),
            },
            {
                "kind": "original_source",
                "label": "原作动态检索",
                "count": len(original_entries),
                "items": [
                    str(item.get("title", "")).strip()
                    for item in original_entries[:6]
                ],
            },
            {
                "kind": "summary",
                "label": "会话摘要",
                "count": int(bool(session_summary)) + int(bool(archived)),
            },
        ]
        return {
            "turn_id": str(payload.get("turn_id", "")).strip(),
            "speaker": str(input_payload.get("speaker", "")).strip(),
            "sources": [
                item for item in sources if int(item.get("count", 0) or 0) > 0
            ],
            "used_at": _utc_now(),
        }

    def _search_turn_memory_hits(
        self,
        *,
        run_id: str,
        session_id: str,
        speaker: str,
        message: str,
        participants: list[str],
        active_participants: list[str],
        scene_card: dict[str, Any],
        session_summary: dict[str, Any],
        scene_progress: dict[str, Any],
    ) -> list[dict[str, Any]]:
        return _turn_memory.search_turn_memory_hits(
            self._resolve_memory_store(run_id),
            session_id=session_id,
            speaker=speaker,
            message=message,
            participants=participants,
            active_participants=active_participants,
            scene_card=scene_card,
            session_summary=session_summary,
            scene_progress=scene_progress,
        )


    @staticmethod
    def _character_index(run_manifest: dict[str, Any]) -> list[dict[str, Any]]:
        return list(run_manifest.get("artifact_index", {}).get("characters", []) or [])

    def _serialize_session(
        self, run_id: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        session = dict(payload)
        session_id = str(session.get("session_id", "")).strip()
        turn_records = self._completed_turn_records(run_id, session_id)
        session["file_urls"] = self._build_file_urls(run_id, session)
        session["character_avatars"] = {
            str(name).strip(): avatar_version(avatar_path(self.runs_root / run_id, str(name)))
            for name in list(session.get("participants", []) or [])
            if str(name).strip()
        }
        session["mode_display"] = self._mode_display(
            str(session.get("mode", "")).strip()
        )
        transcript = self._serialize_transcript(session)
        self._attach_turn_ids_to_transcript(transcript, turn_records)
        session["transcript"] = transcript
        session["scene_progress"] = self._session_scene_progress(session)
        session["relation_delta"] = self._session_relation_delta(session)
        session["character_snapshots"] = self._session_character_snapshots(session)
        session["event_signals"] = self._session_event_signals(session)
        session["relation_matrix"] = self._merged_relation_matrix(
            session, list(session.get("participants", []) or [])
        )
        session["last_entry_preview"] = self._build_last_entry_preview(session)
        session["session_card"] = self._build_session_card(session)
        session["scene_history"] = self._serialize_scene_history(session)
        session["event_timeline"] = self._serialize_event_timeline(
            run_id, session, records=turn_records
        )
        session["branch_meta"] = dict(session.get("branch_meta", {}) or {})
        locked_event_ids = {
            str(item).strip()
            for item in list(session["branch_meta"].get("locked_event_ids", []) or [])
            if str(item).strip()
        }
        for event in session["event_timeline"]:
            event["is_mainline_anchor"] = (
                str(event.get("turn_id", "")).strip() in locked_event_ids
            )
        session["branch_graph"] = self._build_branch_graph(
            run_id, session, current_records=turn_records
        )
        session["relation_timeline"] = self._serialize_relation_timeline(
            run_id, session, records=turn_records
        )
        session["relation_locks"] = dict(session.get("relation_locks", {}) or {})
        session["memory_ledger"] = [
            dict(item or {})
            for item in list(session.get("memory_ledger", []) or [])
            if isinstance(item, dict)
        ]
        session["latest_context_usage"] = dict(
            session.get("latest_context_usage", {}) or {}
        )
        session["speaker_activity"] = _speaker_balance.build_speaker_activity(
            list(session.get("participants", []) or []),
            turn_records,
        )
        current_progress = self._session_scene_progress(session)
        active_for_plan = [
            str(item).strip()
            for item in list(current_progress.get("present_participants", []) or [])
            if str(item).strip()
        ] or list(session.get("participants", []) or [])
        mode = str(session.get("mode", "observe")).strip() or "observe"
        input_speaker = (
            str(session.get("controlled_character", "")).strip()
            if mode == "act"
            else str(dict(session.get("self_insert", {}) or {}).get("display_name", "")).strip()
            if mode == "insert"
            else "User"
        )
        session["speaker_balance"] = _speaker_balance.build_speaker_plan(
            activity=session["speaker_activity"],
            active_participants=active_for_plan,
            message="",
            mode=mode,
            input_speaker=input_speaker,
            controlled_character=str(session.get("controlled_character", "")).strip(),
            message_kind="dialogue",
            response_limit=min(3, max(1, len(active_for_plan))),
        )
        session["branch_origin"] = dict(session.get("branch_origin", {}) or {})
        session["pending_turn_summary"] = self._build_pending_turn_summary(session)
        session["session_memory_summary"] = self._build_session_memory_summary(
            run_id, session, transcript
        )
        session["chapter_outline"] = _chapter_outline.build_chapter_outline(
            session["scene_history"],
            session["event_timeline"],
            session_summary=session["session_memory_summary"],
        )
        session["character_arcs"] = self._serialize_character_arcs(
            run_id, session, records=turn_records
        )
        runtime_overview = self._build_runtime_state_overview(session)
        session["runtime_state_overview"] = runtime_overview
        session["story_recap"] = _story_recap.build_story_recap(
            session=session,
            transcript=transcript,
            chapter_outline=session["chapter_outline"],
            event_timeline=session["event_timeline"],
            relation_timeline=session["relation_timeline"],
            character_arcs=session["character_arcs"],
            runtime_state_overview=runtime_overview,
            session_memory_summary=session["session_memory_summary"],
        )
        monitor = dict(session.get("consistency_monitor", {}) or {})
        monitor_history = list(monitor.get("history", []) or [])
        if monitor_history:
            monitor["metrics"] = _consistency.build_monitor_metrics(monitor_history)
            session["consistency_monitor"] = monitor
        return session

    @staticmethod
    def _attach_turn_ids_to_transcript(
        transcript: list[dict[str, Any]],
        records: list[dict[str, Any]],
    ) -> None:
        """Backfill turn ids for sessions created before history entries carried them."""

        search_from = 0
        for record in records:
            turn_id = str(record.get("turn_id", "")).strip()
            if not turn_id:
                continue
            payload = dict(record.get("payload", {}) or {})
            result = dict(record.get("result", {}) or {})
            input_payload = dict(payload.get("input", {}) or {})
            candidates = [
                (
                    str(input_payload.get("speaker", "")).strip(),
                    str(input_payload.get("message", "")).strip(),
                    str(record.get("updated_at", "")).strip(),
                )
            ]
            candidates.extend(
                (
                    str(item.get("speaker", "")).strip(),
                    str(item.get("message", "")).strip(),
                    str(item.get("ts", record.get("updated_at", ""))).strip(),
                )
                for item in list(result.get("responses", []) or [])
                if isinstance(item, dict)
            )
            for speaker, message, timestamp in candidates:
                if not speaker or not message:
                    continue
                matched_index = next(
                    (
                        index
                        for index in range(search_from, len(transcript))
                        if not str(transcript[index].get("turn_id", "")).strip()
                        and str(transcript[index].get("speaker", "")).strip() == speaker
                        and str(transcript[index].get("message", "")).strip() == message
                    ),
                    -1,
                )
                if matched_index < 0:
                    continue
                transcript[matched_index]["turn_id"] = turn_id
                if not str(transcript[matched_index].get("timestamp", "")).strip():
                    transcript[matched_index]["timestamp"] = timestamp
                search_from = matched_index + 1

    def _completed_turn_records(
        self, run_id: str, session_id: str
    ) -> list[dict[str, Any]]:
        turn_dir = self._session_dir(run_id, session_id) / "turns"
        records: list[dict[str, Any]] = []
        if not turn_dir.exists():
            return records
        for result_path in turn_dir.glob("*.result.json"):
            result = self._read_json(result_path)
            turn_id = str(result.get("turn_id", "")).strip()
            if not turn_id:
                continue
            try:
                payload_path = self._turn_file(
                    run_id, session_id, turn_id, "payload"
                )
            except InvalidStorageIdentifier:
                continue
            payload = self._read_json(payload_path) if payload_path.exists() else {}
            records.append(
                {
                    "turn_id": turn_id,
                    "payload": payload,
                    "result": result,
                    "checkpoint": dict(result.get("checkpoint", {}) or {}),
                    "updated_at": str(result.get("updated_at", "")).strip(),
                }
            )
        records.sort(key=lambda item: (item.get("updated_at", ""), item.get("turn_id", "")))
        return records

    @staticmethod
    def _event_identity(item: dict[str, Any]) -> tuple[str, str, str, str]:
        return tuple(
            str(item.get(key, "")).strip()
            for key in ("kind", "actor", "target", "cue")
        )

    def _turn_events(self, record: dict[str, Any]) -> list[dict[str, Any]]:
        payload = dict(record.get("payload", {}) or {})
        result = dict(record.get("result", {}) or {})
        checkpoint_before = dict(payload.get("checkpoint_before", {}) or {})
        before_items = list(
            dict(checkpoint_before.get("event_signals", {}) or {}).get("recent", [])
            or []
        ) or list(
            dict(payload.get("memory_context", {}) or {}).get("event_signals", [])
            or []
        )
        before = {
            self._event_identity(dict(item or {}))
            for item in before_items
            if isinstance(item, dict)
        }
        checkpoint = dict(result.get("checkpoint", {}) or {})
        recent = list(
            dict(checkpoint.get("event_signals", {}) or {}).get("recent", []) or []
        )
        return [
            dict(item or {})
            for item in recent
            if isinstance(item, dict) and self._event_identity(dict(item or {})) not in before
        ][-6:]

    def _serialize_event_timeline(
        self,
        run_id: str,
        session: dict[str, Any],
        *,
        records: list[dict[str, Any]] | None = None,
    ) -> list[dict[str, Any]]:
        session_id = str(session.get("session_id", "")).strip()
        items: list[dict[str, Any]] = []
        for raw_item in list(session.get("inherited_event_timeline", []) or []):
            if not isinstance(raw_item, dict):
                continue
            inherited = dict(raw_item)
            inherited["inherited"] = True
            inherited["can_branch"] = bool(
                str(inherited.get("source_session_id", "")).strip()
                and str(inherited.get("turn_id", "")).strip()
            )
            items.append(inherited)
        turn_offset = len(items)
        completed_records = (
            records
            if records is not None
            else self._completed_turn_records(run_id, session_id)
        )
        for index, record in enumerate(completed_records):
            payload = dict(record.get("payload", {}) or {})
            result = dict(record.get("result", {}) or {})
            input_payload = dict(payload.get("input", {}) or {})
            responses = [
                dict(item or {})
                for item in list(result.get("responses", []) or [])
                if isinstance(item, dict)
            ]
            events = self._turn_events(record)
            cue = next(
                (str(item.get("cue", "")).strip() for item in events if str(item.get("cue", "")).strip()),
                "",
            )
            user_message = str(input_payload.get("message", "")).strip()
            title = cue or user_message or f"第 {turn_offset + index + 1} 轮剧情推进"
            speakers = [str(input_payload.get("speaker", "")).strip()]
            speakers.extend(str(item.get("speaker", "")).strip() for item in responses)
            speakers.extend(str(item.get("actor", "")).strip() for item in events)
            speakers.extend(str(item.get("target", "")).strip() for item in events)
            scene_progress = dict(
                dict(result.get("checkpoint", {}) or {}).get("scene_progress", {})
                or payload.get("scene_progress", {})
                or {}
            )
            report = dict(result.get("consistency_report", {}) or {})
            items.append(
                {
                    "turn_id": str(record.get("turn_id", "")).strip(),
                    "turn_number": turn_offset + index + 1,
                    "source_session_id": session_id,
                    "inherited": False,
                    "title": _text_utils.trim_summary_text(title, 100),
                    "user_message": _text_utils.trim_summary_text(user_message, 120),
                    "responses": [
                        {
                            "speaker": str(item.get("speaker", "")).strip(),
                            "message": _text_utils.trim_summary_text(
                                str(item.get("message", "")).strip(), 140
                            ),
                        }
                        for item in responses
                    ],
                    "events": events,
                    "participants": list(dict.fromkeys(name for name in speakers if name)),
                    "event_types": list(
                        dict.fromkeys(
                            str(item.get("kind", "")).strip()
                            for item in events
                            if str(item.get("kind", "")).strip()
                        )
                    ) or ["dialogue"],
                    "location": next(
                        (
                            str(item.get("location_hint", "")).strip()
                            for item in events
                            if str(item.get("location_hint", "")).strip()
                        ),
                        str(scene_progress.get("location", "")).strip(),
                    ),
                    "time_hint": next(
                        (
                            str(item.get("time_hint", "")).strip()
                            for item in events
                            if str(item.get("time_hint", "")).strip()
                        ),
                        str(scene_progress.get("time_hint", "")).strip(),
                    ),
                    "consistency_status": str(report.get("status", "pass")).strip() or "pass",
                    "updated_at": str(record.get("updated_at", "")).strip(),
                    "can_branch": True,
                }
            )
        return items

    def _serialize_relation_timeline(
        self,
        run_id: str,
        session: dict[str, Any],
        *,
        records: list[dict[str, Any]] | None = None,
    ) -> list[dict[str, Any]]:
        participants = [
            str(item).strip()
            for item in list(session.get("participants", []) or [])
            if str(item).strip()
        ]
        base_matrix = self._session_relation_matrix(session)
        current_delta = self._session_relation_delta(session)
        locks = dict(session.get("relation_locks", {}) or {})
        inherited_map = {
            str(item.get("pair_key", "")).strip(): dict(item or {})
            for item in list(session.get("inherited_relation_timeline", []) or [])
            if isinstance(item, dict) and str(item.get("pair_key", "")).strip()
        }
        pair_keys = set(base_matrix) | set(current_delta) | set(inherited_map)
        for index, left in enumerate(participants):
            for right in participants[index + 1 :]:
                key = self._pair_key(left, right)
                if key:
                    pair_keys.add(key)
        records = (
            records
            if records is not None
            else self._completed_turn_records(
                run_id, str(session.get("session_id", "")).strip()
            )
        )
        metric_fields = ("trust", "affection", "hostility", "ambiguity")
        timelines: list[dict[str, Any]] = []
        baseline_matrix = _relation_state.merged_relation_matrix(
            base_matrix, {}, participants
        )
        for key in sorted(pair_keys):
            baseline = dict(
                baseline_matrix.get(
                    key, _relation_state.default_relation_entry()
                )
                or {}
            )
            inherited = dict(inherited_map.get(key, {}) or {})
            points = [
                dict(item or {})
                for item in list(inherited.get("points", []) or [])
                if isinstance(item, dict)
            ]
            if points:
                previous = {
                    field: int(
                        dict(points[-1].get("values", {}) or {}).get(
                            field, baseline.get(field, 0)
                        )
                        or 0
                    )
                    for field in metric_fields
                }
            else:
                previous = {
                    field: int(baseline.get(field, 0) or 0)
                    for field in metric_fields
                }
                points = [
                    {
                        "turn_number": 0,
                        "turn_id": "",
                        "values": dict(previous),
                        "changes": {field: 0 for field in metric_fields},
                        "reason": "人物包中的初始关系",
                        "evidence": "",
                        "updated_at": str(session.get("created_at", "")).strip(),
                    }
                ]
            next_turn_number = max(
                [int(item.get("turn_number", 0) or 0) for item in points]
                or [0]
            ) + 1
            for turn_number, record in enumerate(records, start=next_turn_number):
                checkpoint = dict(record.get("checkpoint", {}) or {})
                if not checkpoint:
                    continue
                matrix = dict(checkpoint.get("relation_matrix", {}) or base_matrix)
                delta = dict(checkpoint.get("relation_delta", {}) or {})
                merged = _relation_state.merged_relation_matrix(
                    matrix, delta, participants
                )
                relation = dict(merged.get(key, baseline) or {})
                values = {
                    field: int(relation.get(field, previous[field]) or 0)
                    for field in metric_fields
                }
                changes = {
                    field: values[field] - previous[field]
                    for field in metric_fields
                }
                delta_entry = dict(delta.get(key, {}) or {})
                evidence_lines = list(delta_entry.get("evidence_lines", []) or [])
                reason = str(
                    delta_entry.get("relation_change", "")
                    or delta_entry.get("last_event", "")
                ).strip()
                points.append(
                    {
                        "turn_number": turn_number,
                        "turn_id": str(record.get("turn_id", "")).strip(),
                        "values": values,
                        "changes": changes,
                        "reason": _text_utils.trim_summary_text(
                            reason
                            or (
                                "关系保持稳定"
                                if not any(changes.values())
                                else "本轮互动推动了关系变化"
                            ),
                            140,
                        ),
                        "evidence": _text_utils.trim_summary_text(
                            str(evidence_lines[-1]).strip()
                            if evidence_lines
                            else "",
                            160,
                        ),
                        "updated_at": str(record.get("updated_at", "")).strip(),
                    }
                )
                previous = values
            if len(points) == 1 and not inherited and key in current_delta:
                merged = _relation_state.merged_relation_matrix(
                    base_matrix, current_delta, participants
                )
                relation = dict(merged.get(key, baseline) or {})
                values = {
                    field: int(relation.get(field, previous[field]) or 0)
                    for field in metric_fields
                }
                delta_entry = dict(current_delta.get(key, {}) or {})
                points.append(
                    {
                        "turn_number": 1,
                        "turn_id": "",
                        "values": values,
                        "changes": {
                            field: values[field] - previous[field]
                            for field in metric_fields
                        },
                        "reason": _text_utils.trim_summary_text(
                            str(delta_entry.get("last_event", "")).strip()
                            or "当前会话中的累计变化",
                            140,
                        ),
                        "evidence": "",
                        "updated_at": str(session.get("updated_at", "")).strip(),
                    }
                )
            names = key.split("_", 1)
            timelines.append(
                {
                    "pair_key": key,
                    "characters": names,
                    "label": " · ".join(names),
                    "locked": bool(locks.get(key, False)),
                    "current": dict(points[-1].get("values", {}) or {}),
                    "points": points,
                }
            )
        return timelines

    def _build_legacy_turn_checkpoint(
        self,
        source: dict[str, Any],
        records: list[dict[str, Any]],
        target_index: int,
    ) -> dict[str, Any]:
        target_result = dict(records[target_index].get("result", {}) or {})
        responses = list(target_result.get("responses", []) or [])
        history = [dict(item or {}) for item in list(source.get("history", []) or [])]
        end_index = -1
        if responses:
            tail = dict(responses[-1] or {})
            for index, item in enumerate(history):
                if (
                    str(item.get("speaker", "")).strip() == str(tail.get("speaker", "")).strip()
                    and str(item.get("message", "")).strip() == str(tail.get("message", "")).strip()
                    and (
                        not str(tail.get("ts", "")).strip()
                        or str(item.get("ts", "")).strip() == str(tail.get("ts", "")).strip()
                    )
                ):
                    end_index = index
        if end_index >= 0:
            history = history[: end_index + 1]
        state_source = source
        if target_index + 1 < len(records):
            next_payload = dict(records[target_index + 1].get("payload", {}) or {})
            next_checkpoint = dict(next_payload.get("checkpoint_before", {}) or {})
            if next_checkpoint:
                checkpoint_history = [
                    dict(item or {})
                    for item in list(next_checkpoint.get("history", []) or [])
                    if isinstance(item, dict)
                ]
                if checkpoint_history:
                    next_checkpoint["history"] = checkpoint_history
                else:
                    next_checkpoint["history"] = history
                return next_checkpoint
            next_history = [
                dict(item or {})
                for item in list(next_payload.get("history", []) or [])
                if isinstance(item, dict)
            ]
            if next_history:
                # Legacy results predate per-turn checkpoints.  The next turn's
                # prompt history is the closest persisted post-turn snapshot and
                # remains valid even after the live session history is compressed.
                history = next_history
            memory_context = dict(next_payload.get("memory_context", {}) or {})
            input_payload = dict(next_payload.get("input", {}) or {})
            state_source = {
                "scene_card": dict(next_payload.get("scene_card", {}) or {}),
                "scene_progress": dict(next_payload.get("scene_progress", {}) or {}),
                "character_snapshots": dict(input_payload.get("character_snapshots", {}) or {}),
                "relation_delta": dict(memory_context.get("relation_delta", {}) or {}),
                "event_signals": {"recent": list(memory_context.get("event_signals", []) or [])},
                "memory_summary_state": dict(memory_context.get("archived_summary", {}) or {}),
                "consistency_monitor": {
                    "knowledge_ledger": list(next_payload.get("knowledge_context", []) or [])
                },
            }
        return {
            "participants": list(source.get("participants", []) or []),
            "temporary_npcs": dict(source.get("temporary_npcs", {}) or {}),
            "history": history,
            "scene_card": dict(state_source.get("scene_card", {}) or source.get("scene_card", {}) or {}),
            "scene_card_id": str(state_source.get("scene_card_id", source.get("scene_card_id", ""))).strip(),
            "scene_history": list(source.get("scene_history", []) or []),
            "scene_progress": (
                dict(state_source.get("scene_progress", {}) or {})
                if state_source is not source
                else self._session_scene_progress(source)
            ),
            "character_snapshots": (
                dict(state_source.get("character_snapshots", {}) or {})
                if state_source is not source
                else self._session_character_snapshots(source)
            ),
            "relation_delta": (
                dict(state_source.get("relation_delta", {}) or {})
                if state_source is not source
                else self._session_relation_delta(source)
            ),
            "relation_matrix": self._session_relation_matrix(source),
            "event_signals": (
                dict(state_source.get("event_signals", {}) or {})
                if state_source is not source
                else self._session_event_signals(source)
            ),
            "memory_summary_state": (
                dict(state_source.get("memory_summary_state", {}) or {})
                if state_source is not source
                else self._session_memory_summary_state(source)
            ),
            "consistency_monitor": dict(state_source.get("consistency_monitor", {}) or {}),
        }

    _serialize_transcript = staticmethod(_session_views.serialize_transcript)

    _mode_display = staticmethod(_text_utils.mode_display)

    def _build_session_card(self, session: dict[str, Any]) -> dict[str, Any]:
        return _session_views.build_session_card(session, mode_display=self._mode_display)

    _serialize_scene_history = staticmethod(_session_views.serialize_scene_history)

    _build_scene_history_entry = staticmethod(_session_views.build_scene_history_entry)

    def _build_pending_turn_summary(self, session: dict[str, Any]) -> dict[str, Any]:
        return _session_views.build_pending_turn_summary(
            session,
            normalize_message_kind=self._normalize_message_kind,
        )

    def _build_runtime_state_overview(self, session: dict[str, Any]) -> dict[str, Any]:
        return _runtime_overview.build_runtime_state_overview(
            scene_progress=self._session_scene_progress(session),
            session_summary=dict(session.get("session_memory_summary", {}) or {}),
            character_snapshots=self._session_character_snapshots(session),
            relation_delta=self._session_relation_delta(session),
            event_signals=self._session_event_signals(session),
        )

    def _build_session_memory_summary(
        self,
        run_id: str,
        session: dict[str, Any],
        transcript: list[dict[str, Any]],
    ) -> dict[str, str]:
        semantic_hint = ""
        session_id = str(session.get("session_id", "")).strip()
        if session_id and self._ensure_memory_store(run_id):
            try:
                hits = self._memory_stores[run_id].search_long_term_memory(
                    session_id,
                    "关系 冲突 目标",
                    top_k=1,
                )
            except Exception:
                hits = []
            if hits:
                semantic_hint = str((hits[0] or {}).get("text", "")).strip()
        return _memory_summary.build_session_memory_summary(
            session,
            transcript,
            scene_progress=self._session_scene_progress(session),
            relation_delta=self._session_relation_delta(session),
            event_signals=self._session_event_signals(session),
            semantic_hint=semantic_hint,
        )

    def _ensure_memory_store(self, run_id: str) -> bool:
        return self._resolve_memory_store(run_id) is not None

    def _resolve_memory_store(self, run_id: str) -> MarkdownSessionStore | None:
        normalized_run_id = str(run_id or "").strip()
        if not normalized_run_id:
            return None
        cached = self._memory_stores.get(normalized_run_id)
        if cached is not None:
            return cached
        try:
            if callable(self._memory_store_resolver):
                resolved = self._memory_store_resolver(normalized_run_id)
                if resolved is not None:
                    self._memory_stores[normalized_run_id] = resolved
                    return resolved
            config = Config()
            config.update(
                {
                    "paths": {
                        "sessions": str(
                            self.runs_root
                            / normalized_run_id
                            / "__session_memory_cache"
                        )
                    }
                }
            )
            resolved = MarkdownSessionStore(PathProvider(config))
            self._memory_stores[normalized_run_id] = resolved
            return resolved
        except Exception:
            return None

    _trim_summary_text = staticmethod(_text_utils.trim_summary_text)
    _build_last_entry_preview = staticmethod(_text_utils.build_last_entry_preview)

    def _build_file_urls(self, run_id: str, session: dict[str, Any]) -> dict[str, str]:
        session_id = str(session.get("session_id", "")).strip()
        urls: dict[str, str] = {}
        run_dir = self.runs_root / run_id
        session_relative = self._relative_to_run_dir(
            self._session_file(run_id, session_id), run_dir
        )
        if session_relative is not None:
            urls["session"] = self._file_url(run_id, session_relative)
        pending_path_text = str(
            session.get("pending_turn", {}).get("payload_path", "")
        ).strip()
        if pending_path_text:
            pending_path = Path(pending_path_text)
        else:
            pending_path = None
        if pending_path and pending_path.exists():
            pending_relative = self._relative_to_run_dir(pending_path, run_dir)
            if pending_relative is not None:
                urls["pending_turn_payload"] = self._file_url(run_id, pending_relative)
        return urls

    _build_scene_switch_note = staticmethod(_text_utils.build_scene_switch_note)
    _entry_to_memory_text = staticmethod(_text_utils.entry_to_memory_text)

    def _sessions_root(self, run_id: str) -> Path:
        return self._session_files.sessions_root(run_id)

    def _session_dir(self, run_id: str, session_id: str) -> Path:
        return self._session_files.session_dir(run_id, session_id)

    def _session_file(self, run_id: str, session_id: str) -> Path:
        return self._session_files.session_file(run_id, session_id)

    def session_lock(self, run_id: str, session_id: str):
        return self._session_files.lock(run_id, session_id)

    def _file_url(self, run_id: str, relative_path: Path) -> str:
        return f"/api/web/runs/{run_id}/files/{relative_path.as_posix()}"

    @staticmethod
    def _relative_to_run_dir(path: Path, run_dir: Path) -> Path | None:
        return relative_to_run_dir(path, run_dir)

    @staticmethod
    def _relative_candidates(path: Path, run_dir: Path) -> list[tuple[Path, Path]]:
        return relative_candidates(path, run_dir)

    @staticmethod
    def _normalized_parts(path: Path) -> tuple[str, ...]:
        return normalized_parts(path)

    @staticmethod
    def _read_json(path: Path) -> dict[str, Any]:
        return read_json(path)

    @staticmethod
    def _write_json(path: Path, payload: dict[str, Any]) -> None:
        write_json(path, payload)
