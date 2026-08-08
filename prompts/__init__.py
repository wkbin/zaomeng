"""
Prompts package for Zaomeng.

This package contains all prompt templates extracted from the codebase,
organized by functionality.
"""

from .loader import (
    get_dialogue_director_prompt,
    get_dialogue_suggestions_prompt,
    get_consistency_review_prompt,
    get_inner_thought_rule,
    get_persona_completion_prompt,
    get_scene_card_generation_prompt,
    get_self_card_generation_prompt,
    get_novel_rewrite_prompt,
    load_prompt_config,
)

__all__ = [
    "get_dialogue_director_prompt",
    "get_dialogue_suggestions_prompt",
    "get_consistency_review_prompt",
    "get_inner_thought_rule",
    "get_persona_completion_prompt",
    "get_scene_card_generation_prompt",
    "get_self_card_generation_prompt",
    "get_novel_rewrite_prompt",
    "load_prompt_config",
]
