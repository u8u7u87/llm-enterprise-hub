package com.enterprise.llm.cache;

import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.quota.QuotaReservation;
import com.enterprise.llm.router.FailoverRoutingService;
import com.enterprise.llm.service.ChatExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression test for the streaming cache tenant-attribution defect.
 *
 * <p>The SSE callbacks run on an OkHttp worker thread where {@code TenantSecurityContext} has
 * already been cleared. Before the fix, {@code cacheEngine.put(request, response)} therefore read
 * the tenant from the (empty) ThreadLocal and filed every streamed answer under {@code "default"},
 * so the tenant that produced it could never hit its own cache entry.
 */
@ExtendWith(MockitoExtension.class)
class ChatStreamCacheTenantTest {

    private static final String TENANT_A = "tenant-alpha";
    private static final String TENANT_B = "tenant-beta";

    @Mock
    private FailoverRoutingService failoverRoutingService;

    private CacheEngine cacheEngine;
    private ChatExecutionService chatExecutionService;

    private final AtomicReference<Consumer<ChatCompletionChunk>> capturedOnChunk = new AtomicReference<>();
    private final AtomicReference<Runnable> capturedOnComplete = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        CacheProperties properties = new CacheProperties();
        properties.setEnabled(true);
        cacheEngine = new CacheEngine(properties);

        chatExecutionService = new ChatExecutionService(
                failoverRoutingService,
                null,
                null,
                cacheEngine
        );

        when(failoverRoutingService.streamChatCompletionWithFailover(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    capturedOnChunk.set(invocation.getArgument(1));
                    capturedOnComplete.set(invocation.getArgument(2));
                    return new StreamCancellationHandle(null);
                });
    }

    @Test
    @DisplayName("A streamed answer must be cached under the tenant that produced it")
    void streamedResponseShouldBeCachedUnderReservationTenant() {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "deepseek-chat", List.of(ChatMessage.user("hello")), true, 0.0);

        chatExecutionService.executeStream(request, QuotaReservation.of(TENANT_A, 16));

        // Simulate the SSE callback thread: no tenant bound to this thread at all.
        capturedOnChunk.get().accept(contentChunk("hi there"));
        capturedOnComplete.get().run();

        assertThat(cacheEngine.lookup(request, TENANT_A)).isPresent();
        assertThat(cacheEngine.lookup(request, TENANT_A).orElseThrow()
                .choices().get(0).message().content()).isEqualTo("hi there");
        assertThat(cacheEngine.lookup(request, TENANT_B)).isEmpty();
    }

    @Test
    @DisplayName("Tenant isolation must hold when the same prompt is asked by two tenants")
    void samePromptMustNotCrossTenantBoundary() {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "deepseek-chat", List.of(ChatMessage.user("what is the refund policy?")), true, 0.0);

        chatExecutionService.executeStream(request, QuotaReservation.of(TENANT_A, 16));
        capturedOnChunk.get().accept(contentChunk("answer for alpha"));
        capturedOnComplete.get().run();

        assertThat(cacheEngine.lookup(request, TENANT_A)).isPresent();
        assertThat(cacheEngine.lookup(request, TENANT_B)).isEmpty();
    }

    private static ChatCompletionChunk contentChunk(String content) {
        return new ChatCompletionChunk(
                "chunk-1", "chat.completion.chunk", 1L, "deepseek-chat",
                List.of(new ChatCompletionChunk.ChoiceChunk(
                        0, new ChatCompletionChunk.Delta("assistant", content), null)),
                null);
    }
}
