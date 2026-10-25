# 部署形态完全操作手册 (Deployment Modes Guide)

LLM Enterprise Hub 原生支持**三大部署形态**，涵盖从“单机零依赖极简自闭环”到“企业高并发 K8s 多副本中台”的完整生产周期。通过 Spring Boot Profile，只需一行启动参数即可在不同形态间平滑切换。

---

## 目录导航

- [一、三大部署形态全景对比与选型](#一三大部署形态全景对比与选型)
- [二、形态一：单机一体化模式 (Standalone Mode)](#二形态一单机一体化模式-standalone-mode)
  - [2.1 适用场景与零依赖特性](#21-适用场景与零依赖特性)
  - [2.2 启动与验证](#22-启动与验证)
  - [2.3 嵌入式本地引擎自动托管 (Embedded Runner)](#23-嵌入式本地引擎自动托管-embedded-runner)
- [三、形态二：纯企业级网关模式 (Pure Gateway Mode)](#三形态二纯企业级网关模式-pure-gateway-mode)
  - [3.1 适用场景与核心价值](#31-适用场景与核心价值)
  - [3.2 对接商用公有云 API (DeepSeek / Qwen / OpenAI)](#32-对接商用公有云-api-deepseek--qwen--openai)
  - [3.3 启动与环境变量覆盖](#33-启动与环境变量覆盖)
- [四、形态三：分布式高并发集群模式 (Distributed Multi-Pod Mode)](#四形态三分布式高并发集群模式-distributed-multi-pod-mode)
  - [4.1 适用场景与集群拓扑](#41-适用场景与集群拓扑)
  - [4.2 对接 GPU 算力池 (vLLM / Triton / Ray / SGLang)](#42-对接-gpu-算力池-vllm--triton--ray--sglang)
  - [4.3 K8s Deployment 与配置范例](#43-k8s-deployment-与配置范例)
- [五、生产环境高可用检查清单 (Checklist)](#五生产环境高可用检查清单-checklist)

---

## 一、三大部署形态全景对比与选型

```mermaid
flowchart TD
    subgraph S1["1. 单机一体化模式 (Standalone)"]
        direction TB
        M1_Client(["个人电脑 / 单机服务器"]) --> M1_Jar["LLM Enterprise Hub (单 Jar)"]
        M1_Jar --> M1_SQLite[("内置 SQLite WAL")]
        M1_Jar --> M1_Vec[("内置内存向量库")]
        M1_Jar --> M1_Ollama["本地 Ollama / llama.cpp (GGUF)"]
    end

    subgraph S2["2. 纯网关模式 (Pure Gateway)"]
        direction TB
        M2_Apps(["企业各业务微服务"]) --> M2_Gateway["LLM Enterprise Hub 网关"]
        M2_Gateway --> M2_Gov["多租户配额 / DLP 脱敏 / 安全护栏 / Agent 编排"]
        M2_Gov --> M2_Cloud["公有云/商用 API (DeepSeek / Qwen / OpenAI)"]
    end

    subgraph S3["3. 分布式集群模式 (Distributed Multi-Pod)"]
        direction TB
        M3_SLB(["负载均衡器 (K8s Ingress)"]) --> M3_P1["Hub Pod 1"]
        M3_SLB --> M3_P2["Hub Pod 2"]
        M3_SLB --> M3_P3["Hub Pod N"]
        M3_P1 --> M3_Redis[("Redis 集群<br>(分布式 Token 租约)")]
        M3_P2 --> M3_Redis
        M3_P3 --> M3_Redis
        M3_P1 --> M3_Milvus[("Milvus 2.x 集群")]
        M3_P2 --> M3_Milvus
        M3_P3 --> M3_Milvus
        M3_P1 --> M3_GPU["GPU 算力集群 (vLLM / SGLang / Triton / Ray)"]
        M3_P2 --> M3_GPU
        M3_P3 --> M3_GPU
    end
```

| 评估维度 | 单机一体化 (Standalone) | 纯网关模式 (Pure Gateway) | 分布式集群 (Distributed) |
| :--- | :--- | :--- | :--- |
| **Profile 参数** | `--spring.profiles.active=standalone` | `--spring.profiles.active=gateway` | `--spring.profiles.active=distributed` |
| **适用人群与场景** | 个人开发、测试、边缘一体机、断网保密环境 | 企业统一商业 API 采购与调用治理、降级容灾 | 大型中台、高并发微服务多副本 (日调用千万级) |
| **底层推理引擎** | 本地直接运行 (Ollama / llama.cpp / GGUF) | 外部商业 API (DeepSeek / Qwen / OpenAI) | GPU 推理集群 (vLLM, SGLang, Triton, Ray) |
| **外部中间件依赖** | **0 依赖** (0 Redis, 0 MySQL, 0 Milvus, 0 Python) | **0 依赖** (纯网关反代) | 依赖 Redis (分布式租约) + 共享向量库 |
| **单机内存消耗** | 约 300MB ~ 500MB (极轻量) | 约 200MB ~ 400MB | 根据并发连接数弹性扩展 |

---

## 二、形态一：单机一体化模式 (Standalone Mode)

### 2.1 适用场景与零依赖特性
在单机一体化模式下，系统完全不需要任何外部重型框架（**不需要 Ray、vLLM、Triton、Redis、Milvus、MySQL**）：
- **数据库**：使用内嵌 SQLite，采用 WAL 高性能写前日志模式，单机持久化租户、任务快照与时序度量；
- **向量库**：配置 `milvus.endpoint=disabled`，自动无缝激活纯 Java 内嵌余弦索引，毫秒级完成 RAG 切片与相似度召回；
- **推理**：直连本地单机轻量可执行文件（如 Ollama 或 llama-server，支持 CPU、Mac Apple Silicon Metal 或普通单张消费级显卡）。

### 2.2 启动与验证

```bash
# 1. 编译并使用 standalone profile 运行
./mvnw clean spring-boot:run -pl llm-enterprise-gateway-server \
  -Dspring-boot.run.profiles=standalone
```

或者使用打包好的可执行 Jar 包：
```bash
java -jar llm-enterprise-gateway-server.jar --spring.profiles.active=standalone
```

#### 发起验证请求
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-alpha-test-key-12345" \
  -d '{
    "model": "qwen2.5:0.5b",
    "messages": [
      {"role": "user", "content": "你好，请说明当前系统状态"}
    ]
  }'
```

### 2.3 嵌入式本地引擎自动托管 (Embedded Runner)
如果您希望在启动网关 Java 进程时，**连手动敲命令行启动本地模型都不需要**，可开启嵌入式托管功能：
```yaml
llm:
  embedded-runner:
    enabled: true                                       # 开启自动守护
    binary-path: "/usr/local/bin/llama-server"          # 或 ollama
    model-path: "/data/models/qwen2.5-0.5b-instruct.gguf"
    port: 8081
    gpu-layers: 33                                      # 0 为纯 CPU 模式
```
网关启动时将自动通过 `ProcessBuilder` 拉起本地推理进程，轮询健康检查直到就绪；网关停机时自动通过 ShutdownHook 优雅终止子进程，杜绝孤儿进程。

---

## 三、形态二：纯企业级网关模式 (Pure Gateway Mode)

### 3.1 适用场景与核心价值
企业无需自建任何大模型或维护昂贵 GPU，统一使用公有云顶尖模型（如 DeepSeek-V3、通义千问、OpenAI、智谱等），网关承揽所有安全合规与治理职责：
- **企业财务治理**：多租户配额硬门禁、微美元成本统计、80% 软告警；
- **数据安全合规**：提示词注入攻击拦截、出站敏感数据跨 Chunk 环形滑动窗口脱敏（DLP）；
- **长程任务状态机**：ReAct 智能体多步执行与故障断点续跑。

### 3.2 对接商用公有云 API
在 `application-gateway.yml` 中配置主备线路（支持优雅平滑降级）：
```yaml
llm:
  backend:
    engine: "direct"
  provider:
    base-url: "https://api.deepseek.com/v1"
    api-key: "${DEEPSEEK_API_KEY}"
    model: "deepseek-chat"
  fallback:
    base-url: "https://dashscope.aliyuncs.com/compatible-mode/v1"
    api-key: "${QWEN_API_KEY}"
    model: "qwen-plus"
```

### 3.3 启动与环境变量覆盖
```bash
export DEEPSEEK_API_KEY="sk-your-real-deepseek-key"
export QWEN_API_KEY="sk-your-real-qwen-key"

java -jar llm-enterprise-gateway-server.jar --spring.profiles.active=gateway
```

---

## 四、形态三：分布式高并发集群模式 (Distributed Multi-Pod Mode)

### 4.1 适用场景与集群拓扑
针对中大型企业、金融机构自建私有 GPU 算力池的高吞吐场景：
- 网关采用 K8s 多副本横向伸缩（HPA），底座基于 Java 21 虚拟线程支持数十万长连接流式下发；
- 外接 Redis 集群，开启**分布式 Token 租约限流 (`DistributedRateLimiter`)**，多 Pod 共享原子预占额度；
- 开启**异步削峰写回批处理器 (`AsyncQuotaWriteBehindBatcher`)**，消除热点租户行锁互斥。

### 4.2 对接 GPU 算力池 (vLLM / Triton / Ray / SGLang)
修改 `LLM_BACKEND_ENGINE` 即可实现专有协议最佳适配：
```yaml
llm:
  backend:
    engine: "vllm"                      # 切换为 vllm / sglang / triton / ray
    max-waiting-requests: 25            # 后端排队超过 25 时触发背压保护
    max-gpu-cache-usage: 0.95           # 显存达 95% 时降权规避
  cluster:
    enabled: true
    dns-discovery: "vllm-headless.ai-inference.svc.cluster.local:8000"
    discovery-interval-seconds: 15
```

### 4.3 K8s Deployment 范例

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: llm-enterprise-hub
  namespace: ai-gateway
spec:
  replicas: 4
  selector:
    matchLabels:
      app: llm-enterprise-hub
  template:
    metadata:
      labels:
        app: llm-enterprise-hub
    spec:
      containers:
        - name: gateway
          image: your-registry/llm-enterprise-hub:latest
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "distributed"
            - name: LLM_BACKEND_ENGINE
              value: "vllm"
            - name: LLM_CLUSTER_DNS_DISCOVERY
              value: "vllm-headless.ai-inference.svc.cluster.local:8000"
            - name: REDIS_URL
              value: "redis://redis-cluster.infra.svc:6379"
            - name: MILVUS_ENDPOINT
              value: "http://milvus-standalone.infra.svc:19530"
          ports:
            - containerPort: 8080
          resources:
            requests:
              cpu: "2"
              memory: "2Gi"
            limits:
              cpu: "4"
              memory: "4Gi"
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
```

---

## 五、生产环境高可用检查清单 (Checklist)

1. [ ] **模式匹配**：确认根据实际业务场景正确指定了 `--spring.profiles.active=standalone | gateway | distributed`；
2. [ ] **虚拟线程**：确认运行时使用了 Java 21+ 且开启了 `spring.threads.virtual.enabled=true`；
3. [ ] **SQLite WAL**：单机模式下确认已开启 WAL 模式（系统默认自适应开启）；
4. [ ] **背压门禁**：高并发集群下确认配置了 `max-waiting-requests` 与 `max-gpu-cache-usage`，防止慢请求击穿后端 GPU；
5. [ ] **脱敏护栏**：出站涉及外发场景务必保持 `security.dlp.enabled=true` 与 `streaming.mode=BLOCK`。
