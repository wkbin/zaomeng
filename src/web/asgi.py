"""ASGI entrypoint for uvicorn and reload mode."""

from __future__ import annotations

from .app import create_app

app = create_app()

__all__ = ["app"]
