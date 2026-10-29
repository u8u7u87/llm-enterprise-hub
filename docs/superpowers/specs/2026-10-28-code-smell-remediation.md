# 代码坏味道扫描与治理

- 日期：2026-10-28
- 状态：已实施，全量 **337 个用例通过**（基线 328 + 新增 9）
- 影响模块：`llm-enterprise-core`、`llm-enterprise-spring-boot-starter`、`llm-enterprise-gateway-server`
- 代码规模：284 个 `.java`，约 2.88 万行

---

## 1. 扫描口径

坏味道判断不能靠通读，必须先量化。本次用脚本扫描（脚本临时放在 `/tmp`，逻辑见附录），
**统计前先屏蔽字符串、文本块、注释**——否则注释里的示例代码、文本块里的 JSON 都会污染统计。

同时明确一条纪律：**行数阈值只能提示、不能定性**。像 `MarkdownStructureChunker.chunk()`（94 行）
是一个带状态的分段解析器，已经拆出 `flushTable` / `flushSection` / `buildBreadcrumb`，
再拆只会把状态搬进参数对象，属于"为指标而改"，不采纳。

---

## 2. 发现与处置总表

| # | 类别 | 扫描发现 | 处置 | 结果 |
| :--- | :--- | ---: | :--- | :--- |
| 1 | 跨文件重复代码（推理适配器） | 5 个类 466 行，其中约 250 行逐字重复 | **提取抽象基类** | 466 → 331 行，重复收敛为 1 份 |
| 2 | 通配符 import | 23 处 / 21 个文件 | **展开为显式 import** | 23 → 0 |
| 3 | 未使用 import | 49 处 / 31 个文件 | **删除** | 49 → 0 |
| 4 | 空 catch 块 | 22 处 | **分类治理** | 22 → 0 |
| 5 | 未使用 `Logger` 字段 | 3 处 | **删除字段与 import** | 3 → 0 |
| 6 | `.size() > 0` | 1 处 | **改 `!isEmpty()`** 并补 `ApiKeyPool.isEmpty()` | 1 → 0 |
| 7 | 魔法数字：HTTP 2xx 判定 | 5 个适配器 × 3 处 | **收敛到基类 `isSuccess` + 常量** | 15 → 0 |
| 8 | 魔法数字：重试退避默认值 | `1000L` / `60` 各出现多次 | **具名常量** | — |
| 9 | 重复的响应组装 | 2 个编排器各 12 行 | **`ChatCompletionResponse.withUsage()`** | −20 行 |
| 10 | 重复的 Milvus 删除逻辑 | 2 个方法各 20 行 | **`deleteEntities(filterExpr)`** | −20 行 |
| 11 | 重复的 Retry-After 解析 | 同方法内两段 | **`parseNumericHeader()`** | — |
| 12 | 重复的指标上报 try/catch | `LlmSpan` 2 处 | **`reportMetric(Runnable)`** | — |
| 13 | 重复的采集器广播 try/catch | `CompositeMetricsCollector` 3 处 | **`broadcast(Consumer)`** | — |

---

## 3. 重点改造

### 3.1 推理适配器：5 份重复 → 1 份骨架

改造前，`Direct` / `vLLM` / `SGLang` / `Triton` / `RayServe` 五个适配器的 `checkHealth`
逐字重复同一套流程：空节点校验 → 根地址归一化 → 建请求 → 注入 Bearer → 4 秒超时 →
2xx 判定 → 兜底探测 → 异常兜底。`getRootUrl()` 在 4 个类里各写了一遍。

改造后新增 `AbstractInferenceAdapter`，把流程收敛为模板方法，子类只声明差异：

| 适配器 | 主探针 | 兜底探针 |
| :--- | :--- | :--- |
| Direct | `/v1/models` | 无 |
| vLLM | `/health` | `/v1/models` |
| SGLang | `/health` | `/v1/models` |
| Triton | `/v2/health/ready` | `/v1/models` |
| Ray Serve | `/-/healthz` | `/v1/models` |

指标解析同样收敛：`applyWaitingRequests` / `applyGpuCacheUsage` 只接收"正则 + 文本"。

**行为保持**：兜底探测沿用原实现**不带鉴权头**（否则会把 401 的节点误判为健康）；
`Direct` 的主探针等价于原来的 `buildProbeUrl`，两种 baseUrl 形态（带不带 `/v1`）逐一验算一致。

### 3.2 空 catch：先分类，再决定"补日志"还是"补注释"

22 处空 catch 里**没有一处是真 bug**，但三类的正确处置方式不同：

| 类型 | 代表位置 | 处置 |
| :--- | :--- | :--- |
| 旁路能力自保护 | `CompositeMetricsCollector`、`LlmSpan`、`AgentToolOrchestrator` | 补 `log.warn`/`log.debug`，并写明"不得影响主链路" |
| 解析失败回退默认值 | `LlmErrorParser`、`SqliteSessionStorageProvider` | 窄化为具体异常 + 说明回退语义 |
| 尽力而为的清理 | `MilvusVectorStore`、流关闭 | 补注释，说明失败后业务语义不变 |

顺带修掉 1 处真实缺陷：`ToolConcurrencyAndScopingTest` 里 `catch (InterruptedException ignored) {}`
吞掉了中断标志，已改为 `Thread.currentThread().interrupt()`。

### 3.3 新增测试

改造前 `checkHealth` 的分支逻辑（主探针 / 兜底 / 2xx / 鉴权头 / 空节点）**零测试覆盖**，
属于本次风险最高的区域。先补网再动刀：

- `InferenceAdapterHealthProbeTest`（8 个用例）：主探针成功不触发兜底、主探针失败回退、
  双双失败判不可用、各引擎探针路径、baseUrl 带 `/v1` 的归一化、`Direct` 不做兜底、
  Bearer 头注入、非法节点零请求。
- `LlmMetricsCollectorTest.testCompositeIsolatesFailingCollector`：坏采集器排在前面时，
  健康采集器仍收到全部事件。

---

## 4. 有意不改的项（附理由）

| 项 | 数量 | 不改的理由 |
| :--- | ---: | :--- |
| 超大类 | 3 | `LlmAutoConfiguration`(606) 是约 45 个 `@Bean` 的装配类，拆分需改动 `AutoConfiguration.imports` 与 Bean 注册顺序；`ChatExecutionService`(612)、`FailoverRoutingService`(488) 是编排核心。三者都需要独立的设计评审，不适合混在机械治理里做。**见 §5 计划** |
| 超长方法 | 25 | 多数是线性状态机/解析循环（已内部拆过），行数阈值属于假阳性；真正需要拆的只有 `ChatExecutionService.executeStream`(280 行) 与 `FailoverRoutingService.executeSingleModel*`(108/132 行)，同上 |
| `new Thread(...)` | 4 | 已专项复核并处置，见 **§6**。结论：3 处是 `ThreadFactory` 回调（本身就在线程池里），已改为 Java 21 的 `Thread.ofPlatform()` 工厂；1 处是关闭钩子，必须保留 |
| 空方法体 | 14 | 全部是 record / DTO 的显式无参构造器（供 Jackson 反序列化使用），有意为空 |
| 魔法数字 | 145 | 绝大多数是 `@Value` 默认值、端口号、TTL、HTTP 状态码等协议/配置常量，抽常量反而降低可读性；只治理了"同一含义在多个文件重复出现"的部分（§2 第 7、8 项） |
| 重复字符串字面量 | 168 组 | 集中在测试里的模型名（`"gpt-4o"`、`"qwen2.5:0.5b"`），抽常量会让测试更难读 |
| `catch (Throwable)` | 5 | 全部在后台任务/影子流量/旁路采集里，且都已 `log.warn`，属于"绝不能让异常逃逸"的正确用法 |

---

## 5. 后续计划（未纳入本次）

按风险从低到高：

1. **`FailoverRoutingService.executeSingleModel`（108 行）**：重试循环 + 限流处理 + 模型别名解析三段
   可以拆成私有方法，入参不超过 5 个，风险最低。
2. **`ChatExecutionService.executeStream`（280 行）**：内部有 3 个闭包（`chunkConsumer` 58 行、
   `completeRunnable` 80 行、`errorConsumer` 20 行），建议把"出站分片改写"（guardrail → de-mask →
   token 计数 → 重组 chunk）抽成独立的 `StreamingChunkRewriter` 协作对象，把 6 个捕获变量变成字段。
   该路径已有 5 个 E2E 用例覆盖（`StreamingMask` / `PrivacyVault` / `CanaryRouting` /
   `ShadowTraffic` / `Phase7`），具备改造条件，但需要单独一轮验证。
3. **`LlmAutoConfiguration`（606 行）**：按能力域拆成 `LlmCoreAutoConfiguration` /
   `LlmRagAutoConfiguration` / `LlmAgentAutoConfiguration`，同步更新
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。

---

## 6. 线程创建专项复核

扫描器把 `new Thread` 标成坏味道，逐处复核后结论要分开看。

### 6.1 三处 `new Thread` 其实就在线程池里

`RemoteClusterManager`、`AsyncQuotaWriteBehindBatcher`、`RequestHedgingRouter` 的 `new Thread`
都出现在 `Executors.newSingleThreadScheduledExecutor(...)` 的 **`ThreadFactory` 回调**里：

```java
Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "xxx");   // r 是线程池给的工作 Runnable
    t.setDaemon(true);
    return t;
});
```

这不是"绕过线程池"，而是**线程池创建自身工作线程的唯一方式**——
`ThreadFactory.newThread(Runnable)` 要求你返回一个 `Thread`，而此刻线程池里还没有线程可用。
这三处的线程池生命周期也都是正确的：`RemoteClusterManager.stop()` 调 `shutdownNow()`，
`AsyncQuotaWriteBehindBatcher` 实现 `AutoCloseable` 并有 `@PreDestroy close()`。

真正该被质疑的写法是每次调用都 `new Thread(r).start()`：无上限、无复用、无背压。**本仓库没有这种写法。**

### 6.2 处置

| 位置 | 原写法 | 处置 |
| :--- | :--- | :--- |
| `RemoteClusterManager` | 4 行 `ThreadFactory` lambda | 改 `Thread.ofPlatform().daemon().name(...).factory()` |
| `AsyncQuotaWriteBehindBatcher` | 同上 | 同上 |
| `RequestHedgingRouter` | 同上 | 同上，并补 `AutoCloseable.close()` 释放 `scheduler` |
| `EmbeddedLocalEngineSupervisor` | `addShutdownHook(new Thread(this::stop, ...))` | **保留**：关闭钩子必须是 `Thread`，线程池无法替代 |

`Thread.ofPlatform().daemon().name(x).factory()` 是 Java 21 的标准写法，语义与原来逐字等价（同样 daemon、同样精确命名）。

### 6.3 复核中发现的两个真实缺陷

1. **`RequestHedgingRouter.scheduler` 从不关闭**：字段级 `ScheduledExecutorService`，实例级生命周期，
   但类里没有任何 `shutdown`。线程虽是 daemon 不阻塞 JVM 退出，实例被反复创建时会各自常驻一条空闲线程。
   已实现 `AutoCloseable` 并在测试 `@AfterEach` 中收尾。
2. **`EmbeddedLocalEngineSupervisor.start()` 重复注册关闭钩子**：`addShutdownHook` 注册后不可撤销，
   而 `start()` 没有任何重入保护，每次重启都会再挂一个钩子并永久驻留。已改为一次性注册（`AtomicBoolean` CAS）。

---

## 7. 验证

| 检查项 | 结果 |
| :--- | :--- |
| `mvnw -o test-compile` | 通过 |
| 全量 `mvnw -o test` | **337 用例 / 0 失败 / 0 错误**（基线 328 + 新增 9） |
| 通配符 / 未使用 import 复扫 | 0 / 0 |
| 空 catch 复扫 | 0 |
| `static import` 数量前后比对 | 逐文件一致（无丢失） |
| 适配器重复代码 | 466 → 331 行 |
| `new Thread` 复扫 | 4 → 1（仅剩关闭钩子，属正确用法） |

---

## 附录：扫描脚本要点

- **屏蔽字面量**：单字符状态机区分 `str` / `chr` / 文本块 `"""` / `//` / `/* */`，只把内容替换为空格，
  保持字符偏移不变，便于按偏移精确替换。
- **未使用 import**：在屏蔽后的正文里搜简单名；排除 `{@link X}` 形式的 javadoc 引用；
  通配符 import 必须先展开，否则会漏判。
- **重复代码**：把每行去空白归一化后取 10 行窗口做哈希，跨文件重复即为克隆。
- **行数阈值**：只作为候选提示，必须人工确认是否为线性状态机。
