# 声明式插件模板

这是 KMP 宿主可直接加载的外置插件示例。它不包含 `main.py`，而是通过 `execution.mode = "declarative"` 声明一个聊天动作：

- `operation: suggest` 表示调用宿主的“生成建议”能力；
- `direction` 是给模型的提示词模板；
- 模板支持 `{{seed_text}}`、`{{direction}}` 和 `{{config.<key>}}`。

当前声明式运行时支持 `chatActions` 与 `temporaryNpcGenerators`。旧版 `entry=main.py` 插件仍只能保存，不会执行任意代码。
