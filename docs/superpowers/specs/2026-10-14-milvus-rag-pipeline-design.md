# 设计规范：分租户 Milvus 向量知识库与前置流水线 RAG 检索增强 (Phase 6)

## 1. 目标与背景
用户明确指定针对 RAG 的实现方向：
- **方案选择**：**形态 B（网关前置检索流水线）** + **形态 3（直接使用 Milvus 向量数据库）**。
- **核心业务价值**：
  1. 在网关进入大模型推理前，由流水线自动提取用户 Prompt Query，基于 Embedding 模型生成稠密向量；
  2. 直连 **Milvus 向量数据库**，利用租户隔离标尺（`tenant_id` Scalar Filtering）实现**企业级跨租户知识物理/逻辑隔离**；
  3. 召回 Top-K 知识切片（Chunks），在 Controller 层动态组装增强 System/User Prompt（Prompt Augmentation）；
  4. 支持对外知识库入库/检索管理 REST API，调用方无需在前端额外拼装 RAG，完全由网关一站式完成透明增强。

---

## 2. 总体架构设计

```mermaid
graph TD
    Client[客户端 POST /v1/chat/completions (带 X-API-Key)] --> Auth[1. ApiKeyAuthenticationFilter (解析租户 tenant_id)]
    Auth --> Guard[2. PromptGuardrailService (前置越狱防注入)]
    
    subgraph "Phase 6: 前置 RAG 检索增强流水线 (RagPipelineService)"
        Guard --> RagRouter{租户是否启用 RAG 知识库?}
        RagRouter -->|是| Embed[EmbeddingService: Query 向量化 (1024 维 BGE-M3 / OpenAI 规范)]
        Embed --> MilvusSearch[MilvusVectorStore: expr: tenant_id == current_tenant]
        MilvusSearch --> Augment[PromptAugmentor: 组装召回文档至 System/User Prompt]
        RagRouter -->|否 / 检索为空| SkipRag[保持原 Prompt 继续]
    end

    Augment --> Quota[3. TokenEstimator & QuotaService (计入 RAG 上下文预扣)]
    SkipRag --> Quota
    
    Quota --> Cache[4. CacheEngine (L1/L2 缓存)]
    Cache --> Router[5. FailoverRoutingService (主备模型推理)]
    Router --> DLP[6. OutputGuardrail (后置脱敏)]
    DLP --> ClientRes[安全响应返回客户端]

    subgraph "知识库管理控制面 (KnowledgeBaseController)"
        AdminUser[知识库维护者] -->|POST /v1/knowledge/documents| IngestService[DocumentIngestionService]
        IngestService --> Chunk[TextChunker (滑动窗口重叠切片)]
        Chunk --> EmbedBatch[EmbeddingService (批量向量化)]
        EmbedBatch --> MilvusInsert[Milvus 向量入库: 记录 tenant_id, chunk_text, doc_name]
    end
```

---

## 3. 详细设计要点

### 3.1 向量数据库与客户端 (MilvusVectorStore)
- **底层架构**：
  - 基于 Milvus 标准 RESTful/gRPC API 规范构建轻量 Java 适配层，或支持通过配置直连本地/远程 Milvus 服务（端口 `19530`）；
  - 提供 `MilvusVectorStore` 抽象，定义：
    - `insert(List<DocumentChunk> chunks)`
    - `search(String tenantId, float[] queryVector, int topK, double minScore)`
  - 内置内存回退兜底（当本地 Milvus 暂时未拉起时，自动兼容降级为内存 Cosine 索引，保障 CI/CD 单元测试 100% 独立稳定运行）。

### 3.2 向量嵌入引擎 (EmbeddingService)
- 采用通用 OpenAI 兼容的 `/v1/embeddings` 规范；
- 默认无缝支持 **SiliconFlow / Ollama / 本地部署**的 Embedding 接口（如 `BAAI/bge-m3` 1024 维模型）；
- 支持批量 Embed 文档与单条 Embed Query。

### 3.3 分租户隔离机制 (Multi-Tenant Isolation)
- 集合（Collection）中包含标量字段：
  - `id`: 字符串唯一主键（UUID）；
  - `tenant_id`: 租户 ID（用于强制 Scalar Filter）；
  - `doc_name`: 文档来源名称；
  - `text`: 切片正文；
  - `vector`: 稠密向量数据（Float 数组）。
- **硬隔离查询**：所有检索请求自动强制附加表达式 `tenant_id == '${currentTenant}'`，杜绝跨租户知识越权。

### 3.4 前置流水线接入 (RagPipelineService & ChatCompletionController)
- 在 `ChatCompletionController` 执行配额预扣之前切入；
- 自动提取对话中最新的 User 文本作为检索 Query；
- 召回上下文自动包装为：
  ```markdown
  基于以下已知参考信息回答用户问题，如果无法从中得到答案，请说明无法回答：
  【参考信息 1】...
  【参考信息 2】...
  ```
- 增强后的 Prompt 会自然传递给后续的 `TokenEstimator`，准确预估包含知识库上下文的全部 Tokens，确保账本精准、无算力盗用。

### 3.5 知识库管理端点 (KnowledgeBaseController)
- `POST /v1/knowledge/documents`：为当前租户上传并切分文本内容入库；
- `POST /v1/knowledge/search`：检索当前租户知识库并返回带相似度分数的匹配片段；
- `DELETE /v1/knowledge/documents/{docName}`：删除指定文档切片。
