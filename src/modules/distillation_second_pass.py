#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

from typing import Any, Callable, Dict, List


def build_second_pass_messages(
    *,
    prompt_text: str,
    schema_text: str,
    style_text: str,
    logic_text: str,
    draft_markdown: str,
    hint_markdown: str,
    evidence_markdown: str,
) -> List[Dict[str, str]]:
    system_prompt = "\n\n".join(
        [
            prompt_text,
            schema_text,
            style_text,
            logic_text,
            (
                "第二次蒸馏任务：\n"
                "- 你会收到一份规则草稿 PROFILE 和对应证据。\n"
                "- 你的职责是把深层人格字段、阶段弧光字段、表达特征字段提炼得更具体、更有区分度。\n"
                "- 只能基于给定证据修订，不得脑补原文之外的设定。\n"
                "- 输出必须仍然是可解析的 Markdown，每行使用 `- key: value`。\n"
                "- 请输出完整 `# PROFILE` 文档，而不是解释。\n"
            ),
        ]
    )
    user_prompt = "\n\n".join(
        [
            "以下是规则草稿：",
            draft_markdown,
            "以下是角色专属约束：",
            hint_markdown,
            "以下是证据摘要：",
            evidence_markdown,
        ]
    )
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]


def render_peer_profile_contrasts(
    name: str,
    peer_profiles: Dict[str, Dict[str, Any]],
    *,
    split_persona_scalar: Callable[[str], List[str]],
) -> str:
    if not peer_profiles:
        return "- no peer profiles"
    focus_fields = (
        "identity_anchor",
        "soul_goal",
        "temperament_type",
        "speech_style",
        "background_imprint",
        "social_mode",
        "reward_logic",
        "belief_anchor",
        "stress_response",
    )
    lines: List[str] = [f"# PEERS FOR {name}"]
    for peer_name, peer in sorted(peer_profiles.items()):
        lines.extend(["", f"## {peer_name}"])
        for field in focus_fields:
            value = str(peer.get(field, "")).strip()
            if value:
                lines.append(f"- {field}: {value}")
        decision_rules = (
            split_persona_scalar(str(peer.get("decision_rules", "")))
            if isinstance(peer.get("decision_rules"), str)
            else list(peer.get("decision_rules", []))
        )
        key_bonds = (
            split_persona_scalar(str(peer.get("key_bonds", "")))
            if isinstance(peer.get("key_bonds"), str)
            else list(peer.get("key_bonds", []))
        )
        if decision_rules:
            lines.append(f"- decision_rules: {'，'.join(decision_rules[:3])}")
        if key_bonds:
            lines.append(f"- key_bonds: {'，'.join(key_bonds[:3])}")
    return "\n".join(lines).rstrip() + "\n"


def render_overlap_report(overlap_report: List[str]) -> str:
    if not overlap_report:
        return "- no major overlap alerts"
    return "\n".join(f"- {item}" for item in overlap_report)


def parse_markdown_kv(text: str) -> Dict[str, str]:
    parsed: Dict[str, str] = {}
    for raw_line in str(text or "").splitlines():
        line = raw_line.strip()
        if not line.startswith("- ") or ":" not in line:
            continue
        key, value = line[2:].split(":", 1)
        key_text = key.strip()
        value_text = value.strip()
        if not key_text or not value_text:
            continue
        if key_text in parsed and parsed[key_text]:
            parsed[key_text] = f"{parsed[key_text]}，{value_text}"
        else:
            parsed[key_text] = value_text
    return parsed
