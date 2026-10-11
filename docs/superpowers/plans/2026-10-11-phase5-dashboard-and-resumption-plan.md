# Phase 5 Implementation Plan: Real-Time Dashboard UI & ReAct Execution Resumption Engine

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建内置现代 Web 监控可视化大盘（HTML5 + Tailwind CSS + ECharts）与 ReAct Agent 执行中断断点持久化与自动续跑状态机引擎。

**Architecture:** 
1. 请求流经网关时，通过 `MetricsRecordService` 毫秒级捕获指标，并存储到 SQLite 聚合时序表中；提供 `/v1/admin/metrics/timeline` 接口供静态单页大盘实时轮询渲染。
2. Agent 执行时通过 `AgentTaskManager` 分配 `taskId`，每轮 ReAct 工具调用前保存 Checkpoint 至 SQLite `agent_tasks` 表；当遇到网络/工具执行中断时将任务置为 `SUSPENDED`/`FAILED`，支持调用 `/v1/agent/tasks/{id}/resume` 恢复未完结的任务上下文并继续完成推理。

**Architecture Diagram:**

```mermaid
graph TD
    subgraph UI & Metrics
        User[浏览器] -->|访问 /| IndexPage[static/index.html: ECharts + Tailwind]
        IndexPage -->|轮询 /v1/admin/metrics/timeline| AdminCtrl[AdminController]
        AdminCtrl --> MetricsService[MetricsRecordService]
        MetricsService --> MetricsDB[(SQLite: system_metrics)]
    end

    subgraph Agent Task State Machine
        ClientReq[Agent 请求] --> TaskManager[AgentTaskManager]
        TaskManager -->|持久化每轮状态| TaskDB[(SQLite: agent_tasks)]
        TaskManager --> Orchestrator[AgentToolOrchestrator]
        Orchestrator -->|出错中断| CheckpointFail[保存 SUSPENDED Checkpoint]
        ResumeReq[POST /v1/agent/tasks/{id}/resume] --> TaskManager
        TaskManager -->|加载已成功的工具结果与上下文| Orchestrator
    end
```

**Tech Stack:** Java 21 Virtual Threads, Spring Boot 3 Web, SQLite JDBC, Tailwind CSS, ECharts.

---

### Task 1: 监控时序数据结构与 MetricsRecordService 实现

**Files:**
- Modify: `src/main/resources/schema.sql`
- Create: `src/main/java/com/enterprise/llm/metrics/SystemMetricPoint.java`
- Create: `src/main/java/com/enterprise/llm/metrics/MetricsRecordService.java`
- Test: `src/test/java/com/enterprise/llm/metrics/MetricsRecordServiceTest.java`

- [ ] **Step 1: Write the failing test for MetricsRecordService recording and timeline aggregation**
- [ ] **Step 2: Update `schema.sql` with `system_metrics` table**
- [ ] **Step 3: Implement `MetricsRecordService` with in-memory buffer and SQLite batch flush**
- [ ] **Step 4: Expose `/v1/admin/metrics/timeline` in `AdminController`**
- [ ] **Step 5: Run tests and ensure they pass**
- [ ] **Step 6: Git commit**

---

### Task 2: 内置现代化 Web 监控大盘前端页面 (HTML5 + Tailwind + ECharts)

**Files:**
- Create: `src/main/resources/static/index.html`
- Create: `src/test/java/com/enterprise/llm/metrics/DashboardStaticPageTest.java`

- [ ] **Step 1: Create `src/main/resources/static/index.html` with modern UI components:**
  - 核心状态指标卡（QPS、成功率、Token 消耗、缓存节约 Token）；
  - ECharts 延迟与 Token 时序图；
  - 租户配额占比图与拦截类型饼图；
  - Agent 任务实时监控与一键「重新拉起 (Resume)」交互区。
- [ ] **Step 2: Write test to verify static index page accessibility and 200 OK**
- [ ] **Step 3: Git commit**

---

### Task 3: Agent Task 状态机实体与 SQLite Checkpoint 存储

**Files:**
- Modify: `src/main/resources/schema.sql`
- Create: `src/main/java/com/enterprise/llm/agent/task/AgentTaskStatus.java`
- Create: `src/main/java/com/enterprise/llm/agent/task/AgentTaskEntity.java`
- Create: `src/main/java/com/enterprise/llm/agent/task/AgentTaskRepository.java`
- Test: `src/test/java/com/enterprise/llm/agent/task/AgentTaskRepositoryTest.java`

- [ ] **Step 1: Write test for saving, updating, and querying AgentTaskEntity**
- [ ] **Step 2: Add `agent_tasks` table to `schema.sql`**
- [ ] **Step 3: Implement `AgentTaskStatus`, `AgentTaskEntity`, and `AgentTaskRepository`**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Git commit**

---

### Task 4: AgentTaskManager 断点续跑与 AgentToolOrchestrator 集成

**Files:**
- Create: `src/main/java/com/enterprise/llm/agent/task/AgentTaskManager.java`
- Create: `src/main/java/com/enterprise/llm/agent/task/AgentTaskController.java`
- Modify: `src/main/java/com/enterprise/llm/agent/AgentToolOrchestrator.java`
- Test: `src/test/java/com/enterprise/llm/agent/task/AgentTaskManagerTest.java`
- Test: `src/test/java/com/enterprise/llm/agent/task/AgentTaskControllerTest.java`

- [ ] **Step 1: Write test for agent task lifecycle: start -> suspend on tool failure -> resume and complete**
- [ ] **Step 2: Implement `AgentTaskManager` and `AgentTaskController` (`/v1/agent/tasks`, `/v1/agent/tasks/{id}/resume`)**
- [ ] **Step 3: Update `AgentToolOrchestrator` to accept and update Checkpoint checkpoints**
- [ ] **Step 4: Run tests to verify all pass**
- [ ] **Step 5: Git commit**

---

### Task 5: 全量 E2E 验证与文档交付

**Files:**
- Create: `src/test/java/com/enterprise/llm/e2e/Phase5ResumptionAndDashboardE2ETest.java`
- Modify: `README.md`

- [ ] **Step 1: Write end-to-end integration test: Task runs -> fails at turn 1 -> resume succeeds at turn 2 -> metrics appear in timeline**
- [ ] **Step 2: Run `./mvnw clean verify` across all tests**
- [ ] **Step 3: Update `README.md` with UI and Resume API usage instructions**
- [ ] **Step 4: Git commit**
