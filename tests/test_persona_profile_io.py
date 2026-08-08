from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from src.modules.persona_profile_io import (
    PersonaProfileRepository,
    merge_profile_item,
    parse_navigation_markdown,
    parse_persona_markdown,
    safe_int,
    split_metric_map,
    split_persona_value,
)


class PersonaProfileIoTests(unittest.TestCase):
    def test_markdown_parsers_preserve_navigation_and_repeated_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            navigation = root / "NAVIGATION.md"
            navigation.write_text(
                "## RUNTIME\n- load_order: SOUL -> VOICE\n\n## SOUL\n- file: SOUL.md\n",
                encoding="utf-8",
            )
            persona = root / "SOUL.md"
            persona.write_text("- core_traits: 冷静\n- core_traits: 克制\n", encoding="utf-8")

            descriptor = parse_navigation_markdown(navigation)
            parsed_persona = parse_persona_markdown(persona)

            self.assertEqual(descriptor["runtime"]["load_order"], "SOUL -> VOICE")
            self.assertEqual(descriptor["files"]["SOUL"]["file"], "SOUL.md")
            self.assertEqual(parsed_persona["core_traits"], "冷静；克制")

    def test_value_parsers_keep_metrics_and_invalid_integers_compatible(self):
        self.assertEqual(split_persona_value("冷静； 克制;果断"), ["冷静", "克制", "果断"])
        self.assertEqual(split_metric_map("勇气=8；立场=稳定；无效"), {"勇气": 8, "立场": "稳定"})
        self.assertEqual(safe_int("12"), 12)
        self.assertEqual(safe_int("unknown"), 0)

    def test_merge_profile_item_prefers_richer_profile_and_deduplicates_lists(self):
        existing = {
            "core_traits": ["冷静"],
            "typical_lines": ["先等等"],
            "decision_rules": ["先观察"],
            "speech_style": "简短",
            "values": {"智慧": 8},
        }
        incoming = {
            "core_traits": ["克制", "果断"],
            "typical_lines": ["先等等", "现在动手"],
            "decision_rules": ["先观察", "再行动"],
            "speech_style": "",
            "values": {},
        }

        merged = merge_profile_item(existing, incoming)

        self.assertEqual(merged["core_traits"], ["克制", "果断", "冷静"])
        self.assertEqual(merged["typical_lines"], ["先等等", "现在动手"])
        self.assertEqual(merged["decision_rules"], ["先观察", "再行动"])
        self.assertEqual(merged["speech_style"], "简短")
        self.assertEqual(merged["values"], {"智慧": 8})

    def test_repository_loads_editable_profile_and_navigation_overlays(self):
        with tempfile.TemporaryDirectory() as tmp:
            characters_root = Path(tmp) / "characters"
            persona_dir = characters_root / "novel-a" / "林黛玉"
            persona_dir.mkdir(parents=True)
            (persona_dir / "PROFILE.generated.md").write_text(
                "- name: 林黛玉\n- novel_id: novel-a\n- core_traits: 敏感；清醒\n- speech_style: 清冷\n",
                encoding="utf-8",
            )
            (persona_dir / "PROFILE.md").write_text(
                "- speech_style: 克制简短\n",
                encoding="utf-8",
            )
            (persona_dir / "NAVIGATION.md").write_text(
                "## RUNTIME\n- load_order: SOUL -> VOICE\n\n## VOICE\n- status: inactive\n",
                encoding="utf-8",
            )
            (persona_dir / "SOUL.generated.md").write_text(
                "- hidden_desire: 被真正理解\n",
                encoding="utf-8",
            )
            (persona_dir / "SOUL.md").write_text(
                "- hidden_desire: 守住自尊\n",
                encoding="utf-8",
            )
            (persona_dir / "VOICE.generated.md").write_text(
                "- cadence: 句子很短\n",
                encoding="utf-8",
            )
            repository = PersonaProfileRepository(
                characters_root,
                scoped_root=lambda novel_id: characters_root / novel_id,
                default_navigation_order=["SOUL", "VOICE", "RELATIONS"],
            )

            profiles = repository.load("novel-a")

            profile = profiles["林黛玉"]
            self.assertEqual(profile["speech_style"], "克制简短")
            self.assertEqual(profile["hidden_desire"], "守住自尊")
            self.assertEqual(profile["speech_habits"]["cadence"], "")
            self.assertEqual(profile["core_traits"], ["敏感", "清醒"])


if __name__ == "__main__":
    unittest.main()
