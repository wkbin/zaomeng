from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import PlainTextResponse

from src.web.api.compat import model_to_dict
from src.web.api.deps import get_run_service
from src.web.api.schemas import ArchiveDialogueChapterRequest, AskBookQuestionRequest, ReorderChapterRequest, SaveChapterRequest
from src.web.workflow import WebRunService

router = APIRouter()


@router.get("/api/web/runs/{run_id}/chapters")
def list_chapters(run_id: str, run_service: WebRunService = Depends(get_run_service)) -> dict[str, Any]:
    try:
        return {"items": run_service.list_chapters(run_id)}
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run not found.") from exc


@router.get("/api/web/runs/{run_id}/search")
def search_run_content(
    run_id: str,
    query: str = Query(..., min_length=1, max_length=100),
    limit: int = Query(default=30, ge=1, le=100),
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return {"items": run_service.search_run_content(run_id, query=query, limit=limit)}
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run not found.") from exc


@router.post("/api/web/runs/{run_id}/ask")
def ask_book_question(run_id: str, payload: AskBookQuestionRequest, run_service: WebRunService = Depends(get_run_service)) -> dict[str, Any]:
    try:
        return run_service.answer_book_question(run_id, question=payload.question)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/runs/{run_id}/chapters")
def create_chapter(run_id: str, payload: SaveChapterRequest, run_service: WebRunService = Depends(get_run_service)) -> dict[str, Any]:
    try:
        return run_service.save_chapter(run_id, **model_to_dict(payload))
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.put("/api/web/runs/{run_id}/chapters/{chapter_id}")
def update_chapter(run_id: str, chapter_id: str, payload: SaveChapterRequest, run_service: WebRunService = Depends(get_run_service)) -> dict[str, Any]:
    try:
        return run_service.save_chapter(run_id, chapter_id=chapter_id, **model_to_dict(payload))
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Chapter not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.patch("/api/web/runs/{run_id}/chapters/{chapter_id}/order")
def reorder_chapter(run_id: str, chapter_id: str, payload: ReorderChapterRequest, run_service: WebRunService = Depends(get_run_service)) -> dict[str, Any]:
    try:
        return {"items": run_service.reorder_chapter(run_id, chapter_id, target_order=payload.target_order)}
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Chapter not found.") from exc


@router.post("/api/web/runs/{run_id}/chapters/archive-session")
def archive_session(run_id: str, payload: ArchiveDialogueChapterRequest, run_service: WebRunService = Depends(get_run_service)) -> dict[str, Any]:
    try:
        return run_service.archive_dialogue_session_as_chapter(run_id, **model_to_dict(payload))
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run or session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/runs/{run_id}/chapters/convert-session")
def convert_session(
    run_id: str,
    payload: ArchiveDialogueChapterRequest,
    run_service: WebRunService = Depends(get_run_service),
) -> dict[str, Any]:
    try:
        return run_service.convert_dialogue_session_to_novel(
            run_id, session_id=payload.session_id, title=payload.title
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run or session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.delete("/api/web/runs/{run_id}/chapters/{chapter_id}")
def delete_chapter(run_id: str, chapter_id: str, run_service: WebRunService = Depends(get_run_service)) -> dict[str, str]:
    try:
        return run_service.delete_chapter(run_id, chapter_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Chapter not found.") from exc


@router.post("/api/web/runs/{run_id}/chapters/{chapter_id}/continue")
def continue_chapter(run_id: str, chapter_id: str, run_service: WebRunService = Depends(get_run_service)) -> dict[str, Any]:
    try:
        return run_service.continue_chapter_writing(run_id, chapter_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Chapter not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/api/web/runs/{run_id}/chapters/{chapter_id}/sync-session")
def sync_chapter_session(run_id: str, chapter_id: str, run_service: WebRunService = Depends(get_run_service)) -> dict[str, Any]:
    try:
        return run_service.sync_latest_session_to_chapter(run_id, chapter_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Chapter or session not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.get("/api/web/runs/{run_id}/chapters/export")
def export_chapters(run_id: str, format_name: str = Query(default="markdown", alias="format"), run_service: WebRunService = Depends(get_run_service)) -> PlainTextResponse:
    try:
        text = run_service.render_chapter_manuscript(run_id, format_name=format_name)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Run not found.") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    media_type = "text/markdown; charset=utf-8" if format_name == "markdown" else "text/plain; charset=utf-8"
    return PlainTextResponse(text, media_type=media_type)
