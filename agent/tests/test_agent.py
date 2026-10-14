"""Tests for the AI Mental Health Agent Service."""

import json
import pytest
from httpx import AsyncClient, ASGITransport

import config
from app import app
from services.agent_service import agent_chat_service
from services.diary_service import diary_service
from services.emotion_service import emotion_service


@pytest.fixture
async def client(monkeypatch):
    monkeypatch.setattr(config, "AGENT_INTERNAL_TOKEN", "unit-test-agent-token")
    transport = ASGITransport(app=app)
    async with AsyncClient(
        transport=transport,
        base_url="http://test",
        headers={"X-Agent-Token": "unit-test-agent-token"},
    ) as ac:
        yield ac


@pytest.fixture(autouse=True)
def fake_agent_services(monkeypatch):
    async def stream_chat(_messages, _user_message):
        yield "mocked response"

    async def analyze_emotion(_messages, request_context=None):
        return {
            "primaryEmotion": "焦虑",
            "emotionScore": 45,
            "riskLevel": 1,
            "riskStatus": "known",
            "isNegative": True,
            "improvementSuggestions": ["联系可信任的人"],
            "requestContext": request_context,
        }

    async def analyze_diary(_diary_data):
        return "模拟的日记分析结果"

    monkeypatch.setattr(agent_chat_service, "stream_chat", stream_chat)
    monkeypatch.setattr(emotion_service, "analyze_emotion", analyze_emotion)
    monkeypatch.setattr(diary_service, "analyze_diary", analyze_diary)


class TestHealthCheck:
    async def test_health(self, client):
        response = await client.get("/api/agent/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"


class TestChat:
    async def test_streaming_basic(self, client):
        body = {
            "sessionId": "test-1",
            "messages": [],
            "userMessage": "Hello"
        }
        response = await client.post("/api/agent/chat", json=body)
        assert response.status_code == 200
        lines = response.text.strip().split("\n")
        assert len(lines) >= 1
        last = json.loads(lines[-1])
        assert last["data"]["done"] is True
        assert last["data"]["fullResponse"] == "mocked response"

    async def test_protected_endpoint_rejects_missing_or_invalid_token(self, client):
        response = await client.get(
            "/api/agent/rag/stats",
            headers={"X-Agent-Token": "invalid-token"},
        )
        assert response.status_code == 403

        response = await client.get(
            "/api/agent/rag/stats",
            headers={"X-Agent-Token": ""},
        )
        assert response.status_code == 403

    async def test_protected_endpoint_fails_closed_when_server_token_is_missing(self, client, monkeypatch):
        monkeypatch.setattr(config, "AGENT_INTERNAL_TOKEN", "")

        response = await client.get(
            "/api/agent/rag/stats",
            headers={"X-Agent-Token": ""},
        )

        assert response.status_code == 503

    async def test_with_history(self, client):
        body = {
            "sessionId": "test-2",
            "messages": [
                {"senderType": 1, "content": "I am stressed"},
                {"senderType": 2, "content": "Tell me more"}
            ],
            "userMessage": "Too much work"
        }
        response = await client.post("/api/agent/chat", json=body)
        assert response.status_code == 200
        lines = response.text.strip().split("\n")
        assert len(lines) >= 1


class TestEmotion:
    async def test_anxiety(self, client):
        body = {
            "messages": [
                {"senderType": 1, "content": "I am very anxious today"},
                {"senderType": 1, "content": "I cannot focus at all"}
            ]
        }
        response = await client.post("/api/agent/emotion/analyze", json=body)
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == "200"
        r = data["data"]
        assert "primaryEmotion" in r
        assert "emotionScore" in r
        assert 0 <= r["riskLevel"] <= 3
        assert isinstance(r["improvementSuggestions"], list)

    async def test_negative(self, client):
        body = {
            "messages": [
                {"senderType": 1, "content": "Life feels meaningless"}
            ]
        }
        response = await client.post("/api/agent/emotion/analyze", json=body)
        assert response.status_code == 200
        data = response.json()
        r = data["data"]
        assert r["riskLevel"] >= 1
        assert r["isNegative"] is True


class TestDiary:
    async def test_positive(self, client):
        body = {
            "dominantEmotion": "Happy",
            "moodScore": 8,
            "emotionTriggers": "Project done",
            "diaryContent": "Finished a big project today.",
            "sleepQuality": 4,
            "stressLevel": 2
        }
        response = await client.post("/api/agent/diary/analyze", json=body)
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == "200"
        assert len(data["data"]["analysis"]) > 0

    async def test_minimal(self, client):
        body = {
            "dominantEmotion": "",
            "moodScore": 5,
            "emotionTriggers": "",
            "diaryContent": "A normal day",
            "sleepQuality": None,
            "stressLevel": None
        }
        response = await client.post("/api/agent/diary/analyze", json=body)
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == "200"
        assert len(data["data"]["analysis"]) > 0
