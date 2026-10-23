package com.enterprise.llm.agent;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.guard.CrossTenantViolationException;
import com.enterprise.llm.guard.SecurityViolationException;
import com.enterprise.llm.guard.ToolAction;
import com.enterprise.llm.guard.ToolActionGatekeeper;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class AgentToolOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentToolOrchestrator.class);
    public static final int DEFAULT_MAX_ITERATIONS = 5;

    private final ToolRegistry toolRegistry;
    private final ToolActionGatekeeper toolActionGatekeeper;

    @Autowired
    public AgentToolOrchestrator(ToolRegistry toolRegistry, ToolActionGatekeeper toolActionGatekeeper) {
        this.toolRegistry = toolRegistry;
        this.toolActionGatekeeper = toolActionGatekeeper;
    }

    public AgentToolOrchestrator(ToolRegistry toolRegistry) {
        this(toolRegistry, new ToolActionGatekeeper());
    }

    public AgentToolOrchestrator() {
        this(new ToolRegistry(), new ToolActionGatekeeper());
    }

    @FunctionalInterface
    public interface CheckpointListener {
        void onCheckpoint(int currentIteration, List<ChatMessage> conversationHistory);
    }

    public ChatCompletionResponse runLoop(
            LlmClient llmClient,
            ChatCompletionRequest initialRequest,
            String tenantId,
            int maxIterations
    ) {
        List<ChatMessage> conversationHistory = new ArrayList<>(
                initialRequest.messages() != null ? initialRequest.messages() : Collections.emptyList()
        );
        return runLoopWithCheckpoint(llmClient, initialRequest, conversationHistory, tenantId, 0, maxIterations, null);
    }

    public ChatCompletionResponse runLoopWithCheckpoint(
            LlmClient llmClient,
            ChatCompletionRequest initialRequest,
            List<ChatMessage> existingHistory,
            String tenantId,
            int startIteration,
            int maxIterations,
            CheckpointListener checkpointListener
    ) {
        if (llmClient == null) {
            throw new IllegalArgumentException("LlmClient must not be null");
        }
        if (initialRequest == null) {
            throw new IllegalArgumentException("ChatCompletionRequest must not be null");
        }

        List<ChatMessage> conversationHistory = new ArrayList<>(
                existingHistory != null && !existingHistory.isEmpty() ? existingHistory :
                        (initialRequest.messages() != null ? initialRequest.messages() : Collections.emptyList())
        );

        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        int iteration = startIteration;

        ChatCompletionResponse lastResponse = null;

        while (iteration < maxIterations) {
            iteration++;
            log.debug("Agent tool loop iteration {}/{}", iteration, maxIterations);

            ChatCompletionRequest requestForTurn = new ChatCompletionRequest(
                    initialRequest.model(),
                    new ArrayList<>(conversationHistory),
                    false,
                    initialRequest.temperature(),
                    initialRequest.maxTokens(),
                    initialRequest.tools(),
                    initialRequest.toolChoice()
            );

            lastResponse = llmClient.chatCompletion(requestForTurn);

            if (lastResponse != null && lastResponse.usage() != null) {
                if (lastResponse.usage().promptTokens() != null) {
                    totalPromptTokens += lastResponse.usage().promptTokens();
                }
                if (lastResponse.usage().completionTokens() != null) {
                    totalCompletionTokens += lastResponse.usage().completionTokens();
                }
            }

            if (lastResponse == null || lastResponse.choices() == null || lastResponse.choices().isEmpty()) {
                break;
            }

            ChatCompletionResponse.Choice choice = lastResponse.choices().get(0);
            ChatMessage assistantMsg = choice.message();

            if (assistantMsg == null) {
                break;
            }

            // Check if model emitted tool calls
            List<ToolCall> toolCalls = assistantMsg.toolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                // Final answer generated, loop completes
                break;
            }

            // Append assistant message containing the tool calls to history
            conversationHistory.add(assistantMsg);

            // Execute each requested tool call
            for (ToolCall tc : toolCalls) {
                String callId = tc.id() != null ? tc.id() : ("call_" + System.currentTimeMillis());
                String toolName = (tc.function() != null && tc.function().name() != null) ? tc.function().name() : "";
                Map<String, Object> arguments = (tc.function() != null && tc.function().arguments() != null)
                        ? tc.function().arguments() : Collections.emptyMap();

                ToolAction action = new ToolAction(toolName, arguments);
                String executionResult;

                try {
                    // 1. ToolActionGatekeeper validation (security & tenant isolation)
                    if (toolActionGatekeeper != null) {
                        toolActionGatekeeper.validateAction(action, tenantId);
                    }

                    // 2. Execute registered tool
                    var registeredOpt = toolRegistry.getTool(toolName);
                    if (registeredOpt.isPresent()) {
                        executionResult = registeredOpt.get().handler().execute(arguments);
                    } else {
                        executionResult = "Error: Tool '" + toolName + "' is not registered.";
                        log.warn("Attempted to execute unregistered tool '{}'", toolName);
                    }
                } catch (SecurityViolationException | CrossTenantViolationException e) {
                    log.warn("Tool execution blocked by gatekeeper: {}", e.getMessage());
                    executionResult = "Security policy violation: " + e.getMessage();
                } catch (Exception e) {
                    log.error("Error executing tool '{}'", toolName, e);
                    executionResult = "Tool execution failure: " + e.getMessage();
                }

                // Append tool role response to conversation
                conversationHistory.add(ChatMessage.tool(callId, executionResult));
            }

            // Checkpoint callback after each turn
            if (checkpointListener != null) {
                checkpointListener.onCheckpoint(iteration, new ArrayList<>(conversationHistory));
            }
        }

        // Aggregate total tokens into final response usage
        Usage aggregatedUsage = new Usage(totalPromptTokens, totalCompletionTokens, totalPromptTokens + totalCompletionTokens);
        if (lastResponse != null) {
            return new ChatCompletionResponse(
                    lastResponse.id(),
                    lastResponse.object(),
                    lastResponse.created(),
                    lastResponse.model(),
                    lastResponse.choices(),
                    aggregatedUsage
            );
        }
        return null;
    }

    public ChatCompletionResponse runLoop(LlmClient llmClient, ChatCompletionRequest initialRequest, String tenantId) {
        return runLoop(llmClient, initialRequest, tenantId, DEFAULT_MAX_ITERATIONS);
    }
}
