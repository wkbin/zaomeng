#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""Run constrained, relation-aware conversations between novel characters."""

from __future__ import annotations

import logging
import re
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

from src.core.config import Config
from src.core.contracts import CostEstimator, RelationStore, RuntimePartsLike, SessionStore
from src.core.exceptions import ZaomengError
from src.core.path_provider import PathProvider
from src.core.relation_store import MarkdownRelationStore
from src.core.rulebook import RuleBook
from src.core.session_store import MarkdownSessionStore
from src.modules.chat_relation_state import ChatRelationResolver
from src.modules.distillation import NovelDistiller
from src.modules.persona_profile_io import (
    PERSONA_INT_FIELDS as PROFILE_INT_FIELDS,
    PERSONA_LIST_FIELDS as PROFILE_LIST_FIELDS,
    PERSONA_METRIC_FIELDS as PROFILE_METRIC_FIELDS,
    PERSONA_NESTED_FIELDS as PROFILE_NESTED_FIELDS,
    PERSONA_SCALAR_FIELDS as PROFILE_SCALAR_FIELDS,
    PersonaProfileRepository,
)
from src.modules.reflection import ReflectionEngine
from src.modules.speaker import Speaker
from src.utils.file_utils import (
    canonical_aliases,
    ensure_dir,
    load_markdown_data,
    normalize_character_name,
    novel_id_from_input,
)


class ChatEngine:
    """Multi-character chat with novel-scoped assets."""

    logger = logging.getLogger(__name__)

    SELF_INSERT_DEFAULT_NAME = "你"
    SELF_INSERT_DEFAULT_IDENTITY = "外来访客"
    SELF_INSERT_NAME_PATTERNS = (
        re.compile(r"(?:我叫|名字叫|你可以叫我|叫我)(?P<name>[^，。！？,.!?\\s]{1,12})"),
        re.compile(r"我是(?P<name>[^，。！？,.!?\\s]{1,8})"),
    )
    SELF_INSERT_IDENTITY_PATTERNS = (
        re.compile(r"(?:我是|身份是|算是)(?P<identity>[^，。！？,.!?\\n]{2,32})"),
        re.compile(r"作为(?P<identity>[^，。！？,.!?\\n]{2,32})"),
    )
    SELF_INSERT_IDENTITY_KEYWORDS = (
        "客",
        "访客",
        "新客",
        "来客",
        "旅人",
        "弟子",
        "学生",
        "书生",
        "使者",
        "掌柜",
        "大夫",
        "郎中",
        "姑娘",
        "公子",
        "夫人",
        "丫鬟",
        "侍女",
        "护卫",
        "幕僚",
        "官",
        "兵",
        "人",
        "者",
        "到",
        "来",
        "入",
        "府",
        "宫",
        "门",
        "庄",
        "楼",
    )

    SYSTEM_SPEAKERS = {"Narrator", "User", "旁白", "用户"}
    ADDRESS_SUFFIXES = ("哥哥", "姐姐", "妹妹", "弟弟", "姑娘", "公子", "爷")
    PERSONA_LIST_FIELDS = PROFILE_LIST_FIELDS
    PERSONA_SCALAR_FIELDS = PROFILE_SCALAR_FIELDS
    PERSONA_METRIC_FIELDS = PROFILE_METRIC_FIELDS
    PERSONA_INT_FIELDS = PROFILE_INT_FIELDS
    PERSONA_NESTED_FIELDS = PROFILE_NESTED_FIELDS

    GUIDANCE_SECTION_LABELS = (
        ("style_rules", "风格约束"),
        ("behavior_rules", "行为约束"),
        ("relation_rules", "关系约束"),
        ("memory_cues", "记忆约束"),
    )

    def __init__(
        self,
        config: Optional[Config] = None,
        *,
        llm: Optional[CostEstimator] = None,
        reflection: Optional[ReflectionEngine] = None,
        speaker: Optional[Speaker] = None,
        distiller: Optional[NovelDistiller] = None,
        rulebook: Optional[RuleBook] = None,
        path_provider: Optional[PathProvider] = None,
        session_store: Optional[SessionStore] = None,
        relation_store: Optional[RelationStore] = None,
    ):
        self.config = config or Config()
        if (
            llm is None
            or reflection is None
            or speaker is None
            or distiller is None
            or rulebook is None
            or path_provider is None
        ):
            raise ValueError(
                "ChatEngine requires injected llm, reflection, speaker, distiller, rulebook, and path_provider"
            )
        self.path_provider = path_provider
        self.rulebook = rulebook
        self.llm = llm
        self.reflection = reflection
        self.distiller = distiller
        self.speaker = speaker
        self.session_store = session_store or MarkdownSessionStore(path_provider)
        self.relation_store = relation_store or MarkdownRelationStore(path_provider)
        self.characters_dir = self.path_provider.characters_root()
        self._persona_profiles = PersonaProfileRepository(
            self.characters_dir,
            scoped_root=self.path_provider.characters_root,
            default_navigation_order=NovelDistiller.DEFAULT_NAV_LOAD_ORDER,
        )
        self._relations = ChatRelationResolver(
            path_provider=self.path_provider,
            relation_store=self.relation_store,
            persona_profiles=self._persona_profiles,
        )
        self.generation_mode = str(self.config.get("chat_engine.generation_mode", "auto")).strip().lower()
        self.enable_turn_interactions = bool(self.config.get("chat_engine.enable_turn_interactions", True))
        self.allow_character_silence = bool(self.config.get("chat_engine.allow_character_silence", True))
        self.min_reply_relevance = int(self.config.get("chat_engine.min_reply_relevance", 4))
        self.llm_history_messages = int(self.config.get("chat_engine.llm_history_messages", 8))
        self.address_suffixes = tuple(
            getattr(self.distiller, "address_suffixes", ())
            or self.rulebook.get("distillation", "address_suffixes", list(self.ADDRESS_SUFFIXES))
        )

    @classmethod
    def from_runtime_parts(cls, parts: RuntimePartsLike) -> "ChatEngine":
        return cls(
            parts.config,
            llm=parts.llm,
            reflection=parts.reflection,
            speaker=parts.speaker,
            distiller=parts.distiller,
            rulebook=parts.rulebook,
            path_provider=parts.path_provider,
            session_store=parts.session_store,
            relation_store=parts.relation_store,
        )

    def create_session(self, novel: str, mode: str) -> Dict[str, Any]:
        novel_id = novel_id_from_input(novel)
        profiles = self._load_character_profiles(novel_id)
        if not profiles:
            raise RuntimeError(f"No character profiles found for novel '{novel_id}'. Run distill first.")

        characters = list(profiles.keys())
        session = {
            "id": uuid.uuid4().hex[:12],
            "title": f"{novel}_{mode}_{int(time.time())}",
            "novel": novel,
            "novel_id": novel_id,
            "mode": mode,
            "created_at": int(time.time()),
            "characters": characters,
            "history": [],
            "state": {
                "emotion": {},
                "focus_targets": {},
                "controlled_character": "",
                "selected_characters": list(characters),
                "self_insert": {},
                "relation_delta": {},
                "relation_matrix": self._build_relation_matrix(characters, novel_id),
            },
        }
        self._save_session(session)
        return session

    def restore_session(self, session_id: str) -> Dict[str, Any]:
        data = self.session_store.load_session(session_id, default=None)
        if not data:
            raise FileNotFoundError(f"Session not found: {session_id}")
        data.setdefault("novel_id", novel_id_from_input(data.get("novel", session_id)))
        data.setdefault("state", {})
        data["state"].setdefault("focus_targets", {})
        data["state"].setdefault("controlled_character", "")
        data["state"].setdefault("selected_characters", list(data.get("characters", [])))
        data["state"].setdefault("self_insert", {})
        return data

    def build_session_summary(
        self,
        session: Dict[str, Any],
        latest_responses: Optional[List[tuple[str, str]]] = None,
    ) -> Dict[str, Any]:
        state = session.get("state", {})
        participants = state.get("selected_characters") or session.get("characters", [])
        history = session.get("history", [])
        last_entry = history[-1] if history else {}
        mode = str(session.get("mode", "observe")).strip() or "observe"

        summary = {
            "status": "ready",
            "session_id": session.get("id", ""),
            "title": session.get("title", ""),
            "novel": session.get("novel", ""),
            "novel_id": session.get("novel_id", ""),
            "mode": mode,
            "participants": list(participants),
            "controlled_character": str(state.get("controlled_character", "")).strip(),
            "focus_targets": dict(state.get("focus_targets", {})),
            "history_count": len(history),
            "artifacts": {
                "session_file": str(self.path_provider.sessions_dir() / f"{session.get('id', '')}.md"),
                "relation_snapshot_file": str(self.path_provider.sessions_dir() / f"{session.get('id', '')}_relations.md"),
            },
            "capabilities": {
                "act": True,
                "insert": True,
                "observe": True,
            },
        }
        if last_entry:
            summary["last_entry"] = {
                "speaker": last_entry.get("speaker", ""),
                "target": last_entry.get("target", ""),
                "message": last_entry.get("message", ""),
            }
        if mode == "insert":
            summary["self_insert"] = dict(self._self_insert_profile(session))
        if latest_responses:
            summary["latest_responses"] = [
                {"speaker": speaker, "message": message}
                for speaker, message in latest_responses
            ]
        return summary

    def observe_mode(self, session: Dict[str, Any]) -> None:
        print("进入 observe 模式。输入 /save /reflect /correct /quit")
        while True:
            user_msg = input("\n你: ").strip()
            if not user_msg:
                continue
            if self._handle_inline_command(session, user_msg):
                if user_msg == "/quit":
                    break
                continue

            responses = self.observe_once(session, user_msg)
            self._print_responses(responses)
            self.print_turn_cost()
            self.print_correction_hint(session)

    def act_mode(self, session: Dict[str, Any], character: str) -> None:
        controlled = self._resolve_character_name(character, session["characters"])
        if controlled not in session["characters"]:
            raise ValueError(f"Character '{character}' not found in this session.")

        print(f"进入 act 模式，你扮演 {controlled}。输入 /save /reflect /correct /quit")
        while True:
            user_msg = input(f"\n{controlled}(你): ").strip()
            if not user_msg:
                continue
            if self._handle_inline_command(session, user_msg):
                if user_msg == "/quit":
                    break
                continue

            try:
                responses = self.act_once(session, controlled, user_msg)
            except ValueError as exc:
                print(exc)
                continue

            self._print_responses(responses)
            self.print_turn_cost()
            self.print_correction_hint(session)

    def insert_mode(self, session: Dict[str, Any]) -> None:
        profile = self._self_insert_profile(session)
        print(
            "进入 insert 模式，"
            f"你将以 {profile.get('display_name', '你')} / {profile.get('scene_identity', '外来访客')} 的身份发言。"
            "输入 /save /reflect /correct /quit"
        )
        if profile.get("display_name", "你") == "你" or profile.get("scene_identity", "外来访客") == "外来访客":
            print("首次进入提示：你下一句里可以自然补一句“我叫……”或“我是……”，系统会按这个身份继续接。")
        while True:
            user_msg = input(f"\n{profile.get('display_name', '你')}(你): ").strip()
            if not user_msg:
                continue
            if self._handle_inline_command(session, user_msg):
                if user_msg == "/quit":
                    break
                continue

            responses = self.insert_once(session, user_msg)
            self._print_responses(responses)
            self.print_turn_cost()
            self.print_correction_hint(session)

    def observe_once(self, session: Dict[str, Any], user_msg: str) -> List[tuple[str, str]]:
        speaker, normalized_msg = self._resolve_observe_turn(session, user_msg)
        responders = self._active_characters(session, speaker=speaker, context=normalized_msg)
        return self._run_turn(session, speaker, normalized_msg, responders)

    def act_once(self, session: Dict[str, Any], character: str, user_msg: str) -> List[tuple[str, str]]:
        controlled = self._resolve_character_name(character, session["characters"])
        if controlled not in session["characters"]:
            raise ValueError(f"Character '{character}' not found in this session.")

        responders = self._active_characters(session, speaker=controlled, context=user_msg)
        if not responders:
            raise ValueError("未识别到明确对话对象。请在消息里点名角色，或先补充关系数据。")
        return self._run_turn(session, controlled, user_msg, responders)

    def insert_once(self, session: Dict[str, Any], user_msg: str) -> List[tuple[str, str]]:
        self._ingest_self_insert_profile(session, user_msg)
        speaker = self._self_insert_name(session)
        responders = self._active_characters(session, speaker=speaker, context=user_msg)
        if not responders:
            raise ValueError("未识别到可回应的角色。请点名角色，或先用 setup 指定群聊参与者。")
        return self._run_turn(session, speaker, user_msg, responders)

    def print_turn_cost(self) -> None:
        summary = self.llm.get_cost_summary()
        print(
            f"[累计] provider={summary.get('provider', 'unknown')} token={summary['total_tokens']} "
            f"session=${summary['session_cost']:.4f} daily=${summary['daily_cost']:.4f}"
        )

    @staticmethod
    def print_correction_hint(session: Dict[str, Any]) -> None:
        print(f"修正方式：/correct 角色|对象|原句|修正句|原因  或  correct --session {session['id']} ...")

    def _run_turn(
        self,
        session: Dict[str, Any],
        speaker: str,
        user_msg: str,
        responders: List[str],
    ) -> List[tuple[str, str]]:
        message = user_msg.strip()
        if not message:
            raise ValueError("消息不能为空。")

        self._append_history_entry(session, speaker=speaker, message=message)
        self._persist_runtime_guidance(session, speaker, message)
        self._remember_focus_targets(session, speaker, responders)
        profiles = self._load_character_profiles(session.get("novel_id"))
        ordered_responders = self._plan_turn_sequence(session, speaker, message, responders)

        responses: List[tuple[str, str]] = []
        for name in ordered_responders:
            turn_reply = self._collect_turn_reply(
                session=session,
                speaker=speaker,
                responder=name,
                message=message,
                prior_responses=responses,
                profiles=profiles,
            )
            if not turn_reply:
                continue
            responses.append(turn_reply)

        self._trim_history(session)
        self._update_state(session)
        self._save_session(session)
        return responses

    @staticmethod
    def _append_history_entry(
        session: Dict[str, Any],
        *,
        speaker: str,
        message: str,
        target: str = "",
    ) -> None:
        entry = {"speaker": speaker, "message": message, "ts": int(time.time())}
        if target:
            entry["target"] = target
        session["history"].append(entry)

    def _collect_turn_reply(
        self,
        *,
        session: Dict[str, Any],
        speaker: str,
        responder: str,
        message: str,
        prior_responses: List[tuple[str, str]],
        profiles: Dict[str, Dict[str, Any]],
    ) -> Optional[tuple[str, str]]:
        if self._should_skip_reply(session, speaker, responder, message, prior_responses):
            return None
        profile = profiles.get(responder, {"name": responder})
        target_name = self._resolve_turn_target(session, speaker, responder, message, prior_responses)
        relation_state = self._get_relation_state(session, responder, target_name)
        reply = self._generate_reply(
            session=session,
            speaker=speaker,
            responder=responder,
            profile=profile,
            message=message,
            target_name=target_name,
            relation_state=relation_state,
            prior_responses=prior_responses,
        )
        if not reply:
            return None
        self._append_history_entry(session, speaker=responder, target=target_name, message=reply)
        return responder, reply

    def _generate_reply(
        self,
        *,
        session: Dict[str, Any],
        speaker: str,
        responder: str,
        profile: Dict[str, Any],
        message: str,
        target_name: str,
        relation_state: Dict[str, Any],
        prior_responses: List[tuple[str, str]],
    ) -> str:
        relation_hint = self._relation_hint(responder, session["characters"], session.get("novel_id"))
        guidance = self.speaker.build_generation_guidance(
            character_profile=profile,
            context=message,
            history=session["history"],
            target_name=target_name,
            relation_state=relation_state,
            relation_hint=relation_hint,
        )
        fallback_reply = str(guidance.get("fallback_reply", "")).strip()

        if not self._should_use_llm_generation():
            return self._finalize_reply(profile, fallback_reply, relation_state, target_name)

        try:
            llm_messages = self._build_llm_messages(
                session=session,
                speaker=speaker,
                responder=responder,
                message=message,
                target_name=target_name,
                guidance=guidance,
                prior_responses=prior_responses,
            )
            llm_output = self.llm.chat_completion(
                llm_messages,
                temperature=float(self.config.get("llm.temperature", 0.2) or 0.2),
                max_tokens=int(self.config.get("llm.max_tokens", 0) or 0) or None,
            )
            candidate = self._sanitize_generated_reply(
                responder,
                str(llm_output.get("content", "")),
                fallback_reply=fallback_reply,
            )
        except ZaomengError as exc:
            self.logger.warning("LLM generation failed for %s: %s", responder, exc)
            candidate = fallback_reply

        final_reply = self._finalize_reply(profile, candidate, relation_state, target_name)
        if final_reply:
            return final_reply
        return self._finalize_reply(profile, fallback_reply, relation_state, target_name)

    def _should_use_llm_generation(self) -> bool:
        if self.generation_mode == "rule-only":
            return False
        if self.generation_mode == "llm-only":
            return True
        return self.llm.is_generation_enabled()

    def _finalize_reply(
        self,
        profile: Dict[str, Any],
        reply: str,
        relation_state: Dict[str, Any],
        target_name: str,
    ) -> str:
        cleaned = self._strip_revision_tag(reply.strip())
        if not cleaned:
            return ""
        guarded = self._guard_reply(profile, cleaned, relation_state, target_name)
        return self._strip_revision_tag(guarded).strip()

    def _plan_turn_sequence(
        self,
        session: Dict[str, Any],
        speaker: str,
        message: str,
        responders: List[str],
    ) -> List[str]:
        if not responders:
            return []
        mentioned = self._mentioned_characters(message, responders)
        ranked = self._rank_characters(session, speaker, responders, preferred=mentioned)
        ordered: List[str] = []
        seen = set()
        for name in mentioned + ranked:
            if name in seen:
                continue
            ordered.append(name)
            seen.add(name)
        return ordered or list(responders)

    def _should_skip_reply(
        self,
        session: Dict[str, Any],
        speaker: str,
        responder: str,
        message: str,
        prior_responses: List[tuple[str, str]],
    ) -> bool:
        if not self.allow_character_silence:
            return False
        if session.get("mode") == "act":
            return False
        if not prior_responses:
            return False
        if responder in self._mentioned_characters(message, [responder]):
            return False
        speaker_score = self._relation_score(session, responder, speaker)
        latest_score = 0
        if prior_responses:
            latest_speaker = prior_responses[-1][0]
            if latest_speaker != responder:
                latest_score = self._relation_score(session, responder, latest_speaker)
        return max(speaker_score, latest_score) < self.min_reply_relevance

    def _resolve_turn_target(
        self,
        session: Dict[str, Any],
        speaker: str,
        responder: str,
        message: str,
        prior_responses: List[tuple[str, str]],
    ) -> str:
        if self._is_self_insert_speaker(session, speaker):
            return self._self_insert_name(session)
        candidates = [name for name in session["characters"] if name != responder]
        mentioned = self._mentioned_characters(message, candidates)
        if mentioned:
            return mentioned[0]
        if self.enable_turn_interactions and prior_responses:
            latest = prior_responses[-1][0]
            if latest != responder:
                return latest
        remembered = self._remembered_target(session, responder, candidates)
        if remembered:
            return remembered
        return self._infer_target(responder, session["history"], session["characters"])

    def _build_llm_messages(
        self,
        *,
        session: Dict[str, Any],
        speaker: str,
        responder: str,
        message: str,
        target_name: str,
        guidance: Dict[str, Any],
        prior_responses: List[tuple[str, str]],
    ) -> List[Dict[str, str]]:
        return [
            {"role": "system", "content": "\n".join(self._build_llm_system_lines(responder, guidance))},
            {
                "role": "user",
                "content": "\n".join(
                    self._build_llm_user_lines(
                        session=session,
                        speaker=speaker,
                        responder=responder,
                        message=message,
                        target_name=target_name,
                        prior_responses=prior_responses,
                    )
                ),
            },
        ]

    def _build_llm_system_lines(self, responder: str, guidance: Dict[str, Any]) -> List[str]:
        lines = [
            f"你现在只扮演 {responder}。",
            "你的任务是生成自然、流畅、去模板化的中文角色回应。",
            "只输出该角色此刻会说出的内容，不要解释规则，不要输出字段名。",
            "允许极少量动作或神态描写，但不能抢成旁白大段叙述。",
            "优先遵守人物档案、关系约束、用户纠正、记忆约束。",
            "严禁复读通用 AI 套话、总结腔、万能过渡句。",
            "不要照抄约束草案，要把它转成自然说话。",
        ]
        for key, title in self.GUIDANCE_SECTION_LABELS:
            items = guidance.get(key, [])
            if not items:
                continue
            lines.append(f"{title}:")
            lines.extend(f"- {item}" for item in items[:8])
        lines.extend(self._render_correction_guidance(guidance.get("similar_corrections", [])))
        fallback_reply = str(guidance.get("fallback_reply", "")).strip()
        if fallback_reply:
            lines.append(f"约束草案（不要照抄）: {fallback_reply}")
        return lines

    @staticmethod
    def _render_correction_guidance(corrections: Any) -> List[str]:
        if not isinstance(corrections, list) or not corrections:
            return []
        lines = ["历史纠错参考:"]
        for item in corrections[:2]:
            corrected = str(item.get("corrected_message", "")).strip()
            reason = str(item.get("reason", "")).strip()
            if not corrected:
                continue
            suffix = f" | 原因: {reason}" if reason else ""
            lines.append(f"- 更接近人物的表达: {corrected}{suffix}")
        return lines

    def _build_llm_user_lines(
        self,
        *,
        session: Dict[str, Any],
        speaker: str,
        responder: str,
        message: str,
        target_name: str,
        prior_responses: List[tuple[str, str]],
    ) -> List[str]:
        history_lines = [
            f"{item.get('speaker', '')}: {item.get('message', '')}"
            for item in session["history"][-self.llm_history_messages :]
        ]
        lines = [
            f"会话模式: {session.get('mode', 'observe')}",
            f"当前轮发起者: {speaker}",
            f"当前回应角色: {responder}",
            f"当前主要回应对象: {target_name or '未指定'}",
        ]
        if session.get("mode") == "insert":
            profile = self._self_insert_profile(session)
            lines.extend(
                [
                    "自我代入用户档案:",
                    f"- 称呼: {profile.get('display_name', '你')}",
                    f"- 场景身份: {profile.get('scene_identity', '外来访客')}",
                    f"- 互动风格: {profile.get('interaction_style', 'immersive')}",
                    f"- 剧情影响范围: {profile.get('plot_agency', 'light')}",
                    "- 把对方当作场景中的真实来客回应，不要把用户写成旁白外的系统命令。",
                ]
            )
        if history_lines:
            lines.append("最近对话:")
            lines.extend(f"- {line}" for line in history_lines)
        lines.append("本轮已发生:")
        lines.append(f"- {speaker}: {message}")
        lines.extend(f"- {name}: {reply}" for name, reply in prior_responses)
        lines.append("请输出 1 到 3 句符合人物个性、关系与场景推进的自然回应。")
        return lines

    @staticmethod
    def _sanitize_generated_reply(responder: str, content: str, fallback_reply: str = "") -> str:
        text = str(content or "").strip()
        if not text:
            return fallback_reply
        patterns = (
            rf"^\s*{re.escape(responder)}\s*[：:]\s*",
            r"^\s*assistant\s*[：:]\s*",
        )
        for pattern in patterns:
            text = re.sub(pattern, "", text, count=1, flags=re.IGNORECASE).strip()
        lines = [line.strip() for line in text.splitlines() if line.strip()]
        if not lines:
            return fallback_reply
        return "\n".join(lines[:3]).strip()

    @staticmethod
    def _strip_revision_tag(reply: str) -> str:
        return re.sub(r"\s*\(needs_revision:.*?\)\s*$", "", str(reply or "").strip())

    def _remember_focus_targets(self, session: Dict[str, Any], speaker: str, responders: List[str]) -> None:
        if speaker in self.SYSTEM_SPEAKERS or self._is_self_insert_speaker(session, speaker) or not responders:
            return
        focus_targets = session.setdefault("state", {}).setdefault("focus_targets", {})
        if len(responders) == 1:
            focus_targets[speaker] = responders[0]
        elif speaker in focus_targets:
            focus_targets.pop(speaker, None)

    @staticmethod
    def _print_responses(responses: List[tuple[str, str]]) -> None:
        for speaker, message in responses:
            print(f"{speaker}: {message}")

    def _handle_inline_command(self, session: Dict[str, Any], command: str) -> bool:
        if command == "/quit":
            self._save_session(session)
            print("会话结束。")
            return True
        if command == "/save":
            self._save_session(session)
            print(f"已保存会话: {session['id']}")
            return True
        if command == "/reflect":
            self._reflect_last_turn(session)
            return True
        if command.startswith("/correct"):
            payload = self._parse_inline_correction_command(command)
            if not payload:
                print("格式错误。用法: /correct 角色|对象|原句|修正句|原因")
                return True
            item = self.reflection.save_correction(
                session_id=session["id"],
                character=payload["character"],
                target=payload["target"] or None,
                original_message=payload["original"],
                corrected_message=payload["corrected"],
                reason=payload["reason"],
            )
            self._persist_correction_memory(
                session,
                payload["character"],
                payload["target"],
                payload["original"],
                payload["corrected"],
                payload["reason"],
            )
            self.logger.info(
                "纠错已记录: %s -> %s",
                item["character"],
                item.get("target") or "任意对象",
            )
            return True
        return False

    @staticmethod
    def _parse_inline_correction_command(command: str) -> Optional[Dict[str, str]]:
        payload = command[len("/correct") :].strip()
        parts = [p.strip() for p in payload.split("|")]
        if len(parts) not in (3, 4, 5):
            return None
        if len(parts) == 3:
            character, target, original, corrected, reason = parts[0], "", parts[1], parts[2], "inline_command"
        elif len(parts) == 4:
            character, target, original, corrected, reason = parts[0], parts[1], parts[2], parts[3], "inline_command"
        else:
            character, target, original, corrected, reason = parts[0], parts[1], parts[2], parts[3], parts[4]
        return {
            "character": character,
            "target": target,
            "original": original,
            "corrected": corrected,
            "reason": reason,
        }

    def _reflect_last_turn(self, session: Dict[str, Any]) -> None:
        if not session["history"]:
            print("暂无历史可反思。")
            return
        profiles = self._load_character_profiles(session.get("novel_id"))
        last = session["history"][-1]
        profile = profiles.get(last["speaker"])
        if not profile:
            print("最近一条不是角色发言。")
            return
        check = self.reflection.detect_ooc(profile, last["message"])
        if not check.is_ooc:
            print("反思结果：最近发言符合人设。")
            return
        print("反思结果：疑似 OOC")
        for reason in check.reasons:
            print(f"- {reason}")

    def _relation_hint(self, speaker: str, all_chars: List[str], novel_id: Optional[str]) -> str:
        return self._relations.relation_hint(speaker, all_chars, novel_id)

    def _update_state(self, session: Dict[str, Any]) -> None:
        latest = session["history"][-6:]
        emotion = session["state"]["emotion"]
        relation_matrix = session["state"].setdefault("relation_matrix", {})
        seen_keys = session["state"].setdefault("_processed_history_keys", [])
        seen = set(str(item) for item in seen_keys if item)
        touched: List[str] = []
        for item in latest:
            speaker = item["speaker"]
            if speaker in self.SYSTEM_SPEAKERS:
                continue

            delta = 0
            msg = item["message"]
            ts = int(item.get("ts", 0) or 0)
            dedupe_key = f"{speaker}|{msg}|{ts}"
            if dedupe_key in seen:
                continue
            seen.add(dedupe_key)
            touched.append(dedupe_key)
            if any(k in msg for k in ("！", "怒", "生气", "质问")):
                delta += 1
            if any(k in msg for k in ("冷静", "平静", "慢慢说", "理解")):
                delta -= 1
            emotion[speaker] = max(-5, min(5, emotion.get(speaker, 0) + delta))

            target = item.get("target") or self._infer_target(speaker, latest, session["characters"])
            if not target or target == speaker:
                continue

            key = self._pair_key(speaker, target)
            state = relation_matrix.setdefault(
                key,
                {"trust": 5, "affection": 5, "hostility": 0, "ambiguity": 3},
            )
            if any(k in msg for k in ("谢谢", "抱歉", "理解", "关心", "在意")):
                state["affection"] = min(10, state.get("affection", 5) + 1)
                state["trust"] = min(10, state.get("trust", 5) + 1)
                state["hostility"] = max(0, state.get("hostility", 0) - 1)
            if any(k in msg for k in ("滚", "讨厌", "厌恶", "闭嘴", "烦")):
                state["hostility"] = min(10, state.get("hostility", 0) + 2)
                state["affection"] = max(0, state.get("affection", 5) - 2)
                state["trust"] = max(0, state.get("trust", 5) - 1)
            if any(k in msg for k in ("也许", "或许", "未必", "以后再说")):
                state["ambiguity"] = min(10, state.get("ambiguity", 3) + 1)
            session["state"]["relation_delta"][key] = {
                "trust": state["trust"],
                "affection": state["affection"],
                "hostility": state["hostility"],
                "ambiguity": state["ambiguity"],
            }
            novel_id = str(session.get("novel_id", "")).strip()
            if novel_id and hasattr(self.relation_store, "apply_dialogue_update"):
                try:
                    self.relation_store.apply_dialogue_update(
                        novel_id,
                        pair_key=key,
                        message=msg,
                        speaker=speaker,
                        target=target,
                    )
                except Exception as exc:
                    self.logger.debug("relation evolution skipped: %s", exc)
        if touched:
            session["state"]["_processed_history_keys"] = (list(seen_keys) + touched)[-240:]

    def _save_session(self, session: Dict[str, Any]) -> None:
        session["updated_at"] = int(time.time())
        if hasattr(self.session_store, "compress_context"):
            try:
                self.session_store.compress_context(
                    session,
                    max_recent_turns=int(self.config.get("memory.recent_turns", 24) or 24),
                    summary_char_limit=int(self.config.get("memory.summary_char_limit", 360) or 360),
                )
            except Exception as exc:
                self.logger.debug("session context compression skipped: %s", exc)
        self.session_store.save_session(session)
        self._save_relation_snapshot(session)

    def _persist_runtime_guidance(self, session: Dict[str, Any], speaker: str, message: str) -> None:
        if speaker not in self.SYSTEM_SPEAKERS:
            return
        if not self._looks_like_persistent_guidance(message):
            return
        for character in session.get("characters", []):
            if not self._message_mentions_character(message, character):
                continue
            note = f"用户提示：{message.strip()}"
            self._append_memory_entry(session.get("novel_id"), character, "user_edits", note)

    def _looks_like_persistent_guidance(self, message: str) -> bool:
        durable_tokens = tuple(
            self.rulebook.get(
                "speaker",
                "durable_guidance_tokens",
                ["记住", "设定", "人设", "以后", "别再", "不要再", "改成", "纠正", "必须", "不要", "应该"],
            )
        )
        return any(token in message for token in durable_tokens) and "？" not in message and "?" not in message

    def _message_mentions_character(self, message: str, character: str) -> bool:
        aliases = [character] + self._candidate_aliases(character)
        return any(alias and alias in message for alias in aliases)

    def _persist_correction_memory(
        self,
        session: Dict[str, Any],
        character: str,
        target: str,
        original: str,
        corrected: str,
        reason: str,
    ) -> None:
        note = f"纠正：原句={original}；修正={corrected}；原因={reason or 'inline_command'}"
        self._append_memory_entry(session.get("novel_id"), character, "user_edits", note)
        self._append_memory_entry(session.get("novel_id"), character, "notable_interactions", note)
        if target:
            target_note = f"与{target}相关的纠正：{corrected}"
            self._append_memory_entry(session.get("novel_id"), character, "relationship_updates", target_note)

    def _append_memory_entry(self, novel_id: Optional[str], character: str, field: str, note: str) -> None:
        if not novel_id or not character or not note.strip():
            return
        normalized_name = normalize_character_name(character)
        persona_dir = self.path_provider.character_dir(novel_id, normalized_name)
        memory_file = persona_dir / "MEMORY.md"
        if not memory_file.exists():
            memory_file.write_text(
                "# MEMORY\n\n## Stable Memory\n\n## Mutable Notes\n",
                encoding="utf-8",
            )
        self.distiller.refresh_navigation(persona_dir, normalized_name)
        with memory_file.open("a", encoding="utf-8") as handle:
            handle.write(f"- {field}: {note.strip()}\n")

    def _load_character_profiles(self, novel_id: Optional[str] = None) -> Dict[str, Dict[str, Any]]:
        return self._persona_profiles.load(novel_id)

    @staticmethod
    def _pair_key(a: str, b: str) -> str:
        return ChatRelationResolver.pair_key(a, b)

    def _build_relation_matrix(self, characters: List[str], novel_id: Optional[str]) -> Dict[str, Dict[str, Any]]:
        return self._relations.build_matrix(characters, novel_id)

    def _save_relation_snapshot(self, session: Dict[str, Any]) -> None:
        session.setdefault("updated_at", int(time.time()))
        self.session_store.save_relation_snapshot(session)

    def _get_relation_state_from_disk(
        self,
        speaker: str,
        target: str,
        novel_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        return self._relations.get_from_disk(speaker, target, novel_id)

    def _get_relation_state(self, session: Dict[str, Any], speaker: str, target: str) -> Dict[str, Any]:
        return self._relations.get_session_state(session, speaker, target)
    def _active_characters(
        self,
        session: Dict[str, Any],
        speaker: Optional[str] = None,
        context: str = "",
    ) -> List[str]:
        limit = int(self.config.get("chat_engine.max_speakers_per_turn", 4))
        candidates = [name for name in session["characters"] if name != speaker]
        if not candidates:
            return []

        mentioned = self._mentioned_characters(context, candidates)
        if mentioned:
            if session.get("mode") == "act":
                return mentioned[: max(1, min(limit, len(mentioned)))]
            ranked = self._rank_characters(session, speaker, candidates, preferred=mentioned)
            ordered = []
            seen = set()
            for name in mentioned + ranked:
                if name in seen:
                    continue
                ordered.append(name)
                seen.add(name)
                if len(ordered) >= max(1, limit):
                    break
            return ordered

        remembered = self._remembered_target(session, speaker, candidates)
        if remembered:
            return [remembered]

        ranked = self._rank_characters(session, speaker, candidates)
        if session.get("mode") == "act":
            if self._is_group_act_session(session, speaker=speaker):
                return ranked[: max(1, limit)]
            if not ranked:
                return []
            top = ranked[0]
            if self._relation_score(session, speaker, top) <= self._default_relation_score():
                return []
            return [top]
        if session.get("mode") == "insert":
            return ranked[: max(1, limit)]
        return ranked[: max(1, limit)]

    def _is_group_act_session(self, session: Dict[str, Any], *, speaker: Optional[str] = None) -> bool:
        if session.get("mode") != "act":
            return False
        state = session.get("state", {})
        controlled = str(state.get("controlled_character", "")).strip()
        if not controlled:
            return False
        if speaker and controlled and normalize_character_name(speaker) != normalize_character_name(controlled):
            return False
        selected = state.get("selected_characters", session.get("characters", []))
        if not isinstance(selected, list):
            selected = list(session.get("characters", []))
        participant_count = len([name for name in selected if name in session.get("characters", [])])
        return participant_count > 2

    def _remembered_target(
        self,
        session: Dict[str, Any],
        speaker: Optional[str],
        candidates: List[str],
    ) -> str:
        if not speaker or speaker in self.SYSTEM_SPEAKERS:
            return ""
        focus_targets = session.get("state", {}).get("focus_targets", {})
        target = focus_targets.get(speaker, "")
        if target in candidates:
            return target
        return ""

    def _trim_history(self, session: Dict[str, Any]) -> None:
        turns = int(self.config.get("chat_engine.max_history_turns", 10))
        keep = max(10, turns * (len(self._active_characters(session)) + 1))
        session["history"] = session["history"][-keep:]

    def _resolve_observe_turn(self, session: Dict[str, Any], user_msg: str) -> tuple[str, str]:
        message = user_msg.strip()
        if not message:
            return "Narrator", user_msg

        if len(session.get("characters", [])) == 1:
            only_name = session["characters"][0]
            aliases = [only_name] + self._candidate_aliases(only_name)
            for alias in aliases:
                stripped = self._strip_explicit_speaker_prefix(message, alias)
                if stripped != message:
                    return "Narrator", stripped.strip() or message
            return "Narrator", user_msg

        for name in session["characters"]:
            aliases = [name] + self._candidate_aliases(name)
            for alias in aliases:
                stripped = self._strip_explicit_speaker_prefix(message, alias)
                if stripped == message:
                    continue
                normalized = stripped.strip() or message
                return name, normalized
        return "Narrator", user_msg

    @staticmethod
    def _strip_explicit_speaker_prefix(message: str, alias: str) -> str:
        escaped = re.escape(alias)
        patterns = (
            rf"^\s*[“\"'「『]?\s*{escaped}\s*[：:，,]\s*",
            rf"^\s*[“\"'「『]?\s*{escaped}\s*(?:说道|说|道|问道|问|答道|答|曰|开口道|笑道|沉声道|朗声道|轻声道)\s*[：:，,]?\s*",
        )
        for pattern in patterns:
            updated = re.sub(pattern, "", message, count=1)
            if updated != message:
                return updated
        return message

    def _candidate_aliases(self, name: str) -> List[str]:
        clean = normalize_character_name(name)
        if hasattr(self.distiller, "candidate_aliases"):
            return list(self.distiller.candidate_aliases(clean))

        aliases: List[str] = []
        aliases.extend(canonical_aliases(clean))
        if len(clean) >= 3:
            given = clean[-2:]
            if len(given) == 2 and given != clean:
                aliases.append(given)
                for suffix in self.address_suffixes:
                    aliases.append(f"{given[0]}{suffix}")
                    aliases.append(f"{clean[0]}{suffix}")
        elif len(clean) == 2:
            for suffix in self.address_suffixes:
                aliases.append(f"{clean[0]}{suffix}")
        ordered = []
        seen = set()
        for alias in aliases:
            if alias and alias != clean and alias not in seen:
                ordered.append(alias)
                seen.add(alias)
        return ordered

    def _mentioned_characters(self, context: str, candidates: List[str]) -> List[str]:
        if not context:
            return []

        alias_owners: Dict[str, List[str]] = {}
        for name in candidates:
            for alias in self._candidate_aliases(name):
                alias_owners.setdefault(alias, []).append(name)

        hits: List[tuple[int, str]] = []
        for name in candidates:
            positions = []
            if name in context:
                positions.append(context.index(name))
            for alias in self._candidate_aliases(name):
                if alias_owners.get(alias) != [name]:
                    continue
                if alias in context:
                    positions.append(context.index(alias))
            if positions:
                hits.append((min(positions), name))

        hits.sort(key=lambda item: (item[0], item[1]))
        return [name for _, name in hits]

    @staticmethod
    def _default_relation_score() -> int:
        return 7

    def _relation_score(self, session: Dict[str, Any], speaker: Optional[str], candidate: str) -> int:
        if self._is_self_insert_speaker(session, speaker):
            return self._default_relation_score()
        if not speaker or speaker in self.SYSTEM_SPEAKERS:
            return 0
        state = self._get_relation_state(session, speaker, candidate)
        trust = int(state.get("trust", 5))
        affection = int(state.get("affection", 5))
        hostility = int(state.get("hostility", max(0, 5 - affection)))
        ambiguity = int(state.get("ambiguity", 3))
        return trust + affection - hostility - ambiguity

    def _rank_characters(
        self,
        session: Dict[str, Any],
        speaker: Optional[str],
        candidates: List[str],
        preferred: Optional[List[str]] = None,
    ) -> List[str]:
        preferred_set = set(preferred or [])
        return sorted(
            candidates,
            key=lambda name: (
                1 if name in preferred_set else 0,
                self._relation_score(session, speaker, name),
                name,
            ),
            reverse=True,
        )

    def _resolve_character_name(self, raw_name: str, candidates: List[str]) -> str:
        normalized = normalize_character_name(raw_name)
        if normalized in candidates:
            return normalized
        matched = []
        for name in candidates:
            if normalized == name or normalized in self._candidate_aliases(name):
                matched.append(name)
        if len(matched) == 1:
            return matched[0]
        return normalized

    def _self_insert_profile(self, session: Dict[str, Any]) -> Dict[str, str]:
        state = session.setdefault("state", {})
        profile = state.get("self_insert", {})
        merged = {
            "display_name": self.SELF_INSERT_DEFAULT_NAME,
            "scene_identity": self.SELF_INSERT_DEFAULT_IDENTITY,
            "interaction_style": "immersive",
            "plot_agency": "light",
        }
        if isinstance(profile, dict):
            for key in merged:
                value = str(profile.get(key, "")).strip()
                if value:
                    merged[key] = value
        state["self_insert"] = merged
        return merged

    def _ingest_self_insert_profile(self, session: Dict[str, Any], user_msg: str) -> None:
        if session.get("mode") != "insert":
            return
        profile = self._self_insert_profile(session)
        updates: Dict[str, str] = {}

        inferred_name = self._extract_self_insert_name(user_msg)
        if inferred_name and inferred_name != profile.get("display_name", self.SELF_INSERT_DEFAULT_NAME):
            updates["display_name"] = inferred_name

        inferred_identity = self._extract_self_insert_identity(user_msg)
        if inferred_identity and inferred_identity != profile.get("scene_identity", self.SELF_INSERT_DEFAULT_IDENTITY):
            updates["scene_identity"] = inferred_identity

        if updates:
            merged = dict(profile)
            merged.update(updates)
            session.setdefault("state", {})["self_insert"] = merged

    def _self_insert_name(self, session: Dict[str, Any]) -> str:
        return self._self_insert_profile(session).get("display_name", self.SELF_INSERT_DEFAULT_NAME)

    def _is_self_insert_speaker(self, session: Dict[str, Any], speaker: Optional[str]) -> bool:
        if session.get("mode") != "insert" or not speaker:
            return False
        return normalize_character_name(str(speaker)) == normalize_character_name(self._self_insert_name(session))

    @classmethod
    def _extract_self_insert_name(cls, text: str) -> str:
        for pattern in cls.SELF_INSERT_NAME_PATTERNS:
            match = pattern.search(text or "")
            if not match:
                continue
            candidate = cls._clean_self_insert_candidate(match.group("name"))
            if candidate and candidate not in {"我", "你", cls.SELF_INSERT_DEFAULT_NAME}:
                return candidate
        return ""

    @classmethod
    def _extract_self_insert_identity(cls, text: str) -> str:
        for pattern in cls.SELF_INSERT_IDENTITY_PATTERNS:
            match = pattern.search(text or "")
            if not match:
                continue
            candidate = cls._clean_self_insert_candidate(match.group("identity"))
            if cls._looks_like_scene_identity(candidate):
                return candidate
        return ""

    @staticmethod
    def _clean_self_insert_candidate(value: str) -> str:
        cleaned = re.sub(r"[“”\"'：:]", "", value or "").strip()
        cleaned = re.sub(r"\s+", " ", cleaned)
        return cleaned[:32]

    @classmethod
    def _looks_like_scene_identity(cls, value: str) -> bool:
        if not value or value in {"我", "你", cls.SELF_INSERT_DEFAULT_NAME, cls.SELF_INSERT_DEFAULT_IDENTITY}:
            return False
        if len(value) >= 5:
            return True
        return any(keyword in value for keyword in cls.SELF_INSERT_IDENTITY_KEYWORDS)

    @staticmethod
    def _infer_target(speaker: str, history: List[Dict[str, Any]], all_chars: List[str]) -> str:
        for item in reversed(history):
            prev_speaker = item.get("speaker", "")
            if prev_speaker and prev_speaker != speaker and prev_speaker in all_chars:
                return prev_speaker
        for candidate in all_chars:
            if candidate != speaker:
                return candidate
        return ""

    def _guard_reply(
        self,
        profile: Dict[str, Any],
        reply: str,
        relation_state: Dict[str, Any],
        target_name: str,
    ) -> str:
        issues = self.reflection.relation_alignment_issues(reply, relation_state)
        checked = self.reflection.detect_ooc(profile, reply)
        if not issues and not checked.is_ooc:
            return reply

        rewritten = self._rewrite_reply(reply, relation_state, target_name)
        issues_after = self.reflection.relation_alignment_issues(rewritten, relation_state)
        checked_after = self.reflection.detect_ooc(profile, rewritten)
        if issues_after or checked_after.is_ooc:
            reasons = issues_after + checked_after.reasons
            return f"{rewritten}(needs_revision: {'; '.join(reasons[:2])})"
        return rewritten

    @staticmethod
    def _rewrite_reply(reply: str, relation_state: Dict[str, Any], target_name: str) -> str:
        target = target_name or "对方"
        hostility = int(relation_state.get("hostility", 0))
        affection = int(relation_state.get("affection", 5))
        ambiguity = int(relation_state.get("ambiguity", 3))
        if hostility >= 7:
            return f"对{target}，我把话说到这里，不必更近一步。"
        if affection >= 8:
            return f"对{target}，我会把语气放缓，把话说明白。"
        if ambiguity >= 7:
            return f"对{target}，我先留一点余地，不把话说死。"
        return f"{reply}（已按对象关系收束）"
