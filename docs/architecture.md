# 系统架构与模块设计 (System Architecture & Module Design)

本文档深入解析 **LLM Enterprise Hub** 的系统分层架构、运行时拓扑、多模块工程关系以及端到端请求生命周期。

> 💡 **宏观分层与上下游协同**：关于本项目在全行业大模型在线服务 4 层体系中的宏观位置、同类竞品边界及上下游框架协同机制，请先参阅：[LLM 在线服务全景生态定位与协同白皮书 (ecosystem-positioning.md)](ecosystem-positioning.md)。

---

## 一、系统架构全景图

系统基于 **Java 21 虚拟线程 (Virtual Threads)** 构建高吞吐、低延迟的非阻塞核心底座，在网关层完全兼容 **OpenAI 标准协议**，将大模型治理流程抽象为四个清晰阶段：

```mermaid
flowchart TD
    Client(["用户客户端 / 业务系统 / OpenAI SDK / cURL"])
    
    subgraph S1["1. 流量接入与租户治理层 (Ingress & Multi-Tenant Governance)"]
        Auth["ApiKeyAuthenticationFilter<br>(API Key 鉴权 & 租户上下文注入)"]
        Rate["TenantRateLimiter / DistributedRateLimiter<br>(QPS 令牌桶 + RPM/TPM 双轨滑动窗口)"]
        Budget["TenantBudgetManager<br>(微美元财务成本预算门禁与 80% 软告警)"]
        Controller["ChatCompletionController<br>(OpenAI 兼容端点 /v1/chat/completions)"]
    end

    subgraph S2["2. 路由治理、前置增强与缓存层 (Routing, Pre-Inference & Caching)"]
        Canary["CanaryRoutingService<br>(百分比灰度切流 & 粘性会话一致性哈希)"]
        PromptGuard["PromptGuardrailService<br>(提示词注入检测与规则防御)"]
        TemplateReg["PromptTemplateRegistry<br>(版本化 Prompt 模板中心与变量校验)"]
        RagService["RagPipelineService<br>(多轮 Query 改写 + Markdown 面包屑分块 + 混合重排)"]
        Milvus[("Milvus 向量数据库<br>(租户标量物理隔离 / 内存余弦索引)")]
        Cache["CacheEngine<br>(L1 Exact Hash + L2 稠密向量余弦语义缓存)"]
        QuotaPre["TenantQuotaService & QuotaStorageEngine<br>(原子 Token 租约预占 Token Leasing)"]
    end

    subgraph S3["3. 推理调度与智能体执行中枢 (Harness & Agent Execution Engine)"]
        ChatExec["ChatExecutionService<br>(调度协调与生命周期总控)"]
        Failover["FailoverRoutingService<br>(虚拟模型别名级联 + 首字投机对冲竞速 + 故障自愈)"]
        ClusterMgr["RemoteClusterManager<br>(K8s Headless DNS 动态自感知 + 显存/前缀缓存负载均衡)"]
        GPUCluster[("远程 GPU 推理集群<br>(vLLM / Triton / Ollama / 云端模型)")]
        StreamingAgent["StreamingAgentOrchestrator<br>(流式 ReAct 智能体循环 & SSE 中间事件透传)"]
        Swarm["AgentSwarmOrchestrator<br>(多 Agent 协同工作流与工单分诊 Handoff)"]
        TaskManager["AgentTaskManager<br>(长程任务状态机 & SQLite 事务现场断点续跑)"]
        Gatekeeper["ToolActionGatekeeper<br>(危险动作拦截与跨租户动作越权守门)"]
        ShadowMirror["ShadowTrafficMirror<br>(生产级异步影子流量克隆与效果对照评测)"]
    end

    subgraph S4["4. 出口安全与可观测监控层 (Egress, Safety & Observability)"]
        StreamingDLP["StreamingOutputGuardrail<br>(跨 Chunk 环形滑动窗口隐私敏感词拦截)"]
        DeMasking["PrivacyDeMaskingTransformer<br>(双向隐私脱敏保险库透明反脱敏还原)"]
        Faithfulness["RagFaithfulnessEvaluator<br>(事实忠实度评估与幻觉主动防御门禁)"]
        CostCalc["CostCalculator<br>(官方费率匹配与微美元精细核算)"]
        WriteBehind["AsyncQuotaWriteBehindBatcher<br>(异步削峰冲正批处理器 -> JDBC batchUpdate)"]
        Tracing["TraceCollector & TraceContextPropagator<br>(W3C traceparent 全链路追踪 & 单行 JSON 审计)"]
        Metrics["LlmMetricsCollector & Prometheus<br>(TTFT / TPS / 时序时钟聚合度量)"]
        Dashboard["内嵌现代 Web 运维大盘<br>(Tailwind CSS + ECharts 实时图表)"]
    end

    %% 流程连接
    Client --> Auth --> Rate --> Budget --> Controller
    Controller --> Canary --> PromptGuard --> TemplateReg --> RagService
    RagService <-->|向量相似度检索| Milvus
    RagService --> Cache
    Cache -- "缓存未命中" --> QuotaPre --> ChatExec
    ChatExec --> Failover --> ClusterMgr <--> GPUCluster
    ChatExec --> StreamingAgent <--> Swarm
    StreamingAgent <--> TaskManager
    StreamingAgent <--> Gatekeeper
    ChatExec -.->|异步影子镜像| ShadowMirror
    
    GPUCluster --> StreamingDLP --> DeMasking --> Faithfulness --> CostCalc --> WriteBehind
    WriteBehind --> Tracing --> Metrics --> Client
    Metrics -.-> Dashboard
```

---

## 二、工程多模块拓扑与职责划分

本项目采用严格分层的 Maven 多模块架构，核心业务、脚手架装配、独立服务与示例工程高度解耦：

```
llm-enterprise-hub/
├── llm-enterprise-core/                # 核心引擎层 (纯业务领域模型与核心算法，无外部胖容器依赖)
├── llm-enterprise-spring-boot-starter/ # 开箱即用 Starter (自动化配置、@LlmTool 扫描、依赖装配)
├── llm-enterprise-gateway-server/      # 独立网关服务端 (可直接部署的完整微服务网关应用)
└── samples/
    └── sample-business-app/            # 企业级业务示例工程 (演示 Starter 接入与 @LlmTool 注册)
```

### 1. `llm-enterprise-core` (核心引擎模块)
- **定位**：纯净的 Java 21 领域内核，定义大模型治理、智能体编排与流量治理的标准契约与核心算法；
- **核心包结构**：
  - `client/`：统一 LLM 客户端抽象接口 (`LlmClient`)、SSE 流式处理器 (`StreamCancellationHandle`)；
  - `model/`：OpenAI 标准请求响应模型 (`ChatCompletionRequest`, `ChatCompletionResponse`, `ChatMessage`, `Usage` 等)；
  - `router/`：多级故障转移 (`FailoverRoutingService`)、投机对冲竞速 (`RequestHedgingRouter`)、虚拟模型别名 (`ModelAliasRegistry`)；
  - `router/canary/`：Canary 灰度切流引擎与粘性会话一致性哈希 (`CanaryRoutingService`, `CanaryRule`)；
  - `router/shadow/`：生产级异步影子流量镜像与对照审计 (`ShadowTrafficMirror`, `ShadowComparisonRecord`)；
  - `cluster/`：远程 GPU 集群拓扑自感知 SPI (`ClusterDiscoveryProvider`)、显存与前缀缓存哈希负载均衡 (`ClusterLoadBalancer`)、多 Key 轮转凭据池 (`ApiKeyPool`)；
  - `quota/`：租户配额治理服务 (`TenantQuotaService`)、无锁 CAS 预占与 Redis Lua 租约引擎 (`QuotaStorageEngine`, `DistributedLuaQuotaEngine`)、异步削峰写回批处理器 (`AsyncQuotaWriteBehindBatcher`)；
  - `ratelimit/`：QPS 令牌桶 (`TenantRateLimiter`)、毫秒槽双轨滑动窗口 (`SlidingWindowRateLimiter`)、带本地熔断降级的分布式限流器 (`DistributedRateLimiter`)；
  - `cost/`：企业级模型费率注册表 (`ModelPricingRegistry`)、微美元成本核算器 (`CostCalculator`)、租户财务预算管理器 (`TenantBudgetManager`)；
  - `guard/`：入站提示词注入防御 (`PromptGuardrailService`)、出站正则数据脱敏 (`OutputGuardrailService`)、流式跨 Chunk 环形滑动窗口拦截器 (`StreamingOutputGuardrail`)、双向脱敏隐私保险库 (`PrivacyVault`)、工具越权动作守门员 (`ToolActionGatekeeper`)；
  - `cache/`：L1 Exact Hash 精准匹配 + L2 稠密向量余弦相似度语义缓存引擎 (`CacheEngine`, `CacheEntry`)；
  - `agent/`：ReAct 循环工具调度器 (`AgentToolOrchestrator`)、流式智能体生命周期中间事件透传 (`StreamingAgentOrchestrator`)、多 Agent 协同分诊工作流 (`AgentSwarmOrchestrator`)、长程状态机与检查点恢复 (`AgentTaskManager`)；
  - `rag/`：前置 RAG 检索增强服务 (`RagPipelineService`)、Markdown 章节层级分块器 (`MarkdownStructureChunker`)、多轮指代消解 Query 改写器 (`ContextualQueryRewriter`)、混合检索重排序器 (`HybridSearchReranker`)、事实忠实度评估防御 (`RagFaithfulnessEvaluator`)；
  - `template/`：Prompt 模板引擎 (`PromptTemplateEngine`)、版本化与多租户定制 Prompt 模板中心 (`PromptTemplateRegistry`)；
  - `resilience/`：自愈型三态熔断器 (`CircuitBreaker`)、AWS/Envoy 标准 Full Jitter 指数退避重试器 (`AdaptiveJitteredRetryPolicy`)；
  - `trace/`：全链路 Trace 上下文管理器 (`TraceContext`)、W3C 标准协议传递器 (`TraceContextPropagator`)、结构化调用链与单行标准 JSON 审计打点器 (`TraceCollector`, `RequestAuditLogger`)。

### 2. `llm-enterprise-spring-boot-starter` (自动化装配模块)
- **定位**：为企业微服务赋能的 Spring Boot 3.3 Starter；
- **核心能力**：
  - 自动扫描 Spring 容器中带有 `@LlmTool` 注解的业务 Bean 方法，通过反射与类型推断自动生成标准的 OpenAI JSON Schema 并注册到工具注册表 (`ToolRegistry`)；
  - 自动配置所有的网关治理 Bean（条件装配 `@ConditionalOnMissingBean`），方便业务随时按需替换自定义 SPI 扩展；
  - 支持业务微服务以极低侵入度（仅需声明依赖）实现与 Hub 网关的一体化协同或进程内直接运行。

### 3. `llm-enterprise-gateway-server` (独立网关服务模块)
- **定位**：企业内网独立运行的集中式大模型 API 网关微服务；
- **包含组件**：
  - OpenAI 兼容的 HTTP 接入端点 (`ChatCompletionController`)；
  - SQLite WAL 模式轻量持久化与元数据仓储 (`TenantRepository`, `SqliteSessionStorageProvider`, `SqliteAgentTaskStorageProvider`)；
  - 内嵌现代 Web 运维大盘控制台（静态资源零外部 CDN 依赖，Tailwind CSS + ECharts 实时渲染）；
  - 租户管理与运营大盘 REST API (`AdminController`, `KnowledgeBaseController`, `AgentTaskController`, `SessionController`)。

### 4. `samples/sample-business-app` (业务示例模块)
- **定位**：展示真实企业业务系统如何无缝接入 Starter 的参考实现；
- **典型场景**：
  - 演示真实订单查询 (`queryOrderStatus`) 与运费试算 (`calculateShippingFee`) 业务接口标注 `@LlmTool`；
  - 演示多 Agent 协同工单分诊流转（TriageAgent -> LogisticsAgent）。

---

## 三、请求完整执行时序 (Lifecycle Sequence)

以下为一次带有智能体工具调用、缓存检测与流式输出的完整请求生命周期时序：

```mermaid
sequenceDiagram
    autonumber
    actor Client as 客户端 / SDK
    participant Auth as 鉴权限流过滤链
    participant Router as Canary & 路由治理
    participant Pre as 安全护栏 & 语义缓存
    participant LLM as 远程 GPU 集群 (vLLM)
    participant Agent as ReAct 智能体循环
    participant Post as 流式 DLP 护栏 & 成本审计
    participant Quota as 配额对账批处理器

    Client->>Auth: POST /v1/chat/completions (Stream / API-Key)
    Auth->>Auth: 校验租户有效性 & 令牌桶/滑动窗口限流
    Auth->>Router: 解析虚拟别名与 Canary 灰度分流
    Router->>Pre: 提示词注入检测 & 语义向量缓存检索 (Cosine >= 0.85)
    
    alt 命中 L2 语义缓存
        Pre-->>Client: 直接返回缓存响应 (X-Cache-Lookup: HIT)
    else 缓存未命中
        Pre->>Quota: Pre-flight 原子 Token 租约预占 (Token Leasing)
        Pre->>LLM: 调度执行推理 (带 W3C traceparent 透传)
        
        opt 触发工具调用 (Tool Call)
            LLM-->>Agent: 流式吐出 delta.tool_calls
            Agent->>Agent: ToolActionGatekeeper 权限与动作守门
            Agent->>Agent: 执行本地/远程业务工具
            Agent-->>Client: 推送 SSE 中间事件 (tool_call_start, tool_call_result)
            Agent->>LLM: 回填工具结果，递归执行下一轮补全
        end
        
        LLM-->>Post: 实时输出流式 Chunk
        Post->>Post: 跨 Chunk 环形滑动窗口敏感数据脱敏/拦截
        Post-->>Client: 最终打字机 SSE 推送 (data: {...})
        
        Post->>Quota: 结算差额 (释放预冻结，上报实际 Token)
        Quota->>Quota: AsyncQuotaWriteBehindBatcher 内存聚合 (微批次刷盘)
        Post->>Post: 核算微美元成本，上报 LlmSpan 与单行审计日志
    end
    
    LLM-->>Client: data: [DONE]
```

---

## 四、虚拟线程与高并发模型 (Concurrency Model)

系统全链路基于 **Java 21 Virtual Threads (Project Loom)** 构建：
1. **轻量与百万级并发**：每个入站 HTTP 请求在专属虚拟线程中执行，底层线程栈仅占用几百字节，完全摆脱传统 Tomcat/Netty 平台线程池耗尽导致的请求排队卡死；
2. **非阻塞式 I/O 链路**：
   - 向下游远程推理集群 (vLLM/Triton) 发送 HTTP 同步或流式请求时，虚拟线程自动挂起 Carrier 载体线程，让出 CPU 核心给其他计算任务；
   - 影子流量镜像 (`ShadowTrafficMirror`) 采用 `Executors.newVirtualThreadPerTaskExecutor()` 实现并发异步克隆，做到对主链路绝对的零延迟与零穿透；
   - 智能体工具执行 (`AgentToolOrchestrator`) 对无依赖的多个并发 Tool Calls 使用虚拟线程并行触发，大幅压缩多工具调用的总端到端耗时。
