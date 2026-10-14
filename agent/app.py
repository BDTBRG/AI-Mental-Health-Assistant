"""
AI Mental Health Assistant - Agent Service
FastAPI application with JSON Lines streaming for internal communication.
RAG: FAISS + Sentence-Transformers with persistent index on disk.
"""

import json
import logging
import os
import hmac
from typing import Any, Dict, List, Optional
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
import config
from services.agent_service import agent_chat_service
from services.checkpointer import open_checkpointer
from services.emotion_service import emotion_service
from services.diary_service import diary_service
from services.rag_service import rag_service
from tools.mental_health_tools import CURRENT_AGENT_CONTEXT

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


# ========== 启动时加载 FAISS 索引 ==========
@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动时尝试从磁盘加载已有索引"""
    logger.info("正在加载 FAISS 索引...")
    loaded = rag_service.load()
    if loaded:
        logger.info(f"FAISS 索引加载成功: {rag_service.total_vectors} 个向量, {rag_service.total_articles} 篇文章")
    else:
        logger.info("未找到已有索引，等待 Spring Boot 推送知识库数据进行重建")
    if config.AGENT_CHECKPOINT_BACKEND == "sqlite":
        os.makedirs(os.path.dirname(config.AGENT_CHECKPOINT_DB) or ".", exist_ok=True)
    async with open_checkpointer() as checkpointer:
        agent_chat_service.configure_checkpointer(checkpointer)
        try:
            yield
        finally:
            agent_chat_service.clear_checkpointer()


app = FastAPI(title="AI Mental Health Agent", version="1.0.0", lifespan=lifespan)


def require_internal_token(x_agent_token: str = Header(default="")) -> None:
    expected_token = config.AGENT_INTERNAL_TOKEN
    if not expected_token:
        raise HTTPException(status_code=503, detail="Agent internal token is not configured")
    if not hmac.compare_digest(x_agent_token, expected_token):
        raise HTTPException(status_code=403, detail="Forbidden")


class ChatMessage(BaseModel):
    senderType: int
    content: str

class ChatRequest(BaseModel):
    sessionId: str
    messages: List[ChatMessage] = Field(default_factory=list)
    userMessage: str
    context: Dict[str, Any] = Field(default_factory=dict)

class EmotionRequest(BaseModel):
    userId: Optional[str] = None
    messages: List[ChatMessage]

class DiaryRequest(BaseModel):
    userId: Optional[str] = None
    dominantEmotion: str = ""
    moodScore: Optional[int] = None
    emotionTriggers: str = ""
    diaryContent: str = ""
    sleepQuality: Optional[int] = None
    stressLevel: Optional[int] = None


# ========== RAG 知识库文章模型 ==========
class RAGArticle(BaseModel):
    id: int
    title: str
    content: str

class RAGRebuildRequest(BaseModel):
    articles: List[RAGArticle]


@app.get("/api/agent/health")
async def health():
    return {"status": "ok", "service": "AI Mental Health Agent"}


@app.delete("/api/agent/sessions/{session_id}/checkpoint")
async def delete_session_checkpoint(session_id: str, x_agent_token: str = Header(default="")):
    require_internal_token(x_agent_token)
    await agent_chat_service.purge_session(session_id)
    return {"code": "200", "data": {"deleted": True}}


@app.post("/api/agent/chat")
async def agent_chat(request: ChatRequest, _authorized: None = Depends(require_internal_token)):
    messages_dict = [{"senderType": m.senderType, "content": m.content} for m in request.messages]

    async def generate_lines():
        full_response = ""
        context_token = CURRENT_AGENT_CONTEXT.set(request.context)
        try:
            async for chunk in agent_chat_service.stream_chat(messages_dict, request.userMessage):
                full_response += chunk
                line = json.dumps({
                    "code": "200",
                    "data": {"content": chunk}
                }, ensure_ascii=False)
                yield line + "\n"

            final = json.dumps({
                "code": "200",
                "data": {"content": "", "done": True, "fullResponse": full_response}
            }, ensure_ascii=False)
            yield final + "\n"

        except Exception as e:
            logger.error(f"Chat stream error: {e}")
            error_line = json.dumps({
                "code": "500",
                "message": "AI回复暂时不可用",
                "data": {"done": True}
            }, ensure_ascii=False)
            yield error_line + "\n"
        finally:
            CURRENT_AGENT_CONTEXT.reset(context_token)

    return StreamingResponse(
        generate_lines(),
        media_type="application/x-ndjson",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}
    )


@app.post("/api/agent/emotion/analyze")
async def agent_emotion_analyze(request: EmotionRequest, _authorized: None = Depends(require_internal_token)):
    messages_dict = [{"senderType": m.senderType, "content": m.content} for m in request.messages]
    try:
        result = await emotion_service.analyze_emotion(
            messages_dict, request_context={"userId": request.userId}
        )
        return {"code": "200", "data": result}
    except Exception as e:
        logger.error(f"Emotion analysis error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/agent/diary/analyze")
async def agent_diary_analyze(request: DiaryRequest, _authorized: None = Depends(require_internal_token)):
    diary_dict = request.model_dump()
    try:
        analysis = await diary_service.analyze_diary(diary_dict)
        return {"code": "200", "data": {"analysis": analysis}}
    except Exception as e:
        logger.error(f"Diary analysis error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ========== RAG 索引管理端点 ==========

@app.post("/api/agent/rag/rebuild")
async def rag_rebuild(request: RAGRebuildRequest, _authorized: None = Depends(require_internal_token)):
    """全量重建 FAISS 索引。Spring Boot 在知识库文章增删改后调用。"""
    articles = [a.model_dump() for a in request.articles]
    try:
        total = rag_service.rebuild_index(articles)
        logger.info(f"RAG 索引重建完成: {total} 个向量")
        return {"code": "200", "data": {"total_vectors": total, "total_articles": len(articles)}}
    except Exception as e:
        logger.error(f"RAG 索引重建失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/agent/rag/article/add")
async def rag_article_add(article: RAGArticle, _authorized: None = Depends(require_internal_token)):
    """增量添加单篇文章到索引"""
    try:
        rag_service.add_article(article.id, article.title, article.content)
        return {"code": "200", "data": rag_service.get_stats()}
    except Exception as e:
        logger.error(f"RAG 添加文章失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/api/agent/rag/article/{article_id}")
async def rag_article_remove(article_id: int, _authorized: None = Depends(require_internal_token)):
    """按 ID 删除文章的所有分块"""
    try:
        rag_service.remove_article(article_id)
        return {"code": "200", "data": rag_service.get_stats()}
    except Exception as e:
        logger.error(f"RAG 删除文章失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.put("/api/agent/rag/article/{article_id}")
async def rag_article_update(article_id: int, article: RAGArticle,
                             _authorized: None = Depends(require_internal_token)):
    """更新单篇文章（先删后加）"""
    try:
        rag_service.update_article(article_id, article.title, article.content)
        return {"code": "200", "data": rag_service.get_stats()}
    except Exception as e:
        logger.error(f"RAG 更新文章失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/agent/rag/stats")
async def rag_stats(_authorized: None = Depends(require_internal_token)):
    """查询索引状态"""
    return {"code": "200", "data": rag_service.get_stats()}


@app.post("/api/agent/rag/search")
async def rag_search(query: str, top_k: int = 3, _authorized: None = Depends(require_internal_token)):
    """直接语义检索（调试用，生产由 function calling 调用）"""
    results = rag_service.search(query, top_k)
    return {"code": "200", "data": results}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app:app",
        host=config.AGENT_HOST,
        port=config.AGENT_PORT,
        reload=config.AGENT_RELOAD,
        log_level="info",
    )
