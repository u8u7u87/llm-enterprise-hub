package com.enterprise.llm.rag.model;

import java.util.Map;

public record DocumentChunk(
        String id,
        String tenantId,
        String docName,
        int chunkIndex,
        String text,
        float[] vector,
        Map<String, Object> metadata
) {
    public DocumentChunk(String id, String tenantId, String docName, int chunkIndex, String text) {
        this(id, tenantId, docName, chunkIndex, text, null, Map.of());
    }

    public DocumentChunk withVector(float[] vector) {
        return new DocumentChunk(id, tenantId, docName, chunkIndex, text, vector, metadata);
    }
}
