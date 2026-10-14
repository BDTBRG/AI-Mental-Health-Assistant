"""Shared test isolation for Agent configuration and model-backed services."""

import os

import dotenv


# Prevent config.py from loading a developer's local .env while tests are collected.
def disable_dotenv_loading(*_args, **_kwargs):
    return False


dotenv.load_dotenv = disable_dotenv_loading
os.environ["DEEPSEEK_API_KEY"] = "unit-test-api-key"
os.environ["AGENT_INTERNAL_TOKEN"] = "unit-test-agent-token"
