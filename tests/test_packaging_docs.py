#!/usr/bin/env python3

import unittest
from pathlib import Path


class PackagingDocsTests(unittest.TestCase):
    def test_manifest_describes_prompt_first_bundle(self):
        manifest_text = Path("clawhub-zaomeng-skill/MANIFEST.md").read_text(encoding="utf-8")
        metadata_text = Path("clawhub-zaomeng-skill/.metadata.json").read_text(encoding="utf-8")
        self.assertIn("requirements.txt", manifest_text)
        self.assertIn("tools/prepare_novel_excerpt.py", manifest_text)
        self.assertIn("tools/build_prompt_payload.py", manifest_text)
        self.assertIn("tools/export_relation_graph.py", manifest_text)
        self.assertIn("人物关系图谱", manifest_text)
        self.assertNotIn("runtime/zaomeng_cli.py", manifest_text)
        self.assertNotIn("runtime/src", manifest_text)
        self.assertIn('"name": "zaomeng-skill"', metadata_text)
        self.assertIn('"version": "4.1.3"', metadata_text)

    def test_install_docs_describe_prompt_first_install(self):
        install_text = Path("clawhub-zaomeng-skill/INSTALL.md").read_text(encoding="utf-8")
        self.assertIn("宿主驱动的 skill 包", install_text)
        self.assertIn("requirements.txt", install_text)
        self.assertIn("tools/prepare_novel_excerpt.py", install_text)
        self.assertIn("tools/build_prompt_payload.py", install_text)
        self.assertIn("tools/export_relation_graph.py", install_text)
        self.assertNotIn("runtime/src", install_text)
        self.assertNotIn("--include-runtime", install_text)

    def test_readmes_describe_prompt_first_helpers(self):
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
        validation_text = Path("clawhub-zaomeng-skill/references/validation_policy.md").read_text(encoding="utf-8")

        self.assertIn("多角色蒸馏差分要求", prompt_text)
        self.assertIn("这个角色与同批其他角色最不同的地方是什么", prompt_text)
        self.assertIn("共享场景优先用于提取 `key_bonds`", prompt_text)
        self.assertIn("输出前至少做一次区分度自检", prompt_text)
        self.assertIn("rules/character_hints/<novel_id>.md", prompt_text)

        self.assertIn("统一标尺", schema_text)
        self.assertIn("world_belong", schema_text)
        self.assertIn("rule_view", schema_text)
        self.assertIn("plot_restriction", schema_text)
        self.assertIn("appearance_feature", schema_text)
        self.assertIn("habit_action", schema_text)
        self.assertIn("interest_claim", schema_text)
        self.assertIn("resource_dependence", schema_text)
        self.assertIn("trade_principle", schema_text)
        self.assertIn("carry_style", schema_text)
        self.assertIn("disguise_switch", schema_text)
        self.assertIn("ooc_redline", schema_text)
        self.assertIn("evidence_source", schema_text)
        self.assertIn("contradiction_note", schema_text)
        self.assertIn("timeline_stage", schema_text)
        self.assertIn("relation_change", schema_text)
        self.assertIn("hidden_attitude", schema_text)
        self.assertIn("原作优先原则", schema_text)
        self.assertIn("公平蒸馏规则", schema_text)

        self.assertIn("原作优先", validation_text)
        self.assertIn("差分校验", validation_text)
        self.assertIn("evidence_source", validation_text)
        self.assertIn("interest_claim", validation_text)


if __name__ == "__main__":
    unittest.main()
