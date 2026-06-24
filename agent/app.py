"""
AI Mental Health Assistant - Agent Service
FastAPI application with JSON Lines streaming for internal communication.
"""

import json
import logging
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

import config
from services.chat_service import chat_service
from services.emotion_service import emotion_service
from services.diary_service import diary_service

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="AI Mental Health Agent", version="1.0.0")


class ChatMessage(BaseModel):
    senderType: int
    content: str

class ChatRequest(BaseModel):
    sessionId: str
    messages: List[ChatMessage] = []
    userMessage: str

class EmotionRequest(BaseModel):
    messages: List[ChatMessage]

class DiaryRequest(BaseModel):
    dominantEmotion: str = ""
    moodScore: Optional[int] = None
    emotionTriggers: str = ""
    diaryContent: str = ""
    sleepQuality: Optional[int] = None
    stressLevel: Optional[int] = None


@app.get("/api/agent/health")
async def health():
    return {"status": "ok", "service": "AI Mental Health Agent"}


@app.post("/api/agent/chat")
async def agent_chat(request: ChatRequest):
    """
    Streaming chat using JSON Lines (one JSON object per line).
    Java backend reads line by line and converts to SSE for the frontend.
    Each line: {"code":"200","data":{"content":"..."}}
    Final line: {"code":"200","data":{"content":"","done":true,"fullResponse":"..."}}
    """
    messages_dict = [{"senderType": m.senderType, "content": m.content} for m in request.messages]

    async def generate_lines():
        full_response = ""
        try:
            async for chunk in chat_service.stream_chat(messages_dict, request.userMessage):
                full_response += chunk
                line = json.dumps({
                    "code": "200",
                    "data": {"content": chunk}
                }, ensure_ascii=False)
                yield line + "\n"

            # Send final line with full response for DB saving
            final = json.dumps({
                "code": "200",
                "data": {"content": "", "done": True, "fullResponse": full_response}
            }, ensure_ascii=False)
            yield final + "\n"

        except Exception as e:
            logger.error(f"Chat stream error: {e}")
            error_line = json.dumps({
                "code": "500",
                "data": {"content": f"[AI回复出错: {str(e)}]", "done": True, "fullResponse": ""}
            }, ensure_ascii=False)
            yield error_line + "\n"

    return StreamingResponse(
        generate_lines(),
        media_type="application/x-ndjson",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}
    )


@app.post("/api/agent/emotion/analyze")
async def agent_emotion_analyze(request: EmotionRequest):
    messages_dict = [{"senderType": m.senderType, "content": m.content} for m in request.messages]
    try:
        result = await emotion_service.analyze_emotion(messages_dict)
        return {"code": "200", "data": result}
    except Exception as e:
        logger.error(f"Emotion analysis error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/agent/diary/analyze")
async def agent_diary_analyze(request: DiaryRequest):
    diary_dict = request.model_dump()
    try:
        analysis = await diary_service.analyze_diary(diary_dict)
        return {"code": "200", "data": {"analysis": analysis}}
    except Exception as e:
        logger.error(f"Diary analysis error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app:app", host=config.AGENT_HOST, port=config.AGENT_PORT, reload=True, log_level="info")
