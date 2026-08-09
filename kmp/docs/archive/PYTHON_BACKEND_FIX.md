# Python 后端启动错误修复

## 日期
2026-08-07

## 问题描述

Android 应用启动时，Python 后端报错：
```
No module named 'prompts'
```

## 根本原因

在 Phase 0 准备阶段，我们创建了 `C:/work2/zaomeng/prompts/` 目录并提取了提示词到该目录，但是**忘记创建 `__init__.py` 文件**。

Python 需要 `__init__.py` 文件来将目录识别为包（package）。没有这个文件，Python 无法导入该模块。

## 影响的代码

多个 Python 文件尝试导入 prompts 模块：

```python
# src/web/chat/helpers.py
from prompts.loader import (
    get_dialogue_suggestion_prompt,
    get_branch_edit_prompt,
    get_consistency_audit_prompt,
)

# src/web/service_facades/chapters.py
from prompts.loader import get_novel_rewrite_prompt

# src/web/review/self_cards.py
from prompts.loader import get_self_card_generation_prompt

# src/web/review/scene_cards.py
from prompts.loader import get_scene_card_generation_prompt

# src/web/review/persona_completion.py
from prompts.loader import get_persona_completion_prompt
```

## 解决方案

创建 `C:/work2/zaomeng/prompts/__init__.py` 文件：

```python
"""
Prompts package for Zaomeng.

This package contains all prompt templates extracted from the codebase,
organized by functionality.
"""

from .loader import (
    get_dialogue_suggestion_prompt,
    get_branch_edit_prompt,
    get_consistency_audit_prompt,
    get_persona_completion_prompt,
    get_scene_card_generation_prompt,
    get_self_card_generation_prompt,
    get_novel_rewrite_prompt,
    get_distillation_system_prompt,
)

__all__ = [
    "get_dialogue_suggestion_prompt",
    "get_branch_edit_prompt",
    "get_consistency_audit_prompt",
    "get_persona_completion_prompt",
    "get_scene_card_generation_prompt",
    "get_self_card_generation_prompt",
    "get_novel_rewrite_prompt",
    "get_distillation_system_prompt",
]
```

## 提交信息

- **Commit**: `c9724a4`
- **Repository**: `C:/work2/zaomeng/` (主仓库，不在 Android 迁移分支)
- **Message**: "fix: add missing __init__.py for prompts module"

## 验证步骤

1. 启动 Android 应用
2. 观察 Python 后端启动日志
3. 确认没有 "No module named 'prompts'" 错误
4. 测试任意需要提示词的功能（对话建议、章节生成等）

## 经验教训

### 教训
在创建 Python 包时，**必须同时创建 `__init__.py` 文件**，即使是空文件也可以。这是 Python 包系统的基本要求。

### 最佳实践
创建新的 Python 目录时的检查清单：
- [ ] 创建目录结构
- [ ] 移动/创建 `.py` 文件
- [ ] **创建 `__init__.py` 文件** ⭐
- [ ] 在 `__init__.py` 中导出公共 API
- [ ] 添加模块级文档字符串
- [ ] 测试导入是否正常

### 未来预防措施
在迁移计划的 Phase 0 中添加明确步骤：
```markdown
- [ ] 0.1.3 创建 prompts/ 包结构
  - 创建目录
  - 创建 __init__.py ⭐ 关键
  - 添加 loader.py
  - 测试导入
```

## 相关文档

- Phase 0 准备阶段：`PHASE_0_SUMMARY.md`
- 迁移计划：`MIGRATION_PLAN.md`
- Prompts 包文档：`C:/work2/zaomeng/prompts/README.md`

## 状态

✅ **已修复并验证**

Python 后端现在可以正常启动，与 Ktor 后端并存运行。
