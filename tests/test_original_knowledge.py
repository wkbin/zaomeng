from __future__ import annotations

from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from src.web.chat.helpers import build_dialogue_llm_messages, parse_dialogue_responses
from src.web.chat.original_knowledge import OriginalKnowledgeStore
from src.web.chat.session_views import serialize_transcript


class OriginalKnowledgeStoreTests(unittest.TestCase):
    def test_blank_query_skips_loading_the_source_index(self):
        store = OriginalKnowledgeStore(Path("."))
        with patch.object(store, "ensure") as ensure:
            self.assertEqual(
                [],
                store.search({}, query=" ", participants=[], active_participants=[], scene_terms=[" "]),
            )
        ensure.assert_not_called()

    def test_automatic_boundary_requires_explicit_knowledge_evidence(self):
        self.assertEqual(
            OriginalKnowledgeStore._infer_boundary(
                "玲音说道同学昨天没有到场。", ["玲音", "同学"]
            ),
            ("private", ["玲音"]),
        )
        self.assertEqual(
            OriginalKnowledgeStore._infer_boundary(
                "关于玲音和同学的传闻仍未证实。", ["玲音", "同学"]
            ),
            ("uncertain", []),
        )
        self.assertEqual(
            OriginalKnowledgeStore._infer_boundary(
                "玲音看见门开了，同学也听见了脚步声。", ["玲音", "同学"]
            ),
            ("scene", ["玲音", "同学"]),
        )

    def _manifest(self, root: Path) -> dict:
        run_dir = root / "run-demo"
        source = run_dir / "input" / "lain.txt"
        source.parent.mkdir(parents=True)
        source.write_text(
            "Wired 是连接所有人的网络世界。玲音在房间里第一次接触 Wired。"
            "玲音把父亲留下的秘密瞒着同学，只有玲音知道密码。",
            encoding="utf-8",
        )
        return {
            "run_id": "run-demo",
            "novel_path": str(source),
            "artifact_index": {
                "characters": [{"name": "玲音"}, {"name": "同学"}]
            },
        }

    def test_build_search_and_manual_boundary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = self._manifest(root)
            store = OriginalKnowledgeStore(root)

            index = store.ensure(manifest, character_names=["玲音", "同学"])
            self.assertGreater(index["entry_count"], 0)
            hits = store.search(
                manifest,
                query="Wired 是什么",
                participants=["玲音", "同学"],
                active_participants=["玲音"],
            )
            self.assertTrue(hits)
            self.assertIn("Wired", hits[0]["excerpt"])

            entry_id = hits[0]["source_id"]
            store.update_entry(
                "run-demo",
                entry_id,
                visibility="private",
                knowers=["玲音"],
            )
            store.ensure(manifest, character_names=["玲音", "同学"], force=True)
            updated_hits = store.search(
                manifest,
                query="Wired 是什么",
                participants=["玲音", "同学"],
                active_participants=["玲音"],
            )
            updated = next(item for item in updated_hits if item["source_id"] == entry_id)
            self.assertEqual(updated["allowed_characters"], ["玲音"])
            self.assertIn("同学", updated["denied_characters"])

    def test_rejects_manifest_source_outside_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "run-demo").mkdir()
            source = root / "outside.txt"
            source.write_text("不应读取", encoding="utf-8")
            store = OriginalKnowledgeStore(root)
            with self.assertRaises(ValueError):
                store.ensure({"run_id": "run-demo", "novel_path": str(source)})


class OriginalKnowledgePromptTests(unittest.TestCase):
    def test_prompt_omits_private_source_denied_to_any_possible_responder(self):
        payload = {
            "mode": "observe",
            "input": {
                "speaker": "User",
                "message": "密码是什么？",
                "participants": ["玲音", "同学"],
                "active_participants": ["玲音", "同学"],
            },
            "persona_contexts": [
                {"name": "玲音", "profile": {}, "preview": {}},
                {"name": "同学", "profile": {}, "preview": {}},
            ],
            "responder_hints": [{"name": "玲音"}, {"name": "同学"}],
            "original_source_context": {
                "entries": [
                    {
                        "source_id": "src-secret",
                        "excerpt": "密码是 0462。",
                        "visibility": "private",
                        "allowed_characters": ["玲音"],
                    }
                ]
            },
            "host_action": {"response_limit_hint": 2},
        }

        messages = build_dialogue_llm_messages(payload)
        combined = "\n".join(str(item.get("content", "")) for item in messages)

        self.assertNotIn("密码是 0462", combined)

    def test_prompt_carries_compact_source_context_without_changing_output(self):
        payload = {
            "mode": "observe",
            "input": {
                "speaker": "User",
                "message": "Wired 是什么？",
                "participants": ["玲音"],
                "active_participants": ["玲音"],
            },
            "persona_contexts": [{"name": "玲音", "profile": {}, "preview": {}}],
            "original_source_context": {
                "entries": [
                    {
                        "source_id": "src-00001",
                        "title": "原文片段 1",
                        "excerpt": "Wired 是连接所有人的网络世界。",
                        "visibility": "public",
                        "allowed_characters": ["玲音"],
                    }
                ]
            },
            "host_action": {"response_limit_hint": 1},
        }
        messages = build_dialogue_llm_messages(payload)
        combined = "\n".join(str(item.get("content", "")) for item in messages)
        self.assertIn("ORIGINAL_SOURCE_CONTEXT", combined)
        self.assertIn("Wired 是连接所有人的网络世界", combined)

        responses = parse_dialogue_responses(
            '[{"speaker":"玲音","message":"它连接着大家。","source_ids":["src-00001"]}]',
            ["玲音"],
        )
        self.assertEqual(
            responses[0], {"speaker": "玲音", "message": "它连接着大家。"}
        )

    def test_transcript_does_not_expose_source_trace(self):
        transcript = serialize_transcript(
            {
                "mode": "observe",
                "history": [
                    {
                        "speaker": "玲音",
                        "message": "……",
                        "source_ids": ["src-00001"],
                        "sources": [{"source_id": "src-00001", "excerpt": "原文"}],
                        "provenance": {"status": "source_grounded"},
                    }
                ],
            }
        )
        self.assertNotIn("source_ids", transcript[0])
        self.assertNotIn("sources", transcript[0])
        self.assertNotIn("provenance", transcript[0])


if __name__ == "__main__":
    unittest.main()
