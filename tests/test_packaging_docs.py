#!/usr/bin/env python3

import unittest
from pathlib import Path

from scripts.check_runtime_mirror import (
    load_documented_runtime_wrapper_paths,
    load_documented_shared_runtime_core_paths,
    load_managed_runtime_paths,
)


class PackagingDocsTests(unittest.TestCase):
    def test_manifest_mentions_shared_runtime_core_modules(self):
        manifest_text = Path("clawhub-zaomeng-skill/MANIFEST.md").read_text(encoding="utf-8")
        for entry in load_documented_shared_runtime_core_paths():
            self.assertIn(entry, manifest_text)

    def test_install_and_skill_docs_describe_wrapper_split(self):
        install_text = Path("clawhub-zaomeng-skill/INSTALL.md").read_text(encoding="utf-8")
        skill_text = Path("clawhub-zaomeng-skill/SKILL.md").read_text(encoding="utf-8")
        for entry in load_documented_runtime_wrapper_paths():
            self.assertIn(entry, install_text)
            self.assertIn(entry, skill_text)
        for entry in load_documented_shared_runtime_core_paths():
            self.assertIn(entry, install_text)
            self.assertIn(entry, skill_text)

    def test_readmes_describe_shared_and_wrapper_runtime_layers(self):
        root_readme_en = Path("README.en.md").read_text(encoding="utf-8")
        skill_readme = Path("clawhub-zaomeng-skill/README.md").read_text(encoding="utf-8")
        skill_readme_en = Path("clawhub-zaomeng-skill/README_EN.md").read_text(encoding="utf-8")

        self.assertIn("src/core/runtime_parts.py", root_readme_en)
        self.assertIn("src/core/logging_utils.py", root_readme_en)
        for entry in load_documented_runtime_wrapper_paths():
            self.assertIn(entry, skill_readme)
            self.assertIn(entry, skill_readme_en)
        for entry in load_documented_shared_runtime_core_paths():
            self.assertIn(entry, skill_readme)
            self.assertIn(entry, skill_readme_en)

    def test_skill_docs_do_not_treat_runtime_llm_config_as_openclaw_chat_prerequisite(self):
        clawhub_skill = Path("clawhub-zaomeng-skill/SKILL.md").read_text(encoding="utf-8")
        openclaw_skill = Path("openclaw-skill/SKILL.md").read_text(encoding="utf-8")

        self.assertIn("不要因为运行时没单独填写 `runtime/config.yaml` 就停下来让用户二选一", clawhub_skill)
        self.assertIn("不要插入“配置 LLM（推荐）/继续规则模式”这类额外选择题", clawhub_skill)
        self.assertIn("不要再把“是否单独配置 zaomeng 运行时 LLM”当成继续群聊的前置条件", openclaw_skill)
        self.assertIn("不要向用户抛出“去填 runtime/config.yaml”或“配置 LLM / 继续规则模式”的二选一提示", openclaw_skill)

    def test_distillation_docs_require_multi_character_differentiation(self):
        prompt_text = Path("clawhub-zaomeng-skill/prompts/distill_prompt.md").read_text(encoding="utf-8")
        schema_text = Path("clawhub-zaomeng-skill/references/output_schema.md").read_text(encoding="utf-8")

        self.assertIn("多角色蒸馏差分要求", prompt_text)
        self.assertIn("这个角色与同批其他角色最不同的地方是什么", prompt_text)
        self.assertIn("共享场景优先用于提取 `key_bonds`、关系变化、冲突点、互动节奏", prompt_text)
        self.assertIn("输出前至少做一次区分度自检", prompt_text)

        self.assertIn("易混字段收紧定义", schema_text)
        self.assertIn("禁止只写“活下去”“赢下来”“保护同伴”这类任何人都适用的空泛目标", schema_text)
        self.assertIn("关系字段可共用事件来源，但 `background_imprint`、`identity_anchor`、`soul_goal` 等字段必须只保留该角色独有的视角与后果", schema_text)
        self.assertIn("temperament_type", schema_text)
        self.assertIn("stress_response", schema_text)
        self.assertIn("restraint_threshold", schema_text)
        self.assertIn("temperament_type", prompt_text)
        self.assertIn("moral_bottom_line", prompt_text)
        self.assertIn("self_cognition", prompt_text)
        self.assertIn("rules/character_hints/<novel_id>.md", prompt_text)

    def test_manifest_lists_all_managed_runtime_python_files(self):
        manifest_text = Path("clawhub-zaomeng-skill/MANIFEST.md").read_text(encoding="utf-8")
        for entry in load_managed_runtime_paths():
            self.assertIn(entry, manifest_text)


if __name__ == "__main__":
    unittest.main()
