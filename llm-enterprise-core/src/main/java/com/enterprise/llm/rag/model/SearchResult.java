package com.enterprise.llm.rag.model;

import java.util.Collections;
import java.util.Map;

public record SearchResult(
        String id,
        String tenantId,
        String docName,
        int chunkIndex,
        String text,
        double score,
        Map<String, Object> metadata
) {
    public SearchResult(String id, String docName, int chunkIndex, String text, double score) {
        this(id, "default", docName, chunkIndex, text, score, Collections.emptyMap());
    }

    public SearchResult withScore(double newScore) {
        return new SearchResult(id, tenantId, docName, chunkIndex, text, newScore, metadata != null ? metadata : Collections.emptyMap());
    }
}
