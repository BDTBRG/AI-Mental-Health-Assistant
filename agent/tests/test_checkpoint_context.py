from types import SimpleNamespace

import pytest
from langchain_core.messages import AIMessage, ToolMessage
from langgraph.checkpoint.memory import InMemorySaver

import services.agent_service as agent_service_module
from services.agent_service import AgentChatService, AgentRequestContext
from services.checkpoint_config import build_checkpoint_run_config
from services.session_lifecycle import SessionLifecycleGuard
from tools.mental_health_tools import CURRENT_AGENT_CONTEXT


class CapturingGraph:
    def __init__(self):
        self.state = None
        self.context = None

    async def ainvoke(self, state, _run_config, *, context):
        self.state = state
        self.context = context
        return {**state, "final_text": "测试回复"}


class EmptyCheckpointer:
    async def aget_tuple(self, _run_config):
        return None


class PersonalizationGovernor:
    def __init__(self, private_value):
        self.private_value = private_value
        self.calls = []

    async def invoke(self, _llm, messages, _stage, _request_context, _prompt_version):
        messages = list(messages)
        self.calls.append(messages)
        if any(isinstance(message, ToolMessage) for message in messages):
            assert self.private_value in messages[-1].content
            return AIMessage(content="我会尽量给你简短的支持建议。")
        return AIMessage(content="", tool_calls=[{
            "name": "get_user_memories", "args": {}, "id": "private-call-2"
        }])


class EchoingPersonalizationGovernor(PersonalizationGovernor):
    async def invoke(self, _llm, messages, _stage, _request_context, _prompt_version):
        messages = list(messages)
        self.calls.append(messages)
        if any(isinstance(message, ToolMessage) for message in messages):
            assert self.private_value in messages[-1].content
            return AIMessage(content=f"我记得你提到：{self.private_value}")
        return AIMessage(content="", tool_calls=[{
            "name": "get_user_memories", "args": {}, "id": "private-echo-call"
        }])


@pytest.mark.asyncio
async def test_personal_context_is_passed_as_runtime_context_not_checkpoint_state():
    service = AgentChatService.__new__(AgentChatService)
    graph = CapturingGraph()
    service.graph = graph
    service.checkpointer = EmptyCheckpointer()
    service.session_lifecycle = SessionLifecycleGuard("sqlite")
    private_context = {
        "sessionId": "session-30",
        "turnId": "turn-30",
        "generationToken": "generation-30",
        "userId": "user-9",
        "asOfDate": "2026-10-15",
        "personalizationEnabled": True,
        "emotionHistory": [{"diaryDate": "2026-10-14", "dominantEmotion": "焦虑"}],
        "approvedMemories": [{"content": "我偏好简短建议"}],
    }
    token = CURRENT_AGENT_CONTEXT.set(private_context)
    try:
        chunks = [chunk async for chunk in service.stream_chat([], "我最近怎么样？")]
    finally:
        CURRENT_AGENT_CONTEXT.reset(token)

    assert "".join(chunks) == "测试回复"
    assert "agent_context" not in graph.state
    assert "approvedMemories" not in repr(graph.state)
    assert isinstance(graph.context, AgentRequestContext)
    assert graph.context.values == private_context


@pytest.mark.asyncio
async def test_checkpointer_stores_private_tool_placeholder_not_tool_result(monkeypatch):
    private_value = "我偏好简短建议"
    service = AgentChatService.__new__(AgentChatService)
    service.llm_with_tools = object()
    service.base_llm = object()
    service.configure_checkpointer(InMemorySaver(), SessionLifecycleGuard("sqlite"))
    governor = PersonalizationGovernor(private_value)
    monkeypatch.setattr(agent_service_module, "llm_call_governor", governor)
    monkeypatch.setattr(
        agent_service_module.rag_service,
        "refresh_shared_index_state",
        lambda: {"index_version": "test-index"},
    )
    private_context = {
        "sessionId": "session-31",
        "turnId": "turn-31",
        "generationToken": "generation-31",
        "userId": "user-10",
        "personalizationEnabled": True,
        "approvedMemories": [{"content": private_value, "source": "用户主动添加"}],
    }
    token = CURRENT_AGENT_CONTEXT.set(private_context)
    try:
        chunks = [chunk async for chunk in service.stream_chat([], "我最近怎么样？")]
        saved_checkpoint = await service.checkpointer.aget_tuple(
            build_checkpoint_run_config("session-31")
        )
        retry_chunks = [chunk async for chunk in service.stream_chat([], "我最近怎么样？")]
    finally:
        CURRENT_AGENT_CONTEXT.reset(token)

    reclaimed_context = {
        **private_context,
        "generationToken": "generation-32",
    }
    next_token = CURRENT_AGENT_CONTEXT.set(reclaimed_context)
    try:
        reclaimed_chunks = [chunk async for chunk in service.stream_chat([], "我最近怎么样？")]
    finally:
        CURRENT_AGENT_CONTEXT.reset(next_token)

    checkpoints = [
        checkpoint async for checkpoint in service.checkpointer.alist(
            {"configurable": {"thread_id": "session-31"}}
        )
    ]
    assert checkpoints
    checkpoint = checkpoints[0].checkpoint["channel_values"]

    assert "".join(chunks) == "我会尽量给你简短的支持建议。"
    assert "".join(retry_chunks) == "我会尽量给你简短的支持建议。"
    assert "".join(reclaimed_chunks) == "我会尽量给你简短的支持建议。"
    assert saved_checkpoint is not None
    assert checkpoint["generation_token"] == "generation-32"
    assert saved_checkpoint.checkpoint["channel_values"]["generation_token"] == "generation-31"
    assert len(governor.calls) == 4
    assert private_value in governor.calls[1][-1].content
    assert "agent_context" not in checkpoint
    assert private_value not in repr(checkpoint)
    assert checkpoint["turn_id"] == "turn-31"
    tool_messages = [message for message in checkpoint["messages"]
                     if isinstance(message, ToolMessage)]
    assert len(tool_messages) == 1
    assert private_value not in tool_messages[0].content


@pytest.mark.asyncio
async def test_model_echo_of_personal_tool_result_is_retained_in_checkpoint(monkeypatch):
    private_value = "我偏好简短建议"
    service = AgentChatService.__new__(AgentChatService)
    service.llm_with_tools = object()
    service.base_llm = object()
    service.configure_checkpointer(InMemorySaver(), SessionLifecycleGuard("sqlite"))
    monkeypatch.setattr(
        agent_service_module,
        "llm_call_governor",
        EchoingPersonalizationGovernor(private_value),
    )
    monkeypatch.setattr(
        agent_service_module.rag_service,
        "refresh_shared_index_state",
        lambda: {"index_version": "test-index"},
    )
    private_context = {
        "sessionId": "session-33",
        "turnId": "turn-33",
        "generationToken": "generation-33",
        "userId": "user-11",
        "personalizationEnabled": True,
        "approvedMemories": [{"content": private_value, "source": "用户主动添加"}],
    }
    token = CURRENT_AGENT_CONTEXT.set(private_context)
    try:
        chunks = [chunk async for chunk in service.stream_chat([], "我最近怎么样？")]
        saved_checkpoint = await service.checkpointer.aget_tuple(
            build_checkpoint_run_config("session-33")
        )
    finally:
        CURRENT_AGENT_CONTEXT.reset(token)

    assert "".join(chunks) == f"我记得你提到：{private_value}"
    checkpoint = saved_checkpoint.checkpoint["channel_values"]
    tool_messages = [message for message in checkpoint["messages"]
                     if isinstance(message, ToolMessage)]

    # Personal tool output is not copied directly into its ToolMessage, but a
    # generated response may repeat it and is retained for same-turn replay.
    assert len(tool_messages) == 1
    assert private_value not in tool_messages[0].content
    assert private_value in checkpoint["final_text"]


@pytest.mark.asyncio
async def test_late_old_generation_cannot_replace_new_checkpoint(monkeypatch):
    service = AgentChatService.__new__(AgentChatService)
    service.llm_with_tools = object()
    service.base_llm = object()
    service.configure_checkpointer(InMemorySaver(), SessionLifecycleGuard("sqlite"))
    governor = PersonalizationGovernor("unused-private-memory")
    monkeypatch.setattr(agent_service_module, "llm_call_governor", governor)
    monkeypatch.setattr(
        agent_service_module.rag_service,
        "refresh_shared_index_state",
        lambda: {"index_version": "test-index"},
    )

    new_context = {
        "sessionId": "session-34",
        "turnId": "turn-34",
        "turnSequence": 1204,
        "generationToken": "generation-new",
        "generationEpoch": 2,
        "userId": "user-12",
        "personalizationEnabled": True,
        "approvedMemories": [{
            "content": "unused-private-memory",
            "source": "用户主动添加",
        }],
    }
    token = CURRENT_AGENT_CONTEXT.set(new_context)
    try:
        new_chunks = [chunk async for chunk in service.stream_chat([], "请给我一些建议")]
    finally:
        CURRENT_AGENT_CONTEXT.reset(token)

    stale_contexts = [
        {**new_context, "generationToken": "generation-old", "generationEpoch": 1},
        {
            **{
                key: value
                for key, value in new_context.items()
                if key not in {"generationEpoch", "turnSequence"}
            },
            "generationToken": "generation-legacy",
        },
        {
            **new_context,
            "turnId": "turn-33",
            "turnSequence": 1203,
            "generationEpoch": 99,
        },
    ]
    for stale_context in stale_contexts:
        token = CURRENT_AGENT_CONTEXT.set(stale_context)
        try:
            with pytest.raises(RuntimeError, match="stale generation"):
                [chunk async for chunk in service.stream_chat([], "请给我一些建议")]
        finally:
            CURRENT_AGENT_CONTEXT.reset(token)

    latest_checkpoint = await service.checkpointer.aget_tuple(
        build_checkpoint_run_config("session-34")
    )
    retry_token = CURRENT_AGENT_CONTEXT.set(new_context)
    try:
        new_retry_chunks = [
            chunk async for chunk in service.stream_chat([], "请给我一些建议")
        ]
    finally:
        CURRENT_AGENT_CONTEXT.reset(retry_token)

    saved_state = latest_checkpoint.checkpoint["channel_values"]
    assert "".join(new_chunks) == "我会尽量给你简短的支持建议。"
    assert "".join(new_retry_chunks) == "我会尽量给你简短的支持建议。"
    assert saved_state["generation_epoch"] == 2
    assert saved_state["generation_token"] == "generation-new"
    assert len(governor.calls) == 2


@pytest.mark.parametrize(
    ("tool_name", "tool_args", "request_values", "private_value"),
    [
        (
            "get_user_memories",
            {},
            {
                "personalizationEnabled": True,
                "approvedMemories": [{"content": "我偏好简短建议", "source": "用户主动添加"}],
            },
            "我偏好简短建议",
        ),
        (
            "get_emotion_history",
            {"days": 7},
            {
                "asOfDate": "2026-10-15",
                "emotionHistory": [{
                    "diaryDate": "2026-10-14",
                    "dominantEmotion": "焦虑",
                    "moodScore": 3,
                }],
            },
            "焦虑",
        ),
    ],
)
def test_personal_tools_keep_results_out_of_checkpoint_messages(
    tool_name, tool_args, request_values, private_value
):
    service = AgentChatService.__new__(AgentChatService)
    state = {
        "messages": [AIMessage(content="", tool_calls=[{
            "name": tool_name, "args": tool_args, "id": "private-call-1"
        }])],
        "retrieved_sources": [],
        "knowledge_retrieved": False,
    }
    runtime = SimpleNamespace(context=AgentRequestContext(values=request_values))
    original = CURRENT_AGENT_CONTEXT.get()

    result = service._execute_tools(state, runtime)
    checkpoint_message = result["messages"][-1]
    provider_messages = service._messages_for_model(result["messages"], runtime.context)

    assert isinstance(checkpoint_message, ToolMessage)
    assert private_value not in checkpoint_message.content
    assert private_value in runtime.context.ephemeral_tool_outputs["private-call-1"]
    assert private_value in provider_messages[-1].content
    assert "agent_context" not in state
    assert CURRENT_AGENT_CONTEXT.get() is original


def test_checkpoint_state_schema_has_no_personal_context_field():
    from services.agent_service import AgentState

    assert "agent_context" not in AgentState.__annotations__
