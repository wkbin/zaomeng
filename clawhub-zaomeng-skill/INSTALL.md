# 安装说明

这是一份面向打包校验和环境确认的安装说明。

它不是主要的用户使用指南。  
用户使用方式优先看 `README.md`；宿主和 agent 的执行规则优先看 `SKILL.md`。

## 当前打包形态

这个 bundle 是一个宿主驱动的 skill 包，安装后直接提供 prompt、references、examples 和 helper scripts。

- 轻量 helper 脚本入口：`tools/prepare_novel_excerpt.py`
- prompt payload 组装脚本：`tools/build_prompt_payload.py`

## 本目录应包含的关键文件

- `README.md`
- `README_EN.md`
- `.metadata.json`
- `SKILL.md`
- `MANIFEST.md`
- `PUBLISH.md`
- `requirements.txt`
- `tools/prepare_novel_excerpt.py`
- `tools/build_prompt_payload.py`
- `tools/export_relation_graph.py`
- `references/output_schema.md`
- `references/style_differ.md`
- `references/logic_constraint.md`
- `references/safety_policy.md`
- `references/validation_policy.md`
- `examples/sample_input_excerpt.txt`
- `examples/sample_character_profile.md`
- `examples/sample_relations.md`
- `examples/test-prompts.json`

## Python 依赖

- skill 包自带 `requirements.txt`
- 当前版本边界写在 `requirements.txt` 中
- 其中 `ebooklib` 用于读取 `.epub` 小说，`tiktoken` 用于更准确的 token 估算

## 安装建议

- `python scripts/install_skill.py --skills-dir <your-skills-root>` 安装 skill 包
- 包内 prompt 与 references 主要用于约束与说明，不应用来替代引擎入口
- 安装完成后，宿主可直接调用 helper scripts 组织蒸馏、关系抽取和对话生成

## 快速校验清单

1. `SKILL.md` frontmatter 合法。
2. `tools/prepare_novel_excerpt.py` 可以执行。
3. `tools/export_relation_graph.py` 可以生成 `.html` 与 `.mermaid.md` 图谱。
4. 输出字段符合 `references/output_schema.md`。
5. 安全与校验相关规则文件齐全。
