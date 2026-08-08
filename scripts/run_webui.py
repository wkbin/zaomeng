#!/usr/bin/env python3
from __future__ import annotations

import argparse
import importlib
import os
from pathlib import Path
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

_PACKAGE_PREFIX = f"{__package__}." if __package__ else ""
_web_asset_version = importlib.import_module(f"{_PACKAGE_PREFIX}web_asset_version")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the zaomeng Web UI.")
    parser.add_argument("--host", default="127.0.0.1", help="Bind host")
    parser.add_argument("--port", type=int, default=8000, help="Bind port")
    parser.add_argument("--storage-root", help="Optional storage root for web runs")
    parser.add_argument(
        "--auth-token",
        default=str(os.getenv("ZAOMENG_WEB_AUTH_TOKEN", "")).strip(),
        help="Bearer token for API access (prefer ZAOMENG_WEB_AUTH_TOKEN)",
    )
    parser.add_argument(
        "--allow-remote-update",
        action="store_true",
        help="Allow the authenticated Web UI to execute application updates when bound remotely",
    )
    parser.add_argument("--reload", action="store_true", help="Enable auto reload")
    parser.add_argument(
        "--bump-web-assets",
        action="store_true",
        help="Bump the web static asset version before starting the Web UI.",
    )
    parser.add_argument(
        "--static-version",
        default="",
        help="Explicit web static asset version to sync before starting the Web UI.",
    )
    args = parser.parse_args()

    if args.bump_web_assets and str(args.static_version or "").strip():
        print("Use either --bump-web-assets or --static-version, not both.", file=sys.stderr)
        return 1

    if str(args.static_version or "").strip():
        _web_asset_version.sync_web_asset_version(PROJECT_ROOT, str(args.static_version).strip())
    elif args.bump_web_assets:
        _web_asset_version.bump_web_asset_version(PROJECT_ROOT)

    try:
        import uvicorn
        from src.web.app import create_app
        from src.web.security import is_loopback_host
        from src.web.workflow import WebRunService
    except ModuleNotFoundError as exc:
        print(
            "Missing web dependency. Install requirements first with "
            "`pip install -r requirements.runtime.txt` for Web UI runtime "
            "or `pip install -r requirements.txt` for development/test tooling.",
            file=sys.stderr,
        )
        print(str(exc), file=sys.stderr)
        return 1

    loopback = is_loopback_host(args.host)
    auth_token = str(args.auth_token or "").strip()
    if not loopback and not auth_token:
        parser.error(
            "--auth-token or ZAOMENG_WEB_AUTH_TOKEN is required when --host is not a loopback address"
        )
    if not loopback and len(auth_token) < 24:
        parser.error("The remote Web UI auth token must contain at least 24 characters")
    allow_app_update = loopback or bool(args.allow_remote_update)

    if args.reload:
        if args.storage_root:
            print(
                "--storage-root is not supported together with --reload yet. "
                "Run without --reload or use the default storage root.",
                file=sys.stderr,
            )
            return 1
        static_version = _web_asset_version.read_web_asset_version(PROJECT_ROOT)
        os.environ["ZAOMENG_WEB_AUTH_TOKEN"] = auth_token
        os.environ["ZAOMENG_WEB_ALLOW_APP_UPDATE"] = "1" if allow_app_update else "0"
        print(f"Starting zaomeng Web UI with static asset version: {static_version}")
        if auth_token:
            print("Authentication enabled. Open /#token=<your token> in the browser.")
        uvicorn.run("src.web.asgi:app", host=args.host, port=args.port, reload=True)
        return 0

    app = create_app(
        WebRunService(args.storage_root),
        auth_token=auth_token,
        allow_app_update=allow_app_update,
    )
    static_version = _web_asset_version.read_web_asset_version(PROJECT_ROOT)
    print(f"Starting zaomeng Web UI with static asset version: {static_version}")
    if auth_token:
        print("Authentication enabled. Open /#token=<your token> in the browser.")
    uvicorn.run(app, host=args.host, port=args.port, reload=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
