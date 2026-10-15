import asyncio
from types import SimpleNamespace

import pytest

import config
from services.llm_governance import LLMCallGovernor, LLMQueueTimeout, LLMRequestTimeout


class FakeLLM:
    def __init__(self, error=None):
        self.error = error
        self.calls = 0

    async def ainvoke(self, _messages):
        self.calls += 1
        if self.error:
            raise self.error
        return SimpleNamespace(content="ok")


@pytest.mark.asyncio
async def test_invoke_fails_when_queue_wait_budget_expires(monkeypatch):
    monkeypatch.setattr(config, "LLM_QUEUE_WAIT_TIMEOUT_SECONDS", 0.01)
    governor = LLMCallGovernor()
    governor._slots = asyncio.Semaphore(0)

    with pytest.raises(LLMQueueTimeout):
        await governor.invoke(FakeLLM(), [], "chat-agent")


@pytest.mark.asyncio
async def test_invoke_releases_global_permit_after_model_failure():
    governor = LLMCallGovernor()
    failing_model = FakeLLM(error=RuntimeError("provider failed"))

    with pytest.raises(RuntimeError, match="provider failed"):
        await governor.invoke(failing_model, [], "chat-agent")

    response = await governor.invoke(FakeLLM(), [], "chat-agent")

    assert response.content == "ok"
    assert governor._slots._value == config.LLM_MAX_CONCURRENT_CALLS


@pytest.mark.asyncio
async def test_provider_timeout_has_a_distinct_error_and_releases_permit(monkeypatch):
    monkeypatch.setattr(config, "LLM_REQUEST_TIMEOUT_SECONDS", 0.01)

    class SlowLLM:
        async def ainvoke(self, _messages):
            await asyncio.Event().wait()

    governor = LLMCallGovernor()

    with pytest.raises(LLMRequestTimeout):
        await governor.invoke(SlowLLM(), [], "chat-agent")

    assert governor._slots._value == config.LLM_MAX_CONCURRENT_CALLS


@pytest.mark.asyncio
async def test_context_summary_has_an_independent_concurrency_budget(monkeypatch):
    monkeypatch.setattr(config, "LLM_QUEUE_WAIT_TIMEOUT_SECONDS", 0.01)
    monkeypatch.setattr(config, "LLM_CONTEXT_SUMMARY_MAX_CONCURRENT_CALLS", 1)
    started = asyncio.Event()
    release = asyncio.Event()

    class WaitingLLM:
        async def ainvoke(self, _messages):
            started.set()
            await release.wait()
            return SimpleNamespace(content="ok")

    governor = LLMCallGovernor()
    first_call = asyncio.create_task(
        governor.invoke(WaitingLLM(), [], "context-summary")
    )
    await started.wait()

    with pytest.raises(LLMQueueTimeout):
        await governor.invoke(FakeLLM(), [], "context-summary")

    release.set()
    assert (await first_call).content == "ok"


@pytest.mark.asyncio
async def test_cancellation_releases_summary_and_global_permits():
    started = asyncio.Event()

    class CancellableLLM:
        async def ainvoke(self, _messages):
            started.set()
            await asyncio.Event().wait()

    governor = LLMCallGovernor()
    pending = asyncio.create_task(
        governor.invoke(CancellableLLM(), [], "context-summary")
    )
    await started.wait()
    pending.cancel()

    with pytest.raises(asyncio.CancelledError):
        await pending

    result = await governor.invoke(FakeLLM(), [], "context-summary")
    assert result.content == "ok"
