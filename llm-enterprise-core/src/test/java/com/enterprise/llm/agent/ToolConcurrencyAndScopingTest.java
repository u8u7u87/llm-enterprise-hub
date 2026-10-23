package com.enterprise.llm.agent;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

class ToolConcurrencyAndScopingTest {

    private ToolRegistry toolRegistry;
    private AgentToolOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        orchestrator = new AgentToolOrchestrator(toolRegistry);
    }

    @Test
    @DisplayName("Verify multiple tool calls are executed concurrently using virtual threads")
    void testVirtualThreadParallelToolExecution() {
        // Register 3 slow tools (100ms each)
        for (int i = 1; i <= 3; i++) {
            final int id = i;
            toolRegistry.registerTool(
                    new ToolDefinition("slow_tool_" + id, "Slow tool " + id, Map.of()),
                    args -> {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {}
                        return "{\"result\": \"data_" + id + "\"}";
                    }
            );
        }

        LlmClient mockClient = Mockito.mock(LlmClient.class);

        // Turn 1: LLM outputs 3 parallel tool calls
        List<ToolCall> parallelCalls = List.of(
                new ToolCall("call_1", "function", new ToolCall.FunctionCall("slow_tool_1", Map.of())),
                new ToolCall("call_2", "function", new ToolCall.FunctionCall("slow_tool_2", Map.of())),
                new ToolCall("call_3", "function", new ToolCall.FunctionCall("slow_tool_3", Map.of()))
        );

        ChatCompletionResponse turn1Resp = new ChatCompletionResponse(
                "resp-1", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools(null, parallelCalls), "tool_calls")),
                new Usage(10, 10, 20)
        );

        // Turn 2: LLM generates final answer based on all tool outputs
        ChatCompletionResponse turn2Resp = new ChatCompletionResponse(
                "resp-2", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("All 3 tools executed successfully!"), "stop")),
                new Usage(30, 10, 40)
        );

        Mockito.when(mockClient.chatCompletion(any())).thenReturn(turn1Resp).thenReturn(turn2Resp);

        ChatCompletionRequest req = new ChatCompletionRequest("qwen2.5:0.5b", List.of(ChatMessage.user("Run all 3 tools")), false, 0.7);

        long start = System.currentTimeMillis();
        ChatCompletionResponse finalResponse = orchestrator.runLoop(mockClient, req, "tenant-test");
        long duration = System.currentTimeMillis() - start;

        assertThat(finalResponse.choices().get(0).message().content()).isEqualTo("All 3 tools executed successfully!");

        // If sequential, duration would be >= 300ms. With virtual threads concurrent execution, duration is < 250ms.
        assertThat(duration).isLessThan(280);
    }

    @Test
    @DisplayName("Verify tenant scoping and authorized access to tools")
    void testTenantScopingAndAuthorization() {
        // Register standard tool (all tenants) and VIP tool (only tenant-vip)
        toolRegistry.registerTool(
                new ToolDefinition("common_tool", "Common tool", Map.of()),
                args -> "{\"status\": \"common_ok\"}"
        );

        toolRegistry.registerTool(
                new ToolDefinition("vip_tool", "VIP tool", Map.of()),
                args -> "{\"status\": \"vip_ok\"}",
                Set.of("tenant-vip"),
                Set.of("vip:execute")
        );

        // Verify definitions visibility
        var normalDefs = toolRegistry.getDefinitionsForTenant("tenant-normal");
        assertThat(normalDefs).hasSize(1);
        assertThat(normalDefs.iterator().next().name()).isEqualTo("common_tool");

        var vipDefs = toolRegistry.getDefinitionsForTenant("tenant-vip");
        assertThat(vipDefs).hasSize(2);

        // Test execution authorization
        LlmClient mockClient = Mockito.mock(LlmClient.class);
        List<ToolCall> calls = List.of(
                new ToolCall("call_vip", "function", new ToolCall.FunctionCall("vip_tool", Map.of()))
        );

        ChatCompletionResponse turn1 = new ChatCompletionResponse(
                "resp-1", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools(null, calls), "tool_calls")),
                new Usage(10, 10, 20)
        );

        ChatCompletionResponse turn2 = new ChatCompletionResponse(
                "resp-2", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Done"), "stop")),
                new Usage(10, 5, 15)
        );

        Mockito.when(mockClient.chatCompletion(any())).thenReturn(turn1).thenReturn(turn2);

        // When tenant-normal executes vip_tool, it gets unauthorized error
        orchestrator.runLoopWithCheckpoint(
                mockClient,
                new ChatCompletionRequest("qwen2.5:0.5b", List.of(ChatMessage.user("execute vip")), false, 0.7),
                new java.util.ArrayList<>(List.of(ChatMessage.user("execute vip"))),
                "tenant-normal",
                0,
                5,
                (iter, history) -> {
                    if (iter == 1) {
                        ChatMessage toolMsg = history.get(2);
                        assertThat(toolMsg.content()).contains("not authorized to execute tool 'vip_tool'");
                    }
                }
        );
    }
}
