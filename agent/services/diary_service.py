from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, SystemMessage

import config
import hashlib
from prompts.diary_prompt import DIARY_SYSTEM_PROMPT, DIARY_USER_TEMPLATE
from services.llm_governance import llm_call_governor
from services.llm_config import has_llm_credentials


class DiaryService:
    """Analyzes emotion diary entries using DeepSeek LLM."""

    def __init__(self):
        self.llm = None
        if has_llm_credentials():
            self.llm = ChatOpenAI(
                model=config.DEEPSEEK_MODEL,
                api_key=config.DEEPSEEK_API_KEY.strip(),
                base_url=config.DEEPSEEK_BASE_URL,
                temperature=0.6,
                max_tokens=512,
                timeout=config.LLM_REQUEST_TIMEOUT_SECONDS,
                max_retries=1,
            )

    async def analyze_diary(self, diary_data: dict) -> str:
        """
        Analyze a diary entry and return AI-generated insights.
        Returns a plain text analysis string.
        """
        user_prompt = DIARY_USER_TEMPLATE.format(
            dominant_emotion=diary_data.get("dominantEmotion", "未指定"),
            mood_score=str(diary_data.get("moodScore", 5)),
            emotion_triggers=diary_data.get("emotionTriggers", "未指定"),
            diary_content=diary_data.get("diaryContent", ""),
            sleep_quality=str(diary_data.get("sleepQuality", "未记录")),
            stress_level=str(diary_data.get("stressLevel", "未记录")),
        )

        messages = [
            SystemMessage(content=DIARY_SYSTEM_PROMPT),
            HumanMessage(content=user_prompt),
        ]

        try:
            if self.llm is None and has_llm_credentials():
                self.llm = ChatOpenAI(
                    model=config.DEEPSEEK_MODEL,
                    api_key=config.DEEPSEEK_API_KEY.strip(),
                    base_url=config.DEEPSEEK_BASE_URL,
                    temperature=0.6,
                    max_tokens=512,
                    timeout=config.LLM_REQUEST_TIMEOUT_SECONDS,
                    max_retries=1,
                )
            if self.llm is None:
                return "[AI分析生成失败: 模型服务未配置]"
            response = await llm_call_governor.invoke(
                self.llm,
                messages,
                "diary-analysis",
                request_context={"userId": diary_data.get("userId")},
                prompt_version=hashlib.sha256(DIARY_SYSTEM_PROMPT.encode("utf-8")).hexdigest()[:12],
            )
            return response.content.strip() if response.content else ""
        except Exception:
            return "[AI分析生成失败: 暂时无法连接分析服务]"


# Singleton
diary_service = DiaryService()
