"""Web application layer for zaomeng.

Keep package import side effects minimal so utility modules under ``src.web``
can be imported in lightweight test/runtime environments without requiring
FastAPI and full Web service dependencies to be initialized eagerly.
"""

from __future__ import annotations

from typing import Any

__all__ = ["WebRunService", "create_app"]


def __getattr__(name: str) -> Any:
    if name == "create_app":
        from .app import create_app

        return create_app
    if name == "WebRunService":
        from .workflow import WebRunService

        return WebRunService
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
