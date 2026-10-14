package com.enterprise.llm.rag.model;

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
}
