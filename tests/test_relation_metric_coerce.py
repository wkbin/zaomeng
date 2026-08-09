#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path

from src.utils.file_utils import coerce_int, save_markdown_data
from src.web.artifacts.catalog import relation_type_label
from src.web.artifacts.operations import list_relation_details


def _noop_split(pair_key: str) -> tuple[str, str]:
    parts = pair_key.split("_", 1)
    return parts[0], parts[1] if len(parts) == 2 else ""


class RelationMetricCoerceTests(unittest.TestCase):
    def test_coerce_int_handles_empty_and_ellipsis(self):
        self.assertEqual(coerce_int("", 5), 5)
        self.assertEqual(coerce_int("...", 5), 5)
        self.assertEqual(coerce_int("…", 5), 5)
        self.assertEqual(coerce_int("约7", 5), 7)

    def test_relation_type_label_with_ellipsis_metrics(self):
        label = relation_type_label({"trust": "...", "affection": "", "hostility": "..."})
        self.assertEqual(label, "牵连")

    def test_list_relation_details_with_noisy_metrics(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            relations_file = Path(tmpdir) / "mini_relations.md"
            save_markdown_data(
                relations_file,
                {
                    "novel_id": "mini",
                    "relations": {
                        "刘备_关羽": {
                            "trust": "...",
                            "affection": "",
                            "hostility": "...",
                            "ambiguity": "...",
                        }
                    },
                },
                title="RELATION_GRAPH",
            )
            payload = {
                "novel_id": "mini",
                "relations": {
                    "刘备_关羽": {
                        "trust": "...",
                        "affection": "",
                        "hostility": "...",
                        "ambiguity": "...",
                    }
                },
            }
            result = list_relation_details(
                run_id="run-test",
                manifest={"novel_id": "mini"},
                relations_file=relations_file,
                payload=payload,
                split_relation_pair=_noop_split,
                relation_type_label=relation_type_label,
                coerce_relation_evidence=lambda relation: [],
            )
            item = result["items"][0]
            self.assertEqual(item["trust"], 0)
            self.assertEqual(item["affection"], 0)
            self.assertEqual(item["hostility"], 0)
            self.assertEqual(item["ambiguity"], 3)


if __name__ == "__main__":
    unittest.main()
