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
AGENT_CHECKPOINT_BACKEND = os.getenv("AGENT_CHECKPOINT_BACKEND", "sqlite").strip().lower()
if AGENT_CHECKPOINT_BACKEND not in {"sqlite", "postgres"}:
    raise ValueError("AGENT_CHECKPOINT_BACKEND must be 'sqlite' or 'postgres'")
AGENT_CHECKPOINT_DB = os.getenv(
    "AGENT_CHECKPOINT_DB",
    os.path.join(os.path.dirname(__file__), "data", "agent_checkpoints.sqlite"),
)
AGENT_CHECKPOINT_POSTGRES_DSN = os.getenv("AGENT_CHECKPOINT_POSTGRES_DSN", "")
AGENT_CHECKPOINT_LOCK_POOL_SIZE = max(
    1, int(os.getenv("AGENT_CHECKPOINT_LOCK_POOL_SIZE", "16"))
)
AGENT_INTERNAL_TOKEN = os.getenv("AGENT_INTERNAL_TOKEN", "")
RAG_BACKEND = os.getenv("RAG_BACKEND", "faiss").strip().lower()
if RAG_BACKEND not in {"faiss", "qdrant"}:
    raise ValueError("RAG_BACKEND must be 'faiss' or 'qdrant'")
RAG_QDRANT_URL = os.getenv("RAG_QDRANT_URL", "http://localhost:6333").strip()
RAG_QDRANT_API_KEY = os.getenv("RAG_QDRANT_API_KEY", "")
RAG_QDRANT_COLLECTION_ALIAS = os.getenv(
    "RAG_QDRANT_COLLECTION_ALIAS",
    "mental_health_knowledge",
).strip()
RAG_WRITE_LOCK_REDIS_URL = os.getenv(
    "RAG_WRITE_LOCK_REDIS_URL",
    os.getenv("REDIS_URL", ""),
).strip()
RAG_WRITE_LOCK_NAME = os.getenv("RAG_WRITE_LOCK_NAME", "amha:rag:index-write").strip()
RAG_WRITE_LOCK_TTL_SECONDS = max(60, int(os.getenv("RAG_WRITE_LOCK_TTL_SECONDS", "1800")))
RAG_WRITE_LOCK_WAIT_SECONDS = max(1, int(os.getenv("RAG_WRITE_LOCK_WAIT_SECONDS", "60")))
if RAG_BACKEND == "qdrant" and not RAG_WRITE_LOCK_REDIS_URL:
    raise ValueError("RAG_WRITE_LOCK_REDIS_URL is required when RAG_BACKEND=qdrant")
LLM_MAX_CONCURRENT_CALLS = max(1, int(os.getenv("LLM_MAX_CONCURRENT_CALLS", "8")))
LLM_REQUEST_TIMEOUT_SECONDS = max(5, int(os.getenv("LLM_REQUEST_TIMEOUT_SECONDS", "50")))
LLM_CIRCUIT_FAILURE_THRESHOLD = max(2, int(os.getenv("LLM_CIRCUIT_FAILURE_THRESHOLD", "5")))
LLM_CIRCUIT_RESET_SECONDS = max(5, int(os.getenv("LLM_CIRCUIT_RESET_SECONDS", "30")))
LLM_USER_CALLS_PER_MINUTE = max(1, int(os.getenv("LLM_USER_CALLS_PER_MINUTE", "30")))
LLM_MAX_OUTPUT_TOKENS = max(128, int(os.getenv("LLM_MAX_OUTPUT_TOKENS", "1024")))
