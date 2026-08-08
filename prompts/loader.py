"""提示词配置加载器

从 YAML 文件加载提示词配置，供 Python 和未来的 Kotlin 代码使用。
"""
from __future__ import annotations

import yaml
from pathlib import Path
from typing import Any


_PROMPTS_ROOT = Path(__file__).resolve().parent


def load_prompt_config(category: str, name: str) -> dict[str, Any]:
    """加载提示词配置

    Args:
        category: 类别（dialogue, review, chapters, distillation）
        name: 配置文件名（不含 .yaml 后缀）

    Returns:
        配置字典

    Raises:
        FileNotFoundError: 配置文件不存在
    """
    config_path = _PROMPTS_ROOT / category / f"{name}.yaml"
    if not config_path.exists():
        raise FileNotFoundError(f"Prompt config not found: {config_path}")

    with open(config_path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def get_dialogue_director_prompt(option_count: int = 3, retry: bool = False) -> str:
    """获取对话导演提示词"""
    config = load_prompt_config("dialogue", "director")
    parts = [
        config["system_prompt"],
        config["option_count_instruction"].format(option_count=option_count),
        config["output_format"],
    ]
    if retry:
        parts.append(config["retry_instruction"])
    return "\n".join(parts)


def get_dialogue_suggestions_prompt(option_count: int = 3, retry: bool = False,
                                     generation_goal: str = "", output_rule: str = "") -> str:
    """获取对话建议提示词"""
    config = load_prompt_config("dialogue", "suggestions")
    parts = [
        config["system_prompt"],
        config["option_count_instruction"].format(option_count=option_count),
        config["additional_rules"],
        config["output_format"],
        generation_goal,
        output_rule,
    ]
    if retry:
        parts.append(config["retry_instruction"])
    return "\n".join(part for part in parts if part)


def get_consistency_review_prompt() -> str:
    """获取一致性审校提示词"""
    config = load_prompt_config("dialogue", "consistency_review")
    return "\n".join([
        config["system_prompt"],
        config["output_format"],
    ])


def get_inner_thought_rule() -> str:
    """获取读心功能规则"""
    config = load_prompt_config("dialogue", "inner_thought_rule")
    return config["rule"]


def get_novel_rewrite_prompt() -> str:
    """获取章节改写提示词"""
    config = load_prompt_config("chapters", "novel_rewrite")
    principles = config["core_principles"]
    principles_text = "\n".join([
        f"{i+1}. {p['name']}。{p['rule']}"
        for i, p in enumerate(principles)
    ])
    return "\n".join([
        config["system_prompt"],
        "",
        "核心原则：",
        principles_text,
        "",
        config["output_rules"],
    ])


def get_scene_card_generation_prompt() -> str:
    """获取场景卡生成提示词"""
    config = load_prompt_config("review", "scene_card_generation")
    return config["system_prompt"]


def get_self_card_generation_prompt() -> str:
    """获取角色卡生成提示词"""
    config = load_prompt_config("review", "self_card_generation")
    return config["system_prompt"]


def get_persona_completion_prompt(mode: str = "knowledge_based") -> str:
    """获取人物资料补全提示词

    Args:
        mode: knowledge_based（基于模型知识）/ web_based（基于网页）/ simple（简化模式）
    """
    config = load_prompt_config("review", "persona_completion")
    return config[mode]["system_prompt"]
