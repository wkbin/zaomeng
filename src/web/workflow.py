from __future__ import annotations

import base64
import json
import threading
from pathlib import Path
from typing import Any
from uuid import uuid4

from src.skill_support.prompt_payloads import build_distill_prompt_payload, build_relation_prompt_payload
from src.core.config import Config
from src.core.exceptions import LLMRequestError
from src.core.runtime_factory import build_runtime_parts
from src.utils.file_utils import safe_filename
from src.web.dialogue import DialogueService
from src.web.host_ingest import decode_text_content, export_relations_source, materialize_profile_source


def _project_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _utc_now() -> str:
    from datetime import UTC, datetime

    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


class WebRunService:
    def __init__(self, storage_root: str | Path | None = None) -> None:
        self.project_root = _project_root()
        self.storage_root = Path(storage_root) if storage_root else self.project_root / ".zaomeng-web"
        self.runs_root = self.storage_root / "runs"
        self.settings_path = self.storage_root / "model_settings.json"
        self.runs_root.mkdir(parents=True, exist_ok=True)
        self.dialogue = DialogueService(self.runs_root)

    def get_model_settings(self) -> dict[str, Any]:
        payload = self._load_model_settings_payload()
        provider = str(payload.get("provider", "")).strip()
        model = str(payload.get("model", "")).strip()
        base_url = str(payload.get("base_url", "")).strip()
        api_key = str(payload.get("api_key", "")).strip()
        return {
            "provider": provider,
            "model": model,
            "base_url": base_url,
            "api_key_configured": bool(api_key),
            "configured": self._is_model_configured_payload(payload),
        }

    def save_model_settings(
        self,
        *,
        provider: str,
        model: str,
        base_url: str = "",
        api_key: str = "",
    ) -> dict[str, Any]:
        normalized = {
            "provider": str(provider or "").strip(),
            "model": str(model or "").strip(),
            "base_url": str(base_url or "").strip(),
            "api_key": str(api_key or "").strip(),
            "updated_at": _utc_now(),
        }
        if not normalized["provider"]:
            raise ValueError("Model provider is required.")
        if not normalized["model"]:
            raise ValueError("Model name is required.")
        if normalized["provider"] != "ollama" and not normalized["api_key"]:
            raise ValueError("API key is required for the selected provider.")
        self._write_json(self.settings_path, normalized)
        return self.get_model_settings()

    def model_is_configured(self) -> bool:
        return self._is_model_configured_payload(self._load_model_settings_payload())

    def list_runs(self) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        for manifest_path in sorted(self.runs_root.glob("*/run_manifest.json"), reverse=True):
            payload = self._load_manifest(manifest_path)
            if payload:
                items.append(self._serialize_manifest(payload))
        items.sort(key=lambda item: item.get("updated_at", ""), reverse=True)
        return items

    def list_recent_sessions(self) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        for manifest_path in sorted(self.runs_root.glob("*/run_manifest.json"), reverse=True):
            manifest = self._load_manifest(manifest_path)
            if not manifest:
                continue
            run_id = str(manifest.get("run_id", "")).strip()
            novel_id = str(manifest.get("novel_id", "")).strip()
            if not run_id:
                continue
            for session in self.dialogue.list_sessions(run_id):
                session["novel_id"] = novel_id
                items.append(session)
        items.sort(key=lambda item: item.get("updated_at", ""), reverse=True)
        return items

    def get_run(self, run_id: str) -> dict[str, Any]:
        manifest_path = self._manifest_path(run_id)
        payload = self._load_manifest(manifest_path)
        if not payload:
            raise FileNotFoundError(run_id)
        return self._serialize_manifest(payload)

    def refresh_run(self, run_id: str) -> dict[str, Any]:
        manifest_path = self._manifest_path(run_id)
        manifest = self._load_manifest(manifest_path)
        if not manifest:
            raise FileNotFoundError(run_id)
        refreshed = self._discover_artifacts(manifest)
        refreshed["updated_at"] = _utc_now()
        self._write_json(manifest_path, refreshed)
        return self._serialize_manifest(refreshed)

    def create_run(
        self,
        *,
        novel_name: str,
        novel_content_base64: str,
        characters: list[str],
        max_sentences: int = 120,
        max_chars: int = 50_000,
        auto_run: bool = False,
    ) -> dict[str, Any]:
        if not self.model_is_configured():
            raise ValueError("Model is not configured yet.")
        locked_characters = self._normalize_characters(characters)
        if not locked_characters:
            raise ValueError("At least one character is required.")

        file_name = safe_filename(novel_name or "novel.txt")
        raw_bytes = self._decode_base64(novel_content_base64)
        if not raw_bytes:
            raise ValueError("Novel content is empty.")

        run_id = self._new_run_id()
        run_dir = self.runs_root / run_id
        input_dir = run_dir / "input"
        payload_dir = run_dir / "payloads"
        artifact_dir = run_dir / "artifacts"
        for directory in (input_dir, payload_dir, artifact_dir):
            directory.mkdir(parents=True, exist_ok=True)

        novel_path = input_dir / file_name
        novel_path.write_bytes(raw_bytes)
        novel_id = Path(file_name).stem.strip() or run_id

        manifest = {
            "kind": "zaomeng_web_run",
            "schema_version": 1,
            "run_id": run_id,
            "novel_id": novel_id,
            "novel_path": str(novel_path.resolve()),
            "created_at": _utc_now(),
            "updated_at": _utc_now(),
            "status": "running",
            "success": False,
            "entrypoint": "webui",
            "model_settings": self.get_model_settings(),
            "locked_characters": locked_characters,
            "progress": {
                "stage": "characters_locked",
                "message": "已锁定待蒸馏角色",
                "current_character": "",
                "completed_characters": [],
                "total_characters": len(locked_characters),
                "completed_count": 0,
                "graph_status": "pending",
            },
            "capabilities": {
                "distill": {"status": "preparing", "success": False, "updated_at": _utc_now()},
                "materialize": {"status": "pending", "success": False, "updated_at": _utc_now()},
                "export_graph": {"status": "preparing", "success": False, "updated_at": _utc_now()},
                "verify_workflow": {"status": "pending", "success": False, "updated_at": _utc_now()},
            },
            "artifacts": {
                "payloads": {},
                "status_files": {},
                "character_dirs": {},
                "relation_graph": {},
            },
            "summary": {
                "characters_total": len(locked_characters),
                "characters_completed": 0,
                "graph_status": "pending",
                "status_text": "waiting_for_payloads",
            },
            "events": [
                {
                    "stage": "characters_locked",
                    "status": "running",
                    "message": "已锁定待蒸馏角色",
                    "character": "",
                    "capability": "distill",
                    "timestamp": _utc_now(),
                }
            ],
            "webui": {
                "run_dir": str(run_dir.resolve()),
                "input_dir": str(input_dir.resolve()),
                "payload_dir": str(payload_dir.resolve()),
                "artifact_dir": str(artifact_dir.resolve()),
            },
        }

        manifest_path = self._manifest_path(run_id)
        self._write_json(manifest_path, manifest)

        characters_root = artifact_dir / "characters" / novel_id
        manifest["webui"]["workspace"] = {
            "characters_root": str(characters_root.resolve()),
            "relations_root": str((artifact_dir / "relations").resolve()),
        }

        if auto_run:
            self._write_json(manifest_path, manifest)
            self._start_background_run(
                manifest_path=manifest_path,
                novel_path=novel_path,
                locked_characters=locked_characters,
                max_sentences=max_sentences,
                max_chars=max_chars,
            )
            return self._serialize_manifest(self._load_manifest(manifest_path) or manifest)

        distill_payload = build_distill_prompt_payload(
            novel_path,
            characters=locked_characters,
            max_sentences=max_sentences,
            max_chars=max_chars,
            characters_root=characters_root,
            manifest_path=manifest_path,
            update_mode="auto",
        )
        relation_payload = build_relation_prompt_payload(
            novel_path,
            max_sentences=min(max_sentences, 80),
            max_chars=min(max_chars, 12_000),
        )

        distill_payload_path = payload_dir / "distill_payload.json"
        relation_payload_path = payload_dir / "relation_payload.json"
        self._write_json(distill_payload_path, distill_payload)
        self._write_json(relation_payload_path, relation_payload)

        manifest["progress"]["stage"] = "relation_payload_ready"
        manifest["progress"]["message"] = "蒸馏与关系提取 payload 已准备完成"
        manifest["updated_at"] = _utc_now()
        manifest["summary"]["status_text"] = "waiting_for_host_generation"
        manifest["capabilities"]["distill"] = {
            "status": "ready",
            "success": False,
            "updated_at": _utc_now(),
            "message": "distill payload ready",
        }
        manifest["capabilities"]["export_graph"] = {
            "status": "ready",
            "success": False,
            "updated_at": _utc_now(),
            "message": "relation payload ready",
        }
        manifest["artifacts"]["payloads"] = {
            "distill": str(distill_payload_path.resolve()),
            "relation": str(relation_payload_path.resolve()),
        }
        manifest["excerpt_focus"] = {
            "matched_characters": distill_payload.get("request", {}).get("excerpt_focus", {}).get("matched_characters", []),
            "missing_characters": distill_payload.get("request", {}).get("excerpt_focus", {}).get("missing_characters", []),
            "strategy": distill_payload.get("request", {}).get("excerpt_focus", {}).get("strategy", ""),
        }
        manifest["events"].append(
            {
                "stage": "distill_payload_ready",
                "status": "running",
                "message": "蒸馏 payload 已生成，等待宿主 LLM 执行",
                "character": "",
                "capability": "distill",
                "timestamp": _utc_now(),
            }
        )
        manifest["events"].append(
            {
                "stage": "relation_payload_ready",
                "status": "running",
                "message": "关系图谱 payload 已生成，等待宿主 LLM 执行",
                "character": "",
                "capability": "export_graph",
                "timestamp": _utc_now(),
            }
        )
        self._write_json(manifest_path, manifest)
        return self._serialize_manifest(manifest)

    def restart_run_distill(
        self,
        run_id: str,
        *,
        characters: list[str],
        max_sentences: int = 120,
        max_chars: int = 50_000,
    ) -> dict[str, Any]:
        if not self.model_is_configured():
            raise ValueError("Model is not configured yet.")
        manifest_path = self._manifest_path(run_id)
        manifest = self._load_manifest(manifest_path)
        if not manifest:
            raise FileNotFoundError(run_id)

        locked_characters = self._normalize_characters(characters) or list(manifest.get("locked_characters", []))
        if not locked_characters:
            raise ValueError("At least one character is required.")

        existing_character_names = set()
        artifact_index = manifest.get("artifact_index", {}).get("characters", [])
        if isinstance(artifact_index, list):
            existing_character_names.update(
                str(item.get("name", "")).strip() for item in artifact_index if isinstance(item, dict)
            )
        character_dirs = manifest.get("artifacts", {}).get("character_dirs", {})
        if isinstance(character_dirs, dict):
            existing_character_names.update(str(name).strip() for name in character_dirs.keys())
        existing_requested = [name for name in locked_characters if name in existing_character_names]
        new_requested = [name for name in locked_characters if name not in existing_character_names]
        redistill_summary = f"继续蒸馏：新增 {len(new_requested)} 人，重蒸 {len(existing_requested)} 人"

        novel_path = Path(str(manifest.get("novel_path", "")).strip())
        if not novel_path.exists():
            raise ValueError("Novel source file is missing for this run.")

        progress = manifest.setdefault("progress", {})
        progress.update(
            {
                "stage": "characters_locked",
                "message": redistill_summary,
                "current_character": "",
                "completed_characters": [],
                "total_characters": len(locked_characters),
                "completed_count": 0,
                "graph_status": "pending",
            }
        )
        manifest["locked_characters"] = locked_characters
        manifest["redistill"] = {
            "requested_characters": locked_characters,
            "new_characters": new_requested,
            "existing_characters": existing_requested,
            "summary": redistill_summary,
        }
        manifest["status"] = "running"
        manifest["success"] = False
        manifest["updated_at"] = _utc_now()
        manifest.setdefault("summary", {}).update(
            {
                "characters_total": len(locked_characters),
                "characters_completed": 0,
                "graph_status": "pending",
                "status_text": "waiting_for_payloads",
            }
        )
        manifest.setdefault("capabilities", {})["distill"] = {
            "status": "preparing",
            "success": False,
            "updated_at": _utc_now(),
            "message": "incremental distill requested",
        }
        manifest["capabilities"]["export_graph"] = {
            "status": "preparing",
            "success": False,
            "updated_at": _utc_now(),
            "message": "graph regeneration requested",
        }
        manifest["events"] = [
            {
                "stage": "redistill_requested",
                "status": "running",
                "message": redistill_summary,
                "character": "",
                "capability": "distill",
                "timestamp": _utc_now(),
            }
        ]
        self._write_json(manifest_path, manifest)
        self._start_background_run(
            manifest_path=manifest_path,
            novel_path=novel_path,
            locked_characters=locked_characters,
            max_sentences=max_sentences,
            max_chars=max_chars,
        )
        return self._serialize_manifest(self._load_manifest(manifest_path) or manifest)

    def ingest_character_result(
        self,
        run_id: str,
        *,
        character: str,
        content_base64: str,
        filename: str = "PROFILE.generated.md",
    ) -> dict[str, Any]:
        manifest_path = self._manifest_path(run_id)
        manifest = self._load_manifest(manifest_path)
        if not manifest:
            raise FileNotFoundError(run_id)

        run_dir = self.runs_root / run_id
        novel_id = str(manifest.get("novel_id", "")).strip() or run_id
        safe_character = safe_filename(character)
        host_output_dir = run_dir / "host_output" / novel_id / safe_character
        host_output_dir.mkdir(parents=True, exist_ok=True)
        source_path = host_output_dir / safe_filename(filename or "PROFILE.generated.md")
        source_text = decode_text_content(content_base64)
        source_path.write_text(source_text, encoding="utf-8")

        persona_dir = run_dir / "artifacts" / "characters" / novel_id / safe_character
        payload = materialize_profile_source(source_path, persona_dir)

        manifest.setdefault("capabilities", {})["materialize"] = {
            "status": "complete",
            "success": True,
            "updated_at": _utc_now(),
            "message": f"{payload['character']} materialized",
        }
        manifest.setdefault("artifacts", {}).setdefault("character_dirs", {})
        manifest["artifacts"]["character_dirs"][payload["character"]] = payload["persona_dir"]
        manifest.setdefault("events", []).append(
            {
                "stage": "character_completed",
                "status": "running",
                "message": f"{payload['character']} 人物包已生成",
                "character": payload["character"],
                "capability": "materialize",
                "timestamp": _utc_now(),
            }
        )
        refreshed = self._discover_artifacts(manifest)
        refreshed["updated_at"] = _utc_now()
        self._write_json(manifest_path, refreshed)
        return self._serialize_manifest(refreshed)

    def ingest_relation_result(
        self,
        run_id: str,
        *,
        content_base64: str,
        filename: str = "relations.md",
    ) -> dict[str, Any]:
        manifest_path = self._manifest_path(run_id)
        manifest = self._load_manifest(manifest_path)
        if not manifest:
            raise FileNotFoundError(run_id)

        run_dir = self.runs_root / run_id
        novel_id = str(manifest.get("novel_id", "")).strip() or run_id
        relations_dir = run_dir / "artifacts" / "relations"
        relations_dir.mkdir(parents=True, exist_ok=True)
        relation_source = relations_dir / safe_filename(filename or f"{novel_id}_relations.md")
        relation_source.write_text(decode_text_content(content_base64), encoding="utf-8")
        graph_payload = export_relations_source(relation_source, novel_id=novel_id, manifest_path=manifest_path)

        manifest.setdefault("capabilities", {})["export_graph"] = {
            "status": "complete",
            "success": True,
            "updated_at": _utc_now(),
            "message": "relation graph exported",
        }
        manifest.setdefault("events", []).append(
            {
                "stage": "graph_export_completed",
                "status": "running",
                "message": "人物关系图谱已生成",
                "character": "",
                "capability": "export_graph",
                "timestamp": _utc_now(),
            }
        )
        manifest.setdefault("artifacts", {})["relation_graph"] = dict(graph_payload)
        refreshed = self._discover_artifacts(manifest)
        refreshed["updated_at"] = _utc_now()
        self._write_json(manifest_path, refreshed)
        return self._serialize_manifest(refreshed)

    def resolve_run_file(self, run_id: str, relative_path: str) -> Path:
        run_dir = self.runs_root / run_id
        if not run_dir.exists():
            raise FileNotFoundError(run_id)
        candidate = (run_dir / relative_path).resolve()
        if run_dir.resolve() not in candidate.parents and candidate != run_dir.resolve():
            raise ValueError("Path escapes run directory.")
        if not candidate.exists() or not candidate.is_file():
            raise FileNotFoundError(relative_path)
        return candidate

    def list_dialogue_sessions(self, run_id: str) -> list[dict[str, Any]]:
        self._ensure_run_exists(run_id)
        return self.dialogue.list_sessions(run_id)

    def create_dialogue_session(
        self,
        run_id: str,
        *,
        mode: str,
        participants: list[str],
        controlled_character: str = "",
        self_profile: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        session = self.dialogue.create_session(
            manifest,
            mode=mode,
            participants=participants,
            controlled_character=controlled_character,
            self_profile=self_profile,
        )
        opening_message = self._build_dialogue_opening_message(session)
        self.dialogue.prepare_turn(
            manifest,
            session_id=str(session.get("session_id", "")).strip(),
            message=opening_message,
            speaker_override="场景提示",
            transcript_message="",
        )
        pending_payload = self._load_pending_turn_payload(run_id, str(session.get("session_id", "")).strip())
        try:
            responses = self._generate_dialogue_responses(run_id, pending_payload)
        except LLMRequestError as exc:
            raise ValueError(self._friendly_dialogue_llm_error(exc)) from exc
        return self.dialogue.ingest_turn_responses(
            run_id,
            session_id=str(session.get("session_id", "")).strip(),
            responses=responses,
        )

    def get_dialogue_session(self, run_id: str, session_id: str) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        return self.dialogue.get_session(run_id, session_id)

    def delete_dialogue_session(self, run_id: str, session_id: str) -> None:
        self._ensure_run_exists(run_id)
        self.dialogue.delete_session(run_id, session_id)

    def prepare_dialogue_turn(self, run_id: str, *, session_id: str, message: str) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        return self.dialogue.prepare_turn(manifest, session_id=session_id, message=message)

    def reply_dialogue_turn(self, run_id: str, *, session_id: str, message: str) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        self.dialogue.prepare_turn(manifest, session_id=session_id, message=message)
        pending_payload = self._load_pending_turn_payload(run_id, session_id)
        try:
            responses = self._generate_dialogue_responses(run_id, pending_payload)
        except LLMRequestError as exc:
            raise ValueError(self._friendly_dialogue_llm_error(exc)) from exc
        return self.dialogue.ingest_turn_responses(run_id, session_id=session_id, responses=responses)

    def ingest_dialogue_turn(
        self,
        run_id: str,
        *,
        session_id: str,
        responses: list[dict[str, str]],
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        return self.dialogue.ingest_turn_responses(run_id, session_id=session_id, responses=responses)

    def _serialize_manifest(self, payload: dict[str, Any]) -> dict[str, Any]:
        manifest = dict(payload)
        run_id = str(manifest.get("run_id", "")).strip()
        if run_id:
            manifest["file_urls"] = self._build_file_urls(run_id, manifest)
        return manifest

    def _discover_artifacts(self, manifest: dict[str, Any]) -> dict[str, Any]:
        updated = json.loads(json.dumps(manifest, ensure_ascii=False))
        webui = updated.get("webui", {})
        workspace = webui.get("workspace", {})
        run_dir = Path(str(webui.get("run_dir", ""))).resolve() if webui.get("run_dir") else None
        artifact_dir = Path(str(webui.get("artifact_dir", ""))).resolve() if webui.get("artifact_dir") else None
        characters_root = Path(str(workspace.get("characters_root", ""))).resolve() if workspace.get("characters_root") else None
        relations_root = Path(str(workspace.get("relations_root", ""))).resolve() if workspace.get("relations_root") else None

        character_index = self._discover_character_cards(characters_root)
        if character_index:
            updated.setdefault("artifacts", {}).setdefault("character_dirs", {})
            updated["artifacts"]["character_dirs"] = {
                item["name"]: item["persona_dir"] for item in character_index
            }
            updated.setdefault("artifact_index", {})["characters"] = character_index
            completed_names = [item["name"] for item in character_index]
            updated.setdefault("progress", {})["completed_characters"] = completed_names
            updated["progress"]["completed_count"] = len(completed_names)
            if updated.get("locked_characters") and len(completed_names) >= len(updated["locked_characters"]):
                if updated["progress"].get("graph_status") == "complete":
                    updated["summary"]["status_text"] = "waiting_for_verification"
                else:
                    updated["summary"]["status_text"] = "graph_pending"
                updated["progress"]["current_character"] = ""
            updated["summary"]["characters_completed"] = len(completed_names)

        relation_graph = self._discover_relation_graph(relations_root, artifact_dir, run_dir)
        if relation_graph:
            updated.setdefault("artifacts", {})["relation_graph"] = relation_graph
            updated.setdefault("artifact_index", {})["relation_graph"] = relation_graph
            updated.setdefault("progress", {})["graph_status"] = "complete"
            if updated["summary"].get("status_text") in {"waiting_for_payloads", "waiting_for_host_generation", "graph_pending"}:
                updated["summary"]["status_text"] = "graph_ready"
            updated["summary"]["graph_status"] = "complete"

        return updated

    def _build_file_urls(self, run_id: str, manifest: dict[str, Any]) -> dict[str, str]:
        urls: dict[str, str] = {}
        manifest_path = self._manifest_path(run_id)
        urls["manifest"] = self._file_url(run_id, manifest_path.relative_to(self.runs_root / run_id))
        payloads = manifest.get("artifacts", {}).get("payloads", {})
        if isinstance(payloads, dict):
            for key, value in payloads.items():
                path = Path(str(value))
                if path.exists():
                    urls[f"payload_{key}"] = self._file_url(run_id, path.relative_to(self.runs_root / run_id))
        character_items = manifest.get("artifact_index", {}).get("characters", [])
        if isinstance(character_items, list):
            for item in character_items:
                profile = Path(str(item.get("profile_file", "")))
                if profile.exists():
                    urls[f"character_{item.get('name', '')}"] = self._file_url(run_id, profile.relative_to(self.runs_root / run_id))
        relation_graph = manifest.get("artifact_index", {}).get("relation_graph", {})
        if isinstance(relation_graph, dict):
            for key in ("html_path", "svg_path", "mermaid_path", "relations_file"):
                value = str(relation_graph.get(key, "")).strip()
                if not value:
                    continue
                path = Path(value)
                if path.exists():
                    urls[f"graph_{key.replace('_path', '')}"] = self._file_url(run_id, path.relative_to(self.runs_root / run_id))
        return urls

    def _file_url(self, run_id: str, relative_path: Path) -> str:
        return f"/api/web/runs/{run_id}/files/{relative_path.as_posix()}"

    def _manifest_path(self, run_id: str) -> Path:
        return self.runs_root / run_id / "run_manifest.json"

    def _require_manifest(self, run_id: str) -> dict[str, Any]:
        payload = self._load_manifest(self._manifest_path(run_id))
        if not payload:
            raise FileNotFoundError(run_id)
        return payload

    def _ensure_run_exists(self, run_id: str) -> None:
        if not self._manifest_path(run_id).exists():
            raise FileNotFoundError(run_id)

    def _load_manifest(self, manifest_path: Path) -> dict[str, Any] | None:
        if not manifest_path.exists():
            return None
        return json.loads(manifest_path.read_text(encoding="utf-8"))

    def _load_model_settings_payload(self) -> dict[str, Any]:
        return self._load_json_file(self.settings_path) or {}

    def _load_pending_turn_payload(self, run_id: str, session_id: str) -> dict[str, Any]:
        session_path = self.runs_root / run_id / "dialogue" / session_id / "session.json"
        session_payload = self._load_json_file(session_path) or {}
        pending_path_text = str(session_payload.get("pending_turn", {}).get("payload_path", "")).strip()
        if not pending_path_text:
            raise ValueError("Pending turn payload was not created.")
        pending_path = Path(pending_path_text)
        pending_payload = self._load_json_file(pending_path)
        if not pending_payload:
            raise ValueError("Pending turn payload is empty.")
        return pending_payload

    def _generate_dialogue_responses(self, run_id: str, payload: dict[str, Any]) -> list[dict[str, str]]:
        run_dir = self.runs_root / run_id
        config = self._build_runtime_config_for_run(run_dir=run_dir)
        parts = build_runtime_parts(config)
        if not hasattr(parts.llm, "chat_completion"):
            raise ValueError("Configured model does not support chat generation.")

        allowed_speakers = [str(item.get("name", "")).strip() for item in payload.get("responder_hints", [])]
        allowed_speakers.extend(["旁白", "场景提示"])
        attempts = (
            self._build_dialogue_llm_messages(payload, retry_on_empty=False),
            self._build_dialogue_llm_messages(payload, retry_on_empty=True),
        )
        last_error: Exception | None = None
        for index, llm_messages in enumerate(attempts):
            llm_result = parts.llm.chat_completion(
                llm_messages,
                temperature=float(config.get("llm.temperature", 0.35) or 0.35),
                max_tokens=int(config.get("llm.max_tokens", 900) or 900),
            )
            content = str(llm_result.get("content", "")).strip()
            if not content:
                last_error = ValueError("Model returned an empty reply.")
                if index + 1 < len(attempts):
                    continue
                break
            try:
                return self._parse_dialogue_responses(content=content, allowed_speakers=allowed_speakers)
            except ValueError as exc:
                last_error = exc
                if index + 1 < len(attempts):
                    continue
                raise
        raise ValueError("模型没有返回可用的角色回复。") from last_error

    @staticmethod
    def _build_dialogue_opening_message(session: dict[str, Any]) -> str:
        mode = str(session.get("mode", "observe")).strip() or "observe"
        participants = [str(item).strip() for item in session.get("participants", []) if str(item).strip()]
        cast = "、".join(participants) or "当前角色"
        if mode == "act":
            controlled = str(session.get("controlled_character", "")).strip() or "该角色"
            return (
                f"请先为 {controlled} 与 {cast} 生成一个自然开场。"
                "先给 1 条简短的场景提示或旁白，再让其他角色先接出第一轮对话，不要等待用户补充。"
            )
        if mode == "insert":
            self_profile = dict(session.get("self_insert", {}) or {})
            display_name = str(self_profile.get("display_name", "")).strip() or "我"
            scene_identity = str(self_profile.get("scene_identity", "")).strip()
            identity_suffix = f"，身份是{scene_identity}" if scene_identity else ""
            return (
                f"请先为 {display_name}{identity_suffix} 与 {cast} 生成一个自然开场。"
                "先给 1 条简短的场景提示或旁白，再让角色们先开口，对这个进入场景的人作出第一轮反应。"
            )
        return (
            f"请先为 {cast} 生成一个自然开场。"
            "先给 1 条简短的场景提示或旁白，再让角色们开始第一轮对话，让场景自己动起来。"
        )

    @staticmethod
    def _friendly_dialogue_llm_error(exc: Exception) -> str:
        message = str(exc or "").strip()
        lowered = message.lower()
        if any(token in lowered for token in ("invalidsubscription", "codingplan", "subscription has expired", "does not have a valid")):
            return "当前模型账号没有可用的对话生成订阅权限，请更换可用模型，或检查并续订当前账号权限。"
        return message or "当前模型调用失败，请检查模型配置后重试。"

    @staticmethod
    def _build_dialogue_llm_messages(payload: dict[str, Any], *, retry_on_empty: bool = False) -> list[dict[str, str]]:
        input_block = dict(payload.get("input", {}) or {})
        session_mode = str(payload.get("mode", "")).strip() or "observe"
        participants = [str(item).strip() for item in input_block.get("participants", []) if str(item).strip()]
        persona_contexts = payload.get("persona_contexts", [])
        relation_excerpt = str(payload.get("relation_context", {}).get("relations_excerpt", "")).strip()
        history = payload.get("history", [])
        instructions = dict(payload.get("instructions", {}) or {})
        host_action = dict(payload.get("host_action", {}) or {})
        response_limit = int(host_action.get("response_limit_hint", 2) or 2)

        system_parts = [
            str(payload.get("host_prompt_brief", "")).strip(),
            str(instructions.get("generation_goal", "")).strip(),
            str(instructions.get("mode_rule", "")).strip(),
            str(instructions.get("speaker_rule", "")).strip(),
            str(instructions.get("response_style", "")).strip(),
            str(host_action.get("output_rule", "")).strip(),
            "只返回 JSON 数组，每项必须包含 speaker 和 message。",
        ]
        if retry_on_empty:
            system_parts.append('这次至少返回 1 条可用回复；如果角色暂时不宜直接接话，可先返回 speaker 为“旁白”或“场景提示”的一条提示。')
        system_prompt = "\n".join(part for part in system_parts if part)

        user_payload = {
            "mode": session_mode,
            "speaker": str(input_block.get("speaker", "")).strip(),
            "message": str(input_block.get("message", "")).strip(),
            "participants": participants,
            "response_limit": response_limit,
            "persona_contexts": persona_contexts,
            "history": history,
            "relation_excerpt": relation_excerpt,
            "expected_output": host_action.get("expected_output", [{"speaker": "角色名", "message": "回复内容"}]),
            "retry_on_empty": retry_on_empty,
        }
        user_prompt = json.dumps(user_payload, ensure_ascii=False, indent=2)
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]

    @staticmethod
    def _parse_dialogue_responses(content: str, allowed_speakers: list[str]) -> list[dict[str, str]]:

        text = str(content or "").strip()
        if not text:
            raise ValueError("Model returned an empty reply.")
        if text.startswith("```"):
            text = text.strip("`")
            if "\n" in text:
                text = text.split("\n", 1)[1]
            if text.endswith("```"):
                text = text[:-3].strip()
        parsed: Any
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            start = text.find("[")
            end = text.rfind("]")
            if start == -1 or end == -1 or end <= start:
                raise ValueError("Model reply is not valid JSON.") from None
            parsed = json.loads(text[start : end + 1])

        if isinstance(parsed, dict):
            parsed = parsed.get("responses", [])
        if not isinstance(parsed, list):
            raise ValueError("Model reply is not a response list.")

        allowed = {name for name in allowed_speakers if name}
        clean_responses: list[dict[str, str]] = []
        for item in parsed:
            if not isinstance(item, dict):
                continue
            speaker = str(item.get("speaker", "")).strip()
            message = str(item.get("message", "")).strip()
            if not speaker or not message:
                continue
            if allowed and speaker not in allowed:
                continue
            clean_responses.append({"speaker": speaker, "message": message})
        if not clean_responses:
            raise ValueError("Model reply did not contain usable character responses.")
        return clean_responses

    @staticmethod
    def _load_json_file(path: Path) -> dict[str, Any] | None:
        if not path.exists():
            return None
        return json.loads(path.read_text(encoding="utf-8"))

    def _run_automatic_pipeline(
        self,
        *,
        manifest_path: Path,
        novel_path: Path,
        locked_characters: list[str],
        max_sentences: int,
        max_chars: int,
    ) -> dict[str, Any]:
        manifest = self._load_manifest(manifest_path) or {}
        run_dir = manifest_path.parent
        novel_id = str(manifest.get("novel_id", "")).strip() or run_dir.name
        config = self._build_runtime_config_for_run(run_dir=run_dir)
        parts = build_runtime_parts(config)

        def on_distill(stage: str, payload: dict[str, Any]) -> None:
            current = self._load_manifest(manifest_path) or manifest
            progress = current.setdefault("progress", {})
            if stage == "text_loaded":
                progress["stage"] = "text_loaded"
                progress["message"] = "已载入小说文本"
            elif stage == "characters_ready":
                progress["stage"] = "characters_ready"
                total = int(payload.get("total", 0) or 0)
                progress["message"] = f"已锁定 {total} 个待蒸馏角色" if total else "已锁定待蒸馏角色"
            elif stage == "drafting_character":
                progress["stage"] = "distilling"
                progress["current_character"] = payload.get("character", "")
                progress["message"] = f"正在提取 {payload.get('character', '')}"
            elif stage == "refining_character":
                progress["stage"] = "distilling"
                progress["current_character"] = payload.get("character", "")
                progress["message"] = f"正在精修 {payload.get('character', '')}"
            elif stage == "second_pass_disabled":
                progress["stage"] = "distilling"
                progress["message"] = str(payload.get("reason", "")).strip() or "当前模型不支持二次精修，已自动跳过精修并继续蒸馏"
            elif stage == "character_done":
                completed = list(progress.get("completed_characters", []))
                character = str(payload.get("character", "")).strip()
                if character and character not in completed:
                    completed.append(character)
                progress["completed_characters"] = completed
                progress["completed_count"] = len(completed)
                progress["current_character"] = ""
                progress["message"] = f"{character} 蒸馏完成"
            current.setdefault("events", []).append(
                {
                    "stage": stage,
                    "status": "running",
                    "message": progress.get("message", ""),
                    "character": payload.get("character", ""),
                    "capability": "distill",
                    "timestamp": _utc_now(),
                }
            )
            current["updated_at"] = _utc_now()
            self._write_json(manifest_path, current)

        def on_relation(stage: str, payload: dict[str, Any]) -> None:
            current = self._load_manifest(manifest_path) or manifest
            progress = current.setdefault("progress", {})
            if stage == "rendering_graph":
                progress["stage"] = "rendering_graph"
                progress["graph_status"] = "running"
                progress["message"] = "正在生成人物关系图谱"
            elif stage == "graph_done":
                progress["stage"] = "graph_done"
                progress["graph_status"] = "complete"
                progress["message"] = "人物关系图谱已生成"
            current.setdefault("events", []).append(
                {
                    "stage": stage,
                    "status": "running",
                    "message": progress.get("message", ""),
                    "character": "",
                    "capability": "export_graph",
                    "timestamp": _utc_now(),
                }
            )
            current["updated_at"] = _utc_now()
            self._write_json(manifest_path, current)

        try:
            characters_root = parts.path_provider.characters_root(novel_id)
            parts.distiller.distill(
                str(novel_path),
                characters=locked_characters,
                output_dir=str(characters_root),
                progress_callback=on_distill,
            )
            relations_file = parts.path_provider.relations_file(novel_id)
            parts.extractor.extract(
                str(novel_path),
                output_path=str(relations_file),
                characters=locked_characters,
                progress_callback=on_relation,
            )
            refreshed = self._discover_artifacts(self._load_manifest(manifest_path) or manifest)
            refreshed["status"] = "ready"
            refreshed["success"] = True
            refreshed["updated_at"] = _utc_now()
            refreshed.setdefault("summary", {})["status_text"] = "workflow_complete"
            refreshed.setdefault("capabilities", {})["distill"] = {
                "status": "complete",
                "success": True,
                "updated_at": _utc_now(),
                "message": "characters distilled",
            }
            refreshed["capabilities"]["materialize"] = {
                "status": "complete",
                "success": True,
                "updated_at": _utc_now(),
                "message": "persona bundle written",
            }
            refreshed["capabilities"]["export_graph"] = {
                "status": "complete",
                "success": True,
                "updated_at": _utc_now(),
                "message": "relation graph exported",
            }
            refreshed["capabilities"]["verify_workflow"] = {
                "status": "complete",
                "success": True,
                "updated_at": _utc_now(),
                "message": "automatic workflow finished",
            }
            self._write_json(manifest_path, refreshed)
            return self._serialize_manifest(refreshed)
        except Exception as exc:
            failed = self._load_manifest(manifest_path) or manifest
            failed["status"] = "failed"
            failed["success"] = False
            failed["updated_at"] = _utc_now()
            failed.setdefault("summary", {})["status_text"] = "failed"
            failed.setdefault("progress", {})["message"] = str(exc)
            failed.setdefault("events", []).append(
                {
                    "stage": "failed",
                    "status": "failed",
                    "message": str(exc),
                    "character": "",
                    "capability": "verify_workflow",
                    "timestamp": _utc_now(),
                }
            )
            self._write_json(manifest_path, failed)
            raise

    def _start_background_run(
        self,
        *,
        manifest_path: Path,
        novel_path: Path,
        locked_characters: list[str],
        max_sentences: int,
        max_chars: int,
    ) -> None:
        manifest = self._load_manifest(manifest_path) or {}
        manifest["updated_at"] = _utc_now()
        manifest.setdefault("progress", {})["stage"] = "queued"
        manifest["progress"]["message"] = "已开始蒸馏任务"
        manifest.setdefault("summary", {})["status_text"] = "waiting_for_payloads"
        manifest.setdefault("events", []).append(
            {
                "stage": "queued",
                "status": "running",
                "message": "已开始蒸馏任务",
                "character": "",
                "capability": "verify_workflow",
                "timestamp": _utc_now(),
            }
        )
        self._write_json(manifest_path, manifest)

        thread = threading.Thread(
            target=self._run_automatic_pipeline,
            kwargs={
                "manifest_path": manifest_path,
                "novel_path": novel_path,
                "locked_characters": locked_characters,
                "max_sentences": max_sentences,
                "max_chars": max_chars,
            },
            daemon=True,
        )
        thread.start()

    def _build_runtime_config_for_run(self, *, run_dir: Path) -> Config:
        model_payload = self._load_model_settings_payload()
        config = Config()
        config.update(
            {
                "llm": {
                    "provider": str(model_payload.get("provider", "")).strip(),
                    "model": str(model_payload.get("model", "")).strip(),
                    "base_url": str(model_payload.get("base_url", "")).strip(),
                    "api_key": str(model_payload.get("api_key", "")).strip(),
                },
                "paths": {
                    "characters": str((run_dir / "artifacts" / "characters").resolve()),
                    "relations": str((run_dir / "artifacts" / "relations").resolve()),
                    "sessions": str((run_dir / "dialogue").resolve()),
                    "corrections": str((run_dir / "corrections").resolve()),
                    "logs": str((run_dir / "logs").resolve()),
                    "rules": str((self.project_root / "rules").resolve()),
                },
            }
        )
        return config

    @staticmethod
    def _write_json(path: Path, payload: dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    @staticmethod
    def _normalize_characters(characters: list[str]) -> list[str]:
        ordered: list[str] = []
        seen: set[str] = set()
        for item in characters:
            name = str(item or "").strip()
            if not name or name in seen:
                continue
            ordered.append(name)
            seen.add(name)
        return ordered

    @staticmethod
    def _decode_base64(value: str) -> bytes:
        try:
            return base64.b64decode(str(value or ""), validate=True)
        except Exception as exc:  # pragma: no cover - exact decoder error is not important
            raise ValueError("Novel content is not valid base64.") from exc

    @staticmethod
    def _new_run_id() -> str:
        return f"run-{uuid4().hex[:12]}"

    @staticmethod
    def _is_model_configured_payload(payload: dict[str, Any]) -> bool:
        provider = str(payload.get("provider", "")).strip()
        model = str(payload.get("model", "")).strip()
        api_key = str(payload.get("api_key", "")).strip()
        if not provider or not model:
            return False
        if provider == "ollama":
            return True
        return bool(api_key)

    @staticmethod
    def _read_preview_fields(profile_path: Path) -> dict[str, str]:
        preview: dict[str, str] = {}
        wanted = {"name", "core_identity", "story_role", "soul_goal", "speech_style", "temperament_type"}
        for raw_line in profile_path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line.startswith("- ") or ":" not in line:
                continue
            key, value = line[2:].split(":", 1)
            key = key.strip()
            if key in wanted and value.strip():
                preview[key] = value.strip()
        return preview

    def _discover_character_cards(self, characters_root: Path | None) -> list[dict[str, Any]]:
        if not characters_root or not characters_root.exists():
            return []
        cards: list[dict[str, Any]] = []
        for persona_dir in sorted(path for path in characters_root.iterdir() if path.is_dir()):
            profile_file = None
            for candidate_name in ("PROFILE.generated.md", "PROFILE.md"):
                candidate = persona_dir / candidate_name
                if candidate.exists():
                    profile_file = candidate
                    break
            if profile_file is None:
                continue
            preview = self._read_preview_fields(profile_file)
            cards.append(
                {
                    "name": preview.get("name") or persona_dir.name,
                    "persona_dir": str(persona_dir.resolve()),
                    "profile_file": str(profile_file.resolve()),
                    "generated_files": sorted(path.name for path in persona_dir.glob("*.generated.md")),
                    "editable_files": sorted(
                        path.name for path in persona_dir.glob("*.md") if not path.name.endswith(".generated.md")
                    ),
                    "preview": {
                        "core_identity": preview.get("core_identity", ""),
                        "story_role": preview.get("story_role", ""),
                        "soul_goal": preview.get("soul_goal", ""),
                        "speech_style": preview.get("speech_style", ""),
                        "temperament_type": preview.get("temperament_type", ""),
                    },
                }
            )
        return cards

    def _discover_relation_graph(
        self,
        relations_root: Path | None,
        artifact_dir: Path | None,
        run_dir: Path | None,
    ) -> dict[str, str]:
        search_roots = [root for root in (relations_root, artifact_dir, run_dir) if root and root.exists()]
        candidates: dict[str, Path] = {}
        for root in search_roots:
            for path in root.rglob("*"):
                if not path.is_file():
                    continue
                name = path.name.lower()
                if name.endswith(".html") and "relation" in name:
                    candidates.setdefault("html_path", path)
                elif name.endswith(".svg") and "relation" in name:
                    candidates.setdefault("svg_path", path)
                elif name.endswith(".mermaid.md"):
                    candidates.setdefault("mermaid_path", path)
                elif name.endswith(".status.json") and "relation" in name:
                    candidates.setdefault("relation_status_path", path)
                elif name.endswith(".md") and "relation" in name and not name.endswith(".mermaid.md"):
                    candidates.setdefault("relations_file", path)
        return {key: str(path.resolve()) for key, path in candidates.items()}
