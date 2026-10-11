# 设计规范：监控指标实时页面大盘 & ReAct 执行状态机断点续跑 (Phase 5)

## 1. 目标与背景
用户提出两大核心企业级需求：
1. **监控指标数据实时记录并通过页面展示**：告别仅提供 JSON 接口的模式，提供内置现代、美观、低开销的 Web 实时大盘，展示全网请求吞吐、成功率、Token 消耗、缓存命中节省、租户用量分布与异常拦截。
2. **ReAct 执行中断/失败可重新拉起与断点续跑**：针对 Agent 在多轮 Tool Calling 调度过程中可能出现的临时网络中断、工具执行异常、超时或进程重启，支持状态机管理与 SQLite 持久化 Execution Checkpoint，能够自动重试或通过 API/页面重新拉起，恢复上下文继续执行，避免状态丢失与重复算力开销。

---

## 2. 总体架构设计

```mermaid
graph TD
    subgraph UI 层 (Web Dashboard)
        Browser[浏览器 Client] -->|访问 / 或 /admin/dashboard| StaticPage[静态单页仪表盘 HTML5 + Tailwind + ECharts]
        StaticPage -->|轮询 /v1/admin/analytics/overview & /v1/admin/metrics/timeline| AdminAPI[Admin Controller]
        StaticPage -->|查看 /v1/agent/tasks & 重新拉起任务| AgentTaskAPI[Agent Task API]
    end

    subgraph 监控时序记录引擎 (Metrics Engine)
        RequestPipeline[Chat Completion Pipeline] -->|每一次请求完成/失败/拦截| MetricsRecorder[MetricsRecordService (内存环形缓冲 + SQLite 统计聚合)]
        MetricsRecorder -->|持久化每分钟/小时聚合指标| MetricsDB[(SQLite: system_metrics)]
    end

    subgraph ReAct 任务状态机与断点续跑 (Agent Task Engine)
        AgentRequest[Agent 请求带有 tools / task_id] --> TaskManager[AgentTaskManager]
        TaskManager -->|1. 创建/恢复任务 Task State: RUNNING| CheckpointRepo[(SQLite: agent_tasks)]
        TaskManager --> Orchestrator[AgentToolOrchestrator: 带状态机调度]
        Orchestrator -->|每轮调用前保存 Checkpoint: conversation_history, iteration| CheckpointRepo
        
        Orchestrator -->|工具执行异常 / 上游 5xx / 超时| ErrorHandler{错误类型}
        ErrorHandler -->|可重试错误| AutoRetry[指数退避重试 (Max 3次)]
        ErrorHandler -->|不可逆失败 / 超过重试| MarkSuspended[任务置为 FAILED / SUSPENDED 并固化 Checkpoint]
        
        UserResume[用户/调度器请求 /v1/agent/tasks/{id}/resume] --> TaskManager
        TaskManager -->|加载上次 Checkpoint，跳过已成功的 Tools，从断点轮次继续调用| Orchestrator
    end
```

---

## 3. 核心模块与实现细节

### 3.1 监控指标时序记录与内置 Web 仪表盘
1. **指标时序记录服务 (`MetricsRecordService`)**:
   - 记录每一笔请求的维度：`timestamp`, `tenant_id`, `model`, `status_code`, `latency_ms`, `tokens_used`, `cache_hit_type` (L1/L2/MISS), `violation_type` (NONE/INJECTION/DLP/QUOTA/RATE_LIMIT/TOOL_DANGER/CROSS_TENANT)。
   - 提供最近时段（如 1 小时/24 小时）按分钟粒度聚合的时间序列接口：`/v1/admin/metrics/timeline`。
2. **现代内置 Web 仪表盘 (`src/main/resources/static/index.html`)**:
   - 纯前端单页架构（HTML5 + Tailwind CSS CDN + ECharts），零额外构建配置或 node 依赖。
   - **核心卡片**：
     - 吞吐量与 QPS（实时刷新）；
     - 两级缓存命中率（L1/L2）与累计节约 Token 计数牌；
     - 租户配额与用量 Top 榜单；
     - 安全护栏防护拦截分类饼图（Prompt 注入 / 输出 DLP / 工具高危操作 / 跨租户越权）；
     - 时序折线图（请求延时与 Token 消耗走势）；
   - **Agent 任务控制台**：直观展示 Agent 任务列表、当前轮次状态（RUNNING / COMPLETED / FAILED / SUSPENDED），并支持在页面上一键点击 **「重新拉起 (Resume)」**。

### 3.2 Agent ReAct 任务状态机与 Checkpoint 存储
1. **任务实体与状态枚举 (`AgentTaskEntity` & `TaskStatus`)**:
   - 状态：`PENDING` -> `RUNNING` -> `SUSPENDED` / `FAILED` -> `COMPLETED`。
   - 包含字段：`taskId`, `tenantId`, `model`, `maxIterations`, `currentIteration`, `status`, `errorMessage`, `conversationHistoryJson`, `toolCallsStateJson`, `retryCount`, `createdAt`, `updatedAt`。
2. **SQLite 数据表结构**:
   - `CREATE TABLE agent_tasks (...)`：持久化任务状态与序列化历史。
   - `CREATE TABLE system_metrics (...)`：记录分钟级聚合时序。
3. **断点续跑机制 (`AgentTaskManager.resumeTask(taskId)`)**:
   - 从 SQLite 加载未完成的任务实体。
   - 还原对话上下文（已成功执行的 Tool 及其 Result 保持在上下文中，无需重复执行已有副作用的工具）。
   - 从 `currentIteration` 重新拉起 `orchestrator.runLoop`，继续执行后续规划与决策，直至产出最终答复或再次受阻。
4. **对外暴露接口**:
   - `GET /v1/agent/tasks`：查询任务列表与执行进度；
   - `GET /v1/agent/tasks/{id}`：查询指定任务执行链路详情；
   - `POST /v1/agent/tasks/{id}/resume`：重新拉起失败或挂起的中断任务。

---

## 4. 优雅设计与约束
- **无侵入与零外部依赖**：依然坚持 SQLite + 原生 Spring Boot Web 静态资源托管，不增加 Redis、MQ 或独立前端构建打包环境，开箱即用。
- **线程安全与并发控制**：任务拉起增加并发乐观锁/状态校验（仅 `FAILED` 或 `SUSPENDED` 可被 resume），防止同一任务并发冲突。
- **完善测试覆盖**：编写断点保存、异常模拟中断、重试成功、从断点恢复并最终完成的单元测试与端到端测试。
