# 宁渡 Mind 本地多实例演示

此 Compose 环境用于本地作品演示和多副本联调。它把 Vue 静态站点、两个 Spring Boot 实例和两个 FastAPI Agent 实例放在一套可复现的入口后面，共享 MySQL、Redis、PostgreSQL、Qdrant 和 S3 兼容对象存储。它不是生产部署模板，也不接入临床服务。

## 启动

在仓库根目录执行：

```powershell
Copy-Item deploy/.env.compose.example .env.compose -NoClobber
```

编辑 `.env.compose`，替换本地演示密码和 JWT/Agent 内部密钥。密码会进入数据库连接 URL，建议使用 URL 安全字符。需要真实模型回复时，再填入你有权使用的 `DEEPSEEK_API_KEY`；留空时，Agent 仍可启动并通过健康/就绪检查，对话与上下文摘要请求会返回 HTTP 503 和 `LLM_NOT_CONFIGURED`，不会伪造模型回复。填入或更换 Key 后需重建 Agent 容器使配置生效。

`CRISIS_RESOURCE_TEXT` 默认留空。只有部署方已从当地官方渠道核验联系信息时，才填写可展示的纯文本资源；联系信息来源和复核日期应记录在部署方的配置管理中。未配置时，风险回复仍建议联系当地紧急服务或就近急诊，不会输出未经核验的电话号码。

```powershell
Import-Module .\deploy\compose-verification.psm1 -Force
$projectName = Get-ComposeProjectName -RepositoryRoot (Get-Location).Path
docker compose -p $projectName --env-file .env.compose config --quiet
docker compose -p $projectName --env-file .env.compose up -d --build
docker compose -p $projectName --env-file .env.compose ps
```

`qdrant-ready` 是 Agent 镜像唯一的构建服务，`api-1` 是 Spring 镜像唯一的构建服务；对应副本只引用相同镜像标签且不尝试拉取远程标签，避免 `up --build` 并发构建并覆盖本地标签或在构建完成前拉取不存在的镜像。前端镜像仍由 `gateway` 构建。

构建会将宿主机设置的 `HTTP_PROXY` 和 `HTTPS_PROXY` 作为可选 BuildKit 代理参数传入依赖安装步骤。本机代理若只监听宿主机回环地址，容器内需使用 `host.docker.internal` 可达的代理地址。

打开 <http://127.0.0.1:8080>。仅 Nginx 网关绑定本机端口；MySQL、Redis、PostgreSQL、Qdrant、RustFS 和 Agent 端口没有发布到宿主机。Spring Boot 使用 `demo` profile，首次初始化时会写入仓库已有的演示账号和模拟内容，不要把这套账号用于共享或生产环境。两个 API 副本通过 MySQL 命名锁串行执行初始化，并在同一事务中写入演示数据；等待锁超过 60 秒或初始化失败时，该副本启动失败并由 Compose 重试，不会把锁竞争当作“初始化成功”。H2 单进程测试环境使用事务但不提供跨进程锁；双副本保证以本地 MySQL Compose 验收为准。

项目名由当前 checkout 绝对路径的 SHA-256 前 16 位生成，同一路径下停止/重启会命中同一 Compose 项目，不同目录的克隆会使用不同容器和数据卷。在新的 PowerShell 窗口中先重新计算项目名，再停止或启动：

```powershell
Import-Module .\deploy\compose-verification.psm1 -Force
$projectName = Get-ComposeProjectName -RepositoryRoot (Get-Location).Path
docker compose -p $projectName --env-file .env.compose stop
# 再次启动时运行：docker compose -p $projectName --env-file .env.compose up -d
```

数据库、向量索引、检查点、对象和模型缓存使用命名卷持久化。不要使用 `down -v`；该选项会删除这些本地演示数据。单机 SQLite 开发模式的会话锁与删除墓碑只在当前 Agent 进程内有效，仅用于单实例本地运行；Compose 多副本必须使用共享 PostgreSQL 检查点模式。

## 架构与验收

- 浏览器访问 `gateway:8080`；`/api/` 去掉前缀后转发到两个 Spring 实例，`/file/uploads/` 保留文件读取路径。SSE 响应关闭代理缓冲，读取/发送超时为 310 秒。
- Spring 通过内部网关访问两个 Agent 副本。Agent 共享 PostgreSQL LangGraph 检查点、会话执行 advisory lock 与删除墓碑、Qdrant 知识索引和 Redis 写锁；两个 Agent 串行完成首次 PostgreSQL 检查点表初始化。检查点以会话 ID 作为 LangGraph 根 thread，并按数据库回合 ID `turnSequence` 与单调递增的 `generationEpoch` 比较写入栅栏；低于最新检查点栅栏的迟到请求会被拒绝。只有 `turn_id`、`turnSequence`、`generationEpoch` 与随机 `generation_token` 同时匹配时才复用缓存回复；删除会清理该会话 thread 下的所有检查点。删除墓碑表由 Agent 启动时幂等创建；`AGENT_CHECKPOINT_LOCK_POOL_SIZE` 是每个 Agent 副本的锁连接池上限，默认 16，另有一个独立连接用于先提交删除墓碑。内网 Agent 请求超时为 610 秒，给长 graph 清理和会话删除留出等待空间；公网 SSE 超时仍为 310 秒。
- Java 与 Agent 的 v2 对话/清理接口需要配套升级。新 Java 调用旧 Agent 会收到 404 并失败关闭，清理 Outbox 会重试；新 Agent 仍接受旧 Java 请求：缺失的 `generationEpoch`/`turnSequence` 按 0 兼容，显式提供的值仍须为有效正整数；token-only 的 v2 请求仍须提供 `generationToken`，旧 `/api/agent/chat` 路由仍允许缺少 token。此兼容路径不具备 Agent 端的完整检查点代际栅栏；旧 Java 的数据库 token 仍会阻止过期事件落库。只有 V12 数据迁移完成、所有 Java 与 Agent 实例升级且旧在途请求排空后，才可依赖新的跨副本 checkpoint fencing。旧 Java 的兼容 checkpoint DELETE 无法表达“记忆撤回”与“会话永久删除”的区别，只执行临时清理；永久删除栅栏也要求两端配套升级。
- Agent 另接入仅供出站访问模型和 Hugging Face 的网络；依赖服务与网关位于私有网络。内网端口没有 `ports` 映射。
- Java 和 Agent 的存活/就绪检查分别使用 Actuator 与 FastAPI 探针。Actuator 只暴露 health，隐藏组件详情；Spring 就绪状态要求数据库和 Redis 健康。Agent 就绪状态只有在 RAG 后端和 LangGraph 检查点存储初始化后才为就绪。Qdrant 启动门使用 Agent 镜像中的 Python 标准库每两秒轮询 `/readyz`，最多等待 120 秒，避免依赖 Qdrant 镜像未提供的探测工具。
- 网关结构化访问日志记录格式校验后的探测关联 ID、HTTP 方法、状态、上游地址、上游状态和耗时，不记录请求路径、查询参数或请求体；查看可运行 `docker compose -p $projectName --env-file .env.compose logs gateway`，访问日志中的 `upstream=` 会显示后端地址。验收脚本只统计带本轮关联 ID 且对应上游返回 2xx 的请求，避免同期其他网关流量影响副本覆盖判断。
- Nginx 错误日志保留 `warn` 级诊断信息；代理或客户端错误在某些情况下会包含请求行和 URI/查询参数，因此错误日志不承诺与结构化访问日志同样的字段脱敏。不要把心理内容或凭据放进 URL/查询参数；部署到共享环境前应按该环境的隐私要求配置错误日志采集和保留。
- Agent 镜像固定安装 CPU 版 PyTorch，不下载 CUDA 运行库；首次执行 RAG 建索引时会下载多语言句向量模型，并放入共享模型缓存卷。

可在 Agent 容器内运行回归：

```powershell
Import-Module .\deploy\compose-verification.psm1 -Force
$projectName = Get-ComposeProjectName -RepositoryRoot (Get-Location).Path
docker compose -p $projectName --env-file .env.compose exec -e RAG_BACKEND=faiss agent-1 python -m pytest -q tests
```

执行 `./deploy/verify-compose.ps1` 会请求网关和公开知识接口，检查两个 Agent 副本均为 healthy，并通过内部 Agent 健康路由验证 Nginx 日志覆盖两个 Agent 与两个 Spring 上游。若已安装 Pester，可运行 `Invoke-Pester -Script .\deploy\compose-verification.Tests.ps1 -EnableExit` 检查上游日志解析。需要验证真实生成、RAG 重建或跨实例会话续接时，需提供有效模型凭据并使用专门的模拟账号/文章进行操作；这些步骤不会由启动脚本自动触发。

## 数据与限制

- Compose 使用全新的 PostgreSQL 检查点库、Qdrant 索引和 RustFS 对象桶，不导入现有 SQLite 检查点、FAISS 文件或本机上传目录。旧文件保留在原位置；请按独立迁移工具的预检/备份流程迁移，不要把迁移和启动 Compose 混为一步。
- 新版 Agent 将日记和已授权记忆作为单次运行上下文传递；原始工具结果不会直接写入 checkpoint 的 `ToolMessage`，该消息仅保存占位内容。模型仍可能在生成的 `AIMessage`/`final_text` 中复述或概括个人信息；这些回复会进入 checkpoint 供同 generation 重放，也会作为聊天消息保存在 MySQL。因此 checkpoint 不是脱敏后的记录。模型提供商仍会在该次请求中接收被调用工具的结果，需按实际提供商的数据处理条款评估。
- 旧版 Agent 已写入的 checkpoint 可能含有当时注入的日记摘要和记忆；各版本 checkpoint 也会保存图执行所需的会话消息与生成回复。升级不会自动改写或删除历史 checkpoint。迁移 PostgreSQL 检查点时，应先完成数据库备份、排空旧版 Java/Agent 实例和 RUNNING 对话，再从 MySQL 的 `consultation_session.id` 清单逐个调用内网 v2 临时 checkpoint purge 接口。该接口会清除该会话的 LangGraph checkpoint，但保留 MySQL 中的会话消息；确认清理完成后再恢复流量。此步骤不读取、迁移或清理本机 SQLite 检查点文件。
- MySQL 的 `init.sql` 只在空数据卷首次初始化时执行。它不替代版本化迁移、备份或恢复流程。
- 已有 MySQL 数据库需先备份，再按顺序应用未执行的迁移脚本；本次回合 fencing 需要在 `upgrade_v11_idempotent_session_start.sql` 后应用 `upgrade_v12_chat_turn_generation_epoch.sql`。完成 schema 迁移和 Java/Agent 配套升级后再恢复流量。
- MySQL、Redis、PostgreSQL、Qdrant、RustFS 都是单节点，构成演示环境的基础设施单点；命名卷不是备份。这里没有配置集中日志、指标告警、TLS、密钥管理服务或外部值班渠道。
- DeepSeek 与 Hugging Face 属于外部依赖。当前未执行真实用户对话、临床服务或生产数据验收；不要把本地多副本运行描述成生产 HA 或 SLA 证明。
