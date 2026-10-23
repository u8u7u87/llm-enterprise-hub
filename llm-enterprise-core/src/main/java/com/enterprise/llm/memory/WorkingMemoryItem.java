package com.enterprise.llm.memory;

/**
 * Represents a dehydrated data payload stored in working memory.
 */
public record WorkingMemoryItem(
        String memoryId,
        String sessionId,
        String summary,
        String rawContent,
        String role,
        long createdAtMs,
        float[] embedding
) {
    public WorkingMemoryItem(String memoryId, String sessionId, String summary, String rawContent, String role, long createdAtMs) {
        this(memoryId, sessionId, summary, rawContent, role, createdAtMs, null);
    }

    public WorkingMemoryItem withEmbedding(float[] emb) {
        return new WorkingMemoryItem(memoryId, sessionId, summary, rawContent, role, createdAtMs, emb);
    }
}
