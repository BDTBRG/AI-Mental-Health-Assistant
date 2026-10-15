# 会话历史滚动摘要实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为长会话增加可恢复的滚动摘要，在保留最近原文和风险澄清依据的同时限制 Agent 请求大小、模型排队时间和上下文损失风险。

**Architecture:** MySQL 保存每会话摘要和权威 Outbox 状态，Redis Streams 只分发 Outbox ID；Spring Boot 负责 turn 状态、会话权限、上下文窗口和 SSE 错误契约；FastAPI 生成有来源 ID 的结构化摘要，并仅在本轮模型输入中临时注入摘要。摘要不进入 LangGraph checkpoint，原始消息不因摘要删除。

**Tech Stack:** Java 17、Spring Boot 3、MyBatis-Plus、MySQL、Redis Streams、FastAPI、Pydantic v2、LangChain/LangGraph、Vue 3、TypeScript、Vitest、JUnit 5、pytest。

---

## 计划范围与拆分判断

这是一个贯穿 Spring Boot、Agent 和前端的单一功能，不拆成独立产品计划：上下文水位、`WAITING_CONTEXT` 状态、摘要接口和同一 `clientRequestId` 重试共同定义一个端到端契约。阶段按可独立验证的边界拆分，后续阶段依赖前面冻结的字段和错误码。

本计划只覆盖已批准设计中的 CHAT-02。为让设计中的 DEAD 任务“显式重放”具备实际运维入口，计划增加仅管理员可调用的重放接口并记录操作者/时间，不暴露摘要内容。MinIO、Qdrant、账号注销、人工复核扩展、真实部署和真实用户数据运营不属于本批。不要改动 `.env`、`.env.compose`、虚拟环境、SQLite checkpoint、运行数据、上传文件以及主工作区原有未跟踪审计文件；Compose 验收不执行 `down -v`，不删除命名卷。

## 文件映射

### 新建

- `agent/prompts/context_summary_prompt.py`：独立、版本化的摘要系统提示词。
- `agent/services/context_summary_service.py`：Pydantic 摘要 schema、输入边界、LLM 调用和输出校验。
- `agent/services/agent_metrics.py`：Agent 调用队列等待、截止超时和摘要调用时长的低基数 Prometheus 指标。
- `agent/tests/test_context_summary_service.py`：合成对话下的摘要结构、来源 ID、注入和失败测试。
- `agent/tests/test_context_summary_api.py`：内部鉴权、请求校验和响应契约测试。
- `agent/tests/test_llm_governance.py`：排队超时、permit 释放和取消测试。
- `agent/tests/test_agent_metrics.py`：指标名、错误计数和不包含会话/用户标签的测试。
- `agent/tests/fixtures/context_summary_cases.json`：固定的合成摘要回归样例，不含真实用户数据。
- `server/src/main/java/com/amha/entity/ConsultationContextSummary.java`：会话摘要实体。
- `server/src/main/java/com/amha/entity/ConsultationContextSummaryOutbox.java`：每会话一条、可合并目标水位并记录手动重放操作者/时间的 Outbox 实体。
- `server/src/main/java/com/amha/mapper/ConsultationContextSummaryMapper.java`：摘要 CAS 与会话归属查询。
- `server/src/main/java/com/amha/mapper/ConsultationContextSummaryOutboxMapper.java`：任务锁定和就绪任务查询。
- `server/src/main/java/com/amha/service/impl/ContextSummaryOutboxService.java`：事务 Outbox、租约、重试和 CAS 事务。
- `server/src/main/java/com/amha/service/impl/ContextSummaryOutboxWorker.java`：Redis Streams 分发、pending 恢复与 Agent 调用。
- `server/src/main/java/com/amha/controller/ContextSummaryAdminController.java`：仅管理员可触发 DEAD 摘要任务的安全重放，不返回摘要正文。
- `server/src/main/java/com/amha/service/impl/ContextSummaryMetrics.java`：Outbox backlog/最老等待时间、结果计数和上下文预检计数。
- `server/src/main/java/com/amha/service/impl/ChatContextAssembler.java`：按水位读取摘要和有界原文窗口。
- `server/src/main/java/com/amha/dto/ChatContextSnapshot.java`：`ChatContextSnapshot(List<ConsultationMessage> history, Map<String,Object> contextSummary, Long throughMessageId)` Agent 输入快照。
- `server/src/main/java/com/amha/dto/ChatContextPreparation.java`：只读预检结果，区分 READY、SUMMARY_PENDING、SUMMARY_UNAVAILABLE、WINDOW_TOO_LARGE。
- `server/src/main/java/com/amha/dto/ContextSummaryAgentRequest.java`：Java 到 Agent 的内部摘要请求 `ContextSummaryAgentRequest(Long sessionId, Long userId, Map<String,Object> previousSummary, List<ContextSummaryMessage> messages, String promptVersion, String correlationId)`，`ContextSummaryMessage(Long id, Integer senderType, String content)` 作为内部 record。`userId` 只传给调用治理做限额，不写摘要、不进入模型标签或日志。
- `server/src/main/java/com/amha/dto/ContextSummaryAgentResponse.java`：Java 到 Agent 的内部摘要响应 `ContextSummaryAgentResponse(Map<String,Object> summary, Long coveredThroughMessageId, String promptVersion)`。
- `server/src/main/java/com/amha/dto/ChatRetryHint.java`：`ChatRetryHint(String clientRequestId, boolean retryable, long retryAfterMs)` SSE 预检提示。
- `server/src/main/java/com/amha/common/ChatContextPreparationException.java`：带稳定错误码、可重试标记和重试间隔的预检异常。
- `server/src/main/java/com/amha/config/ChatContextSummaryProperties.java`：摘要预检、Worker 与模型调用独立开关及预算绑定。
- `server/src/test/java/com/amha/service/impl/ContextSummaryOutboxServiceTest.java`：任务合并、重试、租约和水位 CAS 单元测试。
- `server/src/test/java/com/amha/service/impl/ChatContextAssemblerTest.java`：消息数量/字符预算和水位隔离测试。
- `server/src/test/java/com/amha/service/impl/ChatTurnStoreTest.java`：WAITING_CONTEXT 转换、幂等和消息唯一写入单元测试。
- `server/src/test/java/com/amha/service/impl/ContextSummaryMetricsTest.java`：指标注册和标签低基数测试。
- `server/src/test/java/com/amha/service/impl/ContextSummaryOutboxWorkerTest.java`：重复投递、失效租约和删除竞态测试。
- `docs/operations/chat-context-summary-runbook.md`：V13 升级、配置、指标、失败检查、任务重放和回滚手册。
- `server/src/main/resources/db/upgrade_v13_chat_context_summary.sql`：V13 增量建表。

### 修改

- `agent/app.py`：Agent 聊天输入预算、独立 `contextSummary` 字段和鉴权摘要接口。
- `agent/config.py`：队列等待、整轮时限、摘要并发与输入长度配置。
- `agent/services/agent_service.py`：模型输入临时注入摘要、整轮截止时间；不写 checkpoint。
- `agent/services/llm_governance.py`：有界获取/释放全局模型并发槽。
- `agent/requirements.txt`：增加 `prometheus-client`，供 Agent `/metrics` 暴露低基数指标。
- `agent/.env.example`：补充可覆盖的调用排队、整轮截止和摘要并发配置。
- `agent/tests/test_checkpoint_context.py`：摘要不进入 checkpoint 的回归断言。
- `server/src/main/java/com/amha/agent/ChatAgent.java`：使用 `streamResponse(Long sessionId, String userMessage, List<ConsultationMessage> history, Map<String,Object> contextSummary, Map<String,Object> context)` 传递分离的摘要上下文。
- `server/src/main/java/com/amha/agent/MockChatAgent.java`：兼容新方法签名。
- `server/src/main/java/com/amha/agent/RealChatAgent.java`：聊天契约与摘要内部 HTTP 调用。
- `server/src/main/java/com/amha/common/GlobalExceptionHandler.java`：仅为新预检异常返回携带 hint 的 `Result`，保留现有异常状态约定。
- `server/src/main/java/com/amha/entity/ConsultationChatTurn.java`：使用 `WAITING_CONTEXT`、`CONTEXT_REJECTED` 状态，不增加不必要的消息正文列。
- `server/src/main/java/com/amha/mapper/ConsultationChatTurnMapper.java`：查找已有请求和阻止新 turn 越序。
- `server/src/main/java/com/amha/mapper/ConsultationMessageMapper.java`：有界的水位后/水位前消息查询。
- `server/src/main/java/com/amha/service/impl/ChatTurnStore.java`：事务性挂起、同 ID 恢复和完成 turn 时入队。
- `server/src/main/java/com/amha/service/impl/ChatServiceImpl.java`：会话所有权、上下文预检、回放、Agent 输入和删除传播。
- `server/src/main/java/com/amha/controller/ChatController.java`：只在必要时将新预检异常映射为非 SSE 响应；保持已有接口 envelope。
- `server/src/main/resources/db/init.sql`：使全新安装包含 V13 新表定义。
- `server/src/main/resources/application-example.yml`：列出默认关闭的摘要开关、Worker、预算和重试配置示例。
- `server/src/test/java/com/amha/ChatControllerTest.java`：端到端数据库/错误 envelope/归属与幂等用例。
- `server/src/test/java/com/amha/agent/RealChatAgentContractTest.java`：聊天摘要字段与内部摘要请求契约。
- `src/views/user/consultation.vue`：识别非 SSE 的上下文预检响应、保留请求 ID 并展示手动恢复状态。
- `src/views/user/consultation.spec.ts`：pending、terminal、刷新恢复与同 ID 重试测试。

## Task 1: Agent 摘要 schema 与生成服务

**目标文件：** `agent/services/context_summary_service.py`、`agent/prompts/context_summary_prompt.py`、`agent/tests/test_context_summary_service.py`。

摘要结构使用 Pydantic v2，所有模型设 `extra="forbid"`。每个条目是 `{text: str, sourceMessageIds: list[int]}`；每字段最多 8 项、每项最多 240 字符、每项来源最多 5 个 ID、全部文本最多 1,200 字符。输入最多 40 条消息且总内容最多 16,000 字符。来源 ID 必须存在于同会话的当前摘要或本批消息中；服务只做来源归属/水位校验，不把引用存在等同于语义蕴含证明。

- [x] **Step 1: 先写失败测试。** 在 `agent/tests/test_context_summary_service.py` 添加以下测试，服务 API 固定为 `ContextSummaryService(model).summarize(previous_summary, messages, user_id)`；`messages` 为 `{id, senderType, content}` 列表。

```python
import pytest
from pydantic import ValidationError
from services.context_summary_service import ContextSummary, ContextSummaryService


class FakeModel:
    def __init__(self, content):
        self.content = content
        self.calls = []

    async def ainvoke(self, messages):
        self.calls.append(messages)
        return type("Response", (), {"content": self.content})()


@pytest.mark.asyncio
async def test_summary_accepts_only_source_ids_from_same_batch():
    model = FakeModel(
        '{"userStatedContext":[{"text":"用户近期工作压力较大",'
        '"sourceMessageIds":[12]}],"userPreferences":[],"openTopics":[]}'
    )
    service = ContextSummaryService(model)

    result = await service.summarize(
        None,
        [{"id": 12, "senderType": 1, "content": "最近工作压力很大"}],
        user_id="7",
    )

    assert result.user_stated_context[0].text == "用户近期工作压力较大"
    assert result.user_stated_context[0].source_message_ids == [12]
    assert len(model.calls) == 1


@pytest.mark.asyncio
async def test_summary_rejects_unknown_source_and_extra_diagnostic_fields():
    model = FakeModel(
        '{"userStatedContext":[{"text":"用户患有抑郁症",'
        '"sourceMessageIds":[999]}],"userPreferences":[],"openTopics":[],'
        '"riskLevel":3}'
    )
    service = ContextSummaryService(model)

    with pytest.raises((ValueError, ValidationError)):
        await service.summarize(
            None,
            [{"id": 12, "senderType": 1, "content": "最近工作压力很大"}],
            user_id="7",
        )
```

- [x] **Step 2: 确认测试失败。** 在 `agent/` 执行 `python -m pytest tests/test_context_summary_service.py -q`；预期因新服务模块尚不存在而失败。
- [x] **Step 3: 实现最小服务与提示词。** 新建三个严格 schema 字段及来源 ID 校验；摘要内部请求固定为 `{sessionId, previousSummary, messages, promptVersion, correlationId}`，输入消息项为 `{id, senderType, content}`。新增独立摘要提示词，明确消息内容是不可信数据、只能压缩明示信息、无工具/RAG/checkpoint。模型响应只接受 JSON；解析、schema、长度、未知来源 ID 任一失败都抛可分类异常，不回退空摘要。既有摘要里的 source ID 可延续，输出 source ID 只能来自既有摘要或本批次。
- [x] **Step 4: 通过服务测试并补边界测试。** 运行同一 pytest 命令；再补测空摘要、已有摘要来源 ID 可被保留、单字段 9 项、文本总长度超限、输入 41 条/16,001 字符、模型超时和非法 JSON，确认每一项都拒绝推进结果。
- [x] **Step 5: 提交本阶段。** `git add agent/services/context_summary_service.py agent/prompts/context_summary_prompt.py agent/tests/test_context_summary_service.py`，检查 `git diff --cached --check` 后提交 `feat(agent): add bounded context summarizer`，再快进合入本地 `master`。

## Task 2: Agent 内部接口、预算和调用治理

**目标文件：** `agent/app.py`、`agent/config.py`、`agent/services/agent_service.py`、`agent/services/llm_governance.py`、`agent/tests/test_context_summary_api.py`、`agent/tests/test_llm_governance.py`、`agent/tests/test_checkpoint_context.py`、`agent/.env.example`。

- [x] **Step 1: 写 API 契约测试。** 新建 `agent/tests/test_context_summary_api.py`，用 `fastapi.testclient.TestClient` 验证无 `X-Agent-Token` 返回 403、有效 token 返回固定 JSON schema（`summary`、`coveredThroughMessageId`、`promptVersion`），来源 ID 既不属于本批消息也不属于输入摘要时返回 422/服务错误，且摘要端点不访问 `agent_chat_service.checkpointer`。该 Agent 内部 API 返回 Pydantic JSON 对象，不包 Spring 的 `Result`；模型用 fake，不连接 DeepSeek。
- [x] **Step 2: 验证测试失败。** `cd agent; python -m pytest tests/test_context_summary_api.py -q`；预期新路由未注册而失败。
- [x] **Step 3: 实现摘要 API 和请求 schema。** 在 `app.py` 导入 Task 1 的 request/response schema 和服务，新增受 `require_internal_token` 保护的 `POST /api/agent/v2/context-summary`。请求固定字段 `sessionId, userId, previousSummary, messages, promptVersion, correlationId`，最多 40 条、16,000 字符，响应只返回 summary、实际覆盖水位和 prompt 版本；`userId` 仅传给 `LLMCallGovernor` 执行用户额度，不写到摘要或模型标签/日志。聊天 `ChatRequest` 增加独立可选 `contextSummary`；Pydantic 校验最多 39 条历史 + 本轮 1 条、合计原文、当前用户消息与序列化摘要最多 32,000 字符、单条 history/userMessage 最多 8,000 字符；在 FastAPI 请求层同时限制原始 JSON body 为 64 KiB，任何超限都拒绝而不静默截断。
- [x] **Step 4: 写 semaphore 等待失败测试。** 在 `agent/tests/test_llm_governance.py` 用 `monkeypatch` 将 `LLM_QUEUE_WAIT_TIMEOUT_SECONDS` 设为 `0.01`，将 governor semaphore 置为 0 个 permit，并断言 `invoke()` 抛 `LLMQueueTimeout`；另一测试让 FakeLLM 抛错后确认 permit 可供下一次调用获取。

```python
import asyncio
import pytest
from services.llm_governance import LLMCallGovernor, LLMQueueTimeout


class FakeLLM:
    async def ainvoke(self, _messages):
        return type("Response", (), {"content": "ok"})()


@pytest.mark.asyncio
async def test_invoke_fails_when_queue_wait_budget_expires(monkeypatch):
    monkeypatch.setattr("config.LLM_QUEUE_WAIT_TIMEOUT_SECONDS", 0.01)
    governor = LLMCallGovernor()
    governor._slots = asyncio.Semaphore(0)

    with pytest.raises(LLMQueueTimeout):
        await governor.invoke(FakeLLM(), [], "chat-agent")
```
- [x] **Step 5: 实现有界 semaphore 和配置。** `LLMCallGovernor.invoke` 用带 timeout 的 acquire/finally release 代替无界 `async with semaphore`；新增环境配置默认 `LLM_QUEUE_WAIT_TIMEOUT_SECONDS=5`、`AGENT_TURN_TIMEOUT_SECONDS=240`、摘要并发上限 1。获取 permit 的流程按以下结构实现，保证 acquire 超时不释放未持有的 permit，模型成功、异常或取消都在 `finally` 释放：

```python
try:
    await asyncio.wait_for(
        self._slots.acquire(),
        timeout=config.LLM_QUEUE_WAIT_TIMEOUT_SECONDS,
    )
except asyncio.TimeoutError as exc:
    raise LLMQueueTimeout() from exc
try:
    return await asyncio.wait_for(
        llm.ainvoke(messages),
        timeout=config.LLM_REQUEST_TIMEOUT_SECONDS,
    )
finally:
    self._slots.release()
```

摘要 stage 使用独立低并发预算并仍受全局最大并发约束；排队超时、模型超时和熔断使用不同错误类型。
- [x] **Step 6: 写摘要不进入 checkpoint 的失败测试。** 在 `agent/tests/test_checkpoint_context.py` 加用例：构造 `AgentRequestContext(values={"contextSummary": ...})`，断言传给 FakeGovernor 的模型消息包含明确标记的摘要，图 state `messages`、保存给 checkpointer 的 checkpoint input 和下一次 raw history 均不含摘要正文。
- [x] **Step 7: 实现 ephemeral 模型输入与整轮 deadline。** 修改 `AgentChatService.stream_chat`/`_resolve_final_text`，摘要只在 `_call_model` 和 `_force_final` 的模型请求消息中临时注入；安全 gate 继续只用本轮用户消息和最近 raw history。用 `asyncio.wait_for` 包住完整图调用，超时取消本轮，不向 checkpoint 写入成功的部分输出。
- [x] **Step 8: 验证 Agent 回归。** 在 `agent/` 执行 `python -m pytest tests/test_context_summary_service.py tests/test_context_summary_api.py tests/test_llm_governance.py tests/test_checkpoint_context.py tests/test_agent_safety_gate.py -q`；预期全部通过，且没有真实模型网络调用。另以配置信号验证 Agent turn 截止 240 秒小于 Spring Agent 响应截止 270 秒，后者小于现有 SSE 300 秒；摘要模型排队 5 秒 + 单次调用 50 秒小于摘要 HTTP 截止 65 秒。
- [x] **Step 9: 提交本阶段。** 暂存 Task 2 列出的 Agent 文件，提交 `feat(agent): bound chat context and model waits` 并快进合入本地 `master`。

## Task 3: V13 持久化表与摘要 Outbox 服务

**目标文件：** V13 SQL、`init.sql`、两个实体、两个 Mapper、Outbox service、管理员重放 Controller、指标组件及 `ContextSummaryOutboxServiceTest`、`ContextSummaryMetricsTest`、`ChatControllerTest`。

摘要表以 `session_id` 为主键；字段至少包括 `user_id`、JSON `summary_json`、`covered_through_message_id`、`summary_version`、`prompt_version`、`updated_at`。Outbox 表以自增 `id` 为主键并对 `session_id` 建唯一键，每会话复用一条任务行合并 `desired_through_message_id`；任务字段包括 `user_id`、`status`、`attempt_count`、`next_attempt_at`、lease、`last_error_code`、`created_at`、`updated_at`。所有领取操作采用现有 `SELECT ... FOR UPDATE SKIP LOCKED` 模式，业务请求和 Worker 更新同一会话时先锁 `consultation_session` 行，锁顺序一致。Worker 领取时将 `desiredThroughMessageId` 和预期摘要版本复制到内存 claim 快照；新回合增大持久化目标时，旧 claim 只能完成旧水位并将任务重新置为 PENDING，不新增重复保存的 processing 水位列。Redis Stream payload 只放 `{outboxId}`，处理 lease/token 由数据库 claim 生成并用于 CAS 栅栏，不放会话内容、用户 ID 或摘要正文。

- [x] **Step 1: 写持久化、指标与管理员权限失败测试。** 新建 `ContextSummaryOutboxServiceTest`，Mockito 测试 `enqueue(sessionId,userId,target)` 两次后只有一条会话任务、`desiredThroughMessageId` 单调增加、claim 快照固定本次目标；`loadRequest(claim,40,16000)` 不读取水位外消息且遵守数量/字符限制；模拟 processing lease 过期后只有当前 `leaseToken` 能完成。新建 `ContextSummaryMetricsTest` 验证 pending/dead gauge 与低基数 counter 名称、注册后没有用户/会话标签。在 `ChatControllerTest` 增加管理员可重放 DEAD、普通用户收到拒绝结果且不会读取摘要内容的 RED 用例。Outbox service 公共方法签名固定为 `void enqueue(Long sessionId, Long userId, Long targetThroughMessageId)`、`Long claimNextForDispatch()`（无可派发项返回 `null`）、`ContextSummaryClaim claimForProcessing(Long outboxId)`、`ContextSummaryAgentRequest loadRequest(ContextSummaryClaim claim, int maxMessages, int maxChars)`、`void complete(ContextSummaryClaim claim, ContextSummaryAgentResponse response)`、`void fail(ContextSummaryClaim claim, String errorCode)`、`boolean retryDead(Long sessionId, Long actorUserId)`、`void deleteForSession(Long sessionId)`；`ContextSummaryClaim(Long outboxId, Long sessionId, Long userId, Long targetThroughMessageId, Long expectedSummaryVersion, String processingLeaseToken)` 是不可变快照。

Outbox 增加 `manual_retry_count`、`last_manual_retry_by`、`last_manual_retry_at` 三个可空/默认字段，使 DEAD 人工重放具备可审计记录；只有管理员接口能写入这些字段，runbook 禁止直接手改状态 SQL。
- [x] **Step 2: 确认测试失败。** `mvn -f server/pom.xml -Dtest=ContextSummaryOutboxServiceTest,ChatControllerTest test`；预期因新 service 与 `/api/admin/context-summary/{sessionId}/retry` 路由不存在而失败。
- [x] **Step 3: 增加 V13 与 fresh-install schema。** 新 migration 创建 `consultation_context_summary` 和 `consultation_context_summary_outbox`，使用上方定义的列、主键/唯一键/索引；Outbox 状态只使用 `PENDING`、`QUEUED`、`PROCESSING`、`DEAD`，摘要任务失败使用 `attempt_count` 与 `next_attempt_at` 退避。把相同 CREATE TABLE 结构加到 `server/src/main/resources/db/init.sql`，migration 注释写明接续 V12，不作 checkpoint 或历史消息修改。

两张表的 DDL 按以下结构实现，migration 与 `init.sql` 保持列、类型、默认值和索引一致：

```sql
CREATE TABLE consultation_context_summary (
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  summary_json JSON NOT NULL,
  covered_through_message_id BIGINT NOT NULL,
  summary_version BIGINT NOT NULL,
  prompt_version VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (session_id),
  KEY idx_context_summary_user (user_id, session_id)
);

CREATE TABLE consultation_context_summary_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  desired_through_message_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lease_token CHAR(36) DEFAULT NULL,
  lease_until DATETIME DEFAULT NULL,
  last_error_code VARCHAR(64) DEFAULT NULL,
  manual_retry_count INT NOT NULL DEFAULT 0,
  last_manual_retry_by BIGINT DEFAULT NULL,
  last_manual_retry_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_context_summary_outbox_session (session_id),
  KEY idx_context_summary_outbox_ready (status, next_attempt_at, lease_until),
  KEY idx_context_summary_outbox_user (user_id, session_id)
);
```

- [x] **Step 4: 添加实体与 Mapper。** Entity 字段以 MyBatis-Plus camelCase 映射；Mapper 添加摘要 `SELECT ... WHERE session_id=? AND user_id=?`、会话行锁、按水位读取最多 N 条消息、摘要 CAS 更新和 Outbox ready/lease 查询。所有 select 使用参数绑定，禁止拼接用户可控 SQL。
- [x] **Step 5: 实现事务 Outbox 与受限重放入口。** `enqueue` 在 session row lock 内校验 session owner 并合并水位；Dispatcher 发布仅写 `{outboxId}` 的 Redis record，数据库保留 dispatch lease；消费端按 ID 加锁并 claim，处理快照含目标水位、预期摘要版本和新 processing lease token；最多 8 次退避重试（初始 5 秒指数退避、上限 900 秒），耗尽后标为 `DEAD`。新建 `ContextSummaryAdminController`，映射 `POST /api/admin/context-summary/{sessionId}/retry`，从 `HttpServletRequest.userType` 限定 `2` 管理员；其调用 `retryDead(sessionId, actorUserId)`，事务内只允许 DEAD 改回 PENDING，并原子清零 `attempt_count`、清除旧错误、设置立即可运行时间、递增 `manual_retry_count`、写 `last_manual_retry_by/at`；非 DEAD 返回未执行，不记录二次重放。响应不含摘要正文。水位/版本 CAS 冲突时不得覆盖新摘要，重读并重算；删除会话时同事务删 summary/outbox。
- [x] **Step 6: 实现 Spring 指标。** `ContextSummaryMetrics` 注入已有 `MeterRegistry`，注册 `amha.chat.context_summary.outbox.pending`、`.dead`、`.oldest_wait_seconds` gauges，以及摘要 `completed`/`failed`、摘要 duration/输入输出长度 histogram、上下文 `context_pending`/`context_unavailable`/`context_too_large` counters。指标禁止添加 userId、sessionId、原文和摘要标签。
- [x] **Step 7: 验证服务与 SQL 变更。** 运行 Task 3 单测；`git diff --check`；检查 V13 文件没有 `DROP`、没有读写 Agent checkpoint 文件路径、没有 DELETE consultation_message。SQL 在隔离测试库验证后记录结果，禁止在本轮对个人本地运行库执行迁移。
- [x] **Step 8: 提交本阶段。** 暂存 V13、init.sql、entity、mapper、service、metrics、tests，提交 `feat(server): persist context summary jobs` 并快进合入本地 `master`。

## Task 4: Agent HTTP 客户端和 Redis Streams Worker

**目标文件：** `RealChatAgent.java`、`RealChatAgentContractTest.java`、`ContextSummaryOutboxWorker.java`、`ContextSummaryOutboxWorkerTest.java`、`application-example.yml`、`agent/requirements.txt`、`agent/services/agent_metrics.py`、`agent/tests/test_agent_metrics.py`。

- [x] **Step 1: 写 Java-Agent 契约测试。** 扩展 `RealChatAgentContractTest` 的本地 `HttpServer` fake：断言 `/api/agent/v2/context-summary` 路径、`X-Agent-Token`、消息批次和 prompt 版本；响应是 Agent 的固定 Pydantic JSON 对象而非 Spring `Result`；Agent 返回缺少来源字段或水位不匹配时客户端拒绝结果。
- [x] **Step 2: 确认测试失败。** `mvn -f server/pom.xml -Dtest=RealChatAgentContractTest test`；预期未实现摘要客户端方法时编译失败。
- [x] **Step 3: 实现摘要调用 DTO/客户端。** `RealChatAgent.summarizeContext(ContextSummaryAgentRequest)` 对请求和响应设独立 HTTP 总截止 65 秒，直接解析固定响应 DTO `ContextSummaryAgentResponse(summary, coveredThroughMessageId, promptVersion)`，校验 summary 内所有 source ID 属于 request 消息或 previous summary，且响应水位等于 request 实际批次最后一条消息 ID；聊天 Agent HTTP 总截止配置为 270 秒，必须长于 Agent 的 240 秒 turn deadline 且短于现有 300 秒 SSE 生命周期；绝不记录请求体；新增 DTO 与契约测试响应解析。
- [x] **Step 4: 写 Worker 分发恢复测试。** 使用 Mockito 验证 Worker 接收 `{outboxId}` 后先调用 `claimForProcessing(outboxId)`，空/过期 claim 直接 ACK；Agent 异常时调用 `fail(claim,errorCode)`，DB 退避状态写入成功后 ACK 当前 Stream record，由 Dispatcher 按数据库 `next_attempt_at` 再次发布；若 `fail` 本身未能写入，则不 ACK，让 pending reclaimer 恢复；删除后的 task 不会再请求 Agent。
- [x] **Step 5: 实现 Stream Worker。** 复用 `KnowledgeIndexOutboxWorker` 的 consumer group、`OutboxPendingReclaimer`、定期发布、pending replay、stale reclaim 和 `@PreDestroy` 停止；Stream 只传 outbox ID。单批从服务读最多 40 条/16,000 字符，Agent 调用在数据库事务外；Worker processing lease 初始 90 秒，长于 65 秒摘要 HTTP 总截止并留 25 秒结果提交窗口。完成后用 claim 中的目标水位、预期版本及 lease token CAS 写 summary/watermark；若目标水位增长则重新置 PENDING。

Worker 的 ACK 边界按以下控制流实现，异常日志只记录 outbox ID 与分类错误码，不输出请求或摘要正文：

```java
ContextSummaryClaim claim = outboxService.claimForProcessing(outboxId);
if (claim == null) {
    acknowledge(record);
    return;
}
try {
    ContextSummaryAgentRequest request = outboxService.loadRequest(claim, 40, 16_000);
    ContextSummaryAgentResponse result = agent.summarizeContext(request);
    outboxService.complete(claim, result);
    acknowledge(record);
} catch (Exception error) {
    try {
        outboxService.fail(claim, classify(error));
        acknowledge(record); // MySQL owns the retry schedule.
    } catch (Exception persistenceError) {
        // Keep this Redis entry pending; the pending reclaimer retries it.
    }
}
```

- [x] **Step 6: 测试并实现 Agent 指标。** 先新建 `agent/tests/test_agent_metrics.py`，断言 queue-wait、turn-deadline、summary-duration 指标会出现在 exposition 且不包含 user/session 标签；先运行 `cd agent; python -m pytest tests/test_agent_metrics.py -q` 确认因模块/路由不存在而失败。再在 `agent/services/agent_metrics.py` 用 `prometheus-client` 注册低基数 histogram/counter，在 `app.py` 挂载 `/metrics`；运行手册要求该路由只暴露给内部监控网络。
- [x] **Step 7: 添加配置示例并验证。** `application-example.yml` 增加 `amha.chat.context-summary.preflight-enabled=false`、`worker-enabled=false`、`agent-enabled=false`、stream key、批次与 lease 配置；`agent/requirements.txt` 增加 `prometheus-client>=0.20.0,<1.0.0`；Agent 配置增加 queue/turn/summary 并发阈值。使用当前可用的测试 Python 环境运行 `cd agent; python -m pytest tests/test_agent_metrics.py -q`；若缺少依赖，只能在隔离临时环境安装，不改已有虚拟环境。后端运行 `mvn -f server/pom.xml -Dtest=RealChatAgentContractTest,ContextSummaryOutboxServiceTest,ContextSummaryOutboxWorkerTest test`。
- [x] **Step 8: 提交本阶段。** 提交 `feat(server): process context summary outbox tasks` 并快进合入本地 `master`。

## Task 5: Java 上下文组装与 turn 恢复状态机

**目标文件：** `ChatContextAssembler.java`、`ChatContextSnapshot.java`、`ChatTurnStore.java`、`ChatServiceImpl.java`、相关 Mapper/exception/controller 和集成测试。

- [ ] **Step 1: 写上下文组装单测。** 新建 `ChatContextAssemblerTest`，覆盖 summary watermark 后按 ID 升序返回、不读取其他 session 的记录、最近 12 条之外可入 summary target、39 条 raw history + 当前输入边界、总字符 32,000、单条 8,000、最近 12 条自身超限分别得 pending/too-large。测试使用 `ConsultationContextSummaryMapper` 和 `ConsultationMessageMapper` mocks。
- [ ] **Step 2: 确认测试失败。** `mvn -f server/pom.xml -Dtest=ChatContextAssemblerTest test`；预期缺少 assembler。
- [ ] **Step 3: 实现有界 Mapper 和只读 assembler。** 通过 LIMIT 查询，不再将会话全部消息载入 JVM；查询必须绑定 `sessionId + userId`，校验摘要归属和水位，只负责计算返回 `ChatContextPreparation`，不得写 Outbox、turn 或消息。该结果固定含 `status ∈ {READY, SUMMARY_PENDING, SUMMARY_UNAVAILABLE, WINDOW_TOO_LARGE}`、有界原文 `history`、独立 `contextSummary`、`summaryTargetThroughMessageId` 和 `retryAfterMs`；`READY` 时转为 `ChatContextSnapshot(history, contextSummary, throughMessageId)`。`summaryTargetThroughMessageId` 是最近 12 条已持久化消息之前的最大 ID；如果这之前没有消息，不创建空摘要任务。字符数按 Unicode code point 统计；Agent 原始 JSON body 则按 UTF-8 byte 限制。
- [ ] **Step 4: 写 turn 状态单测。** 新增/扩展 `ChatTurnStore` 测试：首次超限通过 `waitForContext(sessionId,userId,requestId,hash,message,targetThroughId)` 一次性创建 `WAITING_CONTEXT` turn、用户消息、会话 message count 和 Outbox 任务；最近窗口硬超限通过 `rejectContextTurn(sessionId,userId,requestId,hash,message)` 原子保存 `CONTEXT_REJECTED` turn 和用户消息；相同 request ID/hash 重试不重复写入；不同 hash 冲突；上下文准备好后同一 turn 转 RUNNING；未整理的 turn 阻止新 ID 越序；`completeTurn` 在同一事务写 AI 消息、完成 turn 并合并下一摘要水位。
- [ ] **Step 5: 实现 ChatTurnStore 状态机。** `ConsultationChatTurnMapper.selectActiveBySessionForUpdate` 把 `WAITING_CONTEXT` 视为活跃排队 turn；复用现有 `beginTurn(Long sessionId, Long userId, String requestId, String requestHash, String userMessage)`，但只允许它在 READY 预检后创建 RUNNING、新增用户消息，或把相同 hash 的 `WAITING_CONTEXT` 原子转为 RUNNING；增加 `waitForContext(Long sessionId, Long userId, String requestId, String requestHash, String userMessage, Long targetThroughMessageId)` 和 `rejectContextTurn(Long sessionId, Long userId, String requestId, String requestHash, String userMessage)`；`CONTEXT_REJECTED` 终态不再重试，`WAITING_CONTEXT` 保留相同 hash/request ID；兼容现有 `PENDING` 首轮 turn 和 generation epoch/token 栅栏。
- [ ] **Step 6: 集成 streamChat 与新错误 envelope。** `ChatServiceImpl` 先验证 session owner，再检查相同 request ID 的现存 turn；RUNNING/COMPLETED 直接 attach/replay，不被摘要预检阻断。新请求或 `WAITING_CONTEXT` 请求按以下分支处理；`ChatTurnStore` 的每个写操作是独立 `@Transactional` 公开方法，返回提交后 `ChatServiceImpl` 才抛非 SSE 异常，避免回滚已保存的用户消息和 Outbox：

```java
switch (preparation.status()) {
    case READY -> turnStart = turnStore.beginTurn(
            sessionId, userId, requestId, requestHash, userMessage);
    case SUMMARY_PENDING, SUMMARY_UNAVAILABLE -> {
        turnStore.waitForContext(sessionId, userId, requestId, requestHash,
                userMessage, preparation.summaryTargetThroughMessageId());
        throw preparation.toException(requestId);
    }
    case WINDOW_TOO_LARGE -> {
        turnStore.rejectContextTurn(sessionId, userId, requestId, requestHash, userMessage);
        throw preparation.toException(requestId);
    }
}
```

`GlobalExceptionHandler` 仅为 `ChatContextPreparationException` 返回携带 hint 的现有 `Result` envelope：`new Result<>(errorCode, message, new ChatRetryHint(clientRequestId, retryable, retryAfterMs))`，状态保持 HTTP 200，日志只记错误码。`ChatRetryHint` 同时包含 `clientRequestId`，因此服务端为无 ID 的兼容请求生成的 ID 可在错误响应中找回。

`SUMMARY_PENDING` 返回 `CONTEXT_SUMMARY_PENDING`；服务/模型不可用或摘要功能关闭时用 `CONTEXT_SUMMARY_UNAVAILABLE`，保持原文和同一 request ID 可恢复、不提交长上下文给 Agent；最近窗口超限为终态 `CONTEXT_WINDOW_TOO_LARGE`，且原子保存该轮和用户消息以防重复请求。READY 后向 `ChatAgent.streamResponse` 传 raw history 与单独的 `userMessage`；如果正在恢复 WAITING_CONTEXT，该轮已存储的用户消息必须从 raw history 中按其最新 ID 排除，不能重复注入。`completeTurn` 才触发异步整理；`deleteSession` 删除 summary/outbox 并阻止晚到 Worker 复活数据。
- [ ] **Step 7: 写集成回归。** 在 `ChatControllerTest` 加 MockMvc 测试：当前 `/stream` Controller 声明 `produces=text/event-stream`，所以必须实际断言预检异常仍被 `GlobalExceptionHandler` 编码为 `application/json` HTTP 200 `Result`，不可只验证 service 异常对象。pending 的 `code=CONTEXT_SUMMARY_PENDING` 且 `data.retryable=true`、`retryAfterMs>0`；服务不可用为 `CONTEXT_SUMMARY_UNAVAILABLE` 且 `data.retryable=true` 并保留相同 ID；最近窗口超限为 `CONTEXT_WINDOW_TOO_LARGE` 且 `data.retryable=false`；`data.clientRequestId` 等于请求 ID；不带 ID的兼容请求首次 pending 后，服务端生成的 ID 出现在响应并被后续同 ID 重试沿用；恢复后 user message 只一条且没有重复加入 Agent history；别人无法触发；删除后摘要/outbox 同事务不存在；Agent mock 收到 summary 但用户 API history 不泄露给其他用户。再验证 `POST /api/admin/context-summary/{sessionId}/retry` 只有 `userType=2` 管理员可将 DEAD 任务重放，普通用户被拒且响应不含摘要正文；重放操作者/时间写入数据库审计字段。仅在 MockMvc 证明必需时修改 `ChatController` 的媒体类型声明，不能把错误响应改成 SSE event。
- [ ] **Step 8: 运行后端验证。** `mvn -f server/pom.xml -Dtest=ChatContextAssemblerTest,ChatControllerTest,RealChatAgentContractTest test`；之后运行 `mvn -f server/pom.xml test`。失败时先定位是本批回归还是已有环境/测试依赖问题，记录真实结果，不跳过失败用例。
- [ ] **Step 9: 提交本阶段。** 提交 `feat(server): resume chat turns after context compaction` 并快进合入本地 `master`。

## Task 6: 前端整理中状态、非 SSE 响应和手动恢复

**目标文件：** `src/views/user/consultation.vue`、`src/views/user/consultation.spec.ts`。

- [ ] **Step 1: 先写 pending UX 失败测试。** 在 `consultation.spec.ts` 模拟 `onopen` 收到 application/json `Result`，其中 `code=CONTEXT_SUMMARY_PENDING`、`data.retryable=true`、`retryAfterMs=5000`；断言页面显示整理提示、待发送用户消息仍可见、sessionStorage 只存 request ID、没有自动重试。另单测错误处理分支在响应提供 `data.clientRequestId` 时保存服务端生成 ID。

```ts
it('keeps the request id and offers manual retry while context is summarized', async () => {
	mocks.fetchEventSource.mockImplementationOnce(async (_url, options: any) => {
		await options.onopen({
			status: 200,
			headers: { get: () => 'application/json' },
			clone: () => ({
				json: async () => ({
					code: 'CONTEXT_SUMMARY_PENDING',
					msg: '正在整理较早的对话',
					data: { retryable: true, retryAfterMs: 5000 },
				}),
			}),
		} as unknown as Response)
	})
	wrapper = mountPage()
	await flushPromises()
	await wrapper.get('[data-testid="message-input"]').setValue('继续上一轮对话')
	await wrapper.get('.send-btn').trigger('click')
	await flushPromises()

	const requestId = sessionStorage.getItem('amha:pending-chat-turn:101')
	expect(requestId).toBeTruthy()
	expect(wrapper.text()).toContain('正在整理较早的对话')
	expect(mocks.fetchEventSource).toHaveBeenCalledTimes(1)
})
```
- [ ] **Step 2: 验证测试失败。** 根目录执行 `pnpm test -- src/views/user/consultation.spec.ts`；预期当前代码只显示通用断网文案。
- [ ] **Step 3: 实现非 SSE 响应解析。** `onopen` 对 `response.clone().json()` 解析 Spring `Result` envelope；pending 显示“正在整理较早的对话”、保留同 ID 和手动“重新检查”；若旧客户端未发送 ID，则从 `data.clientRequestId` 建立 pending storage；unavailable 显示“暂时无法整理，请稍后重试”，保留同 ID 但不显示持续整理状态；窗口超限显示新建会话提示并清理不可重试的 pending 标记。对 401、HTML 错页及非 JSON 仍走当前通用断网恢复逻辑。
- [ ] **Step 4: 验证刷新与重复语义。** 增加刷新后从后端已保存的最后一条 user message 恢复 `clientRequestId`、同一 retry 沿用 request ID、重复点击不会创建不同 request ID、终态超限不自动重播等测试；运行 `pnpm test -- src/views/user/consultation.spec.ts` 和 `pnpm build`。
- [ ] **Step 5: 提交本阶段。** 暂存上述两个前端文件，提交 `feat(web): surface resumable context preparation` 并快进合入本地 `master`。

## Task 7: 离线质量集、运行手册与总体验收

**目标文件：** Agent 合成 fixture/tests、`ChatControllerTest`、新运行手册、V13 和所有本批文件。

- [ ] **Step 1: 增加固定合成摘要 fixture。** 覆盖明确背景、用户偏好、未解决话题、过时信息和用户纠正、相互矛盾表述、prompt injection、空消息及最小化敏感信息；通过来源 ID 与结构断言验收，人工复核自然语言语义，不写虚构准确率。
- [ ] **Step 2: 完成失败路径回归。** Agent 测试验证 queue timeout、turn deadline、模型超时、非法摘要 JSON、未知 source ID、外部 token 缺失、checkpoint 未包含摘要；Java 测试验证 Redis 重复/过期 delivery、最大重试 DEAD、会话删除、CAS 竞争；前端验证 pending 重试和终态提示。
- [ ] **Step 3: 写运行手册。** `docs/operations/chat-context-summary-runbook.md` 写明 V13 备份/预检/执行/回滚、配置键和默认值、Outbox backlog/DEAD 查看方法、通过 `POST /api/admin/context-summary/{sessionId}/retry` 进行管理员手动重放及审计字段核对、先启用 Agent 再 Worker 最后打开用户预检的发布顺序、关闭摘要生成时客户端错误行为、检查无明文日志的方法；手册注明本轮不自动运行 SQL、不清理旧数据。
- [ ] **Step 4: 执行完整本地验证。** 从 `agent/` 执行 `python -m pytest -q`；从根目录执行 `mvn -f server/pom.xml test`、`pnpm test`、`pnpm build`；在工作区执行 `git -c core.whitespace=cr-at-eol diff --check`。如果 Java 集成测试依赖本地服务，按 `server/src/test/resources/application.yml` 核实测试库，不触碰开发数据。
- [ ] **Step 5: 检查多实例环境条件。** 执行 `docker info`；Engine 不可用时记录 Compose 验收未完成，不用 mock 或静态检查替代。可用时按 `deploy/README.md` 的稳定 project name 启动已有 Compose stack，执行 `powershell -NoProfile -ExecutionPolicy Bypass -File deploy/verify-compose.ps1`，再以专用演示账号和合成对话验证至少两个 Spring Boot 与两个 Agent 实例：一台副本写入 WAITING_CONTEXT/Outbox、另一副本消费并完成摘要、跨副本 pending recovery、会话删除竞态和重启后任务可恢复。不得把真实会话用于此验收；不清理命名卷、不连接外部生产服务。若缺少模型凭据，则把跨副本 Outbox/摘要处理标为未验证，不以健康检查替代。
- [ ] **Step 6: 复核安全边界。** 检查每个摘要查询均绑定 session/user；`rg` 确认摘要正文不进入日志语句、Redis Stream payload、checkpoint state 或普通会话列表响应；确认保留所有原始消息且迁移不接触 SQLite/上传文件。
- [ ] **Step 7: 最终提交与合并。** 审查 `git status --short`、`git diff --cached --name-status`，只暂存本计划列出的代码、测试、迁移和新运行手册；提交 `feat(chat): add resumable rolling context summaries` 并快进合入本地 `master`，不推送、不部署。

## 设计覆盖自查

- 设计 §4：Task 3 的 V13、实体、Mapper、fresh-install schema。
- 设计 §5：Task 3/4 的事务 Outbox、Redis Streams、批处理、租约、CAS、删除清理。
- 设计 §6：Task 5/6 的有界上下文、最近原文、pending/unavailable/too-large 分流、SSE 前置错误及服务端生成 request ID 恢复。
- 设计 §7：Task 1/2/4 的内部摘要 API、token、结构校验、临时模型上下文注入。
- 设计 §8：Task 2 的排队超时、整轮 deadline、摘要并发预算和配置。
- 设计 §9：Task 3/5/7 的权限、删除、脱敏日志与指标/运行手册。
- 设计 §10：Task 1/2/4/5/6/7 的合成质量、安全、重复投递和前端恢复测试。
- 设计 §11/§12：Task 7 手册和启动前配置检查。

Task 7 还必须把发布开关默认值落实到配置：`amha.chat.context-summary.preflight-enabled=false`、`worker-enabled=false`、`agent-enabled=false`。开关关闭时不发送超预算历史给 Agent；已有上下文仍在预算内可继续，超预算请求返回 `CONTEXT_SUMMARY_UNAVAILABLE` 并保留可恢复 turn。启用顺序在 runbook 中固定为先 `agent-enabled`，再 `worker-enabled`，最后 `preflight-enabled`；关闭摘要功能不删摘要、Outbox 或原始消息。
