from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request

from src.web.api.deps import get_run_service
from src.web.api.schemas import SaveModelSettingsRequest, StartAppUpdateRequest, TestModelSettingsRequest
from src.web.workflow import WebRunService

router = APIRouter()


@router.get("/api/web/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@router.get("/api/web/settings/model")
def get_model_settings(run_service: WebRunService = Depends(get_run_service)) -> dict[str, Any]:
    return run_service.get_model_settings()


@router.put("/api/web/settings/model")
def save_model_settings(
    payload: SaveModelSettingsRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.save_model_settings(
            provider=payload.provider,
            model=payload.model,
            base_url=payload.base_url,
            api_key=payload.api_key,
            max_tokens=payload.max_tokens,
            reasoning_effort=payload.reasoning_effort,
            profile_id=payload.profile_id,
            profile_name=payload.profile_name,
            create_profile=payload.create_profile,
            activate_profile=payload.activate_profile,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/settings/model/test")
def test_model_settings(
    payload: TestModelSettingsRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.test_model_connection(
            provider=payload.provider,
            model=payload.model,
            base_url=payload.base_url,
            api_key=payload.api_key,
            max_tokens=payload.max_tokens,
            reasoning_effort=payload.reasoning_effort,
            profile_id=payload.profile_id,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Model connection failed: {exc}") from exc


@router.post("/api/web/settings/model/profiles/{profile_id}/activate")
def activate_model_profile(
    profile_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.activate_model_profile(profile_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Model profile was not found.") from exc


@router.delete("/api/web/settings/model/profiles/{profile_id}")
def delete_model_profile(
    profile_id: str,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.delete_model_profile(profile_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Model profile was not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/api/web/settings/update")
def get_app_update_status(
    force: bool = False,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.get_app_update_status(force_check=force)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/settings/update")
def start_app_update(
    payload: StartAppUpdateRequest,
    request: Request,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    if not bool(getattr(request.app.state, "allow_app_update", True)):
        raise HTTPException(status_code=403, detail="Remote application updates are disabled.")
    if payload.confirm != "update":
        raise HTTPException(status_code=400, detail="Application update confirmation is required.")
    try:
        return run_service.start_app_update()
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
