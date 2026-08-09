import base64
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch

from src.plugin_system import (
    PluginError,
    PluginManifest,
    PluginPermissionError,
    PluginRegistry,
)
from src.web.plugin_host import ZaomengPluginHost
from src.web.service_facades.plugins import PluginServiceMixin


class _Host:
    def __init__(self) -> None:
        self.last_capability = ""
        self.last_payload = {}
        self.last_context_request = {}

    def read_dialogue_context(self, **kwargs):
        self.last_context_request = dict(kwargs)
        return {"run_id": kwargs["run_id"], "mode": "act"}

    def invoke_model(self, capability, payload):
        self.last_capability = capability
        self.last_payload = dict(payload)
        if capability == "dialogue_reply_variants":
            return {
                "options": [
                    {"label": "克制回应", "suggestion": "我明白了。"},
                    {"label": "继续追问", "suggestion": "然后呢？"},
                ]
            }
        if capability == "temporary_npc":
            return {
                "name": "巡夜人",
                "role": "巡夜人",
                "appearance": "提着一盏湿灯笼",
                "personality": "多疑",
                "speech_style": "短句盘问",
                "motive": "寻找逃走的人",
                "entrance": "门被推开，雨水顺着灯笼滴下。",
                "opening_line": "方才是谁从后门出去的？",
            }
        return f"{capability}:{payload['run_id']}"


class _PluginService(PluginServiceMixin):
    def __init__(self, plugins: PluginRegistry, dialogue=None) -> None:
        self.plugins = plugins
        self.dialogue = dialogue

    def _require_manifest(self, run_id: str):
        if not run_id:
            raise FileNotFoundError(run_id)
        return object()


class _EnhancerDialogue:
    def __init__(self) -> None:
        self.session = {"plugin_enhancer_states": {}}

    def get_session(self, run_id: str, session_id: str):
        return dict(self.session)

    def set_plugin_enhancer_state(
        self, run_id: str, session_id: str, enhancer_key: str, enabled: bool
    ):
        states = dict(self.session.get("plugin_enhancer_states", {}))
        states[enhancer_key] = enabled
        self.session = {"plugin_enhancer_states": states}
        return dict(self.session)


class _NpcDialogue:
    def __init__(self) -> None:
        self.last_npc = {}

    def add_temporary_npc(self, run_id, session_id, npc):
        self.last_npc = dict(npc)
        return {
            "run_id": run_id,
            "session_id": session_id,
            "participants": [npc["name"]],
        }

def _write_plugin(root: Path, *, result: str = "v1", permissions=None) -> Path:
    plugin_root = root / "demo"
    plugin_root.mkdir(parents=True, exist_ok=True)
    manifest = {
        "id": "com.example.demo",
        "name": "Demo",
        "version": "1.0.0",
        "apiVersion": "1",
        "entry": "main.py",
        "defaultEnabled": True,
        "permissions": permissions
        or ["chat.context.read", "chat.draft.write"],
        "contributes": {
            "chatActions": [
                {"id": "run", "title": "Run", "placement": "composer"}
            ]
        },
    }
    (plugin_root / "plugin.json").write_text(
        json.dumps(manifest), encoding="utf-8"
    )
    (plugin_root / "main.py").write_text(
        f'''class DemoPlugin:
    def activate(self, host):
        self.host = host

    def deactivate(self):
        self.host = None

    def execute_chat_action(self, action_id, request):
        context = self.host.read_dialogue_context(run_id=request["run_id"], session_id="s")
        return {{"suggestion": "{result}:" + context["run_id"]}}

def create_plugin():
    return DemoPlugin()
''',
        encoding="utf-8",
    )
    return plugin_root


def _plugin_package_bytes(
    *,
    plugin_id: str = "com.example.installable",
    version: str = "1.0.0",
    api_version: str = "1",
    unsafe_path: str = "",
) -> bytes:
    manifest = {
        "id": plugin_id,
        "name": "Installable Demo",
        "version": version,
        "apiVersion": api_version,
        "entry": "main.py",
        "defaultEnabled": False,
        "permissions": ["chat.context.read", "chat.draft.write"],
        "contributes": {
            "chatActions": [
                {"id": "run", "title": "Run", "placement": "composer"}
            ]
        },
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(
            "installable/plugin.json",
            json.dumps(manifest, ensure_ascii=False),
        )
        archive.writestr(
            "installable/main.py",
            "class Demo:\n"
            "    def activate(self, host): self.host = host\n"
            "    def deactivate(self): pass\n"
            "    def execute_chat_action(self, action_id, request):\n"
            "        return {'suggestion': 'installed'}\n\n"
            "def create_plugin(): return Demo()\n",
        )
        if unsafe_path:
            archive.writestr(unsafe_path, "escape")
    return output.getvalue()


class PluginSystemTests(unittest.TestCase):
    def test_android_bundle_extracts_builtin_plugins_with_src_package(self):
        settings_script = Path("android/settings.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('include(":builtin-plugins")', settings_script)
        builtin_registry = Path(
            "android/builtin-plugins/src/commonMain/kotlin/"
            "top/wkbin/zaomeng/plugins/builtin/BuiltinPlugins.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("AiAssociationPlugin()", builtin_registry)
        self.assertTrue(
            Path("src/builtin_plugins/ai_association/plugin.json").is_file()
        )

    def test_web_service_discovers_packaged_builtin_plugin(self):
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            plugins = WebRunService(tmp).plugins.list_plugins()

        builtin = next(
            plugin
            for plugin in plugins
            if plugin["id"] == "com.zaomeng.ai-association"
        )
        self.assertEqual(builtin["source"], "official")
        self.assertTrue(builtin["enabled"])

    def test_builtin_voice_polish_and_reply_variants_plugins(self):
        with tempfile.TemporaryDirectory() as tmp:
            host = _Host()
            registry = PluginRegistry(
                [Path("src/builtin_plugins")],
                host_factory=lambda _plugin_id, _permissions: host,
                state_path=Path(tmp) / "plugin-state.json",
                config_path=Path(tmp) / "plugin-config.json",
            )

            plugin_ids = {plugin["id"] for plugin in registry.list_plugins()}
            self.assertIn("com.zaomeng.voice-polish", plugin_ids)
            self.assertIn("com.zaomeng.reply-variants", plugin_ids)
            registry.update_config(
                "com.zaomeng.reply-variants", {"optionCount": 4}
            )
            polished = registry.invoke_chat_action(
                "com.zaomeng.voice-polish",
                "polish-draft",
                {
                    "run_id": "run-1",
                    "session_id": "session-1",
                    "seed_text": "原始草稿",
                },
            )
            variants = registry.invoke_chat_action(
                "com.zaomeng.reply-variants",
                "generate-variants",
                {"run_id": "run-1", "session_id": "session-1"},
            )
            self.assertEqual(host.last_payload["option_count"], 4)

        self.assertEqual(polished["suggestion"], "dialogue_suggestion:run-1")
        self.assertEqual(len(variants["suggestions"]), 2)
        self.assertEqual(variants["suggestions"][0]["label"], "克制回应")

    def test_builtin_plot_dice_uses_llm_with_configured_event_type(self):
        with tempfile.TemporaryDirectory() as tmp:
            host = _Host()
            registry = PluginRegistry(
                [Path("src/builtin_plugins")],
                host_factory=lambda _plugin_id, _permissions: host,
                state_path=Path(tmp) / "plugin-state.json",
                config_path=Path(tmp) / "plugin-config.json",
            )

            plugin_ids = {plugin["id"] for plugin in registry.list_plugins()}
            self.assertIn("com.zaomeng.plot-dice", plugin_ids)
            registry.update_config(
                "com.zaomeng.plot-dice", {"eventType": "time_limit"}
            )
            result = registry.invoke_chat_action(
                "com.zaomeng.plot-dice",
                "roll-plot",
                {"run_id": "run-1", "session_id": "session-1"},
            )

        self.assertEqual(result["suggestion"], "dialogue_suggestion:run-1")
        self.assertEqual(host.last_capability, "dialogue_suggestion")
        self.assertIn("时间压力", host.last_context_request["direction"])
        self.assertIn("只返回一段简短", host.last_context_request["direction"])

    def test_builtin_random_npc_uses_dedicated_contribution_and_updates_session(self):
        with tempfile.TemporaryDirectory() as tmp:
            host = _Host()
            registry = PluginRegistry(
                [Path("src/builtin_plugins")],
                host_factory=lambda _plugin_id, _permissions: host,
                state_path=Path(tmp) / "plugin-state.json",
                config_path=Path(tmp) / "plugin-config.json",
            )
            dialogue = _NpcDialogue()
            service = _PluginService(registry, dialogue)
            plugin = next(
                item
                for item in registry.list_plugins()
                if item["id"] == "com.zaomeng.random-npc"
            )

            result = service.invoke_plugin_temporary_npc_generator(
                "com.zaomeng.random-npc",
                "generate-npc",
                run_id="run-1",
                session_id="session-1",
            )

        self.assertEqual(
            plugin["contributes"]["temporaryNpcGenerators"][0]["title"],
            "随机 NPC",
        )
        self.assertEqual(host.last_capability, "temporary_npc")
        self.assertEqual(dialogue.last_npc["name"], "巡夜人")
        self.assertEqual(result["session"]["participants"], ["巡夜人"])

    def test_temporary_npc_model_capability_retries_invalid_json_once(self):
        service = _PluginService(None)
        service.runs_root = Path("runs")
        service._build_runtime_config_for_run = lambda **_kwargs: {
            "llm.max_tokens": 900
        }
        llm = Mock()
        llm.chat_completion = Mock(
            side_effect=[
                {"content": "not json"},
                {
                    "content": json.dumps(
                        {
                            "name": "巡夜人",
                            "role": "巡夜人",
                            "appearance": "湿灯笼",
                            "personality": "多疑",
                            "speech_style": "短句",
                            "motive": "找人",
                            "entrance": "门开了。",
                            "opening_line": "谁从这里出去的？",
                        },
                        ensure_ascii=False,
                    )
                },
            ]
        )
        service._build_runtime_parts = lambda _config: type(
            "Parts", (), {"llm": llm}
        )()

        npc = service._generate_plugin_temporary_npc(
            "run-1",
            {
                "input": {"participants": ["甲"]},
                "history": [{"speaker": "甲", "message": "谁在那里？"}],
            },
        )

        self.assertEqual(npc["name"], "巡夜人")
        self.assertEqual(llm.chat_completion.call_count, 2)
        self.assertEqual(llm.chat_completion.call_args.kwargs["max_tokens"], 700)

    def test_temporary_npc_contribution_requires_cast_write_permission(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest_path = root / "plugin.json"
            manifest_path.write_text(
                json.dumps(
                    {
                        "id": "com.example.npc",
                        "name": "NPC",
                        "version": "1.0.0",
                        "apiVersion": "1",
                        "entry": "main.py",
                        "permissions": ["chat.context.read", "model.invoke"],
                        "contributes": {
                            "temporaryNpcGenerators": [
                                {"id": "create", "title": "Create"}
                            ]
                        },
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(PluginError, "chat.cast.write"):
                PluginManifest.load(manifest_path)

    def test_inner_thoughts_enhancer_has_per_chat_state_and_respects_plugin_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            registry = PluginRegistry(
                [Path("src/builtin_plugins")],
                host_factory=lambda _plugin_id, _permissions: _Host(),
                state_path=Path(tmp) / "plugin-state.json",
            )
            service = _PluginService(registry, _EnhancerDialogue())

            initial = service.resolve_generation_enhancer_options("run-1", "session-1")
            session = service.set_generation_enhancer_state(
                "com.zaomeng.inner-thoughts",
                "inner-thoughts",
                run_id="run-1",
                session_id="session-1",
                enabled=True,
            )
            active = service.resolve_generation_enhancer_options("run-1", "session-1")
            registry.disable("com.zaomeng.inner-thoughts")
            unavailable = service.resolve_generation_enhancer_options(
                "run-1", "session-1"
            )

        self.assertEqual(initial, {})
        self.assertTrue(
            session["plugin_enhancer_states"]
            ["com.zaomeng.inner-thoughts/inner-thoughts"]
        )
        self.assertTrue(active["include_inner_thoughts"])
        self.assertEqual(unavailable, {})

    def test_app_surfaces_plugin_management_entries(self):
        settings_home = Path(
            "android/app/shared/src/commonMain/kotlin/"
            "top/wkbin/zaomeng/feature/settings/SettingsHomeScreen.kt"
        ).read_text(encoding="utf-8")
        web_modal = Path("src/web/static/fragments/settings-modal.html").read_text(
            encoding="utf-8"
        )
        web_bootstrap = Path("src/web/static/js/bootstrap.js").read_text(
            encoding="utf-8"
        )
        self.assertIn('title = "插件"', settings_home)
        self.assertIn('id="plugin-manager-modal"', web_modal)
        self.assertIn("/web/js/plugin-manager.js", web_bootstrap)

    def test_plugin_management_routes_are_registered(self):
        from src.web.app import create_app
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            paths = create_app(WebRunService(tmp)).openapi()["paths"]
        self.assertIn("/api/web/plugins", paths)
        self.assertIn("/api/web/plugins/refresh", paths)
        self.assertIn("/api/web/plugins/{plugin_id}/enable", paths)
        self.assertIn("/api/web/plugins/{plugin_id}/disable", paths)
        self.assertIn("/api/web/plugins/packages/inspect", paths)
        self.assertIn("/api/web/plugins/packages/{token}/install", paths)
        self.assertIn("/api/web/plugins/{plugin_id}", paths)
        self.assertIn("/api/web/plugins/{plugin_id}/logs", paths)
        self.assertIn("/api/web/plugins/{plugin_id}/config", paths)
        self.assertIn(
            "/api/web/runs/{run_id}/dialogue/sessions/{session_id}"
            "/plugins/{plugin_id}/actions/{action_id}",
            paths,
        )
        self.assertIn(
            "/api/web/runs/{run_id}/dialogue/sessions/{session_id}"
            "/plugins/{plugin_id}/enhancers/{enhancer_id}/state",
            paths,
        )
        self.assertIn(
            "/api/web/runs/{run_id}/dialogue/sessions/{session_id}"
            "/plugins/{plugin_id}/npc-generators/{generator_id}",
            paths,
        )

    def test_plugin_package_requires_permission_confirmation_and_supports_update_uninstall(self):
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            inspected = service.inspect_plugin_package(
                filename="installable.zip",
                content_base64=base64.b64encode(_plugin_package_bytes()).decode("ascii"),
            )
            self.assertEqual(inspected["operation"], "install")
            self.assertEqual(
                inspected["plugin"]["permissions"],
                ["chat.context.read", "chat.draft.write"],
            )
            with self.assertRaisesRegex(PluginError, "确认插件权限"):
                service.install_inspected_plugin_package(
                    inspected["token"],
                    confirm_permissions=False,
                    allow_update=False,
                )

            inspected = service.inspect_plugin_package(
                filename="installable.zip",
                content_base64=base64.b64encode(_plugin_package_bytes()).decode("ascii"),
            )
            installed = service.install_inspected_plugin_package(
                inspected["token"],
                confirm_permissions=True,
                allow_update=False,
            )
            self.assertEqual(installed["source"], "third-party")
            service.enable_plugin("com.example.installable")
            service.plugins.invoke_chat_action(
                "com.example.installable", "run", {"run_id": "run-1"}
            )
            logs = service.list_plugin_logs("com.example.installable")["items"]
            self.assertTrue(any(item["event"] == "chat_action_completed" for item in logs))

            update = service.inspect_plugin_package(
                filename="installable-v2.zip",
                content_base64=base64.b64encode(
                    _plugin_package_bytes(version="2.0.0")
                ).decode("ascii"),
            )
            self.assertEqual(update["operation"], "update")
            self.assertEqual(update["currentVersion"], "1.0.0")
            updated = service.install_inspected_plugin_package(
                update["token"],
                confirm_permissions=True,
                allow_update=True,
            )
            self.assertEqual(updated["version"], "2.0.0")

            removed = service.uninstall_plugin("com.example.installable")
            self.assertEqual(removed["status"], "uninstalled")
            self.assertTrue(Path(removed["recoverablePath"]).is_dir())
            self.assertNotIn(
                "com.example.installable",
                {item["id"] for item in service.list_plugins()},
            )

    def test_plugin_package_rejects_path_traversal(self):
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            with self.assertRaisesRegex(PluginError, "不安全路径"):
                service.inspect_plugin_package(
                    filename="unsafe.zip",
                    content_base64=base64.b64encode(
                        _plugin_package_bytes(unsafe_path="../escape.txt")
                    ).decode("ascii"),
                )

    def test_plugin_package_reports_incompatible_api_without_installing(self):
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            inspected = service.inspect_plugin_package(
                filename="future.zip",
                content_base64=base64.b64encode(
                    _plugin_package_bytes(api_version="2")
                ).decode("ascii"),
            )
            self.assertFalse(inspected["compatible"])
            self.assertEqual(inspected["operation"], "blocked")
            self.assertIn("API 2", inspected["blockedReason"])

    def test_plugin_package_rejects_corrupt_zip(self):
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            with self.assertRaisesRegex(PluginError, "有效的 ZIP"):
                service.inspect_plugin_package(
                    filename="broken.zip",
                    content_base64=base64.b64encode(b"not-a-zip").decode("ascii"),
                )

    def test_plugin_package_cannot_override_official_plugin(self):
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            inspected = service.inspect_plugin_package(
                filename="fake-official.zip",
                content_base64=base64.b64encode(
                    _plugin_package_bytes(plugin_id="com.zaomeng.ai-association")
                ).decode("ascii"),
            )
            self.assertEqual(inspected["operation"], "blocked")
            self.assertIn("内置插件", inspected["blockedReason"])

    def test_plugin_update_requires_explicit_update_confirmation(self):
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            first = service.inspect_plugin_package(
                filename="v1.zip",
                content_base64=base64.b64encode(_plugin_package_bytes()).decode("ascii"),
            )
            service.install_inspected_plugin_package(
                first["token"], confirm_permissions=True, allow_update=False
            )
            update = service.inspect_plugin_package(
                filename="v2.zip",
                content_base64=base64.b64encode(
                    _plugin_package_bytes(version="2.0.0")
                ).decode("ascii"),
            )
            with self.assertRaisesRegex(PluginError, "明确选择更新"):
                service.install_inspected_plugin_package(
                    update["token"],
                    confirm_permissions=True,
                    allow_update=False,
                )

    def test_failed_plugin_update_restores_previous_working_version(self):
        from src.web.workflow import WebRunService

        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            first = service.inspect_plugin_package(
                filename="v1.zip",
                content_base64=base64.b64encode(_plugin_package_bytes()).decode("ascii"),
            )
            service.install_inspected_plugin_package(
                first["token"], confirm_permissions=True, allow_update=False
            )
            service.enable_plugin("com.example.installable")
            update = service.inspect_plugin_package(
                filename="v2.zip",
                content_base64=base64.b64encode(
                    _plugin_package_bytes(version="2.0.0")
                ).decode("ascii"),
            )
            with patch(
                "src.web.service_facades.plugins._move_plugin_directory",
                side_effect=OSError("simulated copy failure"),
            ):
                with self.assertRaisesRegex(PluginError, "simulated copy failure"):
                    service.install_inspected_plugin_package(
                        update["token"],
                        confirm_permissions=True,
                        allow_update=True,
                    )

            restored = next(
                item
                for item in service.list_plugins()
                if item["id"] == "com.example.installable"
            )
            self.assertEqual(restored["version"], "1.0.0")
            self.assertTrue(restored["enabled"])
            result = service.plugins.invoke_chat_action(
                "com.example.installable", "run", {"run_id": "run-1"}
            )
            self.assertEqual(result["suggestion"], "installed")
    def test_android_chat_surfaces_plugins_in_a_dedicated_menu(self):
        chat_screen = Path(
            "android/app/shared/src/commonMain/kotlin/"
            "top/wkbin/zaomeng/feature/chat/ChatScreen.kt"
        ).read_text(encoding="utf-8")

        self.assertIn('Text(if (pluginActionBusy) "插件运行中…" else "插件")', chat_screen)
        self.assertIn("state.pluginActions.forEach", chat_screen)
        self.assertIn("state.generationEnhancers.forEach", chat_screen)
        chat_view_model = Path(
            "android/app/shared/src/commonMain/kotlin/"
            "top/wkbin/zaomeng/feature/chat/ChatViewModel.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("temporaryNpcGenerators", chat_view_model)
        self.assertIn("invokePluginTemporaryNpcGenerator", chat_view_model)
        self.assertNotIn('contentDescription = "一键生成续写建议"', chat_screen)
        self.assertNotIn('Text(if (state.includeInnerThoughts) "关闭读心"', chat_screen)

    def test_manifest_rejects_unknown_permission(self):
        with tempfile.TemporaryDirectory() as tmp:
            plugin_root = _write_plugin(
                Path(tmp),
                permissions=[
                    "chat.context.read",
                    "chat.draft.write",
                    "host.everything",
                ],
            )
            with self.assertRaisesRegex(PluginError, "Unknown plugin permissions"):
                PluginManifest.load(plugin_root / "plugin.json")

    def test_plugin_can_enable_invoke_disable_and_hot_reload(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "plugins"
            _write_plugin(root, result="v1")
            state_path = Path(tmp) / "plugin-state.json"
            registry = PluginRegistry(
                [root],
                host_factory=lambda _plugin_id, _permissions: _Host(),
                state_path=state_path,
            )

            self.assertTrue(registry.list_plugins()[0]["enabled"])
            self.assertEqual(registry.list_plugins()[0]["source"], "official")
            self.assertEqual(
                registry.invoke_chat_action(
                    "com.example.demo", "run", {"run_id": "run-1"}
                )["suggestion"],
                "v1:run-1",
            )

            registry.disable("com.example.demo")
            with self.assertRaisesRegex(PluginError, "disabled"):
                registry.invoke_chat_action(
                    "com.example.demo", "run", {"run_id": "run-1"}
                )

            registry.enable("com.example.demo")
            _write_plugin(root, result="v2")
            registry.refresh()
            self.assertEqual(
                registry.invoke_chat_action(
                    "com.example.demo", "run", {"run_id": "run-2"}
                )["suggestion"],
                "v2:run-2",
            )

    def test_plugin_service_invokes_discovered_chat_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "plugins"
            _write_plugin(root)
            registry = PluginRegistry(
                [root],
                host_factory=lambda _plugin_id, _permissions: _Host(),
                state_path=Path(tmp) / "plugin-state.json",
            )

            result = _PluginService(registry).invoke_plugin_chat_action(
                "com.example.demo",
                "run",
                run_id="run-1",
                session_id="session-1",
                seed_text="draft",
            )

        self.assertEqual(result["suggestion"], "v1:run-1")

    def test_host_denies_undeclared_model_permission(self):
        host = ZaomengPluginHost(object(), "com.example.demo", frozenset())
        with self.assertRaises(PluginPermissionError):
            host.invoke_model("dialogue_suggestion", {"run_id": "run-1"})


if __name__ == "__main__":
    unittest.main()
