# 会话历史滚动摘要运行手册

本文面向本地演示和受控联调。功能只整理较早的对话以维持上下文，不用于诊断或治疗判断。不要在真实用户会话上做故障演练；本文不自动运行 SQL、重放任务、清理数据或接入外部值班平台。

## 发布前检查

1. 确认 Java、Agent 与数据库迁移版本配套。先应用 V13（摘要与 Outbox 表），再应用 V14（轮次与用户消息关联）：`server/src/main/resources/db/upgrade_v13_chat_context_summary.sql`、`server/src/main/resources/db/upgrade_v14_chat_turn_message_link.sql`。
2. 先备份目标 MySQL 数据库并验证备份可读。使用受限权限的 MySQL 客户端凭据文件或组织批准的凭据管理方式，不要把密码写入命令行、脚本、日志或仓库。
3. 在目标 schema 执行只读预检：

   ```sql
   SELECT DATABASE();
   SELECT TABLE_NAME
   FROM information_schema.tables
   WHERE table_schema = DATABASE()
     AND table_name IN (
       'consultation_chat_turn',
       'consultation_context_summary',
       'consultation_context_summary_outbox'
     );
   SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
   FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'consultation_chat_turn'
     AND column_name = 'user_message_id';
   ```

   `consultation_chat_turn` 必须存在；两张 V13 表都不存在时可继续。若 V13 只存在一张，或现存列/索引与 V13 不一致，停止发布并由数据库负责人先核对；`CREATE TABLE IF NOT EXISTS` 不会修复部分建成或结构漂移的表。V14 列可以尚不存在；若已存在，必须为 nullable `BIGINT`，否则先停止并核对 schema 漂移。
4. 从仓库根目录，在确认过的 schema 上按顺序执行两个迁移。Linux/macOS 可使用：

   ```sh
   mysql --defaults-extra-file=/secure/path/mysql-client.cnf <database_name> \
     < server/src/main/resources/db/upgrade_v13_chat_context_summary.sql
   mysql --defaults-extra-file=/secure/path/mysql-client.cnf <database_name> \
     < server/src/main/resources/db/upgrade_v14_chat_turn_message_link.sql
   ```

   PowerShell 可在已选择目标数据库的受控 MySQL 客户端中导入同一 SQL 文件。不要在本地演示之外的环境直接复制执行。
5. V14 通过 `information_schema.columns` 检查后才添加 `consultation_chat_turn.user_message_id`，可重复执行。迁移后用 `SHOW CREATE TABLE` 检查摘要/Outbox 表及该列；旧 turn 的该列保持 NULL，不猜测关联、不回填历史消息。迁移不改写消息、不读取或改写 SQLite checkpoint，也不删除本机上传文件。

## 开关与预算

下面是 Spring 默认值。三个功能开关均默认关闭；所有环境必须显式评估后再打开。

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `AMHA_CHAT_CONTEXT_SUMMARY_AGENT_ENABLED` | `false` | 允许 Java 调用 Agent 摘要端点；Worker 依赖此开关 |
| `AMHA_CHAT_CONTEXT_SUMMARY_WORKER_ENABLED` | `false` | 启动 Outbox/Redis Streams 摘要 Worker |
| `AMHA_CHAT_CONTEXT_SUMMARY_PREFLIGHT_ENABLED` | `false` | 对超出原文窗口的聊天启用摘要预检 |
| `AMHA_CHAT_CONTEXT_SUMMARY_STREAM_KEY` | `amha:outbox:context-summary` | Redis Stream 名称 |
| `AMHA_CHAT_CONTEXT_SUMMARY_DISPATCH_INTERVAL_MS` | `2000` | Outbox 发布轮询间隔 |
| `AMHA_CHAT_CONTEXT_SUMMARY_BATCH_MAX_MESSAGES` | `40` | 单次发给 Agent 的消息数上限 |
| `AMHA_CHAT_CONTEXT_SUMMARY_BATCH_MAX_CHARS` | `16000` | 单次摘要输入字符上限 |
| `AMHA_CHAT_CONTEXT_SUMMARY_PROCESSING_LEASE` | `PT90S` | Java Worker 处理租约，必须比 Agent 超时至少长 25 秒 |
| `AGENT_CONTEXT_SUMMARY_TIMEOUT_MS` | `65000` | Java 到 Agent 摘要请求超时 |
| `AMHA_CHAT_CONTEXT_SUMMARY_MAX_HISTORY_MESSAGES` | `39` | Agent 最近原文消息数上限 |
| `AMHA_CHAT_CONTEXT_SUMMARY_RECENT_MESSAGES_TO_KEEP` | `12` | 为后续对话保留的最近原文消息数 |
| `AMHA_CHAT_CONTEXT_SUMMARY_MAX_CONTEXT_CHARS` | `32000` | Agent 对话输入字符预算 |
| `AMHA_CHAT_CONTEXT_SUMMARY_MAX_SINGLE_MESSAGE_CHARS` | `8000` | 单条消息字符上限 |
| `AMHA_CHAT_CONTEXT_SUMMARY_MAX_AGENT_REQUEST_BYTES` | `65536` | Java 发往 Agent 的 JSON 请求体上限 |
| `AMHA_CHAT_CONTEXT_SUMMARY_RETRY_AFTER_MS` | `5000` | 可恢复预检错误返回的建议重试间隔 |

Agent 侧还需配置 `AGENT_INTERNAL_TOKEN`、模型服务凭据和以下预算；敏感值应通过本地安全配置注入，不提交到 `.env` 或 Compose 示例文件：

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `LLM_QUEUE_WAIT_TIMEOUT_SECONDS` | `5` | 等待共享模型并发槽的最长时间 |
| `LLM_CONTEXT_SUMMARY_MAX_CONCURRENT_CALLS` | `1` | 每个 Agent 实例的摘要模型并发数 |
| `AGENT_TURN_TIMEOUT_SECONDS` | `240` | Agent 整轮聊天截止时间 |

配置项的权威来源是 `server/src/main/resources/application.yml`、`application-example.yml` 和 `agent/config.py`。修改预算时同时检查 Agent 请求大小、模型超时、Java HTTP 超时和处理租约关系。

## 建议启用顺序

1. 保持三项 Spring 开关关闭，先部署匹配版本的 Agent，并确认 Agent 就绪、内部 token 可用、模型凭据可用。Agent 的 `/metrics` 仅供受控的内网监控读取；不要将端口公开到公网。
2. 设置 `AGENT_INTERNAL_TOKEN` 后启用 `AMHA_CHAT_CONTEXT_SUMMARY_AGENT_ENABLED=true`。做一条合成会话摘要请求，确认成功/失败只记录分类错误，不出现摘要正文或消息内容。
3. 启用 `AMHA_CHAT_CONTEXT_SUMMARY_WORKER_ENABLED=true`。观察 Outbox 行数、旧任务等待时间和 Agent 摘要指标，确认任务可消费、租约可恢复。
4. 通过低流量合成会话确认摘要水位、来源 ID 与最近原文窗口正确，再最后启用 `AMHA_CHAT_CONTEXT_SUMMARY_PREFLIGHT_ENABLED=true`。
5. 逐步扩大演示流量。用户端预检错误的行为见下表。任何一步异常都可先关闭预检，再停止 Worker；关闭功能不会删除摘要、Outbox 或原始消息。

## Outbox 检查与人工恢复

只查状态、错误码和审计字段，不查询或复制摘要正文、会话消息、用户自述或 checkpoint 内容。以下 SQL 可用于查看数量：

```sql
SELECT status, COUNT(*) AS task_count
FROM consultation_context_summary_outbox
GROUP BY status;

SELECT id, session_id, desired_through_message_id, status, attempt_count,
       next_attempt_at, last_error_code, manual_retry_count,
       last_manual_retry_by, last_manual_retry_at
FROM consultation_context_summary_outbox
WHERE status IN ('PENDING', 'QUEUED', 'PROCESSING', 'DEAD')
ORDER BY updated_at;
```

`PENDING` 等待重试/分发，`QUEUED` 已进入 Redis Stream，`PROCESSING` 正在处理；过期租约可由 Worker 回收。达到最大自动重试次数后进入 `DEAD`。`last_error_code` 是经过分类的错误码，不应扩展为原始异常文本或模型响应。

`DEAD` 任务须先核对模型服务、Agent 连通性、预算和迁移状态。确认原因已处理后，管理员可调用：

```text
POST /api/admin/context-summary/{sessionId}/retry
```

该接口只接受管理员角色，响应不包含摘要。调用后核对对应 Outbox 行的 `manual_retry_count`、`last_manual_retry_by`、`last_manual_retry_at` 已更新且状态回到 `PENDING`。重复调用不会重置非 `DEAD` 任务，也不要通过 SQL 手改任务状态。

## 用户侧错误处理

| 错误码 | 前端处理 | 运维判断 |
| --- | --- | --- |
| `CONTEXT_SUMMARY_PENDING` | 保留 request ID，展示整理中并允许用户手动重新检查；阻止发送下一轮 | 查看 PENDING/QUEUED/PROCESSING 与摘要指标 |
| `CONTEXT_SUMMARY_UNAVAILABLE` | 保留 request ID，提示稍后手动重试 | 检查 Agent、模型凭据、限额和服务开关；超预算历史不会提交给 Agent |
| `CONTEXT_SNAPSHOT_STALE` | 服务端已保存本轮用户消息和 request ID；客户端停止本次请求并保留 ID，提供手动重试，刷新后可由恢复接口找回原文。重试若仍需摘要，会先转为 `WAITING_CONTEXT` 并入队；摘要完成后用户手动重新检查。上下文就绪时服务端检查全部其他活动轮次：存在 `WAITING_CONTEXT` 或未超时的 `PENDING`/`RUNNING` 即返回业务冲突，不排队生成；只有全部其他 `PENDING`/`RUNNING` 均超时才按 5 分钟规则失效后继续，较早的过期记录不能遮住后续有效轮次 | 常见于并发设备或上下文准备期间又出现新消息；重试按 turn 的 `user_message_id` 校验原文，从历史窗口排除该条并作为本轮输入，只重建一次上下文，不终结其他活动轮次 |
| `CONTEXT_WINDOW_TOO_LARGE` | 清除不可重试标记，提示新建会话 | 近期原文窗口本身超过预算，不能通过重复重试解决 |
| `CONTEXT_REQUEST_SUPERSEDED` | 清理该请求标记，提示使用最近一条消息继续 | 另一个新请求已使旧请求失效 |

前端在浏览器 `sessionStorage` 只保存随机 request ID，不保存原始消息文本。启动扫描会按分页查找当前浏览器保存了 pending marker 的会话，并在“待恢复会话”入口展示可访问的会话标题；单个聊天窗只恢复用户选择的轮次，不并发自动重放。刷新恢复调用 `GET /psychological-chat/sessions/{sessionId}/turns/{clientRequestId}`；服务端要求会话属于当前用户、turn 关联的消息为该会话用户消息，且消息 SHA-256 与 turn 的请求哈希一致，才返回原文。旧 turn 或关联缺失/哈希不匹配时返回不可恢复状态，客户端清除标记并提示重新输入；当前用户会话列表未匹配的 marker 不自动清除，避免覆盖账号切换场景下其他账号的恢复状态。不要通过错误追踪、截图、浏览器存储导出或监控标签补记用户消息。

## 观测、隐私与排障

- Agent 提供内部 `/metrics`，其中摘要耗时指标为 `amha_agent_context_summary_duration_seconds`；模型队列等待指标按有限的调用阶段分类，不包含 user/session ID。
- Java 注册 `amha.chat.context_summary.*` Micrometer 指标，包括 Outbox PENDING/DEAD 数、最旧待处理等待秒数、成功/失败计数、预检结果计数、处理时长和输入/输出字符分布。当前 Actuator 对外只暴露 health；不要把“指标已注册”表述为已经接入 Prometheus/Grafana。运维可先通过上面的只读 SQL 检查 Outbox；如需 Prometheus 抓取，应单独完成受网络访问控制的端点与 registry 配置。
- 重点排查：`DEAD` 增长、最旧 PENDING 持续增长、摘要完成计数停止、Agent 队列超时、Agent 不就绪、Worker 与 Agent 开关不匹配、处理租约小于 HTTP 超时加安全余量。
- 应用日志只记录任务 ID/错误类型等运行信息；不要增加请求体、摘要 JSON、原始消息、授权记忆或模型原始响应的日志。禁止在 Redis Stream 字段加入正文；目前消息队列只传 Outbox ID。
- Compose 环境的运行与跨副本健康检查按 [`deploy/README.md`](../../deploy/README.md) 操作。健康检查只证明服务就绪和流量覆盖，不证明模型生成、摘要质量、请求恢复或生产可用性。真实生成必须使用合成对话和专用演示账号。

## 回滚

1. 先设置 `AMHA_CHAT_CONTEXT_SUMMARY_PREFLIGHT_ENABLED=false`，阻止新请求进入摘要预检；超预算会话返回可恢复的不可用状态，不会把超预算历史发给 Agent。
2. 等待或停止摘要 Worker 后设置 `AMHA_CHAT_CONTEXT_SUMMARY_WORKER_ENABLED=false` 和 `AMHA_CHAT_CONTEXT_SUMMARY_AGENT_ENABLED=false`，保留待处理任务以便后续恢复。
3. 回退 Java/Agent 版本前，确认旧版本不会收到新的字段/路由契约；需要时保持功能关闭。原始聊天消息、V13 摘要/Outbox 表和 V14 轮次关联列均保留。
4. 不以 `DROP TABLE` 作为应用回滚步骤。若数据库必须恢复到迁移前状态，由数据库负责人基于经过验证的完整备份制定恢复窗口与方案；不要在本手册中自动删除摘要或任务数据。
