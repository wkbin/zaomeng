#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Any, Dict

from src.core.contracts import RelationVisualizationExporter
from src.utils.file_utils import save_markdown_data


MERMAID_VERSION = "11.14.0"
MERMAID_BUNDLE_NAME = f"mermaid-{MERMAID_VERSION}.min.js"


class MermaidRelationVisualizationExporter(RelationVisualizationExporter):
    """Export relation visuals through the existing extractor rendering helpers."""

    def __init__(self, renderer: Any):
        self.renderer = renderer

    def export_visualizations(self, relations: Dict[str, Dict[str, Any]], novel_id: str) -> None:
        node_styles = self.renderer._build_visual_node_styles(novel_id, relations)
        mermaid_graph = self.renderer._render_mermaid_graph(relations, node_styles=node_styles)
        mermaid_path = self.renderer.path_provider.visualization_file(novel_id, ".mermaid.md")
        save_markdown_data(
            mermaid_path,
            {
                "novel_id": novel_id,
                "relation_count": len(relations),
                "diagram": mermaid_graph,
            },
            title="RELATION_GRAPH_VISUAL",
            summary=[
                f"- novel_id: {novel_id}",
                f"- relation_count: {len(relations)}",
            ],
        )

        html_path = self.renderer.path_provider.visualization_file(novel_id, ".html")
        mermaid_runtime_filename = self._ensure_mermaid_runtime_asset(html_path.parent)
        html_path.write_text(
            self.renderer._render_relation_html(
                novel_id,
                relations,
                node_styles=node_styles,
                mermaid_graph=mermaid_graph,
                mermaid_runtime_filename=mermaid_runtime_filename,
            ),
            encoding="utf-8",
        )

    def _ensure_mermaid_runtime_asset(self, output_dir: Path) -> str:
        asset_path = Path(__file__).resolve().parents[2] / "zaomeng-skill" / "assets" / "vendor" / MERMAID_BUNDLE_NAME
        if not asset_path.exists():
            return ""
        target_path = output_dir / asset_path.name
        if not target_path.exists():
            shutil.copy2(asset_path, target_path)
        return target_path.name
