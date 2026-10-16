import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from services.context_summary_service import ContextSummaryService


class FixtureModel:
    def __init__(self, content):
        self.content = content

    async def ainvoke(self, messages):
        return SimpleNamespace(content=self.content)


@pytest.mark.asyncio
async def test_synthetic_quality_cases_preserve_only_supported_context():
    fixture_path = Path(__file__).parent / "fixtures" / "context_summary_cases.json"
    cases = json.loads(fixture_path.read_text(encoding="utf-8"))

    expected_case_names = {
        "explicit_background_preferences_and_open_topics",
        "user_correction_replaces_stale_fact",
        "unresolved_contradiction_is_kept_as_open_topic",
        "prompt_injection_is_not_summary_instruction",
        "empty_conversation_produces_empty_summary",
        "sensitive_details_are_minimized",
    }
    assert {case["name"] for case in cases} == expected_case_names

    for case in cases:
        service = ContextSummaryService(
            FixtureModel(json.dumps(case["modelOutput"], ensure_ascii=False))
        )
        result = await service.summarize(
            case.get("previousSummary"), case["messages"], user_id="synthetic-user"
        )

        assert result.model_dump(by_alias=True) == case["expectedSummary"], case["name"]

        allowed_source_ids = {
            message["id"] for message in case["messages"]
        }
        previous = case.get("previousSummary") or {}
        for field in ("userStatedContext", "userPreferences", "openTopics"):
            for entry in previous.get(field, []):
                allowed_source_ids.update(entry["sourceMessageIds"])
        for field in ("userStatedContext", "userPreferences", "openTopics"):
            for entry in case["expectedSummary"][field]:
                assert set(entry["sourceMessageIds"]) <= allowed_source_ids
