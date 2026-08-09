#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import re
from typing import Any, Dict, List, Optional

from src.core.config import Config
from src.core.contracts import CorrectionService, RuleProvider, RuntimePartsLike


class Speaker:
    """Prepare compact reply guidance for the host LLM.

    The speaker module should not simulate a whole character with dense rule trees.
    Its job is to normalize profile data, summarize the most relevant constraints,
    and provide a small deterministic fallback when generation is unavailable.
    """

    DEFAULT_PRIORITY_ORDER = ("责任", "忠诚", "正义", "智慧", "勇气", "善良", "自由", "野心")
    VOICE_SCALAR_FIELDS = (
        "thinking_style",
        "action_style",
        "social_mode",
        "temperament_type",
        "belief_anchor",
        "moral_bottom_line",
        "self_cognition",
        "stress_response",
        "others_impression",
        "restraint_threshold",
        "private_self",
        "reward_logic",
        "world_belong",
        "rule_view",
        "plot_restriction",
        "carry_style",
        "interest_claim",
        "resource_dependence",
        "trade_principle",
        "emotion_model",
        "disguise_switch",
        "ooc_redline",
        "timeline_stage",
    )
    BEHAVIOR_RULE_FIELDS = (
        ("identity_anchor", "核心身份"),
        ("soul_goal", "核心动机"),
        ("temperament_type", "气质底色"),
        ("worldview", "世界观"),
        ("thinking_style", "思考方式"),
        ("action_style", "行事风格"),
        ("reward_logic", "恩怨逻辑"),
        ("belief_anchor", "信念支点"),
        ("moral_bottom_line", "底线边界"),
        ("stress_response", "高压反应"),
        ("interest_claim", "利益诉求"),
        ("carry_style", "分层态度"),
        ("ooc_redline", "演绎红线"),
    )
    MEMORY_CUE_FIELDS = (
        ("user_edits", "用户纠正"),
        ("relationship_updates", "关系更新"),
        ("notable_interactions", "近期互动"),
    )

    def __init__(
        self,
        config: Optional[Config] = None,
        *,
        correction_service: Optional[CorrectionService] = None,
        rulebook: Optional[RuleProvider] = None,
    ):
        self.config = config or Config()
        if correction_service is None or rulebook is None:
            raise ValueError("Speaker requires injected correction_service and rulebook")
        self.rulebook = rulebook
        self.correction_service = correction_service

        speaker_rules = self.rulebook.section("speaker")
        self.question_tokens = tuple(str(item) for item in speaker_rules.get("question_tokens", []))
        self.war_tokens = tuple(str(item) for item in speaker_rules.get("war_tokens", []))
        self.rest_tokens = tuple(str(item) for item in speaker_rules.get("rest_tokens", []))
        self.view_tokens = tuple(str(item) for item in speaker_rules.get("view_tokens", []))
        self.care_tokens = tuple(str(item) for item in speaker_rules.get("care_tokens", []))
        self.trait_priority_map = {
            str(key).strip(): str(value).strip()
            for key, value in speaker_rules.get("trait_priority_map", {}).items()
            if str(key).strip() and str(value).strip()
        }
        configured_order = self.config.get("distillation.values_dimensions", list(self.DEFAULT_PRIORITY_ORDER))
        self.priority_order = tuple(str(item).strip() for item in configured_order if str(item).strip()) or self.DEFAULT_PRIORITY_ORDER

    @classmethod
    def from_runtime_parts(cls, parts: RuntimePartsLike) -> "Speaker":
        return cls(parts.config, correction_service=parts.reflection, rulebook=parts.rulebook)

    def generate(
        self,
        character_profile: Dict[str, Any],
        context: str,
        history: List[Dict[str, str]],
        target_name: str = "",
        relation_state: Optional[Dict[str, Any]] = None,
        relation_hint: str = "",
    ) -> str:
        guidance = self.build_generation_guidance(
            character_profile=character_profile,
            context=context,
            history=history,
            target_name=target_name,
            relation_state=relation_state,
            relation_hint=relation_hint,
        )
        return str(guidance.get("fallback_reply", "")).strip()

    def build_generation_guidance(
        self,
        character_profile: Dict[str, Any],
        context: str,
        history: List[Dict[str, str]],
        target_name: str = "",
        relation_state: Optional[Dict[str, Any]] = None,
        relation_hint: str = "",
    ) -> Dict[str, Any]:
        relation_state = relation_state or {}
        name = str(character_profile.get("name", "角色")).strip() or "角色"
        recent_text = "\n".join(f"{item.get('speaker', '')}: {item.get('message', '')}" for item in history[-6:])
        similar = self.correction_service.search_similar_corrections(
            recent_text,
            character=name,
            target=target_name or None,
            top_k=2,
        )
        voice = self._build_voice(character_profile)
        target_display = self._preferred_target_name(name, target_name, relation_state)
        topic = self._classify_topic(context)

        return {
            "character_name": name,
            "target_name": target_name,
            "target_display": target_display,
            "topic": topic,
            "voice": voice,
            "fallback_reply": self._fallback_reply(topic, context, target_display, voice, relation_state),
            "style_rules": self._summarize_style_rules(voice),
            "behavior_rules": self._summarize_behavior_rules(character_profile, voice),
            "relation_rules": self._summarize_relation_rules(target_display, relation_state, relation_hint),
            "memory_cues": self._summarize_memory_cues(voice),
            "similar_corrections": similar,
        }

    @staticmethod
    def _preferred_target_name(speaker_name: str, target_name: str, relation_state: Dict[str, Any]) -> str:
        appellations = relation_state.get("appellations", {})
        if not isinstance(appellations, dict):
            return target_name
        preferred = str(appellations.get(f"{speaker_name}->{target_name}", "")).strip()
        return preferred or target_name

    def _build_voice(self, profile: Dict[str, Any]) -> Dict[str, Any]:
        values = {
            str(key).strip(): self._safe_int(value, default=5)
            for key, value in dict(profile.get("values", {})).items()
            if str(key).strip()
        }
        traits = self._clean_list(profile.get("core_traits", []))
        decision_rules = self._clean_list(profile.get("decision_rules", []))
        typical_lines = self._clean_list(profile.get("typical_lines", []))
        taboo_topics = self._clean_list(profile.get("taboo_topics", []))
        forbidden_behaviors = self._clean_list(profile.get("forbidden_behaviors", []))
        key_bonds = self._clean_list(profile.get("key_bonds", []))
        user_edits = self._clean_list(profile.get("user_edits", []))
        notable_interactions = self._clean_list(profile.get("notable_interactions", []))
        relationship_updates = self._clean_list(profile.get("relationship_updates", []))
        speech_style = str(profile.get("speech_style", "")).strip()
        speech_habits = dict(profile.get("speech_habits", {})) if isinstance(profile.get("speech_habits", {}), dict) else {}

        priority_scores = {item: values.get(item, 5) for item in self.priority_order}
        for trait in traits:
            mapped = self.trait_priority_map.get(trait)
            if mapped:
                priority_scores[mapped] = priority_scores.get(mapped, 5) + 2
        ordered = sorted(self.priority_order, key=lambda item: priority_scores.get(item, 5), reverse=True)
        primary = ordered[0] if ordered else "责任"

        cadence = str(speech_habits.get("cadence", "")).strip() or self._infer_cadence(speech_style, user_edits, typical_lines)
        signature_phrases = self._clean_list(speech_habits.get("signature_phrases", []))[:4]
        if not signature_phrases:
            signature_phrases = self._extract_signature_phrases(typical_lines)

        worldview = str(profile.get("worldview", "")).strip() or self._fallback_worldview(primary, values, typical_lines)
        goal = str(profile.get("soul_goal", "")).strip() or self._fallback_goal(primary, typical_lines)
        hidden_desire = str(profile.get("hidden_desire", "")).strip()
        direct = any(token in speech_style for token in ("直白", "直接")) or cadence == "short"
        restrained = "克制" in speech_style or ("谨慎" in traits and not direct) or (
            values.get("责任", 5) >= 8 and values.get("勇气", 5) <= 7 and not direct
        )

        if any("更短" in item or "短一些" in item for item in user_edits):
            cadence = "short"
        if any("不轻佻" in item or "不要轻佻" in item for item in user_edits):
            forbidden_behaviors = self._merge_lists(forbidden_behaviors, ["不会轻佻调笑"])

        voice = {
            "name": str(profile.get("name", "角色")).strip() or "角色",
            "traits": traits,
            "values": values,
            "decision_rules": decision_rules,
            "typical_lines": typical_lines,
            "speech_style": speech_style,
            "speech_habits": {
                "cadence": cadence,
                "signature_phrases": signature_phrases,
                "sentence_openers": self._clean_list(speech_habits.get("sentence_openers", []))[:4],
                "connective_tokens": self._clean_list(speech_habits.get("connective_tokens", []))[:4],
                "sentence_endings": self._clean_list(speech_habits.get("sentence_endings", []))[:4],
                "forbidden_fillers": self._clean_list(speech_habits.get("forbidden_fillers", [])),
            },
            "taboo_topics": taboo_topics[:6],
            "forbidden_behaviors": forbidden_behaviors[:6],
            "key_bonds": key_bonds[:4],
            "user_edits": user_edits[:4],
            "notable_interactions": notable_interactions[:4],
            "relationship_updates": relationship_updates[:4],
            "primary_priority": primary,
            "direct": direct,
            "restrained": restrained,
            "worldview": worldview,
            "goal": goal,
            "hidden_desire": hidden_desire,
        }
        for key in self.VOICE_SCALAR_FIELDS:
            voice[key] = str(profile.get(key, "")).strip()
        return voice

    @staticmethod
    def _summarize_style_rules(voice: Dict[str, Any]) -> List[str]:
        habits = voice.get("speech_habits", {})
        rules = [
            f"说话基调: {voice.get('speech_style', '') or '贴近角色既有口吻'}",
            f"句长节奏: {habits.get('cadence', 'medium')}",
            f"表达取向: {'直接' if voice.get('direct') else '含蓄'}",
            f"情绪克制度: {'克制' if voice.get('restrained') else '外露'}",
        ]
        signatures = Speaker._clean_list(habits.get("signature_phrases", []))
        if signatures:
            rules.append("标志语汇: " + " / ".join(signatures[:3]))
        endings = Speaker._clean_list(habits.get("sentence_endings", []))
        if endings:
            rules.append("收尾习惯: " + " / ".join(endings[:2]))
        return [item for item in rules if str(item).strip()]

    @staticmethod
    def _summarize_behavior_rules(profile: Dict[str, Any], voice: Dict[str, Any]) -> List[str]:
        rules: List[str] = []
        for key, label in Speaker.BEHAVIOR_RULE_FIELDS:
            fallback_key = "goal" if key == "soul_goal" else key
            value = str(profile.get(key, "") or voice.get(fallback_key, "")).strip()
            if value:
                rules.append(f"{label}: {value}")
        decision_rules = Speaker._clean_list(voice.get("decision_rules", []))
        if decision_rules:
            rules.append("决策规则: " + " / ".join(decision_rules[:4]))
        taboo_topics = Speaker._clean_list(voice.get("taboo_topics", []))
        if taboo_topics:
            rules.append("禁区话题: " + " / ".join(taboo_topics[:4]))
        forbidden = Speaker._clean_list(voice.get("forbidden_behaviors", []))
        if forbidden:
            rules.append("绝不做: " + " / ".join(forbidden[:4]))
        return rules

    @staticmethod
    def _summarize_relation_rules(
        target_display: str,
        relation_state: Dict[str, Any],
        relation_hint: str,
    ) -> List[str]:
        if not relation_state and not relation_hint and not target_display:
            return []
        rules: List[str] = []
        if target_display:
            rules.append(f"当前主要对象: {target_display}")
        if relation_state:
            trust = relation_state.get("trust", 5)
            affection = relation_state.get("affection", 5)
            hostility = relation_state.get("hostility", max(0, 5 - int(affection)))
            ambiguity = relation_state.get("ambiguity", 3)
            rules.append(f"关系状态: trust={trust}, affection={affection}, hostility={hostility}, ambiguity={ambiguity}")
            for key, label in (
                ("conflict_point", "冲突点"),
                ("typical_interaction", "典型互动"),
                ("relation_change", "关系趋势"),
                ("hidden_attitude", "私下态度"),
            ):
                value = str(relation_state.get(key, "")).strip()
                if value:
                    rules.append(f"{label}: {value}")
        if relation_hint:
            rules.append(f"群体关系概览: {relation_hint}")
        return rules

    @staticmethod
    def _summarize_memory_cues(voice: Dict[str, Any]) -> List[str]:
        cues: List[str] = []
        for key, label in Speaker.MEMORY_CUE_FIELDS:
            values = Speaker._clean_list(voice.get(key, []))
            if values:
                cues.append(f"{label}: {' / '.join(values[:3])}")
        return cues

    def _fallback_reply(
        self,
        topic: str,
        context: str,
        target_display: str,
        voice: Dict[str, Any],
        relation_state: Dict[str, Any],
    ) -> str:
        prefix = self._opening(target_display, voice, relation_state)
        core = self._topic_reply(topic, context, voice, relation_state)
        drive = self._drive_line(voice, topic)
        parts = [part.strip() for part in (prefix, core, drive) if str(part).strip()]
        reply = "。".join(parts)
        if reply and not reply.endswith(("。", "？", "！")):
            reply += "。"
        return reply

    def _opening(self, target_display: str, voice: Dict[str, Any], relation_state: Dict[str, Any]) -> str:
        if target_display:
            affection = self._safe_int(relation_state.get("affection", 5), default=5)
            if affection >= 7:
                return f"{target_display}，这话我听着。"
            return f"{target_display}，你先把话说完。"
        return "这事我听明白了。"

    def _topic_reply(
        self,
        topic: str,
        context: str,
        voice: Dict[str, Any],
        relation_state: Dict[str, Any],
    ) -> str:
        taboo_topics = self._clean_list(voice.get("taboo_topics", []))
        if taboo_topics and any(token in context for token in taboo_topics):
            taboo = taboo_topics[0]
            return f"像“{taboo}”这种话，不能当作寻常话随口一提"

        if topic in {"question", "war", "view"}:
            if voice.get("primary_priority") == "责任":
                return f"依我看，先得把众人和后路安顿住，再定夺怎么往前走"
            if voice.get("primary_priority") == "勇气":
                return f"依我看，真到要紧处就别再躲，能向前就向前"
            return f"依我看，先看局势轻重，再决定这一步能不能做"

        if topic == "care":
            bond = next(iter(self._clean_list(voice.get("key_bonds", []))), target_display)
            return f"人心不安的时候，最要紧的是先把该护住的护住。{bond}这层分量，我心里有数"

        if topic == "rest":
            if voice.get("restrained"):
                return "难得能歇一口气，有些话反倒更该慢慢说"
            return "难得松快些，心里那层紧劲也该放一放"

        hostility = self._safe_int(relation_state.get("hostility", 0), default=0)
        conflict_point = str(relation_state.get("conflict_point", "")).strip()
        if hostility >= 5 and conflict_point:
            return f"只是你我之间还横着{conflict_point}，我不会装作看不见"
        return "话要落地，不能只图一时痛快"

    def _drive_line(self, voice: Dict[str, Any], topic: str) -> str:
        key_bonds = self._clean_list(voice.get("key_bonds", []))
        if topic == "care" and key_bonds:
            return f"我在意的，从来不是嘴上热闹，而是{key_bonds[0]}"
        if topic == "conflict" and str(voice.get("stress_response", "")).strip():
            return str(voice.get("stress_response", "")).strip()
        if topic in {"question", "view", "war"}:
            return self._soften_drive(
                str(voice.get("goal", "")).strip() or str(voice.get("belief_anchor", "")).strip()
            )
        return self._soften_drive(
            str(voice.get("hidden_desire", "")).strip() or str(voice.get("worldview", "")).strip()
        )

    def _classify_topic(self, context: str) -> str:
        text = str(context or "")
        if any(token and token in text for token in self.care_tokens):
            return "care"
        if any(token and token in text for token in self.rest_tokens):
            return "rest"
        if any(token and token in text for token in self.war_tokens):
            return "war"
        if any(token and token in text for token in self.view_tokens):
            return "view"
        if any(token and token in text for token in self.question_tokens) or "？" in text or "?" in text:
            return "question"
        return "general"

    def _infer_cadence(self, speech_style: str, user_edits: List[str], typical_lines: List[str]) -> str:
        if any("更短" in item or "短一些" in item for item in user_edits):
            return "short"
        if "直白" in speech_style or "短句" in speech_style:
            return "short"
        if "铺陈" in speech_style or "缓" in speech_style:
            return "long"
        if typical_lines:
            avg_len = sum(len(item) for item in typical_lines[:3]) / max(1, min(3, len(typical_lines)))
            if avg_len <= 12:
                return "short"
            if avg_len >= 24:
                return "long"
        return "medium"

    def _fallback_worldview(self, primary: str, values: Dict[str, int], typical_lines: List[str]) -> str:
        joined = " ".join(typical_lines[:2])
        if "百姓" in joined or primary == "责任":
            return "先把人和局面稳住，再谈别的"
        if primary == "勇气":
            return "真到要紧处，不能总往后缩"
        if values.get("自由", 5) >= 7:
            return "人不能活成别人手里的棋"
        return "凡事先看轻重和后果"

    def _fallback_goal(self, primary: str, typical_lines: List[str]) -> str:
        joined = " ".join(typical_lines[:2])
        if "百姓" in joined:
            return "把众人安顿下来"
        if primary == "责任":
            return "守住该守的人和局面"
        if primary == "忠诚":
            return "把认下的情分守到最后"
        if primary == "勇气":
            return "真到关键处时能站出来"
        return "把眼前这步走稳"

    @staticmethod
    def _soften_drive(text: str) -> str:
        value = str(text or "").strip()
        if not value:
            return ""
        replacements = (
            ("想把", "总得把"),
            ("放不下的人和情意", "真正舍不得的人"),
            ("守住", "留住"),
            ("守到最后", "撑到最后"),
        )
        for old, new in replacements:
            value = value.replace(old, new)
        return value

    def _extract_signature_phrases(self, typical_lines: List[str]) -> List[str]:
        phrases: List[str] = []
        for line in typical_lines[:4]:
            text = str(line).strip()
            if not text:
                continue
            match = re.match(r"^(.{2,6})", text)
            if match:
                phrases.append(match.group(1))
        return self._merge_lists([], phrases)[:4]

    @staticmethod
    def _clean_list(value: Any) -> List[str]:
        if isinstance(value, list):
            items = value
        else:
            items = re.split(r"[；;]\s*", str(value or "").strip()) if str(value or "").strip() else []
        return [str(item).strip() for item in items if str(item).strip()]

    @staticmethod
    def _merge_lists(base: List[str], incoming: List[str]) -> List[str]:
        merged: List[str] = []
        seen = set()
        for item in list(base) + list(incoming):
            text = str(item).strip()
            if not text or text in seen:
                continue
            merged.append(text)
            seen.add(text)
        return merged

    @staticmethod
    def _safe_int(value: Any, default: int = 0) -> int:
        try:
            return int(value)
        except (TypeError, ValueError):
            return default
