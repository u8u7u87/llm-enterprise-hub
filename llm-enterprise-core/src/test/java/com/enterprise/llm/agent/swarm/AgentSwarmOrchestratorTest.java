package com.enterprise.llm.agent.swarm;

import com.enterprise.llm.agent.ToolCall;
import com.enterprise.llm.agent.ToolDefinition;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSwarmOrchestratorTest {

    @Test
    @DisplayName("Verify Multi-Agent Swarm Orchestrator performs Handoff from Triage to Billing agent")
    void testSwarmHandoffFromTriageToBilling() {
        // 1. Define Agent Roles
        AgentRole triageAgent = new AgentRole(
                "TriageAgent",
                "You are customer triage. If user asks about billing or refund, transfer to BillingAgent.",
                List.of(),
                List.of("BillingAgent")
        );

        ToolDefinition queryOrderTool = new ToolDefinition("query_order", "Query customer order info", Map.of());
        AgentRole billingAgent = new AgentRole(
                "BillingAgent",
                "You are billing specialist. Use query_order to look up and process refunds.",
                List.of(queryOrderTool),
                List.of()
        );
        billingAgent.registerHandler("query_order", args -> "{\"order_id\":\"1001\",\"paid\":true,\"amount\":99.0}");

        AgentSwarmOrchestrator swarm = new AgentSwarmOrchestrator();
        swarm.registerRole(triageAgent);
        swarm.registerRole(billingAgent);

        // 2. Mock LLM:
        // Turn 1 (TriageAgent): returns tool call transfer_to_BillingAgent
        // Turn 2 (BillingAgent): returns tool call query_order
        // Turn 3 (BillingAgent): returns final resolution text
        AtomicInteger turn = new AtomicInteger(0);
        LlmClient mockLlm = new LlmClient() {
            @Override
            public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
                int t = turn.getAndIncrement();
                if (t == 0) {
                    // Triage transfers to BillingAgent
                    ToolCall handoffCall = new ToolCall(
                            "call_handoff_1",
                            "function",
                            new ToolCall.FunctionCall(
                                    AgentSwarmOrchestrator.HANDOFF_TOOL_PREFIX + "BillingAgent",
                                    Map.of("reason", "Customer needs refund for order #1001", "orderId", "1001")
                            )
                    );
                    return new ChatCompletionResponse(
                            "resp-1", "chat.completion", System.currentTimeMillis() / 1000, "mock-model",
                            List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools("", List.of(handoffCall)), "tool_calls")),
                            null
                    );
                } else if (t == 1) {
                    // Billing agent calls query_order
                    ToolCall orderCall = new ToolCall(
                            "call_order_1",
                            "function",
                            new ToolCall.FunctionCall("query_order", Map.of("order_id", "1001"))
                    );
                    return new ChatCompletionResponse(
                            "resp-2", "chat.completion", System.currentTimeMillis() / 1000, "mock-model",
                            List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools("", List.of(orderCall)), "tool_calls")),
                            null
                    );
                } else {
                    // Final billing answer
                    return new ChatCompletionResponse(
                            "resp-3", "chat.completion", System.currentTimeMillis() / 1000, "mock-model",
                            List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Your order #1001 for $99.0 has been refunded successfully."), "stop")),
                            null
                    );
                }
            }

            @Override
            public StreamCancellationHandle streamChatCompletion(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
                return new StreamCancellationHandle(() -> {});
            }
        };

        AgentSharedContext context = new AgentSharedContext();
        ChatCompletionRequest initialReq = new ChatCompletionRequest(
                "mock-model",
                List.of(ChatMessage.user("I need a refund for order #1001")),
                false,
                0.7
        );

        ChatCompletionResponse response = swarm.runSwarm(
                mockLlm,
                "TriageAgent",
                initialReq,
                context,
                "tenant-alpha",
                5
        );

        assertThat(response).isNotNull();
        assertThat(response.choices().get(0).message().content())
                .contains("refunded successfully");

        // Verify shared context received handoff updates
        assertThat(context.get("orderId")).isEqualTo("1001");
        assertThat(context.get("reason")).isEqualTo("Customer needs refund for order #1001");
    }

    @Test
    @DisplayName("Verify Multi-Agent Swarm prevents infinite circular handoffs")
    void testSwarmPreventsInfiniteHandoffLoop() {
        AgentRole agentA = new AgentRole("AgentA", "A", List.of(), List.of("AgentB"));
        AgentRole agentB = new AgentRole("AgentB", "B", List.of(), List.of("AgentA"));

        AgentSwarmOrchestrator swarm = new AgentSwarmOrchestrator();
        swarm.registerRole(agentA);
        swarm.registerRole(agentB);

        // Mock LLM that always triggers handoff back and forth
        AtomicInteger count = new AtomicInteger(0);
        LlmClient mockLlm = new LlmClient() {
            @Override
            public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
                int c = count.getAndIncrement();
                String target = (c % 2 == 0) ? "AgentB" : "AgentA";
                ToolCall call = new ToolCall(
                        "call_" + c,
                        "function",
                        new ToolCall.FunctionCall(AgentSwarmOrchestrator.HANDOFF_TOOL_PREFIX + target, Map.of("reason", "looping"))
                );
                return new ChatCompletionResponse(
                        "resp-" + c, "chat.completion", System.currentTimeMillis() / 1000, "mock-model",
                        List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools("", List.of(call)), "tool_calls")),
                        null
                );
            }

            @Override
            public StreamCancellationHandle streamChatCompletion(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
                return new StreamCancellationHandle(() -> {});
            }
        };

        ChatCompletionRequest initialReq = new ChatCompletionRequest(
                "mock-model",
                List.of(ChatMessage.user("Ping pong")),
                false,
                0.7
        );

        // Max 3 turns
        ChatCompletionResponse response = swarm.runSwarm(
                mockLlm,
                "AgentA",
                initialReq,
                new AgentSharedContext(),
                "tenant-test",
                3
        );

        assertThat(response).isNotNull();
        // Count should not exceed max turns (3)
        assertThat(count.get()).isLessThanOrEqualTo(4);
    }
}
