#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

from pathlib import Path
import re
from typing import Any, Dict, List, Optional, Tuple

from src.core.exceptions import ZaomengError
from src.modules.distillation_second_pass import (
    build_second_pass_messages,
    parse_markdown_kv,
    render_overlap_report,
    render_peer_profile_contrasts,
)


class DistillationRefinementMixin:
    _CRITICAL_SCALAR_FIELDS = (
        "identity_anchor",
        "soul_goal",
        "temperament_type",
        "background_imprint",
        "social_mode",
        "reward_logic",
        "belief_anchor",
        "moral_bottom_line",
        "stress_response",
        "story_role",
        "others_impression",
        "restraint_threshold",
    )
    _OVERLAP_LIST_FIELDS = ("decision_rules", "key_bonds", "core_traits")

    def _refine_profile_with_llm(
        self,
        profile: Dict[str, Any],
        *,
        bucket: Dict[str, List[str]],
        arc_values: List[Tuple[int, Dict[str, int]]],
        peer_profiles: Optional[Dict[str, Dict[str, Any]]] = None,
        overlap_report: Optional[List[str]] = None,
    ) -> Dict[str, Any]:
        local_refined = dict(profile)
        if not self._should_use_llm_second_pass():
            return local_refined

        try:
            character_hint = self._resolve_character_hint(str(local_refined.get("name", "")))
            messages = self._build_second_pass_messages(
                local_refined,
                bucket,
                arc_values,
                peer_profiles=peer_profiles or {},
                overlap_report=overlap_report or [],
                character_hint=character_hint,
            )
            messages[0]["content"] = (
                f"{messages[0]['content']}\n\n"
                "额外要求：\n"
                "- 如果关键字段与同批角色重合，请优先改写成更能体现该角色差异的表述。\n"
                "- 若证据无法支持明显区分，就直接写“证据不足”，不要保留通用空话。\n"
                "- 输出仍需严格遵守 `- key: value` 的 Markdown 结构。\n"
            )
            messages[1]["content"] = "\n\n".join(
                [
                    messages[1]["content"],
                    "## Character Hint",
                    self._render_character_hint(str(profile.get("name", "")), character_hint),
                    "## Peer Contrast",
                    self._render_peer_profile_contrasts(profile["name"], peer_profiles or {}),
                    "## Overlap Alerts",
                    self._render_overlap_report(overlap_report or []),
                ]
            )
            response = self.llm_client.chat_completion(
                messages,
                temperature=0.2,
                max_tokens=1600,
            )
            content = str(response.get("content", "")).strip()
            if not content:
                return self._enforce_profile_distinction(
                    local_refined,
                    bucket=bucket,
                    peer_profiles=peer_profiles or {},
                )
            parsed = self._parse_markdown_kv(content)
            if not parsed:
                return self._enforce_profile_distinction(
                    local_refined,
                    bucket=bucket,
                    peer_profiles=peer_profiles or {},
                )
            refined = self._apply_profile_refinement(local_refined, parsed)
            refined["arc_summary"] = self._infer_arc_summary(refined.get("arc", {}))
            refined["arc_confidence"] = self._safe_int(parsed.get("arc_confidence", refined.get("arc_confidence", 0)))
            return self._enforce_profile_distinction(
                refined,
                bucket=bucket,
                peer_profiles=peer_profiles or {},
            )
        except ZaomengError as exc:
            if self._should_disable_second_pass_after_error(exc):
                reason = self._summarize_second_pass_disable_reason(exc)
                self._second_pass_disabled_reason = reason
                self.logger.warning(
                    "Disabling LLM second pass for remaining characters in this run after %s: %s",
                    profile.get("name", "unknown"),
                    reason,
                )
            else:
                self.logger.warning("Skipping LLM second pass for %s: %s", profile.get("name", "unknown"), exc)
            return local_refined

    def _should_use_llm_second_pass(self) -> bool:
        if getattr(self, "_second_pass_disabled_reason", ""):
            return False
        if self.second_pass_mode == "rule-only":
            return False
        if self.second_pass_mode == "llm-only":
            return True
        return bool(getattr(self.llm_client, "is_generation_enabled", lambda: False)())

    @staticmethod
    def _should_disable_second_pass_after_error(exc: Exception) -> bool:
        message = str(exc or "").lower()
        return any(
            token in message
            for token in (
                "invalidsubscription",
                "codingplan",
                "subscription has expired",
                "does not have a valid",
            )
        )

    @staticmethod
    def _summarize_second_pass_disable_reason(exc: Exception) -> str:
        message = str(exc or "")
        lowered = message.lower()
        if "invalidsubscription" in lowered or "codingplan" in lowered:
            return "当前模型供应商账号没有可用的 CodingPlan / 订阅权限，二次精修已自动跳过。"
        return message

    def _refine_profile_locally(
        self,
        profile: Dict[str, Any],
        *,
        bucket: Dict[str, List[str]],
        peer_profiles: Dict[str, Dict[str, Any]],
        overlap_report: List[str],
    ) -> Dict[str, Any]:
        return dict(profile)

    def _build_second_pass_messages(
        self,
        profile: Dict[str, Any],
        bucket: Dict[str, List[str]],
        arc_values: List[Tuple[int, Dict[str, int]]],
        *,
        peer_profiles: Optional[Dict[str, Dict[str, Any]]] = None,
        overlap_report: Optional[List[str]] = None,
        character_hint: Optional[Dict[str, Any]] = None,
    ) -> List[Dict[str, str]]:
        prompt_text = self._load_auxiliary_markdown(
            "prompt_file",
            "distill_prompt.md",
            fallback=(
                "# 人物二次蒸馏\n"
                "请在不脱离原文证据的前提下，把人物档案修订得更具体、更稳定、更有差异化。"
            ),
        )
        schema_text = self._load_auxiliary_markdown("reference_file", "output_schema.md", fallback="# 输出规则")
        style_text = self._load_auxiliary_markdown("reference_file", "style_differ.md", fallback="# 风格差异")
        logic_text = self._load_auxiliary_markdown("reference_file", "logic_constraint.md", fallback="# 逻辑约束")
        draft_markdown = self._render_profile_md(profile)
        evidence_markdown = self._render_second_pass_evidence(profile["name"], bucket, arc_values)
        hint_markdown = self._render_character_hint(str(profile.get("name", "")), character_hint or {})
        draft_with_contrast = "\n\n".join(
            [
                draft_markdown,
                "## Peer Contrast",
                self._render_peer_profile_contrasts(profile["name"], peer_profiles or {}),
                "## Overlap Alerts",
                self._render_overlap_report(overlap_report or []),
            ]
        )
        return build_second_pass_messages(
            prompt_text=prompt_text,
            schema_text=schema_text,
            style_text=style_text,
            logic_text=logic_text,
            draft_markdown=draft_with_contrast,
            hint_markdown=hint_markdown,
            evidence_markdown=evidence_markdown,
        )

    def _render_second_pass_evidence(
        self,
        name: str,
        bucket: Dict[str, List[str]],
        arc_values: List[Tuple[int, Dict[str, int]]],
    ) -> str:
        timeline = list(bucket.get("timeline", []))
        stage_windows = self._build_stage_windows(timeline)
        lines = [
            f"# EVIDENCE FOR {name}",
            "",
            "## Early Stage",
        ]
        lines.extend(f"- {line}" for line in stage_windows.get("start", [])[: self.llm_evidence_lines_per_stage])
        lines.extend(["", "## Mid Stage"])
        lines.extend(f"- {line}" for line in stage_windows.get("mid", [])[: self.llm_evidence_lines_per_stage])
        lines.extend(["", "## Late Stage"])
        lines.extend(f"- {line}" for line in stage_windows.get("end", [])[: self.llm_evidence_lines_per_stage])
        lines.extend(["", "## Dialogue Samples"])
        lines.extend(f"- {line}" for line in self._dedupe_texts(bucket.get("dialogues", []), 8)[:8])
        lines.extend(["", "## Thought Samples"])
        lines.extend(f"- {line}" for line in self._dedupe_texts(bucket.get("thoughts", []), 6)[:6])
        lines.extend(["", "## Description Samples"])
        lines.extend(f"- {line}" for line in self._dedupe_texts(bucket.get("descriptions", []), 6)[:6])
        lines.extend(["", "## Arc Metrics"])
        for idx, values in arc_values[:12]:
            lines.append(f"- chunk_{idx}: {self._join_metric_map(values)}")
        return "\n".join(lines).rstrip() + "\n"

    def _render_peer_profile_contrasts(
        self,
        name: str,
        peer_profiles: Dict[str, Dict[str, Any]],
    ) -> str:
        return render_peer_profile_contrasts(
            name,
            peer_profiles,
            split_persona_scalar=self._split_persona_scalar,
        )

    @staticmethod
    def _render_overlap_report(overlap_report: List[str]) -> str:
        return render_overlap_report(overlap_report)

    def _load_auxiliary_markdown(self, method_name: str, filename: str, fallback: str) -> str:
        resolver = getattr(self.path_provider, method_name, None)
        if resolver is None:
            return fallback
        path = resolver(filename)
        if not path or not Path(path).exists():
            return fallback
        return Path(path).read_text(encoding="utf-8")

    @staticmethod
    def _parse_markdown_kv(text: str) -> Dict[str, str]:
        return parse_markdown_kv(text)

    def _apply_profile_refinement(self, profile: Dict[str, Any], parsed: Dict[str, str]) -> Dict[str, Any]:
        refined = dict(profile)
        list_fields = {
            "core_traits",
            "typical_lines",
            "decision_rules",
            "life_experience",
            "taboo_topics",
            "forbidden_behaviors",
            "strengths",
            "weaknesses",
            "cognitive_limits",
            "fear_triggers",
            "key_bonds",
            "signature_phrases",
            "sentence_openers",
            "connective_tokens",
            "sentence_endings",
            "forbidden_fillers",
        }
        dict_targets = {
            "cadence": ("speech_habits", "cadence"),
            "signature_phrases": ("speech_habits", "signature_phrases"),
            "sentence_openers": ("speech_habits", "sentence_openers"),
            "connective_tokens": ("speech_habits", "connective_tokens"),
            "sentence_endings": ("speech_habits", "sentence_endings"),
            "forbidden_fillers": ("speech_habits", "forbidden_fillers"),
            "anger_style": ("emotion_profile", "anger_style"),
            "joy_style": ("emotion_profile", "joy_style"),
            "grievance_style": ("emotion_profile", "grievance_style"),
        }
        direct_fields = {
            "speech_style",
            "identity_anchor",
            "soul_goal",
            "trauma_scar",
            "worldview",
            "thinking_style",
            "temperament_type",
            "core_identity",
            "faction_position",
            "background_imprint",
            "world_rule_fit",
            "social_mode",
            "hidden_desire",
            "inner_conflict",
            "story_role",
            "belief_anchor",
            "moral_bottom_line",
            "self_cognition",
            "stress_response",
            "others_impression",
            "restraint_threshold",
            "private_self",
            "stance_stability",
            "reward_logic",
            "action_style",
            "arc_summary",
        }

        for key, value in parsed.items():
            if key in {"arc_start", "arc_mid", "arc_end"}:
                bucket_name = key.split("_", 1)[1]
                arc_bucket = dict(refined.get("arc", {}).get(bucket_name, {}))
                arc_bucket.update(self._split_metric_map(value))
                refined.setdefault("arc", {})[bucket_name] = arc_bucket
                continue
            if key == "values":
                value_map = self._split_metric_map(value)
                if value_map:
                    refined["values"] = {
                        metric: max(0, min(10, self._safe_int(metric_value)))
                        for metric, metric_value in value_map.items()
                    }
                continue
            if key in dict_targets:
                parent, child = dict_targets[key]
                parent_bucket = dict(refined.get(parent, {})) if isinstance(refined.get(parent, {}), dict) else {}
                parent_bucket[child] = self._split_persona_scalar(value) if key in list_fields else value
                refined[parent] = parent_bucket
                continue
            if key in direct_fields:
                refined[key] = value
                continue
            if key in list_fields:
                refined[key] = self._split_persona_scalar(value)
        return refined

    @staticmethod
    def _split_persona_scalar(value: str) -> List[str]:
        return [item.strip() for item in re.split(r"[，,;；/\n]\s*", str(value or "").strip()) if item.strip()]

    def _collect_profile_overlap(
        self,
        profile: Dict[str, Any],
        all_profiles: Dict[str, Dict[str, Any]],
    ) -> List[str]:
        current_name = str(profile.get("name", "")).strip()
        alerts: List[str] = []
        for peer_name, peer in all_profiles.items():
            if peer_name == current_name:
                continue
            for field in self._CRITICAL_SCALAR_FIELDS:
                current_value = self._normalize_overlap_text(profile.get(field, ""))
                peer_value = self._normalize_overlap_text(peer.get(field, ""))
                if current_value and current_value == peer_value:
                    alerts.append(f"{field} is identical to {peer_name}")
            for field in self._OVERLAP_LIST_FIELDS:
                current_items = self._normalize_overlap_items(profile.get(field, []))
                peer_items = self._normalize_overlap_items(peer.get(field, []))
                if current_items and current_items == peer_items:
                    alerts.append(f"{field} fully overlaps with {peer_name}")
                elif current_items and peer_items:
                    overlap = len(set(current_items) & set(peer_items)) / max(1, min(len(current_items), len(peer_items)))
                    if overlap >= 0.75:
                        alerts.append(f"{field} heavily overlaps with {peer_name}")
        return self._dedupe_texts(alerts, 12)

    @staticmethod
    def _normalize_overlap_text(value: Any) -> str:
        return re.sub(r"\s+", "", str(value or "").strip())

    def _normalize_overlap_items(self, value: Any) -> List[str]:
        if isinstance(value, list):
            items = value
        else:
            items = self._split_persona_scalar(str(value or ""))
        return [self._normalize_overlap_text(item) for item in items if self._normalize_overlap_text(item)]

    @staticmethod
    def _split_metric_map(value: str) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        for item in DistillationRefinementMixin._split_metric_entries(value):
            if not item or "=" not in item:
                continue
            key, raw = item.split("=", 1)
            key_text = key.strip()
            raw_text = raw.strip()
            if not key_text:
                continue
            if re.fullmatch(r"-?\d+", raw_text):
                result[key_text] = int(raw_text)
            else:
                result[key_text] = raw_text
        return result

    @staticmethod
    def _split_metric_entries(value: str) -> List[str]:
        text = str(value or "").strip()
        if not text:
            return []
        pattern = r"(?:[;；/\n]\s*|锛\?\s*|(?<=[^=])[，,]\s*(?=[^=，,;；/\n]+?=))"
        return [item.strip() for item in re.split(pattern, text) if item.strip()]

    @staticmethod
    def _safe_int(value: Any) -> int:
        try:
            return int(value)
        except (TypeError, ValueError):
            return 0

    def _enforce_profile_distinction(
        self,
        profile: Dict[str, Any],
        *,
        bucket: Dict[str, List[str]],
        peer_profiles: Dict[str, Dict[str, Any]],
    ) -> Dict[str, Any]:
        refined = dict(profile)
        for field in self._CRITICAL_SCALAR_FIELDS:
            if not self._field_overlaps_with_peer(refined, field, peer_profiles):
                continue
            refined[field] = self._pick_distinct_scalar(field, refined, bucket, peer_profiles)
        for field in self._OVERLAP_LIST_FIELDS:
            current_items = self._normalize_overlap_items(refined.get(field, []))
            if not current_items:
                continue
            filtered = self._filter_distinct_list_items(refined.get(field, []), field, peer_profiles)
            if filtered:
                refined[field] = filtered
        return refined

    def _field_overlaps_with_peer(
        self,
        profile: Dict[str, Any],
        field: str,
        peer_profiles: Dict[str, Dict[str, Any]],
    ) -> bool:
        current_value = self._normalize_overlap_text(profile.get(field, ""))
        if not current_value:
            return False
        for peer in peer_profiles.values():
            if current_value == self._normalize_overlap_text(peer.get(field, "")):
                return True
        return False

    def _pick_distinct_scalar(
        self,
        field: str,
        profile: Dict[str, Any],
        bucket: Dict[str, List[str]],
        peer_profiles: Dict[str, Dict[str, Any]],
    ) -> str:
        peer_values = {
            self._normalize_overlap_text(peer.get(field, ""))
            for peer in peer_profiles.values()
            if self._normalize_overlap_text(peer.get(field, ""))
        }
        for candidate in self._field_candidate_values(field, profile, bucket):
            normalized = self._normalize_overlap_text(candidate)
            if not normalized or normalized in peer_values:
                continue
            return str(candidate).strip()
        return "证据不足"

    def _field_candidate_values(
        self,
        field: str,
        profile: Dict[str, Any],
        bucket: Dict[str, List[str]],
    ) -> List[str]:
        descriptions = self._dedupe_texts(bucket.get("descriptions", []), 10)
        dialogues = self._dedupe_texts(bucket.get("dialogues", []), 10)
        thoughts = self._dedupe_texts(bucket.get("thoughts", []), 10)
        decision_rules = (
            list(profile.get("decision_rules", []))
            if isinstance(profile.get("decision_rules"), list)
            else self._split_persona_scalar(str(profile.get("decision_rules", "")))
        )
        forbidden_behaviors = (
            list(profile.get("forbidden_behaviors", []))
            if isinstance(profile.get("forbidden_behaviors"), list)
            else self._split_persona_scalar(str(profile.get("forbidden_behaviors", "")))
        )
        life_experience = (
            list(profile.get("life_experience", []))
            if isinstance(profile.get("life_experience"), list)
            else self._split_persona_scalar(str(profile.get("life_experience", "")))
        )
        candidates_by_field = {
            "identity_anchor": [*descriptions, *thoughts, str(profile.get("core_identity", "")), str(profile.get("name", ""))],
            "soul_goal": [*thoughts, *decision_rules, str(profile.get("hidden_desire", ""))],
            "temperament_type": [
                str(profile.get("temperament_type", "")),
                self._infer_temperament_type(
                    list(profile.get("core_traits", [])),
                    str(profile.get("speech_style", "")),
                    dict(profile.get("values", {})) if isinstance(profile.get("values", {}), dict) else {},
                    str(profile.get("archetype", "")),
                ),
            ],
            "background_imprint": [*life_experience, *descriptions],
            "social_mode": [*dialogues, *descriptions, str(profile.get("speech_style", "")), str(profile.get("action_style", ""))],
            "reward_logic": [*decision_rules, *thoughts, str(profile.get("worldview", ""))],
            "belief_anchor": [str(profile.get("worldview", "")), str(profile.get("soul_goal", "")), str(profile.get("moral_bottom_line", ""))],
            "moral_bottom_line": [*forbidden_behaviors, str(profile.get("belief_anchor", ""))],
            "stress_response": [
                self._infer_stress_response(
                    dict(profile.get("emotion_profile", {})) if isinstance(profile.get("emotion_profile", {}), dict) else {},
                    decision_rules,
                    str(profile.get("speech_style", "")),
                    forbidden_behaviors,
                    str(profile.get("archetype", "")),
                ),
                *thoughts,
            ],
            "story_role": [str(profile.get("core_identity", "")), *descriptions, str(profile.get("action_style", ""))],
            "others_impression": [*descriptions, str(profile.get("speech_style", "")), str(profile.get("identity_anchor", ""))],
            "restraint_threshold": [*forbidden_behaviors, *thoughts, str(profile.get("hidden_desire", ""))],
        }
        seen = set()
        candidates: List[str] = []
        for raw in candidates_by_field.get(field, [str(profile.get(field, ""))]):
            candidate = str(raw or "").strip()
            normalized = self._normalize_overlap_text(candidate)
            if not normalized or normalized in seen:
                continue
            seen.add(normalized)
            candidates.append(candidate)
        return candidates

    def _filter_distinct_list_items(
        self,
        value: Any,
        field: str,
        peer_profiles: Dict[str, Dict[str, Any]],
    ) -> List[str]:
        items = list(value) if isinstance(value, list) else self._split_persona_scalar(str(value or ""))
        peer_items = set()
        for peer in peer_profiles.values():
            peer_items.update(self._normalize_overlap_items(peer.get(field, [])))
        distinct: List[str] = []
        seen = set()
        for item in items:
            normalized = self._normalize_overlap_text(item)
            if not normalized or normalized in peer_items or normalized in seen:
                continue
            seen.add(normalized)
            distinct.append(str(item).strip())
        return distinct
