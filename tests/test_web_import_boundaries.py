from __future__ import annotations

import subprocess
import sys
import textwrap
import unittest


class WebImportBoundaryTests(unittest.TestCase):
    def test_pure_web_helpers_do_not_import_fastapi(self):
        script = textwrap.dedent(
            """
            import sys
            import src.web.chat.event_signals
            import src.web.chat.helpers
            import src.web.chat.io_utils
            import src.web.chat.memory_summary
            import src.web.chat.persona_context
            import src.web.chat.prompt_rules
            import src.web.chat.relation_excerpt
            import src.web.chat.relation_state
            import src.web.chat.runtime_overview
            import src.web.chat.scene_progress
            import src.web.chat.scene_signals
            import src.web.chat.session_storage
            import src.web.chat.session_views
            import src.web.chat.state_utils
            import src.web.chat.text_utils
            import src.web.chat.turn_memory
            import src.web.api.schemas
            if "fastapi" in sys.modules or "src.web.chat.service" in sys.modules:
                raise SystemExit(1)
            """
        )

        subprocess.run([sys.executable, "-c", script], check=True)

    def test_web_asgi_entrypoint_creates_fastapi_app(self):
        script = textwrap.dedent(
            """
            import src.web.asgi
            app = src.web.asgi.app
            raise SystemExit(0 if app.__class__.__name__ == "FastAPI" else 1)
            """
        )

        subprocess.run([sys.executable, "-c", script], check=True)


if __name__ == "__main__":
    unittest.main()
