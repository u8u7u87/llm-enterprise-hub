# Enterprise Resilience, Dual Rate Limiting & Privacy Vault Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建企业级大模型网关的高可用与韧性基座：落地双轨滑动窗口限流器 (RPM + TPM)、三态自愈熔断器 (Circuit Breaker)、双向隐私脱敏还原 (Privacy Vault) 以及 TTFT 投机对冲竞速路由 (Request Hedging)。

**Architecture:** 
1. **Core 韧性层 (Resilience & CircuitBreaker)**：实现零外部框架依赖的 `CircuitBreaker`（`CLOSED` -> `OPEN` -> `HALF_OPEN`），具备可配置冷却时间窗口、探针并发控制与自愈机制，彻底修复 `FailoverRoutingService` 原有的单向永久熔断缺陷。
2. **双轨滑动窗口限流 (RPM + TPM RateLimiter)**：在 `TenantEntity` 扩充 `rpmLimit` 与 `tpmLimit`，通过 60 槽环形滑动时间窗原子追踪请求频次与 Token 消耗，支持入站 Prompt Token 预估与推理完成后的实际 Token 多退少补校准。
3. **双向隐私脱敏与透明还原 (Privacy Vault)**：入站拦截提取 PII 实体替换为 `<PHONE_1>` 等语义占位符写入会话隔离字典，推理完成后由网关自动透明还原，兼顾合规隐私保护与用户无感体验。
4. **投机对冲竞速路由 (Request Hedging Router)**：在虚拟线程中并行监控主模型 TTFT，超出警戒阈值（如 1500ms）自动唤醒备用模型，两路竞速首字并取消慢分支，削减 P99 尾部延迟。

**Architecture Diagram:**

```mermaid
flowchart TD
    subgraph ClientLayer ["Client / Request"]
        Req["User Prompt"]
    end

    subgraph SecurityVault ["Privacy & Rate Limit"]
        Vault["PrivacyVault (入站 PII 伪匿名化)"]
        Rate["SlidingWindowRateLimiter (RPM + TPM 双轨滑动窗口)"]
        Req --> Vault
        Vault --> Rate
    end

    subgraph ResilienceRouting ["Resilience & Routing"]
        CB["CircuitBreaker<br>(CLOSED / OPEN / HALF_OPEN)"]
        Hedge["RequestHedgingRouter<br>(TTFT 投机对冲竞速)"]
        Rate --> CB
        CB --> Hedge
    end

    subgraph ExecutionLayer ["Execution Layer (Virtual Threads)"]
        Primary["Primary LLM (Cloud High-Tier)"]
        Fallback["Fallback LLM (Local / Fast)"]
        Hedge -->|Primary Stream| Primary
        Hedge -.->|Hedging Race (>1500ms)| Fallback
    end

    subgraph OutputPipeline ["Output Pipeline"]
        DeMask["PrivacyDeMaskingTransformer (透明还原真实 PII)"]
        Primary --> DeMask
        Fallback --> DeMask
        DeMask --> Output["Final Response Stream"]
    end
```

**Tech Stack:** Java 21, Spring Boot 3.3.4, Virtual Threads, JUnit 5, AssertJ, OkHttp3 MockWebServer.

---

### Task 1: Core 层三态自愈熔断器 (`CircuitBreaker`)

**Files:**
- Create: `llm-enterprise-core/src/main/java/com/enterprise/llm/resilience/CircuitBreaker.java`
- Create: `llm-enterprise-core/src/main/java/com/enterprise/llm/resilience/CircuitBreakerConfig.java`
- Create: `llm-enterprise-core/src/main/java/com/enterprise/llm/resilience/CircuitBreakerState.java`
- Test: `llm-enterprise-core/src/test/java/com/enterprise/llm/resilience/CircuitBreakerTest.java`

- [ ] **Step 1: 编写 CircuitBreaker 状态机失败单元测试**
  - 验证 `CLOSED` 连续发生 N 次失败后跃迁为 `OPEN`；
  - 验证 `OPEN` 期间直接拒绝权限；
  - 验证经过冷却时间后自动跃迁为 `HALF_OPEN` 并允许探针通过；
  - 验证 `HALF_OPEN` 下探针成功驱动自愈回到 `CLOSED` 并清零故障计数；
  - 验证 `HALF_OPEN` 下探针失败立即重新回到 `OPEN` 并重置冷却时间。

- [ ] **Step 2: 运行测试验证失败**
  - 命令：`./mvnw test -pl llm-enterprise-core -Dtest=CircuitBreakerTest`
  - 期望：编译错误（类尚未创建）。

- [ ] **Step 3: 编写 CircuitBreaker 实现**
  - 实现基于 `AtomicReference<CircuitBreakerState>`、`AtomicInteger failureCount` 与时间戳计算的零依赖线程安全状态机。

- [ ] **Step 4: 运行测试验证全部通过**
  - 命令：`./mvnw test -pl llm-enterprise-core -Dtest=CircuitBreakerTest`
  - 期望：测试全部通过。

- [ ] **Step 5: Git 提交**
  - `git add llm-enterprise-core/src/main/java/com/enterprise/llm/resilience/ llm-enterprise-core/src/test/java/com/enterprise/llm/resilience/`
  - `git commit -m "feat(core): implement stateful self-healing CircuitBreaker"`

---

### Task 2: `FailoverRoutingService` 重构接入 `CircuitBreaker`

**Files:**
- Modify: `llm-enterprise-core/src/main/java/com/enterprise/llm/router/FailoverRoutingService.java`
- Modify: `llm-enterprise-core/src/test/java/com/enterprise/llm/router/FailoverRoutingServiceTest.java`

- [ ] **Step 1: 编写 Failover 探针自愈与三态迁移失败测试**
  - 在 `FailoverRoutingServiceTest` 增加用例：当主模型连续失败触发熔断后，在冷却时间过后，下一次请求探活主模型成功，后续请求全部恢复走主模型。

- [ ] **Step 2: 运行测试验证失败**
  - 命令：`./mvnw test -pl llm-enterprise-core -Dtest=FailoverRoutingServiceTest#shouldSelfHealWhenPrimaryRecoversAfterCooldown`
  - 期望：失败（当前代码永久停留在 fallback 模式）。

- [ ] **Step 3: 重构 FailoverRoutingService 委托 CircuitBreaker**
  - 将原有的私有 `failureCount` 替换为 `CircuitBreaker` 委托；在同步与流式主模型调用中分别接入 `circuitBreaker.tryAcquirePermission()`、`circuitBreaker.recordSuccess()` 与 `circuitBreaker.recordFailure()`。

- [ ] **Step 4: 运行测试验证自愈与原有测试全部通过**
  - 命令：`./mvnw test -pl llm-enterprise-core -Dtest=FailoverRoutingServiceTest`
  - 期望：所有测试绿色通过。

- [ ] **Step 5: Git 提交**
  - `git add llm-enterprise-core/src/main/java/com/enterprise/llm/router/FailoverRoutingService.java llm-enterprise-core/src/test/java/com/enterprise/llm/router/FailoverRoutingServiceTest.java`
  - `git commit -m "refactor(core): integrate stateful CircuitBreaker into FailoverRoutingService for self-healing"`

---

### Task 3: 双轨滑动窗口限流器 (RPM + TPM) 与校准机制

**Files:**
- Modify: `llm-enterprise-core/src/main/java/com/enterprise/llm/tenant/TenantEntity.java`
- Create: `llm-enterprise-core/src/main/java/com/enterprise/llm/ratelimit/RateLimitResult.java`
- Create: `llm-enterprise-core/src/main/java/com/enterprise/llm/ratelimit/SlidingWindowRateLimiter.java`
- Modify: `llm-enterprise-core/src/main/java/com/enterprise/llm/ratelimit/TenantRateLimiter.java`
- Test: `llm-enterprise-core/src/test/java/com/enterprise/llm/ratelimit/SlidingWindowRateLimiterTest.java`
- Modify: `llm-enterprise-core/src/test/java/com/enterprise/llm/ratelimit/TenantRateLimiterTest.java`

- [ ] **Step 1: 编写 SlidingWindowRateLimiter 与 TPM/RPM 失败测试**
  - 测试 60 槽滑动窗口中 RPM 超限拒绝；
  - 测试 TPM 超过单分钟 Token 限制拒绝；
  - 测试 `recordUsage` 多退少补校准；
  - 测试滑动时间窗口过期后槽位自动清零恢复。

- [ ] **Step 2: 运行测试验证失败**
  - 命令：`./mvnw test -pl llm-enterprise-core -Dtest=SlidingWindowRateLimiterTest`
  - 期望：类不存在失败。

- [ ] **Step 3: 编写 SlidingWindowRateLimiter 与增强 TenantRateLimiter**
  - 维护 60 秒环形数组，原子累加每秒 `requestCount` 与 `tokenCount`；
  - 提供 `tryAcquire(TenantEntity tenant, int estimatedTokens)` 与 `recordUsage(TenantEntity tenant, long actualTokens, long estimatedTokens)`；
  - 保持原有 `tryAcquire(TenantEntity tenant)` 的 100% 向后兼容。

- [ ] **Step 4: 运行测试验证通过**
  - 命令：`./mvnw test -pl llm-enterprise-core -Dtest="SlidingWindowRateLimiterTest,TenantRateLimiterTest"`
  - 期望：全部通过。

- [ ] **Step 5: Git 提交**
  - `git add llm-enterprise-core/src/main/java/com/enterprise/llm/ratelimit/ llm-enterprise-core/src/main/java/com/enterprise/llm/tenant/ llm-enterprise-core/src/test/java/com/enterprise/llm/ratelimit/`
  - `git commit -m "feat(core): implement dual sliding window rate limiter supporting RPM and TPM"`

---

### Task 4: Gateway 鉴权过滤器与 Controller 联动双轨限流与 Token 消耗校准

**Files:**
- Modify: `llm-enterprise-gateway-server/src/main/java/com/enterprise/llm/auth/ApiKeyAuthenticationFilter.java`
- Modify: `llm-enterprise-gateway-server/src/main/java/com/enterprise/llm/controller/ChatCompletionController.java`
- Modify: `llm-enterprise-gateway-server/src/test/java/com/enterprise/llm/controller/ChatCompletionControllerTest.java`

- [ ] **Step 1: 编写端到端 TPM 限流拦截与校准测试**
  - 当租户配置了 `tpmLimit=1000`，突发请求携带预估 1500 tokens 时，网关返回 429 且附带 `TPM limit exceeded`；正常请求返回后，租户实际用量完成校准。

- [ ] **Step 2: 运行测试验证失败**
  - 命令：`./mvnw test -pl llm-enterprise-gateway-server -Dtest=ChatCompletionControllerTest#shouldRejectWhenTpmExceeded`

- [ ] **Step 3: 增强 ApiKeyAuthenticationFilter 与 ChatCompletionController**
  - 在鉴权或控制器入口预估 Prompt Token；调用完成后在响应拦截处调用 `rateLimiter.recordUsage()`。

- [ ] **Step 4: 运行网关层测试验证通过**
  - 命令：`./mvnw test -pl llm-enterprise-gateway-server`

- [ ] **Step 5: Git 提交**
  - `git commit -am "feat(gateway): integrate dual RPM/TPM rate limiting and token usage reconciliation"`

---

### Task 5: Core 层双向隐私脱敏保险库 (`PrivacyVault` & `DeMaskingTransformer`)

**Files:**
- Create: `llm-enterprise-core/src/main/java/com/enterprise/llm/guard/privacy/PrivacyVault.java`
- Create: `llm-enterprise-core/src/main/java/com/enterprise/llm/guard/privacy/PrivacyMaskingService.java`
- Create: `llm-enterprise-core/src/main/java/com/enterprise/llm/guard/privacy/PrivacyDeMaskingTransformer.java`
- Test: `llm-enterprise-core/src/test/java/com/enterprise/llm/guard/privacy/PrivacyVaultTest.java`

- [ ] **Step 1: 编写双向隐私脱敏与透明还原失败测试**
  - 手机号 `13812345678` 入站被替换为 `<PHONE_1>` 并存入 Vault；
  - 外部响应中包含 `<PHONE_1>` 时被无缝还原回 `13812345678`；
  - 流式 Token 分片（如 `[<PH`, `ONE_1>]`）跨 chunk 还原测试。

- [ ] **Step 2: 运行测试验证失败**
  - 命令：`./mvnw test -pl llm-enterprise-core -Dtest=PrivacyVaultTest`

- [ ] **Step 3: 实现 PrivacyVault、MaskingService 与 DeMaskingTransformer**

- [ ] **Step 4: 运行测试验证通过**
  - 命令：`./mvnw test -pl llm-enterprise-core -Dtest=PrivacyVaultTest`

- [ ] **Step 5: Git 提交**
  - `git add llm-enterprise-core/src/main/java/com/enterprise/llm/guard/privacy/ llm-enterprise-core/src/test/java/com/enterprise/llm/guard/privacy/`
  - `git commit -m "feat(core): implement bidirectional PrivacyVault for pseudonymization and transparent de-masking"`

---

### Task 6: 投机对冲竞速路由 (`RequestHedgingRouter`)

**Files:**
- Create: `llm-enterprise-core/src/main/java/com/enterprise/llm/router/RequestHedgingRouter.java`
- Test: `llm-enterprise-core/src/test/java/com/enterprise/llm/router/RequestHedgingRouterTest.java`

- [ ] **Step 1: 编写投机对冲竞速单元测试**
  - 测试正常情况下主模型在阈值内返回首字，备用模型不被唤醒；
  - 测试主模型延迟卡顿（模拟 2000ms 超过 500ms 阈值），备用模型快速响应并接管，主模型调用被取消。

- [ ] **Step 2: 运行测试验证失败**
  - 命令：`./mvnw test -pl llm-enterprise-core -Dtest=RequestHedgingRouterTest`

- [ ] **Step 3: 实现基于虚拟线程与 ScheduledExecutor 的 RequestHedgingRouter**

- [ ] **Step 4: 运行测试验证通过**
  - 命令：`./mvnw test -pl llm-enterprise-core -Dtest=RequestHedgingRouterTest`

- [ ] **Step 5: Git 提交**
  - `git add llm-enterprise-core/src/main/java/com/enterprise/llm/router/RequestHedgingRouter.java llm-enterprise-core/src/test/java/com/enterprise/llm/router/RequestHedgingRouterTest.java`
  - `git commit -m "feat(core): implement RequestHedgingRouter for TTFT speculative racing"`

---

### Task 7: 全工程回归验证与 README/架构说明同步

**Files:**
- Modify: `README.md`
- Run: 全量 `./mvnw clean verify`

- [ ] **Step 1: 运行全工程构建与测试回归**
  - 命令：`./mvnw clean verify`
  - 期望：所有模块 100% 成功，测试总数 > 175，零 failure，零 error。

- [ ] **Step 2: 更新 README.md 中的韧性治理与双向隐私架构图示**

- [ ] **Step 3: Git 提交**
  - `git commit -am "docs: update README with enterprise resilience, dual rate limiting, and privacy vault architecture"`
