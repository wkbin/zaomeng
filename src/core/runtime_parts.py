#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

from dataclasses import dataclass, fields
from typing import Any, Optional

from src.core.config import Config
from src.core.contracts import CostEstimator, RelationStore, RelationVisualizationExporter, SessionStore
from src.core.host_llm_adapter import HostProvidedLLM
from src.core.llm_client import LLMClient
from src.core.path_provider import PathProvider
from src.core.relation_store import MarkdownRelationStore
from src.core.relation_visualization_exporter import MermaidRelationVisualizationExporter
from src.core.rulebook import RuleBook
from src.core.session_store import MarkdownSessionStore
from src.modules.chat_engine import ChatEngine
from src.modules.distillation import NovelDistiller
from src.modules.reflection import ReflectionEngine
from src.modules.relationships import RelationshipExtractor
from src.modules.speaker import Speaker
from src.utils.token_counter import TokenCounter


class _RelationVisualizationRenderer:
    def __init__(self, path_provider: PathProvider):
        self.path_provider = path_provider

    _build_visual_node_styles = RelationshipExtractor._build_visual_node_styles
    _load_profile_visual_metadata = RelationshipExtractor._load_profile_visual_metadata
    _parse_profile_visual_metadata = staticmethod(RelationshipExtractor._parse_profile_visual_metadata)
    _relation_node_names = staticmethod(RelationshipExtractor._relation_node_names)
    _node_category = staticmethod(RelationshipExtractor._node_category)
    _category_palette = staticmethod(RelationshipExtractor._category_palette)
    _render_mermaid_graph = RelationshipExtractor._render_mermaid_graph
    _render_relation_html = RelationshipExtractor._render_relation_html
    _default_node_style = staticmethod(RelationshipExtractor._default_node_style)
    _mermaid_escape = staticmethod(RelationshipExtractor._mermaid_escape)
    _closeness_score = staticmethod(RelationshipExtractor._closeness_score)
    _edge_style = staticmethod(RelationshipExtractor._edge_style)
    _metric_badge = staticmethod(RelationshipExtractor._metric_badge)
    _graph_id = staticmethod(RelationshipExtractor._graph_id)


@dataclass
class RuntimeParts:
    config: Config
    path_provider: PathProvider
    rulebook: RuleBook
    llm: CostEstimator
    token_counter: TokenCounter
    _session_store: Optional[SessionStore] = None
    _relation_store: Optional[RelationStore] = None
    _relation_visualization_exporter: Optional[RelationVisualizationExporter] = None
    _reflection: Optional[ReflectionEngine] = None
    _distiller: Optional[NovelDistiller] = None
    _speaker: Optional[Speaker] = None
    _extractor: Optional[RelationshipExtractor] = None
    _chat_engine: Optional[ChatEngine] = None

    def create_reflection(self) -> ReflectionEngine:
        if self._reflection is None:
            self._reflection = ReflectionEngine.from_runtime_parts(self)
        return self._reflection

    def create_session_store(self) -> SessionStore:
        if self._session_store is None:
            self._session_store = MarkdownSessionStore(self.path_provider)
        return self._session_store

    @property
    def session_store(self) -> SessionStore:
        return self.create_session_store()

    @session_store.setter
    def session_store(self, value: Optional[SessionStore]) -> None:
        self._session_store = value

    def create_relation_store(self) -> RelationStore:
        if self._relation_store is None:
            self._relation_store = MarkdownRelationStore(self.path_provider)
        return self._relation_store

    @property
    def relation_store(self) -> RelationStore:
        return self.create_relation_store()

    @relation_store.setter
    def relation_store(self, value: Optional[RelationStore]) -> None:
        self._relation_store = value

    def create_relation_visualization_exporter(self) -> RelationVisualizationExporter:
        if self._relation_visualization_exporter is None:
            self._relation_visualization_exporter = MermaidRelationVisualizationExporter(
                _RelationVisualizationRenderer(self.path_provider)
            )
        return self._relation_visualization_exporter

    @property
    def relation_visualization_exporter(self) -> RelationVisualizationExporter:
        return self.create_relation_visualization_exporter()

    @relation_visualization_exporter.setter
    def relation_visualization_exporter(self, value: Optional[RelationVisualizationExporter]) -> None:
        self._relation_visualization_exporter = value

    @property
    def reflection(self) -> ReflectionEngine:
        return self.create_reflection()

    @reflection.setter
    def reflection(self, value: Optional[ReflectionEngine]) -> None:
        self._reflection = value

    def create_distiller(self) -> NovelDistiller:
        if self._distiller is None:
            self._distiller = NovelDistiller.from_runtime_parts(self)
        return self._distiller

    @property
    def distiller(self) -> NovelDistiller:
        return self.create_distiller()

    @distiller.setter
    def distiller(self, value: Optional[NovelDistiller]) -> None:
        self._distiller = value

    def create_speaker(self) -> Speaker:
        if self._speaker is None:
            self._speaker = Speaker.from_runtime_parts(self)
        return self._speaker

    @property
    def speaker(self) -> Speaker:
        return self.create_speaker()

    @speaker.setter
    def speaker(self, value: Optional[Speaker]) -> None:
        self._speaker = value

    def create_extractor(self) -> RelationshipExtractor:
        if self._extractor is None:
            self._extractor = RelationshipExtractor.from_runtime_parts(self)
        return self._extractor

    @property
    def extractor(self) -> RelationshipExtractor:
        return self.create_extractor()

    @extractor.setter
    def extractor(self, value: Optional[RelationshipExtractor]) -> None:
        self._extractor = value

    def build_chat_engine(self, chat_engine_cls: type[ChatEngine] = ChatEngine) -> ChatEngine:
        return chat_engine_cls(
            self.config,
            llm=self.llm,
            reflection=self.reflection,
            speaker=self.speaker,
            distiller=self.distiller,
            rulebook=self.rulebook,
            path_provider=self.path_provider,
            session_store=self.session_store,
            relation_store=self.relation_store,
        )

    def create_chat_engine(self) -> ChatEngine:
        if self._chat_engine is None:
            self._chat_engine = self.build_chat_engine()
        return self._chat_engine

    @property
    def chat_engine(self) -> ChatEngine:
        return self.create_chat_engine()

    @chat_engine.setter
    def chat_engine(self, value: Optional[ChatEngine]) -> None:
        self._chat_engine = value

    def shared_dependency_overrides(self) -> "RuntimeDependencyOverrides":
        return RuntimeDependencyOverrides(
            path_provider=self.path_provider,
            rulebook=self.rulebook,
            llm=self.llm,
            token_counter=self.token_counter,
            session_store=self.session_store,
            relation_store=self.relation_store,
            relation_visualization_exporter=self.relation_visualization_exporter,
            reflection=self._reflection,
            distiller=self._distiller,
            speaker=self._speaker,
            extractor=self._extractor,
            chat_engine=self._chat_engine,
        )

    def shared_infrastructure_overrides(self) -> "RuntimeDependencyOverrides":
        return RuntimeDependencyOverrides(
            path_provider=self.path_provider,
            rulebook=self.rulebook,
            llm=self.llm,
            token_counter=self.token_counter,
            session_store=self.session_store,
            relation_store=self.relation_store,
            relation_visualization_exporter=self.relation_visualization_exporter,
        )

    def fork(self, overrides: Optional["RuntimeDependencyOverrides"] = None) -> "RuntimeParts":
        return build_runtime_parts(
            self.config,
            overrides=self.shared_infrastructure_overrides().merged_with(overrides),
        )


@dataclass
class RuntimeDependencyOverrides:
    path_provider: Optional[PathProvider] = None
    rulebook: Optional[RuleBook] = None
    llm: Optional[CostEstimator] = None
    token_counter: Optional[TokenCounter] = None
    session_store: Optional[SessionStore] = None
    relation_store: Optional[RelationStore] = None
    relation_visualization_exporter: Optional[RelationVisualizationExporter] = None
    reflection: Optional[ReflectionEngine] = None
    distiller: Optional[NovelDistiller] = None
    speaker: Optional[Speaker] = None
    extractor: Optional[RelationshipExtractor] = None
    chat_engine: Optional[ChatEngine] = None

    def merged_with(self, other: Optional["RuntimeDependencyOverrides"] = None) -> "RuntimeDependencyOverrides":
        current = {item.name: getattr(self, item.name) for item in fields(self)}
        if other is None:
            return RuntimeDependencyOverrides(**current)
        merged = dict(current)
        for item in fields(other):
            key = item.name
            value = getattr(other, key)
            if value is not None:
                merged[key] = value
        return RuntimeDependencyOverrides(**merged)


def build_runtime_parts(
    config: Optional[Config] = None,
    *,
    overrides: Optional[RuntimeDependencyOverrides] = None,
    host_context: Any = None,
    host_llm_provider_name: str = "host-provided",
    host_llm_model_name: str = "host-default",
) -> RuntimeParts:
    resolved_config = config or Config()
    resolved_overrides = overrides or RuntimeDependencyOverrides()
    path_provider = resolved_overrides.path_provider or PathProvider(resolved_config)
    rulebook = resolved_overrides.rulebook or RuleBook(resolved_config, path_provider=path_provider)
    token_counter = resolved_overrides.token_counter or TokenCounter()
    llm = resolved_overrides.llm
    if llm is None and host_context is not None:
        llm = HostProvidedLLM.from_host_context(
            host_context,
            provider_name=host_llm_provider_name,
            model_name=host_llm_model_name,
            token_counter=token_counter.count,
        )
    if llm is None:
        llm = LLMClient(resolved_config)
    parts = RuntimeParts(
        config=resolved_config,
        path_provider=path_provider,
        rulebook=rulebook,
        llm=llm,
        token_counter=token_counter,
        _session_store=resolved_overrides.session_store,
        _relation_store=resolved_overrides.relation_store,
        _relation_visualization_exporter=resolved_overrides.relation_visualization_exporter,
        _reflection=resolved_overrides.reflection,
        _distiller=resolved_overrides.distiller,
        _speaker=resolved_overrides.speaker,
        _extractor=resolved_overrides.extractor,
        _chat_engine=resolved_overrides.chat_engine,
    )
    return parts
