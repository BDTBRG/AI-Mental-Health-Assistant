import unittest

from services import checkpoint_config
from services.checkpoint_config import build_checkpoint_run_config


class CheckpointConfigTests(unittest.TestCase):
    def test_same_generation_uses_same_checkpoint_namespace_for_retry(self):
        first = build_checkpoint_run_config("session-17", "generation-a")
        retry = build_checkpoint_run_config("session-17", "generation-a")

        self.assertEqual(first, retry)
        self.assertEqual("session-17", first["configurable"]["thread_id"])
        self.assertEqual("generation-a", first["configurable"]["checkpoint_ns"])

    def test_reclaimed_generation_isolated_under_same_session_thread(self):
        stale = build_checkpoint_run_config("session-17", "generation-old")
        reclaimed = build_checkpoint_run_config("session-17", "generation-new")

        self.assertEqual(
            stale["configurable"]["thread_id"],
            reclaimed["configurable"]["thread_id"],
        )
        self.assertNotEqual(
            stale["configurable"]["checkpoint_ns"],
            reclaimed["configurable"]["checkpoint_ns"],
        )

    def test_missing_generation_token_uses_stable_turn_id_for_retries(self):
        first = build_checkpoint_run_config("session-17", None, "turn-42")
        retry = build_checkpoint_run_config("session-17", None, "turn-42")

        self.assertEqual(first, retry)
        self.assertEqual("turn-42", first["configurable"]["checkpoint_ns"])

    def test_explicit_generation_token_takes_precedence_over_turn_id(self):
        run_config = build_checkpoint_run_config(
            "session-17", "generation-current", "turn-42"
        )

        self.assertEqual(
            "generation-current", run_config["configurable"]["checkpoint_ns"]
        )

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
