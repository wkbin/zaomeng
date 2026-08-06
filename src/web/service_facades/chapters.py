from __future__ import annotations

import json
from typing import Any
from uuid import uuid4

from src.core.config import Config
from src.core.llm_client import LLMClient
from src.web.time_utils import utc_now as _utc_now


MIN_DIALOGUE_TURNS_FOR_CHAPTER = 6
_NOVEL_REWRITE_MAX_TOKENS = 4096
NOVEL_CHAPTER_TARGET_CHARS = 2500
NOVEL_CHAPTER_MAX_CHARS = 3500
_NOVEL_CONTEXT_PREVIOUS_CHARS = 1000


_NOVEL_REWRITE_SYSTEM_PROMPT = """
你是一名小说改写编辑。请将提供的结构化角色对话改写成连贯、自然、有画面感的小说正文。

你收到的数据可能包含：
- speaker：说话角色
- message：角色实际说出口的话
- inner_thought：角色没有说出口的真实想法
- user_message：用户或主角说出口的话
- context：此前剧情背景
- scene：已知的场景信息

你的任务是把这些信息组织成小说正文，而不是继续扮演角色回复用户。

核心原则：
1. 忠于原始内容。必须保留人物关系、说话顺序、对话含义、情绪倾向、已经明确发生的事件，以及 inner_thought 所表达的真实心理。不得擅自改变角色立场，不得加入会影响后续剧情的新事实。
2. 可以进行小说化补充。允许补充简短动作、神态和视线变化、合理停顿、说话语气、场景氛围、人物距离感、对话衔接、与角色心理一致的细节。这些内容只能增强画面和情绪，不得推动原对话中没有发生的新剧情。
3. 正确使用 inner_thought。可以将它转化为直接心理活动、间接心理描写、或与动作结合的内心反应。不要求逐字照抄，但不得改变原意。
4. 不机械添加动作。不是每句对白前后都必须添加动作。避免连续使用“他笑了笑”“她点了点头”“他微微一愣”“她垂下眼眸”“他沉默片刻”“她攥紧衣角”。动作必须服务于人物情绪和对话节奏，同一个动作、神态或句式不要频繁重复。
5. 控制描写密度。以人物互动和对话为主，描写为辅。普通对话场景中，对话约占 50%～70%，动作与神态约占 15%～30%，环境描写约占 5%～15%，心理描写约占 10%～25%。不要把几句简单对话扩写成大段空洞景物描写。
6. 视角必须统一。默认使用第三人称限知视角，主要跟随当前核心角色，不随意进入所有角色的内心。只有提供了某个角色的 inner_thought，才可以明确描写该角色当时的真实心理。没有提供时，只能通过动作、神态和语气表现，不得直接断言角色内心想法。
7. 禁止信息越界。不得擅自添加新人物、新地点、新道具、新的过去经历、未说明的人物关系、突然发生的肢体接触、告白、亲吻、冲突、离开等关键剧情、角色不知道的信息、原文没有依据的心理结论。如果必要信息不足，使用模糊但自然的表达。
8. 对话处理。保留原对话的主要内容和角色口吻。允许删除重复口头词、调整标点、合并断裂短句、补充符合语境的语气词、将过于书面的句子调整得更口语化。不得改变原句的意图、承诺、拒绝、态度或信息。
9. 文风要求。正文自然流畅、有画面感但不过度堆砌、情绪克制、避免模板化动作、避免华丽辞藻堆叠、避免把每个心理都解释清楚、留出适当的潜台词和空白。

不要写总结、分析、创作说明或标题，只输出小说正文。

章节字数：正文目标约 2500 字，最多不超过 3500 字。不要为了凑字数堆砌环境或动作。

输入格式：
{
  "context": "此前剧情背景",
  "scene": "当前已知场景",
  "point_of_view": "第三人称限知",
  "style": "现代言情，细腻克制",
  "dialogues": [
    {
      "speaker": "角色名",
      "message": "角色说出口的话",
      "inner_thought": "角色没说出口的想法"
    }
  ]
}

输出要求：
1. 第一行输出章节标题，标题不超过 15 个汉字，不要使用书名号，不要输出 JSON。
2. 从第二行开始输出小说正文。
3. 不得解释改写过程，不得添加输入中没有依据的重要剧情。
""".strip()


def _dialogue_turn_count(transcript: list[dict[str, Any]]) -> int:
    meaningful = [
        dict(item or {})
        for item in list(transcript or [])
        if isinstance(item, dict)
        and str(item.get("message", "")).strip()
        and str(item.get("role", "")).strip() not in {"scene", "director", "loading"}
    ]
    turn_ids = {
        str(item.get("turn_id", "")).strip()
        for item in meaningful
        if str(item.get("turn_id", "")).strip()
    }
    if turn_ids:
        return len(turn_ids)
    return len(meaningful)


def _trim_chapter_content(content: str, limit: int) -> str:
    text = str(content or "").strip()
    if len(text) <= limit:
        return text
    candidate = text[: max(1, limit - 1)]
    boundary = max(
        candidate.rfind("。"),
        candidate.rfind("！"),
        candidate.rfind("？"),
        candidate.rfind("”"),
        candidate.rfind("\n"),
    )
    if boundary > limit * 0.6:
        candidate = candidate[: boundary + 1]
    return candidate.rstrip() + "…"


class ChapterServiceMixin:
    """Local, run-scoped chapter drafts assembled from writing sessions."""

    def list_chapters(self, run_id: str) -> list[dict[str, Any]]:
        self._ensure_run_exists(run_id)
        return list(self._chapter_book(run_id).get("chapters", []))

    def search_run_content(self, run_id: str, *, query: str, limit: int = 30) -> list[dict[str, Any]]:
        manifest = self._require_manifest(run_id)
        needle = str(query or "").strip().casefold()
        if not needle:
            return []
        results: list[dict[str, Any]] = []
        for chapter in self.list_chapters(run_id):
            haystack = "\n".join(
                [str(chapter.get("title", "")), str(chapter.get("goal", "")), str(chapter.get("content", ""))]
            )
            if needle not in haystack.casefold():
                continue
            results.append(
                {
                    "kind": "chapter",
                    "chapter_id": str(chapter.get("chapter_id", "")).strip(),
                    "session_id": "",
                    "title": f"第 {chapter.get('order', 0)} 章 · {chapter.get('title', '')}",
                    "preview": self._search_preview(haystack, needle),
                }
            )
            if len(results) >= limit:
                return results
        for raw_persona in list(manifest.get("artifact_index", {}).get("characters", []) or []):
            if not isinstance(raw_persona, dict):
                continue
            character = str(raw_persona.get("name", "")).strip()
            if not character:
                continue
            preview = raw_persona.get("preview", {})
            preview_values = (
                [str(value).strip() for value in preview.values() if str(value).strip()]
                if isinstance(preview, dict)
                else []
            )
            haystack = "\n".join([character, *preview_values])
            if needle not in haystack.casefold():
                continue
            results.append(
                {
                    "kind": "persona",
                    "chapter_id": "",
                    "session_id": "",
                    "character": character,
                    "title": f"人物 · {character}",
                    "preview": self._search_preview(haystack, needle),
                }
            )
            if len(results) >= limit:
                return results
        for session in self.dialogue.list_sessions(run_id)[:100]:
            session_id = str(session.get("session_id", "")).strip()
            if not session_id:
                continue
            try:
                detail = self.dialogue.get_session(run_id, session_id)
            except FileNotFoundError:
                continue
            transcript = "\n".join(
                f"{str(item.get('speaker', '')).strip()}：{str(item.get('message', '')).strip()}"
                for item in list(detail.get("transcript", []) or [])
                if str(item.get("message", "")).strip()
            )
            if needle not in transcript.casefold():
                continue
            results.append(
                {
                    "kind": "session",
                    "chapter_id": "",
                    "session_id": session_id,
                    "title": str(session.get("last_entry_preview", "")).strip() or f"会话 {session_id[-6:]}",
                    "preview": self._search_preview(transcript, needle),
                }
            )
            if len(results) >= limit:
                break
        return results

    def answer_book_question(self, run_id: str, *, question: str) -> dict[str, Any]:
        normalized = str(question or "").strip()
        if not normalized:
            raise ValueError("问题不能为空。")
        manifest = self._require_manifest(run_id)
        character_names = [
            str(item.get("name", "")).strip()
            for item in list(manifest.get("artifact_index", {}).get("characters", []) or [])
            if isinstance(item, dict) and str(item.get("name", "")).strip() in normalized
        ]
        queries = character_names or [normalized]
        evidence: list[dict[str, Any]] = []
        for query in queries:
            for item in self.search_run_content(run_id, query=query, limit=8):
                if item not in evidence:
                    evidence.append(item)
        if not evidence:
            return {"answer": "没有在当前书卷中找到可引用的证据。请换用角色名、章节标题或更具体的关键词。", "evidence": []}
        payload = self._load_model_settings_payload()
        if not self._is_model_configured_payload(payload):
            raise ValueError("请先在设置中完成模型配置，再使用问书卷。")
        config = Config()
        config.update({"llm": {"provider": payload["provider"], "model": payload["model"], "base_url": payload["base_url"], "api_key": payload["api_key"], "max_tokens": min(max(256, int(payload.get("max_tokens", 0) or 0)), 1200)}})
        source_text = "\n\n".join(f"[{index + 1}] {item['title']}\n{item['preview']}" for index, item in enumerate(evidence[:12]))
        result = LLMClient(config).chat_completion([{"role": "user", "content": f"只依据以下书卷证据回答问题；没有证据就说明不知道。不要编造。\n问题：{normalized}\n\n证据：\n{source_text}"}], temperature=0.2, max_tokens=900)
        return {"answer": str(result.get("content", "")).strip(), "evidence": evidence[:12]}

    def save_chapter(
        self,
        run_id: str,
        *,
        chapter_id: str = "",
        title: str,
        goal: str = "",
        participants: list[str] | None = None,
        content: str = "",
        source_session_id: str = "",
        context_summary: str = "",
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        normalized_title = str(title or "").strip()
        if not normalized_title:
            raise ValueError("章节标题不能为空。")
        if len(normalized_title) > 120:
            raise ValueError("章节标题不能超过 120 个字符。")
        normalized_content = str(content or "").strip()
        if len(normalized_content) > 300_000:
            raise ValueError("章节草稿不能超过 30 万个字符。")
        normalized_participants = list(
            dict.fromkeys(
                item.strip()
                for item in participants or []
                if str(item).strip()
            )
        )[:40]
        book = self._chapter_book(run_id)
        chapters = list(book.get("chapters", []))
        now = _utc_now()
        target_id = str(chapter_id or "").strip()
        target_index = next(
            (index for index, item in enumerate(chapters) if item.get("chapter_id") == target_id),
            -1,
        )
        if target_id and target_index < 0:
            raise FileNotFoundError(target_id)
        if target_index >= 0:
            chapter = dict(chapters[target_index])
            chapter.update(
                title=normalized_title,
                goal=str(goal or "").strip(),
                participants=normalized_participants,
                content=normalized_content,
                updated_at=now,
            )
            if source_session_id:
                chapter["source_session_id"] = str(source_session_id).strip()
            if context_summary:
                chapter["context_summary"] = str(context_summary).strip()
            chapters[target_index] = chapter
        else:
            chapter = {
                "chapter_id": f"chapter-{uuid4().hex[:12]}",
                "order": len(chapters) + 1,
                "title": normalized_title,
                "goal": str(goal or "").strip(),
                "participants": normalized_participants,
                "content": normalized_content,
                "source_session_id": str(source_session_id or "").strip(),
                "context_summary": str(context_summary or "").strip(),
                "created_at": now,
                "updated_at": now,
            }
            chapters.append(chapter)
        book["chapters"] = self._normalized_chapters(chapters)
        self._write_json(self._chapter_book_path(run_id), book)
        return next(item for item in book["chapters"] if item["chapter_id"] == chapter["chapter_id"])

    def archive_dialogue_session_as_chapter(
        self, run_id: str, *, session_id: str, title: str = ""
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        session = self.dialogue.get_session(run_id, session_id)
        transcript = list(session.get("transcript", []) or [])
        if not transcript:
            raise ValueError("这个会话还没有可归档的内容。")
        dialogue_turns = _dialogue_turn_count(transcript)
        if dialogue_turns < MIN_DIALOGUE_TURNS_FOR_CHAPTER:
            raise ValueError(
                f"当前只有 {dialogue_turns} 轮有效对话，"
                f"至少需要 {MIN_DIALOGUE_TURNS_FOR_CHAPTER} 轮才能转为小说章节。"
            )
        content = "\n\n".join(
            f"{str(item.get('speaker', '')).strip() or '旁白'}：{str(item.get('message', '')).strip()}"
            for item in transcript
            if str(item.get("message", "")).strip()
        ).strip()
        if not content:
            raise ValueError("这个会话还没有可归档的内容。")
        book = self._chapter_book(run_id)
        chapters = list(book.get("chapters", []))
        now = _utc_now()
        chapter = {
            "chapter_id": f"chapter-{uuid4().hex[:12]}",
            "order": len(chapters) + 1,
            "title": str(title or session.get("title", "")).strip() or f"第 {len(chapters) + 1} 章",
            "goal": str(dict(session.get("scene_progress", {}) or {}).get("current_goal", "")).strip(),
            "participants": [str(item).strip() for item in session.get("participants", []) if str(item).strip()],
            "content": content,
            "source_session_id": str(session.get("session_id", session_id)).strip(),
            "created_at": now,
            "updated_at": now,
        }
        chapters.append(chapter)
        book["chapters"] = self._normalized_chapters(chapters)
        self._write_json(self._chapter_book_path(run_id), book)
        return chapter

    def convert_dialogue_session_to_novel(
        self, run_id: str, *, session_id: str, title: str = ""
    ) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        session = self.dialogue.get_session(run_id, session_id)
        transcript = list(session.get("transcript", []) or [])
        dialogue_turns = _dialogue_turn_count(transcript)
        if dialogue_turns < MIN_DIALOGUE_TURNS_FOR_CHAPTER:
            raise ValueError(
                f"当前只有 {dialogue_turns} 轮有效对话，"
                f"至少需要 {MIN_DIALOGUE_TURNS_FOR_CHAPTER} 轮才能转为小说章节。"
            )
        payload = self._load_model_settings_payload()
        if not self._is_model_configured_payload(payload):
            raise ValueError("请先在设置中完成模型配置，再使用对话转小说。")

        chapters = self.list_chapters(run_id)
        previous = chapters[-1] if chapters else {}
        previous_content = str(previous.get("content", "")).strip()
        story_recap = dict(session.get("story_recap", {}) or {})
        context_parts: list[str] = []
        if previous and previous_content:
            previous_summary = str(previous.get("context_summary", "")).strip()
            if previous_summary:
                context_parts.append(f"上一章摘要：{previous_summary}")
            context_parts.append(
                f"上一章《{str(previous.get('title', '')).strip()}》结尾：\n"
                f"{previous_content[-_NOVEL_CONTEXT_PREVIOUS_CHARS:]}"
            )
        summary = str(story_recap.get("summary", "")).strip()
        if summary:
            context_parts.append(f"本段剧情摘要：{summary}")

        scene_parts = [
            str(story_recap.get("time_hint", "")).strip(),
            str(story_recap.get("location", "")).strip(),
            str(story_recap.get("atmosphere", "")).strip(),
            str(dict(session.get("scene_card", {}) or {}).get("title", "")).strip(),
        ]
        scene = "，".join(part for part in scene_parts if part)
        dialogues: list[dict[str, str]] = []
        for item in list(transcript or []):
            if not isinstance(item, dict):
                continue
            role = str(item.get("role", "")).strip()
            if role in {"scene", "director", "loading"}:
                continue
            message = str(item.get("message", "")).strip()
            speaker = str(item.get("speaker", "")).strip()
            if not speaker or not message:
                continue
            dialogue: dict[str, str] = {
                "speaker": speaker,
                "message": message,
            }
            inner_thought = str(item.get("inner_thought", "")).strip()
            if inner_thought:
                dialogue["inner_thought"] = inner_thought
            dialogues.append(dialogue)
        if not dialogues:
            raise ValueError("这个会话还没有可转成小说的有效对话。")

        config = Config()
        config.update(
            {
                "llm": {
                    "provider": payload["provider"],
                    "model": payload["model"],
                    "base_url": payload["base_url"],
                    "api_key": payload["api_key"],
                    "max_tokens": min(
                        max(1600, int(payload.get("max_tokens", 0) or 0)),
                        _NOVEL_REWRITE_MAX_TOKENS,
                    ),
                }
            }
        )
        llm_input = {
            "context": "\n\n".join(context_parts).strip(),
            "scene": scene,
            "point_of_view": "第三人称限知",
            "style": "自然、克制、有画面感",
            "chapter_length": (
                f"目标约 {NOVEL_CHAPTER_TARGET_CHARS} 字，"
                f"最多不超过 {NOVEL_CHAPTER_MAX_CHARS} 字。"
            ),
            "dialogues": dialogues,
        }
        result = LLMClient(config).chat_completion(
            [
                {"role": "system", "content": _NOVEL_REWRITE_SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": json.dumps(llm_input, ensure_ascii=False),
                },
            ],
            temperature=0.55,
            max_tokens=min(
                max(1600, int(payload.get("max_tokens", 0) or 0)),
                _NOVEL_REWRITE_MAX_TOKENS,
            ),
        )
        raw_content = str(result.get("content", "")).strip()
        if not raw_content:
            raise ValueError("模型没有返回小说正文，请稍后重试。")
        raw_lines = raw_content.splitlines()
        generated_title = next(
            (
                line.strip(" #《》「」\"'").strip()
                for line in raw_lines
                if line.strip()
            ),
            "",
        )
        body = "\n".join(raw_lines[1:]).strip()
        if not body:
            body = raw_content
        if len(body) > NOVEL_CHAPTER_MAX_CHARS:
            body = _trim_chapter_content(body, NOVEL_CHAPTER_MAX_CHARS)

        chapter_title = (
            str(title or "").strip()
            or generated_title
            or str(story_recap.get("title", "")).strip()
            or f"第 {len(chapters) + 1} 章"
        )
        context_summary = summary or body[:180]
        participants = [
            str(name).strip()
            for name in list(session.get("participants", []) or [])
            if str(name).strip()
        ]
        return self.save_chapter(
            run_id,
            title=chapter_title,
            goal="由对话改写成小说正文。",
            participants=participants,
            content=body,
            source_session_id=str(
                session.get("session_id", session_id)
            ).strip(),
            context_summary=context_summary,
        )

    def delete_chapter(self, run_id: str, chapter_id: str) -> dict[str, str]:
        self._ensure_run_exists(run_id)
        book = self._chapter_book(run_id)
        chapters = list(book.get("chapters", []))
        remaining = [item for item in chapters if item.get("chapter_id") != chapter_id]
        if len(remaining) == len(chapters):
            raise FileNotFoundError(chapter_id)
        book["chapters"] = self._normalized_chapters(remaining)
        self._write_json(self._chapter_book_path(run_id), book)
        return {"status": "deleted", "chapter_id": chapter_id}

    def continue_chapter_writing(self, run_id: str, chapter_id: str) -> dict[str, Any]:
        manifest = self._require_manifest(run_id)
        book = self._chapter_book(run_id)
        chapters = list(book.get("chapters", []))
        index = next((i for i, item in enumerate(chapters) if item.get("chapter_id") == chapter_id), -1)
        if index < 0:
            raise FileNotFoundError(chapter_id)
        chapter = dict(chapters[index])
        participants = list(chapter.get("participants", []) or [])
        if not participants:
            participants = [
                str(name).strip()
                for name in list(manifest.get("available_characters", []) or manifest.get("locked_characters", []) or [])
                if str(name).strip()
            ][:4]
        if not participants:
            raise ValueError("请先为章节填写至少一位出场人物。")
        draft_excerpt = str(chapter.get("content", "")).strip()[-4_000:]
        session = self.create_dialogue_session(
            run_id,
            mode="observe",
            participants=participants,
            scene_profile={
                "title": str(chapter.get("title", "")).strip(),
                "opening_situation": "正在续写本章。已有草稿如下：\n" + draft_excerpt if draft_excerpt else "正在续写一个新章节。",
                "scene_drive": str(chapter.get("goal", "")).strip() or "推进本章剧情，并保持人物一致。",
                "expected_rhythm": "延续已有草稿，自然推进。",
            },
        )
        chapter["last_session_id"] = str(session.get("session_id", "")).strip()
        chapter["updated_at"] = _utc_now()
        chapters[index] = chapter
        book["chapters"] = self._normalized_chapters(chapters)
        self._write_json(self._chapter_book_path(run_id), book)
        return session

    def sync_latest_session_to_chapter(self, run_id: str, chapter_id: str) -> dict[str, Any]:
        self._ensure_run_exists(run_id)
        book = self._chapter_book(run_id)
        chapters = list(book.get("chapters", []))
        index = next((i for i, item in enumerate(chapters) if item.get("chapter_id") == chapter_id), -1)
        if index < 0:
            raise FileNotFoundError(chapter_id)
        chapter = dict(chapters[index])
        session_id = str(chapter.get("last_session_id", "")).strip()
        if not session_id:
            raise ValueError("请先从这个章节进入一次继续写作。")
        session = self.dialogue.get_session(run_id, session_id)
        transcript = [
            item for item in list(session.get("transcript", []) or [])
            if str(item.get("message", "")).strip()
        ]
        synced_count = max(0, int(chapter.get("synced_transcript_count", 0) or 0))
        fresh_entries = transcript[synced_count:]
        if not fresh_entries:
            return chapter
        addition = "\n\n".join(
            f"{str(item.get('speaker', '')).strip() or '旁白'}：{str(item.get('message', '')).strip()}"
            for item in fresh_entries
        )
        existing = str(chapter.get("content", "")).strip()
        chapter["content"] = f"{existing}\n\n{addition}".strip()
        chapter["synced_transcript_count"] = len(transcript)
        chapter["updated_at"] = _utc_now()
        chapters[index] = chapter
        book["chapters"] = self._normalized_chapters(chapters)
        self._write_json(self._chapter_book_path(run_id), book)
        return next(item for item in book["chapters"] if item["chapter_id"] == chapter_id)

    def reorder_chapter(self, run_id: str, chapter_id: str, *, target_order: int) -> list[dict[str, Any]]:
        self._ensure_run_exists(run_id)
        book = self._chapter_book(run_id)
        chapters = list(book.get("chapters", []))
        index = next((i for i, item in enumerate(chapters) if item.get("chapter_id") == chapter_id), -1)
        if index < 0:
            raise FileNotFoundError(chapter_id)
        target_index = max(0, min(len(chapters) - 1, int(target_order) - 1))
        item = chapters.pop(index)
        chapters.insert(target_index, item)
        book["chapters"] = self._normalized_chapters(chapters)
        self._write_json(self._chapter_book_path(run_id), book)
        return list(book["chapters"])

    def render_chapter_manuscript(self, run_id: str, *, format_name: str = "markdown") -> str:
        manifest = self._require_manifest(run_id)
        chapters = self.list_chapters(run_id)
        title = str(manifest.get("title", manifest.get("novel_id", "未命名书卷"))).strip() or "未命名书卷"
        markdown = [f"# {title}", ""]
        for chapter in chapters:
            markdown.extend([f"## {chapter['title']}", ""])
            if chapter.get("goal"):
                markdown.extend([f"> 本章目标：{chapter['goal']}", ""])
            if chapter.get("content"):
                markdown.extend([str(chapter["content"]).strip(), ""])
        rendered = "\n".join(markdown).rstrip() + "\n"
        if format_name == "markdown":
            return rendered
        if format_name == "text":
            return rendered.replace("# ", "").replace("## ", "").replace("> 本章目标：", "本章目标：")
        raise ValueError("仅支持 markdown 或 text 导出格式。")

    def _chapter_book_path(self, run_id: str):
        return self.runs_root / run_id / "chapters" / "chapter_book.json"

    @staticmethod
    def _search_preview(text: str, needle: str) -> str:
        normalized = str(text or "").strip()
        index = normalized.casefold().find(needle)
        if index < 0:
            return normalized[:180]
        start = max(0, index - 56)
        end = min(len(normalized), index + len(needle) + 100)
        return ("…" if start else "") + normalized[start:end].replace("\n", " ") + ("…" if end < len(normalized) else "")

    def _chapter_book(self, run_id: str) -> dict[str, Any]:
        payload = self._load_json_file(self._chapter_book_path(run_id)) or {}
        return {"version": 1, "chapters": self._normalized_chapters(list(payload.get("chapters", []) or []))}

    @staticmethod
    def _normalized_chapters(chapters: list[dict[str, Any]]) -> list[dict[str, Any]]:
        normalized: list[dict[str, Any]] = []
        for index, raw in enumerate(chapters, start=1):
            item = dict(raw or {})
            if not str(item.get("chapter_id", "")).strip():
                continue
            item["order"] = index
            item["title"] = str(item.get("title", "")).strip() or f"第 {index} 章"
            item["goal"] = str(item.get("goal", "")).strip()
            item["participants"] = [str(name).strip() for name in item.get("participants", []) if str(name).strip()]
            item["content"] = str(item.get("content", "")).strip()
            item["source_session_id"] = str(item.get("source_session_id", "")).strip()
            item["context_summary"] = str(item.get("context_summary", "")).strip()
            item["last_session_id"] = str(item.get("last_session_id", "")).strip()
            try:
                item["synced_transcript_count"] = max(0, int(item.get("synced_transcript_count", 0) or 0))
            except (TypeError, ValueError):
                item["synced_transcript_count"] = 0
            normalized.append(item)
        return normalized
