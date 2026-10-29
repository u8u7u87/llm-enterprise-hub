package com.enterprise.llm.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.enterprise.llm.auth.TenantSecurityContext;
import com.enterprise.llm.rag.chunk.MarkdownStructureChunker;
import com.enterprise.llm.rag.chunk.TextChunker;
import com.enterprise.llm.rag.embedding.EmbeddingClient;
import com.enterprise.llm.rag.model.DocumentChunk;
import com.enterprise.llm.rag.model.SearchResult;
import com.enterprise.llm.rag.store.VectorStore;
import com.enterprise.llm.tenant.TenantEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/knowledge")
public class KnowledgeBaseController {

    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    @Autowired
    public KnowledgeBaseController(
            @Autowired(required = false) EmbeddingClient embeddingClient,
            @Autowired(required = false) VectorStore vectorStore
    ) {
        this.textChunker = new TextChunker();
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    public record IngestDocumentRequest(
            String docName,
            String content,
            Integer chunkSize,
            Integer chunkOverlap
    ) {}

    public record SearchKnowledgeRequest(
            String query,
            Integer topK,
            Double minScore
    ) {}

    @PostMapping("/documents")
    public ResponseEntity<?> ingestDocument(@RequestBody IngestDocumentRequest req) {
        TenantEntity tenant = TenantSecurityContext.getCurrentTenant();
        String tenantId = tenant != null ? tenant.getId() : "default";

        if (req.docName() == null || req.docName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "docName must not be empty"));
        }
        if (req.content() == null || req.content().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content must not be empty"));
        }

        List<DocumentChunk> rawChunks;
        if (req.docName().endsWith(".md") || req.content().contains("# ") || req.content().contains("|---") || req.content().contains("| :---")) {
            MarkdownStructureChunker mdChunker = (req.chunkSize() != null && req.chunkOverlap() != null)
                    ? new MarkdownStructureChunker(req.chunkSize(), req.chunkOverlap())
                    : new MarkdownStructureChunker();
            rawChunks = mdChunker.chunk(req.content(), tenantId, req.docName());
        } else {
            TextChunker chunker = (req.chunkSize() != null && req.chunkOverlap() != null)
                    ? new TextChunker(req.chunkSize(), req.chunkOverlap())
                    : this.textChunker;
            rawChunks = chunker.chunk(req.content(), tenantId, req.docName());
        }

        if (rawChunks.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "No chunks generated", "chunk_count", 0));
        }

        List<String> texts = rawChunks.stream().map(DocumentChunk::text).toList();
        List<float[]> embeddings = (embeddingClient != null)
                ? embeddingClient.embedBatch(texts)
                : Collections.emptyList();

        List<DocumentChunk> embeddedChunks = new ArrayList<>();
        for (int i = 0; i < rawChunks.size(); i++) {
            float[] vec = (i < embeddings.size()) ? embeddings.get(i) : new float[0];
            embeddedChunks.add(rawChunks.get(i).withVector(vec));
        }

        if (vectorStore != null) {
            vectorStore.insert(embeddedChunks);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status", "success",
                "tenant_id", tenantId,
                "doc_name", req.docName(),
                "chunk_count", embeddedChunks.size()
        ));
    }

    @PostMapping("/search")
    public ResponseEntity<?> searchKnowledge(@RequestBody SearchKnowledgeRequest req) {
        TenantEntity tenant = TenantSecurityContext.getCurrentTenant();
        String tenantId = tenant != null ? tenant.getId() : "default";

        if (req.query() == null || req.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query must not be empty"));
        }

        if (embeddingClient == null || vectorStore == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        float[] queryVec = embeddingClient.embed(req.query());
        int limit = req.topK() != null && req.topK() > 0 ? req.topK() : 3;
        double threshold = req.minScore() != null ? req.minScore() : 0.5;

        List<SearchResult> hits = vectorStore.search(tenantId, queryVec, limit, threshold);
        return ResponseEntity.ok(hits);
    }

    @DeleteMapping("/documents/{docName}")
    public ResponseEntity<?> deleteDocument(@PathVariable("docName") String docName) {
        TenantEntity tenant = TenantSecurityContext.getCurrentTenant();
        String tenantId = tenant != null ? tenant.getId() : "default";

        if (vectorStore != null) {
            vectorStore.deleteByDocName(tenantId, docName);
        }

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Document " + docName + " deleted for tenant " + tenantId
        ));
    }
}
