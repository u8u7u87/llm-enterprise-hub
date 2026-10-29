package com.enterprise.llm.e2e;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.cost.CostCalculator;
import com.enterprise.llm.cost.ModelPricingRegistry;
import com.enterprise.llm.cost.TenantBudgetExceededException;
import com.enterprise.llm.cost.TenantBudgetManager;
import com.enterprise.llm.guard.StreamingOutputGuardrail;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import com.enterprise.llm.quota.QuotaReservation;
import com.enterprise.llm.router.FailoverRoutingService;
import com.enterprise.llm.router.ModelAliasRegistry;
import com.enterprise.llm.service.ChatExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Phase7AdvancedHardeningE2ETest {

    private ModelPricingRegistry pricingRegistry;
    private CostCalculator costCalculator;
    private TenantBudgetManager budgetManager;
    private ModelAliasRegistry aliasRegistry;
    private StreamingOutputGuardrail streamingGuardrail;

    @BeforeEach
    void setUp() {
        pricingRegistry = new ModelPricingRegistry();
        costCalculator = new CostCalculator(pricingRegistry);
        budgetManager = new TenantBudgetManager();
        aliasRegistry = new ModelAliasRegistry();
        streamingGuardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.BLOCK);
    }

    @Test
    @DisplayName("Should track financial cost and enforce tenant budget limits in ChatExecutionService")
    void shouldTrackCostAndEnforceBudget() {
        LlmClient mockClient = new LlmClient() {
            @Override
            public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
                return new ChatCompletionResponse(
                        "resp-1",
                        "chat.completion",
                        1000L,
                        request.model(),
                        List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Hello"), "stop")),
                        new Usage(100_000, 50_000, 150_000)
                );
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

        FailoverRoutingService router = new FailoverRoutingService(mockClient, mockClient, "qwen2.5:0.5b");
        ChatExecutionService executionService = new ChatExecutionService(
                router,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                costCalculator,
                budgetManager,
                streamingGuardrail,
                null
        );

        String tenantId = "tenant-ai-corp";
        budgetManager.setBudgetLimit(tenantId, 0.05); // Max $0.05 budget

        // Request 1: GPT-4o with 100k prompt, 50k completion -> cost = $0.25 + $0.50 = $0.75
        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o",
                List.of(ChatMessage.user("Test prompt")),
                false,
                0.7
        );

        executionService.executeSync(request, QuotaReservation.of(tenantId, 1000));
        assertThat(budgetManager.getSpentAmount(tenantId)).isEqualTo(0.750000);

        // Next request should be blocked immediately before calling LLM
        assertThatThrownBy(() -> executionService.executeSync(request, QuotaReservation.of(tenantId, 1000)))
                .isInstanceOf(TenantBudgetExceededException.class)
                .hasMessageContaining("has exceeded financial budget limit");
    }


    @Test
    @DisplayName("Should detect and block real-time cross-chunk sensitive leaks in streaming execution")
    void shouldBlockCrossChunkLeakInStream() {
        AtomicBoolean streamFailed = new AtomicBoolean(false);

        LlmClient mockStreamingClient = new LlmClient() {
            @Override
            public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
                return null;
            }

            @Override
            public StreamCancellationHandle streamChatCompletion(
                    ChatCompletionRequest request,
                    Consumer<ChatCompletionChunk> onChunk,
                    Runnable onComplete,
                    Consumer<Throwable> onError) {
                try {
                    // Send chunk 1: partial phone number
                    onChunk.accept(new ChatCompletionChunk(
                            "c1", "chat.completion.chunk", 1000L, request.model(),
                            List.of(new ChatCompletionChunk.ChoiceChunk(0, new ChatCompletionChunk.Delta("assistant", "员工联系方式: 138123"), null)),
                            null
                    ));
                    // Send chunk 2: completing the 11-digit mobile phone number
                    onChunk.accept(new ChatCompletionChunk(
                            "c2", "chat.completion.chunk", 1000L, request.model(),
                            List.of(new ChatCompletionChunk.ChoiceChunk(0, new ChatCompletionChunk.Delta("assistant", "45678 详询"), null)),
                            null
                    ));
                    onComplete.run();
                } catch (Throwable t) {
                    streamFailed.set(true);
                    onError.accept(t);
                }
                return new StreamCancellationHandle(() -> {});
            }
        };


        FailoverRoutingService router = new FailoverRoutingService(mockStreamingClient, mockStreamingClient, "qwen2.5:0.5b");
        ChatExecutionService executionService = new ChatExecutionService(
                router,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                costCalculator,
                budgetManager,
                streamingGuardrail,
                null
        );

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("谁负责这个项目？")),
                true,
                0.7
        );

        SseEmitter emitter = executionService.executeStream(request, null);
        assertThat(emitter).isNotNull();
        assertThat(streamFailed.get()).isTrue();
    }
}
