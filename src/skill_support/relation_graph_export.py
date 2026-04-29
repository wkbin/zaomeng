#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

from pathlib import Path

from src.core.config import Config
from src.core.runtime_factory import build_runtime_parts
from src.utils.file_utils import load_markdown_data, novel_id_from_input


def export_relation_graph(
    relations_file: str | Path,
    *,
    novel_id: str | None = None,
    config_path: str | None = None,
) -> dict[str, str]:
    relation_path = Path(relations_file)
    payload = load_markdown_data(relation_path, default={}) or {}
    resolved_novel_id = str(novel_id or payload.get("novel_id") or novel_id_from_input(relation_path.stem)).strip()
    relations = dict(payload.get("relations", {}) or {})
    if not resolved_novel_id:
        raise ValueError("无法确定 novel_id")
    if not relations:
        raise ValueError("关系文件中没有可导出的 relations 数据")

    config = Config(config_path) if config_path else Config()
    parts = build_runtime_parts(config)
    parts.relation_visualization_exporter.export_visualizations(relations, resolved_novel_id)

    html_path = parts.path_provider.visualization_file(resolved_novel_id, ".html")
    mermaid_path = parts.path_provider.visualization_file(resolved_novel_id, ".mermaid.md")
    return {
        "novel_id": resolved_novel_id,
        "html_path": str(html_path),
        "mermaid_path": str(mermaid_path),
    }
