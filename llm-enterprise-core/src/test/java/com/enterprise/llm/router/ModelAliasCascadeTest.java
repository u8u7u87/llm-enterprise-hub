package com.enterprise.llm.router;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import com.enterprise.llm.resilience.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ModelAliasCascadeTest {

    private ModelAliasRegistry aliasRegistry;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        aliasRegistry = new ModelAliasRegistry();
        circuitBreaker = new CircuitBreaker("test-cb");
    }

    @Test
    @DisplayName("Should resolve default aliases and custom aliases")
    void shouldResolveAliases() {
        assertThat(aliasRegistry.isAlias("fast")).isTrue();
        assertThat(aliasRegistry.isAlias("reasoning")).isTrue();
        assertThat(aliasRegistry.isAlias("gpt-4o")).isFalse();

        List<String> fastCandidates = aliasRegistry.resolveCandidates("fast");
        assertThat(fastCandidates).contains("gpt-4o-mini", "qwen-turbo", "qwen2.5:0.5b");

        // Non-alias returns singleton
        assertThat(aliasRegistry.resolveCandidates("custom-model"))
                .containsExactly("custom-model");
    }

    @Test
    @DisplayName("Should cascade to next candidate model when first candidate in alias fails")
    void shouldCascadeToNextCandidateModelWhenFirstFails() {
        aliasRegistry.registerAlias("auto-tier", List.of("preferred-model", "backup-model"));

        AtomicInteger primaryAttempts = new AtomicInteger(0);

        LlmClient mockPrimary = new LlmClient() {
            @Override
            public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
                primaryAttempts.incrementAndGet();
                if ("preferred-model".equals(request.model())) {
                    throw new RuntimeException("Preferred model endpoint 503 Service Unavailable");
                }
                if ("backup-model".equals(request.model())) {
                    return new ChatCompletionResponse(
                            "resp-backup",
                            "chat.completion",
                            1000L,
                            "backup-model",
                            List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Hello from backup!"), "stop")),
                            new Usage(10, 5, 15)
                    );
                }
                throw new IllegalArgumentException("Unexpected model: " + request.model());
            }

            @Override
            public StreamCancellationHandle streamChatCompletion(
                    ChatCompletionRequest request,
                    Consumer<ChatCompletionChunk> onChunk,
                    Runnable onComplete,
                    Consumer<Throwable> onError) {
                return null;
            }
        };

        LlmClient mockFallback = new LlmClient() {
            @Override
            public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
                return new ChatCompletionResponse("resp-fallback", "chat.completion", 1000L, "fallback", List.of(), new Usage(0, 0, 0));
            }

            @Override
            public StreamCancellationHandle streamChatCompletion(
                    ChatCompletionRequest request,
                    Consumer<ChatCompletionChunk> onChunk,
                    Runnable onComplete,
                    Consumer<Throwable> onError) {
                return null;
            }
        };

        FailoverRoutingService routingService = new FailoverRoutingService(
                mockPrimary,
                mockFallback,
                "qwen2.5:0.5b",
                null,
                circuitBreaker,
                null,
                aliasRegistry
        );

        ChatCompletionRequest request = new ChatCompletionRequest(
                "auto-tier",
                List.of(ChatMessage.user("Test prompt")),
                false,
                0.7
        );

        ChatCompletionResponse response = routingService.chatCompletionWithFailover(request);

        assertThat(response).isNotNull();
        assertThat(response.model()).isEqualTo("backup-model");
        assertThat(response.choices().get(0).message().content()).isEqualTo("Hello from backup!");
        assertThat(primaryAttempts.get()).isGreaterThanOrEqualTo(2);
    }
}
