"""
AI Mental Health Assistant - Agent Service
FastAPI application with JSON Lines streaming for internal communication.
RAG: FAISS + Sentence-Transformers with persistent index on disk.
"""

import json
import logging
import os
import hmac
import asyncio
from typing import Any, Dict, List, Optional
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field
from redis import asyncio as redis_async
import config
from services.agent_service import agent_chat_service
from services.checkpoint_config import require_generation_token
from services.checkpointer import open_checkpointer
from services.session_lifecycle import open_session_lifecycle_guard
from services.emotion_service import emotion_service
from services.diary_service import diary_service
from services.rag_service import rag_service
from tools.mental_health_tools import CURRENT_AGENT_CONTEXT

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


# ========== 启动时加载 FAISS 索引 ==========
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load the configured RAG backend and shared graph checkpoint store."""
    app.state.ready = False
    app.state.checkpointer = None
    app.state.session_lifecycle = None
    logger.info("正在加载 %s RAG 索引...", rag_service.backend)
    loaded = rag_service.load()
    if loaded:
        logger.info(
            "RAG 索引加载成功: %s 个向量, %s 篇文章",
            rag_service.total_vectors,
            rag_service.total_articles,
        )
    else:
        logger.info("未找到已有索引，等待知识库数据同步")
    if config.AGENT_CHECKPOINT_BACKEND == "sqlite":
        os.makedirs(os.path.dirname(config.AGENT_CHECKPOINT_DB) or ".", exist_ok=True)
    try:
        async with open_checkpointer() as checkpointer:
            async with open_session_lifecycle_guard(
                config.AGENT_CHECKPOINT_BACKEND,
                config.AGENT_CHECKPOINT_POSTGRES_DSN,
                config.AGENT_CHECKPOINT_LOCK_POOL_SIZE,
            ) as session_lifecycle:
                agent_chat_service.configure_checkpointer(checkpointer, session_lifecycle)
                app.state.checkpointer = checkpointer
                app.state.session_lifecycle = session_lifecycle
                app.state.ready = True
                try:
                    yield
                finally:
                    app.state.ready = False
                    app.state.checkpointer = None
                    app.state.session_lifecycle = None
                    agent_chat_service.clear_checkpointer()
    finally:
        rag_service.close()


app = FastAPI(title="AI Mental Health Agent", version="1.0.0", lifespan=lifespan)
app.state.ready = False
app.state.checkpointer = None


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


@app.get("/api/agent/health/live")
async def liveness():
    return {"status": "ok", "service": "AI Mental Health Agent"}


async def _dependencies_available() -> bool:
    checkpointer = getattr(app.state, "checkpointer", None)
    if checkpointer is None:
        return False

    try:
        if config.RAG_BACKEND == "qdrant":
            qdrant_store = getattr(rag_service, "qdrant_store", None)
            if qdrant_store is None:
                return False
            await asyncio.wait_for(
                asyncio.to_thread(qdrant_store.get_index_metadata), timeout=18
            )
            if not config.RAG_WRITE_LOCK_REDIS_URL.strip():
                return False
            redis_client = redis_async.Redis.from_url(
                config.RAG_WRITE_LOCK_REDIS_URL,
                socket_connect_timeout=2,
                socket_timeout=2,
            )
            try:
                await redis_client.ping()
            finally:
                await redis_client.aclose()

        if config.AGENT_CHECKPOINT_BACKEND == "postgres":
            connection = getattr(checkpointer, "conn", None)
            lock = getattr(checkpointer, "lock", None)
            if connection is None or lock is None:
                return False
            async with lock:
                cursor = await asyncio.wait_for(
                    connection.execute("SELECT 1"), timeout=3
                )
                await asyncio.wait_for(cursor.fetchone(), timeout=3)
            session_lifecycle = getattr(app.state, "session_lifecycle", None)
            if session_lifecycle is None or not await asyncio.wait_for(
                session_lifecycle.check_ready(), timeout=3
            ):
                return False
        return True
    except Exception as error:
        logger.warning(
            "Agent readiness dependency check failed (%s)", type(error).__name__
        )
        return False


@app.get("/api/agent/health/ready")
async def readiness():
    if not app.state.ready or not await _dependencies_available():
        return JSONResponse(
            status_code=503,
            content={"status": "not_ready", "service": "AI Mental Health Agent"},
        )
    return {"status": "ready", "service": "AI Mental Health Agent"}


@app.delete("/api/agent/sessions/{session_id}/checkpoint")
async def delete_session_checkpoint(session_id: str, x_agent_token: str = Header(default="")):
    require_internal_token(x_agent_token)
    await agent_chat_service.purge_session(session_id)
    return {"code": "200", "data": {"deleted": True}}


@app.post("/api/agent/v2/sessions/{session_id}/checkpoint/purge")
async def purge_session_checkpoint_v2(
    session_id: str, _authorized: None = Depends(require_internal_token)
):
    await agent_chat_service.purge_session(session_id, permanently_deleted=False)
    return {"code": "200", "data": {"deleted": True, "permanent": False}}


@app.delete("/api/agent/v2/sessions/{session_id}")
async def delete_session_v2(
    session_id: str, _authorized: None = Depends(require_internal_token)
):
    await agent_chat_service.purge_session(session_id, permanently_deleted=True)
    return {"code": "200", "data": {"deleted": True, "permanent": True}}


def _build_chat_stream_response(request: ChatRequest) -> StreamingResponse:
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


@app.post("/api/agent/chat", include_in_schema=False)
async def agent_chat_legacy(
    request: ChatRequest, _authorized: None = Depends(require_internal_token)
):
    return _build_chat_stream_response(request)


@app.post("/api/agent/v2/chat")
async def agent_chat_v2(
    request: ChatRequest, _authorized: None = Depends(require_internal_token)
):
    try:
        request.context["generationToken"] = require_generation_token(request.context)
    except ValueError as error:
        raise HTTPException(status_code=422, detail="generationToken is required") from error
    return _build_chat_stream_response(request)


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
    """全量重建知识索引。"""
    articles = [a.model_dump() for a in request.articles]
    try:
        total = await asyncio.to_thread(rag_service.rebuild_index, articles)
        stats = await asyncio.to_thread(rag_service.get_stats)
        stats["source_articles"] = len(articles)
        logger.info("RAG 索引重建完成: %s 个向量", total)
        return {"code": "200", "data": stats}
    except Exception as e:
        logger.error(f"RAG 索引重建失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/agent/rag/article/add")
async def rag_article_add(article: RAGArticle, _authorized: None = Depends(require_internal_token)):
    """增量添加单篇文章到索引"""
    try:
        await asyncio.to_thread(
            rag_service.add_article,
            article.id,
            article.title,
            article.content,
        )
        return {"code": "200", "data": await asyncio.to_thread(rag_service.get_stats)}
    except Exception as e:
        logger.error(f"RAG 添加文章失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/api/agent/rag/article/{article_id}")
async def rag_article_remove(article_id: int, _authorized: None = Depends(require_internal_token)):
    """按 ID 删除文章的所有分块"""
    try:
        await asyncio.to_thread(rag_service.remove_article, article_id)
        return {"code": "200", "data": await asyncio.to_thread(rag_service.get_stats)}
    except Exception as e:
        logger.error(f"RAG 删除文章失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.put("/api/agent/rag/article/{article_id}")
async def rag_article_update(article_id: int, article: RAGArticle,
                             _authorized: None = Depends(require_internal_token)):
    """更新单篇文章（先删后加）"""
    try:
        await asyncio.to_thread(
            rag_service.update_article,
            article_id,
            article.title,
            article.content,
        )
        return {"code": "200", "data": await asyncio.to_thread(rag_service.get_stats)}
    except Exception as e:
        logger.error(f"RAG 更新文章失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/agent/rag/stats")
async def rag_stats(_authorized: None = Depends(require_internal_token)):
    """查询索引状态"""
    return {"code": "200", "data": await asyncio.to_thread(rag_service.get_stats)}


@app.post("/api/agent/rag/search")
async def rag_search(query: str, top_k: int = 3, _authorized: None = Depends(require_internal_token)):
    """直接语义检索（调试用，生产由 function calling 调用）"""
    results = await asyncio.to_thread(rag_service.search, query, top_k)
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
