# LLM Enterprise Hub (企业级大模型网关与自主智能体中枢)

<div align="center">

[![Java](https://img.shields.io/badge/Java-21%2B%20(Virtual%20Threads)-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![SQLite](https://img.shields.io/badge/SQLite-3.46%20(WAL)-003B57?logo=sqlite&logoColor=white)](https://sqlite.org/)
[![Milvus](https://img.shields.io/badge/Milvus-2.x%20Vector%20DB-00B4D8?logo=milvus&logoColor=white)](https://milvus.io/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/Tests-295%2B%20Passed%20(100%25)-success)](#-自动化测试与工程指标)
[![Protocol](https://img.shields.io/badge/Protocol-OpenAI%20Compatible-black)](docs/api-reference.md)

<p align="center">
  <b>面向企业级高并发生产环境的 Java 21 大模型网关与长程智能体（Agent）调度中枢</b><br>
  攻克传统网关在复杂 Agent 交互、流式脱敏、热行锁并发、多 Pod 配额穿透等痛点。<br>
  既可作为<b>独立高性能网关</b>开箱即用，亦可作为 <b>Spring Boot Starter</b> 深度内嵌于企业已有微服务中。
</p>

[快速开始](#-新手极速接入-quick-start) • [生态定位与上下游](docs/ecosystem-positioning.md) • [同类竞品对比](#-同类主流开源竞品深度对比) • [核心能力矩阵](#-核心能力体系全景) • [系统架构设计](docs/architecture.md) • [三大部署形态](docs/deployment-modes.md) • [多推理后端适配](docs/pluggable-inference-backend.md) • [API 接入参考](docs/api-reference.md) • [配置字典](docs/configuration.md)

</div>

---

## 🌟 为什么选择 LLM Enterprise Hub？

随着企业大模型应用从“玩具级 Prompt 单轮调用”迈向“生产级自主智能体（Agent）与多微服务协同”，传统的大模型反代网关（如仅做 API 格式转换与简单分发）迅速暴露出致命短板：

1. **复杂 Agent 无法断点自愈**：传统网关把 LLM 当成无状态黑盒，一旦长程任务中某个 Tool 超时崩溃，数万 Token 交互现场全毁，无法状态回溯与断点续跑；
2. **高并发下配额管理形同虚设**：大模型生成 Token 数事先不可知。多 Pod 并发涌入时，简易后置扣费会引发额度严重击穿；而在数据库执行热行累加又会导致严重行锁互斥；
3. **流式数据跨块脱敏瘫痪**：企业出站 DLP 往往按 Chunk 传输，当敏感手机号或密钥被网络拆分成两个 SSE Chunk 时，传统单块正则匹配彻底失效；
4. **企业 Java 生态割裂严重**：主流开源网关多由 Python 或 Go 编写，国内绝大多数以 Java/Spring 生态为主体的金融、电商、政企后端团队难以深度定制，更无法将已有 `@Service` 业务方法零代码无缝转化为 Agent Tool。

**LLM Enterprise Hub** 应运而生。它以 **Java 21 虚拟线程 (Virtual Threads)** 为高并发底座，以 **Agent Harness 状态机** 与 **三层租约配额机制** 为中枢骨架，打造高吞吐、强安全、全自愈的企业级 AI 基础设施。

---

## 🧭 在线服务全景定位与上下游生态 (Ecosystem Positioning)

为了让开发者与架构师准确把握系统定位，下图展示了 **LLM Enterprise Hub** 在现代企业级大模型在线服务全景体系中所处的宏观层级与端到端协作拓扑：

```mermaid
flowchart TD
    subgraph L1["【上游：业务应用与智能体编排层 (Client & Agent Orchestration)】"]
        App["企业微服务群 (Spring Boot / Java)"]
        UI["前端与客户端 (Open-WebUI / NextChat)"]
        Orch["Agent 编排框架 (LangChain / Spring AI / LlamaIndex)"]
    end

    subgraph L2["【中枢：企业级智能网关与 Agent 治理中枢 (LLM Enterprise Hub - 本项目)】"]
        direction TB
        L2_1["统一 OpenAI 兼容接入 • 多租户鉴权 • 分布式 Token 租约预占 (Leasing)"]
        L2_2["双向全链路安全 (Prompt 注入主动防御 + 跨 Chunk 环形滑动脱敏 DLP)"]
        L2_3["推理感知复合调度 (InferenceAwareLB 前缀缓存亲和 + 排队/显存感知)"]
        L2_4["长程 Agent Harness 状态机 (事务 Checkpoint 断点自愈 + 本地微服务 @LlmTool 注入)"]
        L2_5["L1 精确哈希 + L2 稠密向量语义缓存 (Dense Vector Cache)"]
    end

    subgraph L3["【下游：算力调度平台与高性能推理后端 (Inference & Scheduling)】"]
        Engines["私有高性能推理引擎 (vLLM / SGLang / Triton / llama.cpp)"]
        Schedulers["分布式算力调度平台 (Ray / Ray Serve / K8s + Keda)"]
        CloudSaaS["商业闭源云端模型 (DeepSeek / OpenAI / 阿里通义 / 智谱)"]
    end

    subgraph L4["【基础：存储与数据基础设施 (Hardware & Data Foundation)】"]
        DB["持久化与向量底座 (Milvus 2.x / Redis 7.x / SQLite 3.x WAL)"]
    end

    L1 -->|"① 标准 OpenAI 协议 / SSE 流式 / 自定义租户头"| L2
    L2 -->|"② 前缀哈希亲和 / 实时指标探测 / 故障降级"| L3
    L2 -.->|"③ 状态快照持久化 / 稠密向量检索 / 租约控制"| L4
```

### 1. 本项目所处的层级与核心定位
- **核心层级**：**第 2 层 —— 企业级智能业务网关与 Agent 治理中枢（Enterprise AI Gateway & Agent Governance Layer）**；
- **定位边界**：
  - **向下**：不重复制造底层模型推理算子（不做 CUDA 核函数优化，避免重复发明 vLLM/SGLang 的 PagedAttention），而是通过专有适配器深度感知下游的排队状态与缓存亲和；
  - **向上**：不与上层端到端低代码应用平台（如 Dify/FastGPT）争夺 UI 画板，而是专注于为企业微服务与各种客户端提供**坚如磐石的高并发管控底座与 Java 原生嵌入能力**。

### 2. 上下游开源框架协同矩阵
| 方向 | 典型开源框架 / 系统 | 相互协同配合方式 |
| :--- | :--- | :--- |
| **上游 (Upstream)** | **Spring Cloud / 业务微服务**<br>**LangChain / Spring AI / LlamaIndex**<br>**Open-WebUI / NextChat** | • **协议标准互通**：标准 OpenAI RESTful / SSE 协议，现有应用 0 代码修改即可接入；<br>• **企业安全增强**：业务请求进入时由 Hub 进行多租户鉴权、Token 租约预占、Prompt 注入防御与流式脱敏；<br>• **微服务能力化**：使用 `@LlmTool` 注解将企业现有 Spring Service 方法零代码暴露为 Agent 工具。 |
| **中枢 (本项目)** | **LLM Enterprise Hub** | **承上启下的核心枢纽**：负责并发削峰、多租户配额、敏感脱敏、语义缓存、智能体断点容灾、多后端感知路由。 |
| **下游 (Downstream)** | **vLLM / SGLang / Triton**<br>**Ray / Ray Serve**<br>**llama.cpp / Ollama**<br>**商业云 API (DeepSeek/OpenAI)** | • **算力负载均衡**：通过 `InferenceAwareLoadBalancer` 实时感知 vLLM/SGLang 排队深度与显存占用；<br>• **KV Cache 亲和**：提取 Prompt 前缀哈希实现 APC (Automatic Prefix Caching) 亲和路由；<br>• **算力自动降级**：私有算力故障或过载时，无缝透明降级至备用云端 API 或本地轻量模型。 |
| **基建 (Foundation)** | **Milvus / Redis / SQLite** | • **向量语义缓存**：稠密向量余弦计算，毫秒级直接拦截重复语义请求；<br>• **分布式租约**：Redis Lua 脚本原子预占配额，避免集群并发额度击穿；<br>• **状态机快照**：SQLite WAL / 数据库事务级保存 Agent 交互状态，支撑故障自愈断点续跑。 |

> 📖 **深入阅读**：关于体系架构 4 层模型、5 大竞品阵营深度剖析与端到端协同场景时序，详见：  
> 👉 [**LLM 在线服务全景生态定位与协同白皮书 (docs/ecosystem-positioning.md)**](docs/ecosystem-positioning.md)

---

## 📊 同类主流开源竞品深度对比

为了让技术选型更加客观清晰，我们将 **LLM Enterprise Hub** 与业内主流开源项目进行多维度横向对比：

| 评估维度 | LLM Enterprise Hub | LiteLLM (Proxy) | One-API / New-API | Portkey (Open Source) | Dify | Envoy AI Gateway |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **底层语言与并发模型** | **Java 21 虚拟线程**<br>(百万并发纤程，零上下文切换惩罚) | Python asyncio<br>(受 GIL 限制，高并发需多进程) | Go 协程<br>(高并发轻量 Goroutine) | Node.js / TypeScript<br>(单线程事件循环) | Python (Flask / Celery)<br>(重型框架，面向 Web) | C++<br>(高性能网络事件循环) |
| **交付形态与生态融合** | **双模驱动**：<br>① 独立 Gateway<br>② Spring Boot 原生 Starter | 独立 HTTP 服务 / Python 库 | 独立部署二进制 / Docker | 独立网关 / Node.js 库 | 独立综合 Web 平台<br>(面向业务人员) | Envoy 外部扩展插件 / Filter |
| **Agent Harness 与断点续跑** | **原生支持长程任务状态机**<br>事务快照级 Checkpoint，中断可精准恢复 | ❌ 仅作 LLM API 透传<br>(无状态机能力) | ❌ 纯 API 聚合分发<br>(无智能体调度) | ❌ 仅透传函数调用，无状态机断点恢复 | ✅ 支持可视化工作流编排<br>(但无法嵌入已有微服务) | ❌ 纯流量代理层<br>(无业务与 Agent 状态) |
| **多租户配额与财务治理** | **三层 Token 租约预占**<br>Lua 原子租约 + 异步削峰写回 + 微美元预算 | 简单配额追踪<br>(支持 Redis 扣费，无租约预占) | 基础积分/额度扣除<br>(直接更新数据库，大并发易锁表) | 基础预算限额控制 | 团队/工作空间 Token 限额 | 需依赖外部速率限制服务 |
| **出站敏感数据脱敏 (DLP)** | **跨 Chunk 环形滑动窗口**<br>彻底解决边界割裂，支持双向保险库透明反脱敏 | 基础正则替换<br>(单包匹配，跨包失效) | ❌ 无内置 DLP 护栏 | 基础 PII 掩码<br>(依赖外部 Guardrail) | 基础内容审核接入 | 需结合外部 WAF |
| **语义缓存实现深度** | **稠密向量余弦计算**<br>多租户标量物理隔离，支持毫秒级向量相似度 | 简单 Exact Hash / 基础 Redis 语义匹配 | 基础 Exact Hash 匹配 | 向量缓存 (支持多向量库后端) | 仅针对工作流节点简单缓存 | 基础 HTTP 缓存 |
| **GPU 推理集群感知** | **K8s Headless DNS 动态感知**<br>支持 vLLM 前缀缓存 (APC) 与并发请求最小化负载均衡 | 静态权重轮询 | 静态多渠道权重与优先级轮询 | 静态多供应商路由 | 静态多模型提供商配置 | 基于 Envoy 负载均衡机制 |
| **RAG 检索增强体系** | **前置工业级 RAG 流水线**<br>多轮改写 + Markdown 结构分块 + 忠实度防幻觉 | ❌ 无内置 RAG | ❌ 无内置 RAG | ❌ 无内置 RAG | ✅ 完备的知识库管理与检索 | ❌ 无内置 RAG |
| **部署依赖与运维复杂度** | **极简零依赖**：<br>单 Jar 包内置 SQLite WAL + 嵌入式 Web 看板，亦可外接 Milvus/Redis | 需依赖 Python 运行时与 Redis | 单二进制文件 + SQLite/MySQL | 需 Node.js 运行环境与数据库 | 极重 (需 PostgreSQL, Redis, Weaviate/Qdrant, Celery) | 需掌握 Envoy 配置与容器体系 |

### 💡 选型决策指南 (When to choose which?)
- **选择 LLM Enterprise Hub**：如果你的团队技术栈以 **Java / Spring Boot** 为主，需要一个**既能对外提供统一 OpenAI 兼容网关，又能以内嵌 Starter 直接注入微服务、将 Java 方法秒变 Agent Tool** 的生产级基础设施；同时需要**严格的租户 Token 租约、跨 Chunk 流式安全脱敏、长程智能体断点容灾与零依赖运维**。
- **选择 LiteLLM**：如果是纯 Python 团队，首要诉求是快速对接全球上百家冷门云厂商大模型 API。
- **选择 Dify**：如果需要开箱即用的可视化界面、运营人员托拉拽搭应用，而非深度定制后端微服务。
- **选择 One-API / New-API**：如果主要需求是做国内多渠道大模型 API 的“号池分发与转售计费”。

---

## 🧩 核心能力体系全景

```mermaid
mindmap
  root((LLM Enterprise Hub))
    流量与路由治理
      Canary 百分比权重灰度切流
      一致性哈希粘性会话 (Session / Tenant)
      零侵入生产级异步影子流量镜像
      虚拟模型别名与多级级联降级 (Fast / Reasoning)
      低延迟首字投机对冲竞速路由 (Request Hedging)
      K8s Headless DNS 拓扑动态自感知
    精细化多租户与财务治理
      分布式 Token 租约原子预占 (Token Leasing)
      异步削峰写回批处理器 (消除数据库行锁)
      双轨滑动窗口限流器 (RPM + TPM)
      微美元财务成本精细核算与定价引擎
      租户预算 80% 软告警与硬门禁
    自主智能体 Harness 与长程状态机
      事务 Checkpoint 快照与故障现场断点续跑
      ReAct 循环流式事件透传 (Thought/Action/Observation)
      多 Agent 协同工作流与工单分诊 (Swarm / Handoff)
      Java 21 虚拟线程 Tool 并发执行
      跨租户数据越权与高危动作守门人
    全链路双向安全防护网
      提示词注入深度语义与规则拦截
      跨 Chunk 环形滑动窗口流式敏感词过滤
      双向隐私脱敏保险库与透明反脱敏
      动态脱敏规则热加载与自定义配置
    知识增强与 RAG 工业级防御
      多轮会话 Query 改写与意图消歧
      Markdown 标题面包屑与表格感知分块
      租户物理标量隔离与 Milvus 2.x 向量引擎
      RAG 事实忠实度评估与幻觉主动阻断
    高性能加速与可观测性
      稠密向量级真实语义缓存 (Dense Vector Cache)
      W3C TraceContext 全链路追踪 (traceparent)
      Prometheus / Micrometer 企业级时序度量
      零依赖内嵌现代 Web 运维监控大盘 (Tailwind + ECharts)
```

> 详细架构说明与时序请参阅：[系统架构与模块设计 (docs/architecture.md)](docs/architecture.md)  
> 全部 35+ 项特性技术细节请参阅：[核心特性与技术实现详解 (docs/features.md)](docs/features.md)

---

## 🚀 新手极速接入 (Quick Start)

LLM Enterprise Hub 支持两种接入方式：
- **模式 A：独立网关运行模式 (Standalone Gateway)** —— 1 分钟拉起独立服务，对外提供标准 OpenAI 接口；
- **模式 B：微服务 Spring Boot Starter 嵌入模式** —— 将网关能力和 Tool 编排直接集成至已有 Java 微服务中。

---

### 模式 A：独立网关运行模式 (1 分钟启动)

#### 1. 克隆并运行网关
```bash
# 克隆仓库
git clone https://github.com/your-org/llm-enterprise-hub.git
cd llm-enterprise-hub

# 使用项目自带 Maven Wrapper 快速编译并启动网关
./mvnw clean spring-boot:run -pl llm-enterprise-gateway-server
```
*服务启动后，监听端口为 `8080`，默认 SQLite 数据库 `hub-data.db` 将自动初始化建表。*

#### 2. 发起首次推理请求 (cURL)
系统内置默认测试租户 `tenant-alpha`，使用其专属 API Key：
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-alpha-test-key-12345" \
  -d '{
    "model": "qwen2.5:0.5b",
    "messages": [
      {"role": "user", "content": "你好！请用一句话介绍你自己。"}
    ]
  }'
```

#### 3. 使用官方 OpenAI Python SDK 接入
无需修改任何业务代码，仅需重定向 `base_url` 与 `api_key`：
```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8080/v1",
    api_key="sk-alpha-test-key-12345"
)

response = client.chat.completions.create(
    model="qwen2.5:0.5b",
    messages=[{"role": "user", "content": "请写一段 Python 快速排序代码"}],
    stream=True
)

for chunk in response:
    content = chunk.choices[0].delta.content
    if content:
        print(content, end="", flush=True)
```

#### 4. 打开内置 Web 运维监控看板
直接在浏览器访问：
```text
http://localhost:8080/
```
输入默认 Admin Key：`test-admin-secret-key-12345`。即可实时查看：
- **各租户 QPS、Token 消耗、缓存命中率** 实时折线图；
- **租户管理与日配额动态调整**；
- **Prompt 拦截审计与异常报警**。

---

### 模式 B：微服务 Spring Boot Starter 嵌入模式

如果需要在已有的 Spring Boot 业务系统中直接使用智能体调度与 Tool 编排能力：

#### 1. 引入 Maven 依赖
在你的业务微服务 `pom.xml` 中引入 Starter：
```xml
<dependency>
    <groupId>com.enterprise.llm</groupId>
    <artifactId>llm-enterprise-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

#### 2. 在业务 Service 中标注 `@LlmTool`
无需任何繁琐的 JSON Schema 手写配置，只需添加标准注解，Starter 自动将其暴露为 Agent 可识别并调用的工具沙箱：

```java
@Service
public class OrderService {

    @LlmTool(
        name = "query_order_status",
        description = "根据订单编号查询订单履约状态及物流详情"
    )
    public String getOrderStatus(
            @LlmToolParam(description = "16位订单流水号", required = true) String orderId,
            @LlmToolParam(description = "当前租户标识", required = true) String tenantId
    ) {
        // 工具自带租户物理隔离校验，自动拦截跨租户越权查询
        return "订单 " + orderId + " 当前状态: 【已出库配送中】，配送承运商: 顺丰速运";
    }
}
```

#### 3. 注入服务发起 ReAct 流式闭环
```java
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    @Autowired
    private StreamingAgentOrchestrator streamingAgentOrchestrator;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam String prompt) {
        SseEmitter emitter = new SseEmitter(60000L);
        
        // 自动完成：前置脱敏 -> 意图分析 -> 触发 getOrderStatus -> 结果合成 -> 出口脱敏
        streamingAgentOrchestrator.streamReActLoop(prompt, emitter);
        
        return emitter;
    }
}
```

---

## 🏛 工程架构与模块全景

项目采用高内聚、低耦合的多 Maven 模块单体仓库（Monorepo）设计：

```text
llm-enterprise-hub/
├── llm-enterprise-core/               # 核心引擎层：包含限流、租约、安全脱敏、RAG、Agent 调度等核心实现
├── llm-enterprise-spring-boot-starter/# 嵌入式 Starter：自动装配切面、Tool 扫描器与标准化配置属性
├── llm-enterprise-gateway-server/     # 独立网关主程序：提供 OpenAI 兼容端点、管理面与内嵌 Web 运维大盘
├── samples/sample-business-app/       # 官方接入实战工程：展示如何在真实业务微服务中使用 Starter 与 @LlmTool
├── benchmark/                         # 性能压测模块：SQLite WAL 性能压测与高并发基准测试套件
└── docs/                              # 体系化子文档目录
    ├── ecosystem-positioning.md       # 全景生态定位与上下游协同白皮书
    ├── architecture.md                # 系统架构与模块设计
    ├── features.md                    # 核心特性与技术实现详解
    ├── deployment-modes.md            # 三大部署形态手册 (单机/网关/分布式)
    ├── pluggable-inference-backend.md # 多推理后端适配 (Direct/vLLM/SGLang/Triton/Ray)
    ├── api-reference.md               # 全量 RESTful API 规范与示例
    └── configuration.md               # 生产级配置完全指南
```

---

## 📚 详细子文档导航 (Documentation Map)

为了保持主文档结构清晰，深度技术实现与配置细节均下沉至子文档中：

| 专注文档 | 核心内容提要 | 链接直达 |
| :--- | :--- | :--- |
| 🧭 **生态定位与协同** | 在线服务体系 4 层定位、同类竞品边界剖析、上下游框架全景与端到端配合机制 | [docs/ecosystem-positioning.md](docs/ecosystem-positioning.md) |
| 🏛 **系统架构设计** | 4 阶段请求拓扑、5 大模块职责、时序图、Java 21 虚拟线程并发模型 | [docs/architecture.md](docs/architecture.md) |
| ⚡ **核心特性剖析** | 35+ 项深度企业特性拆解：Canary 灰度、Token 租约、Swarm 协同、流式滑动脱敏 | [docs/features.md](docs/features.md) |
| 📦 **三大部署形态手册** | 单机一体化 (0依赖/本地自跑LLM)、纯网关、分布式集群操作与 K8s 范例 | [docs/deployment-modes.md](docs/deployment-modes.md) |
| 🚀 **多推理后端适配** | 配置选择无框架模式 (DIRECT) 或 Ray、vLLM、Triton、SGLang 及自定义扩展 | [docs/pluggable-inference-backend.md](docs/pluggable-inference-backend.md) |
| 🔌 **API 接入参考** | OpenAI 同步/流式协议、RAG 上传检索、Agent 状态机断点续跑、Admin 运维接口与 cURL 范例 | [docs/api-reference.md](docs/api-reference.md) |
| ⚙️ **配置完全指南** | 完整 `application.yml` 参数字典、环境变量映射表、JVM 虚拟线程与 SQLite 生产调优指南 | [docs/configuration.md](docs/configuration.md) |

---

## 🧪 自动化测试与工程指标

本项目贯彻测试驱动开发（TDD）与防御性编程原则，所有核心特性均具备严密的单元测试、状态迁移测试与高并发集成测试保障：

```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S   S U M M A R Y
[INFO] -------------------------------------------------------
[INFO] Tests run: 295, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] -------------------------------------------------------
```

- **测试用例数**：**295+ 全自动化测试**，测试通过率保持 **100%**；
- **测试覆盖维度**：
  - **核心单元覆盖**：滑动窗口限流、原子 Token 租约预占、双向隐私脱敏正则、向量余弦计算；
  - **状态机与容灾测试**：Agent Task 断点持久化与故障自愈还原、三态熔断器半开探测、投机竞速对冲；
  - **高并发与端到端测试**：Java 21 虚拟线程并发压测、SQLite WAL 模式高并发读写锁竞争、Spring Boot 端到端治理链路验证。

执行全量测试套件：
```bash
./mvnw clean test
```

---

## 📄 开源许可证

本项目采用 **[Apache License 2.0](LICENSE)** 协议开源。无论用于商业化企业大模型基建还是二次开发，均享有高度自由。
