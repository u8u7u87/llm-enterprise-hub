package com.enterprise.llm.rag;

import java.util.List;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.rag.embedding.EmbeddingClient;
import com.enterprise.llm.rag.guard.FaithfulnessResult;
import com.enterprise.llm.rag.guard.RagFaithfulnessEvaluator;
import com.enterprise.llm.rag.model.SearchResult;
import com.enterprise.llm.rag.query.ContextualQueryRewriter;
import com.enterprise.llm.rag.query.QueryRewriter;
import com.enterprise.llm.rag.rerank.HybridSearchReranker;
import com.enterprise.llm.rag.rerank.Reranker;
import com.enterprise.llm.rag.store.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RagPipelineService {

    private static final Logger log = LoggerFactory.getLogger(RagPipelineService.class);

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final PromptAugmentor promptAugmentor;
    private final QueryRewriter queryRewriter;
    private final Reranker reranker;
    private final RagFaithfulnessEvaluator faithfulnessEvaluator;
    private final boolean ragEnabled;
    private final int topK;
    private final double minScore;

    @Autowired
    public RagPipelineService(
            @Autowired(required = false) EmbeddingClient embeddingClient,
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) QueryRewriter queryRewriter,
            @Autowired(required = false) Reranker reranker,
            @Autowired(required = false) RagFaithfulnessEvaluator faithfulnessEvaluator,
            @Value("${rag.enabled:true}") boolean ragEnabled,
            @Value("${rag.top-k:3}") int topK,
            @Value("${rag.min-score:0.5}") double minScore
    ) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.promptAugmentor = new PromptAugmentor();
        this.queryRewriter = queryRewriter != null ? queryRewriter : new ContextualQueryRewriter();
        this.reranker = reranker != null ? reranker : new HybridSearchReranker();
        this.faithfulnessEvaluator = faithfulnessEvaluator != null ? faithfulnessEvaluator : new RagFaithfulnessEvaluator();
        this.ragEnabled = ragEnabled;
        this.topK = topK;
        this.minScore = minScore;
    }

    public RagPipelineService(
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            QueryRewriter queryRewriter,
            Reranker reranker,
            boolean ragEnabled,
            int topK,
            double minScore
    ) {
        this(embeddingClient, vectorStore, queryRewriter, reranker, null, ragEnabled, topK, minScore);
    }

    public RagPipelineService(
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            boolean ragEnabled,
            int topK,
            double minScore
    ) {
        this(embeddingClient, vectorStore, null, null, null, ragEnabled, topK, minScore);
    }

    /**
     * Pre-inference retrieval and prompt augmentation pipeline.
     * Extracts the latest user question, generates embedding, searches Milvus by tenantId,
     * and injects matched chunks into request messages.
     *
     * @param request  original ChatCompletionRequest
     * @param tenantId current tenant ID
     * @return augmented ChatCompletionRequest (or original if no hits or RAG disabled)
     */
    public ChatCompletionRequest processAndAugment(ChatCompletionRequest request, String tenantId) {
        if (!ragEnabled || embeddingClient == null || vectorStore == null || request.messages() == null || request.messages().isEmpty()) {
            return request;
        }

        // Find the latest user query
        String userQuery = null;
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            ChatMessage msg = request.messages().get(i);
            if ("user".equalsIgnoreCase(msg.role()) && msg.content() != null && !msg.content().isBlank()) {
                userQuery = msg.content();
                break;
            }
        }

        // 1. Contextual Query Disambiguation (Resolves anaphora and missing subjects in multi-turn chats)
        String effectiveSearchQuery = queryRewriter != null 
                ? queryRewriter.rewriteQuery(request.messages(), userQuery) 
                : userQuery;

        try {
            float[] queryVector = embeddingClient.embed(effectiveSearchQuery);
            if (queryVector == null || queryVector.length == 0) {
                return request;
            }

            // Retrieve candidate pool
            List<SearchResult> candidateHits = vectorStore.search(tenantId, queryVector, topK, minScore);
            if (candidateHits.isEmpty()) {
                log.debug("RAG search returned 0 hits for tenant {} query: '{}'", tenantId, effectiveSearchQuery);
                return request;
            }

            // 2. Post-retrieval Hybrid Re-ranking & threshold filtering
            List<SearchResult> hits = reranker != null
                    ? reranker.rerank(effectiveSearchQuery, candidateHits, topK, minScore)
                    : candidateHits.stream().limit(topK).toList();

            if (hits.isEmpty()) {
                log.debug("Reranker filtered out all candidate hits below threshold {} for tenant {}", minScore, tenantId);
                return request;
            }

            log.info("RAG search retrieved and reranked {} chunks for tenant {} query: '{}'", hits.size(), tenantId, effectiveSearchQuery);
            List<ChatMessage> augmentedMessages = promptAugmentor.augment(request.messages(), hits);

            return new ChatCompletionRequest(
                    request.model(),
                    augmentedMessages,
                    request.stream(),
                    request.temperature(),
                    request.maxTokens(),
                    request.tools(),
                    request.toolChoice()
            );
        } catch (Exception e) {
            log.warn("Failed to retrieve or augment knowledge for tenant {}: {}", tenantId, e.getMessage());
            return request;
        }
    }

    public FaithfulnessResult evaluateFaithfulness(List<SearchResult> chunks, String answer) {
        if (faithfulnessEvaluator != null) {
            return faithfulnessEvaluator.evaluate(chunks, answer);
        }
        return FaithfulnessResult.pass(1.0, answer, 0, 0, "Faithfulness guardrail not configured");
    }

    public RagFaithfulnessEvaluator getFaithfulnessEvaluator() {
        return faithfulnessEvaluator;
    }
}
