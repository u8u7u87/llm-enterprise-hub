package com.enterprise.llm.agent;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.guard.ToolActionGatekeeper;
import com.enterprise.llm.guard.tool.PropertiesToolPolicyProvider;
import com.enterprise.llm.guard.tool.ToolPolicyProperties;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentToolOrchestratorTest {

    private ToolRegistry toolRegistry;
    private ToolActionGatekeeper gatekeeper;
    private AgentToolOrchestrator orchestrator;
    private LlmClient mockLlmClient;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();

        ToolPolicyProperties properties = new ToolPolicyProperties();
        properties.setEnabled(true);
        properties.setTenantIsolationEnabled(true);
        properties.setDangerPatterns(List.of("(?i)rm\\s+-rf", "(?i)DROP\\s+TABLE", "(?i)/etc/passwd"));
        properties.setTenantIdentityFields(List.of("tenant_id", "tenantid"));
        gatekeeper = new ToolActionGatekeeper(new PropertiesToolPolicyProvider(properties));

        orchestrator = new AgentToolOrchestrator(toolRegistry, gatekeeper);
        mockLlmClient = mock(LlmClient.class);
    }

    @Test
    @DisplayName("Should return immediately when model generates final text answer without tool calls")
    void shouldReturnWhenModelHasNoToolCalls() {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("Hello world")),
                false,
                0.7
        );

        ChatCompletionResponse directResponse = new ChatCompletionResponse(
                "chatcmpl-1", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Hello! How can I help you today?"), "stop")),
                new Usage(10, 15, 25)
        );

        when(mockLlmClient.chatCompletion(any())).thenReturn(directResponse);

        ChatCompletionResponse response = orchestrator.runLoop(mockLlmClient, request, "tenant-a");

        assertThat(response).isNotNull();
        assertThat(response.choices().get(0).message().content()).isEqualTo("Hello! How can I help you today?");
        assertThat(response.usage().totalTokens()).isEqualTo(25);
        verify(mockLlmClient, times(1)).chatCompletion(any());
    }

    @Test
    @DisplayName("Should execute registered tool, append tool message, and complete in next turn")
    void shouldExecuteToolAndCompleteReActLoop() {
        // Register a weather query tool
        toolRegistry.registerTool(
                "get_weather",
                "Get current weather for location",
                Map.of("type", "object"),
                args -> "Sunny, 25°C in " + args.get("city")
        );

        ChatCompletionRequest initialRequest = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("What is the weather in Beijing?")),
                false,
                0.7,
                null,
                toolRegistry.getAllDefinitions().stream().toList(),
                "auto"
        );

        // Turn 1: Model requests tool call
        ToolCall toolCall = new ToolCall("call_abc123", "get_weather", Map.of("city", "Beijing"));
        ChatCompletionResponse turn1Response = new ChatCompletionResponse(
                "chatcmpl-1", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools(null, List.of(toolCall)), "tool_calls")),
                new Usage(15, 10, 25)
        );

        // Turn 2: Model receives tool result and produces final answer
        ChatCompletionResponse turn2Response = new ChatCompletionResponse(
                "chatcmpl-2", "chat.completion", 1001L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Beijing is currently sunny and 25°C."), "stop")),
                new Usage(35, 12, 47)
        );

        when(mockLlmClient.chatCompletion(any()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        ChatCompletionResponse finalResponse = orchestrator.runLoop(mockLlmClient, initialRequest, "tenant-a");

        assertThat(finalResponse).isNotNull();
        assertThat(finalResponse.choices().get(0).message().content()).isEqualTo("Beijing is currently sunny and 25°C.");
        assertThat(finalResponse.usage().totalTokens()).isEqualTo(25 + 47);

        // Verify turn 2 request received tool result message
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(mockLlmClient, times(2)).chatCompletion(captor.capture());

        ChatCompletionRequest secondTurnRequest = captor.getAllValues().get(1);
        List<ChatMessage> messages = secondTurnRequest.messages();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).content()).isEqualTo("What is the weather in Beijing?");
        assertThat(messages.get(1).toolCalls()).hasSize(1);
        assertThat(messages.get(2).role()).isEqualTo("tool");
        assertThat(messages.get(2).toolCallId()).isEqualTo("call_abc123");
        assertThat(messages.get(2).content()).contains("Sunny, 25°C in Beijing");
    }

    @Test
    @DisplayName("Should intercept dangerous command via Gatekeeper and feed security error back to model")
    void shouldInterceptDangerousCommandViaGatekeeper() {
        AtomicInteger executedDanger = new AtomicInteger(0);
        toolRegistry.registerTool(
                "bash_exec",
                "Execute shell command",
                Map.of("type", "object"),
                args -> {
                    executedDanger.incrementAndGet();
                    return "Command executed";
                }
        );

        ChatCompletionRequest initialRequest = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("Clean up the server directory")),
                false,
                0.7
        );

        // Turn 1: Model tries to execute rm -rf /
        ToolCall toolCall = new ToolCall("call_danger", "bash_exec", Map.of("cmd", "rm -rf /tmp/data"));
        ChatCompletionResponse turn1Response = new ChatCompletionResponse(
                "chatcmpl-danger", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools(null, List.of(toolCall)), "tool_calls")),
                new Usage(10, 10, 20)
        );

        // Turn 2: Model handles security warning
        ChatCompletionResponse turn2Response = new ChatCompletionResponse(
                "chatcmpl-turn2", "chat.completion", 1001L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("I cannot perform deletion of directory."), "stop")),
                new Usage(25, 10, 35)
        );

        when(mockLlmClient.chatCompletion(any()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        ChatCompletionResponse finalResponse = orchestrator.runLoop(mockLlmClient, initialRequest, "tenant-a");

        assertThat(executedDanger.get()).isZero(); // Handlers must NEVER be called
        assertThat(finalResponse.choices().get(0).message().content()).isEqualTo("I cannot perform deletion of directory.");

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(mockLlmClient, times(2)).chatCompletion(captor.capture());

        ChatCompletionRequest secondRequest = captor.getAllValues().get(1);
        ChatMessage toolResult = secondRequest.messages().get(2);
        assertThat(toolResult.role()).isEqualTo("tool");
        assertThat(toolResult.content()).contains("Security policy violation");
    }

    @Test
    @DisplayName("Should intercept cross-tenant argument violation via Gatekeeper")
    void shouldInterceptCrossTenantArgumentViolation() {
        toolRegistry.registerTool(
                "fetch_order",
                "Fetch order by ID",
                Map.of(),
                args -> "Order data for " + args.get("order_id")
        );

        ChatCompletionRequest initialRequest = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("Fetch order 999")),
                false,
                0.7
        );

        // Turn 1: Model calls fetch_order with tenant_id = tenant-b while caller is tenant-a
        ToolCall crossTenantCall = new ToolCall("call_xtenant", "fetch_order", Map.of("order_id", "999", "tenant_id", "tenant-b"));
        ChatCompletionResponse turn1Response = new ChatCompletionResponse(
                "chatcmpl-xt", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools(null, List.of(crossTenantCall)), "tool_calls")),
                new Usage(10, 10, 20)
        );

        ChatCompletionResponse turn2Response = new ChatCompletionResponse(
                "chatcmpl-turn2", "chat.completion", 1001L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Access denied: cross-tenant query forbidden."), "stop")),
                new Usage(25, 10, 35)
        );

        when(mockLlmClient.chatCompletion(any()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        ChatCompletionResponse finalResponse = orchestrator.runLoop(mockLlmClient, initialRequest, "tenant-a");

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(mockLlmClient, times(2)).chatCompletion(captor.capture());

        ChatMessage toolResult = captor.getAllValues().get(1).messages().get(2);
        assertThat(toolResult.role()).isEqualTo("tool");
        assertThat(toolResult.content()).contains("Cross-tenant access violation");
    }

    @Test
    @DisplayName("Should respect maxIterations cap and terminate even if model continuously calls tools")
    void shouldRespectMaxIterationsCap() {
        toolRegistry.registerTool("dummy_loop", "Always loops", Map.of(), args -> "continue");

        ChatCompletionRequest request = new ChatCompletionRequest("qwen2.5:0.5b", List.of(ChatMessage.user("Loop")), false, 0.7);

        ToolCall loopCall = new ToolCall("call_loop", "dummy_loop", Collections.emptyMap());
        ChatCompletionResponse loopResponse = new ChatCompletionResponse(
                "loop", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools(null, List.of(loopCall)), "tool_calls")),
                new Usage(5, 5, 10)
        );

        when(mockLlmClient.chatCompletion(any())).thenReturn(loopResponse);

        ChatCompletionResponse response = orchestrator.runLoop(mockLlmClient, request, "tenant-a", 3);

        assertThat(response).isNotNull();
        verify(mockLlmClient, times(3)).chatCompletion(any());
    }
}
