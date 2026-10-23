package com.enterprise.llm.rag;

import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.rag.embedding.EmbeddingClient;
import com.enterprise.llm.rag.model.SearchResult;
import com.enterprise.llm.rag.store.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagPipelineService {

    private static final Logger log = LoggerFactory.getLogger(RagPipelineService.class);

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final PromptAugmentor promptAugmentor;
    private final boolean ragEnabled;
    private final int topK;
    private final double minScore;

    @Autowired
    public RagPipelineService(
            @Autowired(required = false) EmbeddingClient embeddingClient,
            @Autowired(required = false) VectorStore vectorStore,
            @Value("${rag.enabled:true}") boolean ragEnabled,
            @Value("${rag.top-k:3}") int topK,
            @Value("${rag.min-score:0.5}") double minScore
    ) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.promptAugmentor = new PromptAugmentor();
        this.ragEnabled = ragEnabled;
        this.topK = topK;
        this.minScore = minScore;
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

        if (userQuery == null) {
            return request;
        }

        try {
            float[] queryVector = embeddingClient.embed(userQuery);
            if (queryVector == null || queryVector.length == 0) {
                return request;
            }

            List<SearchResult> hits = vectorStore.search(tenantId, queryVector, topK, minScore);
            if (hits.isEmpty()) {
                log.debug("RAG search returned 0 hits for tenant {} query: '{}'", tenantId, userQuery);
                return request;
            }

            log.info("RAG search retrieved {} chunks for tenant {} query: '{}'", hits.size(), tenantId, userQuery);
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
}
