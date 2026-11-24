from pathlib import Path
import tempfile
import unittest

from src.web.chat.checkpoint_history import archive_checkpoint_history, restore_checkpoint_history


class CheckpointHistoryTests(unittest.TestCase):
    def test_checkpoint_chunks_reuse_the_prefix_and_restore_exact_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            session_dir = Path(tmp)
            history = [{"message": f"消息 {index}", "turn_id": f"turn-{index}"} for index in range(40)]
            first = archive_checkpoint_history(session_dir, {"history": history})
            second = archive_checkpoint_history(session_dir, {"history": history + [{"message": "下一条"}]})

            self.assertNotIn("history", first)
            self.assertEqual(first["history_chunks"][0], second["history_chunks"][0])
            self.assertNotEqual(first["history_chunks"][-1], second["history_chunks"][-1])
            self.assertEqual(3, len(list((session_dir / "checkpoint-history").glob("*.json"))))
            self.assertEqual(history, restore_checkpoint_history(session_dir, first)["history"])
            self.assertEqual({"history": history}, restore_checkpoint_history(session_dir, {"history": history}))

    def test_missing_checkpoint_chunk_cannot_restore_partial_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "chunk is missing"):
                restore_checkpoint_history(Path(tmp), {"history_chunks": ["a" * 64]})
