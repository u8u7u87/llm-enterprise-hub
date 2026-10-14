# 实施计划：分租户 Milvus 向量知识库与前置流水线 RAG 检索增强 (Phase 6)

## 任务拆解与执行顺序

- [ ] **Task 1: EmbeddingClient 与向量化服务**
  - 创建: `src/main/java/com/enterprise/llm/rag/embedding/EmbeddingClient.java`
  - 创建: `src/main/java/com/enterprise/llm/rag/embedding/OpenAiCompatibleEmbeddingClient.java`
  - 测试: `src/test/java/com/enterprise/llm/rag/embedding/EmbeddingClientTest.java`
  - 交付: 单元测试验证对 `/v1/embeddings` 接口调用的序列化与向量解析。

- [ ] **Task 2: 文档切片分块与模型实体**
  - 创建: `src/main/java/com/enterprise/llm/rag/model/DocumentChunk.java`
  - 创建: `src/main/java/com/enterprise/llm/rag/model/SearchResult.java`
  - 创建: `src/main/java/com/enterprise/llm/rag/chunk/TextChunker.java`
  - 测试: `src/test/java/com/enterprise/llm/rag/chunk/TextChunkerTest.java`
  - 交付: 实现带 overlap 的滑动窗口切片器，验证切片边界与元数据挂载。

- [ ] **Task 3: Milvus 向量数据库客户端与分租户存储**
  - 创建: `src/main/java/com/enterprise/llm/rag/store/VectorStore.java`
  - 创建: `src/main/java/com/enterprise/llm/rag/store/MilvusVectorStore.java`
  - 测试: `src/test/java/com/enterprise/llm/rag/store/MilvusVectorStoreTest.java`
  - 交付: 支持强制附加 `tenant_id` 过滤标尺、Top-K 检索与多租户数据隔离隔离测试。

- [ ] **Task 4: 前置 RAG 流水线与 Prompt 增强组装**
  - 创建: `src/main/java/com/enterprise/llm/rag/RagPipelineService.java`
  - 创建: `src/main/java/com/enterprise/llm/rag/PromptAugmentor.java`
  - 修改: `src/main/java/com/enterprise/llm/controller/ChatCompletionController.java`
  - 测试: `src/test/java/com/enterprise/llm/rag/RagPipelineServiceTest.java`
  - 交付: 在 Controller 进入模型前截获请求，执行 Query 检索并将召回片段优雅注入 Prompt，随后再执行配额预扣。

- [ ] **Task 5: 知识库管理端点 (KnowledgeBaseController)**
  - 创建: `src/main/java/com/enterprise/llm/rag/KnowledgeBaseController.java`
  - 修改: `src/main/java/com/enterprise/llm/auth/ApiKeyAuthenticationFilter.java`
  - 测试: `src/test/java/com/enterprise/llm/rag/KnowledgeBaseControllerTest.java`
  - 交付: 提供文档批量入库、检索与删除接口，受租户 API Key 认证保护。

- [ ] **Task 6: 全量端到端 E2E 验证与文档交付**
  - 创建: `src/test/java/com/enterprise/llm/e2e/Phase6MilvusRagE2ETest.java`
  - 更新: `README.md`
  - 运行: `./mvnw clean verify`
  - 交付: 全测试 100% 绿灯，重新打包生产 JAR，向用户提交汇报。
