"""Build LangGraph checkpoint identities for isolated Agent executions."""


def require_generation_token(context):
    """Return a normalized generation token or reject a v2 chat request."""
    token = context.get("generationToken") if isinstance(context, dict) else None
    if not isinstance(token, str) or not token.strip():
        raise ValueError("generationToken is required")
    return token.strip()


def build_checkpoint_run_config(session_id, generation_token=None, turn_id=None):
    """Keep a session as purge boundary and use a stable execution namespace."""
    normalized_session_id = str(session_id or "agent-session")
    normalized_generation = str(generation_token or turn_id or "agent-generation")
    return {
        "configurable": {
            "thread_id": normalized_session_id,
            "checkpoint_ns": normalized_generation,
        }
    }
