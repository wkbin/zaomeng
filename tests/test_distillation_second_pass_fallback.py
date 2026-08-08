import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from src.core.config import Config
from src.core.exceptions import LLMRequestError
from src.core.path_provider import PathProvider
from src.core.rulebook import RuleBook
from src.modules.distillation import NovelDistiller
from src.utils.token_counter import TokenCounter


class DistillationSecondPassFallbackTests(unittest.TestCase):
    def test_subscription_error_disables_remaining_second_pass_attempts(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Config()
            config.project_root = Path(tmp)
            path_provider = PathProvider(config)
            rulebook = RuleBook(config, path_provider=path_provider)
            llm_client = Mock()
            llm_client.is_generation_enabled.return_value = True
            llm_client.chat_completion.side_effect = LLMRequestError(
                'LLM 请求失败: 400 Bad Request | {"error":{"code":"InvalidSubscription","message":"CodingPlan expired"}}'
            )
            distiller = NovelDistiller(
                config,
                llm_client=llm_client,
                token_counter=TokenCounter(),
                rulebook=rulebook,
                path_provider=path_provider,
            )

            profile = {"name": "林黛玉", "decision_rules": [], "core_traits": []}
            bucket = {"timeline": [], "dialogues": [], "thoughts": [], "descriptions": []}

            result_1 = distiller._refine_profile_with_llm(profile, bucket=bucket, arc_values=[], peer_profiles={}, overlap_report=[])
            result_2 = distiller._refine_profile_with_llm(
                {"name": "贾宝玉", "decision_rules": [], "core_traits": []},
                bucket=bucket,
                arc_values=[],
                peer_profiles={},
                overlap_report=[],
            )

            self.assertEqual(result_1["name"], "林黛玉")
            self.assertEqual(result_2["name"], "贾宝玉")
            self.assertEqual(llm_client.chat_completion.call_count, 1)
            self.assertTrue(distiller._second_pass_disabled_reason)


if __name__ == "__main__":
    unittest.main()
