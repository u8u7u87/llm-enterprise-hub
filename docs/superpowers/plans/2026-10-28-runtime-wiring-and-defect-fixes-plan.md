# 实现计划：运行期接线补齐与已知缺陷修复

> 对应设计：`docs/superpowers/specs/2026-10-28-runtime-wiring-and-defect-fixes-design.md`
> 执行方式：按任务顺序推进，每个 Checkpoint 停下做一次全量验证与人工确认。

## 执行结果（2026-10-28 完成）

- 全量 `./mvnw -o test`：**328 个用例，0 失败**（基线 311，新增 17）
- 9 个任务全部完成，4 个 Checkpoint 全部通过
- 两处与原计划的偏差见设计文档 §10：影子流量触发点下移到 `ChatExecutionService`；`FailoverRoutingService` 构造器**一个都没删**（全部有调用点）
- 验证命令补充：沙箱环境会阻止覆盖 `target/` 下已有资源，需加 `-Dmaven.resources.skip=true` 或先手工同步资源；正常环境直接 `./mvnw -o test` 即可

## 概览

修复 2 个可复现缺陷（流式脱敏前缀泄漏、流式缓存租户归属），把已编码但未接线的 3 个企业能力（Canary 灰度、影子流量、双向隐私保险库）真正接入网关请求链路，最后做配置/文档对齐与死代码清理。全部新能力默认关闭，保证关闭状态下行为与改动前一致。

## 基线

| 指标 | 值 |
| :--- | :--- |
| 基线用例 | 311（core 228 / gateway 83，2026-10-28 复核 core 228 全绿） |
| 基线命令 | `./mvnw -o test`（沙箱会拦截 `target/` 下旧报告删除，仅告警，不影响结果） |
| 工作区 | clean，HEAD `7cb3c97` |

---

## Phase 1：缺陷修复

### Task 1：流式脱敏改为尾部分片扣留（core）

**描述**：重写 `StreamingOutputGuardrail.StreamSession.inspect` 的返回语义为"可安全下发的前缀"，新增 `flush()`。先替换后切分，尾部疑似敏感前缀（数字串 / `sk-`、`ghp_`、`AKIA` 开头未闭合 token）整体扣留，扣留上限 `overlapBufferSize`。

**验收**
- [x] 对 `13800138000` 的每一个切分点，两次 `inspect` + `flush` 的拼接输出均不含 ≥7 位连续原号码，且含 `1**********`
- [x] 普通文本（无敏感前缀）`inspect` 全量下发，不引入延迟
- [x] BLOCK 模式命中时抛 `StreamingGuardrailException`，且命中前不产生任何输出
- [x] 现有 2 个 MASK 断言按新语义改写（原断言即泄漏语义）

**验证**：`./mvnw -o test -pl llm-enterprise-core -Dtest=StreamingOutputGuardrailTest`

**文件**：`core/.../guard/StreamingOutputGuardrail.java`、`core/src/test/.../guard/StreamingOutputGuardrailTest.java`
**依赖**：无 ｜ **规模**：S

---

### Task 2：网关流式出口接入 hold-back 语义（gateway）

**描述**：`ChatExecutionService.chunkConsumer` 使用 `inspect` 返回的前缀下发，空串不下发；`completeRunnable` 在 `[DONE]` 前 `flush()` 补发残余；累计内容（用于缓存与会话）改用实际下发文本。

**验收**
- [x] `llm.guardrail.streaming.mode=MASK` 时，跨 Chunk 手机号在客户端侧显示为掩码，且不出现原始号码
- [x] `mode=BLOCK` 时流被中断，客户端不会先收到敏感前缀
- [x] usage-only 块（`choices` 为空）照常下发
- [x] 流式结束后仍有 `data: [DONE]`

**验证**：`./mvnw -o test -pl llm-enterprise-gateway-server -Dtest=ChatStreamingE2ETest`
**文件**：`gateway/.../service/ChatExecutionService.java`、`gateway/src/test/.../e2e/ChatStreamingE2ETest.java`
**依赖**：Task 1 ｜ **规模**：M

---

### Task 3：流式缓存租户归属修复

**描述**：`ChatExecutionService` 两处 `cacheEngine.put` 显式传 `tenantId`；`ChatCompletionController` 的 `lookup` 显式传 `currentTenantId`。

**验收**
- [x] 租户 A 的流式结果，租户 A 再请求可命中（`X-Cache-Lookup: HIT`）
- [x] 租户 B 请求同内容不命中 A 的缓存
- [x] 同步路径缓存行为不变

**验证**：`./mvnw -o test -pl llm-enterprise-gateway-server -Dtest=ChatCacheIntegrationTest`
**文件**：`ChatExecutionService.java`、`ChatCompletionController.java`、`gateway/src/test/.../cache/ChatCacheIntegrationTest.java`
**依赖**：无（可与 Task 1 并行）｜ **规模**：XS

### ✅ Checkpoint 1（Task 1-3）
- [x] `./mvnw -o test` 全绿
- [x] 手工确认：MASK 模式下流式响应无原始号码
- [x] 人工确认后进入 Phase 2

---

## Phase 2：流量治理接线

### Task 4：Canary 灰度接入请求链路

**描述**：Starter 新增 `LlmCanaryProperties`（`llm.canary`），在 `canaryRoutingService` Bean 内注册规则与租户覆盖；Controller 在**缓存之前**解析模型；命中改道时回写 `X-Hub-Routed-Model`。

**验收**
- [x] `weight-percent: 100` 时，上游收到的请求体模型为 canary 模型
- [x] 未配置规则时，请求模型保持不变（回归）
- [x] 改道后的请求使用新模型名参与缓存键（不出现 A 键命中 B 答案）
- [x] `sticky-by-tenant: true` 时同租户多次请求模型稳定

**验证**：`./mvnw -o test -pl llm-enterprise-gateway-server -Dtest=CanaryRoutingE2ETest`
**文件**：`starter/.../config/LlmCanaryProperties.java`（新增）、`LlmAutoConfiguration.java`、`ChatCompletionController.java`、新增 `CanaryRoutingE2ETest`
**依赖**：无 ｜ **规模**：M

---

### Task 5：影子流量镜像接入同步链路

**描述**：Starter 新增 `LlmShadowProperties`（`llm.shadow`，默认关闭、采样比 0），Controller 在同步成功返回前触发 `maybeMirrorAsync`，使用裸 `primaryLlmClient`。

**验收**
- [x] `sample-ratio: 1.0` 时上游收到 2 次请求，模型分别为源模型与影子模型
- [x] 影子调用失败不影响主响应（上游第 2 次请求返回 500，客户端仍 200）
- [x] 缓存命中路径不触发镜像；流式请求不触发镜像
- [x] 影子调用不消耗租户配额（租户 `used_tokens` 不变）

**验证**：`./mvnw -o test -pl llm-enterprise-gateway-server -Dtest=ShadowTrafficE2ETest`
**文件**：`starter/.../config/LlmShadowProperties.java`（新增）、`LlmAutoConfiguration.java`、`ChatCompletionController.java`、新增 `ShadowTrafficE2ETest`
**依赖**：Task 4（同在 Controller 前置段，避免冲突）｜ **规模**：M

### ✅ Checkpoint 2（Task 4-5）
- [x] `./mvnw -o test` 全绿
- [x] 默认配置下（两开关关闭）无额外上游调用
- [x] 人工确认后进入 Phase 3

---

## Phase 3：隐私保险库

### Task 6：隐私脱敏同步链路

**描述**：`PrivacyMaskingService.maskRequest` 新增；Controller 建 vault、脱敏入站、`piiPresent` 时跳过缓存；同步响应在 DLP 之后还原；`ChatExecutionService` 落库前还原用户原话。

**验收**
- [x] 上游收到的请求体中不含手机号/邮箱/身份证，含 `<PHONE_1>` 等占位符
- [x] 客户端响应含真实号码，且不含 `<PHONE_1>`
- [x] `llm.privacy.enabled=false` 时行为与改动前完全一致
- [x] 开启时缓存不写入（同请求二次调用仍打到上游）

**验证**：`./mvnw -o test -pl llm-enterprise-gateway-server -Dtest=PrivacyVaultE2ETest`
**文件**：`core/.../guard/privacy/PrivacyMaskingService.java`、`ChatCompletionController.java`、`ChatExecutionService.java`、新增 `PrivacyVaultE2ETest`
**依赖**：Task 3（缓存跳过逻辑与租户参数同一处）｜ **规模**：M

---

### Task 7：隐私脱敏流式链路

**描述**：`chunkConsumer` 在 DLP 检查后接 `StreamDeMasker.transformChunk`；`completeRunnable` 依次 `flush` 护栏与反脱敏缓冲，再发 `[DONE]`；会话累计内容使用还原后文本。

**验收**
- [x] 上游流式回吐 `<PHONE_1>`，客户端收到 `13800138000`
- [x] 占位符被拆到两个 Chunk 时仍能正确还原
- [x] 流式响应不以 `[DONE]` 之外的残片结束

**验证**：`./mvnw -o test -pl llm-enterprise-gateway-server -Dtest=PrivacyVaultE2ETest`
**文件**：`ChatExecutionService.java`、`PrivacyVaultE2ETest`
**依赖**：Task 2、Task 6 ｜ **规模**：M

### ✅ Checkpoint 3（Task 6-7）
- [x] `./mvnw -o test` 全绿
- [x] 开关默认关闭时基线行为不变
- [x] 人工确认后进入 Phase 4

---

## Phase 4：收尾

### Task 8：配置与文档对齐

**描述**：`application.yml` 补三段配置与 `ADMIN_SECRET_KEY` 占位；`index.html` 去硬编码密钥；`README` / `features.md` / `configuration.md` 修正与代码不符的表述（测试数、零 CDN、Redis 租约），新增"能力 → 生效条件"表。

**验收**
- [x] `grep -rn "零外部 CDN\|零依赖.*CDN" README.md docs/` 无残留
- [x] README 测试数与 `./mvnw -o test` 实测一致
- [x] 应用默认配置可正常启动（`./mvnw -o spring-boot:run -pl llm-enterprise-gateway-server` 冒烟）

**验证**：人工核对 + 启动冒烟
**文件**：`application.yml`、`static/index.html`、`README.md`、`docs/features.md`、`docs/configuration.md`
**依赖**：Task 4-7 ｜ **规模**：M

---

### Task 9：死代码清理

**描述**：删除零调用点的构造器重载（`ChatCompletionController` 3、`AdminController` 1、`ChatExecutionService` 4、`FailoverRoutingService` 2）。删除前逐个 `grep -rn "new Xxx("` 复核。

**验收**
- [x] 每个被删构造器 `grep` 结果为 0 处调用
- [x] 全量测试仍全绿
- [x] 无测试代码改动（若发现需要改测试，说明该重载并非死代码，回滚该项）

**验证**：`./mvnw -o test`
**文件**：上述 4 个类
**依赖**：Task 8 ｜ **规模**：S

### ✅ Checkpoint 4（完成）
- [x] 全量测试通过，用例数 ≥ 311 + 新增
- [x] 设计文档 §7 验收标准逐条勾选
- [x] 提交前 `git diff --stat` 复核无越界改动

---

## 风险与缓解

| 风险 | 影响 | 缓解 |
| :--- | :--- | :--- |
| 改写安全组件引入回归 | 高 | Task 1 逐切分点属性测试；BLOCK 语义保持 |
| 流式首块延迟上升 | 中 | 仅扣留疑似敏感尾部；集成测试断言普通文本首块即时下发 |
| 影子流量烧钱 | 中 | 默认关闭 + 采样比 + 不占配额 |
| 隐私开关误用导致占位符外泄 | 高 | 默认关闭 + 跳过缓存 + 专项断言 |
| 沙箱阻断 `package` | 低 | 统一用 `test` 验证 |

## 待确认问题

见设计文档 §9（Q1-Q6）。默认按推荐值执行：隐私默认关闭、Admin 接口不做、影子只出审计日志、大盘改文档、F 项纳入、Redis 不纳入。
