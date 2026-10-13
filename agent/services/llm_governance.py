"""Process-level concurrency, timeout, circuit breaking and token telemetry for LLM calls."""

import asyncio
import logging
import time
from collections import defaultdict, deque
from typing import Any, Dict, List

import config

logger = logging.getLogger(__name__)


class ProviderCircuitOpen(RuntimeError):
    pass


class LLMCallGovernor:
    def __init__(self):
        self._slots = asyncio.Semaphore(config.LLM_MAX_CONCURRENT_CALLS)
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
        self._enforce_user_limit(str(context.get("userId", "")), now)
        started = time.monotonic()
        try:
            async with self._slots:
                response = await asyncio.wait_for(
                    llm.ainvoke(messages),
                    timeout=config.LLM_REQUEST_TIMEOUT_SECONDS,
                )
        except Exception:
            self._record_failure(time.monotonic())
            logger.warning(
                "llm_call_failed stage=%s model=%s prompt=%s index=%s elapsed_ms=%d turn=%s",
                stage,
                config.DEEPSEEK_MODEL,
                prompt_version,
                context.get("knowledgeIndexVersion", "not-applicable"),
                int((time.monotonic() - started) * 1000),
                context.get("turnId", ""),
            )
            raise

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
