from .dialogue import router as dialogue_router
from .diagnostics import router as diagnostics_router
from .chapters import router as chapters_router
from .opening_presets import router as opening_presets_router
from .original_knowledge import router as original_knowledge_router
from .plugins import router as plugins_router
from .runs import router as runs_router
from .scene_cards import router as scene_cards_router
from .self_cards import router as self_cards_router
from .settings import router as settings_router
from .world_memory import router as world_memory_router

ROUTERS = (
    settings_router,
    plugins_router,
    diagnostics_router,
    opening_presets_router,
    scene_cards_router,
    self_cards_router,
    runs_router,
    dialogue_router,
    chapters_router,
    world_memory_router,
    original_knowledge_router,
)

__all__ = [
    "ROUTERS",
    "dialogue_router",
    "diagnostics_router",
    "chapters_router",
    "opening_presets_router",
    "original_knowledge_router",
    "plugins_router",
    "runs_router",
    "scene_cards_router",
    "self_cards_router",
    "settings_router",
]
