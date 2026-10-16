"""Shared model-configuration checks for services that may run without an LLM key."""

import config


LLM_NOT_CONFIGURED = "LLM_NOT_CONFIGURED"


class LLMNotConfigured(RuntimeError):
    """The requested model operation cannot run without provider credentials."""

    code = LLM_NOT_CONFIGURED

    def __init__(self):
        super().__init__(self.code)


def has_llm_credentials() -> bool:
    return bool(config.DEEPSEEK_API_KEY.strip())


def require_llm_credentials() -> None:
    if not has_llm_credentials():
        raise LLMNotConfigured()
