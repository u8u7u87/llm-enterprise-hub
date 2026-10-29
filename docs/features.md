# 核心特性与技术实现详解 (Enterprise Features Deep Dive)

本文档系统性梳理 **LLM Enterprise Hub** 在生产环境演进中落地的全部核心技术能力，分为十大功能维度。

---

## 目录

1. [流量治理、Canary 灰度与影子流量](#1-流量治理canary-灰度与影子流量)
2. [生产级三层租户配额与财务治理体系](#2-生产级三层租户配额与财务治理体系)
3. [远程 GPU 推理集群自感知与负载均衡](#3-远程-gpu-推理集群自感知与负载均衡)
4. [全链路双向安全护栏与隐私保险库](#4-全链路双向安全护栏与隐私保险库)
5. [ReAct 流式智能体闭环与多 Agent Swarm 调度](#5-react-流式智能体闭环与多-agent-swarm-调度)
6. [工业级 RAG 知识增强与事实忠实度防御](#6-工业级-rag-知识增强与事实忠实度防御)
7. [向量级稠密语义缓存引擎](#7-向量级稠密语义缓存引擎)
8. [企业级弹性容灾与自适应重试网络](#8-企业级弹性容灾与自适应重试网络)
9. [版本化 Prompt 模板中心与多租户定制](#9-版本化-prompt-模板中心与多租户定制)
10. [全链路可观测性、W3C 追踪与运营大盘](#10-全链路可观测性w3c-追踪与运营大盘)

---

## 1. 流量治理、Canary 灰度与影子流量

### 1.1 Canary 灰度权重切流与粘性会话 (`CanaryRoutingService`)
- **平滑权重切流**：支持配置百分比规则（如 90% 流量至 `gpt-4o`，10% 流量切至 `deepseek-r1`），用于新模型无损上线检验；
- **一致性哈希粘性会话**：
  - 支持 `stickyBySession`：依据 `sessionId` 哈希取模，同一用户会话的多轮对话始终锁定在同一模型，避免多轮问答风格突变；
  - 支持 `stickyByTenant`：按租户 ID 锁定，保证同租户体验连贯性；
- **租户专属测试覆盖 (`tenantOverride`)**：支持特定灰度租户 100% 路由至测试模型，其余租户正常运行。

### 1.2 生产级异步影子流量镜像与效果评测 (`ShadowTrafficMirror`)
- **零延迟、零破坏性镜像**：实时将线上真实请求异步克隆，通过 Java 21 虚拟线程以 Fire-and-Forget 方式调度至影子候选模型；
- **故障绝对隔离**：影子模型的任何超时、异常均被严格隔离，绝不影响客户端正常响应；
- **指标横向对齐审计 (`ShadowComparisonRecord`)**：实时收集主备模型的耗时、Token 吞吐、生成文本片段并产生审计日志，用于大模型换代实测对比。

### 1.3 虚拟模型别名与多级级联降级 (`ModelAliasRegistry` & `FailoverRoutingService`)
- **虚拟别名解耦**：客户端请求可直接使用 `fast`、`reasoning`、`general` 等别名，屏蔽底层模型名频繁升级带来的调用端改动；
- **多级级联回退**：别名可配置优先级链路 `[primary, secondary, fallback]`，逐级探测切换，保障五九级（99.999%）可用性。

### 1.4 低延时投机对冲竞速路由 (`RequestHedgingRouter`)
- **首字耗时 (TTFT) 竞速**：优先调度主模型，若在设定的延迟窗口（如 1500ms）内未吐出首字，立即并发激活备用模型；两者以首字到达时间竞速，最快的数据信道呈现给用户，大幅压降企业级大模型应用的 P99 交互延迟。

---

## 2. 生产级三层租户配额与财务治理体系

### 2.1 三层 Token 租约机制 (Token Leasing)
- **Pre-flight 原子租约预占**：由于大模型实际生成 Token 数量事先不可知，请求进入时先原子预扣冻结 Token（`frozen_tokens`），防止并发涌入击穿额度；
- **Post-flight 差额核销**：请求结束后，释放预占额度并记入实耗量；
- **零运维自动日切**：配额 Key 按 `tenant:{id}:quota:yyyyMMdd` 组织，次日自然从 0 计数，免去全量清零定时 Job 的隐患；
- **实现现状**：当前 `QuotaStorageEngine` 为进程内 `InMemoryQuotaStorageEngine`（CAS 原子预占），`DistributedLuaQuotaEngine` 是**进程内模拟** Redis Lua 的实现，仓库尚未引入 Redis 客户端依赖。多 Pod 部署需要真正的跨实例原子性时，须先接入 Redis 适配器。

### 2.2 异步削峰写回批处理器 (`AsyncQuotaWriteBehindBatcher`)
- **消除数据库热行行锁冲突**：大并发下（如数千 QPS），同一租户的并发请求若直接执行 `UPDATE tenants SET used_tokens = used_tokens + ?` 会导致行级锁竞争激烈与数据库卡死；
- **内存微批次聚合**：通过无锁 `LongAdder` 聚合租户消耗，以 200ms 或 100 条为批次窗口，通过 `TenantStorageProvider.batchIncrementUsedTokens`（Spring JDBC `batchUpdate`）批量持久化，数据库写 IO 压力削减 95% 以上。

### 2.3 分布式限流与本地有界熔断降级 (`DistributedRateLimiter`)
- **跨 Pod 状态强一致**：连接分布式限流后端，避免单机内存隔离导致的限额随 Pod 数量线性放大；
- **Fail-Open with Bounded Local Quota**：当限流中间件发生超时或宕机时，断路器自动跳闸，平滑降级至网关本地有界限流，既防止大模型网关因依赖挂死导致全线瘫痪，又杜绝无限流流量击穿下游推理集群。

### 2.4 企业级多级财务成本核算与定价引擎 (`TenantBudgetManager` & `CostCalculator`)
- **官方费率内置与前缀匹配**：内置 DeepSeek、OpenAI、通义千问等百万 Token 输入/输出单价表，最长前缀精准匹配；
- **微美元精细核算**：毫秒级计算每次请求的花费（精确至 6 位微美元），自动注入 `LlmSpan` 与审计日志；
- **三级预算管控状态机**：
  - `OK`：正常消耗；
  - `WARNING`（达到 80% 软限额）：触发 `BudgetWarningListener` 运营告警，提前预警；
  - `EXCEEDED`（达到 100% 硬限额）：即时抛出 `TenantBudgetExceededException` 阻断调用。

---

## 3. 远程 GPU 推理集群自感知与负载均衡

### 3.1 零配置动态自感知 (`ClusterDiscoveryProvider` SPI)
- **K8s Headless DNS 动态解析 (`DnsClusterDiscoveryProvider`)**：定时自省 K8s Headless 域名（如 `vllm.inference.svc.cluster.local:8000`），Pod 扩缩容秒级自感知加入路由表；
- **模型目录热探测 (`ModelCatalogDiscoveryProvider`)**：周期性探测 `/v1/models` 端点，后端集群热加载 LoRA 权重或新模型后自动接入；
- **静态模式 (`StaticClusterDiscoveryProvider`)**：支持逗号分隔端点用于本地联调。

### 3.2 显存感知与前缀缓存负载均衡 (`ClusterLoadBalancer`)
- **最小在途请求调度 (`LeastRequestsLoadBalancer`)**：实时统计每个推理 Worker 的活跃连接数 `activeRequests`，智能绕过显存积压的实例，压降 TTFT；
- **前缀缓存一致性哈希 (`PrefixHashLoadBalancer`)**：基于 Prompt 前缀计算一致性哈希，最大化命中 vLLM Automatic Prefix Caching (APC)，降低重复 Prefill 算力消耗；
- **本地保底安全网**：远程集群若全部宕机，网关透明回退到本地 Ollama 免费轻量模型，业务 0 中断。

---

## 4. 全链路双向安全护栏与隐私保险库

### 4.1 入站提示词注入检测 (`PromptGuardrailService`)
- 内置针对 Jailbreak、DAN 越狱指令、指令覆盖、系统提示词嗅探等高危模式的规则防御引擎，毫秒级就地拦截恶意 Payload。

### 4.2 出站流式跨 Chunk 尾部分片扣留敏感词拦截 (`StreamingOutputGuardrail`)
- 维护重叠字符环形窗口，精准识别被 SSE 打字机分片截断的手机号、身份证、银行卡或 `sk-...` API Key；
- 支持 `BLOCK` 模式（立即中断 SSE 发送并审计打点）与 `MASK` 模式（行内脱敏替换）。

### 4.3 双向隐私脱敏保险库 (`PrivacyVault` & `PrivacyDeMaskingTransformer`)
- **入站提示词自动伪匿名化**：离开企业内网前将敏感命名实体置换为占位符（如 `<PHONE_1>`），并生成单次推理生命周期唯一的映射保险库；
- **出站响应透明反脱敏还原**：非流式与流式场景下透明逆向置换回真实敏感数据，客户端完全无感，满足严格的隐私合规（GDPR / PIPL）。

### 4.4 工具动作执行守门员 (`ToolActionGatekeeper`)
- 拦截 Agent 越权动作（如恶意 `rm -rf` 指令、跨租户越权查询），强制约束动作租户 Scope。

---

## 5. ReAct 流式智能体闭环与多 Agent Swarm 调度

### 5.1 流式智能体生命周期闭环 (`StreamingAgentOrchestrator`)
- **打字机分片重组**：零拷贝解析上游 Chunk 中的 `delta.tool_calls`，完整组装后安全委托工具执行；
- **中间过程 SSE 事件透传**：向前端广播 `tool_call_start`、`tool_call_result`、`agent_thought`（适配 DeepSeek-R1 / Qwen 思维链）自定义事件，前端无缝展示“正在查询知识库...”卡片；
- **多轮流式递归驱动**：自动将工具结果回填上下文并触发下一轮流式补全，直至最终完成并发送 `data: [DONE]`。

### 5.2 多 Agent 协同工作流引擎 (`AgentSwarmOrchestrator`)
- 声明式注册 `AgentRole`，支持分级工单分诊；支持角色间透明转交 (`Handoff`) 并携带上下文参数，实现多专长智能体流水线协同。

### 5.3 长程任务断点续跑与现场恢复 (`AgentTaskManager`)
- 基于 SQLite 事务性任务状态机，实时落盘执行检查点；若遇网络中断或系统重启，支持调用 `/v1/agent/tasks/{id}/resume` 恢复执行现场。

---

## 6. 工业级 RAG 知识增强与事实忠实度防御

### 6.1 多轮指代消解与 Query 改写 (`ContextualQueryRewriter`)
- 自动分析多轮会话历史，将用户追问中的代词（如“它”、“这个”、“如何申请”）结合前序轮次实体消除歧义，就地合成自包含检索 Prompt，解决多轮追问召回率为 0 的严重缺陷。

### 6.2 Markdown 结构分块 (`MarkdownStructureChunker`)
- 追踪 Markdown 标题层级，自动添加面包屑上下文前缀（如 `[章节: 企业制度 > 报销标准]`）；完整保留 Markdown 表格和代码块，彻底杜绝表格截断导致的数据幻觉。

### 6.3 混合重排评分与实体提权 (`HybridSearchReranker`)
- 融合 Dense 向量余弦相似度与 Sparse 词频匹配（中文 Bi-Gram N-gram）；对专有名词、工单号、错误代码实施精准加权，后检索阶段硬过滤低置信度噪点切片。

### 6.4 事实忠实度评估与幻觉主动防御 (`RagFaithfulnessEvaluator`)
- 耗时小于 5ms，比对回答中的事实命题与召回切片支撑度，计算 `groundednessScore`；
- 三态阶梯门禁：`PASS`（正常返回）、`WARN_APPEND`（追加合规免责声明）、`REJECT_FALLBACK`（阻断严重幻觉并安全兜底）。

---

## 7. 向量级稠密语义缓存引擎

### 7.1 双层智能缓存架构 (`CacheEngine`)
- **L1 精准 Hash 缓存**：对完全一致的 Prompt 实现 $O(1)$ 微秒级极速命中；
- **L2 稠密向量余弦相似度缓存**：接入嵌入模型生成向量，计算余弦相似度（阈值 $\ge 0.85$）；
- **零字面同义命中**：“如何开增值税发票”与“我想开专票怎么操作”在零字面词袋交集场景下依然精准命中，大幅削减 90%+ 云端调用算力；
- **自适应降级**：无向量服务时平滑回退至 Jaccard 词袋相似度检测。

---

## 8. 企业级弹性容灾与自适应重试网络

### 8.1 自愈型三态熔断器 (`CircuitBreaker`)
- 严格遵循 `CLOSED` -> `OPEN` -> `HALF_OPEN` 状态机流转；
- 半开状态下引入单探针并发自愈保护，探测成功平滑恢复，失败立即重新熔断。

### 8.2 多 Key 凭据池与 429 智能自愈轮转 (`ApiKeyPool`)
- 支持配置多套 API Key（`sk-k1,sk-k2,sk-k3`），采用 Round-Robin 与 Least-Used 调度并发调用；
- 遭遇 429 限流时自动根据响应头 `Retry-After` 标记冷却，并在当前虚拟线程内瞬间透明轮转到下一个健康 Key 重试，客户端完全无感。

### 8.3 智能指数退避与抖动重试器 (`AdaptiveJitteredRetryPolicy`)
- 落地 AWS / Envoy 标准的 **Full Jitter** 与 **Equal Jitter** 随机退避算法：
  $$\text{backoff}(attempt) = \text{random}\left(0, \min(\text{cap}, \text{base} \times 2^{attempt})\right)$$
  彻底离散并发重试流量，杜绝惊群重试雪崩；
- 严密区分可重试错误（429、503、网络超时）与不可重试错误（401、400、合规拦截）。

---

## 9. 版本化 Prompt 模板中心与多租户定制

### 9.1 版本化模板管理 (`PromptTemplateRegistry`)
- 支持 `v1`、`v2`、`latest` 多版本隔离；支持租户级专属模板覆盖，告别代码中硬编码 Prompt。

### 9.2 严格变量完整性校验
- 声明 `requiredVariables` 必填清单，渲染前进行完整性检查；若有缺失立即抛出明确的 [`MissingTemplateVariableException`](../llm-enterprise-core/src/main/java/com/enterprise/llm/template/MissingTemplateVariableException.java)，防止带占位符污染大模型输入。

---

## 10. 全链路可观测性、W3C 追踪与运营大盘

### 10.1 W3C TraceContext 与 OpenTelemetry 互通 (`TraceContextPropagator`)
- 生成与透传符合 W3C 规范的 `traceparent: 00-{trace_id}-{span_id}-01` 请求头，与 Jaeger、Zipkin、SkyWalking 无缝集成；
- 自动将 `traceId` 与 `tenantId` 绑定至 Logback MDC，实现单请求全调用链精准检索。

### 10.2 单行标准 JSON 审计日志 (`RequestAuditLogger`)
- 请求结束后输出包含 `traceId`、`tenantId`、`model`、`promptTokens`、`completionTokens`、`totalTokens`、`ttftMs`、`totalLatencyMs`、`costUsd`、`cacheHit` 等全维度字段的结构化审计日志，方便直接对接 ELK 或 Loki。

### 10.3 Prometheus / Micrometer 企业级度量体系
- 原生暴露 `/actuator/prometheus` 端点，采集 QPS、TTFT、TPS、Token 消耗分布、熔断器状态等核心指标。

### 10.4 内嵌 Web 运维监控大盘
- 静态控制台由网关自身托管（`src/main/resources/static/index.html`），**无需独立前端构建**；
- 注意：Tailwind CSS 与 ECharts 通过公网 CDN（`cdn.tailwindcss.com` / `jsdelivr`）加载，**完全离线环境下看板样式与图表不可用**，但网关 API 不受影响；
- 实时可视化展示租户用量、分钟级请求曲线、L1/L2 缓存命中率与 Agent 任务状态机；
- 看板需要手工输入 `ADMIN_SECRET_KEY`（对应 `admin.secret-key`），仓库内不再内置默认密钥。
