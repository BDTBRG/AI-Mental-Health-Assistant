"""Process-level concurrency, timeout, circuit breaking and token telemetry for LLM calls."""

import asyncio
import logging
import time
from collections import defaultdict, deque
from typing import Any, Dict, List

import config
from services.agent_metrics import observe_llm_queue_wait, record_llm_queue_timeout

logger = logging.getLogger(__name__)


class ProviderCircuitOpen(RuntimeError):
    pass


class LLMQueueTimeout(TimeoutError):
    """The caller could not get an LLM permit within its queue budget."""


class LLMRequestTimeout(TimeoutError):
    """The provider did not finish an LLM request within its call budget."""


class LLMCallGovernor:
    def __init__(self):
        self._slots = asyncio.Semaphore(config.LLM_MAX_CONCURRENT_CALLS)
        self._summary_slots = asyncio.Semaphore(
            config.LLM_CONTEXT_SUMMARY_MAX_CONCURRENT_CALLS
        )
        self._failure_times = deque(maxlen=config.LLM_CIRCUIT_FAILURE_THRESHOLD)
        self._open_until = 0.0
        self._user_call_times = defaultdict(deque)

    async def invoke(self, llm, messages: List[Any], stage: str,
                     request_context: Dict[str, Any] | None = None,
                     prompt_version: str = "unversioned"):
        now = time.monotonic()
        if now < self._open_until:
            raise ProviderCircuitOpen("LLM provider circuit is open")

        context = request_context or {}
        started = time.monotonic()
        queue_started = started
        queue_wait_recorded = False
        acquired_slots = []
        try:
            loop = asyncio.get_running_loop()
            queue_deadline = loop.time() + config.LLM_QUEUE_WAIT_TIMEOUT_SECONDS
            if stage == "context-summary":
                await self._acquire(self._summary_slots, queue_deadline, loop)
                acquired_slots.append(self._summary_slots)
            await self._acquire(self._slots, queue_deadline, loop)
            acquired_slots.append(self._slots)
            observe_llm_queue_wait(stage, time.monotonic() - queue_started)
            queue_wait_recorded = True
            self._enforce_user_limit(str(context.get("userId", "")), time.monotonic())
            try:
                response = await asyncio.wait_for(
                    llm.ainvoke(messages),
                    timeout=config.LLM_REQUEST_TIMEOUT_SECONDS,
                )
            except asyncio.TimeoutError as error:
                self._record_failure(time.monotonic())
                self._log_failure(stage, prompt_version, context, started)
                raise LLMRequestTimeout("LLM provider request timed out") from error
            except Exception:
                self._record_failure(time.monotonic())
                self._log_failure(stage, prompt_version, context, started)
                raise
        except LLMQueueTimeout:
            if not queue_wait_recorded:
                observe_llm_queue_wait(stage, time.monotonic() - queue_started)
            record_llm_queue_timeout(stage)
            raise
        finally:
            for semaphore in reversed(acquired_slots):
                semaphore.release()

        self._failure_times.clear()
        self._open_until = 0.0
        usage = getattr(response, "usage_metadata", None) or {}
        if not usage:
            usage = getattr(response, "response_metadata", {}).get("token_usage", {})
        logger.info(
            "llm_call stage=%s model=%s prompt=%s index=%s latency_ms=%d input_tokens=%s output_tokens=%s turn=%s",
            stage,
            config.DEEPSEEK_MODEL,
            prompt_version,
            context.get("knowledgeIndexVersion", "not-applicable"),
            int((time.monotonic() - started) * 1000),
            usage.get("input_tokens", usage.get("prompt_tokens", "unknown")),
            usage.get("output_tokens", usage.get("completion_tokens", "unknown")),
            context.get("turnId", ""),
        )
        return response

    @staticmethod
    async def _acquire(semaphore: asyncio.Semaphore, deadline: float, loop) -> None:
        remaining = deadline - loop.time()
        if remaining <= 0:
            raise LLMQueueTimeout("LLM queue wait budget expired")
        try:
            await asyncio.wait_for(semaphore.acquire(), timeout=remaining)
        except asyncio.TimeoutError as error:
            raise LLMQueueTimeout("LLM queue wait budget expired") from error

    @staticmethod
    def _log_failure(stage: str, prompt_version: str,
                     context: Dict[str, Any], started: float) -> None:
        logger.warning(
            "llm_call_failed stage=%s model=%s prompt=%s index=%s elapsed_ms=%d turn=%s",
            stage,
            config.DEEPSEEK_MODEL,
            prompt_version,
            context.get("knowledgeIndexVersion", "not-applicable"),
            int((time.monotonic() - started) * 1000),
            context.get("turnId", ""),
        )

    def _enforce_user_limit(self, user_id: str, now: float) -> None:
        if not user_id:
            return
        calls = self._user_call_times[user_id]
        while calls and now - calls[0] > 60:
            calls.popleft()
        if len(calls) >= config.LLM_USER_CALLS_PER_MINUTE:
            raise RuntimeError("用户的模型调用频率已达到当前上限")
        calls.append(now)

    def _record_failure(self, now: float) -> None:
        while self._failure_times and now - self._failure_times[0] > 60:
            self._failure_times.popleft()
        self._failure_times.append(now)
        if len(self._failure_times) >= config.LLM_CIRCUIT_FAILURE_THRESHOLD:
            self._open_until = now + config.LLM_CIRCUIT_RESET_SECONDS
            logger.error("llm_circuit_open duration_seconds=%d", config.LLM_CIRCUIT_RESET_SECONDS)


llm_call_governor = LLMCallGovernor()
