from __future__ import annotations

from typing import Any, Callable


REASONING_EFFORTS = {"auto", "off", "low", "medium", "high", "xhigh"}


def build_model_settings_response(
    payload: dict[str, Any],
    *,
    configured: bool,
    active_profile_id: str = "",
    profiles: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    provider = str(payload.get("provider", "")).strip()
    model = str(payload.get("model", "")).strip()
    base_url = str(payload.get("base_url", "")).strip()
    api_key = str(payload.get("api_key", "")).strip()
    max_tokens = int(payload.get("max_tokens", 0) or 0)
    reasoning_effort = str(payload.get("reasoning_effort", "off")).strip().lower() or "off"
    response = {
        "provider": provider,
        "model": model,
        "base_url": base_url,
        "max_tokens": max_tokens,
        "reasoning_effort": reasoning_effort,
        "api_key_configured": bool(api_key),
        "configured": configured,
    }
    if profiles is not None:
        response["active_profile_id"] = str(active_profile_id or "").strip()
        response["profiles"] = profiles
    return response


def normalize_model_settings(
    *,
    existing: dict[str, Any],
    provider: str,
    model: str,
    base_url: str = "",
    api_key: str = "",
    max_tokens: int = 0,
    reasoning_effort: str = "off",
    utc_now: Callable[[], str],
) -> dict[str, Any]:
    normalized_api_key = str(api_key or "").strip() or str(existing.get("api_key", "")).strip()
    normalized = {
        "provider": str(provider or "").strip(),
        "model": str(model or "").strip(),
        "base_url": str(base_url or "").strip(),
        "api_key": normalized_api_key,
        "max_tokens": max(0, int(max_tokens or 0)),
        "reasoning_effort": str(reasoning_effort or "off").strip().lower() or "off",
        "updated_at": utc_now(),
    }
    validate_model_settings(normalized)
    return normalized


def validate_model_settings(payload: dict[str, Any]) -> None:
    if not str(payload.get("provider", "")).strip():
        raise ValueError("Model provider is required.")
    if not str(payload.get("model", "")).strip():
        raise ValueError("Model name is required.")
    if str(payload.get("provider", "")).strip() != "ollama" and not str(payload.get("api_key", "")).strip():
        raise ValueError("API key is required for the selected provider.")
    if str(payload.get("reasoning_effort", "off")).strip().lower() not in REASONING_EFFORTS:
        raise ValueError("Reasoning effort must be auto, off, low, medium, high, or xhigh.")
