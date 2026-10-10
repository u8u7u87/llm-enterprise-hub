# Phase 2: Enterprise Gateway Control Plane, SQLite Multi-Tenant Governance, Failover Routing & Prompt Guardrails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the enterprise-grade gateway governance plane for `llm-enterprise-hub`: SQLite-backed multi-tenant API key authentication and rate limiting, dynamic failover routing (primary cloud LLM -> local Ollama fallback), and prompt injection security guardrails.

**Architecture Overview:**
Incoming requests pass through an authentication filter verifying client API keys against a lightweight embedded SQLite database. Validated requests are checked by a per-tenant Token Bucket rate limiter. The request is then scanned by a Prompt Guardrail for injection attacks. Finally, `ModelRoutingService` executes the request against the primary model with an automated Circuit Breaker fallback to the local Ollama instance if the primary provider times out or fails.

```mermaid
graph TD
    Client[Client / SDK Request] --> AuthFilter[1. ApiKeyAuthFilter (SQLite Tenant Lookup)]
    AuthFilter --> RateLimiter[2. TokenBucketRateLimiter (QPS & Quota)]
    RateLimiter --> Guardrail[3. PromptGuardrailService (Injection Detection)]
    Guardrail --> Router[4. FailoverRoutingService (Circuit Breaker)]
    Router -->|Primary: e.g. DeepSeek / Remote| PrimaryLLM[Primary Cloud LLM]
    Router -.->|On Failure / 5xx / Timeout| FallbackLLM[Fallback: Local Ollama qwen2.5:0.5b]
    Router --> Trace[OTel / Langfuse Metrics]
```

**Tech Stack:**
- Spring Boot 3.3.x + Java 21
- SQLite (via `org.xerial:sqlite-jdbc`) + Spring Data JDBC / Spring JDBC Template (zero heavy ORM overhead)
- Bucket4j (or native lock-free atomic TokenBucket) for QPS rate limiting
- JUnit 5 + AssertJ + MockWebServer for TDD unit/integration tests

---

### Task 1: SQLite Database Integration & Tenant Repository

**Files:**
- Modify: `pom.xml` (add `sqlite-jdbc` and `spring-boot-starter-jdbc`)
- Create: `src/main/resources/schema.sql`
- Create: `src/main/java/com/enterprise/llm/tenant/TenantEntity.java`
- Create: `src/main/java/com/enterprise/llm/tenant/TenantRepository.java`
- Create: `src/main/java/com/enterprise/llm/tenant/TenantService.java`
- Test: `src/test/java/com/enterprise/llm/tenant/TenantRepositoryTest.java`

- [x] **Step 1: Add SQLite & JDBC dependencies to `pom.xml`**
- [x] **Step 2: Write failing repository test for CRUD & API Key lookup**
- [x] **Step 3: Implement SQLite schema (`schema.sql`) and `TenantRepository`**
- [x] **Step 4: Run test to verify passes**
- [x] **Step 5: Git commit**

---

### Task 2: Multi-Tenant API Key Authentication & Context Injection

**Files:**
- Create: `src/main/java/com/enterprise/llm/auth/ApiKeyAuthenticationFilter.java`
- Create: `src/main/java/com/enterprise/llm/auth/TenantSecurityContext.java`
- Create: `src/main/java/com/enterprise/llm/auth/UnauthorizedException.java`
- Test: `src/test/java/com/enterprise/llm/auth/ApiKeyAuthenticationFilterTest.java`

- [x] **Step 1: Write integration test for valid API Key, missing key (401), invalid key (401), and disabled tenant (403)**
- [x] **Step 2: Implement `TenantSecurityContext` and `ApiKeyAuthenticationFilter`**
- [x] **Step 3: Run test to verify passes**
- [x] **Step 4: Git commit**

---

### Task 3: Per-Tenant Token Bucket Rate Limiting & Quota Enforcement

**Files:**
- Create: `src/main/java/com/enterprise/llm/ratelimit/TenantRateLimiter.java`
- Create: `src/main/java/com/enterprise/llm/ratelimit/RateLimitExceededException.java`
- Create: `src/main/java/com/enterprise/llm/ratelimit/RateLimitInterceptor.java`
- Test: `src/test/java/com/enterprise/llm/ratelimit/TenantRateLimiterTest.java`

- [x] **Step 1: Write unit test simulating burst traffic and rate limiting rejection (HTTP 429)**
- [x] **Step 2: Implement lock-free TokenBucket algorithm keyed by tenant ID**
- [x] **Step 3: Run test to verify passes**
- [x] **Step 4: Git commit**

---

### Task 4: Dynamic Model Routing & Automatic Failover (Circuit Breaker)

**Files:**
- Create: `src/main/java/com/enterprise/llm/router/ModelRouteConfig.java`
- Create: `src/main/java/com/enterprise/llm/router/FailoverRoutingService.java`
- Create: `src/main/java/com/enterprise/llm/router/CircuitBreaker.java`
- Test: `src/test/java/com/enterprise/llm/router/FailoverRoutingServiceTest.java`

- [x] **Step 1: Write test for automatic failover to local Ollama when primary endpoint fails (timeout / 500)**
- [x] **Step 2: Implement lightweight Circuit Breaker and `FailoverRoutingService`**
- [x] **Step 3: Integrate router into `ChatExecutionService`**
- [x] **Step 4: Run test to verify passes**
- [x] **Step 5: Git commit**

---

### Task 5: Prompt Template & Security Guardrails (Injection Detection)

**Files:**
- Create: `src/main/java/com/enterprise/llm/guard/PromptGuardrailService.java`
- Create: `src/main/java/com/enterprise/llm/guard/SecurityViolationException.java`
- Create: `src/main/java/com/enterprise/llm/template/PromptTemplateEngine.java`
- Test: `src/test/java/com/enterprise/llm/guard/PromptGuardrailTest.java`
- Test: `src/test/java/com/enterprise/llm/template/PromptTemplateTest.java`

- [x] **Step 1: Write test for prompt injection detection (Jailbreak, System override attempts)**
- [x] **Step 2: Implement CRISPE template variable engine & rule-based guardrail analyzer**
- [x] **Step 3: Run tests to verify pass**
- [x] **Step 4: Git commit**

---

### Task 6: Full Phase 2 Integration & Live Local Ollama Verification

**Files:**
- Modify: `README.md`
- Test: `src/test/java/com/enterprise/llm/e2e/Phase2GovernanceE2ETest.java`

- [x] **Step 1: Write full E2E test verifying: DB Auth -> Rate Limit -> Guardrail -> Failover to Ollama**
- [x] **Step 2: Run `./mvnw clean verify` across entire project**
- [x] **Step 3: Update README.md with Phase 2 capabilities and curl examples**
- [x] **Step 4: Final Phase 2 git commit**
