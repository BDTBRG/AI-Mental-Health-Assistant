import unittest

from services import checkpoint_config
from services.checkpoint_config import build_checkpoint_run_config


class CheckpointConfigTests(unittest.TestCase):
    def test_retries_use_the_same_session_thread(self):
        first = build_checkpoint_run_config("session-17")
        retry = build_checkpoint_run_config("session-17")

        self.assertEqual(first, retry)
        self.assertEqual("session-17", first["configurable"]["thread_id"])
        self.assertNotIn("checkpoint_ns", first["configurable"])

    def test_checkpoint_config_uses_root_namespace(self):
        run_config = build_checkpoint_run_config("session-17")

        self.assertEqual({"thread_id": "session-17"}, run_config["configurable"])

    def test_v2_generation_token_validator_accepts_nonblank_token(self):
        validator = getattr(checkpoint_config, "require_generation_token", None)
        self.assertTrue(callable(validator))

        self.assertEqual(
            "generation-current", validator({"generationToken": "generation-current"})
        )

    def test_v2_generation_token_validator_rejects_missing_or_blank_token(self):
        validator = getattr(checkpoint_config, "require_generation_token", None)
        self.assertTrue(callable(validator))

        for context in ({}, {"generationToken": "  "}, {"generationToken": None}):
            with self.subTest(context=context), self.assertRaises(ValueError):
                validator(context)


if __name__ == "__main__":
    unittest.main()
