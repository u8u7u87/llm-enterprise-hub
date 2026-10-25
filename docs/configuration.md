# 生产级配置完全指南 (Configuration Guide)

LLM Enterprise Hub 的配置遵循 Spring Boot 标准体系，支持 `application.yml`、命令行参数以及操作系统环境变量注入。本文档系统整理所有配置项、默认值、环境变量映射及高并发生产调优指南。

---

## 目录导航

- [一、配置总览与环境变量映射](#一配置总览与环境变量映射)
- [二、完整配置项字典](#二完整配置项字典)
  - [1. 基础服务与 Java 21 虚拟线程](#1-基础服务与-java-21-虚拟线程)
  - [2. 推理集群拓扑与负载均衡 (vLLM / Triton / Ollama)](#2-推理集群拓扑与负载均衡-vllm--triton--ollama)
  - [3. 流量路由治理 (Canary 灰度 / 影子流量 / 投机对冲)](#3-流量路由治理-canary-灰度--影子流量--投机对冲)
  - [4. 多租户配额、限流与异步削峰批处理](#4-多租户配额限流与异步削峰批处理)
  - [5. 双向安全防护网 (Prompt 注入 / DLP / 工具越权)](#5-双向安全防护网-prompt-注入--dlp--工具越权)
  - [6. 两级缓存与向量语义加速](#6-两级缓存与向量语义加速)
  - [7. RAG 知识增强与 Milvus 向量库](#7-rag-知识增强与-milvus-向量库)
  - [8. 上下文预算与滑动修剪](#8-上下文预算与滑动修剪)
  - [9. 管理面与 Prometheus 可观测性](#9-管理面与-prometheus-可观测性)
- [三、高并发生产环境调优建议 (Production Tuning)](#三高并发生产环境调优建议-production-tuning)

---

## 一、配置总览与环境变量映射

在容器化（Docker / Kubernetes）部署时，推荐通过大写下划线环境变量覆盖 YAML 配置：

| YAML 配置项 | 对应环境变量 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `server.port` | `SERVER_PORT` | `8080` | 服务监听端口 |
| `spring.threads.virtual.enabled` | `SPRING_THREADS_VIRTUAL_ENABLED` | `true` | 启用 Java 21 虚拟线程支持 |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:sqlite:hub-data.db` | 本地 SQLite 存储文件路径 |
| `llm.provider.base-url` | `LLM_PROVIDER_BASE_URL` | `http://localhost:11434/v1` | 主推理集群端点 (OpenAI 协议) |
| `llm.provider.api-key` | `LLM_PROVIDER_API_KEY` | `ollama` | 主推理集群 API 密钥 |
| `llm.fallback.base-url` | `LLM_FALLBACK_BASE_URL` | `http://localhost:11434/v1` | 容灾降级模型端点 |
| `llm.cluster.enabled` | `LLM_CLUSTER_ENABLED` | `false` | 开启 K8s Headless DNS 动态自感知 |
| `llm.cluster.dns-discovery` | `LLM_CLUSTER_DNS_DISCOVERY` | `""` | K8s Headless Service 域名 |
| `milvus.endpoint` | `MILVUS_ENDPOINT` | `http://127.0.0.1:19530` | Milvus 2.x 向量数据库端点 |
| `milvus.api-key` | `MILVUS_API_KEY` | `""` | Milvus 鉴权 Token (可选) |
| `admin.secret-key` | `ADMIN_SECRET_KEY` | `test-admin-secret-key-12345` | 管理员控制面与监控看板鉴权密钥 |

---

## 二、完整配置项字典

### 1. 基础服务与 Java 21 虚拟线程

```yaml
server:
  port: 8080

spring:
  application:
    name: llm-enterprise-hub
  threads:
    virtual:
      enabled: true                  # 关键：开启虚拟线程后，每个请求与 I/O 阻塞均使用纤程处理，支持上万级并发
  datasource:
    url: jdbc:sqlite:hub-data.db     # SQLite 数据文件
    driver-class-name: org.sqlite.JDBC
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
```

---

### 2. 推理集群拓扑与负载均衡 (vLLM / Triton / Ollama)

```yaml
llm:
  # 推理后端引擎选择：direct (无框架/默认), vllm, sglang, triton, ray, 或自定义扩展
  backend:
    engine: "direct"                         # 默认 direct (无框架独立模式)
    max-waiting-requests: 20                 # 后端排队过载阈值
    max-gpu-cache-usage: 0.95                # GPU 显存饱和阈值 (95%)

  # 远程 GPU 推理集群自感知与调度 (可选)
  cluster:
    enabled: false                           # 集群动态调度总开关
    dns-discovery: ""                        # K8s Headless Service (如: vllm-headless.ai.svc.cluster.local:8000)
    static-nodes: ""                         # 静态节点列表 (逗号分隔，如: http://10.0.1.1:8000/v1,http://10.0.1.2:8000/v1)
    discovery-interval-seconds: 15           # 节点拉取与健康探活周期（秒）

  # 主推模型端点 (云端服务或本地推理引擎)
  provider:
    base-url: "http://localhost:11434/v1"
    api-key: "ollama"
    model: "qwen2.5:0.5b"
    timeout-seconds: 60                     # HTTP Socket 超时时间

  # 终极熔断降级模型 (本地轻量级部署，保证绝对可用性)
  fallback:
    base-url: "http://localhost:11434/v1"
    api-key: "ollama"
    model: "qwen2.5:0.5b"

  # 自适应网络重试与退避
  resilience:
    max-rate-limit-retries: 2                # 遇到 429 时的最大重试次数
    backoff-base-ms: 100                     # 指数退避基数 (毫秒)
```

---

### 3. 流量路由治理 (Canary 灰度 / 影子流量 / 投机对冲)

```yaml
llm:
  # Canary 百分比灰度切流
  canary:
    enabled: false
    canary-model: "deepseek-r1"              # 候选新模型
    weight-percent: 10                       # 灰度流量比例 (0 ~ 100)
    sticky-by-session: true                  # 会话一致性哈希，同会话锁定同一模型
    sticky-by-tenant: false                  # 按租户维度锁定
    tenant-overrides:                        # 强制 100% 切流至灰度模型的租户白名单
      - "tenant-qa-internal"

  # 生产级影子流量镜像 (Shadow Traffic Mirroring)
  shadow:
    enabled: false                           # 影子流量开关
    shadow-model: "qwen2.5:72b"              # 镜像接收模型
    sample-rate: 0.2                         # 采样率 (0.0 ~ 1.0)
    audit-log-enabled: true                  # 记录主备生成耗时与效果对比日志

  # 低延迟投机对冲竞速路由 (Request Hedging)
  hedging:
    enabled: false
    delay-window-ms: 1500                    # 主模型未吐出首字的竞速超时时间 (毫秒)
```

---

### 4. 多租户配额、限流与异步削峰批处理

```yaml
llm:
  quota:
    # 异步削峰写回批处理器 (消除数据库热行行锁冲突)
    write-behind:
      enabled: false                         # 生产高并发压测或超千 QPS 建议设为 true
      flush-interval-ms: 200                 # 批次合并刷盘时间间隔 (毫秒)
      batch-size: 100                        # 单次批处理更新最大条目数

    # 分布式 Token 租约限流 (Redis Lua 脚本原子预占)
    distributed:
      enabled: false                         # 生产多 Pod 部署时开启
      redis-url: "redis://localhost:6379"

  # 默认全局限流阈值 (各租户可通过 DB 自定义覆盖)
  ratelimit:
    default-rpm: 600                         # 默认每分钟请求数 (RPM)
    default-tpm: 1000000                     # 默认每分钟 Token 数 (TPM)
```

---

### 5. 双向安全防护网 (Prompt 注入 / DLP / 工具越权)

```yaml
security:
  # 入站提示词注入防御规则
  guardrail:
    prompt:
      enabled: true
      patterns:
        - "(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions"
        - "(?i)disregard\\s+(all\\s+)?(previous|prior|above)\\s+instructions"
        - "(?i)you\\s+are\\s+now\\s+in\\s+dan\\s+mode"
        - "(?i)reveal\\s+your\\s+(system\\s+prompt|instructions)"
        - "(?i)jailbreak"
        - "(?i)system\\s*:\\s*override"

  # 出站敏感数据防泄漏脱敏 (DLP)
  dlp:
    enabled: true
    rules:
      - id: jwt
        name: "JWT Authentication Token"
        pattern: "\\bey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+\\b"
        mask-type: REPLACE_TAG
        replacement: "[REDACTED_JWT]"
        enabled: true
      - id: openai_key
        name: "API Key Pattern"
        pattern: "\\bsk-[a-zA-Z0-9_-]{20,}\\b"
        mask-type: REPLACE_TAG
        replacement: "[REDACTED_API_KEY]"
        enabled: true
      - id: phone
        name: "Mobile Phone"
        pattern: "(?<!\\d)1[3-9]\\d{9}(?!\\d)"
        mask-type: REPLACE_TAG
        replacement: "[REDACTED_PHONE]"
        enabled: true
      - id: email
        name: "Email Address"
        pattern: "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
        mask-type: REPLACE_TAG
        replacement: "[REDACTED_EMAIL]"
        enabled: true

  # Agent 工具调用安全沙箱与越权守门
  tools:
    enabled: true
    tenant-isolation-enabled: true           # 强校验参数中的 tenant_id 是否属于当前调用者
    tenant-identity-fields:
      - "tenant_id"
      - "org_id"
      - "account_id"
    danger-patterns:                         # 危险指令拦截
      - "(?i)rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|--recursive|-[a-zA-Z]*f[a-zA-Z]*r)"
      - "(?i)drop\\s+table"
      - "(?i)drop\\s+database"
      - "(?i)shutdown\\s+-h"

  # 流式敏感数据实时在线滑动窗口拦截
  streaming:
    mode: "BLOCK"                            # 拦截动作: BLOCK (熔断抛错) 或 MASK (掩码替换)
    overlap-size: 64                         # 跨 Chunk 环形滑动窗口重叠字符宽度
```

---

### 6. 两级缓存与向量语义加速

```yaml
cache:
  enabled: true                              # 缓存总开关
  ttl-seconds: 3600                          # 缓存条目有效期 (秒)
  semantic-threshold: 0.88                   # L2 语义相似度阈值 (0.0 ~ 1.0)
  max-capacity: 10000                        # 内存中最多保留缓存条目数
```

---

### 7. RAG 知识增强与 Milvus 向量库

```yaml
rag:
  enabled: true                              # RAG 检索流水线开关
  top-k: 3                                   # 检索召回切片数
  min-score: 0.5                             # 向量余弦相似度下限
  embedding:
    base-url: "http://localhost:11434/v1"   # Embedding 服务端点
    api-key: "ollama"
    model: "bge-m3"                         # 向量表征模型
  guardrail:
    pass-threshold: 0.65                     # 事实忠实度评估通过阈值
    warn-threshold: 0.40                     # 幻觉告警阈值

milvus:
  endpoint: "http://127.0.0.1:19530"        # Milvus 2.x 集群地址 (若设为 disabled 则自动退避到内嵌余弦计算)
  collection: "enterprise_hub_rag"          # 向量集合
  api-key: ""                               # 鉴权 Token
```

---

### 8. 上下文预算与滑动修剪

```yaml
llm:
  context:
    max-tokens: 4096                         # 会话窗口最大 Token 预算
    reserved-output-tokens: 1024             # 预留给模型生成回答的 Token 空间
    strategy: "SLIDING_WINDOW_TRIM"          # 修剪策略: SLIDING_WINDOW_TRIM / DISTILLATION
```

---

### 9. 管理面与 Prometheus 可观测性

```yaml
admin:
  secret-key: "test-admin-secret-key-12345"  # 管理员鉴权密钥

logging:
  pattern:
    console: "%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %clr([%15.15t]){faint} %clr(%-5p) %clr([%X{traceId:-}][%X{tenantId:-}]){cyan} %clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n%wEx"

management:
  endpoints:
    web:
      exposure:
        include: "health,info,prometheus"
  endpoint:
    prometheus:
      enabled: true
  prometheus:
    metrics:
      export:
        enabled: true
```

---

## 三、高并发生产环境调优建议 (Production Tuning)

### 1. JVM 虚拟线程与 GC 调优
在 Java 21+ 运行时，由于虚拟线程创建极其轻量（堆上仅百字节），应避免使用针对传统平台线程的限制池大小参数，重点关注堆内存与元空间：

```bash
java -server \
  -Xms4g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=50 \
  -Djdk.virtualThreadScheduler.parallelism=16 \
  -jar llm-enterprise-gateway-server.jar
```

### 2. SQLite 高并发 WAL 模式调优
系统内置的持久化引擎基于 SQLite，在开启多并发读写时，务必确认已开启 WAL（Write-Ahead Logging）模式：
```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 10000;
PRAGMA temp_store = MEMORY;
```
*注：系统在数据源启动时已内置执行上述 PRAGMA，确保并发读请求不会阻塞写操作。*

### 3. 高并发异步削峰配置 (`llm.quota.write-behind.enabled=true`)
在每秒数千 QPS 的大促或高吞吐批处理场景下，同一租户的所有请求并发更新 SQLite/MySQL 会产生严重的行锁竞争。开启异步削峰写回后：
- 内存微批次聚合（`flush-interval-ms: 200`, `batch-size: 100`）；
- 使用 Spring JDBC `batchUpdate` 批量提交；
- 数据库写 IO 压力削减 95% 以上。
