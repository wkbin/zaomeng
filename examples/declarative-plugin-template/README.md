# 声明式插件模板

这是 KMP 宿主可直接加载的外置插件示例。它不包含 `main.py`，而是通过 `execution.mode = "declarative"` 声明一个聊天动作：

- `operation: suggest` 表示调用宿主的“生成建议”能力；
- `direction` 是给模型的提示词模板；
- 模板支持 `{{seed_text}}`、`{{direction}}` 和 `{{config.<key>}}`。

当前声明式运行时支持 `chatActions`、`generationEnhancers` 与 `temporaryNpcGenerators`。设置项支持 `boolean`、`integer` 和 `enum`。旧版 `entry=main.py` 插件仍只能保存，不会执行任意代码。

完整字段、权限、打包与调试说明见[官网插件开发教程](https://wkbin.github.io/zaomeng/plugin-development.html)。
