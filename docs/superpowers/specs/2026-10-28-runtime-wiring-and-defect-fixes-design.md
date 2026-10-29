# 架构设计规约：运行期接线补齐与已知缺陷修复

- 日期：2026-10-28
- 状态：已实施（2026-10-28），全量 328 个用例通过
- 影响模块：`llm-enterprise-core`、`llm-enterprise-spring-boot-starter`、`llm-enterprise-gateway-server`

---

## 1. 背景与现状证据

代码走查确认：README 宣称的多数能力已有实现与单测，但**部分组件未接入网关请求链路**，另有 2 个可复现缺陷。证据如下：

| 现象                                      | 证据位置                                                                                                                                                                |
| :-------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Canary / 影子流量仅在 Starter 注册 Bean，请求链路未调用 | `LlmAutoConfiguration.java:276-285`；全仓 `resolveModel(` / `maybeMirrorAsync(` 无调用点                                                                                   |
| 隐私保险库三件套无任何调用点                          | `PrivacyMaskingService`、`PrivacyVault`、`PrivacyDeMaskingTransformer` 仅被单测引用                                                                                         |
| 流式脱敏 MASK 模式返回值被丢弃                      | `ChatExecutionService.java:353-358` 只读取 `result.blocked()`                                                                                                          |
| 流式缓存写入落到 `default` 租户                   | `ChatExecutionService.java:394` 调 `cacheEngine.put(req, resp)`，而 `TenantSecurityContext`（ThreadLocal）在 SSE 回调线程已 `clear()`（`ApiKeyAuthenticationFilter.java:95-99`） |
| 跨 Chunk 脱敏存在前缀泄漏                        | `StreamingOutputGuardrail.java:92` 用 `currentWindow.substring(prefixLen)` 回吐本块，但前缀部分在上一块已下发，替换长度变化后无法回改                                                             |

第 5 条是本设计中最需要决策的点，见 §4.1。

---

## 2. 范围

**本批实现**

- A1 流式脱敏：修正跨 Chunk 前缀泄漏 + 让 MASK 模式真正生效
- A2 流式缓存租户归属修复
- B Canary 灰度切流接入请求链路（配置驱动）
- C 影子流量镜像接入同步链路（配置驱动，默认关闭）
- D 双向隐私保险库接入（同步 + 流式，默认关闭）
- E 配置字典与文档对齐（含硬编码 admin key 收敛）
- F 无调用点构造器清理

**明确不做（需另行决策）**

- `DistributedLuaQuotaEngine` 接真 Redis：需新增 Redis 客户端依赖 + Testcontainers 集成测试，属"Ask first"边界，单独立项
- 内嵌大盘 Tailwind/ECharts 本地化：需把约 1MB 静态资源入库，与"改文档"二选一，见待确认问题 Q4
- RAG / 多 Agent / 负载均衡相关改动：本批不触碰

---

## 3. 关键设计决策

**D1｜流式脱敏采用"尾部分片扣留"（hold-back），而非直接使用现有返回值**  
现有 `inspect()` 返回"本块内相对窗口的切片"，替换后长度变化会导致前缀错位；且前缀早已下发，无法回溯。改为：`inspect()` 返回**可安全下发的文本前缀**，把"可能是敏感数据前缀"的尾部留在缓冲区，`flush()` 在流结束时收尾。代价：首字节延迟最多增加一个"敏感 token 长度"（普通文本 0 延迟）。

**D2｜Canary 在 Controller 前置解析，且必须在缓存查询之前**  
缓存键包含 `model`（`CacheEngine.computeKey`），若在缓存之后改模型，会出现"用 A 的键命中 B 的回答"。

**D3｜影子流量使用裸 `primaryLlmClient`，不走 `FailoverRoutingService`**  
影子请求失败不得污染主链路的熔断器状态（`FailoverRoutingService` 内部共用同一个 `CircuitBreaker`），也不得占用集群节点的在途计数。

**D4｜隐私保险库默认关闭；开启时跳过两级缓存**

- 默认关闭：不改变现有 311 个用例的行为基线。
- 开启时跳过缓存读写：缓存键由明文 Prompt 计算，`CacheEntry.promptText` 会驻留明文 PII；若缓存脱敏后的响应，占位符 `<PHONE_1>` 与当前请求的 vault 无映射关系，会把占位符泄漏给用户。跳过缓存是唯一不需要改动 `CacheEntry` 结构的正确解。

**D5｜出站顺序固定为：DLP 检查（脱敏态）→ 反脱敏（还原态）**  
若先还原再 DLP，用户自己提供的手机号会被 `[REDACTED_PHONE]` 覆盖，双向保险库的"透明"语义失效。

**D6｜配置来源：YAML 为主，Admin API 为辅**  
YAML 绑定是必需项（否则组件无法启用）；Admin 运行时改规则接口作为可裁剪任务，评审时确认是否纳入。

---

## 4. 详细设计

### 4.1 A1 流式脱敏（`StreamingOutputGuardrail` + `ChatExecutionService`）

**新语义**

```java
// 返回的 sanitizedText = 本次允许下发给客户端的文本（可能为空串）
InspectionResult inspect(String chunk);
// 流结束时调用，返回仍滞留在缓冲区、已脱敏的尾部文本
String flush();
```

**算法**（`StreamSession`）

```
inspect(chunk):
  buffer += chunk
  if mode == BLOCK:  对 buffer 全量匹配，命中即抛 StreamingGuardrailException
  masked = maskAll(buffer)                      // MASK 模式就地替换
  holdStart = computeHoldStart(masked)
  emit = masked[0, holdStart)
  buffer = masked[holdStart, end)
  return emit

computeHoldStart(s):
  n = len(s)
  // 1) 末尾是数字串且长度 < 19（身份证 18 / 银行卡 19），从数字串起点扣留
  // 2) 末尾是 API Key 前缀（sk- / ghp_ / AKIA 起始且未闭合），从 token 起点扣留
  // 3) 其他情况 holdStart = n（全量下发，普通文本零额外延迟）
  // 4) 扣留长度上限 = overlapBufferSize（默认 64），防止超长 token 拖死流
```

关键点：**先替换再切分**。掩码后的片段即使被切开，也仍是掩码字符，不会泄漏；未完成的敏感前缀（如 4 位数字）则整体扣留，等后续 Chunk 补齐后再判定。

**`ChatExecutionService.chunkConsumer` 改动**（`ChatExecutionService.java:343-376`）

```java
String emit = content;
if (guardSession != null) {
    var r = guardSession.inspect(content);
    if (r.blocked()) throw new StreamingGuardrailException(r.violationType(), content);
    emit = r.sanitizedText();
}
// 计费仍按原始 token 数
generatedTokens.addAndGet(tokenEstimator.countTokens(content));
if (emit.isEmpty()) return;            // 不下发空内容块（usage-only 块不受影响）
```

**`completeRunnable` 收尾**（`ChatExecutionService.java:378-435`）：发送 `[DONE]` 之前，先 `flush()` 护栏与反脱敏缓冲，非空则补发一个内容块。

**行为变更**

- `StreamingOutputGuardrailTest` 中 2 个 MASK 用例的断言需改写（现断言"立即全量下发"，正是泄漏语义）。
- 流式响应中"敏感数据附近"的内容会晚 1 个 Chunk 到达；普通文本无变化。

---

### 4.2 A2 流式缓存租户归属

| 改动点                                 | 现状                                                    | 改为                                         |
| :---------------------------------- | :---------------------------------------------------- | :----------------------------------------- |
| `ChatExecutionService.java:394`     | `cacheEngine.put(req, resp)`（读 ThreadLocal → default） | `cacheEngine.put(req, resp, tenantId)`     |
| `ChatExecutionService.java:232`     | `cacheEngine.put(req, resp)`                          | 同上，显式传 `tenantId`                          |
| `ChatCompletionController.java:141` | `cacheEngine.lookup(req)`                             | `cacheEngine.lookup(req, currentTenantId)` |

`CacheEngine` 的三参重载已存在，无需改核心类。修复后：流式结果可被同租户复用；跨租户仍物理隔离（`tenantId` 参与 L1 键与 L2 过滤）。

---

### 4.3 B Canary 灰度切流

**配置模型**（Starter 新增 `LlmCanaryProperties`，前缀 `llm.canary`）

```yaml
llm:
  canary:
    enabled: false
    rules:
      - base-model: "deepseek-chat"
        canary-model: "deepseek-reasoner"
        weight-percent: 10
        sticky-by-tenant: true
        sticky-by-session: false
    overrides:
      - tenant-id: "tenant-alpha"
        base-model: "deepseek-chat"
        target-model: "deepseek-reasoner"
```

**注册**：`LlmAutoConfiguration.canaryRoutingService(LlmCanaryProperties)` 在构造 Bean 时直接 `setRule` / `setTenantOverride`（不新增类，约 8 行）。

**接线**（`ChatCompletionController.createChatCompletion`，校验之后、RAG/缓存之前）

```java
String resolvedModel = canary != null
        ? canary.resolveModel(request.model(), currentTenantId, request.sessionId())
        : request.model();
ChatCompletionRequest effectiveRequest =
        (resolvedModel != null && !resolvedModel.equals(request.model()))
                ? request.withModel(resolvedModel) : request;
```

**可观测**：命中改道时在响应头回写 `X-Hub-Routed-Model`，便于排障（同步与流式都可设置，因为都在写响应体之前）。

**顺序**：`校验 → 隐私脱敏(D) → Canary → RAG → 缓存 → 配额 → 执行`。Canary 放在隐私脱敏之后，保证缓存键与模型名一致。

---

### 4.4 C 影子流量镜像

**配置**（`LlmShadowProperties`，前缀 `llm.shadow`）

```yaml
llm:
  shadow:
    enabled: false
    rules:
      - source-model: "deepseek-chat"
        shadow-model: "deepseek-reasoner"
        sample-ratio: 0.05
```

**接线**（仅同步路径，`ChatCompletionController` 成功返回前）

```java
if (shadowMirror != null && shadowEnabled && vaultEmpty) {
    shadowMirror.maybeMirrorAsync(effectiveRequest, response, latencyMs,
            currentTenantId, traceId, failoverRoutingService.getPrimaryClient());
}
```

**约束**

- 流式请求不镜像（需要完整响应体才能做对照，缓冲会破坏流式语义）——在文档中明确。
- 缓存命中的请求不镜像（没有真实模型调用，无对照意义）。
- 影子调用不扣租户配额、不计入 `metricsRecordService`；仅输出 `SHADOW_MIRROR_AUDIT` 审计行（现有实现已有）。
- 默认 `sample-ratio` 为 0 且 `enabled=false`，避免误开烧钱。

---

### 4.5 D 双向隐私保险库

**配置**（`LlmPrivacyProperties`，前缀 `llm.privacy`，默认关闭）

```yaml
llm:
  privacy:
    enabled: false
```

**请求侧（Controller）**

```java
PrivacyVault vault = privacyEnabled ? new PrivacyVault() : null;
ChatCompletionRequest effectiveRequest = privacyEnabled
        ? privacyMaskingService.maskRequest(request, vault)   // 新增方法
        : request;
boolean piiPresent = vault != null && !vault.isEmpty();
```

- `maskRequest`：对每条 `ChatMessage.content` 调 `maskAndStore`，保留 `role/toolCalls/toolCallId`。
- Prompt 注入检测仍在**原文**上执行（占位符会削弱规则匹配）。
- `piiPresent == true` 时跳过缓存读与写（D4）。
- 工具调用参数不脱敏（本轮范围外，文档标注）。

**响应侧**

| 路径 | 处理顺序                                                                     | 位置                                                        |
| :- | :----------------------------------------------------------------------- | :-------------------------------------------------------- |
| 同步 | DLP `sanitize`（脱敏态）→ `demask`（还原态）                                       | `ChatCompletionController` 现有 sanitize 之后追加一行             |
| 流式 | 每块 `guard.inspect`（脱敏态）→ `StreamDeMasker.transformChunk` → 下发；结束 `flush` | `ChatExecutionService.chunkConsumer` / `completeRunnable` |

**会话持久化**：`ChatExecutionService` 落库前用 `vault.restore(...)` 还原用户原话，避免下一轮历史里出现无法解释的 `<PHONE_1>`（`ChatExecutionService.java:235-253`、`397-412`）。

**为何同步与流式的还原位置不同**：同步路径的 DLP 在 Controller（既有 E2E `Phase3GovernanceE2ETest` 依赖此行为），流式路径的出口在 Service。这是**有意为之的不对称**，两者出口顺序严格一致（先 DLP 后还原），在代码注释中标注。

**缓存与隐私的交互**：开启隐私时缓存被跳过，因此缓存中永远不会出现占位符，也不会出现 PII 明文。

---

### 4.6 E 配置与文档对齐

1. `application.yml`：新增 `llm.canary` / `llm.shadow` / `llm.privacy` 三段（默认全关），`admin.secret-key` 改为 `${ADMIN_SECRET_KEY:test-admin-secret-key-12345}`。
2. `static/index.html`：删除 `<input value="test-admin-secret-key-12345">` 的硬编码默认值。
3. `README.md`：
   - 测试数 295 → 实测值；
   - "零外部 CDN 依赖"改为如实描述（或按 Q4 决策改为本地资源）；
   - "Redis Lua 原子租约"改为"内存租约引擎（Redis 适配器待接入）"；
   - 新增一张"能力 → 生效条件"表（哪些能力默认关闭、如何开启）。
4. `docs/features.md` / `docs/configuration.md`：同步上述开关与语义变更。

### 4.7 F 无调用点构造器清理

仅删除**零调用点**的重载（不重写测试）：`ChatCompletionController` 3 个、`AdminController` 1 个、`ChatExecutionService` 4 个、`FailoverRoutingService` 2 个。删除前用 `grep -rn "new Xxx("` 复核。

---

## 5. 兼容性

| 维度     | 影响                                              |
| :----- | :---------------------------------------------- |
| 对外 API | 无破坏性变更；新增可选响应头 `X-Hub-Routed-Model`             |
| 默认行为   | A1/A2 是缺陷修复（流式输出在敏感数据附近有 1 块延迟）；B/C/D 默认关闭，行为不变 |
| 现有测试   | 2 个 MASK 断言需改写；其余 309 个必须保持通过                   |
| 配置     | 全部新增项有默认值，老配置文件可直接启动                            |

## 6. 测试策略

| 层级                                             | 用例                                                                                      |
| :--------------------------------------------- | :-------------------------------------------------------------------------------------- |
| 单元（core）                                       | 脱敏 hold-back：对 `13800138000` 的**每个切分点**断言输出不含 ≥7 位连续原号码、且含掩码；`flush()` 收尾；BLOCK 模式不泄漏前缀 |
| 单元（core）                                       | `maskRequest` 多消息脱敏；`PrivacyVault.restore` 幂等                                           |
| 单元（gateway）                                    | 缓存租户隔离：A 写入后 B 查不到、A 能查到                                                                |
| 集成（gateway, `@SpringBootTest` + MockWebServer） | Canary：请求 A 模型 → 上游收到 B 模型；未命中规则时模型不变                                                   |
| 集成                                             | 影子：sample-ratio=1 时上游收到 2 次请求且模型不同；主响应不受影响                                              |
| 集成                                             | 隐私同步：上游收到的请求体**不含**手机号；客户端响应**含**手机号且不含 `<PHONE_1>`                                     |
| 集成                                             | 隐私流式：上游回吐 `<PHONE_1>`，客户端收到还原后的号码                                                       |
| 回归                                             | `./mvnw -o test` 全量（当前基线 311 个用例）                                                       |

验证命令（离线、不触发 `repackage`，规避沙箱对 `target/` 删除的拦截）：

```bash
./mvnw -o test                                  # 全量
./mvnw -o test -pl llm-enterprise-core          # 单模块
./mvnw -o test -pl llm-enterprise-gateway-server -Dtest=ChatStreamingE2ETest
```

## 7. 验收标准

- [x] 全量测试通过：**328 个用例，0 失败**（基线 311 → +17）
- [x] 关闭全部新开关时，行为与改动前一致（除 2 个 MASK 断言，已按新语义改写）
- [x] Canary / 影子 / 隐私三项均能通过 YAML 开关真实生效，并有集成测试证明
      （`CanaryRoutingE2ETest`、`ShadowTrafficE2ETest`、`PrivacyVaultE2ETest`）
- [x] 流式 MASK 模式在任何切分点都不泄漏原始敏感串
      （`StreamingOutputGuardrailTest` 逐切分点属性测试 + `StreamingMaskE2ETest`）
- [x] 同租户流式结果可命中缓存，跨租户不可命中（`ChatStreamCacheTenantTest`）
- [x] `README` 中不存在与代码不符的能力声明（新增"能力开关与生效条件"表）

## 8. 风险与缓解

| 风险                    | 等级 | 缓解                               |
| :-------------------- | :- | :------------------------------- |
| 脱敏 hold-back 改变流式时延特征 | 中  | 仅扣留"疑似敏感尾部"，普通文本零延迟；集成测试断言首块仍可下发 |
| 改写安全组件引入回归            | 中  | 逐切分点属性式测试 + 保留 BLOCK 原有测试语义      |
| 影子流量产生额外推理成本          | 中  | 默认关闭 + 采样比 + 不占配额 + 审计行          |
| 隐私开关误开导致占位符外泄         | 高  | 默认关闭；缓存跳过；占位符泄漏专项断言              |
| 沙箱阻断 `package`        | 低  | 统一用 `test` / `compile` 验证        |

## 9. 待确认问题（评审需拍板）

- **Q1** 隐私保险库默认值：保持 `false`（推荐）还是直接 `true`？
- **Q2** Canary / 影子是否同时提供 Admin 运行时接口（`POST /v1/admin/canary/rules` 等）？推荐：先只做 YAML，Admin 接口列为可选任务。
- **Q3** 影子流量的对照结果是否需要落库/进大盘？推荐：本轮只输出审计日志。
- **Q4** 大盘 CDN：改文档如实描述（推荐，0 成本）还是把 Tailwind/ECharts 本地化入库（离线可用，但仓库 +1MB）？
- **Q5** F 项（死代码清理）是否纳入本批？推荐纳入，放最后，纯机械删除。
- **Q6** 是否本轮就把 `DistributedLuaQuotaEngine` 接真 Redis？推荐：不纳入（需新增依赖 + 容器化测试）。

---

## 10. 实施记录：与原设计的偏差

实施过程中发现 4 处设计假设与代码现实不符，已按实际情况调整，均不影响 §7 验收：

| # | 原设计 | 实际做法 | 原因 |
| :--- | :--- | :--- | :--- |
| 1 | 影子流量在 `ChatCompletionController` 触发，注入 `FailoverRoutingService` 取 primary client | 下移到 `ChatExecutionService.executeSync` 末尾触发，注入可选 `ShadowTrafficMirror` | `executeSync` 同时握有 primary client、tenantId、耗时与 traceId，控制器一个都没有；放这里少注入 2 个依赖 |
| 2 | Canary / 影子规则在 `@Bean` 方法内注册 | 改为 `SmartInitializingSingleton` 后置注册 | `CanaryRoutingService` / `ShadowTrafficMirror` 带 `@Service` / `@Component`，网关组件扫描会先建 Bean，`@ConditionalOnMissingBean` 直接退让，规则永远注册不上（首版实测 Canary 未生效） |
| 3 | 删除 `FailoverRoutingService` 2 个无调用点构造器 | **删除 0 个** | grep 复核发现 3/4/5/6/7 参构造器**全部有调用点**（`Phase7`、`RemoteClusterDiscovery`、`ModelAliasCascade`、`FailoverRoutingServiceTest`、`LlmAutoConfiguration`） |
| 4 | 删除 `ChatExecutionService` 4 个无调用点构造器 | 删除 **6 个**（10/9/8/7/5/1 参），保留 14（主）/4/3 参 | 实际调用点只有 3 种 arity；4 参与 3 参改为直接委托主构造器 |
| 5 | `ChatCompletionController` 3 个重载留到 T9 | T4 阶段即删除 | 加参数时必然要改这 3 个重载，提前删除避免改三遍 |

另有一处**设计之外的发现**：`llm.quota.distributed.*` 配置块没有任何代码读取，属于死配置，已在 `docs/configuration.md` 标注为"预留，尚未接线"。

---

## 11. 追加：内联全限定名清理（代码风格统一）

### 11.1 问题

代码中存在把包路径直接写在类名前的写法，例如 `ChatCompletionController`：

```java
com.enterprise.llm.trace.TraceContext traceCtx = com.enterprise.llm.trace.TraceContext.getCurrent();
com.enterprise.llm.trace.LlmSpan span = new com.enterprise.llm.trace.LlmSpan(...);
```

行宽被撑爆、可读性差，且与同文件已有的 `import` 风格自相矛盾。

### 11.2 口径（先量化，再动手）

统计时**必须排除 `import` 行、字符串、字符、文本块、注释**——否则每个 `import` 语句都会被当成"内联全限定名"，得出虚高的结论。

| 口径 | 文件数 | 处数 |
| :--- | ---: | ---: |
| 4 个源码根全部 `.java` | 282 | — |
| 含真实内联全限定名的文件 | 40 | 170 |

### 11.3 改写规则

| # | 规则 | 例子 |
| :--- | :--- | :--- |
| 1 | 普通类 → `import` + 简单名 | `com.x.TraceContext` → `TraceContext` |
| 2 | 嵌套类 / 静态成员 → 只导入顶层类 | `com.x.StreamingOutputGuardrail.Mode.MASK` → `StreamingOutputGuardrail.Mode.MASK` |
| 3 | 同包类 → 只去前缀，不加 `import` | 同包内 `com.x.Foo` → `Foo` |
| 4 | 已被通配符 `import` 覆盖的包 → 只去前缀 | 有 `import java.util.*;` 时 `java.util.List` → `List` |
| 5 | 简单名与本文件已有 `import` 冲突 / 同文件内指向两个不同顶层类 / 与文件内声明类型同名 → **保持原样** | 保守跳过，宁可不改 |
| 6 | 字符串、文本块、注释一律不动 | `"com.x.Y"` 多为反射键、配置键 |

`import` 区块统一重建：`java`/`javax` 在前，其它按字母序，`static` 最后。

### 11.4 结果与验证

- 170 处全部改写，**0 处跳过**（无歧义、无冲突）。
- 校验手段：
  1. 转换器幂等——二次执行 `0 文件 / 0 处` 改动；
  2. `static import` 数量前后逐文件比对一致（首版曾漏掉 `static` 关键字，由该比对发现并修复）；
  3. `mvnw -o test-compile` 通过；
  4. 全量 `mvnw -o test` —— **328 用例 / 0 失败 / 0 错误**，与重构前基线一致。
