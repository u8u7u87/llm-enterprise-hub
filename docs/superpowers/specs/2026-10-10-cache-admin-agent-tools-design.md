# Phase 4 Design Specification: Two-Level Cache, Admin Analytics API & Agent Tool Calling Orchestration

## 1. Goal
Build the complete enterprise-grade evolution for `llm-enterprise-hub`:
1. Intelligent Two-Level Cache Engine (L1 Exact Hash + L2 Semantic Similarity) to eliminate duplicate token consumption and provide sub-millisecond responses.
2. Tenant Management & Operational Analytics REST API (`AdminController` & `AnalyticsService`).
3. Automated Agent Tool Calling Orchestration & Safety Sandbox (`ToolRegistry`, `AgentToolOrchestrator`, ReAct loop with `ToolActionGatekeeper`).

---

## 2. Architecture & Data Flow

```mermaid
graph TD
    Client[Client / SDK Request] --> Auth[1. ApiKeyAuthenticationFilter]
    Auth --> CacheCheck{2. CacheEngine: L1/L2 Cache Lookup}
    
    CacheCheck -->|Cache HIT| HitResp[Return Cached Response (0 Token, <1ms)<br/>Record Saved Tokens Metric]
    CacheCheck -->|Cache MISS| Guard[3. Prompt Guardrails & Token Pre-Reservation]
    
    Guard --> Orchestrator[4. AgentToolOrchestrator: ReAct Loop Engine]
    Orchestrator --> LLM[5. LLM Provider (Primary / Ollama Fallback)]
    
    LLM --> Decision{Response Type}
    Decision -->|tool_calls Present| Gatekeeper[6. ToolActionGatekeeper Safety Validation]
    Gatekeeper -->|Allowed| ToolExec[7. Execute Registered Tool via ToolRegistry]
    ToolExec --> ReActLoop[Append tool message & Re-invoke LLM (Max Iterations=5)]
    ReActLoop --> LLM
    
    Decision -->|Final Text Response| PostProcess[8. Output DLP Sanitization & Token Settlement]
    PostProcess --> WriteCache[Write to L1 & L2 Cache]
    WriteCache --> Client
    
    subgraph Management Plane
        AdminUser[Admin Client] --> AdminAuth[Admin Key Auth: X-Admin-Key]
        AdminAuth --> AdminAPI[/v1/admin/tenants CRUD & /v1/admin/analytics/overview]
    end
```

---

## 3. Core Components

### 3.1 Intelligent Two-Level Cache Engine (`CacheEngine`)
- **L1 Exact Match**: Normalizes `model`, `messages`, `temperature` into a canonical string and hashes via SHA-256. Lookup is $O(1)$ from an in-memory concurrent LRU cache with configurable TTL.
- **L2 Semantic Similarity**: Tokenizes prompts into word-level shingles/sets and computes Jaccard similarity. Matches if score $\ge$ `semanticThreshold` (default 0.88).
- **Streaming Support**: Emits cached content as simulated SSE chunks to preserve client streaming UI/UX.
- **Analytics Metrics**: Tracks `l1Hits`, `l2Hits`, `misses`, and `savedTokens`.

### 3.2 Tenant Administration & Analytics Control Plane
- **Admin Authentication**: Filter or interceptor verifying `X-Admin-Key` header against `admin.secret-key`.
- **Tenant Management Endpoints (`/v1/admin/tenants`)**:
  - `GET /v1/admin/tenants`: List tenants.
  - `POST /v1/admin/tenants`: Register new tenant with custom API key, quota, and QPS.
  - `PUT /v1/admin/tenants/{id}/quota`: Update `dailyTokenQuota` or reset `usedTokens`.
  - `PATCH /v1/admin/tenants/{id}/status`: Toggle `enabled` state.
- **Analytics Overview (`/v1/admin/analytics/overview`)**:
  - Aggregates total requests, success rate, violations count (400, 401, 403, 429), total used tokens, saved tokens via cache, and average response latency.

### 3.3 Agent Tool Calling Orchestrator (`AgentToolOrchestrator`)
- **`ToolRegistry`**: In-memory registry of executable tools (`ToolDefinition`, `ToolHandler`).
- **`AgentToolOrchestrator`**:
  - Inspects LLM response for `tool_calls`.
  - For each tool call, constructs `ToolAction(name, arguments)` and invokes `ToolActionGatekeeper.validateAction(action, currentTenantId)`.
  - Executes tool handler and captures result.
  - Adds assistant message with tool calls and tool result message to conversation history.
  - Re-invokes LLM up to `maxIterations = 5` until assistant outputs final content or halts.
