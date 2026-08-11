# 快捷接话示例插件

这是旧 Python/Web 插件 API v1 的最小模板。修改 `plugin.json` 中的反向域名 ID、名称和版本，再实现 `main.py` 中的动作即可。

> 当前 KMP 应用可以检查和保存第三方插件包，但不包含 Python 执行环境，无法执行此模板的 `main.py`。KMP 内置插件应通过 `kmp/plugins-api` 和 `kmp/builtin-plugins` 实现。

在仓库根目录打包：

```powershell
python scripts/package_plugin.py examples/plugin-template
```

生成的 `dist/com.example.quick-reply-0.1.0.zip` 可供旧 Python/Web 宿主使用。KMP Android 端仅能检查和保存该包，不能执行其中的 Python 动作。
