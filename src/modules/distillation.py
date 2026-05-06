#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import logging
import re
from collections import defaultdict
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional, Tuple

from src.core.config import Config
from src.core.contracts import CostEstimator, PathProviderLike, RuleProvider, RuntimePartsLike
from src.modules.distillation_extraction import DistillationExtractionMixin
from src.modules.distillation_hints import DistillationHintsMixin
from src.modules.distillation_inference import DistillationInferenceMixin
from src.modules.distillation_persona_io import DistillationPersonaIOMixin
from src.modules.distillation_profile_builder import DistillationProfileBuilderMixin
from src.modules.distillation_refinement import DistillationRefinementMixin
from src.utils.file_utils import (
    canonical_aliases,
    ensure_dir,
    novel_id_from_input,
)
from src.utils.text_parser import load_novel_text
from src.utils.token_counter import TokenCounter


class NovelDistiller(
    DistillationHintsMixin,
    DistillationExtractionMixin,
    DistillationPersonaIOMixin,
    DistillationProfileBuilderMixin,
    DistillationRefinementMixin,
    DistillationInferenceMixin,
):
    """Generic novel character distillation driven by editable markdown rules."""

    logger = logging.getLogger(__name__)

    CHAPTER_HEADING_PATTERNS = (
        re.compile(r"^第[0-9零一二三四五六七八九十百千两]+章"),
        re.compile(r"^第[0-9零一二三四五六七八九十百千两]+回"),
        re.compile(r"^卷[0-9零一二三四五六七八九十百千两]"),
        re.compile(r"^chapter\s+\d+", flags=re.IGNORECASE),
    )
    DEFAULT_NAV_LOAD_ORDER = (
        "SOUL",
        "GOALS",
        "STYLE",
        "TRAUMA",
        "IDENTITY",
        "BACKGROUND",
        "CAPABILITY",
        "BONDS",
        "CONFLICTS",
        "ROLE",
        "AGENTS",
        "RELATIONS",
        "MEMORY",
    )
    PERSONA_FILE_CATALOG = {
        "SOUL": {
            "optional": False,
            "role": "core values, worldview, boundaries",
            "behaviors": "stance, taboo, refusal, value judgment",
            "write_policy": "manual_edit",
        },
        "GOALS": {
            "optional": True,
            "role": "long-term drive, unresolved desire, strategic priority",
            "behaviors": "decision preference, long arc pressure, ambition",
            "write_policy": "manual_edit",
        },
        "STYLE": {
            "optional": True,
            "role": "signature phrasing, cadence, surface emotion, sample lines",
            "behaviors": "word choice, sentence length, tone, recurring fragments",
            "write_policy": "manual_edit",
        },
        "TRAUMA": {
            "optional": True,
            "role": "pain points, scars, triggers, never-do rules",
            "behaviors": "trigger reactions, avoidance, hard boundaries",
            "write_policy": "manual_edit",
        },
        "IDENTITY": {
            "optional": False,
            "role": "background, lived experience, habits, emotion profile",
            "behaviors": "self-reference, memory framing, habit-driven reactions",
            "write_policy": "manual_edit",
        },
        "BACKGROUND": {
            "optional": True,
            "role": "world identity, faction position, environment imprint, survival context",
            "behaviors": "camp alignment, social rank, environmental pressure, worldview fit",
            "write_policy": "manual_edit",
        },
        "CAPABILITY": {
            "optional": True,
            "role": "strengths, weaknesses, blind spots, action tendency",
            "behaviors": "what the character is good at, where they fail, how they overreach",
            "write_policy": "manual_edit",
        },
        "BONDS": {
            "optional": True,
            "role": "relationship habits, trust boundary, reward-and-resentment logic",
            "behaviors": "how the character treats allies, strangers, enemies, and debts",
            "write_policy": "manual_edit",
        },
        "CONFLICTS": {
            "optional": True,
            "role": "hidden desire, inner contradiction, fear triggers, private self",
            "behaviors": "internal pull, weakness exposure, private vs public self",
            "write_policy": "manual_edit",
        },
        "ROLE": {
            "optional": True,
            "role": "story function, stance stability, world-rule compatibility",
            "behaviors": "plot pressure, pivot role, alignment stability",
            "write_policy": "manual_edit",
        },
        "AGENTS": {
            "optional": False,
            "role": "runtime behavior rules, silence policy, group chat routing",
            "behaviors": "when to speak, when to hold back, how to engage others",
            "write_policy": "manual_edit",
        },
        "RELATIONS": {
            "optional": True,
            "role": "target-specific trust, affection, appellations, friction points",
            "behaviors": "tone toward each character, appellations, conflict framing",
            "write_policy": "manual_edit",
        },
        "MEMORY": {
            "optional": False,
            "role": "stable notes plus runtime write-back from user guidance and corrections",
            "behaviors": "persistent user constraints, correction carry-over, mutable notes",
            "write_policy": "runtime_append",
        },
    }

    DEFAULT_ADDRESS_SUFFIXES = ("哥哥", "姐姐", "妹妹", "弟弟", "姑娘", "公子", "爷")
    DEFAULT_SPEECH_VERBS = ("道", "说道", "笑道", "问道", "答道", "喝道", "叫道", "叹道", "呼道")
    DEFAULT_OBJECT_LEADERS = (
        "叫",
        "唤",
        "问",
        "对",
        "向",
        "同",
        "与",
        "跟",
        "把",
        "将",
        "扯住",
        "拉住",
        "搀起",
        "扶起",
        "扶着",
        "呼",
        "忙呼",
        "喝住",
        "捉住",
        "拿住",
        "推着",
        "拖着",
        "请",
        "教",
    )
    DEFAULT_STOP_NAMES = {
        "我们",
        "你们",
        "他们",
        "她们",
        "自己",
        "那里",
        "这里",
        "这个",
        "那个",
        "一种",
        "一个",
    }
    DEFAULT_COMMON_SURNAMES = (
        "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚"
        "范彭郎鲁韦昌马苗凤花方俞任袁柳鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元"
        "卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强"
        "贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗丁宣贲邓郁"
        "单杭洪包诸左石崔吉钮龚程嵇邢滑裴陆荣翁荀羊惠甄曲家封芮羿储靳汲邴糜松井段富巫乌焦巴弓牧隗山"
        "谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘钭厉戎祖武符刘景詹束龙叶幸司韶郜黎"
    )
    DEFAULT_TRAIT_KEYWORDS = {
        "勇敢": ["勇", "冲", "无畏", "果断"],
        "温柔": ["轻声", "温和", "安慰", "体贴"],
        "聪慧": ["思索", "推断", "聪明", "机敏"],
        "敏感": ["委屈", "难过", "心酸", "叹息"],
        "傲气": ["冷笑", "不屑", "高傲", "轻蔑"],
        "忠诚": ["守护", "忠", "誓言", "不离"],
        "善良": ["帮助", "善意", "宽容", "谅解"],
        "执拗": ["坚持", "非要", "绝不", "固执"],
        "机变": ["变化", "试探", "识破", "周旋"],
        "诙谐": ["笑道", "打趣", "顽皮", "戏弄"],
        "虔诚": ["佛", "祈祷", "经文", "戒律"],
        "沉稳": ["稳住", "接应", "收拾", "不慌"],
        "圆滑": ["不如", "且慢", "何必", "先看看"],
        "克制": ["沉住气", "先忍", "不动声色", "收着"],
    }

    def __init__(
        self,
        config: Optional[Config] = None,
        *,
        llm_client: Optional[CostEstimator] = None,
        token_counter: Optional[TokenCounter] = None,
        rulebook: Optional[RuleProvider] = None,
        path_provider: Optional[PathProviderLike] = None,
    ):
        self.config = config or Config()
        if llm_client is None or token_counter is None or rulebook is None or path_provider is None:
            raise ValueError("NovelDistiller requires injected llm_client, token_counter, rulebook, and path_provider")
        self.path_provider = path_provider
        self.rulebook = rulebook
        self.llm_client = llm_client
        self.token_counter = token_counter
        self._last_chunk_count = 0

        self.address_suffixes = tuple(
            self.rulebook.get("distillation", "address_suffixes", list(self.DEFAULT_ADDRESS_SUFFIXES))
        )
        self.speech_verbs = tuple(
            self.rulebook.get("distillation", "speech_verbs", list(self.DEFAULT_SPEECH_VERBS))
        )
        self.object_leaders = tuple(
            self.rulebook.get("distillation", "object_leaders", list(self.DEFAULT_OBJECT_LEADERS))
        )
        self.stop_names = set(self.rulebook.get("distillation", "stop_names", list(self.DEFAULT_STOP_NAMES)))
        self.common_surnames = str(
            self.rulebook.get("distillation", "common_surnames", self.DEFAULT_COMMON_SURNAMES)
        )
        self.trait_keywords = dict(
            self.rulebook.get("distillation", "trait_keywords", self.DEFAULT_TRAIT_KEYWORDS)
        )
        self.archetypes = dict(self.rulebook.get("distillation", "archetypes", {}))
        self.global_character_hints = dict(self.rulebook.get("distillation", "character_hints", {}))
        self._active_character_hints: Dict[str, Any] = {}
        self.value_markers = dict(self.rulebook.get("distillation", "value_markers", {}))
        speaker_rules = self.rulebook.section("speaker")
        self.generic_fillers = tuple(speaker_rules.get("generic_fillers", []))
        self.signature_fragments = tuple(speaker_rules.get("signature_fragments", []))
        self.opener_patterns = tuple(speaker_rules.get("opener_patterns", []))
        self.connective_patterns = tuple(speaker_rules.get("connective_patterns", []))
        self.ending_patterns = tuple(speaker_rules.get("ending_patterns", []))
        self.fragment_stopwords = {
            str(item).strip() for item in speaker_rules.get("fragment_stopwords", []) if str(item).strip()
        }
        self.preferred_leading_chars = tuple(speaker_rules.get("preferred_leading_chars", []))
        self.preferred_trailing_chars = tuple(speaker_rules.get("preferred_trailing_chars", []))
        self.style_templates = dict(self.rulebook.get("distillation", "style_templates", {}))
        self.decision_rule_signals = dict(self.rulebook.get("distillation", "decision_rule_signals", {}))
        self.taboo_topics_by_value = dict(self.rulebook.get("distillation", "taboo_topics_by_value", {}))
        self.forbidden_behaviors_by_value = dict(
            self.rulebook.get("distillation", "forbidden_behaviors_by_value", {})
        )
        self.second_pass_mode = str(self.config.get("distillation.second_pass_mode", "auto")).strip().lower()
        self.refinement_batch_size = max(1, int(self.config.get("distillation.refinement_batch_size", 4) or 4))
        self.stage_window_size = int(self.config.get("distillation.stage_window_size", 6))
        self.llm_evidence_lines_per_stage = int(self.config.get("distillation.llm_evidence_lines_per_stage", 6))
        self._second_pass_disabled_reason = ""

    @classmethod
    def from_runtime_parts(cls, parts: RuntimePartsLike) -> "NovelDistiller":
        return cls(
            parts.config,
            llm_client=parts.llm,
            token_counter=parts.token_counter,
            rulebook=parts.rulebook,
            path_provider=parts.path_provider,
        )

    def estimate_cost(self, novel_path: str) -> float:
        text = self.prepare_novel_text(load_novel_text(novel_path))
        chunks = self._chunk_text(text)
        self._last_chunk_count = len(chunks)
        avg_chunk_tokens = self.token_counter.count(text) / max(1, len(chunks))
        total_prompt_tokens = int(len(chunks) * (avg_chunk_tokens + 250))
        synthetic_prompt = "x" * max(10, total_prompt_tokens // 2)
        return self.llm_client.estimate_cost(synthetic_prompt, expected_completion_ratio=0.35)

    def get_last_chunk_count(self) -> int:
        return self._last_chunk_count

    def distill(
        self,
        novel_path: str,
        characters: Optional[List[str]] = None,
        output_dir: Optional[str] = None,
        progress_callback: Optional[Callable[[str, Dict[str, Any]], None]] = None,
    ) -> Dict[str, Dict[str, Any]]:
        text = self.prepare_novel_text(load_novel_text(novel_path))
        chunks = self._chunk_text(text)
        self._last_chunk_count = len(chunks)
        novel_id = novel_id_from_input(novel_path)
        self._active_character_hints = self._load_novel_character_hints(novel_id)
        self._emit_progress(
            progress_callback,
            "text_loaded",
            novel_id=novel_id,
            chunk_count=len(chunks),
        )

        try:
            target_characters = [item.strip() for item in characters or [] if item.strip()] or self.extract_top_characters(text)
            if not target_characters:
                raise ValueError("No character candidates were extracted from the novel text")
            self._emit_progress(
                progress_callback,
                "characters_ready",
                novel_id=novel_id,
                characters=list(target_characters),
                total=len(target_characters),
            )

            alias_map = self.build_alias_map(text, target_characters, allow_sparse_alias=bool(characters))
            aggregated = {name: self._empty_bucket() for name in target_characters}
            arc_points: Dict[str, List[Tuple[int, Dict[str, int]]]] = defaultdict(list)

            for idx, chunk in enumerate(chunks):
                chunk_evidence, chunk_values = self._extract_from_chunk(chunk, alias_map)
                for name in target_characters:
                    evidence = chunk_evidence.get(name)
                    if not evidence:
                        continue
                    bucket = aggregated[name]
                    bucket["descriptions"].extend(evidence["descriptions"])
                    bucket["dialogues"].extend(evidence["dialogues"])
                    bucket["thoughts"].extend(evidence["thoughts"])
                    bucket["timeline"].append(
                        {
                            "index": idx,
                            "descriptions": list(evidence["descriptions"]),
                            "dialogues": list(evidence["dialogues"]),
                            "thoughts": list(evidence["thoughts"]),
                        }
                    )
                    arc_points[name].append((idx, chunk_values.get(name, {})))

            out_dir = ensure_dir(Path(output_dir) if output_dir else self.path_provider.characters_root(novel_id))
            draft_profiles: Dict[str, Dict[str, Any]] = {}
            for name in target_characters:
                self._emit_progress(
                    progress_callback,
                    "drafting_character",
                    novel_id=novel_id,
                    character=name,
                    index=len(draft_profiles) + 1,
                    total=len(target_characters),
                )
                profile = self._build_profile(name, aggregated[name], arc_points.get(name, []))
                profile["novel_id"] = novel_id
                profile["source_path"] = novel_path
                profile["evidence"] = {
                    "description_count": len(aggregated[name]["descriptions"]),
                    "dialogue_count": len(aggregated[name]["dialogues"]),
                    "thought_count": len(aggregated[name]["thoughts"]),
                    "chunk_count": len(arc_points.get(name, [])),
                }
                draft_profiles[name] = profile

            profiles: Dict[str, Dict[str, Any]] = {}
            second_pass_notice_emitted = False
            for batch in self._character_batches(target_characters):
                batch_profiles = {name: draft_profiles[name] for name in batch}
                for name in batch:
                    self._emit_progress(
                        progress_callback,
                        "refining_character",
                        novel_id=novel_id,
                        character=name,
                        index=len(profiles) + 1,
                        total=len(target_characters),
                    )
                    profile = self._refine_profile_with_llm(
                        draft_profiles[name],
                        bucket=aggregated[name],
                        arc_values=arc_points.get(name, []),
                        peer_profiles={peer_name: peer for peer_name, peer in batch_profiles.items() if peer_name != name},
                        overlap_report=self._collect_profile_overlap(draft_profiles[name], batch_profiles),
                    )
                    if self._second_pass_disabled_reason and not second_pass_notice_emitted:
                        self._emit_progress(
                            progress_callback,
                            "second_pass_disabled",
                            novel_id=novel_id,
                            character=name,
                            reason=self._second_pass_disabled_reason,
                        )
                        second_pass_notice_emitted = True
                    profile["novel_id"] = novel_id
                    profile["source_path"] = novel_path
                    profiles[name] = profile
                    self._export_persona_bundle(out_dir, profile)
                    self._emit_progress(
                        progress_callback,
                        "character_done",
                        novel_id=novel_id,
                        character=name,
                        index=len(profiles),
                        total=len(target_characters),
                        output_dir=str(out_dir),
                    )
            self._emit_progress(
                progress_callback,
                "distill_done",
                novel_id=novel_id,
                total=len(profiles),
                output_dir=str(out_dir),
            )
            return profiles
        finally:
            self._active_character_hints = {}

    @staticmethod
    def _emit_progress(
        callback: Optional[Callable[[str, Dict[str, Any]], None]],
        stage: str,
        **payload: Any,
    ) -> None:
        if callback is None:
            return
        callback(stage, payload)

    def extract_top_characters(self, text: str) -> List[str]:
        return self._extract_top_characters(self.prepare_novel_text(text))

    def build_alias_map(
        self,
        text: str,
        character_names: List[str],
        allow_sparse_alias: bool = False,
    ) -> Dict[str, List[str]]:
        return self._build_alias_map(self.prepare_novel_text(text), character_names, allow_sparse_alias=allow_sparse_alias)

    def text_mentions_any_alias(self, text: str, aliases: List[str]) -> bool:
        return self._text_mentions_any_alias(text, aliases)

    def candidate_aliases(self, name: str) -> List[str]:
        clean = str(name or "").strip()
        aliases: List[str] = []
        aliases.extend(canonical_aliases(clean))
        if len(clean) >= 3:
            given = clean[-2:]
            if given != clean:
                aliases.append(given)
                for suffix in self.address_suffixes:
                    aliases.append(f"{given[0]}{suffix}")
                    aliases.append(f"{clean[0]}{suffix}")
        elif len(clean) == 2:
            for suffix in self.address_suffixes:
                aliases.append(f"{clean[0]}{suffix}")
        return self._unique_texts(item for item in aliases if item and item != clean)

    def _character_batches(self, names: List[str]) -> List[List[str]]:
        batch_size = max(1, self.refinement_batch_size)
        return [names[index : index + batch_size] for index in range(0, len(names), batch_size)]


    def prepare_novel_text(self, text: str) -> str:
        return self._prepare_novel_text(text)

    def _chunk_text(self, text: str) -> List[str]:
        size = int(self.config.get("text_processing.chunk_size_tokens", 8000))
        overlap = int(self.config.get("text_processing.chunk_overlap_tokens", 200))
        return self.token_counter.split_by_tokens(text, size, overlap)
        return self._apply_character_hint(profile, character_hint)

