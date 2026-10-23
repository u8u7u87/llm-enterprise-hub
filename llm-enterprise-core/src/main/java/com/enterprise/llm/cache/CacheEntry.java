package com.enterprise.llm.cache;

import com.enterprise.llm.model.ChatCompletionResponse;

public record CacheEntry(
        String key,
        String tenantId,
        String promptText,
        ChatCompletionResponse response,
        long createdAt,
        long expireAt
) {
    public CacheEntry(String key, String promptText, ChatCompletionResponse response, long createdAt, long expireAt) {
        this(key, "default", promptText, response, createdAt, expireAt);
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expireAt;
    }
}
