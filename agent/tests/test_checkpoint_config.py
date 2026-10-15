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

    def test_v2_generation_epoch_validator_accepts_positive_integer(self):
        validator = getattr(checkpoint_config, "require_generation_epoch", None)
        self.assertTrue(callable(validator))

        self.assertEqual(3, validator({"generationEpoch": 3}))

    def test_v2_generation_epoch_validator_defaults_missing_field_for_legacy_clients(self):
        validator = getattr(checkpoint_config, "require_generation_epoch", None)
        self.assertTrue(callable(validator))

        self.assertEqual(0, validator({}))

    def test_v2_generation_epoch_validator_rejects_invalid_values(self):
        validator = getattr(checkpoint_config, "require_generation_epoch", None)
        self.assertTrue(callable(validator))

        for value in (None, 0, -1, True, 1.5, "3"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validator({"generationEpoch": value})

    def test_v2_turn_sequence_validator_accepts_positive_integer(self):
        validator = getattr(checkpoint_config, "require_turn_sequence", None)
        self.assertTrue(callable(validator))

        self.assertEqual(1204, validator({"turnSequence": 1204}))

    def test_v2_turn_sequence_validator_defaults_missing_field_for_legacy_clients(self):
        validator = getattr(checkpoint_config, "require_turn_sequence", None)
        self.assertTrue(callable(validator))

        self.assertEqual(0, validator({}))

    def test_v2_turn_sequence_validator_rejects_invalid_values(self):
        validator = getattr(checkpoint_config, "require_turn_sequence", None)
        self.assertTrue(callable(validator))

        for value in (None, 0, -1, True, 1.5, "1204"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validator({"turnSequence": value})

    def test_generation_fencing_normalizer_applies_legacy_defaults_and_validates_values(self):
        normalizer = getattr(checkpoint_config, "normalize_generation_fencing_fields", None)
        self.assertTrue(callable(normalizer))

        self.assertEqual(
            {"generationEpoch": 0, "turnSequence": 0},
            normalizer({}),
        )
        self.assertEqual(
            {"generationEpoch": 3, "turnSequence": 1204},
            normalizer({"generationEpoch": 3, "turnSequence": 1204}),
        )
        for context in (
            {"generationEpoch": 9_223_372_036_854_775_808},
            {"turnSequence": 9_223_372_036_854_775_808},
        ):
            with self.subTest(context=context), self.assertRaises(ValueError):
                normalizer(context)


if __name__ == "__main__":
    unittest.main()
