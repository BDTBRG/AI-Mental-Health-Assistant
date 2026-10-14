"""Tests for the configured LangGraph checkpoint store selection."""

import importlib.util
import sys
import unittest
from contextlib import asynccontextmanager
from pathlib import Path
from types import ModuleType
from unittest.mock import patch


AGENT_ROOT = Path(__file__).resolve().parents[1]
CHECKPOINTER_PATH = AGENT_ROOT / "services" / "checkpointer.py"


class FakeSaver:
    instances = []

    def __init__(self, connection_string):
        self.connection_string = connection_string
        self.setup_called = False
        self.closed = False
        type(self).instances.append(self)

    async def setup(self):
        self.setup_called = True

    @classmethod
    def from_conn_string(cls, connection_string):
        saver = cls(connection_string)

        @asynccontextmanager
        async def saver_context():
            try:
                yield saver
            finally:
                saver.closed = True

        return saver_context()


def module(name, **attributes):
    result = ModuleType(name)
    result.__path__ = []
    for key, value in attributes.items():
        setattr(result, key, value)
    return result


def load_checkpointer(backend, sqlite_path="agent/data/agent_checkpoints.sqlite", postgres_dsn=""):
    FakeSaver.instances.clear()
    sqlite_saver = module("langgraph.checkpoint.sqlite.aio", AsyncSqliteSaver=FakeSaver)
    postgres_saver = module("langgraph.checkpoint.postgres.aio", AsyncPostgresSaver=FakeSaver)
    fake_config = module(
        "config",
        AGENT_CHECKPOINT_BACKEND=backend,
        AGENT_CHECKPOINT_DB=sqlite_path,
        AGENT_CHECKPOINT_POSTGRES_DSN=postgres_dsn,
    )
    fake_modules = {
        "config": fake_config,
        "langgraph": module("langgraph"),
        "langgraph.checkpoint": module("langgraph.checkpoint"),
        "langgraph.checkpoint.sqlite": module("langgraph.checkpoint.sqlite"),
        "langgraph.checkpoint.sqlite.aio": sqlite_saver,
        "langgraph.checkpoint.postgres": module("langgraph.checkpoint.postgres"),
        "langgraph.checkpoint.postgres.aio": postgres_saver,
    }
    spec = importlib.util.spec_from_file_location("checkpointer_under_test", CHECKPOINTER_PATH)
    loaded = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, fake_modules):
        spec.loader.exec_module(loaded)
    return loaded, fake_modules


class CheckpointerTests(unittest.IsolatedAsyncioTestCase):
    async def test_postgres_backend_initializes_shared_store_before_yield(self):
        checkpointer_module, fake_modules = load_checkpointer(
            "postgres", postgres_dsn="postgresql://agent:secret@postgres/agent"
        )

        with patch.dict(sys.modules, fake_modules):
            async with checkpointer_module.open_checkpointer() as saver:
                self.assertTrue(saver.setup_called)
                self.assertEqual("postgresql://agent:secret@postgres/agent", saver.connection_string)
                self.assertFalse(saver.closed)

        self.assertTrue(saver.closed)

    async def test_sqlite_backend_remains_available_for_local_development(self):
        checkpointer_module, fake_modules = load_checkpointer(
            "sqlite", sqlite_path="./local-checkpoints.sqlite"
        )

        with patch.dict(sys.modules, fake_modules):
            async with checkpointer_module.open_checkpointer() as saver:
                self.assertEqual("./local-checkpoints.sqlite", saver.connection_string)
                self.assertFalse(saver.setup_called)

        self.assertTrue(saver.closed)

    async def test_postgres_backend_fails_closed_without_dsn(self):
        checkpointer_module, fake_modules = load_checkpointer("postgres")

        with patch.dict(sys.modules, fake_modules):
            with self.assertRaisesRegex(RuntimeError, "AGENT_CHECKPOINT_POSTGRES_DSN"):
                async with checkpointer_module.open_checkpointer():
                    self.fail("A checkpoint store without a DSN must not start")

    async def test_unknown_backend_is_rejected(self):
        checkpointer_module, fake_modules = load_checkpointer("memory")

        with patch.dict(sys.modules, fake_modules):
            with self.assertRaisesRegex(ValueError, "AGENT_CHECKPOINT_BACKEND"):
                async with checkpointer_module.open_checkpointer():
                    self.fail("An unknown checkpoint backend must not start")


if __name__ == "__main__":
    unittest.main()
