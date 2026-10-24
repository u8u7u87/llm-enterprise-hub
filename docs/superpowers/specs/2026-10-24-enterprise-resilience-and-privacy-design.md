# Enterprise Resilience, Rate Limiting & Privacy Vault Specification (Phase 4)

## 1. Overview & Context

对标业界领先的开源大模型网关（**LiteLLM**, **Portkey**, **Kong AI Gateway**）与大厂金融/企业级大模型服务平台的生产实践，本项目在完成基础核心治理、监控闭环（Prometheus）、高并发基准压测（SQLite WAL）与 Multi-Agent Swarm 协同后，进一步构建工业级韧性与隐私安全体系。

本期方案分为两个里程碑：
- **Phase 4.1 (高可用韧性基座)**:
  1. **双轨滑动窗口限流器 (Dual Sliding-Window RateLimiter: RPM + TPM)**：支持 Tokens-Per-Minute 与 Requests-Per-Minute 毫秒级滑动窗口限流，具备基于 Prompt 的原子预估与 Provider 实际返回 Token 差额的校准机制。
  2. **自愈型三态熔断器 (Stateful Circuit Breaker)**：重构路由层单一计数器，实现完整的 `CLOSED` -> `OPEN` -> `HALF_OPEN` 三态状态机，支持冷却探测窗口、探针并发控制与自愈恢复。
- **Phase 4.2 (隐私安全与极致体验)**:
  3. **双向隐私脱敏保险库 (Bidirectional Privacy Vault)**：入站对 PII 敏感实体（手机号/身份证/银行卡/邮箱等）进行命名占位符伪匿名化，出站（流式/非流式）由网关根据会话级 Vault 自动透明反脱敏还原。
  4. **投机对冲竞速路由 (Request Hedging / Speculative Racing)**：基于虚拟线程在主模型 TTFT 超时（默认 1500ms）未出字时并行唤醒备用模型，双路竞速首帧，压降长尾 P99 延时。

---

## 2. Component Design (Phase 4.1)

### 2.1 双轨滑动窗口限流器 (RPM + TPM)

#### 2.1.1 核心痛点与数学模型
大模型云服务商（OpenAI, Azure, Anthropic, DeepSeek）的限流瓶颈主要在 **TPM (Tokens-Per-Minute)**，传统的单维 QPS/RPS 令牌桶无法防范“一次请求携带 32k tokens 导致上游 429”的问题。

滑动窗口模型采用 **环形多槽计数器 (Circular Time-Slot Bucket)**：
- 将 60 秒划分为 60 个 1000ms 的时间槽位；
- 每次请求根据当前系统时间戳定位槽位，并淘汰 60 秒前的数据；
- 请求到达时：
  1. 预估 Prompt Tokens（中文字符/英文字词加权，或 `Math.max(1, charLength / 3)`）；
  2. 原子累加窗口内总请求数与总 Tokens；
  3. 若 `windowRequests + 1 > rpmLimit`，返回 429 (Reason: `RPM_EXCEEDED`)；
  4. 若 `windowTokens + estimatedTokens > tpmLimit`，返回 429 (Reason: `TPM_EXCEEDED`)；
  5. 校验通过则原子计入槽位；
  6. LLM 调用结束收到真实 `totalTokens` 后，调用 `recordUsage` 将差额 `(actualTokens - estimatedTokens)` 增量/减量更新到当前窗口槽位。

#### 2.1.2 关键类与契约
- `TenantEntity` 扩展字段：
  - `rpmLimit`: 租户每分钟请求上限（<=0 表示不限制）；
  - `tpmLimit`: 租户每分钟 Token 上限（<=0 表示不限制）。
- `com.enterprise.llm.ratelimit.RateLimitResult`:
  ```java
  public record RateLimitResult(boolean allowed, String rejectionReason, long retryAfterMs) {}
  ```
- `TenantRateLimiter` 核心方法：
  - `RateLimitResult tryAcquire(TenantEntity tenant, int estimatedTokens)`
  - `boolean tryAcquire(TenantEntity tenant)` // 向后兼容
  - `void recordUsage(TenantEntity tenant, long actualTokens, long estimatedTokens)`

---

### 2.2 自愈型三态熔断器 (Stateful Circuit Breaker)

#### 2.2.1 状态机模型

```mermaid
stateDiagram-v2
    [*] --> CLOSED: 初始化 (FailureCount = 0)
    
    CLOSED --> OPEN: failureCount >= failureThreshold (如 3 次)
    note right of OPEN
        1. 阻断所有主模型流量
        2. 流量直接降级至备用模型
        3. 记录 openTimestamp
    end note
    
    OPEN --> HALF_OPEN: 超过 cooldownDurationMs (如 15000ms)
    note right of HALF_OPEN
        1. 允许限量探针请求 (halfOpenPermits)
        2. 探活主模型状态
    end note
    
    HALF_OPEN --> CLOSED: 探针调用成功 (successCount >= successThreshold)
    note left of CLOSED
        状态完全自愈！
        清零 failureCount
        恢复全部主模型流量
    end note
    
    HALF_OPEN --> OPEN: 探针调用失败
    note right of OPEN
        探针依然失败
        重新进入 OPEN 状态并刷新冷却期
    end note
```

#### 2.2.2 线程安全与并发控制
- 状态跃迁使用 `AtomicReference<State>` 与 CAS 操作；
- `HALF_OPEN` 状态下使用 `Semaphore` 或 `AtomicInteger activePermits` 限制并发探针数（默认 1），防止在主模型刚恢复时瞬时并发将其再次打垮；
- 冷却期检查：在 `tryAcquirePermission()` 时惰性检查当前时间与 `openTimestamp` 的差值，若大于 `cooldownDurationMs`，通过 CAS 驱动状态从 `OPEN` 自动流转为 `HALF_OPEN`。

---

## 3. Component Design (Phase 4.2)

### 3.1 双向隐私脱敏保险库 (Bidirectional Privacy Vault)

#### 3.1.1 架构与处理流
```mermaid
sequenceDiagram
    autonumber
    actor User as 终端用户
    participant Gateway as 网关/调用方
    participant Vault as PrivacyVault (请求/会话隔离)
    participant Core as Core / Model Client
    actor LLM as 外部云端大模型

    User->>Gateway: 输入: "我的手机号是 13812345678，请查询账单"
    Gateway->>Vault: 识别敏感实体 -> 写入映射 <PHONE_1> : 13812345678
    Gateway->>Core: 匿名化 Prompt: "我的手机号是 <PHONE_1>，请查询账单"
    Core->>LLM: 发送请求 (云厂商模型无法获取真实手机号)
    LLM-->>Core: 响应: "已为您查询到 <PHONE_1> 的本月账单为 98 元"
    Core-->>Gateway: 返回带占位符的 Response/Stream
    Gateway->>Vault: 查询 Vault 映射并透明还原
    Gateway-->>User: 最终呈现: "已为您查询到 13812345678 的本月账单为 98 元"
```

#### 3.1.2 核心设计
- `PrivacyVault`: 会话/请求级线程安全的字典容器，保存 `Map<String, String> placeholderToOriginal` 与 `Map<String, String> originalToPlaceholder`；
- `PrivacyMaskingInterceptor`: 统一在请求入站时扫描并伪匿名化（Pseudonymization）；
- `PrivacyDeMaskingTransformer`: 支持普通文本反替换与流式 SSE Token 反替换（对跨 Token 切分的占位符做轻量缓冲区滑动匹配）。

---

### 3.2 投机对冲竞速路由 (Request Hedging Router)

#### 3.2.1 竞速时序
```mermaid
sequenceDiagram
    autonumber
    actor Client as 调用方
    participant HedgingRouter as RequestHedgingRouter
    participant Primary as 主模型 (Cloud High-Tier)
    participant Fallback as 备用模型 (Edge / Local Fast)

    Client->>HedgingRouter: 发起 ChatCompletion
    HedgingRouter->>Primary: 虚拟线程发起主模型调用
    HedgingRouter->>HedgingRouter: 启动延迟调度 (如 1500ms TTFT 警戒线)
    
    alt 主模型在 1500ms 内吐出首字 (正常情况)
        Primary-->>HedgingRouter: 首帧 Token 到达
        HedgingRouter->>HedgingRouter: 取消延迟任务
        HedgingRouter-->>Client: 正常推流输出
    else 1500ms 延迟触发 (长尾劣化)
        HedgingRouter->>Fallback: 虚拟线程立即拉起备用模型发起对冲请求
        Note over HedgingRouter,Fallback: 主备两路并行竞速首帧
        alt 主模型稍后先到
            Primary-->>HedgingRouter: 主模型首帧到达
            HedgingRouter->>Fallback: 取消备用模型请求
            HedgingRouter-->>Client: 交付主模型结果
        else 备用模型先到
            Fallback-->>HedgingRouter: 备用模型首帧到达
            HedgingRouter->>Primary: 取消主模型请求 (节约算力)
            HedgingRouter-->>Client: 交付备用模型结果 (保住低延迟体验)
        end
    end
```

---

## 4. Testing & Verification Plan

### 4.1 单元测试
1. `CircuitBreakerTest`:
   - 验证 `CLOSED` 连续失败达标后切换为 `OPEN`；
   - 验证 `OPEN` 期间拒绝请求；
   - 验证经过冷却时间后自动跃迁为 `HALF_OPEN`；
   - 验证 `HALF_OPEN` 探针成功后状态跃迁恢复为 `CLOSED` 并重置错误计数；
   - 验证 `HALF_OPEN` 探针失败后状态重新回到 `OPEN`。
2. `SlidingWindowRateLimiterTest`:
   - 验证 RPM 阈值拦截与 60s 滑动窗口自动释放；
   - 验证 TPM 阈值拦截（单次大 Token 请求超出上限直接拒绝）；
   - 验证 `recordUsage` 差额多退少补的准确性；
   - 验证高并发多线程下原子槽位计数无负数与死锁。
3. `FailoverRoutingServiceTest`:
   - 接入 `CircuitBreaker` 后的端到端自愈恢复测试。
4. `PrivacyVaultTest`:
   - 验证手机号、身份证、邮箱的双向脱敏与透明反脱敏。
5. `RequestHedgingRouterTest`:
   - 验证当主模型快时取消备用；当主模型慢（模拟 2000ms 延迟）时备用成功对冲并取消主模型。

### 4.2 集成回归测试
- 运行 `./mvnw clean verify`，确保已有全部 163 个用例 + 新增用例 100% 通过。
