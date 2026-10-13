from types import SimpleNamespace

import pytest
from langchain_core.messages import AIMessage

import services.agent_service as agent_service_module
from services.agent_service import AgentChatService
from services.safety_service import SafetyService
from tools.mental_health_tools import CURRENT_AGENT_CONTEXT


class FakeGovernor:
    def __init__(self):
        self.calls = 0

    async def invoke(self, *args, **kwargs):
        self.calls += 1
        return AIMessage(content="测试模型回复")


class FakeGraph:
    def __init__(self, service):
        self.service = service

    async def aget_state(self, run_config):
        return SimpleNamespace(values={})

    async def ainvoke(self, state, run_config):
        state.update(self.service._safety_gate(state))
        if self.service._route_after_safety(state) == "agent":
            state.update(await self.service._call_model(state))
            state.update(self.service._review_output(state))
        return state


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("previous_assistant", "answer", "expected_response"),
    [
        (SafetyService.uncertain_response(), "有", SafetyService.high_risk_response()),
        (SafetyService.uncertain_response(), "我不知道", SafetyService.uncertain_response()),
        (SafetyService.high_risk_response(), "是", SafetyService.high_risk_followup_response()),
        (SafetyService.high_risk_response(), "没有", SafetyService.high_risk_response()),
    ],
)
async def test_pending_safety_clarification_never_calls_real_model(
    previous_assistant, answer, expected_response, monkeypatch
):
    service = AgentChatService.__new__(AgentChatService)
    service.llm_with_tools = object()
    service.graph = FakeGraph(service)
    fake_governor = FakeGovernor()
    monkeypatch.setattr(agent_service_module, "llm_call_governor", fake_governor)

    history = [
        {"senderType": 1, "content": "文章中提到了自杀这个词"},
        {"senderType": 2, "content": previous_assistant},
    ]
    context_token = CURRENT_AGENT_CONTEXT.set(
        {"sessionId": "test-session", "turnId": "test-turn"}
    )
    try:
        chunks = [chunk async for chunk in service.stream_chat(history, answer)]
    finally:
        CURRENT_AGENT_CONTEXT.reset(context_token)

    assert "".join(chunks) == expected_response
    assert fake_governor.calls == 0
