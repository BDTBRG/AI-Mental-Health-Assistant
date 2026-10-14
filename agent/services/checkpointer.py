"""Select and manage the configured LangGraph checkpoint backend."""

from contextlib import asynccontextmanager

import config


@asynccontextmanager
async def open_checkpointer():
    """Open a checkpoint store and prepare its schema before serving requests.

    SQLite remains the default for existing local development. Deployments can
    opt into PostgreSQL so multiple Agent replicas share the same graph state.
    This function never imports, opens, or migrates the SQLite file in
    PostgreSQL mode.
    """
    backend = config.AGENT_CHECKPOINT_BACKEND
    if backend == "postgres":
        dsn = config.AGENT_CHECKPOINT_POSTGRES_DSN.strip()
        if not dsn:
            raise RuntimeError("AGENT_CHECKPOINT_POSTGRES_DSN is required for postgres backend")

        from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

        async with AsyncPostgresSaver.from_conn_string(dsn) as checkpointer:
            await checkpointer.setup()
            yield checkpointer
        return

    if backend == "sqlite":
        from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

        async with AsyncSqliteSaver.from_conn_string(config.AGENT_CHECKPOINT_DB) as checkpointer:
            yield checkpointer
        return

    raise ValueError("AGENT_CHECKPOINT_BACKEND must be 'sqlite' or 'postgres'")
