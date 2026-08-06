import base64
import json
import os
import shutil
import tempfile
import threading
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import Mock, patch

from src.core.exceptions import LLMRequestError
from src.web.chat.helpers import (
    _reorder_plot_push_responses,
    build_dialogue_association_llm_messages,
    build_dialogue_llm_messages,
    compact_dialogue_suggestion_payload,
    generate_dialogue_associations,
    generate_dialogue_responses,
    parse_dialogue_associations,
    parse_dialogue_responses,
    parse_dialogue_suggestion,
)
from src.web.pipeline import process_relation_graph, update_manifest_chunk_progress
from src.web.review.persona_completion import collect_persona_web_references
from src.web.workflow import WebRunService

class SceneCardServiceTests(unittest.TestCase):
    def test_scene_card_recommendation_prefers_insert_friendly_card(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            guest_card = service.save_scene_card(
                fields={
                    "title": "新客入席",
                    "time_hint": "薄暮",
                    "location": "花厅",
                    "atmosphere": "表面热络，暗地试探",
                    "opening_situation": "一位新到的外客被引入席间，众人的目光都轻轻落了过去。",
                    "public_goal": "先把这位来客安顿进今晚的场面。",
                    "hidden_tension": "谁都想先看清这位外客站在哪边。",
                    "scene_drive": "让来客与席上人物迅速形成试探。",
                    "expected_rhythm": "慢热试探",
                    "forbidden_topics": "旧案",
                }
            )
            service.save_scene_card(
                fields={
                    "title": "二人檐下",
                    "time_hint": "深夜",
                    "location": "回廊",
                    "atmosphere": "安静发紧",
                    "opening_situation": "两个人被雨声隔在檐下，谁都不肯先明说。",
                    "public_goal": "先把真正来意试出来。",
                    "hidden_tension": "旧事随时会被挑破。",
                    "scene_drive": "把试探慢慢推成摊牌。",
                    "expected_rhythm": "慢热",
                    "forbidden_topics": "前尘",
                }
            )

            payload = service.recommend_scene_cards(
                mode="insert", participants=["林黛玉", "贾宝玉", "薛宝钗"]
            )

            self.assertEqual(payload["recommended_card_id"], guest_card["card_id"])
            self.assertTrue(payload["items"][0]["recommendation"]["reasons"])

    def test_dialogue_scene_card_recommendation_prefers_next_scene_not_current_one(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            current_scene = service.save_scene_card(
                fields={
                    "title": "雨夜回廊",
                    "time_hint": "深夜",
                    "location": "回廊",
                    "atmosphere": "雨声压得人心发紧",
                    "opening_situation": "两个人被雨隔在檐下，话还没真正说开。",
                    "public_goal": "先把来意试出来。",
                    "hidden_tension": "旧事随时会被翻出来。",
                    "scene_drive": "让试探一点点逼近摊牌。",
                    "expected_rhythm": "慢热",
                    "forbidden_topics": "前尘",
                }
            )
            next_scene = service.save_scene_card(
                fields={
                    "title": "转入花厅",
                    "time_hint": "夜深",
                    "location": "花厅",
                    "atmosphere": "人多却更安静，像谁都在等先开口",
                    "opening_situation": "雨势更大，众人不得不转入花厅继续对坐。",
                    "public_goal": "把表面客套维持住。",
                    "hidden_tension": "真正要问的话终于躲不过去了。",
                    "scene_drive": "让局面从试探推向摊牌。",
                    "expected_rhythm": "三句一推进",
                    "forbidden_topics": "旧账",
                }
            )
            service.save_scene_card(
                fields={
                    "title": "席后逼问",
                    "time_hint": "更深",
                    "location": "偏厅",
                    "atmosphere": "客套彻底收住，只剩正面拉扯",
                    "opening_situation": "众人散去后，只余两人留在偏厅把话挑明。",
                    "public_goal": "把真正立场逼出来。",
                    "hidden_tension": "之前压住的话终于要摊开。",
                    "scene_drive": "把局面从试探推进到逼问与摊牌。",
                    "expected_rhythm": "越聊越紧",
                    "forbidden_topics": "闲话",
                }
            )
            run = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    run["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[
                    {"speaker": "场景提示", "message": "雨势更大，众人不得不转入花厅。"}
                ],
            ):
                session = service.create_dialogue_session(
                    run["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉"],
                    scene_card_id=current_scene["card_id"],
                )
                service.reply_dialogue_turn(
                    run["run_id"],
                    session_id=session["session_id"],
                    message="雨势更大，众人不得不转入花厅。",
                    message_kind="narration",
                )

            payload = service.recommend_dialogue_scene_card(
                run["run_id"], session_id=session["session_id"]
            )

            self.assertEqual(payload["current_scene_card_id"], current_scene["card_id"])
            self.assertEqual(payload["recommended_card_id"], next_scene["card_id"])
            self.assertNotEqual(
                payload["recommended_card_id"], current_scene["card_id"]
            )
            self.assertTrue(payload["items"][0]["recommendation"]["reasons"])
            self.assertTrue(
                str(payload.get("recommended_transition_message", "")).strip()
            )
            self.assertTrue(
                str(payload.get("recommended_auto_continue_message", "")).strip()
            )
            self.assertTrue(payload["chain_suggestions"])
            self.assertGreaterEqual(len(payload["chain_suggestions"][0]["scenes"]), 2)
            self.assertTrue(str(payload["chain_suggestions"][0]["reason"]).strip())

    def test_dialogue_scene_card_recommendation_stays_in_same_location_when_beat_is_early(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            current_scene = service.save_scene_card(
                fields={
                    "title": "雨夜回廊",
                    "time_hint": "深夜",
                    "location": "回廊",
                    "atmosphere": "雨声压着话头",
                    "opening_situation": "两个人还站在檐下，谁都没把话说透。",
                    "public_goal": "先试出彼此来意。",
                    "hidden_tension": "有些旧话一碰就要翻出来。",
                    "scene_drive": "让试探再压低一层。",
                    "expected_rhythm": "慢热",
                    "forbidden_topics": "旧账",
                }
            )
            same_location = service.save_scene_card(
                fields={
                    "title": "回廊压低声气",
                    "time_hint": "深夜",
                    "location": "回廊",
                    "atmosphere": "静得能听见雨线擦过栏杆",
                    "opening_situation": "两个人谁也没走，反而把声音压得更低。",
                    "public_goal": "顺着刚才的话再往里探一步。",
                    "hidden_tension": "谁先心软谁就先露了底。",
                    "scene_drive": "让场面继续收紧，不急着换幕。",
                    "expected_rhythm": "缓慢加压",
                    "forbidden_topics": "外人",
                }
            )
            service.save_scene_card(
                fields={
                    "title": "转入花厅",
                    "time_hint": "夜深",
                    "location": "花厅",
                    "atmosphere": "人多却更安静",
                    "opening_situation": "雨势更大，众人被催着转到花厅落座。",
                    "public_goal": "先把场面稳住。",
                    "hidden_tension": "真正要问的话还压在心口。",
                    "scene_drive": "从试探转向更公开的拉扯。",
                    "expected_rhythm": "三句一推进",
                    "forbidden_topics": "旧案",
                }
            )
            run = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    run["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[
                    {
                        "speaker": "场景提示",
                        "message": "回廊里只剩雨声和一句没说完的话。",
                    }
                ],
            ):
                session = service.create_dialogue_session(
                    run["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉"],
                    scene_card_id=current_scene["card_id"],
                )

            service.dialogue.update_scene_progress_state(
                run["run_id"],
                session["session_id"],
                {
                    "location": "回廊",
                    "time_hint": "深夜",
                    "atmosphere_summary": "雨声压着话头，谁都没有退开",
                    "beat_maturity": 22,
                    "should_offer_scene_shift": False,
                    "scene_shift_reason": "",
                    "world_tension_summary": "两个人都还在试探，还没到换场的时候",
                },
            )

            payload = service.recommend_dialogue_scene_card(
                run["run_id"], session_id=session["session_id"]
            )

            self.assertEqual(payload["recommended_card_id"], same_location["card_id"])
            self.assertIn(
                "生成一个自然开场",
                str(payload.get("recommended_auto_continue_message", "")).strip(),
            )

    def test_dialogue_scene_card_recommendation_uses_runtime_shift_reason_in_transition_hint(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            current_scene = service.save_scene_card(
                fields={
                    "title": "雨夜回廊",
                    "time_hint": "深夜",
                    "location": "回廊",
                    "atmosphere": "雨声压得人心发紧",
                    "opening_situation": "两个人被雨隔在檐下，话已经逼到边上。",
                    "public_goal": "先稳住表面客气。",
                    "hidden_tension": "真正的问题已经快藏不住了。",
                    "scene_drive": "让试探逼近摊牌。",
                    "expected_rhythm": "慢热",
                    "forbidden_topics": "前尘",
                }
            )
            next_scene = service.save_scene_card(
                fields={
                    "title": "灯下入席",
                    "time_hint": "夜深",
                    "location": "花厅",
                    "atmosphere": "灯火亮着，谁都更难回避彼此",
                    "opening_situation": "雨脚催着众人换到花厅，落座后谁也没先碰茶。",
                    "public_goal": "把表面话撑到头。",
                    "hidden_tension": "下一句就可能把真正心思挑明。",
                    "scene_drive": "让局面顺势从回避转向正面相对。",
                    "expected_rhythm": "越聊越紧",
                    "forbidden_topics": "闲话",
                }
            )
            service.save_scene_card(
                fields={
                    "title": "回廊再压一拍",
                    "time_hint": "深夜",
                    "location": "回廊",
                    "atmosphere": "雨线更急，但还是没人挪步",
                    "opening_situation": "两个人还站在原地，只把语气压得更轻。",
                    "public_goal": "把上一句试探再咬紧一点。",
                    "hidden_tension": "谁先退让谁就输了这口气。",
                    "scene_drive": "继续在原地消磨彼此的耐心。",
                    "expected_rhythm": "慢压",
                    "forbidden_topics": "旁人",
                }
            )
            run = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    run["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[
                    {
                        "speaker": "场景提示",
                        "message": "雨已经大到不得不换个地方把话说完。",
                    }
                ],
            ):
                session = service.create_dialogue_session(
                    run["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉"],
                    scene_card_id=current_scene["card_id"],
                )

            service.dialogue.update_scene_progress_state(
                run["run_id"],
                session["session_id"],
                {
                    "location": "回廊",
                    "time_hint": "深夜",
                    "atmosphere_summary": "雨势更重，回避已经压不住了",
                    "beat_maturity": 82,
                    "should_offer_scene_shift": True,
                    "scene_shift_reason": "雨势压得两人都没法再站在回廊里装作无事",
                    "world_tension_summary": "再拖一两句，局面就会逼到必须正面开口",
                },
            )

            payload = service.recommend_dialogue_scene_card(
                run["run_id"], session_id=session["session_id"]
            )

            self.assertEqual(payload["recommended_card_id"], next_scene["card_id"])
            self.assertIn(
                "雨势压得两人都没法再站在回廊里装作无事",
                payload["recommended_transition_message"],
            )
            self.assertTrue(
                str(payload.get("recommended_auto_continue_message", "")).strip()
            )

    def test_dialogue_scene_history_tracks_initial_scene_and_switches(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            first_scene = service.save_scene_card(
                fields={
                    "title": "回廊夜谈",
                    "time_hint": "深夜",
                    "location": "回廊",
                    "atmosphere": "安静发紧",
                    "opening_situation": "两人隔着雨声说话。",
                    "public_goal": "先探来意。",
                    "hidden_tension": "旧事随时会被挑开。",
                    "scene_drive": "把试探慢慢逼紧。",
                    "expected_rhythm": "慢热",
                    "forbidden_topics": "前尘",
                }
            )
            second_scene = service.save_scene_card(
                fields={
                    "title": "转入花厅",
                    "time_hint": "夜深",
                    "location": "花厅",
                    "atmosphere": "表面客套，暗地收紧",
                    "opening_situation": "雨势更大，众人不得不转入花厅。",
                    "public_goal": "先把场面稳住。",
                    "hidden_tension": "真正要问的话终于躲不过去。",
                    "scene_drive": "从试探推向摊牌。",
                    "expected_rhythm": "三句一推进",
                    "forbidden_topics": "旧账",
                }
            )
            run = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    run["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session = service.create_dialogue_session(
                    run["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉"],
                    scene_card_id=first_scene["card_id"],
                )

            switched = service.switch_dialogue_scene_card(
                run["run_id"],
                session_id=session["session_id"],
                scene_card_id=second_scene["card_id"],
                transition_message="雨势更大，众人转入花厅。",
            )

            history = switched["scene_history"]
            self.assertEqual(len(history), 2)
            self.assertEqual(history[0]["title"], "回廊夜谈")
            self.assertEqual(history[1]["title"], "转入花厅")
            self.assertEqual(
                history[1]["transition_message"], "雨势更大，众人转入花厅。"
            )
            self.assertEqual(history[1]["is_current"], "true")

    def test_switch_dialogue_scene_card_can_auto_continue_new_scene(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            first_scene = service.save_scene_card(
                fields={
                    "title": "回廊夜谈",
                    "time_hint": "深夜",
                    "location": "回廊",
                    "atmosphere": "安静发紧",
                    "opening_situation": "两人隔着雨声说话。",
                    "public_goal": "先探来意。",
                    "hidden_tension": "旧事随时会被挑开。",
                    "scene_drive": "把试探慢慢逼紧。",
                    "expected_rhythm": "慢热",
                    "forbidden_topics": "前尘",
                }
            )
            second_scene = service.save_scene_card(
                fields={
                    "title": "转入花厅",
                    "time_hint": "夜深",
                    "location": "花厅",
                    "atmosphere": "表面客套，暗地收紧",
                    "opening_situation": "雨势更大，众人不得不转入花厅。",
                    "public_goal": "先把场面稳住。",
                    "hidden_tension": "真正要问的话终于躲不过去。",
                    "scene_drive": "从试探推向摊牌。",
                    "expected_rhythm": "三句一推进",
                    "forbidden_topics": "旧账",
                }
            )
            run = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    run["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                side_effect=[
                    [{"speaker": "场景提示", "message": "开场。"}],
                    [
                        {
                            "speaker": "林黛玉",
                            "message": "（她抬眼看了看门外雨势）进了花厅，也未见得就好说。",
                        }
                    ],
                ],
            ):
                session = service.create_dialogue_session(
                    run["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉"],
                    scene_card_id=first_scene["card_id"],
                )
                switched = service.switch_dialogue_scene_card(
                    run["run_id"],
                    session_id=session["session_id"],
                    scene_card_id=second_scene["card_id"],
                    transition_message="雨势更大，众人转入花厅。",
                    auto_continue=True,
                )

            transcript = list(switched.get("transcript", []) or [])
            self.assertEqual(
                switched["session_card"]["scene_card"]["title"], "转入花厅"
            )
            self.assertTrue(
                any(
                    "众人转入花厅" in str(item.get("message", ""))
                    for item in transcript
                )
            )
            self.assertTrue(
                any(str(item.get("speaker", "")) == "林黛玉" for item in transcript)
            )
            self.assertEqual(switched.get("status"), "ready")
            self.assertFalse(bool(switched.get("pending_turn")))

    def test_branch_dialogue_session_from_scene_creates_new_session(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            first_scene = service.save_scene_card(
                fields={
                    "title": "回廊夜谈",
                    "time_hint": "深夜",
                    "location": "回廊",
                    "atmosphere": "安静发紧",
                    "opening_situation": "两人隔着雨声说话。",
                    "public_goal": "先探来意。",
                    "hidden_tension": "旧事随时会被挑开。",
                    "scene_drive": "把试探慢慢逼紧。",
                    "expected_rhythm": "慢热",
                    "forbidden_topics": "前尘",
                }
            )
            second_scene = service.save_scene_card(
                fields={
                    "title": "花厅再会",
                    "time_hint": "夜深",
                    "location": "花厅",
                    "atmosphere": "表面松，暗地紧",
                    "opening_situation": "众人转入花厅，谁都还没真正把话挑明。",
                    "public_goal": "把场面先稳住。",
                    "hidden_tension": "真正要问的话已经逼到嘴边。",
                    "scene_drive": "把客套推成摊牌。",
                    "expected_rhythm": "三句一推进",
                    "forbidden_topics": "旧账",
                }
            )
            run = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    run["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session = service.create_dialogue_session(
                    run["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉"],
                    scene_card_id=first_scene["card_id"],
                )

            service.switch_dialogue_scene_card(
                run["run_id"],
                session_id=session["session_id"],
                scene_card_id=second_scene["card_id"],
                transition_message="雨势更大，众人转入花厅。",
            )
            branch = service.branch_dialogue_session_from_scene(
                run["run_id"],
                session_id=session["session_id"],
                scene_index=1,
            )

            self.assertNotEqual(branch["session_id"], session["session_id"])
            self.assertEqual(branch["session_card"]["scene_card"]["title"], "花厅再会")
            self.assertEqual(branch["session_card"]["scene_card"]["location"], "花厅")
            self.assertEqual(branch["scene_history"][0]["title"], "花厅再会")
            self.assertEqual(branch["branch_origin"]["scene_title"], "花厅再会")
            self.assertTrue(
                str(branch["session_memory_summary"]["recap"]).startswith("承接旧线：")
            )

    def test_scene_card_crud_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)

            created = service.save_scene_card(
                fields={
                    "title": "雨夜探院",
                    "time_hint": "二更将尽",
                    "location": "偏院回廊",
                    "atmosphere": "雨声压低了人声，气氛发紧",
                    "opening_situation": "众人刚散，只有两个人被一场突雨逼回檐下。",
                    "public_goal": "先把这场偶遇说圆。",
                    "hidden_tension": "谁都知道这不是单纯偶遇，却都先不点破。",
                    "scene_drive": "让试探一步步变成摊牌。",
                    "expected_rhythm": "慢热试探，越聊越绷紧",
                    "forbidden_topics": "旧事；家中真正站队",
                }
            )

            self.assertTrue(created["card_id"])
            self.assertEqual(created["fields"]["title"], "雨夜探院")

            listed = service.list_scene_cards()
            self.assertEqual(len(listed), 1)
            self.assertEqual(listed[0]["card_id"], created["card_id"])

            fetched = service.get_scene_card(created["card_id"])
            self.assertEqual(fetched["fields"]["scene_drive"], "让试探一步步变成摊牌。")

            deleted = service.delete_scene_card(created["card_id"])
            self.assertEqual(deleted["status"], "deleted")
            self.assertEqual(service.list_scene_cards(), [])

    def test_self_card_crud_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)

            created = service.save_self_card(
                fields={
                    "display_name": "阿眠",
                    "scene_identity": "误入席间的来客",
                    "interaction_style": "先试探后松弛",
                    "core_identity": "机敏的局外人",
                    "story_role": "搅动静局的人",
                    "identity_anchor": "见招拆招，总要先摸清局面",
                    "temperament_type": "温醒带锋",
                    "soul_goal": "先活明白，再选站哪边",
                    "core_traits": "敏锐；会看人；嘴上留分寸",
                    "key_bonds": "自己；眼前局势",
                    "speech_style": "先轻后准，不把话说死",
                    "worldview": "局面比道理先到，真心却不能全赔进去。",
                    "belief_anchor": "再乱的场，也得先给自己留一条路。",
                    "moral_bottom_line": "不拿无辜的人去垫脚。",
                    "restraint_threshold": "被逼着选边站时才会真正翻脸。",
                    "stress_response": "越紧越会先把话说轻，再慢慢收口。",
                }
            )

            self.assertTrue(created["card_id"])
            self.assertEqual(created["fields"]["display_name"], "阿眠")

            listed = service.list_self_cards()
            self.assertEqual(len(listed), 1)
            self.assertEqual(listed[0]["card_id"], created["card_id"])

            fetched = service.get_self_card(created["card_id"])
            self.assertEqual(fetched["fields"]["core_identity"], "机敏的局外人")

            deleted = service.delete_self_card(created["card_id"])
            self.assertEqual(deleted["status"], "deleted")
            self.assertEqual(service.list_self_cards(), [])

    def test_opening_preset_crud_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)

            created = service.save_opening_preset(
                fields={
                    "title": "雨夜试探局",
                    "note": "三人慢慢试探，不要一开口就摊牌。",
                    "mode": "insert",
                    "participants": ["林黛玉", "贾宝玉", "薛宝钗"],
                    "controlled_character": "",
                    "scene_card_id": "",
                    "scene_card": {},
                    "self_card_id": "",
                    "self_card": {},
                    "self_name": "阿眠",
                    "self_identity": "借住府中的外客",
                    "self_style": "先轻后紧",
                }
            )

            self.assertTrue(created["card_id"])
            self.assertEqual(created["preview"]["title"], "雨夜试探局")
            self.assertEqual(created["preview"]["self_name"], "阿眠")

            listed = service.list_opening_presets()
            self.assertEqual(len(listed), 1)
            self.assertEqual(listed[0]["card_id"], created["card_id"])

            fetched = service.get_opening_preset(created["card_id"])
            self.assertEqual(fetched["fields"]["mode"], "insert")
            self.assertEqual(
                fetched["fields"]["participants"], ["林黛玉", "贾宝玉", "薛宝钗"]
            )

            deleted = service.delete_opening_preset(created["card_id"])
            self.assertEqual(deleted["status"], "deleted")
            self.assertEqual(service.list_opening_presets(), [])

    def test_opening_preset_keeps_snapshots_after_cards_deleted(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)

            scene_card = service.save_scene_card(
                fields={
                    "title": "花厅夜宴",
                    "time_hint": "入夜",
                    "location": "花厅",
                    "atmosphere": "笑里带锋",
                    "opening_situation": "众人都在等谁先把话递出去。",
                    "public_goal": "先把席面稳住。",
                    "hidden_tension": "每个人都在看彼此站哪边。",
                    "scene_drive": "让试探慢慢逼近真话。",
                    "expected_rhythm": "慢热试探",
                    "forbidden_topics": "旧账",
                }
            )
            self_card = service.save_self_card(
                fields={
                    "display_name": "阿眠",
                    "scene_identity": "借住府中的外客",
                    "interaction_style": "先轻后紧",
                    "core_identity": "会看局的人",
                    "story_role": "外来变量",
                    "identity_anchor": "先看局面再递话",
                    "temperament_type": "轻醒克制",
                    "soul_goal": "给自己挣一块能站稳的地方",
                    "core_traits": "敏锐；留分寸；有后手",
                    "key_bonds": "自己；少数值得信的人",
                    "speech_style": "先轻描淡写，再慢慢收紧",
                    "worldview": "热闹背后总有人在算账。",
                    "belief_anchor": "先护住自己，才谈得上护别人。",
                    "moral_bottom_line": "不拿无辜人垫脚。",
                    "restraint_threshold": "被逼着替别人背锅时会翻脸。",
                    "stress_response": "越紧越像在闲谈。",
                }
            )

            preset = service.save_opening_preset(
                fields={
                    "title": "夜宴自己入席",
                    "note": "适合慢慢把气氛绷起来。",
                    "mode": "insert",
                    "participants": ["林黛玉", "贾宝玉"],
                    "scene_card_id": scene_card["card_id"],
                    "scene_card": {
                        "card_id": scene_card["card_id"],
                        "fields": scene_card["fields"],
                        "preview": scene_card["preview"],
                    },
                    "self_card_id": self_card["card_id"],
                    "self_card": {
                        "card_id": self_card["card_id"],
                        "fields": self_card["fields"],
                        "preview": self_card["preview"],
                    },
                    "self_name": "阿眠",
                    "self_identity": "借住府中的外客",
                    "self_style": "先轻后紧",
                }
            )

            service.delete_scene_card(scene_card["card_id"])
            service.delete_self_card(self_card["card_id"])

            fetched = service.get_opening_preset(preset["card_id"])
            self.assertEqual(fetched["preview"]["scene_title"], "花厅夜宴")
            self.assertEqual(fetched["preview"]["self_name"], "阿眠")
            self.assertEqual(
                fetched["fields"]["scene_card"]["fields"]["location"], "花厅"
            )
            self.assertEqual(
                fetched["fields"]["self_card"]["fields"]["core_identity"], "会看局的人"
            )

    def test_generate_self_card_returns_complete_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            fake_parts = Mock()
            fake_parts.llm.chat_completion = Mock(
                return_value={
                    "content": json.dumps(
                        {
                            "display_name": "沈雾",
                            "scene_identity": "寄住高门的外来账房",
                            "interaction_style": "试探里带一点笑",
                            "core_identity": "善算局的外来人",
                            "story_role": "让旧局失衡的新变量",
                            "identity_anchor": "先看谁在装稳，再决定把话递给谁",
                            "temperament_type": "松弛机警",
                            "soul_goal": "替自己挣一条能站稳的路",
                            "hidden_desire": "想有人真正把她当自己人",
                            "inner_conflict": "既想靠近热闹，又怕真心被拿去做账",
                            "self_cognition": "知道自己最会看缝下针",
                            "private_self": "一个人时反而安静",
                            "speech_style": "先轻描淡写，再慢慢逼近重点",
                            "cadence": "句子不急，尾音常常收住",
                            "typical_lines": "这话也不必说满；容我再看一步",
                            "signature_phrases": "不急；再看一步",
                            "sentence_openers": "先；容我",
                            "sentence_endings": "也罢；就是了",
                            "social_mode": "见人下菜，却不轻贱人",
                            "thinking_style": "先拆局，再找最省力的入口",
                            "decision_rules": "先保余地；再押关键人",
                            "reward_logic": "肯把力气用在会回看自己的人身上",
                            "worldview": "局势会骗人，人心却总在细处漏底。",
                            "belief_anchor": "给自己留路，不等于先把心卖掉。",
                            "moral_bottom_line": "不把无辜者推到刀口前。",
                            "restraint_threshold": "被逼着替人背锅时会彻底翻脸。",
                            "core_traits": "敏锐；会周旋；不轻信",
                            "key_bonds": "自己；局中少数真心人",
                            "forbidden_behaviors": "替人白白送命；空口效忠",
                            "stress_response": "越乱越像在闲谈，其实脑子转得更快",
                            "emotion_model": "情绪不先上脸，先藏进字缝里",
                            "anger_style": "声音更轻，话却更准",
                            "joy_style": "笑意不大，却会多给一步台阶",
                            "grievance_style": "不立刻诉苦，反而更客气",
                            "others_impression": "看着和气，实则很会拿分寸",
                        },
                        ensure_ascii=False,
                    )
                }
            )

            with patch.object(service, "_build_runtime_parts", return_value=fake_parts):
                payload = service.generate_self_card()

            self.assertEqual(payload["fields"]["display_name"], "沈雾")
            self.assertEqual(payload["fields"]["core_identity"], "善算局的外来人")
            self.assertEqual(
                payload["preview"]["speech_style"], "先轻描淡写，再慢慢逼近重点"
            )

    def test_create_dialogue_session_uses_selected_self_card_profile(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            card = service.save_self_card(
                fields={
                    "display_name": "阿眠",
                    "scene_identity": "园中借住的外客",
                    "interaction_style": "初见试探",
                    "core_identity": "看得懂人情的局外人",
                    "story_role": "掀开静水的一只手",
                    "identity_anchor": "先看局，再决定要不要把真话说透",
                    "temperament_type": "轻醒克制",
                    "soul_goal": "给自己争一个不必仰人鼻息的位置",
                    "core_traits": "敏锐；稳口风；会留后手",
                    "key_bonds": "自己；少数值得信的人",
                    "speech_style": "柔声开口，话尾常带一点试探",
                    "worldview": "热闹场面里，真正要紧的总是没说出口的那句。",
                    "belief_anchor": "先护住自己，才谈得上护别人。",
                    "moral_bottom_line": "不借别人的血给自己铺路。",
                    "restraint_threshold": "被人逼着替错局收尾时会转硬。",
                    "stress_response": "越紧张越像在闲话家常。",
                }
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    payload["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session = service.create_dialogue_session(
                    payload["run_id"],
                    mode="insert",
                    participants=["林黛玉", "贾宝玉"],
                    self_card_id=card["card_id"],
                    self_profile={},
                )

            self.assertEqual(session["session_card"]["self_card_id"], card["card_id"])
            self.assertEqual(
                session["session_card"]["self_insert"]["display_name"], "阿眠"
            )
            self.assertEqual(
                session["session_card"]["self_insert"]["core_identity"],
                "看得懂人情的局外人",
            )

    def test_insert_session_keeps_snapshot_after_self_card_deleted(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            card = service.save_self_card(
                fields={
                    "display_name": "阿眠",
                    "scene_identity": "园中借住的外客",
                    "interaction_style": "初见试探",
                    "core_identity": "看得懂人情的局外人",
                    "story_role": "掀开静水的一只手",
                    "identity_anchor": "先看局，再决定要不要把真话说透",
                    "temperament_type": "轻醒克制",
                    "soul_goal": "给自己争一个不必仰人鼻息的位置",
                    "core_traits": "敏锐；稳口风；会留后手",
                    "key_bonds": "自己；少数值得信的人",
                    "speech_style": "柔声开口，话尾常带一点试探",
                    "worldview": "热闹场面里，真正要紧的总是没说出口的那句。",
                    "belief_anchor": "先护住自己，才谈得上护别人。",
                    "moral_bottom_line": "不借别人的血给自己铺路。",
                    "restraint_threshold": "被人逼着替错局收尾时会转硬。",
                    "stress_response": "越紧张越像在闲话家常。",
                }
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    payload["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "开场。"}],
            ):
                session = service.create_dialogue_session(
                    payload["run_id"],
                    mode="insert",
                    participants=["林黛玉", "贾宝玉"],
                    self_card_id=card["card_id"],
                    self_profile={},
                )

            service.delete_self_card(card["card_id"])

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "林黛玉", "message": "你这话倒说得轻。"}],
            ):
                replied = service.reply_dialogue_turn(
                    payload["run_id"],
                    session_id=session["session_id"],
                    message="我只是先来看看风向。",
                )

            self.assertEqual(
                replied["session_card"]["self_insert"]["display_name"], "阿眠"
            )
            self.assertEqual(replied["transcript"][-2]["speaker"], "阿眠")
            self.assertEqual(replied["transcript"][-1]["speaker"], "林黛玉")

    def test_session_keeps_scene_card_snapshot_after_scene_card_deleted(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            scene_card = service.save_scene_card(
                fields={
                    "title": "花厅夜宴",
                    "time_hint": "掌灯时分",
                    "location": "花厅暖阁",
                    "atmosphere": "灯火明亮，席间暗潮涌动",
                    "opening_situation": "席上看似热闹，真正要说的话却都压在杯盏间。",
                    "public_goal": "把场面撑得体面周全。",
                    "hidden_tension": "有人想借席间一句话逼出真正立场。",
                    "scene_drive": "从寒暄慢慢推到失手说破。",
                    "expected_rhythm": "前松后紧",
                    "forbidden_topics": "旧案；婚事",
                }
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    payload["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "花厅里酒气微暖。"}],
            ):
                session = service.create_dialogue_session(
                    payload["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉"],
                    scene_card_id=scene_card["card_id"],
                )

            service.delete_scene_card(scene_card["card_id"])

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[
                    {"speaker": "贾宝玉", "message": "这话怎么偏偏此刻提起。"}
                ],
            ):
                replied = service.reply_dialogue_turn(
                    payload["run_id"],
                    session_id=session["session_id"],
                    message="门外忽然传来一阵急促脚步声。",
                    message_kind="narration",
                )

            self.assertEqual(replied["session_card"]["scene_card"]["title"], "花厅夜宴")
            self.assertEqual(
                replied["session_card"]["scene_card"]["location"], "花厅暖阁"
            )

    def test_switch_dialogue_scene_card_updates_snapshot_and_appends_transition(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            first_scene = service.save_scene_card(
                fields={
                    "title": "雨夜回廊",
                    "time_hint": "夜深",
                    "location": "回廊",
                    "atmosphere": "安静发紧",
                    "opening_situation": "两个人被雨声隔在檐下。",
                    "public_goal": "先把话探清。",
                    "hidden_tension": "谁也不愿先摊牌。",
                    "scene_drive": "从试探推向明说。",
                    "expected_rhythm": "慢热",
                    "forbidden_topics": "旧案",
                }
            )
            second_scene = service.save_scene_card(
                fields={
                    "title": "花厅对坐",
                    "time_hint": "掌灯后",
                    "location": "花厅",
                    "atmosphere": "表面客气，底下绷紧",
                    "opening_situation": "众人散后，只剩两盏灯和未尽的话。",
                    "public_goal": "把今晚的场面圆过去。",
                    "hidden_tension": "有人想逼出真正立场。",
                    "scene_drive": "把局势往摊牌再推一步。",
                    "expected_rhythm": "前松后紧",
                    "forbidden_topics": "婚事",
                }
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉", "贾宝玉"],
            )
            for name in ("林黛玉", "贾宝玉"):
                service.ingest_character_result(
                    payload["run_id"],
                    character=name,
                    content_base64=base64.b64encode(
                        f"- name: {name}\n- novel_id: hongloumeng\n- core_identity: 人物\n".encode(
                            "utf-8"
                        )
                    ).decode("ascii"),
                )

            with patch.object(
                WebRunService,
                "_generate_dialogue_responses",
                return_value=[{"speaker": "场景提示", "message": "雨还没停。"}],
            ):
                session = service.create_dialogue_session(
                    payload["run_id"],
                    mode="observe",
                    participants=["林黛玉", "贾宝玉"],
                    scene_card_id=first_scene["card_id"],
                )

            switched = service.switch_dialogue_scene_card(
                payload["run_id"],
                session_id=session["session_id"],
                scene_card_id=second_scene["card_id"],
                transition_message="雨势更大，众人只得移进花厅，把未说完的话接着往下说。",
            )

            self.assertEqual(
                switched["session_card"]["scene_card_id"], second_scene["card_id"]
            )
            self.assertEqual(
                switched["session_card"]["scene_card"]["title"], "花厅对坐"
            )
            self.assertEqual(switched["transcript"][-1]["speaker"], "场景提示")
            self.assertIn("移进花厅", switched["transcript"][-1]["message"])
            self.assertIn("花厅对坐", switched["session_memory_summary"]["scene_frame"])
