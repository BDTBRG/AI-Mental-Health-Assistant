"""Build LangGraph checkpoint identities at the session thread boundary."""


def require_generation_token(context):
    """Return a normalized generation token or reject a v2 chat request."""
    token = context.get("generationToken") if isinstance(context, dict) else None
    if not isinstance(token, str) or not token.strip():
        raise ValueError("generationToken is required")
    return token.strip()


def _require_positive_integer(context, field, label):
    value = context.get(field) if isinstance(context, dict) else None
    if type(value) is not int or value <= 0 or value > 9_223_372_036_854_775_807:
        raise ValueError(f"{label} must be a positive integer")
    return value


def require_generation_epoch(context):
    """Return the attempt number, allowing pre-fence clients to use epoch zero."""
    if isinstance(context, dict) and "generationEpoch" not in context:
        return 0
    return _require_positive_integer(context, "generationEpoch", "generationEpoch")


def require_turn_sequence(context):
    """Return the turn order, allowing pre-fence clients to use sequence zero."""
    if isinstance(context, dict) and "turnSequence" not in context:
        return 0
    return _require_positive_integer(context, "turnSequence", "turnSequence")


def normalize_generation_fencing_fields(context):
    """Validate explicit fencing values while defaulting absent legacy fields to zero."""
    return {
        "generationEpoch": require_generation_epoch(context),
        "turnSequence": require_turn_sequence(context),
    }


def build_checkpoint_run_config(session_id):
    """Use LangGraph's root namespace; generation fencing lives in saved state."""
    normalized_session_id = str(session_id or "agent-session")
    return {"configurable": {"thread_id": normalized_session_id}}
