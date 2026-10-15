"""Tests for the AI Mental Health Agent Service."""

import json
import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from httpx import AsyncClient, ASGITransport

import app as app_module
import config
from app import app
from services.agent_service import agent_chat_service
from services.diary_service import diary_service
from services.emotion_service import emotion_service
from services.rag_service import rag_service


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

    async def test_liveness_remains_available_before_application_is_ready(self, client, monkeypatch):
        monkeypatch.setattr(app.state, "ready", False, raising=False)

        response = await client.get("/api/agent/health/live")

        assert response.status_code == 200
        assert response.json()["status"] == "ok"

    async def test_readiness_returns_service_unavailable_before_startup_finishes(
        self, client, monkeypatch
    ):
        monkeypatch.setattr(app.state, "ready", False, raising=False)
        dependency_check = AsyncMock(return_value=True)
        monkeypatch.setattr(
            app_module, "_dependencies_available", dependency_check, raising=False
        )

        response = await client.get("/api/agent/health/ready")

        assert response.status_code == 503
        assert response.json()["status"] == "not_ready"
        dependency_check.assert_not_awaited()

    async def test_readiness_returns_ok_after_shared_dependencies_are_initialized(
        self, client, monkeypatch
    ):
        monkeypatch.setattr(app.state, "ready", True, raising=False)
        monkeypatch.setattr(
            app_module, "_dependencies_available", AsyncMock(return_value=True), raising=False
        )

        response = await client.get("/api/agent/health/ready")

        assert response.status_code == 200
        assert response.json()["status"] == "ready"

    async def test_readiness_fails_when_a_shared_dependency_is_unavailable(
        self, client, monkeypatch
    ):
        monkeypatch.setattr(app.state, "ready", True, raising=False)
        monkeypatch.setattr(
            app_module, "_dependencies_available", AsyncMock(return_value=False), raising=False
        )

        response = await client.get("/api/agent/health/ready")

        assert response.status_code == 503
        assert response.json()["status"] == "not_ready"

    async def test_dependency_probe_checks_qdrant_redis_and_postgres(self, monkeypatch):
        executed_statements = []
        redis_urls = []
        redis_client = AsyncMock()

        class FakeConnection:
            async def execute(self, statement):
                executed_statements.append(statement)
                return SimpleNamespace(fetchone=AsyncMock(return_value={"?column?": 1}))

        class FakeRedisFactory:
            @staticmethod
            def from_url(url, **_kwargs):
                redis_urls.append(url)
                return redis_client

        monkeypatch.setattr(config, "RAG_BACKEND", "qdrant")
        monkeypatch.setattr(config, "RAG_WRITE_LOCK_REDIS_URL", "redis://redis:6379/0")
        monkeypatch.setattr(config, "AGENT_CHECKPOINT_BACKEND", "postgres")
        metadata_reads = []
        stats_reads = []

        class FakeQdrantStore:
            def get_index_metadata(self):
                metadata_reads.append(True)
                return {"collection_name": "knowledge", "index_version": "v1"}

        def read_full_stats():
            stats_reads.append(True)
            return {"ready": False}

        monkeypatch.setattr(app_module.rag_service, "qdrant_store", FakeQdrantStore())
        monkeypatch.setattr(app_module.rag_service, "get_stats", read_full_stats)
        monkeypatch.setattr(app_module.redis_async, "Redis", FakeRedisFactory)
        monkeypatch.setattr(
            app.state,
            "checkpointer",
            SimpleNamespace(conn=FakeConnection(), lock=asyncio.Lock()),
            raising=False,
        )
        monkeypatch.setattr(
            app.state,
            "session_lifecycle",
            SimpleNamespace(check_ready=AsyncMock(return_value=True)),
            raising=False,
        )

        assert await app_module._dependencies_available()
        assert metadata_reads == [True]
        assert stats_reads == []
        assert executed_statements == ["SELECT 1"]
        assert redis_urls == ["redis://redis:6379/0"]
        redis_client.ping.assert_awaited_once()
        redis_client.aclose.assert_awaited_once()


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

    async def test_legacy_checkpoint_delete_remains_a_temporary_purge(self, client, monkeypatch):
        purge = AsyncMock()
        monkeypatch.setattr(agent_chat_service, "purge_session", purge)

        response = await client.delete("/api/agent/sessions/legacy-18/checkpoint")

        assert response.status_code == 200
        purge.assert_awaited_once_with("legacy-18")

    async def test_versioned_checkpoint_purge_and_session_delete_are_distinct(
        self, client, monkeypatch
    ):
        purge = AsyncMock()
        monkeypatch.setattr(agent_chat_service, "purge_session", purge)

        temporary = await client.post(
            "/api/agent/v2/sessions/session-21/checkpoint/purge"
        )
        permanent = await client.delete("/api/agent/v2/sessions/session-22")

        assert temporary.status_code == 200
        assert temporary.json()["data"]["permanent"] is False
        assert permanent.status_code == 200
        assert permanent.json()["data"]["permanent"] is True
        assert purge.await_args_list[0].args == ("session-21",)
        assert purge.await_args_list[0].kwargs == {"permanently_deleted": False}
        assert purge.await_args_list[1].args == ("session-22",)
        assert purge.await_args_list[1].kwargs == {"permanently_deleted": True}

    async def test_generation_aware_route_is_available_for_fenced_clients(self, client):
        body = {
            "sessionId": "test-generation-route",
            "messages": [],
            "userMessage": "Hello",
            "context": {"generationToken": "generation-1", "turnId": "turn-1"},
        }

        response = await client.post("/api/agent/v2/chat", json=body)

        assert response.status_code == 200
        last = json.loads(response.text.strip().split("\n")[-1])
        assert last["data"]["done"] is True
        assert last["data"]["fullResponse"] == "mocked response"

    async def test_generation_aware_route_rejects_missing_generation_token(self, client):
        response = await client.post("/api/agent/v2/chat", json={
            "sessionId": "test-generation-route-missing-token",
            "messages": [],
            "userMessage": "Hello",
            "context": {"turnId": "turn-1"},
        })

        assert response.status_code == 422

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


class TestRAG:
    async def test_rebuild_returns_verified_shared_index_metadata(self, client, monkeypatch):
        received_articles = []

        def rebuild(articles):
            received_articles.extend(articles)
            return 1

        def stats():
            return {
                "backend": "qdrant",
                "total_vectors": 2,
                "total_articles": 1,
                "index_version": "verified-index-version",
            }

        monkeypatch.setattr(rag_service, "rebuild_index", rebuild)
        monkeypatch.setattr(rag_service, "get_stats", stats)
        response = await client.post("/api/agent/rag/rebuild", json={
            "articles": [{"id": 8, "title": "已发布知识", "content": "知识内容"}],
        })

        assert response.status_code == 200
        assert received_articles == [{"id": 8, "title": "已发布知识", "content": "知识内容"}]
        assert response.json()["data"]["index_version"] == "verified-index-version"
        assert response.json()["data"]["total_vectors"] == 2


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
