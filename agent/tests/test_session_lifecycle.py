"""Cross-request session lifecycle fencing tests (standard library only)."""

import asyncio
import unittest

from services.session_lifecycle import (
    DeletedAgentSessionError,
    SessionLifecycleGuard,
)


class FakeCursor:
    def __init__(self, row=None):
        self.row = row

    async def fetchone(self):
        return self.row


class FakeConnection:
    def __init__(self, pool):
        self.pool = pool
        self.held_session = None
        self.closed = False

    async def execute(self, statement, params=None):
        self.pool.statements.append((statement, params))
        if "pg_advisory_lock" in statement:
            self.held_session = params[0]
            lock = self.pool.advisory_locks.setdefault(params[0], asyncio.Lock())
            await lock.acquire()
        elif "pg_advisory_unlock" in statement and self.held_session is not None:
            if self.pool.cancel_on_unlock:
                raise asyncio.CancelledError()
            self.pool.advisory_locks[self.held_session].release()
            self.held_session = None
        if "INSERT INTO agent_deleted_sessions" in statement:
            self.pool.deleted.add(params[0])
        if "SELECT EXISTS" in statement:
            return FakeCursor((params[0] in self.pool.deleted,))
        return FakeCursor((None,))

    async def close(self):
        self.closed = True
        if self.held_session is not None:
            self.pool.advisory_locks[self.held_session].release()
            self.held_session = None


class FakeConnectionContext:
    def __init__(self, connection):
        self.connection = connection

    async def __aenter__(self):
        return self.connection

    async def __aexit__(self, *_args):
        return False


class FakePool:
    def __init__(self, cancel_on_unlock=False):
        self.deleted = set()
        self.statements = []
        self.advisory_locks = {}
        self.connections = []
        self.cancel_on_unlock = cancel_on_unlock

    def connection(self):
        connection = FakeConnection(self)
        self.connections.append(connection)
        return FakeConnectionContext(connection)


class SessionLifecycleGuardTests(unittest.IsolatedAsyncioTestCase):
    async def test_postgres_execution_uses_session_advisory_lock(self):
        pool = FakePool()
        guard = SessionLifecycleGuard("postgres", pool)

        async with guard.execution("session-17"):
            pass

        sql = [statement for statement, _ in pool.statements]
        self.assertTrue(any("pg_advisory_lock" in statement for statement in sql))
        self.assertTrue(any("pg_advisory_unlock" in statement for statement in sql))
        lock_params = next(params for statement, params in pool.statements
                           if "pg_advisory_lock" in statement)
        unlock_params = next(params for statement, params in pool.statements
                             if "pg_advisory_unlock" in statement)
        self.assertEqual(("session-17",), lock_params)
        self.assertEqual(lock_params, unlock_params)

    async def test_cancelled_unlock_closes_connection_to_release_session_lock(self):
        pool = FakePool(cancel_on_unlock=True)
        guard = SessionLifecycleGuard("postgres", pool)

        with self.assertRaises(asyncio.CancelledError):
            async with guard.execution("session-20"):
                pass

        self.assertTrue(pool.connections[0].closed)
        self.assertFalse(pool.advisory_locks["session-20"].locked())

    async def test_cancelled_purge_unlock_closes_connection_to_release_session_lock(self):
        pool = FakePool(cancel_on_unlock=True)
        guard = SessionLifecycleGuard("postgres", pool)
        purged = []

        with self.assertRaises(asyncio.CancelledError):
            await guard.purge("session-21", lambda: purged.append("done"))

        self.assertEqual(["done"], purged)
        self.assertTrue(pool.connections[0].closed)
        self.assertFalse(pool.advisory_locks["session-21"].locked())

    async def test_deleted_session_is_tombstoned_before_waiting_for_active_run(self):
        pool = FakePool()
        guard = SessionLifecycleGuard("postgres", pool)
        entered = asyncio.Event()
        release = asyncio.Event()
        events = []

        async def active_run():
            async with guard.execution("session-18"):
                events.append("run-start")
                entered.set()
                await release.wait()
                events.append("run-checkpoint-write")

        run_task = asyncio.create_task(active_run())
        await entered.wait()

        async def purge_checkpoints():
            events.append("purged")

        purge_task = asyncio.create_task(
            guard.purge("session-18", purge_checkpoints, permanently_deleted=True)
        )
        await asyncio.sleep(0)

        insert_position = next(
            index for index, (statement, _) in enumerate(pool.statements)
            if "INSERT INTO agent_deleted_sessions" in statement
        )
        lock_position = max(
            index for index, (statement, _) in enumerate(pool.statements)
            if "pg_advisory_lock" in statement
        )
        self.assertLess(insert_position, lock_position)

        release.set()
        await asyncio.gather(run_task, purge_task)
        self.assertEqual(
            ["run-start", "run-checkpoint-write", "purged"], events
        )

        with self.assertRaises(DeletedAgentSessionError):
            async with guard.execution("session-18"):
                self.fail("a deleted session must not start a new graph execution")

    async def test_local_guard_serializes_run_and_purge(self):
        guard = SessionLifecycleGuard("sqlite")
        entered = asyncio.Event()
        release = asyncio.Event()
        events = []

        async def active_run():
            async with guard.execution("session-19"):
                events.append("run-start")
                entered.set()
                await release.wait()
                events.append("run-checkpoint-write")

        run_task = asyncio.create_task(active_run())
        await entered.wait()
        purge_task = asyncio.create_task(
            guard.purge("session-19", lambda: _record(events, "purged"), True)
        )
        await asyncio.sleep(0)
        self.assertEqual(["run-start"], events)

        release.set()
        await asyncio.gather(run_task, purge_task)
        self.assertEqual(
            ["run-start", "run-checkpoint-write", "purged"], events
        )


async def _record(events, value):
    events.append(value)


if __name__ == "__main__":
    unittest.main()
