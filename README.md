# LLM Enterprise Hub (企业级大模型网关与智能体调度中枢)

[![Java](https://img.shields.io/badge/Java-21%2B%20(Virtual%20Threads)-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![SQLite](https://img.shields.io/badge/SQLite-3.46-003B57?logo=sqlite&logoColor=white)](https://sqlite.org/)
[![Milvus](https://img.shields.io/badge/Milvus-2.x%20Vector%20DB-00B4D8?logo=milvus&logoColor=white)](https://milvus.io/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/Tests-163%2B%20Passed%20(100%25)-success)](#-自动化测试与质量指标)

> **LLM Enterprise Hub** 是一个面向企业级生产环境设计的高性能大模型网关与自主智能体（Agent）调度中枢。全链路基于 **Java 21 虚拟线程 (Virtual Threads)** 构建高吞吐底座，向上全面兼容 **OpenAI 标准协议**，无缝串联多租户治理、双向动态安全护栏、两级加速缓存、ReAct 工具编排与断点续跑状态机、前置 Milvus 知识库 RAG 流水线，并内置开箱即用的现代化 Web 监控运维大盘。

---

## 目录导航

- [系统架构全景](#-系统架构全景)
- [工程模块拓扑与代码目录详解](#-工程模块拓扑与代码目录详解)
- [核心能力体系剖析](#-核心能力体系剖析)
  - [1. 智能云边故障转移与熔断降级](#1-智能云边故障转移与熔断降级)
  - [2. 精细化多租户治理体系](#2-精细化多租户治理体系)
  - [3. 全链路双向安全防护网](#3-全链路双向安全防护网)
  - [4. 租户隔离智能两级缓存引擎](#4-租户隔离智能两级缓存引擎)
  - [5. 企业级 ReAct Agent 调度与断点续跑](#5-企业级-react-agent-调度与断点续跑)
  - [6. 前置 RAG 检索流水线与 Milvus 向量引擎](#6-前置-rag-检索流水线与-milvus-向量引擎)
  - [7. 零依赖内嵌现代 Web 运维监控看板](#7-零依赖内嵌现代-web-运维监控看板)
  - [8. 上下文预算管理、修剪与语义提炼 (Context Distillation)](#8-上下文预算管理修剪与语义提炼-context-distillation)
  - [9. 大厂生产级大模型异常分级体系与故障自愈](#9-大厂生产级大模型异常分级体系与故障自愈)
  - [10. 工程多模块解耦与 Spring AI 式工具化开发体验](#10-工程多模块解耦与-spring-ai-式工具化开发体验)
  - [11. 全链路 Trace 追踪与 MDC 结构化日志闭环](#11-全链路-trace-追踪与-mdc-结构化日志闭环)
  - [12. 服务端多租户会话管理体系 (Session Management SPI)](#12-服务端多租户会话管理体系-session-management-spi)
  - [13. 工作记忆 (Working Memory) 临时脱水下沉与语义按需回捞](#13-工作记忆-working-memory-临时脱水下沉与语义按需回捞)
  - [14. Java 21 虚拟线程 Tool 并发执行与租户 Scope 权限治理](#14-java-21-虚拟线程-tool-并发执行与租户-scope-权限治理)
  - [15. Prometheus / Micrometer 企业级度量体系与端点暴露](#15-prometheus--micrometer-企业级度量体系与端点暴露)
  - [16. 高并发压测基准套件与 SQLite WAL 调优](#16-高并发压测基准套件与-sqlite-wal-调优)
  - [17. 多 Agent 协同工作流引擎 (Multi-Agent Swarm / Handoff)](#17-多-agent-协同工作流引擎-multi-agent-swarm--handoff)
- [全量 RESTful API 规范与示例](#-全量-restful-api-规范与示例)
- [核心配置文件参考 (application.yml)](#-核心配置文件参考-applicationyml)
- [快速开始与环境搭建](#-快速开始与环境搭建)
- [自动化测试与质量指标](#-自动化测试与质量指标)

---

## 🏛 系统架构全景

系统完整请求生命周期及内部组件交互拓扑如下：

```mermaid
flowchart TD
    Client(["用户客户端 / 应用服务 (OpenAI SDK / cURL)"])
    
    subgraph S1["1. 网关入口接入与安全校验 (Ingress)"]
        Auth["ApiKeyAuthenticationFilter<br>(API Key 鉴权 & 租户识别)"]
        Rate["TenantRateLimiter<br>(令牌桶平滑 QPS 限流)"]
        Controller["ChatCompletionController<br>(OpenAI 兼容 REST 端点)"]
    end

    subgraph S2["2. 前置检索增强与加速 (Pre-Inference)"]
        PromptGuard["PromptGuardrailService<br>(提示词注入检测与规则引擎)"]
        RagService["RagPipelineService<br>(前置 RAG 检索增强)"]
        Milvus[("Milvus 向量数据库<br>(租户物理标量隔离 / 内存余弦索引)")]
        Cache["CacheEngine<br>(L1 Exact Hash + L2 语义相似度)"]
        Quota["TenantQuotaService & TokenEstimator<br>(BPE 配额预扣)"]
    end

    subgraph S3["3. 推理调度与智能体执行 (Execution Engine)"]
        ChatExec["ChatExecutionService<br>(调度总控)"]
        Failover["FailoverRoutingService<br>(云端主模型 / 边端本地降级 / 熔断器)"]
        Orchestrator["AgentToolOrchestrator<br>(ReAct 循环工具调用沙箱)"]
        TaskManager["AgentTaskManager<br>(SQLite 事务 Checkpoint & 现场还原)"]
        Gatekeeper["ToolActionGatekeeper<br>(危险动作拦截与跨租户隔离守门)"]
    end

    subgraph S4["4. 出口安全与可观测监控 (Egress & Observability)"]
        DLP["OutputGuardrailService<br>(敏感数据正则脱敏)"]
        QuotaSettle["TenantQuotaService<br>(真实 Token 消耗原子结算)"]
        Metrics["MetricsRecordService<br>(分钟级滑动时序聚合 & SQLite 持久化)"]
        Dashboard["内嵌 Web 控制看板<br>(Tailwind CSS + ECharts)"]
        AdminAPI["AdminController<br>(租户管理与运营大盘接口)"]
    end

    %% 流程连接
    Client --> Auth --> Rate --> Controller
    Controller --> PromptGuard --> RagService
    RagService <-->|向量检索| Milvus
    RagService --> Cache
    Cache -- 命中返回 (0 Token) --> Client
    Cache -- 未命中 --> Quota --> ChatExec
    ChatExec --> Failover
    ChatExec -. 携带 tools .-> Orchestrator
    Orchestrator <--> Gatekeeper
    Orchestrator <--> TaskManager
    Failover --> DLP --> QuotaSettle --> Metrics --> Client
    Metrics --> Dashboard
    Metrics --> AdminAPI
```

---

## 📂 工程模块拓扑与代码目录详解

项目基于 Maven 多模块 (Multi-Module Reactor) 架构分层设计，实现**纯领域模型、Spring Boot Starter 自动装配、独立网关服务与业务集成范例**的彻底解耦：

```text
llm-enterprise-hub/                      # Maven 聚合根工程 (pom 打包)
├── llm-enterprise-core/                 # 核心领域模型与算法纯净库 (零 Web 依赖，纯 POJO)
│   └── src/main/java/com/enterprise/llm/
│       ├── agent/                       # ReAct 工具编排、ToolRegistry、@LlmTool 注解定义与 Task SPI
│       ├── cache/                       # L1 Hash + L2 语义两级缓存引擎
│       ├── client/                      # LlmClient、LlmErrorParser 上游统一调用层
│       ├── context/                     # 上下文预算管理、修剪与语义提炼 (ContextManager)
│       ├── exception/                   # 大模型强类型异常分类 (可重试/阻断/自愈)
│       ├── guard/                       # 出口 DLP 脱敏、入口注入防御、工具越权沙箱
│       ├── model/                       # OpenAI 协议兼容标准 DTO/VO (ChatMessage, ToolCall 等)
│       ├── quota/                       # 租户配额原子预扣与结算 (TenantQuotaService)
│       ├── rag/                         # 语义分块、向量化与 Milvus 存储抽象
│       ├── ratelimit/                   # 租户级令牌桶平滑限流 (TenantRateLimiter)
│       ├── router/                      # 云边故障转移、指数退避重试与熔断器 (FailoverRoutingService)
│       └── tenant/                      # 租户领域模型与存储 SPI (TenantStorageProvider)
│
├── llm-enterprise-spring-boot-starter/  # 开发者体验与自动配置中枢 (对标 Spring AI)
│   └── src/main/java/com/enterprise/llm/starter/
│       ├── EnableLlmEnterprise.java     # 一键开启企业级模型能力注解
│       ├── LlmAutoConfiguration.java    # 全局组件条件化自动装配 (@ConditionalOnMissingBean)
│       └── annotation/
│           └── LlmToolBeanPostProcessor.java # 自动反射扫描 @LlmTool 生成 JSON Schema 并注册
│
├── llm-enterprise-gateway-server/       # 开箱即用的独立网关服务 (生产可执行程序)
│   └── src/main/java/com/enterprise/llm/
│       ├── LlmEnterpriseHubApplication.java # Spring Boot 启动主类
│       ├── admin/                       # 运营大盘与租户管理控制面 (/v1/admin/*)
│       ├── auth/                        # ApiKeyAuthenticationFilter 鉴权过滤器
│       ├── controller/                  # ChatCompletionController (/v1/chat/completions)
│       ├── metrics/                     # 分钟级滑动窗口时序指标与 SQLite 持久化
│       ├── repository/                  # SQLite 租户表与 Agent 任务表存储实现
│       └── resources/static/index.html  # 零依赖现代 Web 运维监控大盘
│
└── samples/sample-business-app/         # 独立业务微服务集成范例
    └── src/main/java/com/sample/app/
        ├── SampleBusinessApplication.java # 标注 @EnableLlmEnterprise 即可注入一切模型能力
        ├── service/OrderService.java     # 业务代码直接标注 @LlmTool 自动接入智能体
        └── controller/DemoAssistantController.java # 验证直接调用工具与上下文提炼
```

---

## ⚡ 核心能力体系剖析

### 1. 智能云边故障转移与熔断降级
- **双引擎热备**：通过 `FailoverRoutingService` 配置主模型（如云端商用大模型）与备用模型（如本地部署的 Ollama `qwen2.5:0.5b`）。
- **智能熔断状态机**：
  - 当主节点连续故障达到阈值（如连续 3 次超时或 5xx 错误），熔断器立即置为 **OPEN** 状态；
  - 触发熔断后，后续请求 **零延迟旁路直通** 本地备用节点，避免网络阻塞；
  - 经过静默窗口后自动转为 **HALF-OPEN** 状态，放行探活请求，探活成功自动复位为 **CLOSED**。
- **SSE 流式无感容灾**：流式推送在握手阶段若探测到上游不可用，自动无缝重连备用模型流式通道，保障前端用户无白屏感知。

### 2. 精细化多租户治理体系
- **基于 SQLite 的租户元数据持久化**：开箱自带 `tenants` 表，独立记录租户 ID、API 密钥、启用状态、QPS 限制、每日 Token 限额与累计使用量。
- **令牌桶 (Token Bucket) 平滑限流**：`TenantRateLimiter` 在内存中为每个租户维护独立的平滑令牌桶，超出租户 QPS 阈值时立即返回 `429 Too Many Requests`。
- **两阶段 BPE 配额预扣与结算**：
  1. **Pre-flight 预扣 (`tryReserve`)**：根据用户 Prompt 与 `max_tokens` 通过 BPE 分词器精确预估本次请求最大 Token 消耗；若租户当日余额不足，在向 LLM 发起请求前直接拒绝，**彻底杜绝模型穿透超用与盗刷**；
  2. **Post-inference 结算 (`settle`)**：请求完成后，依据模型真实返回的 `Usage` 多退少补，精确扣减租户每日配额。

### 3. 全链路双向安全防护网
- **入口提示词注入防护 (`PromptGuardrailService`)**：
  - 基于可插拔规则引擎，内置阻止 DAN 越狱、提示词窃取、密码诱导及系统覆盖的敏感正则；
  - 支持企业在 `application.yml` 的 `security.guardrail.prompt.patterns` 中随意扩展规则，亦支持运行时动态调用 `addDynamicPattern()`。
- **出口敏感数据动态脱敏 (`OutputGuardrailService`)**：
  - 支持 JWT Token、API Key、AWS AK、11位中国手机号、企业邮箱等敏感模式识别；
  - 支持 `REPLACE_TAG`（整串置换为 `[REDACTED_*]`）与 `MASK_MIDDLE`（中间部分掩码）两种脱敏策略。
- **Agent 工具安全沙箱双重守门 (`ToolActionGatekeeper`)**：
  - **指令安全评级**：阻断 `rm -rf`、`DROP TABLE`、`format`、`mkfs`、`shutdown` 等高危破坏性系统指令；
  - **跨租户物理阻断**：严格比对工具参数中的租户标识字段（`tenant_id`, `org_id`, `account_id` 等），若调用参数中包含非当前会话租户的标识，立即阻断并抛出 `CrossTenantViolationException`。

### 4. 租户隔离智能两级缓存引擎
- **L1 精准 Hash 缓存**：基于 SHA-256 对 `tenant_id`、`model`、`temperature` 及消息序列化指纹进行哈希索引，亚毫秒级（`< 0.5ms`）极速响应，响应头携带 `X-Cache-Lookup: HIT`，**0 Token 消耗**。
- **L2 语义相似度缓存**：基于 Jaccard 分词重合度计算语义相似度，针对相同租户下的同义表达在达标阈值（默认 0.88）时自动命中。
- **多租户安全隔离命名空间**：无论是 L1 还是 L2，缓存内部强制绑定 `tenant_id`，**彻底杜绝跨租户语义命中导致的机密数据越权泄漏**。
- **流式 SSE 模拟回放**：当流式请求命中缓存时，由 Java 21 虚拟线程平滑分批吐出打字机 chunk 并正常发送 `[DONE]`，保持与真实推理完全一致的前端视觉体验。

### 5. 企业级 ReAct Agent 调度与断点续跑
- **标准化 Tool Calling 支持**：全面兼容 OpenAI 的 `tools`、`tool_calls` 与 `tool_call_id`。
- **ReAct 自主调度闭环**：当模型返回 `tool_calls` 时，`AgentToolOrchestrator` 自动调度执行沙箱工具，将执行结果包装为 `role: tool` 回填到上下文并继续向模型发起下一轮推理，直至输出最终回答或达到最大迭代轮次（默认 5 轮）。
- **任务状态机与 SQLite Checkpoint 事务持久化**：
  - 核心状态流转：`PENDING` -> `RUNNING` -> [成功] `COMPLETED` / [异常/超时] `SUSPENDED` -> [人工/调度重新拉起] `RUNNING`。
  - 每一轮工具调用完成后，自动将当前轮次 `current_iteration` 与全量历史 `history_json` 持久化入库。
- **现场还原与跳步断点续跑**：
  - 调用 `POST /v1/agent/tasks/{id}/resume` 即可从最后一个 Checkpoint 重新拉起任务，直接从中断点发起下一轮推理，跳过已成功的历史工具执行，避免重复消耗 Token 与副作用。

### 6. 前置 RAG 检索流水线与 Milvus 向量引擎
- **网关前置透明注入**：在 `ChatCompletionController` 前置截获请求，自动提取最后一条用户 Prompt，批量向量化后向向量数据库发起相似度搜索，将匹配到的 Top-K 切片（附相似度阈值过滤）以标准 Markdown 结构化自动注入 System Prompt。
- **预扣联动**：RAG 注入所增加的 System Prompt 长度会自动计入 BPE 预估流水，确保租户额度计算 100% 准确。
- **直连 Milvus 2.x RESTful API (`MilvusVectorStore`)**：
  - 原生直连企业级 Milvus 向量数据库集群，调用 `/v2/vectordb/entities/insert` 与 `/v2/vectordb/entities/search`；
  - **强制标量隔离**：检索时强制注入 `tenant_id == "${tenantId}"` 过滤表达式，实现真正的多租户数据物理隔离；
  - **智能无感降级**：当未配置 Milvus 集群或配置 `milvus.endpoint=disabled` 时，底层自动无缝激活高精度内存余弦相似度索引，单机开发或离线 CI 测试零依赖运行。

### 7. 零依赖内嵌现代 Web 运维监控看板
- **即开即用**：无需安装 Node.js、Nginx 或额外部署前端项目，服务启动后直接通过浏览器访问 `http://localhost:8080/` 即可直达。
- **极速响应与动态刷新**：页面采用 **Tailwind CSS + Apache ECharts** 极速渲染，支持每 5 秒自动动态轮询：
  - 活跃租户数、累计已消耗 Tokens、缓存节省 Tokens、缓存命中率统计卡片；
  - 系统吞吐量 (RPM) 与平均延迟趋势双轴时序折线图（按分钟滑动窗口聚合）；
  - 缓存与安全拦截分布饼图；
  - ReAct Agent 任务调度控制台（实时展示任务状态机流转，支持在任务挂起时一键交互触发断点恢复）。

### 8. 上下文预算管理、修剪与语义提炼 (Context Distillation)
- **增量治理水位线 (High-Water Mark)**：通过 `ContextBudgetProperties` 设定窗口上限（如 8192 或 32768）与触发比率（默认 0.75）。当 `currentTokens + reservedTokens > maxTokens * ratio` 时，启动无感三阶段压缩，彻底避免长会话 Token 消耗呈二次方爆炸与突破大模型硬上限。
- **三阶段递进治理流水线**：
  1. **Phase 1: 工具冗余输出修剪 (`pruneOldToolMessages`)**：超过两轮以上的历史 `role: tool` 消息，若字符长度超过阈值自动折叠为摘要标签 `{"status": "success", "summary": "[Earlier tool output pruned for context token efficiency, length: N chars]"}`，节省 70%+ 工具大 JSON 消耗；
  2. **Phase 2: 智能滑动窗口保活 (`applySlidingWindow`)**：遵循“System 角色定义首因效应”与“最新上下文近因效应”双重保护守则，**绝对保留所有 System 提示词与最新的 K 轮对话（默认 4 条）**，只对中间陈旧轮次执行滑出；
  3. **Phase 3: 语义提炼与摘要注入 (`distillAndSummarize`)**：通过 `HeuristicContextDistiller`（支持插拔大模型异步提炼）将滑出丢弃的历史消息提炼为紧凑的结构化备忘录：`ChatMessage.system("【前序会话历史提炼摘要与背景备忘】\n- [USER]: ...\n- [ASSISTANT]: ...")`，在极致节省 Token 的同时完整保留会话意图与核心事实。

### 9. 大厂生产级大模型异常分级体系与故障自愈
- **强类型异常分级类图 (`com.enterprise.llm.exception.*`)**：
  - **可重试抖动异常 (Retryable)**：`LlmRateLimitException`（HTTP 429）、`LlmServerException`（HTTP 500/502/503/504、超时）；
  - **致命阻断异常 (Fatal/Non-retryable)**：`LlmAuthenticationException`（401/403 凭据失效）、`LlmContentFilterException`（400 内容安全违规拦截）、`LlmInvalidRequestException`（400 请求格式与参数校验）；
  - **现场可自愈异常 (Self-Healing)**：`LlmContextLengthExceededException`（400 模型上下文爆窗）。
- **上游 HTTP 错误响应语义解析器 (`LlmErrorParser`)**：深度解析上游 OpenAI、Azure、Ollama 等供应商标准的 `{ "error": { "message": "...", "type": "...", "code": "..." } }` 格式，自动识别错误语义并精准派生强类型异常。
- **动态指数退避重试 (Exponential Backoff with Jitter)**：命中 429 限流时，自动解析 `Retry-After` 或 `retry-after-ms` 响应头，在 Java 21 虚拟线程内执行带抖动的指数退避重试，平滑削峰。
- **400 爆窗紧急压缩现场自愈 (`Emergency Compaction Recovery`)**：当主模型返回 `context_length_exceeded` 异常时，网关不盲目重试相同载荷，而是立即调用 `ContextManager.forceEmergencyCompaction(request)` 触发紧急折叠并就地原位重试，彻底解决突发大 Prompt 导致的爆窗宕机。
- **安全快速失败与标准化 OpenAI 错误输出**：遇到 401 凭证失效或 400 内容安全违规时，阻断向 Fallback 本地模型切换，通过 `ChatCompletionController` 统一以标准 OpenAI 错误 JSON（包含 `message`、`type`、`code`）和正确 HTTP 状态码返回给客户端，绝不向外暴露 Java 异常堆栈。

### 10. 工程多模块解耦与 Spring AI 式工具化开发体验
为了支持企业级灵活定制开发、自主迁移与轻量嵌入，系统全面完成了三层 Maven 架构解耦，提供对标 **Spring AI** 的开箱即用开发体验：

#### 模块定位与职责分工
| 模块名 | 类型 | 职责范围 | 核心依赖 |
| :--- | :--- | :--- | :--- |
| **`llm-enterprise-core`** | 纯领域内核 | 领域模型、长上下文预算与提炼、两级缓存、故障转移路由、ReAct 调度器、安全护栏、Token 计算与存储 SPI 抽象 | 零 Web 依赖、纯 POJO |
| **`llm-enterprise-spring-boot-starter`** | 自动装配 SDK | `@EnableLlmEnterprise`、`@LlmTool` 注解扫描器、`LlmAutoConfiguration`、模型客户端自动配置 | Spring Boot 自动装配中枢 |
| **`llm-enterprise-gateway-server`** | 独立网关服务 | OpenAI 兼容 REST 端点、多租户 SQLite 鉴权与配额持久化、Web 实时监控看板 | 可直接部署的生产微服务 |
| **`samples/sample-business-app`** | 业务接入示例 | 演示外部微服务引入 Starter、使用 `@LlmTool` 声明业务方法、自动注册到工具池与 ReAct 调度的完整流程 | 业务开发最佳范例 |

#### `@LlmTool` 注解驱动函数调用开发范式
外部业务应用无需编写冗长复杂的 OpenAI Function Calling JSON Schema，只需在任意 Spring Bean 的方法上标注 `@LlmTool` 与 `@LlmToolParam`，即可在应用启动时由 `LlmToolBeanPostProcessor` 自动反射识别、生成参数 Schema 并注册到全局 `ToolRegistry`：

```java
@Service
public class OrderService {

    @LlmTool(name = "query_order_status", description = "查询企业订单物流、承运商与支付状态")
    public Map<String, Object> queryOrderStatus(
            @LlmToolParam(name = "orderId", description = "订单唯一标识号 (如 ORD-1001)", required = true)
            String orderId
    ) {
        return Map.of("orderId", orderId, "status", "IN_TRANSIT", "carrier", "SF-Express");
    }
}
```

#### 外部微服务接入三步法
1. **引入依赖**：
   ```xml
   <dependency>
       <groupId>com.enterprise.llm</groupId>
       <artifactId>llm-enterprise-spring-boot-starter</artifactId>
       <version>0.0.1-SNAPSHOT</version>
   </dependency>
   ```
2. **开启注解**：在主类标注 `@SpringBootApplication` 与 `@EnableLlmEnterprise`（或依赖 Spring Boot 标准自动装配）。
3. **注入即用**：直接注入 `ToolRegistry`、`AgentToolOrchestrator`、`LlmClient` 或 `ContextManager`，开箱即享企业级大模型调度与安全治理能力。


---

### 11. 全链路 Trace 追踪与 MDC 结构化日志闭环

在分布式生产环境下，大模型的长耗时与多步骤调用必须具备毫秒级可观测性。系统构建了标准的分布式追踪机制：
- **`TraceContext` 与 MDC 绑定**：入口通过 `TraceFilter`（Order 0）自动提取或生成 `X-Trace-Id`、`X-Session-Id`，并在鉴权后富化 `tenantId`，直接同步至 SLF4J MDC。网关所有日志自动带有 `[%X{traceId:-}][%X{tenantId:-}]` 前缀。
- **虚拟线程上下文自闭环**：在 `TraceFilter` 的 `finally` 块彻底清除 ThreadLocal 与 MDC，避免虚拟线程池化引发的上下文泄漏与串流。
- **细粒度 `LlmSpan` 性能度量**：记录 `ttftMs`（首字生成延迟 Time To First Token）、`totalLatencyMs`、`tokensPerSecond` (TPS)、`promptTokens`、`completionTokens`，全面覆盖流式 SSE 和同步调用。
- **结构化请求审计输出 (`RequestAuditLogger`)**：单次请求无论成功、降级还是安全拦截，均输出单行独立 JSON 格式的标准审计日志，可直接对接到 ELK / Loki 等日志采集系统；支持导出为 Langfuse 兼容格式。

---

### 12. 服务端多租户会话管理体系 (Session Management SPI)

原生 OpenAI 接口是无状态协议，导致前端客户端必须在每次请求中携带全部历史消息，极易产生流量冗余与历史数据篡改风险。
- **`SessionStorageProvider` 存储 SPI**：解耦核心层存储抽象，支持 `InMemorySessionStorageProvider` 与基于 SQLite 的 `SqliteSessionStorageProvider` 持久化适配。
- **透明对话上下文接管 (`SessionManager`)**：
  - 客户端请求仅需在 Body 中传入 `"session_id": "sess-xxxx"` 和最新的用户问题；
  - 服务端自动从 SQLite 加载该租户的历史会话并完成上下文拼接（自动保留 system prompt）；
  - 推理完成或流式推送结束时，自动将当前轮次的问答对持久化至 `chat_session_messages` 表；
  - 兼容客户端全量回传模式：如果检测到客户端已包含历史记录，自动去重不产生重复拼接。
- **RESTful 会话管理能力 (`/v1/sessions`)**：支持租户级别的会话列表分页查询、指定会话全量历史拉取以及会话级联物理清除。

---

### 13. 工作记忆 (Working Memory) 临时脱水下沉与语义按需回捞

在长程智能体与复杂业务对话中，工具调用的中间数据（如长表格、几百行的 SQL 查询结果、大篇幅接口响应）会迅速占满大模型的上下文窗口，推高推理成本并导致模型注意力分散：
- **大结果脱水下沉 (Offload to Working Memory)**：
  当工具执行结果或历史轮次文本超过阈值（如 > 300 字符），或触发上下文预算压缩时，原始明细自动下沉存入 `WorkingMemoryManager` 内存暂存区，在上下文消息中替换为轻量级引用存根：
  `[WorkingMemoryRef: id="mem-xxxx" summary="Earlier tool output (622 chars)"]`
- **语义相关性按需回捞 (Semantic Rehydrate Recall)**：
  后续轮次中，当用户针对先前已脱水的内容发起追问（如“刚刚查出的那个订单金额是多少？”）时，`WorkingMemoryManager` 基于关键词停用词重合度（或 `EmbeddingClient` 余弦相似度）精准判定相关性，将对应的原始明细自动回捞唤醒并还原至输入上下文中，实现“大容量暂存，按需精确唤醒”。

---

### 14. Java 21 虚拟线程 Tool 并发执行与租户 Scope 权限治理

- **虚拟线程并发执行多工具调用 (Parallel Tool Calling)**：
  当大模型在单轮决策中输出多个并行 `tool_calls`（例如同时查询三个城市的天气、或同时调用库存与汇率接口），`AgentToolOrchestrator` 自动调度 Java 21 虚拟线程（`newVirtualThreadPerTaskExecutor`）进行并发异步 I/O 执行，大幅将串行累加耗时压缩至单次最长调用的耗时，并保证结果严格按原序号保序追加。
- **租户粒度与 Scope 细粒度权限控制**：
  在 `@LlmTool` 注解中新增 `allowedTenants` 与 `scopes` 声明属性。例如：
  ```java
  @LlmTool(name = "vip_pricing_engine", allowedTenants = {"tenant-vip"}, scopes = {"vip:pricing"})
  public String calculateVipDiscount(String customerId) { ... }
  ```
  未被授权的租户无论在工具元数据发现阶段（下发模型 `tools` 列表）还是在运行时沙箱调用阶段均被严格屏蔽拦截，杜绝特权工具越权调用风险。

---

### 15. Prometheus / Micrometer 企业级度量体系与端点暴露

为了满足大厂生产环境对 SLA、延迟分布、Token 吞吐及工具性能的实时监控，系统构建了指标度量闭环：
- **Core 层零框架依赖 SPI (`LlmMetricsCollector`)**：
  在核心层抽象出高性能度量接口，预置 `NoOpMetricsCollector`（零开销缺省）与 `CompositeMetricsCollector`（多路广播）。
- **Spring Boot Starter / Gateway 自动装配**：
  引入 `micrometer-registry-prometheus` 与 `spring-boot-starter-actuator`，自动将 `LlmSpan` 和 `AgentToolOrchestrator` 生命周期事件转换为标准 Micrometer 指标，并通过 `/actuator/prometheus` 端点向 Prometheus / Grafana 暴露。

**标准监控指标字典**：

| 指标名称 | 类型 | 标签 (Tags) | 说明与 SLA 分位 |
| :--- | :--- | :--- | :--- |
| `llm_requests_total` | Counter | `model`, `tenant`, `status` | 累计模型推理请求次数 |
| `llm_request_duration_seconds` | Summary/Timer | `model`, `tenant` | 端到端请求总耗时（P50/P90/P99 分位发布） |
| `llm_time_to_first_token_seconds` | Summary/Timer | `model`, `tenant` | 流式首字到达耗时 (TTFT)，监控用户交互灵敏度 |
| `llm_tokens_per_second` | Summary | `model` | 实时 Token 生成吞吐速率 (TPS) |
| `llm_tokens_total` | Counter | `model`, `tenant`, `type` (`prompt`/`completion`) | 累计消耗 Token 量（支持财务核算与分级计费） |
| `llm_tool_duration_seconds` | Timer | `tool`, `tenant`, `status` | 外部 Tool/函数执行延迟统计 |
| `llm_active_requests` | Gauge | `model` | 当前在途正在处理中的并发请求水位 |

---

### 16. 高并发压测基准套件与 SQLite WAL 调优

在面对高吞吐线上流量时，系统针对虚拟线程调度与 SQLite 嵌入式持久化进行了极致并发调优：
- **SQLite 并发写入瓶颈彻底攻克**：
  配置 `PRAGMA journal_mode = WAL;`、`PRAGMA busy_timeout = 5000;` 与 `PRAGMA synchronous = NORMAL;` 开启读写分离。在 `SqliteSessionStorageProvider` 中引入 JVM 级 `ReentrantLock` 锁机制，彻底避免了 50+ 虚拟线程并发写库时操作系统级文件锁自旋导致的 HikariCP 线程池饥饿与锁超时。
- **并发性能基准验证报告 (`ConcurrencyBenchmarkSuiteTest`)**：
  - **Tool 虚拟线程并发加速比**：模拟 8 个典型耗时 40ms 的外部 I/O 工具，串行耗时 370ms，虚拟线程调度耗时仅 **91ms**，**加速比达到 4.07x**；
  - **SQLite WAL 并发持久化吞吐**：50 虚拟线程并发写入 100 组独立会话与消息，耗时仅 105ms，**吞吐量达到 952.38 ops/sec**，**成功率 100%（0 错误）**，P50 延迟 81ms，P99 延迟 100ms。

---

### 17. 多 Agent 协同工作流引擎 (Multi-Agent Swarm / Handoff)

为了支持复杂的跨领域协同场景，系统在 `llm-enterprise-core` 中内置了自主智能体工作流引擎（参考 OpenAI Swarm 与工业界 Multi-Agent 架构设计）：
- **`AgentRole`（智能体角色）**：支持自主声明 Agent 职责、专有 System Prompt、配备专属 Tool 集合及允许转交的下游目标智能体列表；
- **`AgentHandoff`（动态委托转交）**：当当前 Agent 判断任务超出其职责范畴时，大模型自动调用合成转交工具 `transfer_to_<AgentName>`，将控制权平滑移交，并更新共享黑板；
- **`AgentSharedContext`（跨智能体共享黑板）**：线程安全的状态机存储，在整个 Swarm 协作生命周期中传递业务参数（如订单号、鉴权凭据、中间计算结果）；
- **`AgentSwarmOrchestrator`（总控引擎）**：
  驱动会话迭代，自动为当前激活的 Agent 挂载其合法转交动作，智能防止死循环转交（默认 `maxHandoffTurns = 5`）；
- **智能客服真实场景实战 (`sample-business-app`)**：
  已在样例工程完整落地 `TriageAgent`（工单分诊接待）依据用户问答自动无感转交给 `LogisticsAgent`（物流专家），调用 `@LlmTool` 查询快递状态并最终给出综合解答的端到端协作闭环。

---

## 🔌 全量 RESTful API 规范与示例

所有业务接口均需通过 HTTP Header 提供租户 API Key（`Authorization: Bearer <key>` 或 `X-API-Key: <key>`），管理员接口需提供 `X-Admin-Key: <admin_key>`。

### 1. 对话补全接口 (`POST /v1/chat/completions`)

标准同步请求：
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-tenant-alpha-key" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen2.5:0.5b",
    "messages": [
      {"role": "user", "content": "你好，请介绍一下你自己。"}
    ],
    "temperature": 0.7,
    "stream": false
  }'
```

SSE 打字机流式请求：
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-tenant-alpha-key" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen2.5:0.5b",
    "messages": [
      {"role": "user", "content": "用一首诗描述春天的美景。"}
    ],
    "stream": true
  }'
```

携带工具自动触发 ReAct 调度循环：
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-tenant-alpha-key" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen2.5:0.5b",
    "messages": [
      {"role": "user", "content": "帮我查询杭州明天的天气情况。"}
    ],
    "tools": [
      {
        "type": "function",
        "function": {
          "name": "get_weather",
          "description": "获取指定城市的天气预报",
          "parameters": {
            "type": "object",
            "properties": {
              "city": {"type": "string", "description": "城市名，如杭州"}
            },
            "required": ["city"]
          }
        }
      }
    ]
  }'
```

### 2. 知识库 RAG 控制面接口

上传文档并自动切片向量化入库 (`POST /v1/knowledge/documents`)：
```bash
curl -X POST http://localhost:8080/v1/knowledge/documents \
  -H "Authorization: Bearer sk-tenant-alpha-key" \
  -H "Content-Type: application/json" \
  -d '{
    "docName": "company_handbook.md",
    "content": "本项目核心代号为 Aurora，由架构团队主导，每周五下午进行跨部门技术评审。",
    "chunkSize": 300,
    "chunkOverlap": 50
  }'
```

知识库语义相似度检索测试 (`POST /v1/knowledge/search`)：
```bash
curl -X POST http://localhost:8080/v1/knowledge/search \
  -H "Authorization: Bearer sk-tenant-alpha-key" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "项目的代号叫什么？",
    "topK": 3,
    "minScore": 0.5
  }'
```

级联删除租户指定文档 (`DELETE /v1/knowledge/documents/{docName}`)：
```bash
curl -X DELETE http://localhost:8080/v1/knowledge/documents/company_handbook.md \
  -H "Authorization: Bearer sk-tenant-alpha-key"
```

### 3. Agent 任务管理控制面接口 (需管理员鉴权)

获取所有 Agent 任务列表 (`GET /v1/agent/tasks`)：
```bash
curl -X GET http://localhost:8080/v1/agent/tasks \
  -H "X-Admin-Key: test-admin-secret-key-12345"
```

一键断点续跑挂起任务 (`POST /v1/agent/tasks/{taskId}/resume`)：
```bash
curl -X POST http://localhost:8080/v1/agent/tasks/task-a1b2c3d4/resume \
  -H "X-Admin-Key: test-admin-secret-key-12345"
```

### 4. 运营大盘与监控时序接口 (需管理员鉴权)

获取全站运营聚合概况 (`GET /v1/admin/analytics/overview`)：
```bash
curl -X GET http://localhost:8080/v1/admin/analytics/overview \
  -H "X-Admin-Key: test-admin-secret-key-12345"
```

获取近 60 分钟时序监控细粒度指标 (`GET /v1/admin/metrics/timeline`)：
```bash
curl -X GET "http://localhost:8080/v1/admin/metrics/timeline?minutes=60" \
  -H "X-Admin-Key: test-admin-secret-key-12345"
```

### 5. 服务端多租户会话管理接口

查询当前租户会话列表 (`GET /v1/sessions?limit=20&offset=0`)：
```bash
curl -X GET http://localhost:8080/v1/sessions \
  -H "Authorization: Bearer sk-tenant-alpha-key"
```

拉取指定会话完整消息流水 (`GET /v1/sessions/{sessionId}`)：
```bash
curl -X GET http://localhost:8080/v1/sessions/sess-101 \
  -H "Authorization: Bearer sk-tenant-alpha-key"
```

删除会话及其历史数据 (`DELETE /v1/sessions/{sessionId}`)：
```bash
curl -X DELETE http://localhost:8080/v1/sessions/sess-101 \
  -H "Authorization: Bearer sk-tenant-alpha-key"
```

---

## ⚙ 核心配置文件参考 (application.yml)

所有参数均已集中在 `src/main/resources/application.yml` 中，生产环境可通过环境变量或外置配置无缝覆盖：

```yaml
spring:
  application:
    name: llm-enterprise-hub
  threads:
    virtual:
      enabled: true                  # 启用 Java 21 虚拟线程支持高并发吞吐
  datasource:
    url: jdbc:sqlite:hub-data.db     # SQLite 本地持久化路径
    driver-class-name: org.sqlite.JDBC
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql

server:
  port: 8080

llm:
  provider:
    base-url: "http://localhost:11434/v1"   # 主模型端点 (Ollama / OpenAI / 云端)
    api-key: "ollama"
    model: "qwen2.5:0.5b"
    timeout-seconds: 60
  fallback:
    base-url: "http://localhost:11434/v1"   # 熔断降级端点
    api-key: "ollama"
    model: "qwen2.5:0.5b"

security:
  guardrail:
    prompt:
      enabled: true                         # 提示词注入防护开关
      patterns:
        - "(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions"
        - "(?i)disregard\\s+(all\\s+)?(previous|prior|above)\\s+instructions"
        - "(?i)you\\s+are\\s+now\\s+in\\s+dan\\s+mode"
        - "(?i)reveal\\s+your\\s+(system\\s+prompt|instructions)"
        - "(?i)jailbreak"
  dlp:
    enabled: true                          # 出口数据脱敏开关
  tools:
    enabled: true                          # 工具沙箱守门开关
    tenant-isolation-enabled: true         # 强制工具动作租户隔离

cache:
  enabled: true                            # 两级缓存总开关
  ttl-seconds: 3600                        # 缓存有效期 (秒)
  semantic-threshold: 0.88                 # L2 语义相似度判定阈值
  max-capacity: 10000

rag:
  enabled: true                            # RAG 前置增强总开关
  top-k: 3                                 # 默认召回切片数
  min-score: 0.5                           # 召回最低余弦相似度
  embedding:
    base-url: "http://localhost:11434/v1"  # 向量化端点
    api-key: "ollama"
    model: "bge-m3"

milvus:
  endpoint: "http://127.0.0.1:19530"       # Milvus 服务地址 (若设为 disabled 则自动走内存余弦索引)
  collection: "enterprise_hub_rag"
  api-key: ""

admin:
  secret-key: "test-admin-secret-key-12345" # 控制面与大盘鉴权密钥
```

---

## 🚀 快速开始与环境搭建

### 1. 环境准备
- **JDK 21+**（推荐 Eclipse Temurin / Amazon Corretto 21）；
- **Maven 3.9+**（项目自带 `./mvnw` 包装器）；
- **Docker / Docker Compose**（用于一键拉起 Milvus 与 Ollama 支撑服务，可选）。

### 2. 外部依赖编排 (可选一键拉起)
项目支持单机离线运行（向量库自动回退到内存余弦索引）。若需直连真实 Milvus 集群，可在根目录通过 Docker Compose 启动：

```yaml
# docker-compose.yml 示例
version: '3.8'
services:
  etcd:
    image: quay.io/coreos/etcd:v3.5.5
    environment:
      - ETCD_AUTO_COMPACTION_MODE=revision
      - ETCD_AUTO_COMPACTION_RETENTION=1000
      - ETCD_QUOTA_BACKEND_BYTES=4294967296
    volumes:
      - ./volumes/etcd:/etcd
    command: etcd -advertise-client-urls=http://127.0.0.1:2379 -listen-client-urls=http://0.0.0.0:2379 --data-dir=/etcd

  minio:
    image: minio/minio:RELEASE.2023-03-20T20-16-18Z
    environment:
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    volumes:
      - ./volumes/minio:/minio_data
    command: minio server /minio_data

  milvus-standalone:
    image: milvusdb/milvus:v2.4.0
    command: ["milvus", "run", "standalone"]
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: minio:9000
    volumes:
      - ./volumes/milvus:/var/lib/milvus
    ports:
      - "19530:19530"
      - "9091:9091"
    depends_on:
      - "etcd"
      - "minio"
```

启动依赖：
```bash
docker compose up -d
```

### 3. 本地编译与启动
在 `llm-enterprise-hub` 根目录下执行：

```bash
# 1. 运行全量测试并打包所有模块 Jar
./mvnw clean package -DskipTests=false

# 2. 启动独立网关应用程序 (默认端口 8080)
java -jar llm-enterprise-gateway-server/target/llm-enterprise-gateway-server-0.0.1-SNAPSHOT.jar

# 3. (可选) 启动示例业务微服务体验 @LlmTool (默认端口 8089)
java -jar samples/sample-business-app/target/sample-business-app-0.0.1-SNAPSHOT.jar
```

服务启动成功后：
- 打开浏览器访问 **`http://localhost:8080/`** 即可直接体验 **实时监控大盘与 Agent 调度中心**；
- 默认已初始化预置测试租户，API Key 格式如 `sk-alpha-secret` 等；
- 管理员默认密钥为 `test-admin-secret-key-12345`。

---

## 🧪 自动化测试与质量指标

项目包含极度严苛的单元测试与端到端 (E2E) 集成测试套件，全面覆盖：
- 租户鉴权与令牌桶平滑限流 (`TenantRateLimiterTest`, `ApiKeyAuthenticationFilterTest`)；
- BPE Token 预估与每日额度原子预扣/结算 (`TokenEstimatorTest`, `TenantQuotaServiceTest`, `ChatQuotaIntegrationTest`)；
- 提示词注入可配置规则防护与敏感数据脱敏 (`PromptGuardrailTest`, `OutputGuardrailServiceTest`)；
- 两级缓存命中、语义相似度、缓存过期与多租户隔离 (`CacheEngineTest`, `ChatCacheIntegrationTest`)；
- ReAct 模式多轮工具编排调度与沙箱越权拦截 (`AgentToolOrchestratorTest`, `ToolActionGatekeeperTest`)；
- Agent 长程任务状态机与断点续跑现场恢复 (`AgentTaskManagerTest`, `AgentTaskControllerTest`, `Phase5ResumptionAndDashboardE2ETest`)；
- 前置 RAG 检索增强流水线与 Milvus 多租户隔离验证 (`TextChunkerTest`, `MilvusVectorStoreTest`, `KnowledgeBaseControllerTest`, `Phase6MilvusRagE2ETest`)；
- 长会话上下文预算管理、工具消息修剪与启发式语义提炼 (`ContextManagerTest`)；
- 大厂强类型大模型异常分级解析器 (`LlmErrorParserTest`)；
- 429 指数退避重试、400 爆窗现场自愈与致命错误快速阻断 (`FailoverRoutingResilienceTest`)；
- 端到端上下文自动压缩与故障自愈全链路 E2E 验证 (`ContextCompactionAndResilienceE2ETest`)；
- `@LlmTool` 注解反射扫描与 JSON Schema 自动化生成测试 (`LlmToolAnnotationScanTest`)；
- 独立业务微服务引入 Starter、工具自动装配与 ReAct 闭环端到端验证 (`SampleBusinessAppTest`)；
- 全链路 TraceContext 传递、MDC 绑定与单行标准 JSON 审计日志打点 (`TraceContextAndAuditTest`, `TraceFilterAndMdcIntegrationTest`)；
- 服务端多租户会话管理 SPI、会话上下文自动拼接与 REST 接口 (`SessionManagerTest`, `SessionManagementIntegrationTest`)；
- 工作记忆 (Working Memory) 大结果脱水下沉与语义相关性按需回捞 (`WorkingMemoryManagerTest`, `WorkingMemoryContextCompactionIntegrationTest`)；
- Java 21 虚拟线程 Tool 并发执行性能与租户 Scope 权限隔离 (`ToolConcurrencyAndScopingTest`)；
- Prometheus / Micrometer 指标导出、TTFT、TPS、Token 消耗端到端验证 (`MicrometerLlmMetricsCollectorTest`, `PrometheusActuatorEndpointTest`)；
- 高并发 SQLite WAL 模式读写分离与虚拟线程加速比性能基准 (`ConcurrencyBenchmarkSuiteTest`)；
- 多 Agent 协同工作流引擎与智能客服工单分诊实战 (`AgentSwarmOrchestratorTest`, `CustomerSupportSwarmTest`)。

执行全量自动化验证指令：
```bash
./mvnw clean verify
```

**测试运行报告**：
```text
[INFO] Reactor Summary for llm-enterprise-hub 0.0.1-SNAPSHOT:
[INFO] 
[INFO] llm-enterprise-hub ................................. SUCCESS
[INFO] llm-enterprise-core ................................ SUCCESS (94 tests)
[INFO] llm-enterprise-spring-boot-starter ................. SUCCESS (3 tests)
[INFO] llm-enterprise-gateway-server ...................... SUCCESS (60 tests)
[INFO] sample-business-app ................................ SUCCESS (6 tests)
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time: 54.603 s
```
**全量 163 个用例全部绿色通过，0 失败、0 错误、0 跳过**。
