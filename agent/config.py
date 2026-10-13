import os
from dotenv import load_dotenv

load_dotenv()

# 清除系统代理环境变量，避免 HTTP 请求被指向不存在的本地代理 (127.0.0.1:9)
# 否则所有 langchain_openai / httpx 请求都会走代理导致 Connection error
for key in ('HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy', 'ALL_PROXY', 'all_proxy'):
    os.environ.pop(key, None)

DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")

AGENT_HOST = "0.0.0.0"
AGENT_PORT = 5000
JAVA_BACKEND_URL = "http://localhost:8080"
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
