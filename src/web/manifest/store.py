from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any, Callable

from src.web.path_safety import resolve_storage_child
from src.web.run_ops.state import project_manifest_summary

from .compat import rewrite_run_root_paths


def manifest_path(runs_root: Path, run_id: str) -> Path:
    return resolve_storage_child(runs_root, run_id, field_name="run_id") / "run_manifest.json"


def require_manifest(
    run_id: str,
    *,
    loader: Callable[[Path], dict[str, Any] | None],
    runs_root: Path,
) -> dict[str, Any]:
    payload = loader(manifest_path(runs_root, run_id))
    if not payload:
        raise FileNotFoundError(run_id)
    return payload


def ensure_run_exists(runs_root: Path, run_id: str) -> None:
    if not manifest_path(runs_root, run_id).exists():
        raise FileNotFoundError(run_id)


def load_json_file(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None


def write_json_file(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    temp_name = ""
    try:
        with tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as temp_file:
            temp_name = temp_file.name
            temp_file.write(text)
            temp_file.flush()
            os.fsync(temp_file.fileno())
        os.replace(temp_name, path)
    finally:
        if temp_name:
            temp_path = Path(temp_name)
            if temp_path.exists():
                temp_path.unlink()


def load_manifest(
    manifest_path_value: Path,
    *,
    reconcile: Callable[[Path, dict[str, Any]], tuple[dict[str, Any], bool]],
    writer: Callable[[Path, dict[str, Any]], None],
) -> dict[str, Any] | None:
    if not manifest_path_value.exists():
        return None
    try:
        payload = json.loads(manifest_path_value.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None
    payload, changed = reconcile(manifest_path_value, payload)
    if changed:
        writer(manifest_path_value, payload)
    return payload


def reconcile_loaded_manifest(
    manifest_path_value: Path,
    payload: dict[str, Any],
    *,
    is_thread_alive: Callable[[str], bool],
    utc_now: Callable[[], str],
    finalize_manifest_timing: Callable[[dict[str, Any], str], None],
) -> tuple[dict[str, Any], bool]:
    manifest = dict(payload or {})
    target_root = manifest_path_value.parent.resolve(strict=False)
    canonical_run_id = manifest_path_value.parent.name.strip()
    changed = False

    source_root_text = str(dict(manifest.get("webui", {}) or {}).get("run_dir", "")).strip()
    if source_root_text:
        source_root = Path(source_root_text)
        if str(source_root.resolve(strict=False)) != str(target_root):
            manifest = rewrite_run_root_paths(
                manifest,
                source_root=source_root,
                target_root=target_root,
            )
            changed = True

    run_id = str(manifest.get("run_id", "")).strip()
    if canonical_run_id and run_id != canonical_run_id:
        manifest["run_id"] = canonical_run_id
        run_id = canonical_run_id
        changed = True

    webui = manifest.setdefault("webui", {})
    canonical_paths = {
        "run_dir": str(target_root),
        "input_dir": str((target_root / "input").resolve(strict=False)),
        "payload_dir": str((target_root / "payloads").resolve(strict=False)),
        "artifact_dir": str((target_root / "artifacts").resolve(strict=False)),
    }
    for key, value in canonical_paths.items():
        if str(webui.get(key, "")).strip() != value:
            webui[key] = value
            changed = True

    run_id = run_id or canonical_run_id
    status = str(manifest.get("status", "")).strip()
    control = dict(manifest.get("control", {}) or {})
    thread_alive = is_thread_alive(run_id)
    if status == "running" and bool(control.get("stop_requested", False)) and not thread_alive:
        now_text = utc_now()
        manifest["status"] = "stopped"
        manifest["success"] = False
        manifest["updated_at"] = now_text
        progress = manifest.setdefault("progress", {})
        progress["stage"] = "stopped"
        current_character = str(progress.get("current_character", "")).strip()
        progress["message"] = f"已停止蒸馏，停在 {current_character}。" if current_character else "这次蒸馏已停止。"
        control["stop_acknowledged_at"] = str(control.get("stop_acknowledged_at", "")).strip() or now_text
        manifest["control"] = control
        finalize_manifest_timing(manifest, "stopped")
        manifest.setdefault("capabilities", {})["verify_workflow"] = {
            "status": "stopped",
            "success": False,
            "updated_at": now_text,
            "message": "automatic workflow stopped after restart reconciliation",
        }
        events = manifest.setdefault("events", [])
        if not any(str(item.get("stage", "")).strip() == "stopped" for item in events if isinstance(item, dict)):
            events.append(
                {
                    "stage": "stopped",
                    "status": "stopped",
                    "message": progress["message"],
                    "character": current_character,
                    "capability": "verify_workflow",
                    "timestamp": now_text,
                }
            )
        project_manifest_summary(manifest)
        changed = True
    return manifest, changed
