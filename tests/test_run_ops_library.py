from __future__ import annotations

import unittest

from src.web.run_ops.library import delete_sessions


class DeleteSessionsTests(unittest.TestCase):
    def test_delete_sessions_removes_existing_and_reports_missing(self) -> None:
        deleted_keys: list[tuple[str, str]] = []

        def delete_session(run_id: str, session_id: str) -> None:
            key = (run_id, session_id)
            if key == ("run-a", "session-1"):
                deleted_keys.append(key)
                return
            raise FileNotFoundError(session_id)

        result = delete_sessions(
            items=[
                {"run_id": "run-a", "session_id": "session-1"},
                {"run_id": "run-a", "session_id": "session-1"},
                {"run_id": "run-b", "session_id": "session-missing"},
                {"run_id": "", "session_id": "session-2"},
            ],
            delete_session=delete_session,
        )

        self.assertEqual(result["deleted_count"], 1)
        self.assertEqual(result["not_found_count"], 1)
        self.assertEqual(deleted_keys, [("run-a", "session-1")])

    def test_delete_sessions_requires_valid_items(self) -> None:
        with self.assertRaises(ValueError):
            delete_sessions(items=[], delete_session=lambda *_: None)


if __name__ == "__main__":
    unittest.main()
