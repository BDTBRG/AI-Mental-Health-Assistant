from prometheus_client import generate_latest
import pytest

import app as app_module
from services.agent_metrics import (
    AGENT_METRICS_REGISTRY,
    observe_context_summary_duration,
    observe_llm_queue_wait,
    record_agent_turn_deadline,
    record_llm_queue_timeout,
)


def test_agent_metrics_expose_governance_without_user_or_session_labels():
    observe_llm_queue_wait("chat-agent", 0.025)
    observe_llm_queue_wait("unbounded-user-controlled-stage", 0.001)
    record_llm_queue_timeout("context-summary")
    record_agent_turn_deadline()
    observe_context_summary_duration(0.1)

    exposition = generate_latest(AGENT_METRICS_REGISTRY).decode("utf-8")

    assert "amha_agent_llm_queue_wait_seconds" in exposition
    assert 'stage="other"' in exposition
    assert "amha_agent_llm_queue_timeouts_total" in exposition
    assert "amha_agent_turn_deadlines_total" in exposition
    assert "amha_agent_context_summary_duration_seconds" in exposition
    assert "user_id" not in exposition.lower()
    assert "userid" not in exposition.lower()
    assert "session_id" not in exposition.lower()
    assert "sessionid" not in exposition.lower()


@pytest.mark.asyncio
async def test_agent_metrics_route_returns_prometheus_exposition():
    response = await app_module.agent_metrics()

    assert response.status_code == 200
    assert "text/plain" in response.media_type
    assert b"amha_agent_llm_queue_wait_seconds" in response.body
