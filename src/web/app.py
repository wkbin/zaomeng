from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from src.web.workflow import WebRunService


class CreateRunRequest(BaseModel):
    novel_name: str = Field(..., min_length=1)
    novel_content_base64: str = Field(..., min_length=1)
    characters: list[str] = Field(default_factory=list, min_length=1)
    max_sentences: int = Field(default=120, ge=20, le=300)
    max_chars: int = Field(default=50_000, ge=2_000, le=200_000)
    auto_run: bool = Field(default=False)


class RestartRunRequest(BaseModel):
    characters: list[str] = Field(default_factory=list)
    max_sentences: int = Field(default=120, ge=20, le=300)
    max_chars: int = Field(default=50_000, ge=2_000, le=200_000)


class SaveModelSettingsRequest(BaseModel):
    provider: str = Field(..., min_length=1)
    model: str = Field(..., min_length=1)
    base_url: str = Field(default="")
    api_key: str = Field(default="")


class IngestCharacterRequest(BaseModel):
    character: str = Field(..., min_length=1)
    content_base64: str = Field(..., min_length=1)
    filename: str = Field(default="PROFILE.generated.md")


class IngestRelationRequest(BaseModel):
    content_base64: str = Field(..., min_length=1)
    filename: str = Field(default="relations.md")


class CreateDialogueSessionRequest(BaseModel):
    mode: str = Field(..., pattern="^(act|insert|observe)$")
    participants: list[str] = Field(default_factory=list)
    controlled_character: str = Field(default="")
    self_profile: dict[str, str] = Field(default_factory=dict)


class PrepareDialogueTurnRequest(BaseModel):
    message: str = Field(..., min_length=1)


class DialogueResponseItem(BaseModel):
    speaker: str = Field(..., min_length=1)
    message: str = Field(..., min_length=1)


class IngestDialogueTurnRequest(BaseModel):
    responses: list[DialogueResponseItem] = Field(default_factory=list, min_length=1)


def create_app(service: WebRunService | None = None) -> FastAPI:
    app = FastAPI(title="zaomeng webui", version="0.1.0")
    run_service = service or WebRunService()
    static_dir = Path(__file__).resolve().parent / "static"
    app.mount("/web", StaticFiles(directory=static_dir, html=True), name="web")

    @app.get("/")
    def root() -> FileResponse:
        return FileResponse(static_dir / "index.html")

    @app.get("/api/web/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/api/web/settings/model")
    def get_model_settings() -> dict[str, Any]:
        return run_service.get_model_settings()

    @app.put("/api/web/settings/model")
    def save_model_settings(payload: SaveModelSettingsRequest) -> dict[str, Any]:
        try:
            return run_service.save_model_settings(
                provider=payload.provider,
                model=payload.model,
                base_url=payload.base_url,
                api_key=payload.api_key,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/web/runs")
    def list_runs() -> dict[str, Any]:
        return {"items": run_service.list_runs()}

    @app.get("/api/web/sessions")
    def list_recent_sessions() -> dict[str, Any]:
        return {"items": run_service.list_recent_sessions()}

    @app.post("/api/web/runs")
    def create_run_route(payload: CreateRunRequest) -> dict[str, Any]:
        try:
            run = run_service.create_run(
                novel_name=payload.novel_name,
                novel_content_base64=payload.novel_content_base64,
                characters=payload.characters,
                max_sentences=payload.max_sentences,
                max_chars=payload.max_chars,
                auto_run=payload.auto_run,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return run

    @app.get("/api/web/runs/{run_id}")
    def get_run(run_id: str) -> dict[str, Any]:
        try:
            return run_service.get_run(run_id)
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Run not found.") from exc

    @app.post("/api/web/runs/{run_id}/redistill")
    def redistill_run(run_id: str, payload: RestartRunRequest) -> dict[str, Any]:
        try:
            return run_service.restart_run_distill(
                run_id,
                characters=payload.characters,
                max_sentences=payload.max_sentences,
                max_chars=payload.max_chars,
            )
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Run not found.") from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/web/runs/{run_id}/refresh")
    def refresh_run(run_id: str) -> dict[str, Any]:
        try:
            return run_service.refresh_run(run_id)
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Run not found.") from exc

    @app.post("/api/web/runs/{run_id}/ingest/character")
    def ingest_character(run_id: str, payload: IngestCharacterRequest) -> dict[str, Any]:
        try:
            return run_service.ingest_character_result(
                run_id,
                character=payload.character,
                content_base64=payload.content_base64,
                filename=payload.filename,
            )
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Run not found.") from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/web/runs/{run_id}/ingest/relation")
    def ingest_relation(run_id: str, payload: IngestRelationRequest) -> dict[str, Any]:
        try:
            return run_service.ingest_relation_result(
                run_id,
                content_base64=payload.content_base64,
                filename=payload.filename,
            )
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Run not found.") from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/web/runs/{run_id}/files/{relative_path:path}")
    def get_run_file(run_id: str, relative_path: str) -> FileResponse:
        try:
            file_path = run_service.resolve_run_file(run_id, relative_path)
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="File not found.") from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return FileResponse(file_path)

    @app.get("/api/web/runs/{run_id}/dialogue/sessions")
    def list_dialogue_sessions(run_id: str) -> dict[str, Any]:
        try:
            return {"items": run_service.list_dialogue_sessions(run_id)}
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Run not found.") from exc

    @app.post("/api/web/runs/{run_id}/dialogue/sessions")
    def create_dialogue_session(run_id: str, payload: CreateDialogueSessionRequest) -> dict[str, Any]:
        try:
            return run_service.create_dialogue_session(
                run_id,
                mode=payload.mode,
                participants=payload.participants,
                controlled_character=payload.controlled_character,
                self_profile=payload.self_profile,
            )
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Run not found.") from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/web/runs/{run_id}/dialogue/sessions/{session_id}")
    def get_dialogue_session(run_id: str, session_id: str) -> dict[str, Any]:
        try:
            return run_service.get_dialogue_session(run_id, session_id)
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Session not found.") from exc

    @app.delete("/api/web/runs/{run_id}/dialogue/sessions/{session_id}")
    def delete_dialogue_session(run_id: str, session_id: str) -> dict[str, str]:
        try:
            run_service.delete_dialogue_session(run_id, session_id)
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Session not found.") from exc
        return {"status": "deleted"}

    @app.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/prepare")
    def prepare_dialogue_turn(run_id: str, session_id: str, payload: PrepareDialogueTurnRequest) -> dict[str, Any]:
        try:
            return run_service.prepare_dialogue_turn(run_id, session_id=session_id, message=payload.message)
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Session not found.") from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply")
    def reply_dialogue_turn(run_id: str, session_id: str, payload: PrepareDialogueTurnRequest) -> dict[str, Any]:
        try:
            return run_service.reply_dialogue_turn(run_id, session_id=session_id, message=payload.message)
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Session not found.") from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/ingest")
    def ingest_dialogue_turn(run_id: str, session_id: str, payload: IngestDialogueTurnRequest) -> dict[str, Any]:
        try:
            return run_service.ingest_dialogue_turn(
                run_id,
                session_id=session_id,
                responses=[item.model_dump() for item in payload.responses],
            )
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail="Session not found.") from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    return app


app = create_app()
