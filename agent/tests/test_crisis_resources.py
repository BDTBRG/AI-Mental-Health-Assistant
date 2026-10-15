"""Crisis response resources must come from deployment configuration."""

import importlib
import os
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from services.safety_service import SafetyService


class CrisisResourceConfigurationTests(unittest.TestCase):
    @staticmethod
    def load_crisis_resource_tool():
        langchain_core = types.ModuleType("langchain_core")
        langchain_core.__path__ = []
        langchain_tools = types.ModuleType("langchain_core.tools")
        langchain_tools.tool = lambda function: function
        rag_module = types.ModuleType("services.rag_service")
        rag_module.rag_service = object()
        stubs = {
            "langchain_core": langchain_core,
            "langchain_core.tools": langchain_tools,
            "services.rag_service": rag_module,
        }
        with patch.dict(sys.modules, stubs):
            sys.modules.pop("tools.mental_health_tools", None)
            try:
                module = importlib.import_module("tools.mental_health_tools")
                return module.get_crisis_resources
            finally:
                sys.modules.pop("tools.mental_health_tools", None)

    def test_unconfigured_resources_use_local_emergency_guidance_without_numbers(self):
        with patch.dict(os.environ, {"CRISIS_RESOURCE_TEXT": ""}, clear=False):
            guidance = SafetyService.crisis_resource_guidance()

        self.assertIn("当地紧急服务", guidance)
        self.assertNotRegex(guidance, r"\d{3,}")

    def test_configured_resources_are_included_in_deterministic_risk_reply(self):
        configured = "地区示例求助资源：TEST-HELP-42（演示配置）"
        with patch.dict(os.environ, {"CRISIS_RESOURCE_TEXT": configured}, clear=False):
            self.assertIn(configured, SafetyService.crisis_resource_guidance())
            self.assertIn(configured, SafetyService.high_risk_response())
            self.assertEqual(configured, self.load_crisis_resource_tool()().split("\n", 1)[-1])

    def test_resource_rotation_does_not_erase_a_pending_safety_question(self):
        with patch.dict(os.environ, {"CRISIS_RESOURCE_TEXT": "资源版本 A"}, clear=False):
            previous_high_risk_reply = SafetyService.high_risk_response()
            previous_followup_reply = SafetyService.high_risk_followup_response()

        with patch.dict(os.environ, {"CRISIS_RESOURCE_TEXT": "资源版本 B"}, clear=False):
            self.assertEqual(
                "safe_location",
                SafetyService.pending_clarification_kind(previous_high_risk_reply),
            )
            self.assertTrue(
                SafetyService.is_high_risk_followup_response(previous_followup_reply)
            )
            self.assertEqual(
                "risk_intent",
                SafetyService.pending_clarification_kind(previous_followup_reply),
            )

    def test_prompt_does_not_claim_an_unverified_hotline(self):
        prompt_path = Path(__file__).resolve().parents[1] / "prompts" / "chat_prompt.py"
        prompt = prompt_path.read_text(encoding="utf-8")

        self.assertNotIn("400-161-9995", prompt)
        self.assertIn("get_crisis_resources", prompt)


if __name__ == "__main__":
    unittest.main()
