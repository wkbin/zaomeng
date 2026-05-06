import unittest
from types import SimpleNamespace
from unittest.mock import patch

from src.core.config import Config
from src.core.llm_client import LLMClient
from src.utils.token_counter import TokenCounter


class TokenFallbackTests(unittest.TestCase):
    def test_llm_client_falls_back_when_tiktoken_init_fails(self):
        with patch("src.core.llm_client.tiktoken") as mock_tiktoken:
            mock_tiktoken.get_encoding.side_effect = RuntimeError("ssl eof")
            client = LLMClient(Config())
        self.assertIsNone(client.encoder)
        self.assertGreater(client.count_tokens("林黛玉"), 0)

    def test_token_counter_falls_back_when_tiktoken_init_fails(self):
        fake_tiktoken = SimpleNamespace(
            get_encoding=lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("ssl eof"))
        )
        with patch.dict("sys.modules", {"tiktoken": fake_tiktoken}):
            counter = TokenCounter()

        self.assertIsNone(counter._encoder)
        self.assertGreater(counter.count("贾宝玉"), 0)


if __name__ == "__main__":
    unittest.main()
