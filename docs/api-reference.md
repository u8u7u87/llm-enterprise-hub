# RESTful API 规范与接入参考 (API Reference)

LLM Enterprise Hub 提供两类接口：
1. **OpenAI 兼容接口**：无缝对接 LangChain、LlamaIndex、OpenAI 官方 SDK、NextChat、Open-WebUI 等生态；
2. **企业级治理与调度控制接口**：涵盖 RAG 知识库管理、长程 Agent 状态机断点续跑、多租户会话管理、管理员控制面与 Prometheus 监控。

---

## 目录导航

- [通用规范与鉴权](#通用规范与鉴权)
- [1. OpenAI 兼容推理接口](#1-openai-兼容推理接口)
  - [1.1 同步对话补全 (Chat Completions)](#11-同步对话补全-chat-completions)
  - [1.2 流式对话补全 (Server-Sent Events)](#12-流式对话补全-server-sent-events)
  - [1.3 语义缓存命中验证](#13-语义缓存命中验证)
  - [1.4 统一错误响应规范](#14-统一错误响应规范)
- [2. 知识库与 RAG 接口](#2-知识库与-rag-接口)
  - [2.1 摄取切片文档 (Document Ingestion)](#21-摄取切片文档-document-ingestion)
  - [2.2 租户隔离向量相似度检索 (Vector Search)](#22-租户隔离向量相似度检索-vector-search)
- [3. 企业级 Agent 任务状态机与断点续跑](#3-企业级-agent-任务状态机与断点续跑)
  - [3.1 查询 Agent 任务列表](#31-查询-agent-任务列表)
  - [3.2 获取任务详情与 Checkpoint 状态](#32-获取任务详情与-checkpoint-状态)
  - [3.3 故障自愈与断点恢复执行 (Resume Task)](#33-故障自愈与断点恢复执行-resume-task)
- [4. 服务端多租户会话管理](#4-服务端多租户会话管理)
  - [4.1 创建新会话](#41-创建新会话)
  - [4.2 获取会话列表](#42-获取会话列表)
  - [4.3 获取会话详情与对话历史](#43-获取会话详情与对话历史)
  - [4.4 删除会话](#44-删除会话)
- [5. 管理面与运营大盘接口](#5-管理面与运营大盘接口)
  - [5.1 租户全量列表](#51-租户全量列表)
  - [5.2 注册或更新租户](#52-注册或更新租户)
  - [5.3 调整租户 Token 预算与配额](#53-调整租户-token-预算与配额)
  - [5.4 快速启停租户](#54-快速启停租户)
  - [5.5 运营大盘实时数据概览](#55-运营大盘实时数据概览)
  - [5.6 分钟级时序统计流水](#56-分钟级时序统计流水)
- [6. 可观测性与健康检查](#6-可观测性与健康检查)

---

## 通用规范与鉴权

- **基础 URL**：`http://localhost:8080`
- **数据格式**：所有请求体与响应体默认采用 `application/json; charset=UTF-8`；
- **鉴权方式**：
  - **业务端点（OpenAI 兼容 / RAG / 会话）**：使用标准 Bearer Token：
    ```http
    Authorization: Bearer <TENANT_API_KEY>
    ```
    网关安全过滤器将自动识别 Key 对应的租户（如 `tenant-alpha`），注入 `TenantSecurityContext`，并按租户规则实施限流、配额扣减与数据物理隔离。
  - **管理端点（/v1/admin/*、/v1/agent/tasks/*）**：使用 Admin 专用密钥：
    ```http
    X-Admin-Key: <ADMIN_SECRET_KEY>
    ```
  - **链路追踪注入（可选）**：支持标准 W3C 分布式链路头 `traceparent`：
    ```http
    traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
    ```

---

## 1. OpenAI 兼容推理接口

### 1.1 同步对话补全 (Chat Completions)

- **路径**：`POST /v1/chat/completions`
- **请求头**：
  - `Content-Type: application/json`
  - `Authorization: Bearer sk-alpha-test-key-12345`

#### 请求参数 (JSON Body)
| 参数名 | 类型 | 必填 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `model` | string | 是 | - | 模型名称或虚拟别名（如 `gpt-4o`, `deepseek-r1`, `fast`） |
| `messages` | array | 是 | - | 对话消息数组，每项包含 `role` (`system`/`user`/`assistant`) 与 `content` |
| `stream` | boolean | 否 | `false` | 是否开启 Server-Sent Events 流式响应 |
| `temperature` | number | 否 | `0.7` | 采样温度 (0.0 ~ 2.0) |
| `max_tokens` | integer | 否 | `null` | 最大生成 Token 数（用于前置租约额度精准预占） |

#### cURL 示例
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-alpha-test-key-12345" \
  -d '{
    "model": "qwen2.5:0.5b",
    "messages": [
      {"role": "system", "content": "你是一个严谨的企业级助手。"},
      {"role": "user", "content": "请简要说明大模型网关的核心价值。"}
    ],
    "temperature": 0.7
  }'
```

#### 响应示例 (HTTP 200 OK)
```json
{
  "id": "chatcmpl-a9f8b1c2-3d4e-4f5a-8b9c-0d1e2f3a4b5c",
  "object": "chat.completion",
  "created": 1729814400,
  "model": "qwen2.5:0.5b",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "大模型网关的核心价值在于为企业建立统一的 AI 基础设施接入中枢，提供多租户配额控制、高并发平滑限流、双向数据安全脱敏、故障容灾降级与统一可观测性审计。"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 28,
    "completion_tokens": 56,
    "total_tokens": 84
  }
}
```

---

### 1.2 流式对话补全 (Server-Sent Events)

设置 `"stream": true`，网关将返回标准 SSE 数据流。在流式传输过程中，网关的**跨 Chunk 环形滑动窗口脱敏引擎**会对数据块做实时合规校验，若命中拦截规则可优雅熔断或掩码替换。

#### cURL 示例
```bash
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-alpha-test-key-12345" \
  -d '{
    "model": "qwen2.5:0.5b",
    "messages": [
      {"role": "user", "content": "请写一首关于技术与未来的现代短诗。"}
    ],
    "stream": true
  }'
```

#### 响应流示例
```text
data: {"id":"chatcmpl-stream-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"代码"},"finish_reason":null}]}

data: {"id":"chatcmpl-stream-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"如星光流淌，"},"finish_reason":null}]}

data: {"id":"chatcmpl-stream-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"重构着思维的疆界。"},"finish_reason":null}]}

data: {"id":"chatcmpl-stream-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

---

### 1.3 语义缓存命中验证

当相同的请求（或经由 Dense Vector 余弦相似度判定的等价意图请求）再次到达时，网关直接返回两级缓存中的答案，响应耗时通常低于 1ms，并在 HTTP Response Header 中标识：

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Cache-Lookup: HIT
```

---

### 1.4 统一错误响应规范

系统所有异常（鉴权失败、租约超额、提示词注入攻击、上游熔断）均遵循 OpenAI 标准错误 JSON 格式：

#### 示例 1：提示词注入拦截 (HTTP 400 Bad Request)
```json
{
  "error": "Security violation: Potential prompt injection detected"
}
```

#### 示例 2：租户日配额超限或滑动窗口限流 (HTTP 429 Too Many Requests)
```json
{
  "status": 429,
  "error": "Rate limit or daily token quota exceeded: Tenant TPM limit exceeded, current window usage: 850000, allowed: 500000"
}
```
*注：429 响应头会自动携带 `Retry-After: <秒数>`，告知客户端需等待的恢复时间。*

#### 示例 3：上游推理节点宕机或降级失败 (HTTP 502 / 503)
```json
{
  "error": {
    "message": "All primary and fallback model nodes failed or circuit breaker is OPEN",
    "type": "llm_error",
    "code": "upstream_circuit_open",
    "param": null
  }
}
```

---

## 2. 知识库与 RAG 接口

系统内置前置 RAG 检索流水线，所有文档和向量索引基于租户物理隔离。

### 2.1 摄取切片文档 (Document Ingestion)

- **路径**：`POST /v1/knowledge/documents`
- **鉴权**：`Authorization: Bearer <API_KEY>`

支持纯文本与 Markdown 格式。若文档名为 `.md` 或正文中含有 Markdown 标题与表格，系统自动激活 `MarkdownStructureChunker` 提取层级面包屑上下文。

#### 请求示例
```bash
curl -X POST http://localhost:8080/v1/knowledge/documents \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-alpha-test-key-12345" \
  -d '{
    "docName": "enterprise-security-guidelines.md",
    "content": "# 企业信息安全规范\n## 密码管理策略\n所有内部员工的系统密码必须至少包含 12 个字符，包含大写字母、小写字母、数字及特殊符号。\n## 访问控制\n生产数据库严禁通过外网直接访问，必须通过堡垒机并启用双因子认证。",
    "chunkSize": 300,
    "chunkOverlap": 50
  }'
```

#### 响应示例 (HTTP 200 OK)
```json
{
  "status": "success",
  "docName": "enterprise-security-guidelines.md",
  "chunksCreated": 2,
  "collection": "enterprise_hub_rag"
}
```

---

### 2.2 租户隔离向量相似度检索 (Vector Search)

- **路径**：`POST /v1/knowledge/search`
- **鉴权**：`Authorization: Bearer <API_KEY>`

#### 请求示例
```bash
curl -X POST http://localhost:8080/v1/knowledge/search \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-alpha-test-key-12345" \
  -d '{
    "query": "生产数据库如何进行网络访问？",
    "topK": 2,
    "minScore": 0.6
  }'
```

#### 响应示例 (HTTP 200 OK)
```json
{
  "query": "生产数据库如何进行网络访问？",
  "count": 1,
  "results": [
    {
      "chunkId": "chunk-b1c2d3e4",
      "docName": "enterprise-security-guidelines.md",
      "content": "【企业信息安全规范 > 访问控制】生产数据库严禁通过外网直接访问，必须通过堡垒机并启用双因子认证。",
      "score": 0.892,
      "tenantId": "tenant-alpha"
    }
  ]
}
```

---

## 3. 企业级 Agent 任务状态机与断点续跑

系统将智能体工具循环抽象为带持久化快照的长程任务，支持由于网络闪断、第三方 Tool 异常中断时的**无损现场断点恢复 (Resume)**。

### 3.1 查询 Agent 任务列表

- **路径**：`GET /v1/agent/tasks`
- **鉴权**：`X-Admin-Key: <ADMIN_SECRET_KEY>`

#### 请求示例
```bash
curl -X GET http://localhost:8080/v1/agent/tasks \
  -H "X-Admin-Key: test-admin-secret-key-12345"
```

#### 响应示例 (HTTP 200 OK)
```json
[
  {
    "taskId": "task-7b9e1a2f-89c0",
    "tenantId": "tenant-alpha",
    "status": "SUSPENDED",
    "currentStep": 3,
    "maxSteps": 10,
    "createdAt": 1729810000000,
    "updatedAt": 1729810015000
  }
]
```

---

### 3.2 获取任务详情与 Checkpoint 状态

- **路径**：`GET /v1/agent/tasks/{id}`
- **鉴权**：`X-Admin-Key: <ADMIN_SECRET_KEY>`

```bash
curl -X GET http://localhost:8080/v1/agent/tasks/task-7b9e1a2f-89c0 \
  -H "X-Admin-Key: test-admin-secret-key-12345"
```

#### 响应示例 (HTTP 200 OK)
```json
{
  "taskId": "task-7b9e1a2f-89c0",
  "tenantId": "tenant-alpha",
  "status": "SUSPENDED",
  "currentStep": 3,
  "maxSteps": 10,
  "contextSnapshot": "{\"messages\":[{\"role\":\"user\",\"content\":\"分析近30天服务器错误日志并汇总成报表\"}]}",
  "completedToolActions": [
    {"tool": "fetch_server_logs", "input": "{\"days\": 30}", "output": "{\"total_errors\": 42}"},
    {"tool": "aggregate_error_codes", "input": "{\"logs_id\": \"log-42\"}", "output": "{\"500\": 38, \"502\": 4}"}
  ],
  "pendingToolCall": {
    "name": "generate_excel_report",
    "arguments": "{\"summary\": \"500 errors count: 38\"}"
  }
}
```

---

### 3.3 故障自愈与断点恢复执行 (Resume Task)

- **路径**：`POST /v1/agent/tasks/{id}/resume`
- **鉴权**：`X-Admin-Key: <ADMIN_SECRET_KEY>`

网关从数据库加载该 Task 的最新 Checkpoint，重构会话上下文及已执行工具产物，跳过已成功的动作，直接从挂起点恢复调度直至任务终态。

#### 请求示例
```bash
curl -X POST http://localhost:8080/v1/agent/tasks/task-7b9e1a2f-89c0/resume \
  -H "X-Admin-Key: test-admin-secret-key-12345"
```

#### 响应示例 (HTTP 200 OK)
返回最终执行完毕的 `ChatCompletionResponse`，状态自动跃迁为 `COMPLETED`。

---

## 4. 服务端多租户会话管理

支持服务端全托管的多轮对话 Session，提供工作记忆持久化与按需语义检索。

### 4.1 创建新会话

- **路径**：`POST /v1/sessions`
- **鉴权**：`Authorization: Bearer <API_KEY>`

```bash
curl -X POST http://localhost:8080/v1/sessions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-alpha-test-key-12345" \
  -d '{
    "title": "项目代码架构设计研讨",
    "userId": "user-8848"
  }'
```

#### 响应示例
```json
{
  "sessionId": "sess-4a8b-9c2d-e1f0",
  "tenantId": "tenant-alpha",
  "userId": "user-8848",
  "title": "项目代码架构设计研讨",
  "messageCount": 0,
  "createdAt": 1729814400000,
  "updatedAt": 1729814400000
}
```

### 4.2 获取会话列表与历史详情

- **列表**：`GET /v1/sessions?limit=20&offset=0`
- **详情**：`GET /v1/sessions/{sessionId}`
- **删除**：`DELETE /v1/sessions/{sessionId}`

---

## 5. 管理面与运营大盘接口

所有管理端点统一受到 `X-Admin-Key` 严格保护。

### 5.1 租户全量列表

```bash
curl -X GET http://localhost:8080/v1/admin/tenants \
  -H "X-Admin-Key: test-admin-secret-key-12345"
```

### 5.2 注册或更新租户

```bash
curl -X POST http://localhost:8080/v1/admin/tenants \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: test-admin-secret-key-12345" \
  -d '{
    "id": "tenant-beta",
    "apiKey": "sk-beta-finance-key-67890",
    "tier": "ENTERPRISE_VIP",
    "qps": 200,
    "dailyTokenQuota": 50000000,
    "enabled": true
  }'
```

### 5.3 调整租户 Token 预算与配额

```bash
curl -X PUT http://localhost:8080/v1/admin/tenants/tenant-beta/quota \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: test-admin-secret-key-12345" \
  -d '{
    "daily_token_quota": 100000000
  }'
```

### 5.4 快速启停租户

```bash
curl -X PATCH http://localhost:8080/v1/admin/tenants/tenant-beta/status \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: test-admin-secret-key-12345" \
  -d '{"enabled": false}'
```

### 5.5 运营大盘实时数据概览

```bash
curl -X GET http://localhost:8080/v1/admin/analytics/overview \
  -H "X-Admin-Key: test-admin-secret-key-12345"
```

#### 响应示例
```json
{
  "activeTenants": 12,
  "totalRequestsToday": 145890,
  "totalTokensToday": 18954002,
  "avgLatencyMs": 425.6,
  "cacheHitRatePercent": 34.2,
  "securityInterceptsCount": 27
}
```

### 5.6 分钟级时序统计流水

```bash
curl -X GET "http://localhost:8080/v1/admin/metrics/timeline?limit=30" \
  -H "X-Admin-Key: test-admin-secret-key-12345"
```

---

## 6. 可观测性与健康检查

| 端点 | 协议 | 鉴权 | 描述 |
| :--- | :--- | :--- | :--- |
| `GET /actuator/health` | HTTP | 无 | 服务健康状态探针（Liveness / Readiness） |
| `GET /actuator/prometheus` | HTTP | 无 / 内网 | Prometheus Scrape 抓取端点（包含 TTFT、TPS、租约拒绝等企业级度量） |
| `GET /` | HTTP | 无 | 内嵌零依赖现代化 Web 监控运维大盘（自带实时折线图与租户管理控制台） |
