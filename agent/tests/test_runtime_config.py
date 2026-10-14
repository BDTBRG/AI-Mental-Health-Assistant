import ast
import os
import sys
import unittest
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from types import ModuleType
from unittest.mock import patch


AGENT_ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = AGENT_ROOT / "config.py"
APP_PATH = AGENT_ROOT / "app.py"


def load_config(environment):
    dotenv_stub = ModuleType("dotenv")
    dotenv_stub.load_dotenv = lambda: None
    spec = spec_from_file_location("agent_config_under_test", CONFIG_PATH)
    module = module_from_spec(spec)
    with patch.dict(os.environ, environment, clear=False):
        runtime_keys = (
            "AGENT_HOST",
            "AGENT_PORT",
            "AGENT_RELOAD",
            "AGENT_CHECKPOINT_BACKEND",
            "AGENT_CHECKPOINT_DB",
            "AGENT_CHECKPOINT_POSTGRES_DSN",
        )
        for key in runtime_keys:
            if key not in environment:
                os.environ.pop(key, None)
        with patch.dict(sys.modules, {"dotenv": dotenv_stub}):
            spec.loader.exec_module(module)
            observed_environment = {
                key: os.environ.get(key)
                for key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")
            }
    return module, observed_environment


class AgentRuntimeConfigTests(unittest.TestCase):
    def test_import_preserves_deployment_proxy_environment(self):
        proxy_values = {
            "HTTP_PROXY": "http://proxy.example:8080",
            "HTTPS_PROXY": "http://secure-proxy.example:8443",
            "ALL_PROXY": "socks5://proxy.example:1080",
        }
        _, observed = load_config(proxy_values)
        self.assertEqual(proxy_values, observed)

    def test_host_port_and_reload_are_environment_configurable(self):
        config, _ = load_config({
            "AGENT_HOST": "127.0.0.1",
            "AGENT_PORT": "5050",
            "AGENT_RELOAD": "true",
        })
        self.assertEqual("127.0.0.1", config.AGENT_HOST)
        self.assertEqual(5050, config.AGENT_PORT)
        self.assertTrue(config.AGENT_RELOAD)

    def test_reload_is_disabled_by_default(self):
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("AGENT_RELOAD", None)
            config, _ = load_config({})
        self.assertFalse(config.AGENT_RELOAD)

    def test_checkpoint_backend_defaults_to_existing_sqlite_path(self):
        config, _ = load_config({})
        self.assertEqual("sqlite", config.AGENT_CHECKPOINT_BACKEND)
        self.assertTrue(config.AGENT_CHECKPOINT_DB.endswith("agent_checkpoints.sqlite"))

    def test_checkpoint_backend_accepts_postgres_dsn(self):
        config, _ = load_config({
            "AGENT_CHECKPOINT_BACKEND": "postgres",
            "AGENT_CHECKPOINT_POSTGRES_DSN": "postgresql://agent:secret@db/agent",
        })
        self.assertEqual("postgres", config.AGENT_CHECKPOINT_BACKEND)
        self.assertEqual(
            "postgresql://agent:secret@db/agent",
            config.AGENT_CHECKPOINT_POSTGRES_DSN,
        )

    def test_checkpoint_backend_rejects_unknown_values(self):
        with self.assertRaisesRegex(ValueError, "AGENT_CHECKPOINT_BACKEND"):
            load_config({"AGENT_CHECKPOINT_BACKEND": "memory"})

    def test_port_accepts_only_valid_tcp_port_range(self):
        for port in ("1", "65535"):
            with self.subTest(port=port):
                config, _ = load_config({"AGENT_PORT": port})
                self.assertEqual(int(port), config.AGENT_PORT)

        for port in ("0", "65536", "abc"):
            with self.subTest(port=port):
                with self.assertRaisesRegex(ValueError, "AGENT_PORT must be between 1 and 65535"):
                    load_config({"AGENT_PORT": port})

    def test_launcher_uses_the_configured_reload_flag(self):
        tree = ast.parse(APP_PATH.read_text(encoding="utf-8-sig"))
        uvicorn_calls = [
            node for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name)
            and node.func.value.id == "uvicorn"
            and node.func.attr == "run"
        ]
        self.assertEqual(1, len(uvicorn_calls))
        reload_value = next(keyword.value for keyword in uvicorn_calls[0].keywords if keyword.arg == "reload")
        self.assertIsInstance(reload_value, ast.Attribute)
        self.assertEqual("AGENT_RELOAD", reload_value.attr)


if __name__ == "__main__":
    unittest.main()
