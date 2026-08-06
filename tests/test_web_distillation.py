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

class DistillationServiceTests(unittest.TestCase):
    def test_persona_web_references_filters_dictionary_like_results(self):
        fake_html = """
        <html><body>
          <li class="b_algo">
            <h2>江（汉语汉字）_百度百科</h2>
            <p>江，通用规范汉字，一级字，读作 jiang，常见于江河湖海的名称。</p>
          </li>
          <li class="b_algo">
            <h2>江澄角色介绍</h2>
            <p>江澄是《魔道祖师》中的重要角色，性格冷厉而重情，成长线鲜明。</p>
          </li>
        </body></html>
        """

        refs = collect_persona_web_references(
            character="江澄",
            novel_title="魔道祖师",
            fetch_text=lambda url, timeout: fake_html,
        )

        self.assertEqual(len(refs), 1)
        self.assertIn("江澄", refs[0]["title"])

    def test_build_distill_chunk_payloads_splits_large_excerpt(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.DISTILL_CHUNK_MAX_CHARS = 24
            service.DISTILL_CHUNK_MAX_SENTENCES = 2
            payload = {
                "prompt": "system",
                "references": {},
                "request": {
                    "excerpt": "第一句很长很长。第二句也很长很长。第三句依旧很长很长。第四句还是很长很长。",
                    "excerpt_stages": {
                        "start": "第一句很长很长。第二句也很长很长。",
                        "mid": "第三句依旧很长很长。第四句还是很长很长。",
                        "end": "",
                    },
                    "excerpt_focus": {"strategy": "character_windows"},
                },
                "meta": {},
            }

            chunks = service._build_distill_chunk_payloads(payload)

            self.assertGreaterEqual(len(chunks), 2)
            self.assertTrue(
                all(item["payload"]["request"]["excerpt"] for item in chunks)
            )
            self.assertTrue(
                any(str(item["label"]).startswith("前段") for item in chunks)
            )

    def test_chunk_parallel_workers_stays_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            config = Mock()
            config.get = Mock(
                side_effect=lambda key, default=None: {
                    "llm.provider": "openai-compatible",
                    "llm.parallel_chunk_workers": 3,
                }.get(key, default)
            )
            self.assertEqual(
                service._chunk_parallel_workers(config=config, chunk_total=1), 1
            )
            self.assertEqual(
                service._chunk_parallel_workers(config=config, chunk_total=2), 2
            )
            self.assertEqual(
                service._chunk_parallel_workers(config=config, chunk_total=5), 3
            )

    def test_generate_character_profile_markdown_falls_back_to_chunked_merge(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            manifest_path = Path(tmp) / "run_manifest.json"
            manifest_path.write_text(
                json.dumps(
                    {"control": {"stop_requested": False}}, ensure_ascii=False, indent=2
                )
                + "\n",
                encoding="utf-8",
            )
            payload = {
                "prompt": "system",
                "references": {
                    "output_schema": "schema",
                    "style_differ": "style",
                    "logic_constraint": "logic",
                    "validation_policy": "policy",
                },
                "request": {
                    "excerpt": "甲说。乙说。",
                    "excerpt_stages": {"start": "甲说。", "mid": "乙说。", "end": ""},
                    "excerpt_focus": {"strategy": "character_windows"},
                },
                "meta": {"novel_id": "demo"},
            }
            fake_parts = Mock()
            fake_parts.llm.chat_completion = Mock(
                side_effect=[
                    LLMRequestError("LLM 连接失败: [WinError 10054]"),
                    {
                        "content": "# PROFILE\n- name: 甲\n- speech_style: 先压住再开口\n"
                    },
                    {
                        "content": "# PROFILE\n- name: 甲\n- speech_style: 句尾收得很轻\n"
                    },
                    {
                        "content": "# PROFILE\n- name: 甲\n- speech_style: 先压住再开口，句尾收得很轻\n"
                    },
                ]
            )

            with patch.object(
                service,
                "_build_distill_chunk_payloads",
                return_value=[
                    {"label": "前段", "payload": payload},
                    {"label": "中段", "payload": payload},
                ],
            ):
                content, meta = service._generate_character_profile_markdown(
                    parts=fake_parts,
                    config=Mock(
                        get=Mock(side_effect=lambda key, default=None: default)
                    ),
                    manifest_path=manifest_path,
                    payload=payload,
                    character="甲",
                    peer_characters=["甲", "乙"],
                )

            self.assertIn("speech_style", content)
            self.assertTrue(meta["chunked"])
            self.assertEqual(meta["chunk_count"], 2)
            self.assertEqual(fake_parts.llm.chat_completion.call_count, 4)

    def test_generate_relation_markdown_falls_back_to_chunked_merge(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            manifest_path = Path(tmp) / "run_manifest.json"
            manifest_path.write_text(
                json.dumps(
                    {"control": {"stop_requested": False}}, ensure_ascii=False, indent=2
                )
                + "\n",
                encoding="utf-8",
            )
            payload = {
                "prompt": "system",
                "references": {
                    "output_schema": "schema",
                    "logic_constraint": "logic",
                    "validation_policy": "policy",
                },
                "request": {
                    "excerpt": "甲见乙。乙应甲。",
                    "excerpt_stages": {
                        "start": "甲见乙。",
                        "mid": "乙应甲。",
                        "end": "",
                    },
                    "excerpt_focus": {"strategy": "character_windows"},
                },
                "meta": {"novel_id": "demo"},
            }
            fake_parts = Mock()
            fake_parts.llm.chat_completion = Mock(
                side_effect=[
                    LLMRequestError("LLM 连接失败: [WinError 10054]"),
                    {
                        "content": "# RELATION_GRAPH\n\n## 甲_乙\n- trust: 7\n- affection: 3\n- power_gap: 0\n- conflict_point: 立场试探\n- typical_interaction: 观察与回应\n- hidden_attitude: \n- relation_change: 固化\n- appellation_to_target: 乙\n- confidence: 7\n"
                    },
                    {
                        "content": "# RELATION_GRAPH\n\n## 甲_乙\n- trust: 8\n- affection: 4\n- power_gap: 0\n- conflict_point: 互相试探\n- typical_interaction: 追问与回应\n- hidden_attitude: \n- relation_change: 升温\n- appellation_to_target: 乙\n- confidence: 7\n"
                    },
                    {
                        "content": "# RELATION_GRAPH\n\n## 甲_乙\n- trust: 8\n- affection: 4\n- power_gap: 0\n- conflict_point: 互相试探\n- typical_interaction: 观察、追问与回应\n- hidden_attitude: \n- relation_change: 反复波动\n- appellation_to_target: 乙\n- confidence: 8\n"
                    },
                ]
            )

            with patch.object(
                service,
                "_build_relation_chunk_payloads",
                return_value=[
                    {"label": "前段", "payload": payload},
                    {"label": "中段", "payload": payload},
                ],
            ):
                content, meta = service._generate_relation_markdown(
                    parts=fake_parts,
                    config=Mock(
                        get=Mock(side_effect=lambda key, default=None: default)
                    ),
                    manifest_path=manifest_path,
                    payload=payload,
                    characters=["甲", "乙"],
                )

            self.assertIn("RELATION_GRAPH", content)
            self.assertTrue(meta["chunked"])
            self.assertEqual(meta["chunk_count"], 2)
            self.assertEqual(fake_parts.llm.chat_completion.call_count, 4)

    def test_finalize_generated_profile_source_backfills_evidence_counts_from_payload(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            source_path = Path(tmp) / "PROFILE.generated.md"
            source_path.write_text(
                "# PROFILE\n"
                "- name: 魏无羡\n"
                "- novel_id: 魔道祖师\n"
                "- worldview: 人心自有轻重。\n"
                "- description_count: 0\n"
                "- dialogue_count: 0\n"
                "- thought_count: 0\n"
                "- chunk_count: 0\n"
                "- evidence_source: \n",
                encoding="utf-8",
            )
            payload = {
                "request": {
                    "excerpt": "魏无羡笑道：“先别慌。”\n江澄心想此事绝不简单。\n夷陵风声渐紧。",
                    "excerpt_stages": {
                        "start": "魏无羡笑道：“先别慌。”",
                        "mid": "江澄心想此事绝不简单。",
                        "end": "夷陵风声渐紧。",
                    },
                    "excerpt_focus": {"strategy": "character_windows_mixed"},
                }
            }

            service._finalize_generated_profile_source(
                source_path, payload=payload, chunk_count=3
            )
            content = source_path.read_text(encoding="utf-8")

            self.assertIn("- description_count: 1", content)
            self.assertIn("- dialogue_count: 1", content)
            self.assertIn("- thought_count: 1", content)
            self.assertIn("- chunk_count: 3", content)
            self.assertIn(
                "- evidence_source: excerpt:start；excerpt:mid；excerpt:end；strategy:character_windows_mixed",
                content,
            )
            evidence_path = source_path.with_name("EVIDENCE.generated.json")
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            self.assertEqual(evidence["schema_version"], "persona-evidence/v1")
            self.assertEqual(evidence["character"], "魏无羡")
            self.assertEqual(evidence["reference_count"], 3)
            self.assertEqual(evidence["references"][0]["kind"], "dialogue")

    def test_profile_repair_triggers_when_completion_fields_are_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            source_path = Path(tmp) / "PROFILE.generated.md"
            source_path.write_text(
                "# PROFILE\n"
                "- name: 魏无羡\n"
                "- novel_id: 魔道祖师\n"
                "- worldview: 人心自有轻重。\n"
                "- belief_anchor: 人总得护住自己想护的。\n"
                "- moral_bottom_line: 不会主动把无辜者推进死局。\n"
                "- restraint_threshold: 真被逼到绝路时会掀桌。\n"
                "- stress_response: 压得越狠越像没事。\n"
                "- speech_style: 先笑后刺。\n",
                encoding="utf-8",
            )
            fake_parts = Mock()
            fake_parts.llm.chat_completion = Mock(
                return_value={
                    "content": "# PROFILE\n- name: 魏无羡\n- soul_goal: 护住该护的人\n"
                }
            )
            payload = {
                "prompt": "系统提示",
                "references": {
                    "output_schema": "schema",
                    "style_differ": "style",
                    "logic_constraint": "logic",
                    "validation_policy": "policy",
                },
                "request": {
                    "excerpt": "魏无羡笑道：“先别慌。”\n江澄心想此事绝不简单。\n夷陵风声渐紧。",
                    "excerpt_stages": {
                        "start": "魏无羡笑道：“先别慌。”",
                        "mid": "江澄心想此事绝不简单。",
                        "end": "夷陵风声渐紧。",
                    },
                    "excerpt_focus": {"strategy": "character_windows_mixed"},
                },
                "meta": {"novel_id": "魔道祖师"},
            }

            repaired = service._maybe_repair_generated_profile(
                parts=fake_parts,
                config=Mock(get=Mock(side_effect=lambda key, default=None: default)),
                payload=payload,
                character="魏无羡",
                peer_characters=["魏无羡", "蓝忘机"],
                source_path=source_path,
            )

            self.assertIsNotNone(repaired)
            self.assertGreaterEqual(fake_parts.llm.chat_completion.call_count, 1)

    def test_extract_dialogue_evidence_prioritizes_character_specific_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            payload = {
                "request": {
                    "excerpt": "\n".join(
                        [
                            "袭人笑道：“今日倒热闹。”",
                            "麝月道：“且先坐下。”",
                            "众人都笑了起来。",
                            "薛宝钗笑道：“这话也太急了些。”",
                            "宝钗心想此事还得再看一步。",
                            "探春道：“先把话说清楚。”",
                        ]
                    ),
                    "excerpt_stages": {
                        "start": "袭人笑道：“今日倒热闹。”\n麝月道：“且先坐下。”",
                        "mid": "薛宝钗笑道：“这话也太急了些。”\n宝钗心想此事还得再看一步。",
                        "end": "探春道：“先把话说清楚。”",
                    },
                }
            }

            evidence = service._extract_dialogue_evidence(payload, character="薛宝钗")

            self.assertGreaterEqual(len(evidence), 2)
            self.assertEqual(evidence[0], "薛宝钗笑道：“这话也太急了些。”")
            self.assertEqual(evidence[1], "宝钗心想此事还得再看一步。")

    def test_extract_dialogue_evidence_matches_traditional_character_variants(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            payload = {
                "request": {
                    "excerpt": "薛寶釵笑道：「你先别急。」\n眾人一时无话。",
                    "excerpt_stages": {
                        "start": "薛寶釵笑道：「你先别急。」",
                        "mid": "",
                        "end": "",
                    },
                }
            }

            evidence = service._extract_dialogue_evidence(payload, character="薛宝钗")

            self.assertIn("薛寶釵笑道：「你先别急。」", evidence)

    def test_automatic_pipeline_repairs_risky_relation_scalars_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="hongloumeng.txt",
                novel_content_base64=base64.b64encode(
                    "林黛玉见了贾宝玉。".encode("utf-8")
                ).decode("ascii"),
                characters=["林黛玉"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            manifest_path = run_dir / "run_manifest.json"
            novel_path = run_dir / "input" / "hongloumeng.txt"

            class _FakePathProvider:
                def __init__(self, base_dir: Path) -> None:
                    self.base_dir = base_dir

                def characters_root(self, novel_id: str) -> Path:
                    path = self.base_dir / "artifacts" / "characters" / novel_id
                    path.mkdir(parents=True, exist_ok=True)
                    return path

                def relations_file(self, novel_id: str) -> Path:
                    path = (
                        self.base_dir
                        / "artifacts"
                        / "relations"
                        / f"{novel_id}_relations.md"
                    )
                    path.parent.mkdir(parents=True, exist_ok=True)
                    return path

            fake_parts = Mock()
            fake_parts.path_provider = _FakePathProvider(run_dir)
            fake_parts.llm.chat_completion = Mock(
                side_effect=[
                    {
                        "content": "# PROFILE\n- name: 林黛玉\n- novel_id: hongloumeng\n- core_identity: 贾府外来才女\n- speech_style: 清冷里带一点针锋\n- cadence: 轻声慢落却藏锋\n- signature_phrases: 你也不用哄我；我原知道\n- typical_lines: 你也不用哄我；我原知道你心里有数\n- sentence_openers: 你也；我原\n- sentence_endings: 罢了；也就如此\n- worldview: 世情热闹，真心稀薄。\n- belief_anchor: 真心不可轻负\n- moral_bottom_line: 不肯轻贱真情\n- restraint_threshold: 平日克制，唯独真心受损时会失控。\n- stress_response: 越委屈越先收住情绪，再把语气压得更冷。\n"
                    },
                    {
                        "content": "# RELATION_GRAPH\n\n## 林黛玉_贾宝玉\n- trust: 8\n- affection: 9\n- power_gap: 0\n- conflict_point: 转过大厅，宝玉心里还自狐疑，只听墙角边一阵呵呵大笑。\n- typical_interaction: 大家想着，宝玉却等不得了，也不等贾政的命，便说道：“旧诗有云：\n- hidden_attitude: \n- relation_change: 因为许多事情反复拉扯所以一直变化\n- appellation_to_target: 宝玉\n- confidence: 8\n"
                    },
                    {
                        "content": "# RELATION_GRAPH\n\n## 林黛玉_贾宝玉\n- trust: 8\n- affection: 9\n- power_gap: 0\n- conflict_point: 真心太重时容易因误会互伤。\n- typical_interaction: 常在试探、心软与安抚之间来回。\n- hidden_attitude: \n- relation_change: 反复波动\n- appellation_to_target: 宝玉\n- confidence: 8\n"
                    },
                ]
            )

            with (
                patch("src.web.workflow.build_runtime_parts", return_value=fake_parts),
                patch.object(service, "_maybe_repair_generated_profile", return_value=None),
            ):
                result = service._run_automatic_pipeline(
                    manifest_path=manifest_path,
                    novel_path=novel_path,
                    locked_characters=["林黛玉"],
                    max_sentences=120,
                    max_chars=50000,
                )

            self.assertTrue(result["success"])
            relation_path = (
                run_dir / "artifacts" / "relations" / "hongloumeng_relations.md"
            )
            relation_text = relation_path.read_text(encoding="utf-8")
            self.assertIn("反复波动", relation_text)
            self.assertNotIn("旧诗有云", relation_text)
            self.assertEqual(result["quality"]["relation_repairs"]["count"], 1)
            repair_messages = fake_parts.llm.chat_completion.call_args_list[2].args[0]
            self.assertIn("REPAIR_TASK", repair_messages[1]["content"])
            self.assertIn("关系图谱", repair_messages[1]["content"])

    def test_automatic_pipeline_uses_distinct_distill_stage_messages(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            service.save_model_settings(
                provider="openai-compatible",
                model="deepseek-chat",
                base_url="https://example.com/v1",
                api_key="sk-test",
            )
            payload = service.create_run(
                novel_name="novel.txt",
                novel_content_base64=base64.b64encode(
                    "Alpha meets Beta.".encode("utf-8")
                ).decode("ascii"),
                characters=["Alpha"],
            )
            run_dir = Path(tmp) / "runs" / payload["run_id"]
            manifest_path = run_dir / "run_manifest.json"
            novel_path = run_dir / "input" / "novel.txt"

            class _FakePathProvider:
                def __init__(self, base_dir: Path) -> None:
                    self.base_dir = base_dir

                def characters_root(self, novel_id: str) -> Path:
                    path = self.base_dir / "artifacts" / "characters" / novel_id
                    path.mkdir(parents=True, exist_ok=True)
                    return path

                def relations_file(self, novel_id: str) -> Path:
                    path = (
                        self.base_dir
                        / "artifacts"
                        / "relations"
                        / f"{novel_id}_relations.md"
                    )
                    path.parent.mkdir(parents=True, exist_ok=True)
                    return path

            fake_parts = Mock()
            fake_parts.path_provider = _FakePathProvider(run_dir)
            fake_parts.llm.chat_completion = Mock(
                side_effect=[
                    {
                        "content": "# PROFILE\n- name: Alpha\n- novel_id: novel\n- core_identity: 核心人物\n- soul_goal: 守住答案\n- speech_style: 先压低语气再落结论\n- cadence: 慢半拍后落点\n- signature_phrases: 先看清；别急着站位\n- typical_lines: 先看清再说；别急着站位\n- sentence_openers: 先；别急\n- sentence_endings: 再说；也罢\n- worldview: 先把局势看清，再决定站位。\n- belief_anchor: 关键时刻不能自乱阵脚。\n- moral_bottom_line: 不把同伴当代价随手抛掉。\n- restraint_threshold: 平时克制，底线被逼穿时才会失控。\n- stress_response: 压力越大越会先收声，再集中判断。\n"
                    },
                    {
                        "content": "# RELATION_GRAPH\n\n## Alpha_Beta\n- trust: 7\n- affection: 3\n- power_gap: 0\n- conflict_point: 立场试探\n- typical_interaction: 观察与回应\n- hidden_attitude: \n- relation_change: 固化\n- appellation_to_target: Beta\n- confidence: 7\n"
                    },
                ]
            )

            with (
                patch("src.web.workflow.build_runtime_parts", return_value=fake_parts),
                patch.object(service, "_maybe_repair_generated_profile", return_value=None),
            ):
                result = service._run_automatic_pipeline(
                    manifest_path=manifest_path,
                    novel_path=novel_path,
                    locked_characters=["Alpha"],
                    max_sentences=120,
                    max_chars=50000,
                )

            messages = [item["message"] for item in result["events"]]
            self.assertIn("已载入小说文本", messages)
            self.assertIn("已锁定 1 个待蒸馏角色", messages)
            self.assertIn("正在蒸馏 Alpha", messages)
            self.assertIn("正在落盘 Alpha", messages)

    def test_build_distill_llm_messages_include_stage_evidence_and_field_priorities(
        self,
    ):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            payload = {
                "prompt": "系统提示",
                "references": {
                    "output_schema": "schema",
                    "style_differ": "style",
                    "logic_constraint": "logic",
                    "validation_policy": "validation",
                },
                "request": {
                    "characters": ["贾宝玉"],
                    "excerpt": "总证据",
                    "excerpt_stages": {
                        "start": "贾宝玉初入大观园。",
                        "mid": "贾宝玉为黛玉伤神。",
                        "end": "贾宝玉看破繁华。",
                    },
                    "excerpt_focus": {
                        "requested_characters": ["贾宝玉"],
                        "matched_characters": ["贾宝玉"],
                        "missing_characters": [],
                        "strategy": "character_windows",
                    },
                    "update_mode": "create",
                    "existing_profiles": {},
                },
                "meta": {"novel_id": "hongloumeng"},
            }

            messages = service._build_distill_llm_messages(
                payload, character="贾宝玉", peer_characters=["林黛玉", "贾宝玉"]
            )
            self.assertEqual(messages[0]["content"], "系统提示")
            self.assertIn("FIELD_GROUPS", messages[1]["content"])
            self.assertIn("### START", messages[1]["content"])
            self.assertIn("DIALOGUE_STYLE", messages[1]["content"])
            self.assertIn("贾宝玉初入大观园", messages[1]["content"])
            self.assertIn("贾宝玉看破繁华", messages[1]["content"])

    def test_profile_repair_targets_flag_generic_style_when_dialogue_exists(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            profile = {
                "speech_style": "冷静克制",
                "cadence": "",
                "signature_phrases": [],
                "typical_lines": [],
                "sentence_openers": [],
                "sentence_endings": [],
                "worldview": "人情比功名更重。",
                "belief_anchor": "真情不可轻负。",
                "moral_bottom_line": "不轻贱真情。",
                "restraint_threshold": "平日克制，真心受损时会失控。",
                "stress_response": "压力越大越先把情绪压低。",
            }
            issues = service._collect_profile_repair_targets(
                profile,
                dialogue_evidence=["贾宝玉道：“你瞧瞧，这个好不好？”"],
            )

            self.assertIn("speech_style", issues)
            self.assertIn("cadence", issues)
            self.assertIn("signature_phrases", issues)

    def test_profile_completion_groups_are_limited_to_four_target_sections(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            groups = service._collect_profile_completion_groups({}, repair_targets={})

            self.assertEqual(
                [name for name, _, _ in groups],
                ["Inner Core", "Decision Logic", "Emotion And Stress", "Voice"],
            )

    def test_profile_completion_groups_treat_evidence_insufficient_as_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            groups = service._collect_profile_completion_groups(
                {
                    "soul_goal": "证据不足",
                    "speech_style": "证据不足",
                    "speech_habits": {
                        "cadence": "证据不足",
                        "signature_phrases": ["证据不足"],
                    },
                    "emotion_profile": {"anger_style": "证据不足"},
                },
                repair_targets={},
            )

            self.assertIn("Inner Core", [name for name, _, _ in groups])
            self.assertIn("Emotion And Stress", [name for name, _, _ in groups])
            self.assertIn("Voice", [name for name, _, _ in groups])

    def test_profile_repair_prompt_is_single_group_patch_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = WebRunService(tmp)
            payload = {
                "prompt": "系统提示",
                "references": {
                    "output_schema": "schema",
                    "style_differ": "style",
                    "logic_constraint": "logic",
                    "validation_policy": "policy",
                },
                "request": {"excerpt": "贾宝玉道：“你也不用哄我。”"},
                "meta": {"novel_id": "hongloumeng"},
            }

            messages = service._build_distill_repair_messages(
                payload,
                character="贾宝玉",
                peer_characters=["贾宝玉", "林黛玉"],
                profile={"name": "贾宝玉", "novel_id": "hongloumeng"},
                group_name="Voice",
                fields=("speech_style", "cadence", "signature_phrases"),
                repair_targets={
                    "speech_style": "太泛，缺少对白味道 -> 冷静克制",
                    "cadence": "为空",
                },
                dialogue_evidence=["贾宝玉道：“你也不用哄我。”"],
            )

            prompt = messages[1]["content"]
            self.assertIn("REPAIR_TASK", prompt)
            self.assertIn("请只修补这一组字段：Voice", prompt)
            self.assertIn("不要自由重写整份 PROFILE", prompt)
            self.assertIn("- speech_style", prompt)
            self.assertIn("- cadence", prompt)
            self.assertNotIn("完整的 PROFILE.generated.md Markdown", prompt)

            completion_messages = service._build_distill_completion_messages(
                payload,
                character="贾宝玉",
                peer_characters=["贾宝玉", "林黛玉"],
                profile={"name": "贾宝玉", "novel_id": "hongloumeng"},
                group_name="Inner Core",
                fields=("soul_goal", "belief_anchor"),
                dialogue_evidence=[],
            )
            completion_prompt = completion_messages[1]["content"]
            self.assertIn("COMPLETION_TASK", completion_prompt)
            self.assertNotIn("完整的 PROFILE.generated.md Markdown", completion_prompt)
