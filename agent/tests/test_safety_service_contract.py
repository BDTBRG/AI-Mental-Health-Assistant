"""Dependency-free contract tests for the deterministic safety classifier."""

import unittest

from services.safety_service import SafetyService


class SafetyServiceContractTest(unittest.TestCase):
    def setUp(self):
        self.service = SafetyService()

    def test_indirect_wish_not_to_wake_is_clarified(self):
        result = self.service.assess("这几天一直觉得不想醒来了")

        self.assertEqual(result.level, "uncertain")

    def test_indirect_wish_to_disappear_is_clarified(self):
        result = self.service.assess("我只想消失")

        self.assertEqual(result.level, "uncertain")

    def test_cannot_go_on_is_clarified_instead_of_sent_to_model(self):
        result = self.service.assess("我真的累了，撑不下去了")

        self.assertEqual(result.level, "uncertain")

    def test_recent_possible_overdose_is_high_risk(self):
        for message in (
            "我刚才吃了很多安眠药",
            "我不小心服了很多安眠药",
            "我刚刚服了10片安眠药",
            "我刚刚服了十片安眠药",
            "我刚刚服了十二片安眠药",
            "我刚刚服了二十片安眠药",
            "我刚刚服了十几片安眠药",
            "我刚刚服了几十片安眠药",
            "我刚刚服了上百片安眠药",
            "我不小心服了十片安眠药",
        ):
            with self.subTest(message=message):
                self.assertEqual(self.service.assess(message).level, "high")

    def test_normal_medication_use_does_not_trigger_crisis_response(self):
        for message in ("我刚刚按医嘱服了药", "我按医嘱服了两片药"):
            with self.subTest(message=message):
                self.assertEqual(self.service.assess(message).level, "low")

    def test_quoted_or_denied_numeric_dose_is_not_escalated_as_high_risk(self):
        quoted = self.service.assess("我引用“他刚刚服了十片安眠药”")
        denied = self.service.assess("我没有服十片安眠药")

        self.assertEqual(quoted.level, "uncertain")
        self.assertNotEqual(denied.level, "high")

    def test_common_completed_self_harm_phrasing_is_high_risk(self):
        for message in (
            "我已经割了手腕",
            "我现在划伤了自己",
            "我已经把手腕割伤了",
            "我把手腕划开了",
            "我把手腕划破了",
            "我把手腕划开",
            "我刚刚服了很多安眠药",
            "我把手腕划开一道口子",
        ):
            with self.subTest(message=message):
                self.assertEqual(self.service.assess(message).level, "high")

    def test_accidental_injury_is_clarified_not_escalated_as_crisis(self):
        for message in ("我做饭时划伤了手腕", "我削苹果时划伤了手腕"):
            with self.subTest(message=message):
                self.assertEqual(self.service.assess(message).level, "uncertain")

    def test_explicit_intent_takes_precedence_over_accident_context(self):
        for message in ("我做饭时想把手腕割开", "我在做饭的时候故意割伤手腕"):
            with self.subTest(message=message):
                self.assertEqual(self.service.assess(message).level, "high")

    def test_body_part_negation_does_not_become_direct_high_intent(self):
        for message in ("我不想把手腕割开", "我没有想把手腕划开"):
            with self.subTest(message=message):
                self.assertNotEqual(self.service.assess(message).level, "high")

    def test_tattoo_intent_is_not_mistaken_for_self_harm(self):
        result = self.service.assess("我想给自己的手臂划个纹身")

        self.assertEqual(result.level, "low")

    def test_explicit_denial_is_not_classified_as_high_risk_action(self):
        result = self.service.assess("我现在没有伤害自己的打算")

        self.assertNotEqual(result.level, "high")

    def test_harm_statement_inside_a_quote_is_not_classified_as_high(self):
        result = self.service.assess("我刚才引用“他吞了很多安眠药”")

        self.assertEqual(result.level, "uncertain")

    def test_ordinary_sleep_statement_is_not_a_risk_signal(self):
        result = self.service.assess("我今天很累，想早点睡觉")

        self.assertEqual(result.level, "low")


if __name__ == "__main__":
    unittest.main()
