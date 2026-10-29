package com.enterprise.llm.cache;

import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import com.enterprise.llm.router.FailoverRoutingService;
import com.enterprise.llm.service.ChatExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatCacheIntegrationTest {

    @Mock
    private FailoverRoutingService failoverRoutingService;

    private CacheProperties cacheProperties;
    private CacheEngine cacheEngine;
    private ChatExecutionService chatExecutionService;

    @BeforeEach
    void setUp() {
        cacheProperties = new CacheProperties();
        cacheProperties.setEnabled(true);
        cacheEngine = new CacheEngine(cacheProperties);
        chatExecutionService = new ChatExecutionService(
                failoverRoutingService,
                null,
                null,
                cacheEngine
        );
    }

    @Test
    void shouldEmitCachedStreamSmoothly() {
        ChatCompletionResponse cachedResponse = new ChatCompletionResponse(
                "cached-stream-1", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", "Hello from cache!"), "stop")),
                new Usage(5, 5, 10)
        );

        SseEmitter emitter = chatExecutionService.emitCachedStream(cachedResponse);

        assertThat(emitter).isNotNull();
    }
}
