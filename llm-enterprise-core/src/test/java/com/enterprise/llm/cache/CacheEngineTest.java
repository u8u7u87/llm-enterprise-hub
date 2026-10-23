package com.enterprise.llm.cache;

import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CacheEngineTest {

    private CacheProperties properties;
    private CacheEngine cacheEngine;

    @BeforeEach
    void setUp() {
        properties = new CacheProperties();
        properties.setEnabled(true);
        properties.setTtlSeconds(60);
        properties.setSemanticThreshold(0.80);
        cacheEngine = new CacheEngine(properties);
    }

    @Test
    void shouldHitL1ExactCache() {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(new ChatMessage("user", "What is Java 21?")),
                false,
                0.7
        );

        ChatCompletionResponse response = new ChatCompletionResponse(
                "cache-1", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", "Java 21 has virtual threads"), "stop")),
                new Usage(10, 20, 30)
        );

        cacheEngine.put(request, response);

        Optional<ChatCompletionResponse> cached = cacheEngine.lookup(request);

        assertThat(cached).isPresent();
        assertThat(cached.get().id()).isEqualTo("cache-1");
        assertThat(cacheEngine.getL1Hits()).isEqualTo(1);
        assertThat(cacheEngine.getSavedTokens()).isEqualTo(30);
    }

    @Test
    void shouldHitL2SemanticCacheWhenPhrasedSimilarly() {
        ChatCompletionRequest originalReq = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(new ChatMessage("user", "Explain quicksort algorithm in Java")),
                false,
                0.7
        );

        ChatCompletionResponse response = new ChatCompletionResponse(
                "cache-2", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", "Quicksort is divide and conquer"), "stop")),
                new Usage(15, 25, 40)
        );

        cacheEngine.put(originalReq, response);

        // Slightly different wording, but very similar word tokens
        ChatCompletionRequest similarReq = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(new ChatMessage("user", "Explain quicksort algorithm in Java language")),
                false,
                0.7
        );

        Optional<ChatCompletionResponse> cached = cacheEngine.lookup(similarReq);

        assertThat(cached).isPresent();
        assertThat(cached.get().id()).isEqualTo("cache-2");
        assertThat(cacheEngine.getL2Hits()).isEqualTo(1);
    }

    @Test
    void shouldMissWhenQueryIsUnrelated() {
        ChatCompletionRequest request1 = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(new ChatMessage("user", "Write a python script")),
                false,
                0.7
        );

        ChatCompletionResponse response = new ChatCompletionResponse(
                "cache-3", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", "print('hello')"), "stop")),
                new Usage(10, 10, 20)
        );

        cacheEngine.put(request1, response);

        ChatCompletionRequest unrelatedReq = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(new ChatMessage("user", "What is the capital of France?")),
                false,
                0.7
        );

        Optional<ChatCompletionResponse> cached = cacheEngine.lookup(unrelatedReq);

        assertThat(cached).isEmpty();
        assertThat(cacheEngine.getMisses()).isEqualTo(1);
    }

    @Test
    void shouldExpireEntryAfterTtl() throws InterruptedException {
        properties.setTtlSeconds(0); // Expires immediately
        CacheEngine shortCache = new CacheEngine(properties);

        ChatCompletionRequest req = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(new ChatMessage("user", "hello")),
                false,
                0.7
        );
        ChatCompletionResponse resp = new ChatCompletionResponse(
                "c-exp", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", "hi"), "stop")),
                new Usage(5, 5, 10)
        );

        shortCache.put(req, resp);
        Thread.sleep(50);

        Optional<ChatCompletionResponse> cached = shortCache.lookup(req);
        assertThat(cached).isEmpty();
    }
}
