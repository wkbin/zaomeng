# Prompts 提示词配置

本目录存放所有 LLM 提示词配置，采用 YAML 格式，方便 Python 和 Kotlin 代码共享。

## 目录结构

```
prompts/
├── dialogue/              # 对话相关提示词
│   ├── director.yaml      # 对话导演模式
│   ├── suggestions.yaml   # 对话建议生成
│   ├── consistency_review.yaml  # 一致性审校
│   └── inner_thought_rule.yaml  # 读心功能规则
├── chapters/              # 章节相关
│   └── novel_rewrite.yaml # 小说改写
├── review/                # 审校和生成
│   ├── persona_completion.yaml  # 人物资料补全
│   ├── scene_card_generation.yaml  # 场景卡生成
│   └── self_card_generation.yaml   # 角色卡生成
├── distillation/          # 蒸馏相关（待添加）
├── loader.py              # Python 加载器
└── README.md              # 本文件
```

## 使用方式

### Python

```python
from prompts.loader import (
    get_dialogue_director_prompt,
    get_dialogue_suggestions_prompt,
    get_consistency_review_prompt,
    get_novel_rewrite_prompt,
    # ...
)

# 获取对话导演提示词
prompt = get_dialogue_director_prompt(option_count=3, retry=False)

# 获取章节改写提示词
rewrite_prompt = get_novel_rewrite_prompt()
```

### Kotlin（未来实现）

```kotlin
// 使用 kotlinx.serialization 和 kaml 解析 YAML
val config = PromptLoader.load("dialogue", "director")
val systemPrompt = config["system_prompt"] as String
```

## 迁移进度

| 文件 | 原始位置 | 状态 |
|------|---------|------|
| director.yaml | src/web/chat/helpers.py:1187 | ✅ 已提取 |
| suggestions.yaml | src/web/chat/helpers.py:1106 | ✅ 已提取 |
| consistency_review.yaml | src/web/chat/helpers.py:1649 | ✅ 已提取 |
| novel_rewrite.yaml | src/web/service_facades/chapters.py:19 | ✅ 已提取 |
| scene_card_generation.yaml | src/web/review/scene_cards.py | ✅ 已提取 |
| self_card_generation.yaml | src/web/review/self_cards.py | ✅ 已提取 |
| persona_completion.yaml | src/web/review/persona_completion.py | ✅ 已提取 |
| inner_thought_rule.yaml | src/web/chat/helpers.py:21 | ✅ 已提取 |

## 设计原则

1. **单一职责**：每个 YAML 文件对应一个具体的提示词场景
2. **结构化**：使用 YAML 的层级结构组织复杂提示词
3. **可复用**：提取公共部分（如 retry_instruction）
4. **可扩展**：支持参数化（如 option_count）
5. **跨语言**：YAML 格式便于 Python 和 Kotlin 共享
6. **版本控制**：提示词变更可追溯

## 待迁移

- 蒸馏相关提示词（src/web/prompts/builders.py）
- 其他散落在代码中的硬编码提示词
