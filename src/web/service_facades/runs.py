from __future__ import annotations

from time import perf_counter
from typing import Any
import base64
import os
import shutil
from pathlib import Path
from uuid import uuid4

from src.core.config import Config
from src.core.llm_client import LLMClient
from src.web.time_utils import utc_now as _utc_now
from src.web.run_ops import (
    build_model_settings_response,
    delete_run_group,
    delete_sessions,
    estimate_sampling_plan,
    is_model_configured_payload,
    list_recent_sessions,
    list_runs,
    normalize_model_settings,
    refresh_run_manifest,
    stop_run_manifest,
)



class RunServiceMixin:
    @staticmethod
    def _copy_writable_character_snapshot(source: Path, target: Path) -> None:
        """Copy an imported profile without carrying archive/read-only permissions."""
        if target.exists():
            raise FileExistsError(target)
        target.mkdir(parents=True, mode=0o700)
        for current_root, directory_names, file_names in os.walk(source):
            current = Path(current_root)
            relative = current.relative_to(source)
            destination = target / relative
            destination.mkdir(parents=True, exist_ok=True, mode=0o700)
            destination.chmod(0o700)
            for directory_name in directory_names:
                child = destination / directory_name
                child.mkdir(exist_ok=True, mode=0o700)
                child.chmod(0o700)
            for file_name in file_names:
                source_file = current / file_name
                destination_file = destination / file_name
                shutil.copyfile(source_file, destination_file)
                destination_file.chmod(0o600)

    def create_crossover_space(
        self,
        *,
        title: str,
        world_setting: str,
        participants: list[dict[str, str]],
    ) -> dict[str, Any]:
        if not 2 <= len(participants) <= 8:
            raise ValueError("共演空间需要选择 2 到 8 名人物。")
        selected: list[tuple[str, str, Path]] = []
        names: set[str] = set()
        for item in participants:
            run_id = str(item.get("run_id", "")).strip()
            character = str(item.get("character", "")).strip()
            if not run_id or not character or character in names:
                raise ValueError("共演人物不能为空或重名。")
            source = self._require_manifest(run_id)
            artifact = next(
                (
                    entry for entry in source.get("artifact_index", {}).get("characters", [])
                    if str(entry.get("name", "")).strip() == character
                ),
                None,
            )
            profile = Path(str((artifact or {}).get("profile_file", "")).strip())
            source_run_dir = (self.runs_root / run_id).resolve()
            try:
                profile.resolve().relative_to(source_run_dir)
            except (ValueError, OSError):
                raise FileNotFoundError(f"{run_id}:{character}")
            if not profile.exists():
                raise FileNotFoundError(f"{run_id}:{character}")
            selected.append((run_id, character, profile.parent))
            names.add(character)

        if len({run_id for run_id, _, _ in selected}) < 2:
            raise ValueError("跨书卷共演至少需要来自两个不同书卷的人物。")

        safe_title = str(title or "").strip()
        setting = str(world_setting or "").strip()
        seed = f"共演空间：{safe_title}\n世界设定：{setting or '由参与者共同展开。'}"
        created = self.create_run(
            novel_name=f"{safe_title}.txt",
            novel_content_base64=base64.b64encode(seed.encode("utf-8")).decode("ascii"),
            characters=[character for _, character, _ in selected],
            defer_run=True,
        )
        run_id = str(created["run_id"])
        manifest_path = self._manifest_path(run_id)
        try:
            manifest = self._require_manifest(run_id)
            target_root = Path(manifest["webui"]["workspace"]["characters_root"])
            for source_run_id, character, source_dir in selected:
                target = target_root / source_dir.name
                self._copy_writable_character_snapshot(source_dir, target)
            manifest = self._discover_artifacts(manifest)
            now = _utc_now()
            manifest.update({"status": "ready", "success": True, "entrypoint": "crossover_beta", "updated_at": now})
            manifest["beta_feature"] = {
                "kind": "cross_book_crossover",
                "unstable": True,
                "world_setting": setting,
                "source_snapshots": [
                    {"run_id": source_run_id, "character": character}
                    for source_run_id, character, _ in selected
                ],
            }
            manifest.setdefault("summary", {})["status_text"] = "crossover_beta_ready"
            self._write_json(manifest_path, manifest)
            return self._serialize_manifest(manifest)
        except Exception:
            shutil.rmtree(self.runs_root / run_id, ignore_errors=True)
            raise

    def estimate_sampling_plan(
        self,
        *,
        char_count: int,
        sentence_count: int,
        character_count: int,
        max_sentences: int = 120,
        max_chars: int = 50_000,
    ) -> dict[str, Any]:
        return estimate_sampling_plan(
            char_count=char_count,
            sentence_count=sentence_count,
            character_count=character_count,
            max_sentences=max_sentences,
            max_chars=max_chars,
            distill_chunk_max_chars=self.DISTILL_CHUNK_MAX_CHARS,
            distill_chunk_max_sentences=self.DISTILL_CHUNK_MAX_SENTENCES,
            relation_chunk_max_chars=self.RELATION_CHUNK_MAX_CHARS,
            relation_chunk_max_sentences=self.RELATION_CHUNK_MAX_SENTENCES,
        )

    def get_model_settings(self) -> dict[str, Any]:
        document = self._load_model_settings_document()
        payload = self._load_model_settings_payload()
        return build_model_settings_response(
            payload,
            configured=self._is_model_configured_payload(payload),
            active_profile_id=str(document.get("active_profile_id", "")).strip(),
            profiles=[self._model_profile_summary(item) for item in list(document.get("profiles", []) or [])],
        )

    def save_model_settings(
        self,
        *,
        provider: str,
        model: str,
        base_url: str = "",
        api_key: str = "",
        max_tokens: int = 0,
        reasoning_effort: str = "off",
        profile_id: str = "",
        profile_name: str = "",
        create_profile: bool = False,
        activate_profile: bool = True,
    ) -> dict[str, Any]:
        document = self._load_model_settings_document()
        profiles = list(document.get("profiles", []) or [])
        requested_profile_id = str(profile_id or "").strip()
        active_profile_id = str(document.get("active_profile_id", "")).strip()
        if create_profile:
            requested_profile_id = f"profile-{uuid4().hex[:12]}"
            existing: dict[str, Any] = {}
        else:
            requested_profile_id = requested_profile_id or active_profile_id or "default"
            existing = next(
                (dict(item) for item in profiles if str(item.get("profile_id", "")).strip() == requested_profile_id),
                {},
            )
        existing["api_key"] = self._secret_store.read(self._model_profile_secret_name(existing))
        normalized = normalize_model_settings(
            existing=existing,
            provider=provider,
            model=model,
            base_url=base_url,
            api_key=api_key,
            max_tokens=max_tokens,
            reasoning_effort=reasoning_effort,
            utc_now=_utc_now,
        )
        normalized["profile_id"] = requested_profile_id
        normalized["name"] = str(profile_name or existing.get("name", "")).strip() or str(model).strip()
        secret_name = self._model_profile_secret_name(normalized)
        api_key_value = str(normalized.pop("api_key", "")).strip()
        if api_key_value:
            self._secret_store.write(secret_name, api_key_value)
        normalized["api_key_ref"] = secret_name
        replaced = False
        next_profiles: list[dict[str, Any]] = []
        for item in profiles:
            if str(item.get("profile_id", "")).strip() == requested_profile_id:
                next_profiles.append(normalized)
                replaced = True
            else:
                next_profiles.append(item)
        if not replaced:
            next_profiles.append(normalized)
        self._write_json(
            self.settings_path,
            {
                "version": 2,
                "active_profile_id": requested_profile_id if activate_profile or not active_profile_id else active_profile_id,
                "profiles": next_profiles,
            },
        )
        return self.get_model_settings()

    def activate_model_profile(self, profile_id: str) -> dict[str, Any]:
        document = self._load_model_settings_document()
        selected = str(profile_id or "").strip()
        if not any(str(item.get("profile_id", "")).strip() == selected for item in document.get("profiles", [])):
            raise FileNotFoundError(selected)
        document["active_profile_id"] = selected
        self._write_json(self.settings_path, document)
        return self.get_model_settings()

    def test_model_connection(
        self,
        *,
        provider: str,
        model: str,
        base_url: str = "",
        api_key: str = "",
        max_tokens: int = 0,
        reasoning_effort: str = "off",
        profile_id: str = "",
    ) -> dict[str, Any]:
        document = self._load_model_settings_document()
        requested_profile_id = str(profile_id or "").strip()
        existing = next(
            (
                dict(item)
                for item in document.get("profiles", [])
                if str(item.get("profile_id", "")).strip() == requested_profile_id
            ),
            {},
        )
        existing["api_key"] = self._secret_store.read(self._model_profile_secret_name(existing))
        payload = normalize_model_settings(
            existing=existing,
            provider=provider,
            model=model,
            base_url=base_url,
            api_key=api_key,
            max_tokens=max_tokens,
            reasoning_effort=reasoning_effort,
            utc_now=_utc_now,
        )
        config = Config()
        config.update(
            {
                "llm": {
                    "provider": payload["provider"],
                    "model": payload["model"],
                    "base_url": payload["base_url"],
                    "api_key": payload["api_key"],
                    "max_tokens": min(max(1, int(payload["max_tokens"] or 0)), 32) or 16,
                    "reasoning_effort": payload["reasoning_effort"],
                    "timeout_seconds": 20,
                    "retry_attempts": 0,
                }
            }
        )
        started = perf_counter()
        result = LLMClient(config).chat_completion(
            [{"role": "user", "content": "Reply with exactly: OK"}],
            temperature=0,
            max_tokens=16,
        )
        return {
            "ok": True,
            "provider": str(result.get("provider", payload["provider"])).strip(),
            "model": str(result.get("model", payload["model"])).strip(),
            "latency_ms": round((perf_counter() - started) * 1000),
            "message": "连接成功。",
        }

    def delete_model_profile(self, profile_id: str) -> dict[str, Any]:
        document = self._load_model_settings_document()
        selected = str(profile_id or "").strip()
        profiles = list(document.get("profiles", []) or [])
        target = next((item for item in profiles if str(item.get("profile_id", "")).strip() == selected), None)
        if target is None:
            raise FileNotFoundError(selected)
        if len(profiles) <= 1:
            raise ValueError("At least one model profile must remain.")
        self._secret_store.delete(self._model_profile_secret_name(target))
        remaining = [item for item in profiles if str(item.get("profile_id", "")).strip() != selected]
        active = str(document.get("active_profile_id", "")).strip()
        document["profiles"] = remaining
        document["active_profile_id"] = active if active != selected else str(remaining[0].get("profile_id", "")).strip()
        self._write_json(self.settings_path, document)
        return self.get_model_settings()

    def _model_profile_summary(self, profile: dict[str, Any]) -> dict[str, Any]:
        secret_name = self._model_profile_secret_name(profile)
        return {
            "profile_id": str(profile.get("profile_id", "")).strip(),
            "name": str(profile.get("name", "")).strip(),
            "provider": str(profile.get("provider", "")).strip(),
            "model": str(profile.get("model", "")).strip(),
            "base_url": str(profile.get("base_url", "")).strip(),
            "max_tokens": max(0, int(profile.get("max_tokens", 0) or 0)),
            "reasoning_effort": str(
                profile.get("reasoning_effort", "off")
            ).strip().lower()
            or "auto",
            "api_key_configured": bool(self._secret_store.read(secret_name)),
            "configured": self._is_model_configured_payload(
                {**profile, "api_key": self._secret_store.read(secret_name)}
            ),
        }

    def model_is_configured(self) -> bool:
        return is_model_configured_payload(self._load_model_settings_payload())

    def list_runs(self) -> list[dict[str, Any]]:
        return list_runs(
            runs_root=self.runs_root,
            load_manifest=self._load_manifest,
            serialize_manifest=self._serialize_manifest,
        )

    def list_recent_sessions(self) -> list[dict[str, Any]]:
        return list_recent_sessions(
            runs_root=self.runs_root,
            load_manifest=self._load_manifest,
            list_sessions=self.dialogue.list_sessions,
        )

    def delete_recent_sessions(self, items: list[dict[str, str]]) -> dict[str, Any]:
        return delete_sessions(
            items=items,
            delete_session=self.delete_dialogue_session,
        )

    def get_run(self, run_id: str) -> dict[str, Any]:
        manifest_path = self._manifest_path(run_id)
        payload = self._load_manifest(manifest_path)
        if not payload:
            raise FileNotFoundError(run_id)
        return self._serialize_manifest(payload)

    def refresh_run(self, run_id: str) -> dict[str, Any]:
        manifest_path = self._manifest_path(run_id)
        refreshed = self._update_manifest(
            manifest_path,
            lambda current: refresh_run_manifest(
                current,
                discover_artifacts=self._discover_artifacts,
                utc_now=_utc_now,
            ),
        )
        return self._serialize_manifest(refreshed)

    def stop_run(self, run_id: str) -> dict[str, Any]:
        manifest_path = self._manifest_path(run_id)
        manifest = self._update_manifest(
            manifest_path,
            lambda current: stop_run_manifest(current, utc_now=_utc_now),
        )
        return self._serialize_manifest(manifest)

    def delete_run_group(self, run_id: str) -> dict[str, Any]:
        return delete_run_group(
            run_id=run_id,
            runs_root=self.runs_root,
            require_manifest=self._require_manifest,
            load_manifest=self._load_manifest,
        )

    def create_run(
        self,
        *,
        novel_name: str,
        novel_content_base64: str,
        characters: list[str],
        max_sentences: int = 120,
        max_chars: int = 50_000,
        auto_run: bool = False,
        defer_run: bool = False,
    ) -> dict[str, Any]:
        if auto_run and defer_run:
            raise ValueError("A deferred run cannot start automatically.")
        if not defer_run and not self.model_is_configured():
            raise ValueError("Model is not configured yet.")
        prepared = self._prepare_create_run(
            novel_name=novel_name,
            novel_content_base64=novel_content_base64,
            characters=characters,
        )
        locked_characters = prepared["locked_characters"]
        manifest = prepared["manifest"]
        manifest_path = prepared["manifest_path"]
        novel_path = prepared["novel_path"]

        if defer_run:
            manifest = self._prepare_deferred_run_manifest(manifest)
            self._write_json(manifest_path, manifest)
            return self._serialize_manifest(manifest)

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

        manifest = self._prepare_manual_run_manifest(
            manifest=manifest,
            manifest_path=manifest_path,
            novel_path=novel_path,
            payload_dir=prepared["payload_dir"],
            characters_root=prepared["characters_root"],
            locked_characters=locked_characters,
            max_sentences=max_sentences,
            max_chars=max_chars,
        )
        self._write_json(manifest_path, manifest)
        return self._serialize_manifest(manifest)

    def restart_run_distill(
        self,
        run_id: str,
        *,
        characters: list[str],
        novel_name: str = "",
        novel_content_base64: str = "",
        max_sentences: int = 120,
        max_chars: int = 50_000,
    ) -> dict[str, Any]:
        if not self.model_is_configured():
            raise ValueError("Model is not configured yet.")
        prepared = self._prepare_restart_run(
            run_id,
            characters=characters,
            novel_name=novel_name,
            novel_content_base64=novel_content_base64,
        )
        manifest = prepared["manifest"]
        manifest_path = prepared["manifest_path"]
        novel_path = prepared["novel_path"]
        locked_characters = prepared["locked_characters"]
        relation_characters = prepared["relation_characters"]
        self._write_json(manifest_path, manifest)
        self._start_background_run(
            manifest_path=manifest_path,
            novel_path=novel_path,
            locked_characters=locked_characters,
            relation_characters=relation_characters,
            max_sentences=max_sentences,
            max_chars=max_chars,
        )
        return self._serialize_manifest(self._load_manifest(manifest_path) or manifest)

    def resume_unfinished_characters(self, run_id: str) -> dict[str, Any]:
        """Continue a stopped or failed run without reprocessing completed personas."""
        manifest = self._load_manifest(self._manifest_path(run_id))
        if not manifest:
            raise FileNotFoundError(run_id)
        if str(manifest.get("status", "")).strip() == "running":
            raise ValueError("This run is already running.")

        locked_characters = self._normalize_characters(list(manifest.get("locked_characters", []) or []))
        completed_characters = set(
            self._normalize_characters(list((manifest.get("progress", {}) or {}).get("completed_characters", []) or []))
        )
        unfinished_characters = [name for name in locked_characters if name not in completed_characters]
        if not unfinished_characters:
            raise ValueError("All locked characters have already been distilled.")
        return self.restart_run_distill(run_id, characters=unfinished_characters)
