package com.sample.app;

import com.enterprise.llm.agent.AgentToolOrchestrator;
import com.enterprise.llm.agent.ToolCall;
import com.enterprise.llm.agent.ToolDefinition;
import com.enterprise.llm.agent.ToolRegistry;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.context.ContextManager;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class SampleBusinessAppTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AgentToolOrchestrator orchestrator;

    @Autowired
    private ContextManager contextManager;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Verify Spring Boot context loads and tools are automatically registered via @LlmTool")
    void testContextLoadsAndToolsAutoRegistered() {
        assertThat(toolRegistry).isNotNull();
        assertThat(orchestrator).isNotNull();
        assertThat(contextManager).isNotNull();

        Collection<ToolDefinition> definitions = toolRegistry.getAllDefinitions();
        assertThat(definitions).extracting(ToolDefinition::name)
                .contains("query_order_status", "calculate_shipping_fee");

        ToolDefinition orderDef = toolRegistry.getTool("query_order_status").orElseThrow().definition();
        assertThat(orderDef.description()).contains("logistics");
        assertThat(orderDef.parameters()).containsKey("properties");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) orderDef.parameters().get("properties");
        assertThat(props).containsKey("orderId");
    }

    @Test
    @DisplayName("Verify direct execution of @LlmTool via ToolRegistry")
    void testToolRegistryExecution() throws Exception {
        var registeredOpt = toolRegistry.getTool("query_order_status");
        assertThat(registeredOpt).isPresent();

        String result = registeredOpt.get().handler().execute(Map.of("orderId", "ORD-9999"));
        assertThat(result).contains("ORD-9999");
        assertThat(result).contains("IN_TRANSIT");
        assertThat(result).contains("SF-Express");
    }

    @Test
    @DisplayName("Verify ReAct autonomous loop execution with @LlmTool via AgentToolOrchestrator")
    void testOrchestratorLoopWithAnnotatedTool() {
        LlmClient mockLlm = Mockito.mock(LlmClient.class);

        // Turn 1: Model issues a tool call for query_order_status
        ToolCall toolCall = new ToolCall("call_order_1", "query_order_status", Map.of("orderId", "ORD-2026-X"));
        ChatMessage assistantCallMsg = new ChatMessage("assistant", null, List.of(toolCall), null);
        ChatCompletionResponse turn1Response = new ChatCompletionResponse(
                "resp_1", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, assistantCallMsg, "tool_calls")),
                null
        );

        // Turn 2: Model receives tool execution result and provides final text response
        ChatMessage assistantFinalMsg = ChatMessage.assistant("Order ORD-2026-X is currently in transit with SF-Express.");
        ChatCompletionResponse turn2Response = new ChatCompletionResponse(
                "resp_2", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, assistantFinalMsg, "stop")),
                null
        );

        Mockito.when(mockLlm.chatCompletion(any()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        ChatCompletionRequest initialRequest = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("Where is my order ORD-2026-X?")),
                false, 0.7, 512, null, null
        );

        ChatCompletionResponse finalResponse = orchestrator.runLoop(mockLlm, initialRequest, "tenant-business", 3);

        assertThat(finalResponse).isNotNull();
        assertThat(finalResponse.choices().get(0).message().content())
                .contains("Order ORD-2026-X is currently in transit");
        Mockito.verify(mockLlm, Mockito.times(2)).chatCompletion(any());
    }

    @Test
    @DisplayName("Verify ContextManager is autowired and operational")
    void testContextManagerBudgeting() {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(
                        ChatMessage.system("You are a helpful logistics assistant."),
                        ChatMessage.user("What is the status of my order ORD-1001?"),
                        ChatMessage.assistant("It is in transit.")
                ),
                false, 0.7, 256, null, null
        );
        ChatCompletionRequest managed = contextManager.manageContext(request);
        assertThat(managed).isNotNull();
        assertThat(managed.messages()).hasSize(3);
    }

    @Test
    @DisplayName("Verify REST endpoints in sample application")
    void testRestEndpoints() throws Exception {
        mockMvc.perform(get("/api/assistant/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'query_order_status')]").exists())
                .andExpect(jsonPath("$[?(@.name == 'calculate_shipping_fee')]").exists());

        mockMvc.perform(post("/api/assistant/tool-execute")
                        .param("toolName", "query_order_status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("orderId", "ORD-MOCK-777"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ORD-MOCK-777"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
    }
}
