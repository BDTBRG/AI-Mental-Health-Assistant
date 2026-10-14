import os
from dotenv import load_dotenv

load_dotenv()

DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")

AGENT_HOST = os.getenv("AGENT_HOST", "0.0.0.0")
try:
    AGENT_PORT = int(os.getenv("AGENT_PORT", "5000"))
except ValueError as error:
    raise ValueError("AGENT_PORT must be between 1 and 65535") from error
if not 1 <= AGENT_PORT <= 65535:
    raise ValueError("AGENT_PORT must be between 1 and 65535")
AGENT_RELOAD = os.getenv("AGENT_RELOAD", "false").strip().lower() in {"1", "true", "yes", "on"}
AGENT_CHECKPOINT_DB = os.getenv(
    "AGENT_CHECKPOINT_DB",
    os.path.join(os.path.dirname(__file__), "data", "agent_checkpoints.sqlite"),
)
AGENT_INTERNAL_TOKEN = os.getenv("AGENT_INTERNAL_TOKEN", "")
LLM_MAX_CONCURRENT_CALLS = max(1, int(os.getenv("LLM_MAX_CONCURRENT_CALLS", "8")))
LLM_REQUEST_TIMEOUT_SECONDS = max(5, int(os.getenv("LLM_REQUEST_TIMEOUT_SECONDS", "50")))
LLM_CIRCUIT_FAILURE_THRESHOLD = max(2, int(os.getenv("LLM_CIRCUIT_FAILURE_THRESHOLD", "5")))
LLM_CIRCUIT_RESET_SECONDS = max(5, int(os.getenv("LLM_CIRCUIT_RESET_SECONDS", "30")))
LLM_USER_CALLS_PER_MINUTE = max(1, int(os.getenv("LLM_USER_CALLS_PER_MINUTE", "30")))
LLM_MAX_OUTPUT_TOKENS = max(128, int(os.getenv("LLM_MAX_OUTPUT_TOKENS", "1024")))
