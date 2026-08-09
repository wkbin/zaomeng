#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import html
import json
import re
import shutil
from pathlib import Path
from typing import Any

from .workflow_completion import build_capability_status, build_relation_completion_status, default_status_path, update_run_manifest, write_json


MERMAID_VERSION = "11.14.0"
MERMAID_BUNDLE_NAME = f"mermaid-{MERMAID_VERSION}.min.js"


def export_relation_graph(
    relations_file: str | Path,
    *,
    novel_id: str | None = None,
    config_path: str | None = None,
    manifest_path: str | Path | None = None,
) -> dict[str, str]:
    del config_path
    relation_path = Path(relations_file).resolve()
    relations_payload = _load_relations_payload(relation_path)
    relations = relations_payload["relations"]
    resolved_novel_id = str(novel_id or relations_payload.get("novel_id") or _novel_id_from_name(relation_path.stem)).strip()
    if not resolved_novel_id:
        raise ValueError("无法确定 novel_id")
    if not relations:
        raise ValueError("关系文件中没有可导出的 relations 数据")

    output_dir = relation_path.parent
    base_name = relation_path.stem
    mermaid_path = output_dir / f"{base_name}.mermaid.md"
    html_path = output_dir / f"{base_name}.html"
    mermaid_runtime_filename = _ensure_mermaid_runtime_asset(output_dir)

    characters_root = _infer_characters_root(relation_path, resolved_novel_id)
    node_styles = _build_visual_node_styles(characters_root, relations)
    mermaid_graph = _render_mermaid_graph(relations, node_styles=node_styles)
    html_text = _render_relation_html(
        resolved_novel_id,
        relations,
        node_styles=node_styles,
        mermaid_graph=mermaid_graph,
        mermaid_runtime_filename=mermaid_runtime_filename,
    )

    mermaid_path.write_text(mermaid_graph + "\n", encoding="utf-8")
    html_path.write_text(html_text, encoding="utf-8")

    status_payload = build_relation_completion_status(
        relation_path,
        novel_id=resolved_novel_id,
        html_path=html_path,
        mermaid_path=mermaid_path,
    )
    status_path = write_json(output_dir / f"{base_name}.status.json", status_payload)
    capability_status_path = default_status_path(
        "export_graph",
        manifest_path=manifest_path,
        output_dir=output_dir,
    )
    capability_status = build_capability_status(
        "export_graph",
        status=status_payload["status"],
        success=bool(status_payload.get("success")),
        novel_id=resolved_novel_id,
        inputs={"relations_file": str(relation_path)},
        outputs={
            "html_path": str(html_path),
            "mermaid_path": str(mermaid_path),
            "relation_status_path": str(status_path),
        },
        manifest_path=manifest_path,
        message="relation graph exported",
    )
    write_json(capability_status_path, capability_status)
    if manifest_path:
        update_run_manifest(
            manifest_path,
            stage="graph_export_completed",
            status="running",
            message="relation graph exported",
            capability="export_graph",
            capability_status=capability_status,
            artifact_updates={
                "relation_graph": {
                    "html_path": str(html_path),
                    "mermaid_path": str(mermaid_path),
                    "relation_status_path": str(status_path),
                },
                "status_files": {"export_graph": str(capability_status_path.resolve())},
            },
            status_file=capability_status_path,
            graph_status="complete",
        )

    return {
        "novel_id": resolved_novel_id,
        "html_path": str(html_path),
        "mermaid_path": str(mermaid_path),
        "status_path": str(status_path),
        "capability_status_path": str(capability_status_path),
    }


def _load_relations_payload(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    stripped = text.lstrip()
    if stripped.startswith("{"):
        payload = json.loads(text)
        return {
            "novel_id": payload.get("novel_id", ""),
            "relations": dict(payload.get("relations", {}) or {}),
        }

    novel_id = ""
    relations: dict[str, dict[str, Any]] = {}
    current_key = ""
    current_payload: dict[str, Any] | None = None

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("- novel_id:"):
            novel_id = line.split(":", 1)[1].strip()
            continue
        if line.startswith("## "):
            current_key = line[3:].strip()
            current_payload = {}
            relations[current_key] = current_payload
            continue
        if line.startswith("- ") and ":" in line and current_payload is not None:
            key, raw_value = line[2:].split(":", 1)
            current_payload[key.strip()] = _coerce_value(raw_value.strip())

    return {"novel_id": novel_id, "relations": relations}


def _coerce_value(value: str) -> Any:
    if re.fullmatch(r"-?\d+", value):
        return int(value)
    return value


def _safe_int(
    value: Any,
    default: int,
    *,
    min_value: int | None = None,
    max_value: int | None = None,
) -> int:
    if isinstance(value, bool):
        parsed = int(value)
    elif isinstance(value, int):
        parsed = value
    elif isinstance(value, float):
        parsed = int(round(value))
    elif value is None:
        parsed = default
    else:
        text = str(value).strip()
        if not text or text in {".", "..", "...", "…"}:
            parsed = default
        else:
            try:
                parsed = int(text)
            except ValueError:
                match = re.search(r"-?\d+", text)
                parsed = int(match.group(0)) if match else default
    if min_value is not None:
        parsed = max(min_value, parsed)
    if max_value is not None:
        parsed = min(max_value, parsed)
    return parsed


def _novel_id_from_name(name: str) -> str:
    text = str(name or "").strip()
    text = re.sub(r"(?i)_?relations?$", "", text).strip("_- ")
    return text


def _infer_characters_root(relation_path: Path, novel_id: str) -> Path | None:
    parts = list(relation_path.parts)
    try:
        index = parts.index("relations")
    except ValueError:
        return None
    base = Path(*parts[:index])
    candidate = base / "characters" / novel_id
    return candidate if candidate.exists() else None


def _build_relation_entries(relations: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for pair_key, payload in sorted(relations.items()):
        names = pair_key.split("_")
        if len(names) != 2:
            continue
        trust = _safe_int(payload.get("trust"), 5, min_value=0, max_value=10)
        affection = _safe_int(payload.get("affection"), 5, min_value=0, max_value=10)
        hostility = _safe_int(payload.get("hostility"), max(0, 5 - affection), min_value=0, max_value=10)
        hidden_attitude = str(payload.get("hidden_attitude", "")).strip()
        conflict_point = str(payload.get("conflict_point", "")).strip()
        interaction = str(payload.get("typical_interaction", "")).strip()
        evolution = str(payload.get("relation_change", "")).strip() or _infer_evolution(trust, affection, hostility)
        relation_type = str(payload.get("relationship_type", "")).strip() or _infer_relationship_type(
            trust,
            affection,
            hostility,
            conflict_point,
            hidden_attitude,
        )
        intensity = _intensity_score(trust, affection, hostility)
        stability_score = _stability_score(evolution, _safe_int(payload.get("confidence"), 6, min_value=0, max_value=10), hidden_attitude)
        closeness = _closeness_score(trust, affection)
        entries.append(
            {
                "key": pair_key,
                "trust": trust,
                "affection": affection,
                "hostility": hostility,
                "power_gap": _safe_int(payload.get("power_gap"), 0),
                "confidence": _safe_int(payload.get("confidence"), 6, min_value=0, max_value=10),
                "relationship_type": relation_type,
                "intensity": intensity,
                "closeness": closeness,
                "stability_score": stability_score,
                "stability_label": _stability_label(stability_score),
                "evolution": evolution,
                "conflict_point": conflict_point,
                "typical_interaction": interaction,
                "hidden_attitude": hidden_attitude,
                "evidence_summary": _evidence_summary(interaction, conflict_point, hidden_attitude),
                "edge_style": _edge_style(
                    trust,
                    hostility,
                    closeness,
                    relation_type=relation_type,
                    intensity=intensity,
                    stability_score=stability_score,
                    hidden_attitude=hidden_attitude,
                ),
            }
        )
    return entries


def _infer_relationship_type(
    trust: int,
    affection: int,
    hostility: int,
    conflict_point: str,
    hidden_attitude: str,
) -> str:
    if hostility >= 7:
        return "对立"
    if affection >= 8 and trust >= 8:
        return "深厚"
    if affection >= 7 and trust >= 6:
        return "亲近"
    if hostility >= 5 and affection >= 5:
        return "拉扯"
    if hostility >= 4 and conflict_point:
        return "竞争"
    if trust >= 7:
        return "协作"
    if hidden_attitude:
        return "复杂"
    return "中性"


def _infer_evolution(trust: int, affection: int, hostility: int) -> str:
    if hostility >= 7:
        return "恶化"
    if affection >= 7 and trust >= 7:
        return "升温"
    if hostility >= 5 and affection >= 5:
        return "反复波动"
    return "稳定"


def _intensity_score(trust: int, affection: int, hostility: int) -> int:
    return max(0, min(10, int(round((trust + affection + hostility) / 3))))


def _stability_score(evolution: str, confidence: int, hidden_attitude: str) -> int:
    score = confidence
    if evolution in {"反复波动", "恶化"}:
        score -= 3
    elif evolution == "升温":
        score -= 1
    if hidden_attitude:
        score -= 1
    return max(1, min(10, score))


def _stability_label(score: int) -> str:
    if score >= 8:
        return "稳定"
    if score >= 5:
        return "可变"
    return "脆弱"


def _evidence_summary(interaction: str, conflict_point: str, hidden_attitude: str) -> str:
    values = [value for value in (interaction, conflict_point, hidden_attitude) if value]
    return "；".join(values) or "证据摘要未提供"


def _build_visual_node_styles(
    characters_root: Path | None,
    relations: dict[str, dict[str, Any]],
) -> dict[str, dict[str, str]]:
    profile_metadata = _load_profile_visual_metadata(characters_root)
    node_names = _relation_node_names(relations)
    categories: list[str] = []
    node_styles: dict[str, dict[str, str]] = {}

    for name in node_names:
        profile = profile_metadata.get(name, {})
        category_key, legend = _node_category(profile)
        if category_key not in categories:
            categories.append(category_key)
        node_styles[name] = {
            "category_key": category_key,
            "legend": legend,
            "faction_position": str(profile.get("faction_position", "")).strip(),
            "story_role": str(profile.get("story_role", "")).strip(),
            "world_belong": str(profile.get("world_belong", "")).strip(),
        }

    palette_map: dict[str, dict[str, str]] = {}
    for index, category_key in enumerate(categories):
        palette = _category_palette(index)
        palette_map[category_key] = {
            "class_name": f"group_{index}",
            "fill": palette["fill"],
            "stroke": palette["stroke"],
            "text": palette["text"],
        }

    fallback = _default_node_style()
    for name, style in node_styles.items():
        style.update(palette_map.get(style.get("category_key", ""), fallback))

    return node_styles


def _load_profile_visual_metadata(root: Path | None) -> dict[str, dict[str, str]]:
    if root is None or not root.exists():
        return {}
    metadata: dict[str, dict[str, str]] = {}
    for persona_dir in sorted(path for path in root.iterdir() if path.is_dir()):
        merged: dict[str, str] = {}
        for filename in ("PROFILE.generated.md", "PROFILE.md"):
            profile_path = persona_dir / filename
            if not profile_path.exists():
                continue
            merged.update(_parse_profile_visual_metadata(profile_path))
        if merged:
            metadata[persona_dir.name] = merged
    return metadata


def _parse_profile_visual_metadata(path: Path) -> dict[str, str]:
    parsed: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line.startswith("- ") or ":" not in line:
            continue
        key, value = line[2:].split(":", 1)
        key = key.strip()
        value = value.strip()
        if key in {"faction_position", "story_role", "world_belong"} and value:
            parsed[key] = value
    return parsed


def _relation_node_names(relations: dict[str, dict[str, Any]]) -> list[str]:
    names = set()
    for pair_key in relations:
        parts = pair_key.split("_")
        if len(parts) == 2:
            names.update(parts)
    return sorted(names)


def _node_category(profile: dict[str, str]) -> tuple[str, str]:
    faction = str(profile.get("faction_position", "")).strip()
    if faction:
        return f"faction::{faction}", f"阵营：{faction}"
    world_belong = str(profile.get("world_belong", "")).strip()
    if world_belong:
        return f"world::{world_belong}", f"归属：{world_belong}"
    role = str(profile.get("story_role", "")).strip()
    if role:
        return f"role::{role}", f"角色：{role}"
    return "unknown", "未标注阵营/角色"


def _category_palette(index: int) -> dict[str, str]:
    palette = [
        {"fill": "#fde2e2", "stroke": "#c43d3d", "text": "#6d1616"},
        {"fill": "#ece6ff", "stroke": "#7a56d1", "text": "#46237f"},
        {"fill": "#e4f5e7", "stroke": "#2d8a4d", "text": "#18532c"},
        {"fill": "#ffe9cf", "stroke": "#c77719", "text": "#7b4312"},
        {"fill": "#e0f2fe", "stroke": "#0d8bb1", "text": "#164e63"},
        {"fill": "#fce7f3", "stroke": "#c0267c", "text": "#831843"},
    ]
    return palette[index % len(palette)]


def _default_node_style() -> dict[str, str]:
    return {
        "class_name": "group_unknown",
        "legend": "未标注阵营/角色",
        "fill": "#f3f4f6",
        "stroke": "#6b7280",
        "text": "#111827",
        "faction_position": "",
        "story_role": "",
        "world_belong": "",
    }


def _render_mermaid_graph(
    relations: dict[str, dict[str, Any]],
    *,
    node_styles: dict[str, dict[str, str]] | None = None,
) -> str:
    lines = ["graph LR"]
    node_styles = node_styles or {}
    node_classes: dict[str, dict[str, str]] = {}
    link_styles: list[str] = []
    node_ids = {name: _graph_id(name, index) for index, name in enumerate(_relation_node_names(relations))}

    for pair_key, payload in sorted(relations.items()):
        names = pair_key.split("_")
        if len(names) != 2:
            continue
        left, right = names
        trust = _safe_int(payload.get("trust"), 5, min_value=0, max_value=10)
        affection = _safe_int(payload.get("affection"), 5, min_value=0, max_value=10)
        hostility = _safe_int(payload.get("hostility"), max(0, 5 - affection), min_value=0, max_value=10)
        closeness = _closeness_score(trust, affection)
        hidden_attitude = str(payload.get("hidden_attitude", "")).strip()
        relation_type = str(payload.get("relationship_type", "")).strip() or _infer_relationship_type(
            trust,
            affection,
            hostility,
            str(payload.get("conflict_point", "")).strip(),
            hidden_attitude,
        )
        evolution = str(payload.get("relation_change", "")).strip() or _infer_evolution(trust, affection, hostility)
        intensity = _intensity_score(trust, affection, hostility)
        stability_score = _stability_score(evolution, _safe_int(payload.get("confidence"), 6, min_value=0, max_value=10), hidden_attitude)
        label = f"信{trust} 情{affection} 冲{hostility}"
        left_id = node_ids.setdefault(left, _graph_id(left, len(node_ids)))
        right_id = node_ids.setdefault(right, _graph_id(right, len(node_ids)))
        lines.append(
            f"    {left_id}[\"{_mermaid_escape(left)}\"] ---|{_mermaid_escape(label)}| {right_id}[\"{_mermaid_escape(right)}\"]"
        )
        node_classes[left] = node_styles.get(left, _default_node_style())
        node_classes[right] = node_styles.get(right, _default_node_style())
        link_styles.append(
            _edge_style(
                trust,
                hostility,
                closeness,
                relation_type=relation_type,
                intensity=intensity,
                stability_score=stability_score,
                hidden_attitude=hidden_attitude,
            )
        )

    if len(lines) == 1:
        placeholder = _default_node_style()
        lines.append("    node_empty[\"暂无关系数据\"]")
        node_classes["empty"] = {**placeholder, "class_name": "group_empty"}

    class_definitions: dict[str, dict[str, str]] = {}
    for name, style in sorted(node_classes.items()):
        class_name = style.get("class_name", "group_unknown")
        node_id = "node_empty" if name == "empty" else node_ids.get(name, _graph_id(name, len(node_ids)))
        lines.append(f"    class {node_id} {class_name}")
        class_definitions[class_name] = style
    for class_name, style in sorted(class_definitions.items()):
        lines.append(
            "    classDef "
            f"{class_name} "
            f"fill:{style.get('fill', '#f3f4f6')},"
            f"stroke:{style.get('stroke', '#6b7280')},"
            f"color:{style.get('text', '#111827')},"
            "stroke-width:2px"
        )
    for index, style in enumerate(link_styles):
        lines.append(f"    linkStyle {index} {style}")
    return "\n".join(lines)


def _render_relation_html(
    novel_id: str,
    relations: dict[str, dict[str, Any]],
    *,
    node_styles: dict[str, dict[str, str]],
    mermaid_graph: str,
    mermaid_runtime_filename: str = "",
) -> str:
    relation_entries = _build_relation_entries(relations)
    relation_types = sorted({entry["relationship_type"] for entry in relation_entries})
    table_rows: list[str] = []
    for entry in relation_entries:
        tooltip = html.escape(entry["evidence_summary"])
        tone = _type_tone(entry["relationship_type"])
        conflict_point = html.escape(entry["conflict_point"]) or '<span class="muted">-</span>'
        typical_interaction = html.escape(entry["typical_interaction"]) or '<span class="muted">-</span>'
        table_rows.append(
            "<tr "
            f"data-type=\"{html.escape(entry['relationship_type'])}\" "
            f"data-trust=\"{entry['trust']}\" "
            f"data-intensity=\"{entry['intensity']}\" "
            f"title=\"{tooltip}\">"
            f"<td><span class=\"pair-key\">{html.escape(entry['key'])}</span></td>"
            f"<td><span class=\"badge {tone}\">{html.escape(entry['relationship_type'])}</span></td>"
            f"<td>{_metric_badge(entry['trust'], 'trust')}</td>"
            f"<td>{_metric_badge(entry['affection'], 'affection')}</td>"
            f"<td>{_metric_badge(entry['hostility'], 'hostility')}</td>"
            f"<td>{entry['intensity']}</td>"
            f"<td>{html.escape(entry['stability_label'])}</td>"
            f"<td>{html.escape(entry['evolution'])}</td>"
            f"<td>{conflict_point}</td>"
            f"<td>{typical_interaction}</td>"
            "</tr>"
        )
    if not table_rows:
        table_rows.append("<tr><td colspan=\"10\"><span class=\"muted\">暂无关系数据，生成后这里会显示图谱和明细。</span></td></tr>")

    relation_cards: list[str] = []
    for entry in relation_entries:
        tooltip = html.escape(entry["evidence_summary"])
        tone = _type_tone(entry["relationship_type"])
        pair_names = str(entry["key"]).split("_", 1)
        left_name = pair_names[0]
        right_name = pair_names[1] if len(pair_names) > 1 else ""
        relation_cards.append(
            "<li class=\"relation-item\" "
            f"data-type=\"{html.escape(entry['relationship_type'])}\" "
            f"data-trust=\"{entry['trust']}\" "
            f"data-intensity=\"{entry['intensity']}\" "
            f"title=\"{tooltip}\">"
            "<div class=\"relation-card-body\">"
            "<div class=\"relation-head\">"
            "<div class=\"pair-flow\">"
            f"<span class=\"pair-person\"><span class=\"pair-avatar\">{html.escape(left_name[:1])}</span><strong>{html.escape(left_name)}</strong></span>"
            "<span class=\"pair-link\" aria-hidden=\"true\">↔</span>"
            f"<span class=\"pair-person\"><span class=\"pair-avatar\">{html.escape(right_name[:1])}</span><strong>{html.escape(right_name)}</strong></span>"
            "</div>"
            "<div class=\"relation-tags\">"
            f"<span class=\"badge {tone}\">{html.escape(entry['relationship_type'])}</span>"
            f"<span class=\"badge neutral\">{html.escape(entry['evolution'])}</span>"
            "</div>"
            "</div>"
            "<div class=\"metric-row\">"
            f"{_metric_badge(entry['trust'], 'trust')}"
            f"{_metric_badge(entry['affection'], 'affection')}"
            f"{_metric_badge(entry['hostility'], 'hostility')}"
            f"{_metric_badge(entry['intensity'], 'intensity')}"
            "</div>"
            "<div class=\"relation-facts\">"
            f"<span>稳定性 <strong>{html.escape(entry['stability_label'])}</strong></span>"
            f"<span>置信度 <strong>{entry['confidence']}</strong></span>"
            "</div>"
            f"<p class=\"relation-summary\">{html.escape(entry['evidence_summary']) or '暂无证据摘要'}</p>"
            "<details class=\"relation-more\"><summary>查看关系背景</summary>"
            f"<div class=\"relation-meta\"><span>典型互动</span>{html.escape(entry['typical_interaction']) or '未提供'}</div>"
            f"<div class=\"relation-meta\"><span>冲突焦点</span>{html.escape(entry['conflict_point']) or '未提供'}</div>"
            f"<div class=\"relation-meta\"><span>隐藏态度</span>{html.escape(entry['hidden_attitude']) or '未提供'}</div>"
            "</details>"
            "</div>"
            "</li>"
        )
    if not relation_cards:
        relation_cards.append("<li class=\"empty\">暂无关系卡片。</li>")

    escaped_mermaid = html.escape(mermaid_graph)
    embedded_graph_html = ""
    relation_count = len(relation_entries)
    def _json_for_script(value: object) -> str:
        """Serialize JSON for a raw script element without turning quotes into entities."""

        return (
            json.dumps(value, ensure_ascii=False)
            .replace("&", "\\u0026")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        )

    relation_entries_json = _json_for_script(relation_entries)
    node_styles_json = _json_for_script(node_styles)
    default_style_json = _json_for_script(_default_node_style())
    runtime_script_tag = (
        f'  <script src="{html.escape(mermaid_runtime_filename)}"></script>\n'
        if mermaid_runtime_filename
        else ""
    )
    unique_categories: list[tuple[str, dict[str, str]]] = []
    seen_categories = set()
    for style in node_styles.values():
        class_name = style.get("class_name", "group_unknown")
        if class_name in seen_categories:
            continue
        seen_categories.add(class_name)
        unique_categories.append((class_name, style))
    node_cards: list[str] = []
    for name, style in sorted(node_styles.items()):
        details = []
        faction = str(style.get("faction_position", "")).strip()
        world_belong = str(style.get("world_belong", "")).strip()
        role = str(style.get("story_role", "")).strip()
        if faction:
            details.append(f"阵营：{html.escape(faction)}")
        if world_belong:
            details.append(f"归属：{html.escape(world_belong)}")
        if role:
            details.append(f"角色：{html.escape(role)}")
        if not details:
            details.append("未标注阵营/角色")
        node_cards.append(
            "<li class=\"node-item\" "
            f"title=\"{html.escape(' / '.join(details))}\">"
            f"<span class=\"node-avatar\" style=\"background:{style.get('fill', '#f3f4f6')}; border-color:{style.get('stroke', '#6b7280')}; color:{style.get('text', '#111827')};\">{html.escape(name[:1])}</span>"
            "<div class=\"node-copy\">"
            f"<strong>{html.escape(name)}</strong>"
            f"<span>{' / '.join(details)}</span>"
            "</div>"
            "</li>"
        )
    if not node_cards:
        node_cards.append("<li class=\"node-item muted\">暂无节点元数据</li>")
    category_legend_parts: list[str] = []
    for _, style in unique_categories:
        legend = str(style.get("legend", "未标注阵营/角色")).strip() or "未标注阵营/角色"
        compact_legend = legend if len(legend) <= 26 else f"{legend[:25]}…"
        category_legend_parts.append(
            "<span class=\"legend-item\" "
            f"title=\"{html.escape(legend)}\">"
            f"<span class=\"swatch\" style=\"background:{style.get('fill', '#f3f4f6')}; border-color:{style.get('stroke', '#6b7280')};\"></span>"
            f"{html.escape(compact_legend)}"
            "</span>"
        )
    category_legend = "".join(category_legend_parts)
    category_legend_html = category_legend or '<span class="legend-item">暂无阵营/角色元数据</span>'
    conflict_count = sum(1 for entry in relation_entries if entry["hostility"] >= 6)
    high_trust_count = sum(1 for entry in relation_entries if entry["trust"] >= 7)
    visible_nodes = len(_relation_node_names(relations))
    return (
        "<!DOCTYPE html>\n"
        "<html lang=\"zh-CN\">\n"
        "<head>\n"
        "  <meta charset=\"utf-8\" />\n"
        "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n"
        "  <meta name=\"zaomeng-relation-ui-version\" content=\"2\" />\n"
        f"  <title>{html.escape(novel_id)} 人物关系图谱</title>\n"
        "  <style>\n"
        "    :root { color-scheme:light; --bg:#f4f5f8; --ink:#242535; --muted:#667085; --faint:#98a2b3; --line:rgba(36,37,53,.10); --line-strong:rgba(36,37,53,.18); --card:rgba(255,255,255,.88); --card-strong:#fff; --accent:#6f63e9; --accent-strong:#594dcc; --accent-soft:rgba(111,99,233,.11); --trust:#269b75; --affection:#d86683; --hostility:#d95d5d; --warning:#d79732; --shadow:0 18px 52px rgba(31,35,48,.08); --shadow-soft:0 8px 24px rgba(31,35,48,.055); }\n"
        "    * { box-sizing:border-box; }\n"
        "    html { min-width:0; background:var(--bg); }\n"
        "    body { min-width:0; min-height:100vh; margin:0; overflow-x:hidden; color:var(--ink); background:radial-gradient(circle at 8% -4%, rgba(111,99,233,.13), transparent 29rem), radial-gradient(circle at 96% 5%, rgba(38,155,117,.09), transparent 24rem), linear-gradient(180deg,#fafafd 0%,var(--bg) 46%,#eef0f5 100%); font-family:'PingFang SC','Microsoft YaHei','Segoe UI',system-ui,-apple-system,BlinkMacSystemFont,sans-serif; font-size:15px; line-height:1.6; text-rendering:optimizeLegibility; }\n"
        "    button, input, select { font:inherit; }\n"
        "    button:focus-visible, input:focus-visible, select:focus-visible, summary:focus-visible { outline:3px solid rgba(111,99,233,.22); outline-offset:2px; }\n"
        "    .page { width:min(100%,1440px); margin:0 auto; padding:32px 28px 48px; }\n"
        "    .hero { display:flex; align-items:flex-end; justify-content:space-between; gap:28px; padding:8px 4px 22px; }\n"
        "    .hero-copy { min-width:0; }\n"
        "    .eyebrow, .section-kicker { display:block; color:var(--accent-strong); font-size:11px; font-weight:800; letter-spacing:.14em; text-transform:uppercase; }\n"
        "    h1 { margin:4px 0 8px; font-size:clamp(30px,3vw,44px); line-height:1.15; letter-spacing:-.035em; font-weight:760; }\n"
        "    .subtitle { max-width:820px; color:var(--muted); font-size:14px; line-height:1.75; }\n"
        "    .view-status { display:inline-flex; flex:0 0 auto; align-items:center; gap:8px; min-height:36px; padding:0 13px; border:1px solid var(--line); border-radius:999px; background:rgba(255,255,255,.72); color:var(--muted); font-size:12px; font-weight:700; box-shadow:var(--shadow-soft); backdrop-filter:blur(14px); }\n"
        "    .view-status::before { content:''; width:8px; height:8px; border-radius:50%; background:var(--trust); box-shadow:0 0 0 5px rgba(38,155,117,.12); }\n"
        "    .summary { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:12px; margin-bottom:14px; }\n"
        "    .stat { display:flex; align-items:center; justify-content:space-between; gap:12px; min-height:76px; padding:15px 17px; border:1px solid var(--line); border-radius:18px; background:var(--card); box-shadow:var(--shadow-soft); backdrop-filter:blur(16px); }\n"
        "    .stat-label { color:var(--muted); font-size:12px; font-weight:700; }\n"
        "    .stat-value { color:var(--ink); font-size:27px; line-height:1; font-weight:760; letter-spacing:-.04em; }\n"
        "    .toolbar { position:sticky; top:12px; z-index:10; display:flex; align-items:flex-end; gap:14px; margin-bottom:16px; padding:14px; border:1px solid var(--line); border-radius:20px; background:rgba(255,255,255,.82); box-shadow:var(--shadow); backdrop-filter:blur(20px); }\n"
        "    .toolbar-copy { flex:0 0 150px; padding:0 2px 4px; }\n"
        "    .toolbar-copy strong { display:block; font-size:14px; }\n"
        "    .toolbar-copy span { display:block; margin-top:2px; color:var(--muted); font-size:12px; }\n"
        "    .filters { display:grid; flex:1 1 auto; grid-template-columns:minmax(150px,.8fr) minmax(180px,1fr) minmax(180px,1fr); gap:10px; min-width:0; }\n"
        "    .filter { min-width:0; }\n"
        "    .filter label { display:flex; align-items:center; justify-content:space-between; gap:8px; margin:0 2px 6px; color:var(--muted); font-size:11px; font-weight:700; }\n"
        "    .range-value { min-width:24px; color:var(--accent-strong); text-align:right; font-variant-numeric:tabular-nums; }\n"
        "    .filter input, .filter select { width:100%; min-height:40px; margin:0; border:1px solid var(--line); border-radius:12px; background:#fafafe; color:var(--ink); }\n"
        "    .filter select { padding:0 34px 0 12px; }\n"
        "    .filter input[type='range'] { height:40px; padding:0 9px; accent-color:var(--accent); }\n"
        "    .reset-button, .canvas-button { min-height:40px; border:1px solid var(--line); border-radius:12px; background:#fff; color:var(--muted); font-weight:750; cursor:pointer; transition:transform .18s ease,border-color .18s ease,color .18s ease,box-shadow .18s ease; }\n"
        "    .reset-button { flex:0 0 auto; padding:0 15px; }\n"
        "    .reset-button:hover, .canvas-button:hover { transform:translateY(-1px); border-color:var(--line-strong); color:var(--accent-strong); box-shadow:var(--shadow-soft); }\n"
        "    .grid { display:grid; grid-template-columns:minmax(0,1.65fr) minmax(280px,.62fr); gap:16px; align-items:start; }\n"
        "    .stack { display:grid; gap:16px; margin-top:16px; }\n"
        "    .card { min-width:0; border:1px solid var(--line); border-radius:22px; background:var(--card); box-shadow:var(--shadow); backdrop-filter:blur(16px); }\n"
        "    .card-head, .section-head { display:flex; align-items:center; justify-content:space-between; gap:16px; }\n"
        "    .card-head { padding:18px 20px 12px; }\n"
        "    .card h2, .section-head h2 { margin:2px 0 0; font-size:18px; line-height:1.35; letter-spacing:-.015em; }\n"
        "    .canvas-controls { display:flex; align-items:center; gap:6px; }\n"
        "    .canvas-button { min-width:40px; padding:0 11px; font-size:13px; }\n"
        "    .legend { display:flex; gap:8px; margin:0; padding:0 20px 12px; overflow-x:auto; scrollbar-width:none; }\n"
        "    .legend::-webkit-scrollbar, .graph-key::-webkit-scrollbar { display:none; }\n"
        "    .legend-item { display:inline-flex; flex:0 0 auto; align-items:center; gap:7px; max-width:280px; padding:6px 10px; overflow:hidden; border:1px solid var(--line); border-radius:999px; background:rgba(255,255,255,.78); color:var(--muted); font-size:12px; text-overflow:ellipsis; white-space:nowrap; }\n"
        "    .swatch { width:9px; height:9px; flex:0 0 auto; border:2px solid transparent; border-radius:999px; }\n"
        "    .graph-shell { display:grid; min-height:420px; margin:0 14px 14px; padding:18px; overflow:auto; border:1px solid var(--line); border-radius:18px; background-color:#fbfbfe; background-image:radial-gradient(rgba(111,99,233,.18) .8px,transparent .8px); background-size:20px 20px; cursor:grab; overscroll-behavior:contain; }\n"
        "    .graph-shell.is-dragging { cursor:grabbing; user-select:none; }\n"
        "    .graph-image { display:block; width:100%; height:auto; }\n"
        "    .mermaid { display:grid; width:100%; min-width:0; place-items:center; text-align:center; }\n"
        "    #graph-view svg { display:block; width:100%; max-width:none!important; height:auto; min-height:260px; overflow:visible; transition:width .2s ease; }\n"
        "    #graph-view .node rect, #graph-view .node polygon, #graph-view .node path { filter:drop-shadow(0 7px 11px rgba(31,35,48,.10)); stroke-width:1.6px!important; }\n"
        "    #graph-view .node rect { rx:14px; ry:14px; }\n"
        "    #graph-view .nodeLabel, #graph-view .edgeLabel { font-family:'PingFang SC','Microsoft YaHei','Segoe UI',system-ui,sans-serif!important; font-weight:700; }\n"
        "    #graph-view .edgeLabel p { padding:3px 7px; border:1px solid var(--line); border-radius:999px; background:rgba(255,255,255,.94)!important; color:var(--muted)!important; box-shadow:0 4px 10px rgba(31,35,48,.06); }\n"
        "    .graph-key { display:flex; align-items:center; gap:14px; padding:0 20px 17px; color:var(--muted); font-size:12px; }\n"
        "    .graph-key span { display:inline-flex; align-items:center; gap:6px; }\n"
        "    .edge-dot { width:18px; height:3px; border-radius:999px; background:var(--faint); }\n"
        "    .edge-dot.trust { background:var(--trust); } .edge-dot.mixed { background:var(--warning); } .edge-dot.danger { background:var(--hostility); }\n"
        "    .graph-details { margin:0 20px 18px; }\n"
        "    details summary { cursor:pointer; color:var(--muted); font-size:12px; font-weight:750; list-style:none; }\n"
        "    details summary::-webkit-details-marker { display:none; }\n"
        "    details summary::before { content:'＋'; display:inline-block; width:18px; color:var(--accent-strong); }\n"
        "    details[open] > summary::before { content:'−'; }\n"
        "    .edge-rule { display:grid; gap:8px; margin:12px 0; padding:13px 14px; border-radius:14px; background:var(--accent-soft); }\n"
        "    .edge-rule strong { display:inline-block; min-width:74px; color:var(--ink); font-size:12px; }\n"
        "    pre { max-height:280px; margin:12px 0 0; padding:14px; overflow:auto; border:1px solid var(--line); border-radius:14px; background:#232534; color:#e7e9f4; font:12px/1.65 ui-monospace,SFMono-Regular,Consolas,monospace; white-space:pre-wrap; }\n"
        "    .side-card { position:sticky; top:108px; max-height:calc(100vh - 128px); overflow:hidden; }\n"
        "    .side-card .card-head { border-bottom:1px solid var(--line); }\n"
        "    .node-list, .relation-list { list-style:none; margin:0; padding:0; display:grid; gap:10px; }\n"
        "    .node-list { max-height:calc(100vh - 260px); padding:12px; overflow:auto; }\n"
        "    .node-item { display:flex; gap:11px; align-items:flex-start; padding:12px; border:1px solid transparent; border-radius:15px; background:rgba(248,249,252,.82); transition:border-color .18s ease,background .18s ease,transform .18s ease; }\n"
        "    .node-item:hover { transform:translateY(-1px); border-color:var(--line); background:#fff; }\n"
        "    .node-avatar { display:grid; width:38px; height:38px; flex:0 0 auto; place-items:center; border:1.5px solid; border-radius:13px; font-size:14px; font-weight:800; }\n"
        "    .node-copy { min-width:0; }\n"
        "    .node-item strong { display:block; margin:0 0 3px; font-size:13px; }\n"
        "    .node-item .node-copy span { display:-webkit-box; overflow:hidden; color:var(--muted); font-size:11px; line-height:1.55; -webkit-box-orient:vertical; -webkit-line-clamp:3; }\n"
        "    .note { margin:0; padding:0 18px 17px; color:var(--faint); font-size:11px; }\n"
        "    .relation-section { padding:20px; }\n"
        "    .section-head { margin-bottom:14px; }\n"
        "    .section-copy { color:var(--muted); font-size:12px; }\n"
        "    .relation-list { grid-template-columns:repeat(auto-fit,minmax(330px,1fr)); }\n"
        "    .relation-item { min-width:0; padding:16px; border:1px solid var(--line); border-radius:18px; background:rgba(255,255,255,.72); transition:transform .18s ease,border-color .18s ease,box-shadow .18s ease; }\n"
        "    .relation-item:hover { transform:translateY(-2px); border-color:var(--line-strong); box-shadow:var(--shadow-soft); }\n"
        "    .relation-card-body { min-width:0; }\n"
        "    .relation-head { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }\n"
        "    .pair-flow { display:flex; min-width:0; align-items:center; gap:8px; }\n"
        "    .pair-person { display:inline-flex; min-width:0; align-items:center; gap:7px; }\n"
        "    .pair-person strong { overflow:hidden; font-size:13px; text-overflow:ellipsis; white-space:nowrap; }\n"
        "    .pair-avatar { display:grid; width:29px; height:29px; flex:0 0 auto; place-items:center; border-radius:10px; background:var(--accent-soft); color:var(--accent-strong); font-size:12px; font-weight:800; }\n"
        "    .pair-link { color:var(--faint); font-size:14px; }\n"
        "    .relation-tags { display:flex; flex:0 0 auto; gap:5px; }\n"
        "    .badge { display:inline-flex; align-items:center; min-height:25px; padding:0 8px; border:1px solid transparent; border-radius:999px; font-size:10px; font-weight:750; }\n"
        "    .badge.warm { border-color:rgba(38,155,117,.18); background:rgba(38,155,117,.11); color:#177656; }\n"
        "    .badge.mixed { border-color:rgba(215,151,50,.22); background:rgba(215,151,50,.12); color:#966115; }\n"
        "    .badge.danger { border-color:rgba(217,93,93,.20); background:rgba(217,93,93,.11); color:#ac3e3e; }\n"
        "    .badge.neutral { border-color:var(--line); background:#f4f5f8; color:var(--muted); }\n"
        "    .metric-row { display:flex; gap:7px; flex-wrap:wrap; margin:13px 0 10px; }\n"
        "    .metric { display:inline-flex; align-items:center; gap:5px; min-width:0; padding:5px 8px; border-radius:9px; background:#f5f6f9; color:var(--ink); font-size:11px; font-weight:800; font-variant-numeric:tabular-nums; }\n"
        "    .metric::before { color:var(--muted); font-size:9px; font-weight:700; }\n"
        "    .metric.trust::before { content:'信任'; color:var(--trust); } .metric.affection::before { content:'好感'; color:var(--affection); } .metric.hostility::before { content:'冲突'; color:var(--hostility); } .metric.intensity::before { content:'强度'; color:var(--accent); }\n"
        "    .relation-facts { display:flex; gap:12px; color:var(--muted); font-size:11px; }\n"
        "    .relation-facts strong { margin-left:3px; color:var(--ink); }\n"
        "    .relation-summary { display:-webkit-box; margin:10px 0 0; overflow:hidden; color:var(--muted); font-size:12px; line-height:1.65; -webkit-box-orient:vertical; -webkit-line-clamp:2; }\n"
        "    .relation-more { margin-top:10px; }\n"
        "    .relation-meta { display:grid; grid-template-columns:72px minmax(0,1fr); gap:8px; margin-top:7px; color:var(--muted); font-size:11px; }\n"
        "    .relation-meta span { color:var(--faint); }\n"
        "    .data-details { margin-top:14px; border-top:1px solid var(--line); padding-top:14px; }\n"
        "    .data-details > summary { display:flex; align-items:center; justify-content:space-between; min-height:40px; padding:0 4px; color:var(--ink); font-size:13px; }\n"
        "    .summary-hint { color:var(--faint); font-size:11px; font-weight:500; }\n"
        "    .table-shell { width:100%; margin-top:10px; overflow-x:auto; border:1px solid var(--line); border-radius:14px; background:#fff; overscroll-behavior-x:contain; }\n"
        "    table { width:100%; min-width:980px; border-spacing:0; border-collapse:separate; background:white; font-size:12px; }\n"
        "    th, td { padding:11px 12px; border:0; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; }\n"
        "    th { position:sticky; top:0; z-index:1; background:#f7f8fb; color:var(--muted); font-size:10px; letter-spacing:.04em; }\n"
        "    tbody tr:last-child td { border-bottom:0; }\n"
        "    tbody tr:hover td { background:#fafafe; }\n"
        "    .pair-key { font-weight:750; color:var(--ink); }\n"
        "    .muted { color:var(--muted); }\n"
        "    .empty { padding:18px; border:1px dashed var(--line-strong); border-radius:15px; background:#fafafe; color:var(--muted); text-align:center; }\n"
        "    @media (max-width:1050px) { .toolbar { align-items:stretch; flex-wrap:wrap; } .toolbar-copy { flex:1 0 100%; } .filters { flex:1 1 calc(100% - 90px); } .grid { grid-template-columns:minmax(0,1fr) minmax(250px,.46fr); } }\n"
        "    @media (max-width:820px) { .page { padding:22px 14px 34px; } .hero { align-items:flex-start; flex-direction:column; gap:12px; } .summary { grid-template-columns:repeat(2,minmax(0,1fr)); } .toolbar { position:static; } .filters { flex-basis:100%; grid-template-columns:1fr; } .reset-button { width:100%; } .grid { grid-template-columns:1fr; } .side-card { position:static; max-height:none; } .node-list { max-height:none; grid-template-columns:repeat(2,minmax(0,1fr)); } .graph-shell { min-height:360px; } .relation-list { grid-template-columns:1fr; } }\n"
        "    @media (max-width:560px) { body { font-size:14px; } .page { padding:16px 10px 28px; } h1 { font-size:29px; } .summary { gap:8px; } .stat { min-height:66px; padding:12px; border-radius:15px; } .stat-value { font-size:23px; } .card { border-radius:18px; } .card-head { padding:15px 14px 10px; } .legend { padding:0 14px 10px; } .graph-shell { min-height:330px; margin:0 8px 10px; padding:10px; } .graph-key { gap:9px; padding:0 14px 14px; overflow-x:auto; white-space:nowrap; } .graph-details { margin:0 14px 14px; } .node-list { grid-template-columns:1fr; } .relation-section { padding:15px 12px; } .relation-head { align-items:flex-start; flex-direction:column; } .relation-tags { align-self:flex-start; } .pair-person strong { max-width:90px; } .summary-hint { display:none; } }\n"
        "    @media (prefers-reduced-motion:reduce) { *, *::before, *::after { scroll-behavior:auto!important; transition:none!important; } }\n"
        "  </style>\n"
        "  <script type=\"application/json\" id=\"relation-data\">"
        + relation_entries_json
        + "</script>\n"
        "  <script type=\"application/json\" id=\"node-style-data\">"
        + node_styles_json
        + "</script>\n"
        "  <script type=\"application/json\" id=\"default-style-data\">"
        + default_style_json
        + "</script>\n"
        + runtime_script_tag
        + "  <script>\n"
        "    const relationEntries = JSON.parse(document.getElementById('relation-data').textContent);\n"
        "    const nodeStyles = JSON.parse(document.getElementById('node-style-data').textContent);\n"
        "    const defaultStyle = JSON.parse(document.getElementById('default-style-data').textContent);\n"
        "    let graphZoom = 1;\n"
        "    let graphRenderVersion = 0;\n"
        "    let compactGraphLayout = window.matchMedia('(max-width: 720px)').matches;\n"
        "    let filterTimer = 0;\n"
        "    const escapeLabel = (value) => String(value).replace(/\\\\/g, '\\\\\\\\').replace(/\"/g, '\\\\\"');\n"
        "    const buildNodeIds = (entries) => {\n"
        "      const names = Array.from(new Set(entries.flatMap((entry) => entry.key.split('_')))).sort((a, b) => a.localeCompare(b, 'zh-CN'));\n"
        "      return new Map(names.map((name, index) => [name, `n${index}`]));\n"
        "    };\n"
        "    const edgeStyle = (entry) => entry.edge_style || 'stroke:#8a5a2b,stroke-width:2px';\n"
        "    const buildMermaid = (entries) => {\n"
        "      const lines = [`graph ${compactGraphLayout ? 'TD' : 'LR'}`];\n"
        "      const nodeIds = buildNodeIds(entries);\n"
        "      const nodeClasses = new Map();\n"
        "      const classDefs = new Map();\n"
        "      if (!entries.length) {\n"
        "        lines.push('    node_empty[\"暂无符合筛选条件的关系\"]');\n"
        "        nodeClasses.set('empty', { ...defaultStyle, class_name: 'group_empty' });\n"
        "        classDefs.set('group_empty', { ...defaultStyle, class_name: 'group_empty' });\n"
        "      }\n"
        "      entries.forEach((entry) => {\n"
        "        const [left, right] = entry.key.split('_');\n"
        "        const leftId = nodeIds.get(left);\n"
        "        const rightId = nodeIds.get(right);\n"
        "        lines.push(`    ${leftId}[\"${escapeLabel(left)}\"] ---|${escapeLabel(`信${entry.trust} 情${entry.affection} 冲${entry.hostility}`)}| ${rightId}[\"${escapeLabel(right)}\"]`);\n"
        "        [left, right].forEach((name) => {\n"
        "          const style = nodeStyles[name] || defaultStyle;\n"
        "          nodeClasses.set(name, style);\n"
        "          classDefs.set(style.class_name, style);\n"
        "        });\n"
        "      });\n"
        "      Array.from(nodeClasses.entries()).sort(([a], [b]) => a.localeCompare(b, 'zh-CN')).forEach(([name, style]) => {\n"
        "        const nodeId = name === 'empty' ? 'node_empty' : nodeIds.get(name);\n"
        "        lines.push(`    class ${nodeId} ${style.class_name}`);\n"
        "      });\n"
        "      Array.from(classDefs.entries()).sort(([a], [b]) => a.localeCompare(b, 'zh-CN')).forEach(([className, style]) => {\n"
        "        lines.push(`    classDef ${className} fill:${style.fill},stroke:${style.stroke},color:${style.text},stroke-width:2px`);\n"
        "      });\n"
        "      entries.forEach((entry, index) => lines.push(`    linkStyle ${index} ${edgeStyle(entry)}`));\n"
        "      return lines.join('\\n');\n"
        "    };\n"
        "    const applyGraphZoom = () => {\n"
        "      const svg = document.querySelector('#graph-view svg');\n"
        "      if (!svg) return;\n"
        "      svg.style.width = String(Math.round(graphZoom * 100)) + '%';\n"
        "    };\n"
        "    const setGraphZoom = (nextZoom) => {\n"
        "      graphZoom = Math.max(.7, Math.min(2, nextZoom));\n"
        "      applyGraphZoom();\n"
        "    };\n"
        "    const fitGraph = () => {\n"
        "      graphZoom = 1;\n"
        "      applyGraphZoom();\n"
        "      const shell = document.querySelector('.graph-shell');\n"
        "      shell.scrollTo({ left: 0, top: 0, behavior: 'smooth' });\n"
        "    };\n"
        "    const renderGraph = async (entries) => {\n"
        "      const renderVersion = ++graphRenderVersion;\n"
        "      const definition = buildMermaid(entries);\n"
        "      document.getElementById('graph-source').textContent = definition;\n"
        "      const target = document.getElementById('graph-view');\n"
        "      target.setAttribute('aria-busy', 'true');\n"
        "      if (!window.mermaid) {\n"
        "        if (!target.querySelector('svg')) {\n"
        "          target.innerHTML = '<div class=\"empty\">Mermaid 脚本未加载成功。本地 file 页面可能被浏览器限制了外链脚本，请改用本地静态服务打开，或直接查看下方 Mermaid 源码。</div>';\n"
        "        }\n"
        "        target.setAttribute('aria-busy', 'false');\n"
        "        return;\n"
        "      }\n"
        "      try {\n"
        "        window.mermaid.initialize({ startOnLoad: false, theme: 'base', securityLevel: 'strict', flowchart: { htmlLabels: false, curve: 'basis', nodeSpacing: 54, rankSpacing: 76, padding: 18 }, themeVariables: { background: 'transparent', primaryColor: '#ffffff', primaryTextColor: '#242535', lineColor: '#7d8496', primaryBorderColor: '#6f63e9', clusterBorder: '#d9dce6', edgeLabelBackground: '#ffffff', fontFamily: 'PingFang SC, Microsoft YaHei, Segoe UI, system-ui, sans-serif' } });\n"
        "        const rendered = await window.mermaid.render(`graph-${renderVersion}`, definition);\n"
        "        if (renderVersion !== graphRenderVersion) return;\n"
        "        target.innerHTML = rendered.svg;\n"
        "        const svg = target.querySelector('svg');\n"
        "        if (svg) {\n"
        "          svg.setAttribute('role', 'img');\n"
        "          svg.setAttribute('aria-label', '人物关系网络，共 ' + String(entries.length) + ' 条可见关系');\n"
        "        }\n"
        "        applyGraphZoom();\n"
        "      } catch (error) {\n"
        "        if (renderVersion !== graphRenderVersion) return;\n"
        "        console.error('Mermaid render failed:', error);\n"
        "        target.innerHTML = '<div class=\"empty\">关系网络图渲染失败，请展开下方 Mermaid 源码检查语法。</div>';\n"
        "      } finally {\n"
        "        if (renderVersion === graphRenderVersion) target.setAttribute('aria-busy', 'false');\n"
        "      }\n"
        "    };\n"
        "    const applyFilters = async () => {\n"
        "      const type = document.getElementById('filter-type').value;\n"
        "      const minTrust = Number(document.getElementById('filter-trust').value || 0);\n"
        "      const minIntensity = Number(document.getElementById('filter-intensity').value || 0);\n"
        "      const filtered = relationEntries.filter((entry) => (!type || entry.relationship_type === type) && entry.trust >= minTrust && entry.intensity >= minIntensity);\n"
        "      document.getElementById('filter-trust-value').textContent = String(minTrust);\n"
        "      document.getElementById('filter-intensity-value').textContent = String(minIntensity);\n"
        "      document.getElementById('visible-relation-count').textContent = '显示 ' + String(filtered.length) + ' / ' + String(relationEntries.length) + ' 条';\n"
        "      document.querySelectorAll('[data-type][data-trust][data-intensity]').forEach((element) => {\n"
        "        const visible = (!type || element.dataset.type === type) && Number(element.dataset.trust) >= minTrust && Number(element.dataset.intensity) >= minIntensity;\n"
        "        element.style.display = visible ? '' : 'none';\n"
        "      });\n"
        "      await renderGraph(filtered);\n"
        "    };\n"
        "    const scheduleFilters = () => {\n"
        "      window.clearTimeout(filterTimer);\n"
        "      filterTimer = window.setTimeout(() => { void applyFilters(); }, 100);\n"
        "    };\n"
        "    window.addEventListener('DOMContentLoaded', async () => {\n"
        "      ['filter-type', 'filter-trust', 'filter-intensity'].forEach((id) => document.getElementById(id).addEventListener('input', scheduleFilters));\n"
        "      document.getElementById('filter-reset').addEventListener('click', () => {\n"
        "        document.getElementById('filter-type').value = '';\n"
        "        document.getElementById('filter-trust').value = '0';\n"
        "        document.getElementById('filter-intensity').value = '0';\n"
        "        void applyFilters();\n"
        "      });\n"
        "      document.getElementById('graph-zoom-out').addEventListener('click', () => setGraphZoom(graphZoom - .15));\n"
        "      document.getElementById('graph-zoom-in').addEventListener('click', () => setGraphZoom(graphZoom + .15));\n"
        "      document.getElementById('graph-fit').addEventListener('click', fitGraph);\n"
        "      const graphShell = document.querySelector('.graph-shell');\n"
        "      let dragState = null;\n"
        "      graphShell.addEventListener('pointerdown', (event) => {\n"
        "        if (event.pointerType !== 'mouse' || event.button !== 0) return;\n"
        "        dragState = { x: event.clientX, y: event.clientY, left: graphShell.scrollLeft, top: graphShell.scrollTop };\n"
        "        graphShell.classList.add('is-dragging');\n"
        "        graphShell.setPointerCapture(event.pointerId);\n"
        "      });\n"
        "      graphShell.addEventListener('pointermove', (event) => {\n"
        "        if (!dragState) return;\n"
        "        graphShell.scrollLeft = dragState.left - (event.clientX - dragState.x);\n"
        "        graphShell.scrollTop = dragState.top - (event.clientY - dragState.y);\n"
        "      });\n"
        "      const stopDragging = () => { dragState = null; graphShell.classList.remove('is-dragging'); };\n"
        "      graphShell.addEventListener('pointerup', stopDragging);\n"
        "      graphShell.addEventListener('pointercancel', stopDragging);\n"
        "      window.addEventListener('resize', () => {\n"
        "        const nextCompactLayout = window.matchMedia('(max-width: 720px)').matches;\n"
        "        if (nextCompactLayout === compactGraphLayout) return;\n"
        "        compactGraphLayout = nextCompactLayout;\n"
        "        scheduleFilters();\n"
        "      });\n"
        "      await applyFilters();\n"
        "    });\n"
        "  </script>\n"
        "</head>\n"
        "<body>\n"
        "  <div class=\"page\">\n"
        "    <header class=\"hero\">\n"
        "      <div class=\"hero-copy\">\n"
        "        <span class=\"eyebrow\">RELATION EXPLORER</span>\n"
        f"        <h1>《{html.escape(novel_id)}》人物关系</h1>\n"
        f"        <div class=\"subtitle\">从角色网络进入故事：用关系类型、信任与强度筛选关键连接，线条颜色表示关系倾向，粗细表示关系强弱。</div>\n"
        "      </div>\n"
        "      <span class=\"view-status\">动态关系视图</span>\n"
        "    </header>\n"
        "    <div class=\"summary\">\n"
        f"      <div class=\"stat\"><span class=\"stat-label\">全部关系</span><span class=\"stat-value\">{relation_count}</span></div>\n"
        f"      <div class=\"stat\"><span class=\"stat-label\">高信任</span><span class=\"stat-value\">{high_trust_count}</span></div>\n"
        f"      <div class=\"stat\"><span class=\"stat-label\">高冲突</span><span class=\"stat-value\">{conflict_count}</span></div>\n"
        f"      <div class=\"stat\"><span class=\"stat-label\">角色节点</span><span class=\"stat-value\">{visible_nodes}</span></div>\n"
        "    </div>\n"
        "    <section class=\"toolbar\" aria-label=\"关系筛选\">\n"
        "      <div class=\"toolbar-copy\"><strong>探索关系</strong><span id=\"visible-relation-count\" aria-live=\"polite\">显示全部关系</span></div>\n"
        "      <div class=\"filters\">\n"
        "      <div class=\"filter\"><label for=\"filter-type\"><span>关系类型</span></label><select id=\"filter-type\"><option value=\"\">全部类型</option>"
        + "".join(f"<option value=\"{html.escape(item)}\">{html.escape(item)}</option>" for item in relation_types)
        + "</select></div>\n"
        "      <div class=\"filter\"><label for=\"filter-trust\"><span>最低信任</span><output id=\"filter-trust-value\" class=\"range-value\">0</output></label><input id=\"filter-trust\" type=\"range\" min=\"0\" max=\"10\" value=\"0\" /></div>\n"
        "      <div class=\"filter\"><label for=\"filter-intensity\"><span>最低强度</span><output id=\"filter-intensity-value\" class=\"range-value\">0</output></label><input id=\"filter-intensity\" type=\"range\" min=\"0\" max=\"10\" value=\"0\" /></div>\n"
        "      </div>\n"
        "      <button id=\"filter-reset\" class=\"reset-button\" type=\"button\">重置筛选</button>\n"
        "    </section>\n"
        "    <div class=\"grid\">\n"
        "      <section class=\"card graph-card\">\n"
        "        <div class=\"card-head\"><div><span class=\"section-kicker\">NETWORK</span><h2>关系网络</h2></div>\n"
        "          <div class=\"canvas-controls\" aria-label=\"画布控制\">\n"
        "            <button id=\"graph-zoom-out\" class=\"canvas-button\" type=\"button\" aria-label=\"缩小图谱\">−</button>\n"
        "            <button id=\"graph-zoom-in\" class=\"canvas-button\" type=\"button\" aria-label=\"放大图谱\">＋</button>\n"
        "            <button id=\"graph-fit\" class=\"canvas-button\" type=\"button\">适应</button>\n"
        "          </div>\n"
        "        </div>\n"
        f"        <div class=\"legend\">{category_legend_html}</div>\n"
        "        <div class=\"graph-shell\">\n"
        f"          <div id=\"graph-view\" class=\"mermaid\">{embedded_graph_html}</div>\n"
        "        </div>\n"
        "        <div class=\"graph-key\" aria-label=\"连线图例\">\n"
        "          <span><i class=\"edge-dot trust\"></i>信任</span><span><i class=\"edge-dot mixed\"></i>拉扯</span><span><i class=\"edge-dot danger\"></i>冲突</span><span><i class=\"edge-dot\"></i>中性</span>\n"
        "        </div>\n"
        "        <div class=\"graph-details\">\n"
        "          <details><summary>图谱阅读说明</summary><div class=\"edge-rule\">\n"
        "            <div><strong>颜色</strong><span class=\"muted\">绿色偏信任，橙色表示拉扯或竞争，红色偏冲突。</span></div>\n"
        "            <div><strong>样式</strong><span class=\"muted\">线越粗关系越强；虚线表示关系脆弱，或表面与真实态度存在落差。</span></div>\n"
        "          </div></details>\n"
        "          <details><summary>技术信息 · Mermaid 源码</summary><pre id=\"graph-source\">"
        f"{escaped_mermaid}</pre></details>\n"
        "        </div>\n"
        "      </section>\n"
        "      <aside class=\"card side-card\">\n"
        "        <div class=\"card-head\"><div><span class=\"section-kicker\">CHARACTERS</span><h2>角色索引</h2></div></div>\n"
        "        <ul class=\"node-list\">\n"
        f"          {''.join(node_cards)}\n"
        "        </ul>\n"
        "        <div class=\"note\">角色颜色来自人物画像中的阵营、归属与故事定位。</div>\n"
        "      </aside>\n"
        "    </div>\n"
        "    <div class=\"stack\">\n"
        "      <section class=\"card relation-section\">\n"
        "        <div class=\"section-head\"><div><span class=\"section-kicker\">RELATIONSHIPS</span><h2>关系卡片</h2></div><span class=\"section-copy\">从卡片快速理解关系，再按需展开详细数据</span></div>\n"
        "        <ul class=\"relation-list\">\n"
        f"          {''.join(relation_cards)}\n"
        "        </ul>\n"
        "        <details class=\"data-details\">\n"
        "          <summary><span>关系明细表</span><span class=\"summary-hint\">用于查看全部原始维度</span></summary>\n"
        "          <div class=\"table-shell\"><table>\n"
        "        <thead><tr><th>关系对</th><th>关系类型</th><th>信任</th><th>好感</th><th>冲突</th><th>强度</th><th>稳定性</th><th>演变</th><th>冲突焦点</th><th>典型互动</th></tr></thead>\n"
        f"        <tbody>{''.join(table_rows)}</tbody>\n"
        "          </table></div>\n"
        "        </details>\n"
        "      </section>\n"
        "    </div>\n"
        "  </div>\n"
        "</body>\n"
        "</html>\n"
    )


def _closeness_score(trust: int, affection: int) -> int:
    return max(1, min(5, int(round((trust + affection) / 4))))


def _edge_style(
    trust: int,
    hostility: int,
    closeness: int,
    *,
    relation_type: str,
    intensity: int,
    stability_score: int,
    hidden_attitude: str,
) -> str:
    if hostility >= max(6, trust):
        color = "#c53b30"
    elif trust >= 8:
        color = "#1f8f63"
    elif relation_type in {"拉扯", "竞争"}:
        color = "#d18a1d"
    else:
        color = "#8a5a2b"
    width = max(2, min(7, max(closeness + 1, 1 + round(intensity / 2))))
    parts = [f"stroke:{color}", f"stroke-width:{width}px"]
    if hidden_attitude or stability_score <= 4:
        parts.append("stroke-dasharray:8 4")
    return ",".join(parts)


def _skill_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _vendored_mermaid_asset_path() -> Path:
    return _skill_root() / "assets" / "vendor" / MERMAID_BUNDLE_NAME


def _load_vendored_mermaid_runtime() -> str:
    asset_path = _vendored_mermaid_asset_path()
    if not asset_path.exists():
        return ""
    return asset_path.read_text(encoding="utf-8")


def _ensure_mermaid_runtime_asset(output_dir: Path) -> str:
    asset_path = _vendored_mermaid_asset_path()
    if not asset_path.exists():
        return ""
    target_path = output_dir / asset_path.name
    if not target_path.exists():
        shutil.copy2(asset_path, target_path)
    return target_path.name


def _type_tone(relation_type: str) -> str:
    if relation_type in {"深厚", "亲近", "协作"}:
        return "warm"
    if relation_type in {"对立", "竞争"}:
        return "danger"
    if relation_type == "拉扯":
        return "mixed"
    return "neutral"


def _metric_badge(value: int, kind: str) -> str:
    return f"<span class=\"metric {kind}\">{value}</span>"


def _graph_id(name: str, index: int) -> str:
    return f"n{index}"


def _mermaid_escape(value: Any) -> str:
    return str(value).replace("\\", "\\\\").replace('"', '\\"')
