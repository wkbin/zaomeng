from __future__ import annotations

from pathlib import Path
from typing import Any

from src.web.time_utils import utc_now as _utc_now
from src.web.persona_avatars import avatar_path, avatar_version
from src.web.artifacts import discover_character_cards, discover_relation_graph
from src.web.chat import load_pending_turn_payload as load_dialogue_pending_turn_payload
from src.web.manifest import (
    build_file_urls,
    discover_artifacts,
    ensure_run_exists,
    file_url,
    load_json_file,
    load_manifest,
    manifest_path,
    reconcile_loaded_manifest,
    relative_to_run_dir,
    require_manifest,
    serialize_manifest,
)
from src.web.pipeline import assert_run_not_stopped, build_progress_chunking_from_artifacts, build_summary_chunking
from src.web.run_ops import finalize_manifest_timing, format_elapsed_text, is_stop_requested



class CoreServiceMixin:
    def _serialize_manifest(self, payload: dict[str, Any]) -> dict[str, Any]:
        run_id = str(payload.get("run_id", "")).strip()
        file_urls = self._build_file_urls(run_id, payload) if run_id else {}
        serialized = serialize_manifest(payload, run_id=run_id, file_urls=file_urls)
        for persona in serialized.get("artifact_index", {}).get("characters", []):
            if isinstance(persona, dict):
                persona["avatar_version"] = avatar_version(
                    avatar_path(self.runs_root / run_id, str(persona.get("name", "")))
                ) if run_id else ""
        return serialized

    def _discover_artifacts(self, manifest: dict[str, Any]) -> dict[str, Any]:
        return discover_artifacts(
            manifest,
            discover_character_cards=discover_character_cards,
            discover_relation_graph=discover_relation_graph,
            build_progress_chunking_from_artifacts=build_progress_chunking_from_artifacts,
            build_summary_chunking=build_summary_chunking,
        )

    @staticmethod
    def _format_elapsed_text(seconds: float) -> str:
        return format_elapsed_text(seconds)

    def _finalize_manifest_timing(self, manifest: dict[str, Any], *, outcome: str) -> None:
        finalize_manifest_timing(manifest, outcome=outcome, now_text=_utc_now())

    def _is_stop_requested(self, manifest_path: Path) -> bool:
        return is_stop_requested(manifest_path, load_manifest=self._load_manifest)

    def _assert_run_not_stopped(
        self,
        manifest_path: Path,
        *,
        message: str = "这次蒸馏已停止。",
        current_character: str = "",
    ) -> None:
        assert_run_not_stopped(
            manifest_path,
            message=message,
            current_character=current_character,
            update_manifest=self._update_manifest,
            utc_now=_utc_now,
            is_stop_requested=self._is_stop_requested,
            stopped_error_type=self.STOPPED_ERROR_TYPE,
        )

    def _build_file_urls(self, run_id: str, manifest: dict[str, Any]) -> dict[str, str]:
        current_manifest_path = self._manifest_path(run_id)
        run_dir = self.runs_root / run_id
        return build_file_urls(
            run_id=run_id,
            manifest=manifest,
            manifest_path=current_manifest_path,
            run_dir=run_dir,
        )

    def _file_url(self, run_id: str, relative_path: Path) -> str:
        return file_url(run_id, relative_path)

    @staticmethod
    def _relative_to_run_dir(path: Path, run_dir: Path) -> Path | None:
        return relative_to_run_dir(path, run_dir)

    def _manifest_path(self, run_id: str) -> Path:
        return manifest_path(self.runs_root, run_id)

    def _require_manifest(self, run_id: str) -> dict[str, Any]:
        return require_manifest(run_id, loader=self._load_manifest, runs_root=self.runs_root)

    def _ensure_run_exists(self, run_id: str) -> None:
        ensure_run_exists(self.runs_root, run_id)

    def _load_manifest(self, current_manifest_path: Path) -> dict[str, Any] | None:
        return load_manifest(
            current_manifest_path,
            reconcile=self._reconcile_loaded_manifest,
            writer=self._write_json,
        )

    def _reconcile_loaded_manifest(
        self,
        current_manifest_path: Path,
        payload: dict[str, Any],
    ) -> tuple[dict[str, Any], bool]:
        return reconcile_loaded_manifest(
            current_manifest_path,
            payload,
            is_thread_alive=lambda run_id: bool((thread := self._active_run_threads.get(run_id)) and thread.is_alive()),
            utc_now=_utc_now,
            finalize_manifest_timing=lambda manifest, outcome: self._finalize_manifest_timing(manifest, outcome=outcome),
        )

    def _load_model_settings_payload(self) -> dict[str, Any]:
        document = self._load_model_settings_document()
        profiles = list(document.get("profiles", []) or [])
        active_profile_id = str(document.get("active_profile_id", "")).strip()
        payload = next(
            (dict(item) for item in profiles if str(item.get("profile_id", "")).strip() == active_profile_id),
            dict(profiles[0]) if profiles else {},
        )
        if not payload:
            return {}
        secret_name = self._model_profile_secret_name(payload)
        inline_api_key = str(payload.get("api_key", "")).strip()
        stored_api_key = self._secret_store.read(secret_name)
        if inline_api_key:
            self._secret_store.write(secret_name, inline_api_key)
            stored_api_key = inline_api_key
            sanitized = dict(payload)
            sanitized.pop("api_key", None)
            sanitized["api_key_ref"] = secret_name
            document["profiles"] = [
                sanitized if str(item.get("profile_id", "")).strip() == str(payload.get("profile_id", "")).strip() else item
                for item in profiles
            ]
            self._write_json(self.settings_path, document)
        resolved = dict(payload)
        resolved["api_key"] = stored_api_key or inline_api_key
        return resolved

    def _load_model_settings_document(self) -> dict[str, Any]:
        payload = self._load_json_file(self.settings_path) or {}
        profiles = [dict(item) for item in list(payload.get("profiles", []) or []) if isinstance(item, dict)]
        if profiles:
            active_profile_id = str(payload.get("active_profile_id", "")).strip()
            if not any(str(item.get("profile_id", "")).strip() == active_profile_id for item in profiles):
                active_profile_id = str(profiles[0].get("profile_id", "")).strip()
            return {"version": 2, "active_profile_id": active_profile_id, "profiles": profiles}
        legacy_keys = {"provider", "model", "base_url", "api_key", "api_key_ref", "max_tokens", "updated_at"}
        if not any(key in payload for key in legacy_keys):
            return {"version": 2, "active_profile_id": "", "profiles": []}
        profile = {
            "profile_id": "default",
            "name": str(payload.get("name", "")).strip() or "默认模型",
            "provider": str(payload.get("provider", "")).strip(),
            "model": str(payload.get("model", "")).strip(),
            "base_url": str(payload.get("base_url", "")).strip(),
            "max_tokens": int(payload.get("max_tokens", 0) or 0),
            "updated_at": str(payload.get("updated_at", "")).strip(),
            "api_key_ref": str(payload.get("api_key_ref", "")).strip() or self._model_api_key_secret_name,
        }
        if str(payload.get("api_key", "")).strip():
            profile["api_key"] = str(payload.get("api_key", "")).strip()
        return {"version": 2, "active_profile_id": "default", "profiles": [profile]}

    def _model_profile_secret_name(self, profile: dict[str, Any]) -> str:
        configured = str(profile.get("api_key_ref", "")).strip()
        if configured:
            return configured
        profile_id = str(profile.get("profile_id", "")).strip()
        return self._model_api_key_secret_name if profile_id in {"", "default"} else f"{self._model_api_key_secret_name}_{profile_id}"

    def _load_pending_turn_payload(self, run_id: str, session_id: str) -> dict[str, Any]:
        return load_dialogue_pending_turn_payload(
            runs_root=self.runs_root,
            run_id=run_id,
            session_id=session_id,
            load_json_file=self._load_json_file,
        )

    @staticmethod
    def _load_json_file(path: Path) -> dict[str, Any] | None:
        return load_json_file(path)
