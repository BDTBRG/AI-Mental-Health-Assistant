"""Serialize Agent executions with checkpoint purges for each chat session."""

import asyncio
import inspect
from contextlib import asynccontextmanager
from typing import AsyncIterator, Callable


class DeletedAgentSessionError(RuntimeError):
    """Raised when a request tries to recreate state for a deleted session."""


class SessionLifecycleGuard:
    """Guard checkpoint reads/writes and purges with a per-session lock.

    PostgreSQL mode uses session advisory locks and durable deletion tombstones,
    shared by all Agent replicas. SQLite mode is intended for the local,
    single-process development setup and keeps the same ordering in-process.
    """

    _LOCK_SQL = "SELECT pg_advisory_lock(hashtextextended(%s, 0))"
    _UNLOCK_SQL = "SELECT pg_advisory_unlock(hashtextextended(%s, 0))"
    _IS_DELETED_SQL = (
        "SELECT EXISTS (SELECT 1 FROM agent_deleted_sessions WHERE session_id = %s)"
    )
    _MARK_DELETED_SQL = (
        "INSERT INTO agent_deleted_sessions (session_id) VALUES (%s) "
        "ON CONFLICT (session_id) DO NOTHING"
    )

    def __init__(self, backend: str, postgres_pool=None, tombstone_pool=None):
        if backend not in {"sqlite", "postgres"}:
            raise ValueError("backend must be 'sqlite' or 'postgres'")
        if backend == "postgres" and postgres_pool is None:
            raise ValueError("postgres_pool is required for PostgreSQL session fencing")
        self.backend = backend
        self._postgres_pool = postgres_pool
        self._tombstone_pool = tombstone_pool or postgres_pool
        self._local_deleted_sessions = set()
        self._local_locks = {}
        self._local_lock_references = {}
        self._local_lock_registry = asyncio.Lock()

    @asynccontextmanager
    async def execution(self, session_id: str) -> AsyncIterator[None]:
        """Hold the session lock through checkpoint reads and graph completion."""
        normalized_session_id = str(session_id)
        if self.backend == "postgres":
            async with self._postgres_pool.connection() as connection:
                try:
                    await connection.execute(self._LOCK_SQL, (normalized_session_id,))
                    if await self._is_deleted(connection, normalized_session_id):
                        raise DeletedAgentSessionError("Agent session has been deleted")
                    yield
                finally:
                    try:
                        await connection.execute(self._UNLOCK_SQL, (normalized_session_id,))
                    except BaseException:
                        # Do not return a possibly still-locked connection to
                        # the pool. Closing it releases PostgreSQL session locks.
                        await connection.close()
                        raise
            return

        lock = await self._acquire_local_lock(normalized_session_id)
        try:
            async with lock:
                if normalized_session_id in self._local_deleted_sessions:
                    raise DeletedAgentSessionError("Agent session has been deleted")
                yield
        finally:
            await self._release_local_lock_reference(normalized_session_id, lock)

    async def purge(
        self,
        session_id: str,
        delete_checkpoints: Callable[[], object],
        permanently_deleted: bool = False,
    ) -> None:
        """Fence, then purge checkpoints; permanent deletes block future runs."""
        normalized_session_id = str(session_id)
        if permanently_deleted:
            await self._mark_deleted(normalized_session_id)

        async with self._purge_lock(normalized_session_id):
            result = delete_checkpoints()
            if inspect.isawaitable(result):
                await result

    @asynccontextmanager
    async def _purge_lock(self, session_id: str) -> AsyncIterator[None]:
        """Acquire the same lock without rejecting a pre-existing tombstone."""
        if self.backend == "postgres":
            async with self._postgres_pool.connection() as connection:
                try:
                    await connection.execute(self._LOCK_SQL, (session_id,))
                    yield
                finally:
                    try:
                        await connection.execute(self._UNLOCK_SQL, (session_id,))
                    except BaseException:
                        await connection.close()
                        raise
            return

        lock = await self._acquire_local_lock(session_id)
        try:
            async with lock:
                yield
        finally:
            await self._release_local_lock_reference(session_id, lock)

    async def _mark_deleted(self, session_id: str) -> None:
        if self.backend == "postgres":
            # autocommit is enabled on the lifecycle pool so the tombstone is
            # visible before this request waits behind an active graph run. A
            # separate tiny pool prevents active graphs from exhausting all
            # lock connections and blocking deletion fencing.
            async with self._tombstone_pool.connection() as connection:
                await connection.execute(self._MARK_DELETED_SQL, (session_id,))
            return
        self._local_deleted_sessions.add(session_id)

    async def _is_deleted(self, connection, session_id: str) -> bool:
        cursor = await connection.execute(self._IS_DELETED_SQL, (session_id,))
        row = await cursor.fetchone()
        return bool(row and row[0])

    async def check_ready(self) -> bool:
        if self.backend == "sqlite":
            return True
        async with self._postgres_pool.connection() as connection:
            cursor = await connection.execute("SELECT 1")
            row = await cursor.fetchone()
        if not row or row[0] != 1:
            return False
        async with self._tombstone_pool.connection() as connection:
            cursor = await connection.execute("SELECT 1")
            row = await cursor.fetchone()
            return bool(row and row[0] == 1)

    async def _acquire_local_lock(self, session_id: str) -> asyncio.Lock:
        async with self._local_lock_registry:
            lock = self._local_locks.get(session_id)
            if lock is None:
                lock = asyncio.Lock()
                self._local_locks[session_id] = lock
                self._local_lock_references[session_id] = 0
            self._local_lock_references[session_id] += 1
            return lock

    async def _release_local_lock_reference(self, session_id: str, lock: asyncio.Lock) -> None:
        async with self._local_lock_registry:
            references = self._local_lock_references.get(session_id, 0) - 1
            if references <= 0 and self._local_locks.get(session_id) is lock:
                self._local_locks.pop(session_id, None)
                self._local_lock_references.pop(session_id, None)
            else:
                self._local_lock_references[session_id] = references


@asynccontextmanager
async def open_session_lifecycle_guard(backend: str, postgres_dsn: str = "", pool_size: int = 16):
    """Create shared PostgreSQL fencing state or a local SQLite-mode guard."""
    if backend == "sqlite":
        yield SessionLifecycleGuard("sqlite")
        return
    if backend != "postgres":
        raise ValueError("backend must be 'sqlite' or 'postgres'")
    if not postgres_dsn.strip():
        raise RuntimeError("AGENT_CHECKPOINT_POSTGRES_DSN is required for postgres backend")

    from psycopg_pool import AsyncConnectionPool

    pool = AsyncConnectionPool(
        conninfo=postgres_dsn,
        min_size=1,
        max_size=max(1, int(pool_size)),
        kwargs={"autocommit": True},
        open=False,
    )
    tombstone_pool = AsyncConnectionPool(
        conninfo=postgres_dsn,
        min_size=1,
        max_size=1,
        kwargs={"autocommit": True},
        open=False,
    )
    await pool.open()
    try:
        await pool.wait()
        try:
            await tombstone_pool.open()
            await tombstone_pool.wait()
            async with tombstone_pool.connection() as connection:
                await connection.execute(
                    "CREATE TABLE IF NOT EXISTS agent_deleted_sessions ("
                    "session_id TEXT PRIMARY KEY, "
                    "deleted_at TIMESTAMPTZ NOT NULL DEFAULT now()"
                    ")"
                )
            yield SessionLifecycleGuard("postgres", pool, tombstone_pool)
        finally:
            await tombstone_pool.close()
    finally:
        await pool.close()
