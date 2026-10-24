package com.enterprise.llm.cache;

import com.enterprise.llm.model.ChatCompletionResponse;

public record CacheEntry(
        String key,
        String tenantId,
        String promptText,
        ChatCompletionResponse response,
        long createdAt,
        long expireAt,
        float[] vector
) {
    public CacheEntry(String key, String tenantId, String promptText, ChatCompletionResponse response, long createdAt, long expireAt) {
        this(key, tenantId, promptText, response, createdAt, expireAt, null);
    }

    public CacheEntry(String key, String promptText, ChatCompletionResponse response, long createdAt, long expireAt) {
        this(key, "default", promptText, response, createdAt, expireAt, null);
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expireAt;
    }
}
