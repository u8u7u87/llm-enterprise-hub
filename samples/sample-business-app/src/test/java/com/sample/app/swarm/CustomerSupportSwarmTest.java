package com.sample.app.swarm;

import com.enterprise.llm.agent.ToolCall;
import com.enterprise.llm.agent.swarm.AgentSwarmOrchestrator;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CustomerSupportSwarmTest {

    @Autowired
    private CustomerSupportSwarmService swarmService;

    @Test
    @DisplayName("Verify Customer Support Swarm: TriageAgent delegates to LogisticsAgent which invokes query_order_status")
    void testEndToEndCustomerSupportWorkflow() {
        AtomicInteger step = new AtomicInteger(0);

        LlmClient mockLlm = new LlmClient() {
            @Override
            public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
                int s = step.getAndIncrement();
                if (s == 0) {
                    // Turn 1 (TriageAgent): Hand off to LogisticsAgent
                    ToolCall handoff = new ToolCall(
                            "call_triage_1",
                            "function",
                            new ToolCall.FunctionCall(
                                    AgentSwarmOrchestrator.HANDOFF_TOOL_PREFIX + CustomerSupportSwarmService.ROLE_LOGISTICS,
                                    Map.of("reason", "Customer inquiry regarding ORD-999 logistics status")
                            )
                    );
                    return new ChatCompletionResponse(
                            "resp-1", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                            List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools("", List.of(handoff)), "tool_calls")),
                            null
                    );
                } else if (s == 1) {
                    // Turn 2 (LogisticsAgent): Call query_order_status
                    ToolCall query = new ToolCall(
                            "call_query_1",
                            "function",
                            new ToolCall.FunctionCall("query_order_status", Map.of("orderId", "ORD-999"))
                    );
                    return new ChatCompletionResponse(
                            "resp-2", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                            List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools("", List.of(query)), "tool_calls")),
                            null
                    );
                } else {
                    // Turn 3 (LogisticsAgent): Final customer response
                    return new ChatCompletionResponse(
                            "resp-3", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                            List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Your package ORD-999 is IN_TRANSIT with SF-Express, estimated 2 days."), "stop")),
                            null
                    );
                }
            }

            @Override
            public StreamCancellationHandle streamChatCompletion(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
                return new StreamCancellationHandle(() -> {});
            }
        };

        ChatCompletionResponse response = swarmService.handleInquiry(
                mockLlm,
                "Where is my order ORD-999? When will it arrive?"
        );

        assertThat(response).isNotNull();
        assertThat(response.choices().get(0).message().content())
                .contains("ORD-999 is IN_TRANSIT with SF-Express");
        assertThat(step.get()).isEqualTo(3);
    }
}
