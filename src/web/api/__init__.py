from __future__ import annotations

from typing import Any

_DEPS_EXPORTS = {"get_run_service"}
_ROUTE_EXPORTS = {
    "ROUTERS",
    "dialogue_router",
    "chapters_router",
    "opening_presets_router",
    "plugins_router",
    "runs_router",
    "scene_cards_router",
    "self_cards_router",
    "settings_router",
}
_SCHEMA_EXPORTS = {
    "BranchDialogueSessionRequest",
    "CreateDialogueSessionRequest",
    "CreateRunRequest",
    "DialogueResponseItem",
    "SaveChapterRequest",
    "ArchiveDialogueChapterRequest",
    "IngestCharacterRequest",
    "IngestDialogueTurnRequest",
    "IngestRelationRequest",
    "PrepareDialogueTurnRequest",
    "RecommendSceneCardRequest",
    "RestartRunRequest",
    "SaveModelSettingsRequest",
    "SaveOpeningPresetRequest",
    "SavePersonaReviewRequest",
    "SaveSceneCardRequest",
    "SaveSelfCardRequest",
    "SwitchDialogueSceneCardRequest",
}

__all__ = [
    "ROUTERS",
    "dialogue_router",
    "chapters_router",
    "BranchDialogueSessionRequest",
    "opening_presets_router",
    "plugins_router",
    "CreateDialogueSessionRequest",
    "CreateRunRequest",
    "DialogueResponseItem",
    "SaveChapterRequest",
    "ArchiveDialogueChapterRequest",
    "get_run_service",
    "IngestCharacterRequest",
    "IngestDialogueTurnRequest",
    "IngestRelationRequest",
    "PrepareDialogueTurnRequest",
    "RecommendSceneCardRequest",
    "RestartRunRequest",
    "runs_router",
    "scene_cards_router",
    "self_cards_router",
    "SaveModelSettingsRequest",
    "SaveOpeningPresetRequest",
    "SaveSceneCardRequest",
    "SavePersonaReviewRequest",
    "SaveSelfCardRequest",
    "settings_router",
    "SwitchDialogueSceneCardRequest",
]


def __getattr__(name: str) -> Any:
    if name in _DEPS_EXPORTS:
        from . import deps

        return getattr(deps, name)
    if name in _ROUTE_EXPORTS:
        from . import routes

        return getattr(routes, name)
    if name in _SCHEMA_EXPORTS:
        from . import schemas

        return getattr(schemas, name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
