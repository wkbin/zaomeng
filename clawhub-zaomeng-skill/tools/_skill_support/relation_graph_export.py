#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import html
import json
import re
from pathlib import Path
from typing import Any


def export_relation_graph(
    relations_file: str | Path,
    *,
    novel_id: str | None = None,
    config_path: str | None = None,
) -> dict[str, str]:
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

    characters_root = _infer_characters_root(relation_path, resolved_novel_id)
    node_styles = _build_visual_node_styles(characters_root, relations)
    mermaid_graph = _render_mermaid_graph(relations, node_styles=node_styles)
    html_text = _render_relation_html(resolved_novel_id, relations, node_styles=node_styles, mermaid_graph=mermaid_graph)

    mermaid_path.write_text(mermaid_graph + "\n", encoding="utf-8")
    html_path.write_text(html_text, encoding="utf-8")

    return {
        "novel_id": resolved_novel_id,
        "html_path": str(html_path),
        "mermaid_path": str(mermaid_path),
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
        if key in {"faction_position", "story_role"} and value:
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
        return f"faction::{faction}", f"阵营: {faction}"
    role = str(profile.get("story_role", "")).strip()
    if role:
        return f"role::{role}", f"角色: {role}"
    return "unknown", "未标注阵营/角色"


def _category_palette(index: int) -> dict[str, str]:
    palette = [
        {"fill": "#dbeafe", "stroke": "#1d4ed8", "text": "#172554"},
        {"fill": "#dcfce7", "stroke": "#15803d", "text": "#14532d"},
        {"fill": "#fef3c7", "stroke": "#b45309", "text": "#78350f"},
        {"fill": "#fee2e2", "stroke": "#dc2626", "text": "#7f1d1d"},
        {"fill": "#e0f2fe", "stroke": "#0891b2", "text": "#164e63"},
        {"fill": "#ede9fe", "stroke": "#6d28d9", "text": "#4c1d95"},
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

    for pair_key, payload in sorted(relations.items()):
        names = pair_key.split("_")
        if len(names) != 2:
            continue
        left, right = names
        trust = int(payload.get("trust", 5))
        affection = int(payload.get("affection", 5))
        hostility = int(payload.get("hostility", max(0, 5 - affection)))
        closeness = _closeness_score(trust, affection)
        label = f"T{trust} A{affection} H{hostility}"
        lines.append(f"    {_graph_id(left)}[{left}] ---|{label}| {_graph_id(right)}[{right}]")
        node_classes[left] = node_styles.get(left, _default_node_style())
        node_classes[right] = node_styles.get(right, _default_node_style())
        link_styles.append(_edge_style(trust, hostility, closeness))

    if len(lines) == 1:
        placeholder = _default_node_style()
        lines.append("    empty[暂无关系数据]")
        node_classes["empty"] = {**placeholder, "class_name": "group_empty"}

    class_definitions: dict[str, dict[str, str]] = {}
    for name, style in sorted(node_classes.items()):
        class_name = style.get("class_name", "group_unknown")
        node_id = name if name == "empty" else _graph_id(name)
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
) -> str:
    rows: list[str] = []
    for pair_key, payload in sorted(relations.items()):
        trust = int(payload.get("trust", 5))
        affection = int(payload.get("affection", 5))
        hostility = int(payload.get("hostility", max(0, 5 - affection)))
        closeness = _closeness_score(trust, affection)
        power_gap = int(payload.get("power_gap", 0))
        conflict = html.escape(str(payload.get("conflict_point", "")).strip())
        interaction = html.escape(str(payload.get("typical_interaction", "")).strip())
        rows.append(
            "<tr>"
            f"<td><span class=\"pair-key\">{html.escape(pair_key)}</span></td>"
            f"<td>{_metric_badge(trust, 'trust')}</td>"
            f"<td>{_metric_badge(affection, 'affection')}</td>"
            f"<td>{_metric_badge(hostility, 'hostility')}</td>"
            f"<td>{_metric_badge(closeness, 'closeness')}</td>"
            f"<td>{power_gap}</td>"
            f"<td>{conflict or '<span class=\"muted\">-</span>'}</td>"
            f"<td>{interaction or '<span class=\"muted\">-</span>'}</td>"
            "</tr>"
        )
    if not rows:
        rows.append("<tr><td colspan=\"8\"><span class=\"muted\">暂无关系数据，生成后这里会显示图谱和明细。</span></td></tr>")

    escaped_mermaid = html.escape(mermaid_graph)
    relation_count = len(relations)
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
        role = str(style.get("story_role", "")).strip()
        if faction:
            details.append(f"阵营：{html.escape(faction)}")
        if role:
            details.append(f"角色：{html.escape(role)}")
        if not details:
            details.append("未标注阵营/角色")
        node_cards.append(
            "<li class=\"node-item\">"
            f"<span class=\"swatch\" style=\"background:{style.get('fill', '#f3f4f6')}; border-color:{style.get('stroke', '#6b7280')};\"></span>"
            "<div>"
            f"<strong>{html.escape(name)}</strong>"
            f"<span>{' / '.join(details)}</span>"
            "</div>"
            "</li>"
        )
    if not node_cards:
        node_cards.append("<li class=\"node-item muted\">暂无节点元数据</li>")
    category_legend = "".join(
        (
            "<span class=\"legend-item\">"
            f"<span class=\"swatch\" style=\"background:{style.get('fill', '#f3f4f6')}; border-color:{style.get('stroke', '#6b7280')};\"></span>"
            f"{html.escape(style.get('legend', '未标注阵营/角色'))}"
            "</span>"
        )
        for _, style in unique_categories
    )
    conflict_count = sum(1 for payload in relations.values() if int(payload.get("hostility", max(0, 5 - int(payload.get("affection", 5))))) >= 6)
    return (
        "<!DOCTYPE html>\n"
        "<html lang=\"zh-CN\">\n"
        "<head>\n"
        "  <meta charset=\"utf-8\" />\n"
        "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n"
        f"  <title>{html.escape(novel_id)} relation graph</title>\n"
        "  <style>\n"
        "    :root { --bg:#f6efe2; --ink:#1f2937; --muted:#6b7280; --line:#d6c7a7; --card:#fffaf0; --warm:#8a5a2b; --trust:#156f55; --affection:#b4583a; --hostility:#b42318; }\n"
        "    * { box-sizing:border-box; }\n"
        "    body { font-family: 'Noto Serif SC', 'Source Han Serif SC', serif; margin: 0; background: radial-gradient(circle at top, #fff9ef, var(--bg)); color: var(--ink); }\n"
        "    .page { max-width: 1280px; margin: 0 auto; padding: 28px 20px 36px; }\n"
        "    h1 { margin: 0 0 8px; font-size: 32px; }\n"
        "    .subtitle { color: var(--muted); margin-bottom: 20px; }\n"
        "    .summary { display:grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-bottom: 20px; }\n"
        "    .stat { background: linear-gradient(180deg, #fffdf8, #f7edd8); border:1px solid var(--line); border-radius: 14px; padding: 14px 16px; }\n"
        "    .stat-label { display:block; color: var(--muted); font-size: 12px; text-transform: uppercase; letter-spacing: .08em; margin-bottom: 8px; }\n"
        "    .stat-value { font-size: 26px; color: var(--warm); }\n"
        "    .grid { display:grid; grid-template-columns: minmax(0, 1.35fr) minmax(340px, .95fr); gap: 18px; align-items:start; }\n"
        "    .card { background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 18px; box-shadow: 0 10px 35px rgba(90, 60, 20, .06); }\n"
        "    .card h2 { margin: 0 0 12px; font-size: 19px; }\n"
        "    .legend { display:flex; flex-wrap:wrap; gap:10px; margin: 4px 0 12px; }\n"
        "    .legend-item { display:inline-flex; align-items:center; gap:8px; padding:6px 10px; border-radius:999px; background:#fff; border:1px solid var(--line); font-size:13px; }\n"
        "    .swatch { width:12px; height:12px; border-radius:999px; display:inline-block; border:2px solid transparent; flex:0 0 auto; }\n"
        "    .edge-rule { display:grid; gap:10px; margin-top: 14px; }\n"
        "    .edge-rule strong { display:block; margin-bottom: 4px; }\n"
        "    .graph-shell { min-height: 480px; background: linear-gradient(180deg, #fffef9, #fcf5e8); border:1px dashed #dbc8a4; border-radius: 14px; padding: 12px; }\n"
        "    .mermaid { text-align:center; }\n"
        "    pre { white-space: pre-wrap; overflow-x: auto; background: #fffdf8; padding: 16px; border-radius: 12px; border:1px solid var(--line); }\n"
        "    details { margin-top: 16px; }\n"
        "    details summary { cursor: pointer; color: var(--warm); font-weight: 600; }\n"
        "    .node-list { list-style:none; margin:0; padding:0; display:grid; gap:10px; }\n"
        "    .node-item { display:flex; gap:10px; align-items:flex-start; padding:10px 12px; border-radius:12px; background:#fffdf8; border:1px solid var(--line); }\n"
        "    .node-item strong { display:block; margin-bottom:4px; }\n"
        "    .node-item span { color: var(--muted); font-size: 13px; }\n"
        "    table { width: 100%; border-collapse: collapse; background: white; }\n"
        "    th, td { border: 1px solid var(--line); padding: 10px; text-align: left; vertical-align: top; }\n"
        "    th { background: #efe3c5; }\n"
        "    .pair-key { font-weight: 600; color: #4b3a28; }\n"
        "    .metric { display:inline-flex; min-width:40px; justify-content:center; padding:4px 10px; border-radius:999px; font-weight:700; color:#fff; }\n"
        "    .metric.trust { background: var(--trust); }\n"
        "    .metric.affection { background: var(--affection); }\n"
        "    .metric.hostility { background: var(--hostility); }\n"
        "    .metric.closeness { background: #6b7280; }\n"
        "    .muted { color: var(--muted); }\n"
        "    .note { color: var(--muted); font-size: 13px; margin-top: 10px; }\n"
        "    @media (max-width: 980px) { .grid { grid-template-columns: 1fr; } .page { padding: 20px 14px 28px; } h1 { font-size: 26px; } }\n"
        "  </style>\n"
        "  <script type=\"module\">\n"
        "    import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';\n"
        "    mermaid.initialize({ startOnLoad: true, theme: 'base', securityLevel: 'loose', themeVariables: { primaryTextColor: '#1f2937', lineColor: '#7b5b34', primaryBorderColor: '#8a5a2b', clusterBorder: '#d6c7a7', fontFamily: 'Noto Serif SC, Source Han Serif SC, serif' } });\n"
        "  </script>\n"
        "</head>\n"
        "<body>\n"
        "  <div class=\"page\">\n"
        f"    <h1>{html.escape(novel_id)} 人物关系图谱</h1>\n"
        f"    <div class=\"subtitle\">共 {relation_count} 条关系。节点颜色优先按阵营，其次按角色类型；绿色边偏信任，红色边偏冲突，线越粗表示关系越亲密。</div>\n"
        "    <div class=\"summary\">\n"
        f"      <div class=\"stat\"><span class=\"stat-label\">Relations</span><span class=\"stat-value\">{relation_count}</span></div>\n"
        f"      <div class=\"stat\"><span class=\"stat-label\">High Trust</span><span class=\"stat-value\">{sum(1 for payload in relations.values() if int(payload.get('trust', 5)) >= 7)}</span></div>\n"
        f"      <div class=\"stat\"><span class=\"stat-label\">High Conflict</span><span class=\"stat-value\">{conflict_count}</span></div>\n"
        f"      <div class=\"stat\"><span class=\"stat-label\">Node Groups</span><span class=\"stat-value\">{max(1, len(unique_categories))}</span></div>\n"
        "    </div>\n"
        "    <div class=\"grid\">\n"
        "      <div class=\"card\">\n"
        "        <h2>关系网络</h2>\n"
        f"        <div class=\"legend\">{category_legend or '<span class=\"legend-item\">暂无阵营/角色元数据</span>'}</div>\n"
        "        <div class=\"graph-shell\">\n"
        f"          <div class=\"mermaid\">{html.escape(mermaid_graph)}</div>\n"
        "        </div>\n"
        "        <div class=\"edge-rule\">\n"
        "          <div><strong>边的含义</strong><span class=\"muted\">绿色表示信任占优，红色表示冲突占优，棕色表示关系仍在拉扯或偏中性。</span></div>\n"
        "          <div><strong>线宽映射</strong><span class=\"muted\">线宽按亲密度计算，亲密度由 trust 与 affection 的均值近似映射到 1 到 5 级。</span></div>\n"
        "        </div>\n"
        "        <details>\n"
        "          <summary>查看 Mermaid 源码</summary>\n"
        f"          <pre>{escaped_mermaid}</pre>\n"
        "        </details>\n"
        "      </div>\n"
        "      <div class=\"card\">\n"
        "        <h2>节点说明</h2>\n"
        "        <ul class=\"node-list\">\n"
        f"          {''.join(node_cards)}\n"
        "        </ul>\n"
        "        <div class=\"note\">颜色来自人物画像中的 `faction_position` 或 `story_role`。如果两者都缺失，会回退为中性色。</div>\n"
        "      </div>\n"
        "    </div>\n"
        "    <div class=\"card\" style=\"margin-top:18px;\">\n"
        "      <h2>关系明细</h2>\n"
        "      <table>\n"
        "        <thead><tr><th>关系对</th><th>Trust</th><th>Affection</th><th>Hostility</th><th>Closeness</th><th>Power Gap</th><th>Conflict</th><th>Interaction</th></tr></thead>\n"
        f"        <tbody>{''.join(rows)}</tbody>\n"
        "      </table>\n"
        "    </div>\n"
        "  </div>\n"
        "</body>\n"
        "</html>\n"
    )


def _closeness_score(trust: int, affection: int) -> int:
    return max(1, min(5, int(round((trust + affection) / 4))))


def _edge_style(trust: int, hostility: int, closeness: int) -> str:
    if hostility >= max(6, trust):
        color = "#b42318"
    elif trust >= 7:
        color = "#15803d"
    else:
        color = "#8a5a2b"
    width = 1 + closeness
    return f"stroke:{color},stroke-width:{width}px"


def _metric_badge(value: int, kind: str) -> str:
    return f"<span class=\"metric {kind}\">{value}</span>"


def _graph_id(name: str) -> str:
    return re.sub(r"[^A-Za-z0-9_\u4e00-\u9fff]", "_", str(name))
