from src.web.chat.story_recap import build_story_recap


def _session() -> dict:
    return {
        "participants": ["林黛玉", "贾宝玉"],
        "session_card": {"scene_title": "潇湘馆"},
    }


def _transcript() -> list[dict]:
    return [
        {"speaker": "林黛玉", "message": "你来了？", "role": "character"},
        {"speaker": "旁白", "message": "loading", "role": "loading"},
        {"speaker": "贾宝玉", "message": "听说你病了，我放心不下。", "role": "character"},
    ]


def _chapter_outline() -> dict:
    return {
        "chapter_count": 1,
        "unresolved_hook_count": 1,
        "chapters": [
            {
                "title": "潇湘馆探病",
                "summary": "宝玉到潇湘馆探望黛玉。",
                "hooks": ["答应明日再来"],
                "participants": ["林黛玉", "贾宝玉"],
                "is_current": True,
            }
        ],
    }


def _event_timeline() -> list[dict]:
    return [
        {
            "title": "宝玉探病",
            "turn_id": "turn-1",
            "time_hint": "午后",
            "location": "潇湘馆",
            "participants": ["林黛玉", "贾宝玉"],
            "event_types": ["dialogue"],
            "responses": [
                {"speaker": "贾宝玉", "message": "听说你病了，我放心不下。"}
            ],
            "updated_at": "2026-07-31T12:00:00Z",
        }
    ]


def _relation_timeline() -> list[dict]:
    return [
        {
            "pair_key": "林黛玉_贾宝玉",
            "label": "林黛玉 · 贾宝玉",
            "characters": ["林黛玉", "贾宝玉"],
            "current": {"trust": 8, "affection": 9, "hostility": 0, "ambiguity": 2},
            "points": [
                {
                    "values": {
                        "trust": 8,
                        "affection": 9,
                        "hostility": 0,
                        "ambiguity": 2,
                    },
                    "changes": {
                        "trust": 1,
                        "affection": 2,
                        "hostility": 0,
                        "ambiguity": 0,
                    },
                    "reason": "宝玉主动前来探病。",
                    "evidence": "听说你病了，我放心不下。",
                    "turn_id": "turn-1",
                }
            ],
        }
    ]


def _character_arcs() -> list[dict]:
    return [
        {
            "name": "贾宝玉",
            "growth_summary": "最近变化：情绪、目标",
            "current": {
                "mood": "关切",
                "interaction_state": "softening",
                "focus": "林黛玉",
                "last_target": "林黛玉",
            },
        }
    ]


def _runtime_overview() -> dict:
    return {
        "location": "潇湘馆",
        "time_hint": "午后",
        "atmosphere": "安静",
        "next_hint": "下一幕可以顺势转到两人坐下详谈。",
    }


def test_build_story_recap_assembles_existing_session_state() -> None:
    recap = build_story_recap(
        session=_session(),
        transcript=_transcript(),
        chapter_outline=_chapter_outline(),
        event_timeline=_event_timeline(),
        relation_timeline=_relation_timeline(),
        character_arcs=_character_arcs(),
        runtime_state_overview=_runtime_overview(),
        session_memory_summary={"recap": "宝玉来潇湘馆探望黛玉。"},
    )

    assert recap["title"] == "潇湘馆探病"
    assert recap["summary"] == "宝玉到潇湘馆探望黛玉。"
    assert recap["location"] == "潇湘馆"
    assert recap["event_count"] == 1
    assert recap["chapter_count"] == 1
    assert recap["unresolved_hook_count"] == 1
    assert recap["events"][0]["title"] == "宝玉探病"
    assert recap["relations"][0]["changes"][0]["delta"] == 1
    assert recap["character_arcs"][0]["name"] == "贾宝玉"
    assert recap["hooks"] == ["答应明日再来"]
    assert len(recap["quotes"]) == 2
    assert recap["next_hint"] == "下一幕可以顺势转到两人坐下详谈。"


def test_build_story_recap_share_text_contains_core_sections() -> None:
    recap = build_story_recap(
        session=_session(),
        transcript=_transcript(),
        chapter_outline=_chapter_outline(),
        event_timeline=_event_timeline(),
        relation_timeline=_relation_timeline(),
        character_arcs=_character_arcs(),
        runtime_state_overview=_runtime_overview(),
        session_memory_summary={},
    )

    share_text = recap["share_text"]
    assert "《潇湘馆探病》" in share_text
    assert "事件" in share_text
    assert "关系变化" in share_text
    assert "人物状态" in share_text
    assert "待续伏笔" in share_text
    assert "下一拍" in share_text


def test_build_story_recap_falls_back_when_data_is_empty() -> None:
    recap = build_story_recap(
        session={},
        transcript=[],
        chapter_outline={},
        event_timeline=[],
        relation_timeline=[],
        character_arcs=[],
        runtime_state_overview={},
        session_memory_summary={},
    )

    assert recap["title"] == "未命名章节"
    assert "这一局刚开始" in recap["summary"]
    assert recap["events"] == []
    assert recap["relations"] == []
    assert recap["hooks"] == []
    assert recap["share_text"]
