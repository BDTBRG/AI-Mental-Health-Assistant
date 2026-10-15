import json
from types import SimpleNamespace

import pytest
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError

import app as app_module
import config
from services.context_summary_service import ContextSummaryService


class FakeModel:
    def __init__(self, content):
        self.content = content
        self.calls = []

    async def ainvoke(self, messages):
        self.calls.append(messages)
        return SimpleNamespace(content=self.content)


@pytest.fixture
async def client(monkeypatch):
    monkeypatch.setattr(config, "AGENT_INTERNAL_TOKEN", "unit-test-agent-token")
    async with AsyncClient(
        transport=ASGITransport(app=app_module.app),
        base_url="http://test",
    ) as test_client:
        yield test_client


def summary_request():
    return {
        "sessionId": "session-12",
        "userId": "private-user-7",
        "previousSummary": None,
        "messages": [
            {"id": 12, "senderType": 1, "content": "最近工作压力较大"}
        ],
        "promptVersion": "context-summary-v1",
        "correlationId": "summary-request-12",
    }


@pytest.mark.asyncio
async def test_context_summary_route_requires_the_internal_token(client):
    response = await client.post(
        "/api/agent/v2/context-summary", json=summary_request()
    )

    assert response.status_code == 403


@pytest.mark.asyncio
async def test_context_summary_route_returns_versioned_schema_without_checkpoint_access(
    client, monkeypatch
):
    output = {
        "userStatedContext": [
            {"text": "用户近期工作压力较大", "sourceMessageIds": [12]}
        ],
        "userPreferences": [],
        "openTopics": [],
    }
    model = FakeModel(json.dumps(output, ensure_ascii=False))
    monkeypatch.setattr(
        app_module,
        "context_summary_service",
        ContextSummaryService(model),
        raising=False,
    )

    class CheckpointerAccessGuard:
        @property
        def checkpointer(self):
            raise AssertionError("summary generation must not access checkpoints")

    monkeypatch.setattr(app_module, "agent_chat_service", CheckpointerAccessGuard())
    response = await client.post(
        "/api/agent/v2/context-summary",
        json=summary_request(),
        headers={"X-Agent-Token": "unit-test-agent-token"},
    )

    assert response.status_code == 200
    assert response.json() == {
        "summary": output,
        "coveredThroughMessageId": 12,
        "promptVersion": "context-summary-v1",
    }
    assert "private-user-7" not in " ".join(
        message.content for message in model.calls[0]
    )


@pytest.mark.asyncio
async def test_context_summary_route_accepts_spring_numeric_session_id(client, monkeypatch):
    output = {
        "userStatedContext": [],
        "userPreferences": [],
        "openTopics": [],
    }
    monkeypatch.setattr(
        app_module,
        "context_summary_service",
        ContextSummaryService(FakeModel(json.dumps(output))),
        raising=False,
    )
    request = summary_request()
    request["sessionId"] = 17

    response = await client.post(
        "/api/agent/v2/context-summary",
        json=request,
        headers={"X-Agent-Token": "unit-test-agent-token"},
    )

    assert response.status_code == 200
    assert response.json()["coveredThroughMessageId"] == 12


@pytest.mark.asyncio
async def test_context_summary_route_rejects_unknown_model_source_ids(client, monkeypatch):
    model = FakeModel(
        '{"userStatedContext":[{"text":"未经支持的结论",'
        '"sourceMessageIds":[999]}],"userPreferences":[],"openTopics":[]}'
    )
    monkeypatch.setattr(
        app_module,
        "context_summary_service",
        ContextSummaryService(model),
        raising=False,
    )

    response = await client.post(
        "/api/agent/v2/context-summary",
        json=summary_request(),
        headers={"X-Agent-Token": "unit-test-agent-token"},
    )

    assert response.status_code == 502
    assert "未经支持的结论" not in response.text


def test_chat_request_enforces_message_and_context_character_budgets():
    with pytest.raises(ValidationError):
        app_module.ChatRequest.model_validate(
            {
                "sessionId": "session-1",
                "messages": [
                    {"senderType": 1, "content": "x" * 8001}
                ],
                "userMessage": "继续",
            }
        )

    summary = {
        "userStatedContext": [
            {"text": "s" * 240, "sourceMessageIds": [1]} for _ in range(5)
        ],
        "userPreferences": [],
        "openTopics": [],
    }
    with pytest.raises(ValidationError):
        app_module.ChatRequest.model_validate(
            {
                "sessionId": "session-1",
                "messages": [
                    {"senderType": 1, "content": "x" * 8000},
                    {"senderType": 1, "content": "x" * 8000},
                    {"senderType": 1, "content": "x" * 8000},
                    {"senderType": 1, "content": "x" * 7000},
                ],
                "userMessage": "",
                "contextSummary": summary,
            }
        )

    with pytest.raises(ValidationError):
        app_module.ChatRequest.model_validate(
            {
                "sessionId": "session-1",
                "messages": [
                    {"senderType": 1, "content": "x" * 8000}
                    for _ in range(4)
                ],
                "userMessage": "x",
            }
        )

    with pytest.raises(ValidationError):
        app_module.ChatRequest.model_validate(
            {
                "sessionId": "session-1",
                "messages": [
                    {"senderType": 1, "content": "x"}
                    for _ in range(40)
                ],
                "userMessage": "继续",
            }
        )


@pytest.mark.asyncio
async def test_json_request_body_is_limited_to_sixty_four_kib(client):
    response = await client.post(
        "/api/agent/v2/chat",
        content=b"{" + b" " * (64 * 1024) + b"}",
        headers={
            "Content-Type": "application/json",
            "X-Agent-Token": "unit-test-agent-token",
        },
    )

    assert response.status_code == 413


@pytest.mark.asyncio
async def test_chunked_json_request_body_is_limited_without_content_length(client):
    async def chunks():
        yield b"{" + b" " * (32 * 1024)
        yield b" " * (32 * 1024 + 1) + b"}"

    response = await client.post(
        "/api/agent/v2/chat",
        content=chunks(),
        headers={
            "Content-Type": "application/json",
            "X-Agent-Token": "unit-test-agent-token",
        },
    )

    assert response.status_code == 413
