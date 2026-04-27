#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

from src.core.config import Config
from src.core.contracts import CostEstimator, PathProviderLike, RuleProvider
from src.utils.file_utils import canonical_aliases, ensure_dir, novel_id_from_input, safe_filename
from src.utils.text_parser import load_novel_text, split_sentences
from src.utils.token_counter import TokenCounter


class NovelDistiller:
    """Generic novel character distillation driven by editable markdown rules."""

    DEFAULT_NAV_LOAD_ORDER = ("SOUL", "GOALS", "STYLE", "TRAUMA", "IDENTITY", "AGENTS", "RELATIONS", "MEMORY")
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
        self.value_markers = dict(self.rulebook.get("distillation", "value_markers", {}))
        speaker_rules = self.rulebook.section("speaker")
        self.generic_fillers = tuple(speaker_rules.get("generic_fillers", []))
        self.signature_fragments = tuple(speaker_rules.get("signature_fragments", []))
        self.style_templates = dict(self.rulebook.get("distillation", "style_templates", {}))
        self.taboo_topics_by_value = dict(self.rulebook.get("distillation", "taboo_topics_by_value", {}))
        self.forbidden_behaviors_by_value = dict(
            self.rulebook.get("distillation", "forbidden_behaviors_by_value", {})
        )

    def estimate_cost(self, novel_path: str) -> float:
        text = load_novel_text(novel_path)
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
    ) -> Dict[str, Dict[str, Any]]:
        text = load_novel_text(novel_path)
        chunks = self._chunk_text(text)
        self._last_chunk_count = len(chunks)
        novel_id = novel_id_from_input(novel_path)

        target_characters = [item.strip() for item in characters or [] if item.strip()] or self.extract_top_characters(text)
        if not target_characters:
            raise ValueError("No character candidates were extracted from the novel text")

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
                arc_points[name].append((idx, chunk_values.get(name, {})))

        out_dir = ensure_dir(Path(output_dir) if output_dir else self.path_provider.characters_root(novel_id))
        profiles: Dict[str, Dict[str, Any]] = {}
        for name in target_characters:
            profile = self._build_profile(name, aggregated[name], arc_points.get(name, []))
            profile["novel_id"] = novel_id
            profile["source_path"] = novel_path
            profile["evidence"] = {
                "description_count": len(aggregated[name]["descriptions"]),
                "dialogue_count": len(aggregated[name]["dialogues"]),
                "thought_count": len(aggregated[name]["thoughts"]),
                "chunk_count": len(arc_points.get(name, [])),
            }
            profiles[name] = profile
            self._export_persona_bundle(out_dir, profile)
        return profiles

    def extract_top_characters(self, text: str) -> List[str]:
        return self._extract_top_characters(text)

    def build_alias_map(
        self,
        text: str,
        character_names: List[str],
        allow_sparse_alias: bool = False,
    ) -> Dict[str, List[str]]:
        return self._build_alias_map(text, character_names, allow_sparse_alias=allow_sparse_alias)

    def text_mentions_any_alias(self, text: str, aliases: List[str]) -> bool:
        return self._text_mentions_any_alias(text, aliases)

    def refresh_navigation(self, persona_dir: Path, character_name: str) -> None:
        self.refresh_persona_navigation(persona_dir, character_name)

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

    def _chunk_text(self, text: str) -> List[str]:
        size = int(self.config.get("text_processing.chunk_size_tokens", 8000))
        overlap = int(self.config.get("text_processing.chunk_overlap_tokens", 200))
        return self.token_counter.split_by_tokens(text, size, overlap)

    def _extract_top_characters(self, text: str) -> List[str]:
        name_pattern = re.compile(rf"([{self.common_surnames}][\u4e00-\u9fff]{{1,2}})")
        raw_names: List[str] = []
        for match in name_pattern.finditer(text):
            start = match.start()
            if start > 0 and "\u4e00" <= text[start - 1] <= "\u9fff":
                continue
            raw_names.append(match.group(1))

        disallowed = set("你我他她它们的了得地着过吗呀啊呢就在和并与把被让向对将又很都并且")
        candidates = []
        for name in raw_names:
            if name in self.stop_names or len(name) < 2 or len(name) > 3:
                continue
            if any(ch in disallowed for ch in name[1:]):
                continue
            candidates.append(name)

        counts = Counter(candidates)
        filtered = self._pick_frequent_names(counts, min_count=int(self.config.get("distillation.min_appearances", 3)))
        if not filtered:
            filtered = self._pick_frequent_names(counts, min_count=2)
        if not filtered:
            filtered = self._pick_frequent_names(counts, min_count=1)

        if len(filtered) < 3:
            alias_candidates = re.findall(r"[\u4e00-\u9fff]{2}(?:儿|爷|姐|妹|兄|玉|钗)", text)
            for alias, count in Counter(alias_candidates).most_common(10):
                if count < 2 or alias in self.stop_names or alias in filtered:
                    continue
                filtered.append(alias)

        return filtered[: int(self.config.get("distillation.max_characters", 10))]

    def _pick_frequent_names(self, counts: Counter[str], min_count: int) -> List[str]:
        filtered: List[str] = []
        for name, count in counts.most_common(60):
            if count < min_count:
                continue
            if self._looks_like_name(name):
                filtered.append(name)
        return filtered

    def _build_alias_map(
        self,
        text: str,
        character_names: List[str],
        allow_sparse_alias: bool = False,
    ) -> Dict[str, List[str]]:
        alias_owners: Dict[str, List[str]] = defaultdict(list)
        for name in character_names:
            for alias in self.candidate_aliases(name):
                alias_owners[alias].append(name)

        alias_map: Dict[str, List[str]] = {}
        for name in character_names:
            aliases = [name]
            for alias in self.candidate_aliases(name):
                if alias_owners.get(alias) != [name]:
                    continue
                if not self._alias_is_reliable(text, alias, allow_sparse_alias=allow_sparse_alias):
                    continue
                aliases.append(alias)
            alias_map[name] = self._unique_texts(aliases)
        return alias_map

    def _alias_is_reliable(self, text: str, alias: str, allow_sparse_alias: bool = False) -> bool:
        if len(alias) < 2 or alias in self.stop_names:
            return False
        min_mentions = 1 if allow_sparse_alias else 2
        return self._count_token_mentions(text, alias) >= min_mentions

    def _extract_from_chunk(
        self,
        chunk: str,
        alias_map: Dict[str, List[str]],
    ) -> Tuple[Dict[str, Dict[str, List[str]]], Dict[str, Dict[str, int]]]:
        sentences = split_sentences(chunk)
        evidence_map: Dict[str, Dict[str, List[str]]] = {}
        value_map: Dict[str, Dict[str, int]] = {}
        dims = self._value_dimensions()

        for name, aliases in alias_map.items():
            evidence = self._empty_bucket()
            values_acc: List[Dict[str, int]] = []

            for idx, sentence in enumerate(sentences):
                prev_sent = sentences[idx - 1] if idx > 0 else ""
                next_sent = sentences[idx + 1] if idx + 1 < len(sentences) else ""
                contains_name = self._text_mentions_any_alias(sentence, aliases)
                pronoun_hit = any(token in sentence for token in ("他", "她")) and (
                    self._text_mentions_any_alias(prev_sent, aliases) or self._text_mentions_any_alias(next_sent, aliases)
                )
                has_quote = "“" in sentence or "\"" in sentence
                speaker_hit = has_quote and self._is_likely_spoken_by(sentence, aliases, prev_sent, next_sent)

                if not (contains_name or pronoun_hit or speaker_hit):
                    continue

                if has_quote and speaker_hit:
                    spoken = self._extract_spoken_content(sentence, aliases, prev_sent, next_sent)
                    if spoken:
                        evidence["dialogues"].append(spoken)
                        values_acc.append(self._score_values(spoken, dims))
                    continue

                if any(token in sentence for token in ("心想", "想着", "觉得", "暗道", "心里")):
                    evidence["thoughts"].append(sentence)
                else:
                    evidence["descriptions"].append(sentence)
                values_acc.append(self._score_values(sentence, dims))

            if any(evidence.values()):
                evidence_map[name] = {
                    key: self._dedupe_texts(items, limit=24 if key == "descriptions" else 12)
                    for key, items in evidence.items()
                }
                value_map[name] = self._average_values(values_acc, dims)

        return evidence_map, value_map

    def _score_values(self, sentence: str, dims: List[str]) -> Dict[str, int]:
        score = {dim: 5 for dim in dims}
        for dim in dims:
            config = self.value_markers.get(dim, {})
            positive = sum(sentence.count(token) for token in config.get("positive", []))
            negative = sum(sentence.count(token) for token in config.get("negative", []))
            delta = min(3, positive) - min(3, negative)
            score[dim] = max(1, min(10, score[dim] + delta))
        return score

    @staticmethod
    def _average_values(values_list: List[Dict[str, int]], dims: List[str]) -> Dict[str, int]:
        if not values_list:
            return {dim: 5 for dim in dims}
        averaged: Dict[str, int] = {}
        for dim in dims:
            averaged[dim] = int(round(sum(item.get(dim, 5) for item in values_list) / len(values_list)))
        return averaged

    def _build_profile(
        self,
        name: str,
        bucket: Dict[str, List[str]],
        arc_values: List[Tuple[int, Dict[str, int]]],
    ) -> Dict[str, Any]:
        descriptions = self._dedupe_texts(bucket["descriptions"], 24)
        dialogues = self._dedupe_texts(bucket["dialogues"], 8)
        thoughts = self._dedupe_texts(bucket["thoughts"], 12)
        archetype = self._infer_archetype(name, descriptions, dialogues, thoughts)
        values = self._infer_values_from_corpus(self._merge_arc_values(arc_values), descriptions, dialogues, thoughts, archetype)
        core_traits = self._infer_traits(descriptions + dialogues + thoughts, archetype)
        speech_style = self._infer_speech_style(dialogues, archetype)
        decision_rules = self._infer_decision_rules(thoughts, descriptions, dialogues, archetype)
        arc = self._build_arc(arc_values, values)
        identity_anchor = self._infer_identity_anchor(core_traits, values, decision_rules, archetype)
        soul_goal = self._infer_soul_goal(values, core_traits, archetype)
        life_experience = self._infer_life_experience(descriptions, dialogues, thoughts, decision_rules, values, archetype)
        worldview = self._infer_worldview(values, core_traits, archetype)
        thinking_style = self._infer_thinking_style(values, core_traits, speech_style, archetype)
        speech_habits = self._infer_speech_habits(dialogues, speech_style)
        emotion_profile = self._infer_emotion_profile(dialogues, thoughts, speech_style, core_traits)
        taboo_topics = self._infer_taboo_topics(values, core_traits, decision_rules)
        forbidden_behaviors = self._infer_forbidden_behaviors(values, core_traits, speech_style)

        return {
            "name": name,
            "core_traits": core_traits[: int(self.config.get("distillation.traits_max_count", 10))],
            "values": values,
            "speech_style": speech_style,
            "typical_lines": dialogues[:8],
            "decision_rules": decision_rules[:8],
            "identity_anchor": identity_anchor,
            "soul_goal": soul_goal,
            "life_experience": life_experience[:4],
            "worldview": worldview,
            "thinking_style": thinking_style,
            "speech_habits": speech_habits,
            "emotion_profile": emotion_profile,
            "taboo_topics": taboo_topics[:6],
            "forbidden_behaviors": forbidden_behaviors[:6],
            "arc": arc,
            "archetype": archetype,
        }

    def _infer_traits(self, lines: List[str], archetype: str) -> List[str]:
        if not lines:
            return self._apply_archetype_traits(["克制", "复杂"], archetype)
        corpus = " ".join(lines)
        hits: List[Tuple[str, int]] = []
        for trait, markers in self.trait_keywords.items():
            score = sum(corpus.count(token) for token in markers)
            if score > 0:
                hits.append((trait, score))
        hits.sort(key=lambda item: item[1], reverse=True)
        base_traits = [trait for trait, _ in hits[:8]] or ["谨慎", "多思"]
        return self._apply_archetype_traits(base_traits, archetype)

    def _infer_archetype(
        self,
        name: str,
        descriptions: List[str],
        dialogues: List[str],
        thoughts: List[str],
    ) -> str:
        corpus = " ".join([name] + descriptions[:10] + dialogues[:10] + thoughts[:10])
        best_name = "default"
        best_score = 0
        for archetype_name, config in self.archetypes.items():
            markers = [str(item).strip() for item in config.get("markers", []) if str(item).strip()]
            score = sum(corpus.count(marker) for marker in markers)
            if score > best_score:
                best_name = archetype_name
                best_score = score
        return best_name if best_score > 0 else "default"

    def _apply_archetype_traits(self, traits: List[str], archetype: str) -> List[str]:
        configured = self.archetypes.get(archetype, {}).get("traits", [])
        return self._unique_texts(list(traits) + [str(item).strip() for item in configured if str(item).strip()])[:10]

    def _infer_values_from_corpus(
        self,
        values: Dict[str, int],
        descriptions: List[str],
        dialogues: List[str],
        thoughts: List[str],
        archetype: str,
    ) -> Dict[str, int]:
        dims = self._value_dimensions()
        corpus = " ".join(descriptions + dialogues + thoughts)
        merged = {dim: int(values.get(dim, 5)) for dim in dims}
        for dim in dims:
            config = self.value_markers.get(dim, {})
            positive = sum(corpus.count(token) for token in config.get("positive", []))
            negative = sum(corpus.count(token) for token in config.get("negative", []))
            delta = min(3, positive) - min(3, negative)
            merged[dim] = max(1, min(10, merged.get(dim, 5) + delta))
        for dim, bias in self.archetypes.get(archetype, {}).get("value_bias", {}).items():
            if dim not in merged:
                merged[dim] = 5
            merged[dim] = max(1, min(10, merged[dim] + int(bias)))
        return merged

    def _merge_arc_values(self, arc_values: List[Tuple[int, Dict[str, int]]]) -> Dict[str, int]:
        dims = self._value_dimensions()
        if not arc_values:
            return {dim: 5 for dim in dims}
        merged = defaultdict(list)
        for _, values in arc_values:
            for dim in dims:
                merged[dim].append(int(values.get(dim, 5)))
        return {dim: int(round(sum(items) / len(items))) for dim, items in merged.items()}

    def _build_arc(
        self,
        arc_values: List[Tuple[int, Dict[str, int]]],
        fallback_values: Dict[str, int],
    ) -> Dict[str, Any]:
        if not arc_values:
            return {
                "start": fallback_values,
                "mid": {**fallback_values, "trigger_event": "信息不足"},
                "end": {**fallback_values, "final_state": "信息不足"},
            }

        ordered = sorted(arc_values, key=lambda item: item[0])
        start = ordered[0][1] or fallback_values
        mid = ordered[len(ordered) // 2][1] or fallback_values
        end = ordered[-1][1] or fallback_values
        return {
            "start": start,
            "mid": {**mid, "trigger_event": "关键关系或冲突推动"},
            "end": {**end, "final_state": "阶段性收束"},
        }

    def _infer_speech_style(self, lines: List[str], archetype: str) -> str:
        configured = str(self.archetypes.get(archetype, {}).get("speech_style", "")).strip()
        if configured:
            return configured
        if not lines:
            return self.style_templates.get("quiet", "发言偏少，更多通过态度和分寸表明立场。")
        avg_len = sum(len(item) for item in lines) / max(1, len(lines))
        exclaim_ratio = sum(1 for item in lines if any(token in item for token in ("！", "!", "？", "?")))
        if avg_len <= 12:
            return self.style_templates.get("short_direct", "句式偏短，较少铺垫，态度来得直接。")
        if exclaim_ratio >= max(1, len(lines) // 3):
            return self.style_templates.get("emotional", "情绪浮在表面，回应时容易带出锋芒或波动。")
        if avg_len >= 26:
            return self.style_templates.get("long_reflective", "句式较长，喜欢把轻重和前因后果慢慢展开。")
        return self.style_templates.get("balanced", "表达有分寸，既不极短，也不刻意铺陈。")

    def _infer_decision_rules(
        self,
        thoughts: List[str],
        descriptions: List[str],
        dialogues: List[str],
        archetype: str,
    ) -> List[str]:
        rules = [str(item).strip() for item in self.archetypes.get(archetype, {}).get("decision_rules", []) if str(item).strip()]
        for line in thoughts[:12] + descriptions[:12]:
            if "如果" in line and "就" in line:
                rules.append("遇到关键转折时，会先稳住最核心的立场。")
            elif any(token in line for token in ("保护", "守", "帮", "护")):
                rules.append("自己人在眼前受压时，倾向主动介入。")
            elif any(token in line for token in ("退", "避", "沉默", "按住")):
                rules.append("局势失控时，会先收住表达再判断后势。")
        joined = "".join(dialogues[:8])
        if any(token in joined for token in ("先", "且慢", "慢些", "等等")):
            rules.append("不会一上来把话说死，通常会先留一步判断。")
        if any(token in joined for token in ("不可", "不能", "休得", "岂可")):
            rules.append("遇到底线问题时，会明显收紧语气并立即表态。")
        if not rules:
            rules.append("高压情境下，会先判断关系和后果，再决定动作。")
        return self._dedupe_texts(rules, 8)

    def _infer_identity_anchor(
        self,
        core_traits: List[str],
        values: Dict[str, int],
        decision_rules: List[str],
        archetype: str,
    ) -> str:
        configured = str(self.archetypes.get(archetype, {}).get("identity_anchor", "")).strip()
        if configured:
            return configured
        top_value = self._top_dimensions(values, count=2)
        if "责任" in top_value:
            return "遇到局面时，习惯先把担子接住的人"
        if "忠诚" in top_value:
            return "把信义和跟随关系看得很重的人"
        if "正义" in top_value:
            return "先分是非，再谈利害的人"
        if "智慧" in top_value or "谨慎" in core_traits:
            return "凡事先探虚实和后势的人"
        if any("自己人" in rule for rule in decision_rules):
            return "见不得身边人独自受压的人"
        return "不会轻率交出真实态度的人"

    def _infer_soul_goal(self, values: Dict[str, int], core_traits: List[str], archetype: str) -> str:
        configured = str(self.archetypes.get(archetype, {}).get("soul_goal", "")).strip()
        if configured:
            return configured
        top_value = self._top_dimensions(values, count=1)[0]
        mapping = {
            "责任": "把眼前的人和局面尽量稳住，不让局势轻易散掉",
            "忠诚": "守住已经认下的承诺与关系，不轻易失信",
            "正义": "把轻重和是非摆正，不让局势被歪理带偏",
            "智慧": "先看清局势再动手，尽量少走弯路",
            "勇气": "真到要紧处，愿意先一步站到前面",
            "善良": "尽量少伤人心，也少伤无辜之人",
            "自由": "不给自己和身边人活成任人摆布的棋子",
            "野心": "借势把局面推向更远的位置，而不止是应付眼前",
        }
        if "执拗" in core_traits and "正义" not in mapping:
            return "认准了就要做到底，不愿轻易退回去"
        return mapping.get(top_value, "把事情看透，再把自己真正想守的东西守住")

    def _infer_life_experience(
        self,
        descriptions: List[str],
        dialogues: List[str],
        thoughts: List[str],
        decision_rules: List[str],
        values: Dict[str, int],
        archetype: str,
    ) -> List[str]:
        configured = self.archetypes.get(archetype, {}).get("life_experience", "")
        lines = [str(configured).strip()] if str(configured).strip() else []
        corpus = " ".join(descriptions[:6] + thoughts[:6])
        if any(token in corpus for token in ("旧事", "往年", "从前", "昔日")):
            lines.append("过往经历仍在影响当下的分寸和判断。")
        if any("先收住" in rule or "后势" in rule for rule in decision_rules):
            lines.append("见过局势反覆之后，更少只凭一时热气定夺。")
        if values.get("责任", 5) >= 8:
            lines.append("这些经历让他更习惯替旁人托底，而不是只顾自己。")
        if values.get("善良", 5) >= 8:
            lines.append("看过人心冷暖之后，更不愿把无辜者推到前面。")
        if not lines:
            lines.append("经历过人情与局势的反覆，因此很少只看眼前这一层。")
        return self._dedupe_texts(lines, 4)

    def _infer_worldview(self, values: Dict[str, int], core_traits: List[str], archetype: str) -> str:
        configured = str(self.archetypes.get(archetype, {}).get("worldview", "")).strip()
        if configured:
            return configured
        top_value = self._top_dimensions(values, count=2)
        if "忠诚" in top_value:
            return "先看人是否可靠，再看事值不值得做。"
        if "正义" in top_value:
            return "是非若站不稳，利益再大也不该轻动。"
        if "智慧" in top_value:
            return "世事最怕只看一面，虚实和后势都要算进去。"
        if "责任" in top_value or "善良" in top_value:
            return "局面再乱，也不能把身边人与无辜者轻易丢下。"
        if "谨慎" in core_traits:
            return "先看清，再落子，宁慢一步，不乱一步。"
        return "说话做事都不能只图一时痛快，还得顾后果。"

    def _infer_thinking_style(
        self,
        values: Dict[str, int],
        core_traits: List[str],
        speech_style: str,
        archetype: str,
    ) -> str:
        configured = str(self.archetypes.get(archetype, {}).get("thinking_style", "")).strip()
        if configured:
            return configured
        top_value = self._top_dimensions(values, count=1)[0]
        if top_value == "智慧" or "谨慎" in core_traits:
            return "先拆局势，再定立场。"
        if top_value in {"忠诚", "正义"}:
            return "先问对错与名分，再谈成败。"
        if top_value == "勇气":
            return "先看该不该顶上，再看怎么顶。"
        if "敏感" in core_traits:
            return "先感受人心冷暖，再决定把话说到几分。"
        if "直白" in speech_style:
            return "先抓最要紧的一点，直接给态度。"
        return "先稳住分寸，再把轻重说清。"

    def _infer_speech_habits(self, dialogues: List[str], speech_style: str) -> Dict[str, Any]:
        cadence = "medium"
        if "句式偏短" in speech_style or "直白" in speech_style:
            cadence = "short"
        elif "句式较长" in speech_style or "铺陈" in speech_style:
            cadence = "long"

        signature_phrases: List[str] = []
        for line in dialogues[:6]:
            for fragment in self.signature_fragments:
                if fragment in line and fragment not in signature_phrases:
                    signature_phrases.append(fragment)
        if not signature_phrases:
            signature_phrases = self._extract_signature_phrases(dialogues)

        return {
            "cadence": cadence,
            "signature_phrases": signature_phrases[:4],
            "forbidden_fillers": list(self.generic_fillers),
        }

    @staticmethod
    def _infer_emotion_profile(
        dialogues: List[str],
        thoughts: List[str],
        speech_style: str,
        core_traits: List[str],
    ) -> Dict[str, Any]:
        anger = "怒时会先压住锋芒，说话更冷更短。" if "克制" in speech_style else "怒时会把边界和态度讲得更硬。"
        joy = "高兴时也不轻浮，只会略略放松语气。" if "克制" in speech_style else "高兴时语气会明显松快一些。"
        grievance = "受委屈时多半先忍住，不肯立刻摊开。" if "敏感" in core_traits else "受委屈时会把态度说得更直。"
        if any("叹" in item for item in thoughts[:6]):
            grievance = "受屈时往往先把情绪收在心里，再慢慢露出来。"
        return {
            "anger_style": anger,
            "joy_style": joy,
            "grievance_style": grievance,
        }

    def _infer_taboo_topics(
        self,
        values: Dict[str, int],
        core_traits: List[str],
        decision_rules: List[str],
    ) -> List[str]:
        topics: List[str] = []
        for value_name, configured_topics in self.taboo_topics_by_value.items():
            if values.get(value_name, 5) >= 8:
                topics.extend(str(item).strip() for item in configured_topics if str(item).strip())
        if "敏感" in core_traits:
            topics.append("拿人心取笑")
        if any("自己人" in rule for rule in decision_rules):
            topics.append("牺牲自己人")
        return self._dedupe_texts(topics, 6)

    def _infer_forbidden_behaviors(
        self,
        values: Dict[str, int],
        core_traits: List[str],
        speech_style: str,
    ) -> List[str]:
        bans: List[str] = []
        for value_name, configured_bans in self.forbidden_behaviors_by_value.items():
            if values.get(value_name, 5) >= 8:
                bans.extend(str(item).strip() for item in configured_bans if str(item).strip())
        if "克制" in speech_style:
            bans.append("不会无缘无故撒泼失态")
        if "谨慎" in core_traits:
            bans.append("不会在虚实未明时把话说死")
        return self._dedupe_texts(bans, 6)

    def _export_persona_bundle(self, out_dir: Path, profile: Dict[str, Any]) -> None:
        char_dir = ensure_dir(out_dir / safe_filename(profile.get("name", "unnamed")))
        profile_content = self._render_profile_md(profile)
        (char_dir / "PROFILE.generated.md").write_text(profile_content, encoding="utf-8")
        editable_profile = char_dir / "PROFILE.md"
        if not editable_profile.exists():
            editable_profile.write_text(profile_content, encoding="utf-8")

        bundle = {
            "SOUL": self._render_soul_md(profile),
            "IDENTITY": self._render_identity_md(profile),
            "AGENTS": self._render_agents_md(profile),
            "MEMORY": self._render_memory_md(profile),
        }
        if self._should_create_goals_md(profile):
            bundle["GOALS"] = self._render_goals_md(profile)
        if self._should_create_style_md(profile):
            bundle["STYLE"] = self._render_style_md(profile)
        if self._should_create_trauma_md(profile):
            bundle["TRAUMA"] = self._render_trauma_md(profile)

        for base_name, content in bundle.items():
            generated = char_dir / f"{base_name}.generated.md"
            generated.write_text(content, encoding="utf-8")
            editable = char_dir / f"{base_name}.md"
            if not editable.exists():
                editable.write_text(content, encoding="utf-8")

        self.refresh_persona_navigation(char_dir, str(profile.get("name", "")))

    @classmethod
    def refresh_persona_navigation(cls, persona_dir: Path, character_name: str) -> None:
        generated = persona_dir / "NAVIGATION.generated.md"
        generated.write_text(cls._render_navigation_generated_md(persona_dir, character_name), encoding="utf-8")
        editable = persona_dir / "NAVIGATION.md"
        if not editable.exists():
            editable.write_text(cls._render_navigation_override_md(), encoding="utf-8")

    @classmethod
    def _render_navigation_generated_md(cls, persona_dir: Path, character_name: str) -> str:
        active_order = [
            base_name for base_name in cls.DEFAULT_NAV_LOAD_ORDER if cls._persona_file_is_active(persona_dir, base_name)
        ]
        if not active_order:
            active_order = ["SOUL", "IDENTITY", "AGENTS", "MEMORY"]

        lines = [
            "# NAVIGATION",
            "<!-- Runtime entrypoint. Read this file first, then follow load_order. -->",
            "",
            "## Runtime",
            f"- character: {character_name}",
            f"- load_order: {' -> '.join(active_order)}",
            "- first_read: NAVIGATION.generated.md -> NAVIGATION.md overrides",
            "- write_back: MEMORY handles durable user guidance and corrections; RELATIONS handles target-specific manual edits",
            "",
        ]
        for base_name in cls.DEFAULT_NAV_LOAD_ORDER:
            meta = cls.PERSONA_FILE_CATALOG.get(base_name, {})
            lines.extend(
                [
                    f"## {base_name}",
                    f"- status: {'active' if cls._persona_file_is_active(persona_dir, base_name) else 'inactive'}",
                    f"- optional: {'yes' if meta.get('optional', True) else 'no'}",
                    f"- file: {base_name}.md",
                    f"- fallback: {base_name}.generated.md",
                    f"- present: {'yes' if cls._persona_file_exists(persona_dir, base_name) else 'no'}",
                    f"- role: {meta.get('role', '')}",
                    f"- behaviors: {meta.get('behaviors', '')}",
                    f"- write_policy: {meta.get('write_policy', 'manual_edit')}",
                    "",
                ]
            )
        return "\n".join(lines).rstrip() + "\n"

    @staticmethod
    def _render_navigation_override_md() -> str:
        return (
            "# NAVIGATION\n"
            "<!-- Optional overrides for the generated navigation map.\n"
            "Use the same key format as NAVIGATION.generated.md.\n"
            "-->\n"
        )

    @staticmethod
    def _persona_file_exists(persona_dir: Path, base_name: str) -> bool:
        return (persona_dir / f"{base_name}.md").exists() or (persona_dir / f"{base_name}.generated.md").exists()

    @classmethod
    def _persona_file_is_active(cls, persona_dir: Path, base_name: str) -> bool:
        if not cls.PERSONA_FILE_CATALOG.get(base_name, {}).get("optional", True):
            return True
        return cls._persona_file_exists(persona_dir, base_name)

    def _render_profile_md(self, profile: Dict[str, Any]) -> str:
        speech_habits = profile.get("speech_habits", {}) if isinstance(profile.get("speech_habits", {}), dict) else {}
        emotion = profile.get("emotion_profile", {}) if isinstance(profile.get("emotion_profile", {}), dict) else {}
        arc = profile.get("arc", {}) if isinstance(profile.get("arc", {}), dict) else {}
        evidence = profile.get("evidence", {}) if isinstance(profile.get("evidence", {}), dict) else {}
        return (
            "# PROFILE\n"
            "<!-- Canonical markdown profile storage. Runtime loads this file before persona overlays. -->\n\n"
            "## Meta\n"
            f"- name: {profile.get('name', '')}\n"
            f"- novel_id: {profile.get('novel_id', '')}\n"
            f"- source_path: {profile.get('source_path', '')}\n\n"
            "## Core\n"
            f"- core_traits: {self._join_items(profile.get('core_traits', []))}\n"
            f"- values: {self._join_metric_map(profile.get('values', {}))}\n"
            f"- speech_style: {profile.get('speech_style', '')}\n"
            f"- identity_anchor: {profile.get('identity_anchor', '')}\n"
            f"- soul_goal: {profile.get('soul_goal', '')}\n"
            f"- worldview: {profile.get('worldview', '')}\n"
            f"- thinking_style: {profile.get('thinking_style', '')}\n\n"
            "## Voice\n"
            f"- typical_lines: {self._join_items(profile.get('typical_lines', []))}\n"
            f"- decision_rules: {self._join_items(profile.get('decision_rules', []))}\n"
            f"- life_experience: {self._join_items(profile.get('life_experience', []))}\n"
            f"- taboo_topics: {self._join_items(profile.get('taboo_topics', []))}\n"
            f"- forbidden_behaviors: {self._join_items(profile.get('forbidden_behaviors', []))}\n"
            f"- cadence: {speech_habits.get('cadence', '')}\n"
            f"- signature_phrases: {self._join_items(speech_habits.get('signature_phrases', []))}\n"
            f"- forbidden_fillers: {self._join_items(speech_habits.get('forbidden_fillers', []))}\n"
            f"- anger_style: {emotion.get('anger_style', '')}\n"
            f"- joy_style: {emotion.get('joy_style', '')}\n"
            f"- grievance_style: {emotion.get('grievance_style', '')}\n\n"
            "## Arc\n"
            f"- arc_start: {self._join_metric_map(arc.get('start', {}))}\n"
            f"- arc_mid: {self._join_metric_map(arc.get('mid', {}))}\n"
            f"- arc_end: {self._join_metric_map(arc.get('end', {}))}\n\n"
            "## Evidence\n"
            f"- description_count: {evidence.get('description_count', 0)}\n"
            f"- dialogue_count: {evidence.get('dialogue_count', 0)}\n"
            f"- thought_count: {evidence.get('thought_count', 0)}\n"
            f"- chunk_count: {evidence.get('chunk_count', 0)}\n"
        )

    def _render_soul_md(self, profile: Dict[str, Any]) -> str:
        return (
            "# SOUL\n\n"
            "## Core\n"
            f"- identity_anchor: {profile.get('identity_anchor', '')}\n"
            f"- soul_goal: {profile.get('soul_goal', '')}\n"
            f"- worldview: {profile.get('worldview', '')}\n"
            f"- thinking_style: {profile.get('thinking_style', '')}\n"
            f"- taboo_topics: {self._join_items(profile.get('taboo_topics', []))}\n"
            f"- forbidden_behaviors: {self._join_items(profile.get('forbidden_behaviors', []))}\n"
        )

    def _render_goals_md(self, profile: Dict[str, Any]) -> str:
        return (
            "# GOALS\n\n"
            "## Long Arc\n"
            f"- soul_goal: {profile.get('soul_goal', '')}\n"
            f"- decision_rules: {self._join_items(profile.get('decision_rules', []))}\n"
            f"- arc_end: {self._join_metric_map(profile.get('arc', {}).get('end', {}))}\n"
        )

    def _render_style_md(self, profile: Dict[str, Any]) -> str:
        speech_habits = profile.get("speech_habits", {}) if isinstance(profile.get("speech_habits", {}), dict) else {}
        return (
            "# STYLE\n\n"
            "## Expression\n"
            f"- speech_style: {profile.get('speech_style', '')}\n"
            f"- typical_lines: {self._join_items(profile.get('typical_lines', []))}\n"
            f"- cadence: {speech_habits.get('cadence', '')}\n"
            f"- signature_phrases: {self._join_items(speech_habits.get('signature_phrases', []))}\n"
            f"- forbidden_fillers: {self._join_items(speech_habits.get('forbidden_fillers', []))}\n"
        )

    def _render_trauma_md(self, profile: Dict[str, Any]) -> str:
        return (
            "# TRAUMA\n\n"
            "## Boundaries\n"
            f"- taboo_topics: {self._join_items(profile.get('taboo_topics', []))}\n"
            f"- forbidden_behaviors: {self._join_items(profile.get('forbidden_behaviors', []))}\n"
            f"- grievance_style: {profile.get('emotion_profile', {}).get('grievance_style', '')}\n"
        )

    def _render_identity_md(self, profile: Dict[str, Any]) -> str:
        emotion = profile.get("emotion_profile", {}) if isinstance(profile.get("emotion_profile", {}), dict) else {}
        return (
            "# IDENTITY\n\n"
            "## Self\n"
            f"- identity_anchor: {profile.get('identity_anchor', '')}\n"
            f"- life_experience: {self._join_items(profile.get('life_experience', []))}\n"
            f"- core_traits: {self._join_items(profile.get('core_traits', []))}\n"
            f"- values: {self._join_metric_map(profile.get('values', {}))}\n"
            f"- anger_style: {emotion.get('anger_style', '')}\n"
            f"- joy_style: {emotion.get('joy_style', '')}\n"
            f"- grievance_style: {emotion.get('grievance_style', '')}\n"
        )

    def _render_agents_md(self, profile: Dict[str, Any]) -> str:
        return (
            "# AGENTS\n\n"
            "## Runtime Rules\n"
            "- group_chat_policy: 群聊中优先回应明确点名、关系最紧密或当前冲突最相关的对象\n"
            "- silence_policy: 未被点名且无强关联时可保持一拍沉默，不抢戏\n"
            "- correction_policy: 用户纠正与持续提示要写入 MEMORY，并在后续对话沿用\n"
            f"- decision_rules: {self._join_items(profile.get('decision_rules', []))}\n"
        )

    def _render_memory_md(self, profile: Dict[str, Any]) -> str:
        return (
            "# MEMORY\n\n"
            "## Stable Memory\n"
            f"- canon_memory: {self._join_items(profile.get('life_experience', []))}\n"
            f"- relationship_updates: \n"
            "\n## Mutable Notes\n"
            "- user_edits: \n"
            "- notable_interactions: \n"
        )

    @staticmethod
    def _should_create_goals_md(profile: Dict[str, Any]) -> bool:
        return bool(str(profile.get("soul_goal", "")).strip() or profile.get("decision_rules"))

    @staticmethod
    def _should_create_style_md(profile: Dict[str, Any]) -> bool:
        return bool(str(profile.get("speech_style", "")).strip() or profile.get("typical_lines"))

    @staticmethod
    def _should_create_trauma_md(profile: Dict[str, Any]) -> bool:
        return bool(profile.get("taboo_topics") or profile.get("forbidden_behaviors"))

    def _extract_spoken_content(
        self,
        sentence: str,
        aliases: List[str],
        prev_sent: str = "",
        next_sent: str = "",
    ) -> str:
        verb_pattern = "|".join(re.escape(item) for item in self.speech_verbs)
        for alias in aliases:
            escaped = re.escape(alias)
            leading = rf"{escaped}[^\n“”\"]{{0,8}}(?:{verb_pattern})(?:[：:，,\s]{{0,2}})?[“\"](?P<quote>[^”\"]+)"
            for match in re.finditer(leading, sentence):
                if self._looks_like_subject_position(sentence, match.start()):
                    return str(match.group("quote") or "").strip()
            trailing = rf"[“\"](?P<quote>[^”\"]+)[”\"]?[^\n“”\"]{{0,8}}{escaped}[^\n“”\"]{{0,4}}(?:{verb_pattern})"
            for match in re.finditer(trailing, sentence):
                alias_start = match.start() + str(match.group(0)).find(alias)
                if self._looks_like_subject_position(sentence, alias_start):
                    return str(match.group("quote") or "").strip()
        return ""

    def _is_likely_spoken_by(
        self,
        sentence: str,
        aliases: List[str],
        prev_sent: str = "",
        next_sent: str = "",
    ) -> bool:
        verb_pattern = "|".join(re.escape(item) for item in self.speech_verbs)
        contexts = [sentence, f"{prev_sent}{sentence}", f"{sentence}{next_sent}", f"{prev_sent}{sentence}{next_sent}"]
        for alias in aliases:
            escaped = re.escape(alias)
            for context in contexts:
                outside_quotes = self._strip_quoted_content(context)
                patterns = [
                    rf"{escaped}[^\n“”\"]{{0,8}}(?:{verb_pattern})(?:[：:，,\s]{{0,2}})?",
                    rf"{escaped}[^\n“”\"]{{0,4}}[：:]",
                ]
                for pattern in patterns:
                    for match in re.finditer(pattern, outside_quotes):
                        if self._looks_like_subject_position(outside_quotes, match.start()):
                            return True
        return False

    def _looks_like_subject_position(self, text: str, alias_start: int) -> bool:
        prefix = text[:alias_start].rstrip()
        if not prefix:
            return True
        if prefix[-1] in "，。！？；：、“”\"'（）()[]【】<>《》 \t\r\n":
            return True
        tail = prefix[-4:]
        if any(tail.endswith(marker) for marker in self.object_leaders):
            return False
        if any(tail.endswith(marker) for marker in ("只见", "却说", "忽见", "便见", "原来", "那", "这", "又", "便")):
            return True
        return False

    def _text_mentions_any_alias(self, text: str, aliases: List[str]) -> bool:
        return any(self._contains_token(text, alias) for alias in aliases)

    @staticmethod
    def _contains_token(text: str, token: str) -> bool:
        return bool(token) and token in text

    @staticmethod
    def _count_token_mentions(text: str, token: str) -> int:
        return text.count(token) if token else 0

    @staticmethod
    def _strip_quoted_content(text: str) -> str:
        stripped = re.sub(r"“[^”]*”", "", text)
        return re.sub(r'"[^"]*"', "", stripped)

    @staticmethod
    def _looks_like_name(name: str) -> bool:
        if len(name) < 2 or len(name) > 4:
            return False
        bad = {"但是", "于是", "因为", "如果", "然后", "突然", "还是", "已经", "不能", "不会"}
        bad_suffixes = {"说", "道", "笑", "听", "问", "看", "想", "叹", "喊", "叫", "哭", "忙"}
        return name not in bad and name[-1] not in bad_suffixes

    @staticmethod
    def _empty_bucket() -> Dict[str, List[str]]:
        return {"descriptions": [], "dialogues": [], "thoughts": []}

    def _value_dimensions(self) -> List[str]:
        dims = self.config.get("distillation.values_dimensions", []) or list(self.value_markers.keys())
        return [str(item).strip() for item in dims if str(item).strip()]

    @staticmethod
    def _join_items(items: Iterable[Any]) -> str:
        cleaned = [str(item).strip() for item in items if str(item).strip()]
        return "；".join(cleaned)

    @staticmethod
    def _join_metric_map(items: Dict[str, Any]) -> str:
        if not isinstance(items, dict):
            return ""
        parts = []
        for key, value in items.items():
            key_text = str(key).strip()
            if key_text:
                parts.append(f"{key_text}={value}")
        return "；".join(parts)

    @staticmethod
    def _dedupe_texts(items: Iterable[str], limit: int) -> List[str]:
        cleaned = NovelDistiller._unique_texts(
            re.sub(r"\s+", " ", str(item).strip()) for item in items if str(item).strip()
        )
        return cleaned[:limit]

    @staticmethod
    def _unique_texts(items: Iterable[str]) -> List[str]:
        ordered: List[str] = []
        seen = set()
        for item in items:
            clean = str(item).strip()
            if not clean or clean in seen:
                continue
            ordered.append(clean)
            seen.add(clean)
        return ordered

    def _extract_signature_phrases(self, dialogues: List[str]) -> List[str]:
        phrases: List[str] = []
        for line in dialogues[:6]:
            parts = [part.strip("，。！？；：、 ") for part in re.split(r"[，。！？；：]", line) if part.strip()]
            for part in parts:
                if 2 <= len(part) <= 8 and part not in phrases:
                    phrases.append(part)
            if len(phrases) >= 4:
                break
        return phrases[:4]

    @staticmethod
    def _top_dimensions(values: Dict[str, int], count: int) -> List[str]:
        if not values:
            return ["责任"] * count
        ordered = sorted(values.items(), key=lambda item: int(item[1]), reverse=True)
        top = [name for name, _ in ordered[:count]]
        while len(top) < count:
            top.append(top[0] if top else "责任")
        return top
