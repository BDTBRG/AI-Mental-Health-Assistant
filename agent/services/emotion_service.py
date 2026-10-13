import json
import logging
import re
import hashlib
from typing import Dict, List

from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, SystemMessage

import config
from prompts.emotion_prompt import EMOTION_SYSTEM_PROMPT, EMOTION_USER_TEMPLATE
from services.llm_governance import llm_call_governor

logger = logging.getLogger(__name__)


class EmotionService:
    """Analyzes conversation emotion using DeepSeek LLM."""

    def __init__(self):
        self.llm = ChatOpenAI(
            model=config.DEEPSEEK_MODEL,
            api_key=config.DEEPSEEK_API_KEY,
            base_url=config.DEEPSEEK_BASE_URL,
            temperature=0.3,
            max_tokens=512,
            timeout=config.LLM_REQUEST_TIMEOUT_SECONDS,
            max_retries=1,
        )

    def _format_conversation(self, messages: List[Dict]) -> str:
        """Format messages into a readable conversation string."""
        lines = []
        for msg in messages:
            sender_type = msg.get("senderType", 1)
            content = msg.get("content", "")
            role = "用户" if sender_type == 1 else "AI助手"
            lines.append(f"{role}: {content}")
        return "\n".join(lines)

    def _parse_json_response(self, text: str) -> Dict:
        """Parse and validate the model's risk result."""
        text = text.strip()
        marker = chr(96) * 3
        if text.startswith(marker):
            text = re.sub(r"^" + marker + r"(?:json)?\s*", "", text)
            text = re.sub(r"\s*" + marker + r"$", "", text)

        try:
            result = json.loads(text)
        except json.JSONDecodeError:
            return self._unknown_analysis("模型返回内容无法解析，暂不能判断风险")

        if not isinstance(result, dict):
            return self._unknown_analysis("模型返回内容格式无效，暂不能判断风险")

        risk_level = result.get("riskLevel")
        if isinstance(risk_level, str) and risk_level.isdigit():
            risk_level = int(risk_level)
        if isinstance(risk_level, bool) or not isinstance(risk_level, int) or risk_level not in range(4):
            return self._unknown_analysis("模型未返回有效风险等级，暂不能判断风险")

        result["riskLevel"] = risk_level
        result["riskStatus"] = "known"
        result["analysisStatus"] = "complete"
        return result

    @staticmethod
    def _unknown_analysis(description: str) -> Dict:
        """Fail closed: unavailable analysis must never look like a normal result."""
        return {
            "primaryEmotion": "暂不可判断",
            "emotionScore": None,
            "riskLevel": None,
            "riskStatus": "unknown",
            "analysisStatus": "unavailable",
            "riskDescription": description,
            "isNegative": None,
            "suggestion": "当前分析不可用；如果你正处于紧急危险中，请联系身边可信任的人或当地紧急服务。",
            "improvementSuggestions": [],
        }

    async def analyze_emotion(self, messages: List[Dict], request_context: Dict | None = None) -> Dict:
        """
        Analyze the emotional state based on conversation messages.
        Returns a dict matching the frontend EmotionAnalysis type.
        """
        conversation = self._format_conversation(messages)
        user_prompt = EMOTION_USER_TEMPLATE.format(conversation=conversation)

        llm_messages = [
            SystemMessage(content=EMOTION_SYSTEM_PROMPT),
            HumanMessage(content=user_prompt),
        ]

        try:
            response = await llm_call_governor.invoke(
                self.llm, llm_messages, "emotion-analysis",
                request_context=request_context,
                prompt_version=hashlib.sha256(EMOTION_SYSTEM_PROMPT.encode("utf-8")).hexdigest()[:12],
            )
            result = self._parse_json_response(response.content)
            return result
        except Exception:
            logger.exception("Emotion analysis provider failed")
            return self._unknown_analysis("情绪分析暂时不可用，暂不能判断风险")


# Singleton
emotion_service = EmotionService()
