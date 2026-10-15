"""Build LangGraph checkpoint identities at the session thread boundary."""


def require_generation_token(context):
    """Return a normalized generation token or reject a v2 chat request."""
    token = context.get("generationToken") if isinstance(context, dict) else None
    if not isinstance(token, str) or not token.strip():
        raise ValueError("generationToken is required")
    return token.strip()


def build_checkpoint_run_config(session_id):
    """Use LangGraph's root namespace; generation fencing lives in saved state."""
    normalized_session_id = str(session_id or "agent-session")
    return {"configurable": {"thread_id": normalized_session_id}}
