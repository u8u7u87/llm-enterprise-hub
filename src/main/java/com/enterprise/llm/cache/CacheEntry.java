package com.enterprise.llm.cache;

import com.enterprise.llm.model.ChatCompletionResponse;

public record CacheEntry(
        String key,
        String promptText,
        ChatCompletionResponse response,
        long createdAt,
        long expireAt
) {
    public boolean isExpired() {
        return System.currentTimeMillis() > expireAt;
    }
}
