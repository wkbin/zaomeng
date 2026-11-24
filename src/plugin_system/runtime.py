from __future__ import annotations

import json
import re
import sys
import threading
import traceback
from datetime import datetime, timezone
from dataclasses import dataclass
from pathlib import Path
from types import ModuleType
from typing import Any, Callable, Protocol


PLUGIN_API_VERSION = "1"
_PLUGIN_ID = re.compile(r"^[a-z0-9]+(?:[._-][a-z0-9]+)+$")
_ACTION_ID = re.compile(r"^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
_SETTING_KEY = re.compile(r"^[A-Za-z][A-Za-z0-9._-]*$")
_KNOWN_PERMISSIONS = {
    "chat.context.read",
    "chat.cast.write",
    "chat.draft.write",
    "generation.enhance",
    "model.invoke",
    "storage.read",
    "storage.write",
    "network.access",
}


class PluginError(ValueError):
    """Raised when a plugin cannot be discovered, loaded, or invoked."""


class PluginPermissionError(PluginError):
    """Raised when a plugin calls a host capability it did not declare."""


class PluginHost(Protocol):
    def read_dialogue_context(
        self,
        *,
        run_id: str,
        session_id: str,
        seed_text: str = "",
        direction: str = "",
    ) -> dict[str, Any]: ...

    def invoke_model(self, capability: str, payload: dict[str, Any]) -> Any: ...


@dataclass(frozen=True)
class ChatAction:
    id: str
    title: str
    placement: str
    icon: str = ""

    @classmethod
    def parse(cls, payload: Any) -> "ChatAction":
        if not isinstance(payload, dict):
            raise PluginError("chatActions entries must be JSON objects.")
        action_id = str(payload.get("id", "")).strip()
        title = str(payload.get("title", "")).strip()
        placement = str(payload.get("placement", "composer")).strip() or "composer"
        if not _ACTION_ID.fullmatch(action_id):
            raise PluginError(f"Invalid chat action id: {action_id!r}.")
        if not title or len(title) > 80:
            raise PluginError("A chat action title must contain 1-80 characters.")
        if placement not in {"composer", "message", "tools"}:
            raise PluginError(f"Unsupported chat action placement: {placement!r}.")
        return cls(
            id=action_id,
            title=title,
            placement=placement,
            icon=str(payload.get("icon", "")).strip()[:40],
        )

    def to_dict(self) -> dict[str, str]:
        return {
            "id": self.id,
            "title": self.title,
            "placement": self.placement,
            "icon": self.icon,
        }


@dataclass(frozen=True)
class GenerationEnhancer:
    id: str
    title: str
    description: str = ""
    icon: str = ""
    default_active: bool = False

    @classmethod
    def parse(cls, payload: Any) -> "GenerationEnhancer":
        if not isinstance(payload, dict):
            raise PluginError("generationEnhancers entries must be JSON objects.")
        enhancer_id = str(payload.get("id", "")).strip()
        title = str(payload.get("title", "")).strip()
        if not _ACTION_ID.fullmatch(enhancer_id):
            raise PluginError(f"Invalid generation enhancer id: {enhancer_id!r}.")
        if not title or len(title) > 80:
            raise PluginError("A generation enhancer title must contain 1-80 characters.")
        return cls(
            id=enhancer_id,
            title=title,
            description=str(payload.get("description", "")).strip()[:240],
            icon=str(payload.get("icon", "")).strip()[:40],
            default_active=bool(payload.get("defaultActive", False)),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "title": self.title,
            "description": self.description,
            "icon": self.icon,
            "defaultActive": self.default_active,
        }


@dataclass(frozen=True)
class TemporaryNpcGenerator:
    id: str
    title: str
    icon: str = ""

    @classmethod
    def parse(cls, payload: Any) -> "TemporaryNpcGenerator":
        if not isinstance(payload, dict):
            raise PluginError("temporaryNpcGenerators entries must be JSON objects.")
        generator_id = str(payload.get("id", "")).strip()
        title = str(payload.get("title", "")).strip()
        if not _ACTION_ID.fullmatch(generator_id):
            raise PluginError(f"Invalid temporary NPC generator id: {generator_id!r}.")
        if not title or len(title) > 80:
            raise PluginError(
                "A temporary NPC generator title must contain 1-80 characters."
            )
        return cls(
            id=generator_id,
            title=title,
            icon=str(payload.get("icon", "")).strip()[:40],
        )

    def to_dict(self) -> dict[str, str]:
        return {"id": self.id, "title": self.title, "icon": self.icon}


@dataclass(frozen=True)
class PluginSetting:
    key: str
    title: str
    kind: str
    default: Any
    minimum: int | None = None
    maximum: int | None = None
    options: tuple[tuple[str, str], ...] = ()

    @classmethod
    def parse(cls, payload: Any) -> "PluginSetting":
        if not isinstance(payload, dict):
            raise PluginError("settings entries must be JSON objects.")
        key = str(payload.get("key", "")).strip()
        title = str(payload.get("title", "")).strip()
        kind = str(payload.get("type", "")).strip()
        if not _SETTING_KEY.fullmatch(key):
            raise PluginError(f"Invalid plugin setting key: {key!r}.")
        if not title or len(title) > 80:
            raise PluginError("A plugin setting title must contain 1-80 characters.")
        if kind == "boolean":
            default: Any = bool(payload.get("default", False))
            return cls(key, title, kind, default)
        if kind == "integer":
            minimum = int(payload.get("min", 0))
            maximum = int(payload.get("max", 100))
            if minimum > maximum:
                raise PluginError(f"Invalid range for plugin setting {key!r}.")
            default = int(payload.get("default", minimum))
            if not minimum <= default <= maximum:
                raise PluginError(f"Default value is outside the range for {key!r}.")
            return cls(key, title, kind, default, minimum, maximum)
        if kind == "enum":
            raw_options = payload.get("options", [])
            if not isinstance(raw_options, list) or not raw_options:
                raise PluginError(f"Enum plugin setting {key!r} requires options.")
            options: list[tuple[str, str]] = []
            for item in raw_options:
                if not isinstance(item, dict):
                    raise PluginError(f"Enum options for {key!r} must be objects.")
                value = str(item.get("value", "")).strip()
                label = str(item.get("label", "")).strip()
                if not value or not label:
                    raise PluginError(f"Enum options for {key!r} require value and label.")
                options.append((value, label))
            default = str(payload.get("default", options[0][0])).strip()
            if default not in {value for value, _label in options}:
                raise PluginError(f"Invalid default enum value for {key!r}.")
            return cls(key, title, kind, default, options=tuple(options))
        raise PluginError(f"Unsupported plugin setting type: {kind!r}.")

    def normalize(self, value: Any) -> Any:
        if self.kind == "boolean":
            if not isinstance(value, bool):
                raise PluginError(f"Plugin setting {self.key!r} must be boolean.")
            return value
        if self.kind == "integer":
            if isinstance(value, bool) or not isinstance(value, int):
                raise PluginError(f"Plugin setting {self.key!r} must be an integer.")
            if value < int(self.minimum) or value > int(self.maximum):
                raise PluginError(f"Plugin setting {self.key!r} is outside its allowed range.")
            return value
        normalized = str(value).strip()
        if normalized not in {option for option, _label in self.options}:
            raise PluginError(f"Plugin setting {self.key!r} has an unsupported value.")
        return normalized

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "key": self.key,
            "title": self.title,
            "type": self.kind,
            "default": self.default,
        }
        if self.kind == "integer":
            payload.update({"min": self.minimum, "max": self.maximum})
        if self.kind == "enum":
            payload["options"] = [
                {"value": value, "label": label} for value, label in self.options
            ]
        return payload


@dataclass(frozen=True)
class PluginManifest:
    id: str
    name: str
    version: str
    api_version: str
    entry: str
    description: str
    permissions: tuple[str, ...]
    chat_actions: tuple[ChatAction, ...]
    generation_enhancers: tuple[GenerationEnhancer, ...]
    temporary_npc_generators: tuple[TemporaryNpcGenerator, ...]
    settings: tuple[PluginSetting, ...]
    default_enabled: bool

    @classmethod
    def load(cls, path: Path) -> "PluginManifest":
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise PluginError(f"Cannot read plugin manifest {path}: {exc}") from exc
        if not isinstance(raw, dict):
            raise PluginError("plugin.json must contain a JSON object.")

        plugin_id = str(raw.get("id", "")).strip()
        name = str(raw.get("name", "")).strip()
        version = str(raw.get("version", "")).strip()
        api_version = str(raw.get("apiVersion", "")).strip()
        entry = str(raw.get("entry", "")).strip()
        if not _PLUGIN_ID.fullmatch(plugin_id):
            raise PluginError(f"Invalid plugin id: {plugin_id!r}.")
        if not name or len(name) > 80:
            raise PluginError("Plugin name must contain 1-80 characters.")
        if not version or len(version) > 40:
            raise PluginError("Plugin version is required and must be at most 40 characters.")
        if api_version != PLUGIN_API_VERSION:
            raise PluginError(
                f"Plugin {plugin_id!r} requires API {api_version!r}; host supports {PLUGIN_API_VERSION!r}."
            )
        if not entry or Path(entry).is_absolute() or ".." in Path(entry).parts:
            raise PluginError("Plugin entry must be a relative path inside the plugin directory.")
        if Path(entry).suffix.lower() != ".py":
            raise PluginError("Plugin API v1 only supports Python entry files.")

        raw_permissions = raw.get("permissions", [])
        if not isinstance(raw_permissions, list):
            raise PluginError("permissions must be a JSON array.")
        permissions = tuple(dict.fromkeys(str(item).strip() for item in raw_permissions))
        unknown = sorted(set(permissions) - _KNOWN_PERMISSIONS)
        if unknown:
            raise PluginError(f"Unknown plugin permissions: {', '.join(unknown)}.")

        contributes = raw.get("contributes", {}) or {}
        if not isinstance(contributes, dict):
            raise PluginError("contributes must be a JSON object.")
        raw_actions = contributes.get("chatActions", []) or []
        if not isinstance(raw_actions, list):
            raise PluginError("contributes.chatActions must be a JSON array.")
        actions = tuple(ChatAction.parse(item) for item in raw_actions)
        action_ids = [item.id for item in actions]
        if len(action_ids) != len(set(action_ids)):
            raise PluginError("Chat action ids must be unique within a plugin.")
        if actions and not {"chat.context.read", "chat.draft.write"}.issubset(permissions):
            raise PluginError(
                "Plugins contributing chatActions must declare chat.context.read and chat.draft.write."
            )
        raw_enhancers = contributes.get("generationEnhancers", []) or []
        if not isinstance(raw_enhancers, list):
            raise PluginError("contributes.generationEnhancers must be a JSON array.")
        enhancers = tuple(GenerationEnhancer.parse(item) for item in raw_enhancers)
        enhancer_ids = [item.id for item in enhancers]
        if len(enhancer_ids) != len(set(enhancer_ids)):
            raise PluginError("Generation enhancer ids must be unique within a plugin.")
        if enhancers and "generation.enhance" not in permissions:
            raise PluginError(
                "Plugins contributing generationEnhancers must declare generation.enhance."
            )
        raw_npc_generators = contributes.get("temporaryNpcGenerators", []) or []
        if not isinstance(raw_npc_generators, list):
            raise PluginError(
                "contributes.temporaryNpcGenerators must be a JSON array."
            )
        npc_generators = tuple(
            TemporaryNpcGenerator.parse(item) for item in raw_npc_generators
        )
        npc_generator_ids = [item.id for item in npc_generators]
        if len(npc_generator_ids) != len(set(npc_generator_ids)):
            raise PluginError(
                "Temporary NPC generator ids must be unique within a plugin."
            )
        if npc_generators and not {
            "chat.context.read",
            "chat.cast.write",
        }.issubset(permissions):
            raise PluginError(
                "Plugins contributing temporaryNpcGenerators must declare "
                "chat.context.read and chat.cast.write."
            )
        raw_settings = raw.get("settings", []) or []
        if not isinstance(raw_settings, list):
            raise PluginError("settings must be a JSON array.")
        settings = tuple(PluginSetting.parse(item) for item in raw_settings)
        setting_keys = [item.key for item in settings]
        if len(setting_keys) != len(set(setting_keys)):
            raise PluginError("Plugin setting keys must be unique within a plugin.")
        return cls(
            id=plugin_id,
            name=name,
            version=version,
            api_version=api_version,
            entry=entry,
            description=str(raw.get("description", "")).strip()[:500],
            permissions=permissions,
            chat_actions=actions,
            generation_enhancers=enhancers,
            temporary_npc_generators=npc_generators,
            settings=settings,
            default_enabled=bool(raw.get("defaultEnabled", False)),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "version": self.version,
            "apiVersion": self.api_version,
            "description": self.description,
            "permissions": list(self.permissions),
            "contributes": {
                "chatActions": [item.to_dict() for item in self.chat_actions],
                "generationEnhancers": [
                    item.to_dict() for item in self.generation_enhancers
                ],
                "temporaryNpcGenerators": [
                    item.to_dict() for item in self.temporary_npc_generators
                ],
            },
            "settings": [item.to_dict() for item in self.settings],
            "defaultEnabled": self.default_enabled,
        }


@dataclass
class _PluginRecord:
    manifest: PluginManifest
    root: Path
    source: str = "third-party"
    instance: Any = None
    module: ModuleType | None = None
    module_prefix: str = ""
    enabled: bool = False
    error: str = ""
    active_calls: int = 0


class PluginRegistry:
    """Discovers and hot-enables trusted, in-process API v1 plugins."""

    def __init__(
        self,
        roots: list[Path],
        *,
        host_factory: Callable[[str, frozenset[str]], PluginHost],
        state_path: Path | None = None,
        log_path: Path | None = None,
        config_path: Path | None = None,
    ) -> None:
        self._roots = tuple(Path(item) for item in roots)
        self._host_factory = host_factory
        self._state_path = state_path
        self._log_path = log_path
        self._config_path = config_path
        self._config_store = self._load_config_store()
        self._records: dict[str, _PluginRecord] = {}
        self._lock = threading.RLock()
        self._condition = threading.Condition(self._lock)
        self.refresh()

    def _load_config_store(self) -> dict[str, dict[str, Any]]:
        if self._config_path is None or not self._config_path.is_file():
            return {}
        try:
            payload = json.loads(self._config_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}
        if not isinstance(payload, dict):
            return {}
        return {
            str(plugin_id): dict(values)
            for plugin_id, values in payload.items()
            if isinstance(values, dict)
        }

    def _save_config_store(self) -> None:
        if self._config_path is None:
            return
        self._config_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self._config_path.with_suffix(self._config_path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(self._config_store, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        temporary.replace(self._config_path)

    def _load_enabled_state(self) -> set[str] | None:
        if self._state_path is None or not self._state_path.is_file():
            return None
        try:
            payload = json.loads(self._state_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return set()
        values = payload.get("enabled", []) if isinstance(payload, dict) else []
        return {str(item).strip() for item in values if str(item).strip()}

    def _save_enabled_state(self) -> None:
        if self._state_path is None:
            return
        self._state_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"enabled": sorted(key for key, item in self._records.items() if item.enabled)}
        temporary = self._state_path.with_suffix(self._state_path.suffix + ".tmp")
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self._state_path)

    def refresh(self) -> list[dict[str, Any]]:
        with self._lock:
            desired = self._load_enabled_state()
            previously_enabled = {
                plugin_id for plugin_id, record in self._records.items() if record.enabled
            }
            discovered: dict[str, _PluginRecord] = {}
            for base in self._roots:
                if not base.is_dir():
                    continue
                for manifest_path in sorted(base.glob("*/plugin.json")):
                    try:
                        manifest = PluginManifest.load(manifest_path)
                        entry = (manifest_path.parent / manifest.entry).resolve()
                        if not entry.is_relative_to(manifest_path.parent.resolve()) or not entry.is_file():
                            raise PluginError(f"Plugin entry does not exist: {manifest.entry}.")
                        if manifest.id in discovered:
                            raise PluginError(f"Duplicate plugin id: {manifest.id}.")
                        discovered[manifest.id] = _PluginRecord(
                            manifest=manifest,
                            root=manifest_path.parent,
                            source=(
                                "official"
                                if base == self._roots[0]
                                else "third-party"
                            ),
                        )
                    except PluginError as exc:
                        self.record_event(
                            "",
                            "error",
                            "discovery_failed",
                            str(exc),
                            details={"manifest": str(manifest_path)},
                        )
                        continue

            for record in self._records.values():
                self._deactivate(record)
            self._records = discovered
            for plugin_id, record in self._records.items():
                # 第三方插件的入口会被 exec 进当前进程，defaultEnabled 不能替用户做主：
                # 只有内置插件可以默认启用，第三方必须在 UI 里显式启用（写入 plugin-state.json）。
                default_enabled = record.source == "official" and record.manifest.default_enabled
                should_enable = (
                    plugin_id in desired
                    if desired is not None
                    else (plugin_id in previously_enabled or default_enabled)
                )
                if should_enable and not record.enabled:
                    try:
                        self._activate(record)
                    except PluginError as exc:
                        record.error = str(exc)
            return self.list_plugins()

    def _activate(self, record: _PluginRecord) -> None:
        entry = (record.root / record.manifest.entry).resolve()
        module_prefix = f"_zaomeng_plugin_{record.manifest.id.replace('.', '_').replace('-', '_')}"
        entry_parts = Path(record.manifest.entry).with_suffix("").parts
        module_name = ".".join((module_prefix, *entry_parts))
        package = ModuleType(module_prefix)
        package.__path__ = [str(record.root)]  # type: ignore[attr-defined]
        package.__package__ = module_prefix
        sys.modules[module_prefix] = package
        module = ModuleType(module_name)
        module.__file__ = str(entry)
        module.__package__ = module_name.rpartition(".")[0]
        sys.modules[module_name] = module
        try:
            source = entry.read_text(encoding="utf-8")
            exec(compile(source, str(entry), "exec"), module.__dict__)
            factory = getattr(module, "create_plugin", None)
            if not callable(factory):
                raise PluginError("Plugin entry must export create_plugin().")
            instance = factory()
            execute = getattr(instance, "execute_chat_action", None)
            if record.manifest.chat_actions and not callable(execute):
                raise PluginError("Plugin contributes chatActions but has no execute_chat_action().")
            generate_npc = getattr(instance, "generate_temporary_npc", None)
            if record.manifest.temporary_npc_generators and not callable(generate_npc):
                raise PluginError(
                    "Plugin contributes temporaryNpcGenerators but has no "
                    "generate_temporary_npc()."
                )
            host = self._host_factory(record.manifest.id, frozenset(record.manifest.permissions))
            activate = getattr(instance, "activate", None)
            if callable(activate):
                activate(host)
            record.module = module
            record.module_prefix = module_prefix
            record.instance = instance
            record.enabled = True
            record.error = ""
            self.record_event(record.manifest.id, "info", "enabled", "插件已启用。")
        except Exception as exc:
            self.record_event(
                record.manifest.id,
                "error",
                "activation_failed",
                str(exc),
                details={"traceback": traceback.format_exc()},
            )
            for loaded_name in tuple(sys.modules):
                if loaded_name == module_prefix or loaded_name.startswith(module_prefix + "."):
                    sys.modules.pop(loaded_name, None)
            if isinstance(exc, PluginError):
                raise
            raise PluginError(f"Plugin {record.manifest.id!r} failed to activate: {exc}") from exc

    def _deactivate(self, record: _PluginRecord) -> None:
        while record.active_calls > 0:
            self._condition.wait()
        if record.instance is not None:
            deactivate = getattr(record.instance, "deactivate", None)
            if callable(deactivate):
                try:
                    deactivate()
                except Exception as exc:
                    self.record_event(
                        record.manifest.id,
                        "warning",
                        "deactivation_failed",
                        str(exc),
                        details={"traceback": traceback.format_exc()},
                    )
        if record.module_prefix:
            for loaded_name in tuple(sys.modules):
                if loaded_name == record.module_prefix or loaded_name.startswith(
                    record.module_prefix + "."
                ):
                    sys.modules.pop(loaded_name, None)
        record.instance = None
        record.module = None
        record.module_prefix = ""
        record.enabled = False
        self.record_event(record.manifest.id, "info", "disabled", "插件已停用。")

    def enable(self, plugin_id: str) -> dict[str, Any]:
        with self._lock:
            record = self._require_record(plugin_id)
            if not record.enabled:
                self._activate(record)
            self._save_enabled_state()
            return self._view(record)

    def disable(self, plugin_id: str) -> dict[str, Any]:
        with self._lock:
            record = self._require_record(plugin_id)
            self._deactivate(record)
            record.error = ""
            self._save_enabled_state()
            return self._view(record)

    def _require_record(self, plugin_id: str) -> _PluginRecord:
        normalized = str(plugin_id).strip()
        record = self._records.get(normalized)
        if record is None:
            raise PluginError(f"Plugin not found: {normalized!r}.")
        return record

    def invoke_chat_action(
        self, plugin_id: str, action_id: str, request: dict[str, Any]
    ) -> dict[str, Any]:
        with self._lock:
            record = self._require_record(plugin_id)
            if not record.enabled or record.instance is None:
                raise PluginError(f"Plugin is disabled: {plugin_id!r}.")
            if action_id not in {item.id for item in record.manifest.chat_actions}:
                raise PluginError(f"Chat action not found: {plugin_id}/{action_id}.")
            instance = record.instance
            record.active_calls += 1
        try:
            payload = dict(request)
            payload["config"] = self.get_config(plugin_id)
            result = instance.execute_chat_action(action_id, payload)
        except Exception as exc:
            self.record_event(
                plugin_id,
                "error",
                "chat_action_failed",
                str(exc),
                details={"actionId": action_id, "traceback": traceback.format_exc()},
            )
            raise
        finally:
            with self._lock:
                record.active_calls -= 1
                self._condition.notify_all()
        if not isinstance(result, dict):
            self.record_event(
                plugin_id,
                "error",
                "invalid_chat_action_result",
                "聊天动作必须返回 JSON 对象。",
                details={"actionId": action_id, "resultType": type(result).__name__},
            )
            raise PluginError("Chat action result must be a JSON object.")
        self.record_event(
            plugin_id,
            "info",
            "chat_action_completed",
            "聊天动作执行完成。",
            details={"actionId": action_id},
        )
        return result

    def require_generation_enhancer(
        self, plugin_id: str, enhancer_id: str
    ) -> dict[str, Any]:
        with self._lock:
            record = self._require_record(plugin_id)
            if not record.enabled or record.instance is None:
                raise PluginError(f"Plugin is disabled: {plugin_id!r}.")
            enhancer = next(
                (
                    item
                    for item in record.manifest.generation_enhancers
                    if item.id == enhancer_id
                ),
                None,
            )
            if enhancer is None:
                raise PluginError(
                    f"Generation enhancer not found: {plugin_id}/{enhancer_id}."
                )
            return enhancer.to_dict()

    def invoke_temporary_npc_generator(
        self, plugin_id: str, generator_id: str, request: dict[str, Any]
    ) -> dict[str, Any]:
        with self._lock:
            record = self._require_record(plugin_id)
            if not record.enabled or record.instance is None:
                raise PluginError(f"Plugin is disabled: {plugin_id!r}.")
            if generator_id not in {
                item.id for item in record.manifest.temporary_npc_generators
            }:
                raise PluginError(
                    f"Temporary NPC generator not found: {plugin_id}/{generator_id}."
                )
            instance = record.instance
            record.active_calls += 1
        try:
            payload = dict(request)
            payload["config"] = self.get_config(plugin_id)
            result = instance.generate_temporary_npc(generator_id, payload)
        except Exception as exc:
            self.record_event(
                plugin_id,
                "error",
                "temporary_npc_generator_failed",
                str(exc),
                details={
                    "generatorId": generator_id,
                    "traceback": traceback.format_exc(),
                },
            )
            raise
        finally:
            with self._lock:
                record.active_calls -= 1
                self._condition.notify_all()
        if not isinstance(result, dict):
            raise PluginError("Temporary NPC generator result must be a JSON object.")
        self.record_event(
            plugin_id,
            "info",
            "temporary_npc_generator_completed",
            "临时 NPC 生成完成。",
            details={"generatorId": generator_id},
        )
        return result

    def invoke_generation_enhancer(
        self, plugin_id: str, enhancer_id: str, request: dict[str, Any]
    ) -> dict[str, Any]:
        self.require_generation_enhancer(plugin_id, enhancer_id)
        with self._lock:
            record = self._require_record(plugin_id)
            instance = record.instance
            record.active_calls += 1
        try:
            method = getattr(instance, "enhance_generation", None)
            if not callable(method):
                raise PluginError(
                    f"Plugin does not implement enhance_generation: {plugin_id!r}."
                )
            payload = dict(request)
            payload["config"] = self.get_config(plugin_id)
            result = method(enhancer_id, payload)
        except Exception as exc:
            self.record_event(
                plugin_id,
                "error",
                "generation_enhancer_failed",
                str(exc),
                details={"enhancerId": enhancer_id, "traceback": traceback.format_exc()},
            )
            raise
        finally:
            with self._lock:
                record.active_calls -= 1
                self._condition.notify_all()
        if not isinstance(result, dict):
            raise PluginError("Generation enhancer result must be a JSON object.")
        return result

    def record_event(
        self,
        plugin_id: str,
        level: str,
        event: str,
        message: str,
        *,
        details: dict[str, Any] | None = None,
    ) -> None:
        if self._log_path is None:
            return
        payload = {
            "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "pluginId": str(plugin_id or ""),
            "level": str(level or "info"),
            "event": str(event or "event"),
            "message": str(message or "")[:2000],
            "details": dict(details or {}),
        }
        try:
            with self._lock:
                self._log_path.parent.mkdir(parents=True, exist_ok=True)
                if self._log_path.exists() and self._log_path.stat().st_size > 2 * 1024 * 1024:
                    rotated = self._log_path.with_suffix(self._log_path.suffix + ".1")
                    rotated.unlink(missing_ok=True)
                    self._log_path.replace(rotated)
                with self._log_path.open("a", encoding="utf-8") as output:
                    output.write(json.dumps(payload, ensure_ascii=False, default=str) + "\n")
        except OSError:
            pass

    def list_logs(self, plugin_id: str, *, limit: int = 100) -> list[dict[str, Any]]:
        self._require_record(plugin_id)
        if self._log_path is None or not self._log_path.is_file():
            return []
        safe_limit = max(1, min(int(limit), 500))
        try:
            lines = self._log_path.read_text(encoding="utf-8").splitlines()
        except OSError:
            return []
        items: list[dict[str, Any]] = []
        for line in reversed(lines):
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(item, dict) and item.get("pluginId") == plugin_id:
                items.append(item)
                if len(items) >= safe_limit:
                    break
        return items

    def list_plugins(self) -> list[dict[str, Any]]:
        with self._lock:
            return [self._view(item) for item in self._records.values()]

    def get_config(self, plugin_id: str) -> dict[str, Any]:
        with self._lock:
            record = self._require_record(plugin_id)
            stored = dict(self._config_store.get(plugin_id, {}))
            values: dict[str, Any] = {}
            for setting in record.manifest.settings:
                candidate = stored.get(setting.key, setting.default)
                try:
                    values[setting.key] = setting.normalize(candidate)
                except PluginError:
                    values[setting.key] = setting.default
            return values

    def update_config(self, plugin_id: str, values: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(values, dict):
            raise PluginError("Plugin config must be a JSON object.")
        with self._lock:
            record = self._require_record(plugin_id)
            settings = {item.key: item for item in record.manifest.settings}
            unknown = sorted(set(values) - set(settings))
            if unknown:
                raise PluginError(f"Unknown plugin settings: {', '.join(unknown)}.")
            current = self.get_config(plugin_id)
            for key, value in values.items():
                current[key] = settings[key].normalize(value)
            self._config_store[plugin_id] = current
            self._save_config_store()
            self.record_event(
                plugin_id,
                "info",
                "config_updated",
                "插件配置已更新。",
                details={"keys": sorted(values)},
            )
            return current

    def plugin_location(self, plugin_id: str) -> tuple[Path, str]:
        with self._lock:
            record = self._require_record(plugin_id)
            return record.root, record.source

    def _view(self, record: _PluginRecord) -> dict[str, Any]:
        return {
            **record.manifest.to_dict(),
            "enabled": record.enabled,
            "status": "enabled" if record.enabled else ("error" if record.error else "disabled"),
            "error": record.error,
            "source": record.source,
            "config": self.get_config(record.manifest.id),
        }
