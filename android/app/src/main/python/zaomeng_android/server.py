from __future__ import annotations

import os
import json
import shutil
import socket
import threading
from pathlib import Path
from typing import Any

import uvicorn

from src.web.app import create_app
from src.web.secrets import InMemorySecretStore, ProtectedSecretStore
from src.web.workflow import WebRunService
from zaomeng_android.recovery import audit_runtime_storage, record_startup_recovery, recover_interrupted_runs


_lock = threading.RLock()
_server: uvicorn.Server | None = None
_thread: threading.Thread | None = None
_port = 0
_error = ""


def _bundled_resource_root() -> Path:
    import src

    return Path(src.__file__).resolve().parent.parent


def _seed_missing_resources(storage_root: Path) -> None:
    bundled_root = _bundled_resource_root()
    for directory_name in ("rules", "zaomeng-skill"):
        source_root = bundled_root / directory_name
        if not source_root.is_dir():
            raise FileNotFoundError(f"Bundled resource directory is missing: {directory_name}")
        target_root = storage_root / directory_name
        for source in source_root.rglob("*"):
            if not source.is_file():
                continue
            target = target_root / source.relative_to(source_root)
            if target.exists():
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)


def _available_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def _serve(server: uvicorn.Server) -> None:
    global _error
    try:
        server.run()
    except BaseException as exc:  # surfaced to Kotlin through status()
        _error = f"{type(exc).__name__}: {exc}"


def _settings_secret_names_and_inline_values(storage_root: Path) -> tuple[set[str], dict[str, str]]:
    names = {"model_api_key"}
    inline: dict[str, str] = {}
    settings_path = storage_root / "model_settings.json"
    try:
        document = json.loads(settings_path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return names, inline
    profiles = document.get("profiles") if isinstance(document, dict) else None
    if not isinstance(profiles, list):
        profiles = [document] if isinstance(document, dict) else []
    for profile in profiles:
        if not isinstance(profile, dict):
            continue
        profile_id = str(profile.get("profile_id", "")).strip()
        secret_name = str(profile.get("api_key_ref", "")).strip()
        if not secret_name:
            secret_name = "model_api_key" if profile_id in {"", "default"} else f"model_api_key_{profile_id}"
        names.add(secret_name)
        api_key = str(profile.get("api_key", "")).strip()
        if api_key:
            inline[secret_name] = api_key
    return names, inline


def read_legacy_model_secrets(storage_root: str) -> str:
    """Return legacy plaintext secrets so Kotlin can migrate them to Keystore."""
    root = Path(str(storage_root)).resolve()
    names, values = _settings_secret_names_and_inline_values(root)
    store = ProtectedSecretStore(root / "secrets")
    for name in names:
        stored = store.read(name)
        if stored:
            values[name] = stored
    return json.dumps(values, ensure_ascii=False)


def purge_legacy_model_secrets(storage_root: str) -> None:
    """Remove plaintext model keys after Kotlin confirms Keystore migration."""
    root = Path(str(storage_root)).resolve()
    settings_path = root / "model_settings.json"
    try:
        document = json.loads(settings_path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        document = None
    changed = False
    if isinstance(document, dict):
        profiles = document.get("profiles")
        targets = profiles if isinstance(profiles, list) else [document]
        for profile in targets:
            if isinstance(profile, dict) and "api_key" in profile:
                profile.pop("api_key", None)
                changed = True
        if changed:
            temporary = settings_path.with_suffix(".json.tmp")
            temporary.write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding="utf-8")
            os.replace(temporary, settings_path)
    secrets_root = root / "secrets"
    if secrets_root.is_dir():
        for child in secrets_root.iterdir():
            if child.is_file() and child.name.startswith("model_api_key"):
                child.unlink(missing_ok=True)


def start(storage_root: str, auth_token: str, model_secrets_json: str = "{}") -> int:
    """Start one process-local FastAPI server and return its loopback port."""
    global _server, _thread, _port, _error
    with _lock:
        if _thread is not None and _thread.is_alive() and _port:
            return _port

        root = Path(str(storage_root)).resolve()
        root.mkdir(parents=True, exist_ok=True)
        os.environ["ZAOMENG_RUNTIME_ROOT"] = str(root)
        _seed_missing_resources(root)
        token = str(auth_token or "").strip()
        if len(token) < 24:
            raise ValueError("The local API token must contain at least 24 characters.")

        audit_runtime_storage(root)
        recovered_run_ids = recover_interrupted_runs(root)
        record_startup_recovery(root, recovered_run_ids)

        _port = _available_loopback_port()
        _error = ""
        try:
            model_secrets = json.loads(str(model_secrets_json or "{}"))
        except (TypeError, ValueError):
            model_secrets = {}
        app = create_app(
            WebRunService(root, secret_store=InMemorySecretStore(model_secrets)),
            auth_token=token,
            allow_app_update=False,
            serve_static=False,
        )
        config = uvicorn.Config(
            app,
            host="127.0.0.1",
            port=_port,
            log_level="warning",
            access_log=False,
        )
        _server = uvicorn.Server(config)
        _thread = threading.Thread(
            target=_serve,
            args=(_server,),
            name="zaomeng-local-api",
            daemon=True,
        )
        _thread.start()
        return _port


def status() -> dict[str, Any]:
    with _lock:
        return {
            "running": bool(_thread is not None and _thread.is_alive()),
            "started": bool(_server is not None and _server.started),
            "port": _port,
            "error": _error,
        }


def startup_error() -> str:
    """Return the exception raised by the server thread, if any."""
    with _lock:
        error = str(_error or "").strip()
        if error:
            return error
        if _thread is not None and not _thread.is_alive() and not bool(_server and _server.started):
            return "Uvicorn server thread exited before startup completed."
        return ""


def stop() -> None:
    with _lock:
        if _server is not None:
            _server.should_exit = True
