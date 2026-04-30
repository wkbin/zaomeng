#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path

from src.skill_support.novel_preparation import (
    build_excerpt_payload,
    load_prepared_novel_excerpt,
    prepare_novel_excerpt,
)


class NovelPreparationTests(unittest.TestCase):
    def test_prepare_novel_excerpt_limits_sentence_count(self):
        text = "第一句。第二句。第三句。第四句。"
        excerpt = prepare_novel_excerpt(text, max_sentences=2, max_chars=100)
        self.assertEqual(excerpt, "第一句。\n第二句。")

    def test_prepare_novel_excerpt_limits_character_count(self):
        text = "很长的一句没有停顿但是依然需要被截断"
        excerpt = prepare_novel_excerpt(text, max_sentences=5, max_chars=8)
        self.assertEqual(excerpt, text[:8])

    def test_load_prepared_novel_excerpt_reads_text_file(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            novel_path = Path(tmpdir) / "novel.txt"
            novel_path.write_text("甲。乙。丙。", encoding="utf-8")
            excerpt = load_prepared_novel_excerpt(novel_path, max_sentences=2, max_chars=100)
            self.assertEqual(excerpt, "甲。\n乙。")

    def test_build_excerpt_payload_includes_source_metadata(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            novel_path = Path(tmpdir) / "红楼梦.txt"
            novel_path.write_text("宝玉。黛玉。宝钗。", encoding="utf-8")
            payload = build_excerpt_payload(novel_path, max_sentences=2, max_chars=100)
            self.assertEqual(payload["source_name"], "红楼梦.txt")
            self.assertEqual(payload["max_sentences"], 2)
            self.assertEqual(payload["excerpt"], "宝玉。\n黛玉。")


    def test_prepare_novel_excerpt_prefers_target_character_windows(self):
        text = (
            "第一章都是旁白。"
            "这一段只说甲。"
            "又一段还是甲。"
            "中间过场没有目标。"
            "到了后文，肖冉终于登场。"
            "肖冉看了齐夏一眼，没有急着说话。"
            "章晨泽站在旁边听着。"
        )
        excerpt = prepare_novel_excerpt(
            text,
            characters=["肖冉", "章晨泽"],
            max_sentences=4,
            max_chars=200,
        )
        self.assertIn("肖冉", excerpt)
        self.assertIn("章晨泽", excerpt)
        self.assertNotIn("第一章都是旁白", excerpt)

    def test_build_excerpt_payload_reports_missing_requested_characters(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            novel_path = Path(tmpdir) / "novel.txt"
            novel_path.write_text("齐夏出场了。旁白继续。", encoding="utf-8")
            payload = build_excerpt_payload(
                novel_path,
                characters=["齐夏", "肖冉"],
                max_sentences=4,
                max_chars=200,
            )
            self.assertEqual(payload["excerpt_strategy"], "character_windows")
            self.assertEqual(payload["matched_characters"], ["齐夏"])
            self.assertEqual(payload["missing_characters"], ["肖冉"])


if __name__ == "__main__":
    unittest.main()
