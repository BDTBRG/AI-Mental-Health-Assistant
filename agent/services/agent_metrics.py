"""Low-cardinality Prometheus metrics for Agent request governance."""

from prometheus_client import CollectorRegistry, Counter, Histogram


AGENT_METRICS_REGISTRY = CollectorRegistry(auto_describe=True)

_KNOWN_STAGES = frozenset(
    {
        "chat-agent",
        "chat-finalize",
        "diary-analysis",
        "emotion-analysis",
        "context-summary",
    }
)

LLM_QUEUE_WAIT_SECONDS = Histogram(
    "amha_agent_llm_queue_wait_seconds",
    "Time spent waiting for Agent LLM concurrency permits.",
    labelnames=("stage",),
    registry=AGENT_METRICS_REGISTRY,
)
LLM_QUEUE_TIMEOUTS = Counter(
    "amha_agent_llm_queue_timeouts",
    "LLM calls rejected after exhausting their queue wait budget.",
    labelnames=("stage",),
    registry=AGENT_METRICS_REGISTRY,
)
AGENT_TURN_DEADLINES = Counter(
    "amha_agent_turn_deadlines",
    "Agent chat turns cancelled after reaching their end-to-end deadline.",
    registry=AGENT_METRICS_REGISTRY,
)
CONTEXT_SUMMARY_DURATION_SECONDS = Histogram(
    "amha_agent_context_summary_duration_seconds",
    "Time spent generating and validating a rolling context summary.",
    registry=AGENT_METRICS_REGISTRY,
)


def _bounded_stage(stage: str) -> str:
    return stage if stage in _KNOWN_STAGES else "other"


def observe_llm_queue_wait(stage: str, seconds: float) -> None:
    LLM_QUEUE_WAIT_SECONDS.labels(stage=_bounded_stage(stage)).observe(max(0.0, seconds))


def record_llm_queue_timeout(stage: str) -> None:
    LLM_QUEUE_TIMEOUTS.labels(stage=_bounded_stage(stage)).inc()


def record_agent_turn_deadline() -> None:
    AGENT_TURN_DEADLINES.inc()


def observe_context_summary_duration(seconds: float) -> None:
    CONTEXT_SUMMARY_DURATION_SECONDS.observe(max(0.0, seconds))
