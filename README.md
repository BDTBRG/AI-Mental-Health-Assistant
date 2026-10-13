# AI Mental Health Assistant

面向心理健康支持场景的全栈 AI 应用，提供持续对话、情绪日记、可信知识检索，以及用户授权后的人工复核工作流。项目用于展示 AI 应用全栈工程能力；产品定位是日常支持，不提供诊断或治疗判断，也不代表已接入真实临床服务。

## 产品能力

### 用户端

- SSE 流式对话，支持请求幂等、事件游标续传和断线重连。
- 会话消息按窗口加载，长会话可继续回看更早记录。
- 情绪日记和趋势视图区分用户自评与 AI 分析；缺失日期保持为空档。
- 用户可主动申请人工复核，并可撤回授权。
- 个性化记忆默认关闭；用户可查看、纠正、设定有效期或删除记忆。
- 用户可删除自己的日记，未完成的分析任务也会被移除。

### 管理端

- 只展示用户明确申请复核且授权的会话。
- 支持领取、完成和释放复核任务；状态更新带版本校验并写入操作审计。
- 情绪日记管理接口限定管理员访问，并记录读取和删除审计。

## 架构与工程机制

    Vue 3 / TypeScript
          │ REST + SSE（Last-Event-ID）
          ▼
    Spring Boot ── MySQL：会话、事件、审计、Outbox
          │                    │
          │ 内部鉴权            └── Redis Streams：日记分析 / 知识索引异步任务
          ▼
    FastAPI Agent ── LangGraph：按会话持久化流程状态
          ├── 安全门控与生成后检查
          ├── 混合 RAG：FAISS 向量召回 + 词项召回 / 重排 / 来源标识
          ├── 用户主动维护的长期记忆
          └── LLM 调用治理与脱敏观测

### 前端

- 对话请求使用客户端幂等 ID，服务端事件带序号；重连通过事件 ID 续读。
- 会话列表只渲染最近消息窗口，用户可以逐批加载较早消息。
- 复核状态、用户授权、记忆开关和日记趋势分别呈现，避免把 AI 推断混同为用户自评。

### 后端

- 会话消息、流式事件和回合状态写入 MySQL；同一幂等 ID 重放可复用已生成结果，冲突请求被拒绝。
- 用户、会话归属在读取、删除及流式入口校验；公开注册不能指定管理员角色。
- 日记分析与知识索引通过事务 Outbox 衔接数据库与 Redis Streams，包含租约、重试和失败状态。
- 管理工作流采用带版本号的状态迁移，防止多人并发操作覆盖，并记录审计信息。
- LLM 调用和 Agent 私有 API 分别有调用预算/熔断控制和共享密钥校验。

### Agent

- 风险识别独立于生成提示词；识别或分析失败按“不确定”处理，避免默认回落为低风险。生成后再做安全检查。
- RAG 使用稠密与词项召回、重排和低置信度拒答，并保留文章/片段标识供回答引用；索引映射按 chunk ID 读取。
- LangGraph 检查点以会话为隔离键持久化，聊天回合结果缓存以支持重试复用。
- 长期记忆只采纳用户主动添加的内容，包含来源和有效期；撤回或变更会在数据库事务中写入检查点清理 Outbox，由 Worker 持续退避重试，直到 Agent 确认完成。
- 知识下架、删除和索引更新由版本化 Outbox 传播；失败任务带错误码与尝试次数，并持续退避重试，避免索引长期保留旧版本。
- 调用层记录模型、提示词摘要、索引版本、阶段耗时和 Token 用量，并实施超时、并发上限、熔断及进程内用户配额。

## 技术栈

| 端 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router、ECharts、Axios |
| 后端 | Java 17、Spring Boot 3、MyBatis-Plus、MySQL 8、Redis 7、JWT、WebFlux、SSE |
| Agent | Python、FastAPI、LangChain OpenAI 兼容接口、LangGraph、FAISS、Sentence Transformers、SQLite checkpoint |
| 验证 | JUnit 5、MockMvc、H2、Vue TypeScript 构建、Python compileall / pytest |

## 本地运行

### 环境要求

- Node.js 18+、pnpm（或 npm）
- JDK 17、Maven 3.9+
- MySQL 8、Redis 7
- Python 3.10+

### 1. 初始化数据库

新库执行：

    mysql -u root -p < server/src/main/resources/db/init.sql

已有数据库按顺序应用 upgrade_v2 至 upgrade_v10 的脚本；先备份并核对当前 schema。升级脚本没有在本任务中应用到生产数据库。

### 2. 启动 Agent

    cd agent
    cp .env.example .env
    # 在 .env 中设置 DeepSeek API Key 和随机生成的 AGENT_INTERNAL_TOKEN
    python -m venv .venv
    # 按操作系统激活 .venv
    pip install -r requirements.txt
    python app.py

Agent 默认监听 http://localhost:5000。私有接口要求 X-Agent-Token；缺少密钥会失败关闭。

### 3. 启动后端

将与 Agent .env 中完全相同的 AGENT_INTERNAL_TOKEN 提供给 Spring Boot 进程：

    cd server
    export AGENT_INTERNAL_TOKEN='your-long-random-secret'
    mvn spring-boot:run

PowerShell 示例：

    $env:AGENT_INTERNAL_TOKEN = 'your-long-random-secret'
    mvn spring-boot:run

后端默认监听 http://localhost:8080。仅在空数据库初始化时创建演示管理员 admin/admin123 和演示数据；部署前应替换演示凭据。

### 4. 启动前端

    pnpm install
    pnpm dev

前端默认监听 http://localhost:5173，Vite 将 /api 转发到后端。

## 数据与安全边界

- 只在用户明确授权并主动申请后进入人工复核队列；复核工作台是演示工作流。
- 情绪分析和对话内容仅用于支持体验，不应当作医学诊断或治疗建议。
- 数据库升级脚本需按版本顺序人工核验；本文档不代表生产迁移已经执行。
- 调用配额与熔断状态在当前实现中是进程内状态，多实例环境需改为共享限流和统一成本账本。
- Agent 测试需要先安装依赖；测试环境若缺少 pytest，python -m pytest 不可运行。
- 没有真实服务流量和固定基准测试数据时，不对延迟提升、准确率、成本节省或恢复率作量化承诺。

## 验证命令

    # Spring Boot 后端测试
    cd server
    mvn test

    # 前端 TypeScript 检查（在项目根目录）
    pnpm exec vue-tsc -b

    # Agent 语法检查（在项目根目录）
    python -m compileall -q agent

    # Agent 测试（安装 requirements 后）
    python -m pytest -q agent/tests
