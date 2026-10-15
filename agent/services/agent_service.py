"""Durable, safety-gated LangGraph workflow for one mental-health chat session."""

import logging
import hashlib
import asyncio
from typing import Any, AsyncGenerator, Dict, List, Literal, Tuple, TypedDict

from langchain_openai import ChatOpenAI
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langgraph.graph import END, START, StateGraph

import config
from prompts.chat_prompt import CHAT_SYSTEM_PROMPT
from services.safety_service import safety_service
from services.llm_governance import llm_call_governor
from services.checkpoint_config import build_checkpoint_run_config
from services.rag_service import rag_service
from tools.mental_health_tools import (
    ALL_TOOLS,
    CURRENT_AGENT_CONTEXT,
    CURRENT_RETRIEVAL_HITS,
    CURRENT_RETRIEVED_SOURCES,
)
from services.citation_service import review_source_citations

logger = logging.getLogger(__name__)


class AgentState(TypedDict):
    # Replaced with the authoritative Java history at the start of each turn.
    messages: List[Any]
    latest_user_message: str
    pending_safety_clarification: Literal["risk_intent", "safe_location"] | None
    turn_id: str
    agent_context: Dict[str, Any]
    retrieved_sources: List[Tuple[str, str]]
    knowledge_retrieved: bool
    tool_rounds: int
    blocked: bool
    final_text: str


class AgentChatService:
    """Orchestrates safety, tool decisions, retrieval and output review as graph nodes."""

    MAX_TOOL_CALLS = 3

    def __init__(self):
        self.base_llm = ChatOpenAI(
            model=config.DEEPSEEK_MODEL,
            api_key=config.DEEPSEEK_API_KEY,
            base_url=config.DEEPSEEK_BASE_URL,
            temperature=0.7,
            max_tokens=config.LLM_MAX_OUTPUT_TOKENS,
            timeout=config.LLM_REQUEST_TIMEOUT_SECONDS,
            max_retries=1,
        )
        self.llm_with_tools = self.base_llm.bind_tools(ALL_TOOLS)
        self.graph = None
        self.checkpointer = None

    def configure_checkpointer(self, checkpointer) -> None:
        self.checkpointer = checkpointer
        builder = StateGraph(AgentState)
        builder.add_node("safety_gate", self._safety_gate)
        builder.add_node("agent", self._call_model)
        builder.add_node("tools", self._execute_tools)
        builder.add_node("force_final", self._force_final)
        builder.add_node("review_output", self._review_output)
        builder.add_edge(START, "safety_gate")
        builder.add_conditional_edges(
            "safety_gate",
            self._route_after_safety,
            {"agent": "agent", END: END},
        )
        builder.add_conditional_edges(
            "agent",
            self._route_after_agent,
            {
                "tools": "tools",
                "force_final": "force_final",
                "review_output": "review_output",
            },
        )
        builder.add_edge("tools", "agent")
        builder.add_edge("force_final", "review_output")
        builder.add_edge("review_output", END)
        self.graph = builder.compile(checkpointer=checkpointer)

    def clear_checkpointer(self) -> None:
        self.graph = None
        self.checkpointer = None

    async def purge_session(self, session_id: str) -> None:
        if self.checkpointer is not None:
            await self.checkpointer.adelete_thread(str(session_id))

    def _build_messages(self, history: List[Dict]) -> List[Any]:
        messages: List[Any] = [SystemMessage(content=CHAT_SYSTEM_PROMPT)]
        for item in history:
            sender_type = item.get("senderType", 1)
            content = item.get("content", "")
            if sender_type == 1:
                messages.append(HumanMessage(content=content))
            elif sender_type == 2:
                messages.append(AIMessage(content=content))
        return messages

    @staticmethod
    def _content(message: Any) -> str:
        content = getattr(message, "content", "")
        return content if isinstance(content, str) else str(content)

    def _safety_gate(self, state: AgentState) -> Dict[str, Any]:
        clarification_kind = state.get("pending_safety_clarification")
        if clarification_kind:
            assessment = safety_service.assess_clarification(
                state["latest_user_message"], clarification_kind
            )
        else:
            assessment = safety_service.assess(state["latest_user_message"])
        if assessment.level == "high":
            response = safety_service.high_risk_response()
            return {
                "messages": state["messages"] + [AIMessage(content=response)],
                "blocked": True,
                "final_text": response,
            }
        if assessment.level == "uncertain":
            response = (
                safety_service.high_risk_followup_response()
                if clarification_kind == "safe_location"
                else safety_service.uncertain_response()
            )
            return {
                "messages": state["messages"] + [AIMessage(content=response)],
                "blocked": True,
                "final_text": response,
            }
        return {"blocked": False}

    @staticmethod
    def _route_after_safety(state: AgentState) -> str:
        return END if state.get("blocked") else "agent"

    async def _call_model(self, state: AgentState) -> Dict[str, Any]:
        call_context = dict(state.get("agent_context", {}))
        await asyncio.to_thread(rag_service.refresh_shared_index_state)
        call_context["knowledgeIndexVersion"] = rag_service.index_version
        response = await llm_call_governor.invoke(
            self.llm_with_tools,
            state["messages"],
            "chat-agent",
            call_context,
            hashlib.sha256(CHAT_SYSTEM_PROMPT.encode("utf-8")).hexdigest()[:12],
        )
        tool_calls = getattr(response, "tool_calls", None) or []
        return {
            "messages": state["messages"] + [response],
            "tool_rounds": state.get("tool_rounds", 0) + (1 if tool_calls else 0),
        }

    def _execute_tools(self, state: AgentState) -> Dict[str, Any]:
        response = state["messages"][-1]
        tool_map = {tool.name: tool for tool in ALL_TOOLS}
        messages = list(state["messages"])
        retrieved_sources = list(state.get("retrieved_sources", []))
        retrieval_hits: List[bool] = []
        context_token = CURRENT_AGENT_CONTEXT.set(state["agent_context"])
        sources_token = CURRENT_RETRIEVED_SOURCES.set(retrieved_sources)
        hits_token = CURRENT_RETRIEVAL_HITS.set(retrieval_hits)
        try:
            for call in getattr(response, "tool_calls", None) or []:
                name = call.get("name", "")
                tool = tool_map.get(name)
                if tool is None:
                    result = "无法执行未知工具。"
                else:
                    try:
                        result = str(tool.invoke(call.get("args", {})))
                    except Exception:
                        logger.exception("Agent tool execution failed: %s", name)
                        result = "该工具暂时不可用，请基于已知信息谨慎回答。"
                messages.append(ToolMessage(content=result, tool_call_id=call.get("id", "unknown")))
        finally:
            CURRENT_RETRIEVAL_HITS.reset(hits_token)
            CURRENT_RETRIEVED_SOURCES.reset(sources_token)
            CURRENT_AGENT_CONTEXT.reset(context_token)
        return {
            "messages": messages,
            "retrieved_sources": retrieved_sources,
            "knowledge_retrieved": (
                state.get("knowledge_retrieved", False) or bool(retrieval_hits)
            ),
        }

    async def _force_final(self, state: AgentState) -> Dict[str, Any]:
        forced_messages = state["messages"] + [HumanMessage(
            content="请根据已有信息给出支持性回复，不要再调用工具；依据不足时请说明限制。"
        )]
        response = await llm_call_governor.invoke(
            self.base_llm,
            forced_messages,
            "chat-finalize",
            state.get("agent_context"),
            hashlib.sha256(CHAT_SYSTEM_PROMPT.encode("utf-8")).hexdigest()[:12],
        )
        return {"messages": state["messages"] + [response]}

    def _review_output(self, state: AgentState) -> Dict[str, Any]:
        response = self._content(state["messages"][-1])
        safe_response, blocked = safety_service.review_response(response)
        if blocked:
            logger.warning("Unsafe generated content was replaced by the output safety gate")
            messages = state["messages"][:-1] + [AIMessage(content=safe_response)]
            return {"messages": messages, "final_text": safe_response}

        reviewed_response, rejected = review_source_citations(
            safe_response,
            state.get("retrieved_sources", []),
            require_citation=state.get("knowledge_retrieved", False),
        )
        if rejected:
            logger.warning("Unverified knowledge citations were replaced")
            messages = state["messages"][:-1] + [AIMessage(content=reviewed_response)]
        else:
            messages = state["messages"]
        return {"messages": messages, "final_text": reviewed_response}

    @staticmethod
    def _route_after_agent(state: AgentState) -> str:
        response = state["messages"][-1]
        tool_calls = getattr(response, "tool_calls", None) or []
        if not tool_calls:
            return "review_output"
        if state.get("tool_rounds", 0) >= AgentChatService.MAX_TOOL_CALLS:
            return "force_final"
        return "tools"

    async def stream_chat(
        self, messages: List[Dict], user_message: str
    ) -> AsyncGenerator[str, None]:
        if self.graph is None:
            raise RuntimeError("LangGraph checkpointer has not been initialized")

        context = CURRENT_AGENT_CONTEXT.get() or {}
        session_id = str(context.get("sessionId", "agent-session"))
        turn_id = str(context.get("turnId", "agent-turn"))
        generation_token = context.get("generationToken")
        run_config = build_checkpoint_run_config(session_id, generation_token, turn_id)

        # The SQL stream store is authoritative for completed turns. This checkpoint
        # cache prevents an Agent retry from calling the provider twice for one turn.
        snapshot = await self.graph.aget_state(run_config)
        if (snapshot.values.get("turn_id") == turn_id
                and snapshot.values.get("final_text")):
            if snapshot.values.get("blocked"):
                # Rebuild deterministic safety replies using current deployment
                # resources rather than replaying possibly stale checkpoint text.
                refreshed_safety_state = self._safety_gate(snapshot.values)
                final_text = str(
                    refreshed_safety_state.get("final_text")
                    or safety_service.uncertain_response()
                )
            else:
                cached_text = str(snapshot.values["final_text"])
                safe_cached_text, output_blocked = safety_service.review_response(cached_text)
                if output_blocked:
                    final_text = safe_cached_text
                elif ("retrieved_sources" not in snapshot.values
                      or "knowledge_retrieved" not in snapshot.values):
                    # Older checkpoints do not carry source provenance. Do not replay
                    # an answer whose retrieval basis can no longer be checked.
                    final_text, rejected = review_source_citations(
                        safe_cached_text, [], require_citation=True
                    )
                    if rejected:
                        logger.warning("Cached Agent output had no citation provenance")
                else:
                    final_text, rejected = review_source_citations(
                        safe_cached_text,
                        snapshot.values.get("retrieved_sources", []),
                        require_citation=snapshot.values.get("knowledge_retrieved", False),
                    )
                    if rejected:
                        logger.warning("Unverified cached knowledge citations were replaced")
        else:
            initial = self._build_messages(messages)
            pending_safety_clarification = (
                safety_service.pending_clarification_kind(self._content(initial[-1]))
                if initial and isinstance(initial[-1], AIMessage)
                else None
            )
            initial.append(HumanMessage(content=user_message))
            state: AgentState = {
                "messages": initial,
                "latest_user_message": user_message,
                "pending_safety_clarification": pending_safety_clarification,
                "turn_id": turn_id,
                "agent_context": context,
                "retrieved_sources": [],
                "knowledge_retrieved": False,
                "tool_rounds": 0,
                "blocked": False,
                "final_text": "",
            }
            result = await self.graph.ainvoke(state, run_config)
            final_text = str(result.get("final_text", ""))

        for offset in range(0, len(final_text), 32):
            yield final_text[offset:offset + 32]


agent_chat_service = AgentChatService()
