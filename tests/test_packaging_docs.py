#!/usr/bin/env python3

import unittest
from pathlib import Path


class PackagingDocsTests(unittest.TestCase):
    def test_manifest_describes_prompt_first_bundle(self):
        manifest_text = Path("clawhub-zaomeng-skill/MANIFEST.md").read_text(encoding="utf-8")
        self.assertIn("requirements.txt", manifest_text)
        self.assertIn("tools/prepare_novel_excerpt.py", manifest_text)
        self.assertIn("tools/build_prompt_payload.py", manifest_text)
        self.assertIn("tools/export_relation_graph.py", manifest_text)
        self.assertIn("人物关系图谱", manifest_text)
        self.assertNotIn("runtime/zaomeng_cli.py", manifest_text)
        self.assertNotIn("runtime/src", manifest_text)

    def test_install_docs_describe_prompt_first_install_and_optional_runtime(self):
        install_text = Path("clawhub-zaomeng-skill/INSTALL.md").read_text(encoding="utf-8")
        self.assertIn("宿主驱动的 skill 包", install_text)
        self.assertIn("requirements.txt", install_text)
        self.assertIn("tools/prepare_novel_excerpt.py", install_text)
        self.assertIn("tools/build_prompt_payload.py", install_text)
        self.assertIn("tools/export_relation_graph.py", install_text)
        self.assertNotIn("runtime/src", install_text)
        self.assertNotIn("--include-runtime", install_text)

    def test_readmes_describe_prompt_first_helpers_and_cli_separation(self):
        root_readme = Path("README.md").read_text(encoding="utf-8")
        root_readme_en = Path("README.en.md").read_text(encoding="utf-8")
        skill_readme = Path("clawhub-zaomeng-skill/README.md").read_text(encoding="utf-8")
        skill_readme_en = Path("clawhub-zaomeng-skill/README_EN.md").read_text(encoding="utf-8")

        self.assertIn("tools/prepare_novel_excerpt.py", skill_readme)
        self.assertIn("tools/build_prompt_payload.py", skill_readme)
        self.assertIn("tools/export_relation_graph.py", skill_readme)
        self.assertIn("requirements.txt", skill_readme)
        self.assertIn("tools/prepare_novel_excerpt.py", skill_readme_en)
        self.assertIn("tools/build_prompt_payload.py", skill_readme_en)
        self.assertIn("tools/export_relation_graph.py", skill_readme_en)
        self.assertIn("requirements.txt", skill_readme_en)
        self.assertNotIn("runtime/zaomeng_cli.py", skill_readme)
        self.assertNotIn("runtime/zaomeng_cli.py", skill_readme_en)

    def test_skill_docs_prioritize_host_llm_without_env_preflight(self):
        clawhub_skill = Path("clawhub-zaomeng-skill/SKILL.md").read_text(encoding="utf-8")
        skill_readme = Path("clawhub-zaomeng-skill/README.md").read_text(encoding="utf-8")
        skill_readme_en = Path("clawhub-zaomeng-skill/README_EN.md").read_text(encoding="utf-8")

        self.assertIn("宿主负责实际生成", clawhub_skill)
        self.assertIn("tools/prepare_novel_excerpt.py", clawhub_skill)
        self.assertIn("tools/build_prompt_payload.py", clawhub_skill)
        self.assertIn("tools/export_relation_graph.py", clawhub_skill)
        self.assertIn("宿主负责实际调用模型", skill_readme)
        self.assertIn("人物关系图谱", clawhub_skill)
        self.assertIn("人物关系图谱", skill_readme)
        self.assertIn("relationship graphs", skill_readme_en)
        self.assertIn("进度播报", clawhub_skill)
        self.assertIn("当前正在蒸馏哪个角色", clawhub_skill)
        self.assertIn("正在生成人物关系图谱", clawhub_skill)
        self.assertIn("act 模式 / observe 模式继续", clawhub_skill)
        self.assertNotIn("runtime/config.yaml", clawhub_skill)
        self.assertNotIn("OPENAI_API_KEY", clawhub_skill)
        self.assertNotIn("runtime/zaomeng_cli.py", clawhub_skill)

    def test_distillation_docs_require_multi_character_differentiation(self):
        prompt_text = Path("clawhub-zaomeng-skill/prompts/distill_prompt.md").read_text(encoding="utf-8")
        schema_text = Path("clawhub-zaomeng-skill/references/output_schema.md").read_text(encoding="utf-8")

        self.assertIn("多角色蒸馏差分要求", prompt_text)
        self.assertIn("这个角色与同批其他角色最不同的地方是什么", prompt_text)
        self.assertIn("共享场景优先用于提取 `key_bonds`", prompt_text)
        self.assertIn("输出前至少做一次区分度自检", prompt_text)

        self.assertIn("易混字段收紧定义", schema_text)
        self.assertIn("identity_anchor", schema_text)
        self.assertIn("background_imprint", schema_text)
        self.assertIn("soul_goal", schema_text)
        self.assertIn("temperament_type", schema_text)
        self.assertIn("stress_response", schema_text)
        self.assertIn("restraint_threshold", schema_text)
        self.assertIn("temperament_type", prompt_text)
        self.assertIn("moral_bottom_line", prompt_text)
        self.assertIn("self_cognition", prompt_text)
        self.assertIn("rules/character_hints/<novel_id>.md", prompt_text)


if __name__ == "__main__":
    unittest.main()
