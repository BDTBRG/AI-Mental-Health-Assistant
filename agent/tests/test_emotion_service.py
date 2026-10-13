from services.emotion_service import EmotionService


class FailingLLM:
    async def ainvoke(self, _messages):
        raise TimeoutError("provider timeout")


def build_service(llm):
    service = object.__new__(EmotionService)
    service.llm = llm
    return service


async def test_provider_failure_is_unknown_not_normal():
    service = build_service(FailingLLM())

    result = await service.analyze_emotion([{"senderType": 1, "content": "最近压力很大"}])

    assert result["riskLevel"] is None
    assert result["riskStatus"] == "unknown"
    assert result["isNegative"] is None


def test_invalid_model_json_is_unknown_not_normal():
    service = build_service(None)

    result = service._parse_json_response("not valid json")

    assert result["riskLevel"] is None
    assert result["riskStatus"] == "unknown"
    assert result["isNegative"] is None
