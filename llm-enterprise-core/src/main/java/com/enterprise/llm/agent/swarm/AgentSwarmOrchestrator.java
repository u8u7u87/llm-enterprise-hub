package com.enterprise.llm.agent.swarm;

import com.enterprise.llm.agent.ToolCall;
import com.enterprise.llm.agent.ToolDefinition;
import com.enterprise.llm.agent.ToolHandler;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise Multi-Agent Swarm Orchestrator.
 * Coordinates autonomous agent handoffs, shared blackboard context,
 * and multi-turn collaborative problem solving.
 */
@Service
public class AgentSwarmOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentSwarmOrchestrator.class);
    public static final String HANDOFF_TOOL_PREFIX = "transfer_to_";
    public static final int DEFAULT_MAX_HANDOFF_TURNS = 5;

    private final Map<String, AgentRole> roles = new ConcurrentHashMap<>();

    public AgentSwarmOrchestrator() {
    }

    public void registerRole(AgentRole role) {
        if (role == null || role.getName() == null) {
            throw new IllegalArgumentException("AgentRole and its name must not be null");
        }
        roles.put(role.getName(), role);
        log.info("Registered AgentRole [{}] with {} tools and {} handoffs",
                role.getName(), role.getTools().size(), role.getHandoffTargets().size());
    }

    public AgentRole getRole(String name) {
        return roles.get(name);
    }

    public Map<String, AgentRole> getRoles() {
        return Collections.unmodifiableMap(roles);
    }

    public ChatCompletionResponse runSwarm(
            LlmClient llmClient,
            String initialRoleName,
            ChatCompletionRequest initialRequest,
            AgentSharedContext context,
            String tenantId,
            int maxHandoffTurns
    ) {
        if (llmClient == null) {
            throw new IllegalArgumentException("LlmClient cannot be null");
        }
        if (initialRoleName == null || !roles.containsKey(initialRoleName)) {
            throw new IllegalArgumentException("Initial agent role [" + initialRoleName + "] is not registered");
        }
        if (initialRequest == null) {
            throw new IllegalArgumentException("ChatCompletionRequest cannot be null");
        }

        AgentSharedContext effectiveContext = (context != null) ? context : new AgentSharedContext();
        String currentRoleName = initialRoleName;

        List<ChatMessage> conversation = new ArrayList<>();
        if (initialRequest.messages() != null) {
            conversation.addAll(initialRequest.messages());
        }

        int turn = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        ChatCompletionResponse lastResponse = null;

        while (turn < maxHandoffTurns) {
            turn++;
            AgentRole currentRole = roles.get(currentRoleName);
            if (currentRole == null) {
                log.warn("Swarm attempted to route to unknown role: {}", currentRoleName);
                break;
            }

            log.debug("Swarm Turn {}/{} executing AgentRole [{}]", turn, maxHandoffTurns, currentRoleName);

            // 1. Prepare system prompt with role instructions & shared blackboard state
            String systemInstructions = currentRole.getInstructions();
            if (!effectiveContext.snapshot().isEmpty()) {
                systemInstructions = systemInstructions + "\n[Shared Blackboard Context: " + effectiveContext.snapshot() + "]";
            }
            ensureSystemPrompt(conversation, systemInstructions);

            // 2. Synthesize tools for current role + handoff tools
            List<ToolDefinition> turnTools = new ArrayList<>(currentRole.getTools());
            for (String targetRole : currentRole.getHandoffTargets()) {
                String toolName = HANDOFF_TOOL_PREFIX + targetRole;
                turnTools.add(new ToolDefinition(
                        toolName,
                        "Transfer conversation and user intent to the specialized " + targetRole + " agent",
                        Map.of("reason", "Reason for the transfer", "type", "object")
                ));
            }

            ChatCompletionRequest turnRequest = new ChatCompletionRequest(
                    initialRequest.model(),
                    new ArrayList<>(conversation),
                    false,
                    initialRequest.temperature(),
                    initialRequest.maxTokens(),
                    turnTools,
                    null,
                    initialRequest.sessionId(),
                    initialRequest.user()
            );

            ChatCompletionResponse response = llmClient.chatCompletion(turnRequest);
            lastResponse = response;

            if (response != null && response.usage() != null) {
                if (response.usage().promptTokens() != null) totalPromptTokens += response.usage().promptTokens();
                if (response.usage().completionTokens() != null) totalCompletionTokens += response.usage().completionTokens();
            }

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                break;
            }

            ChatMessage assistantMessage = response.choices().get(0).message();
            if (assistantMessage == null) {
                break;
            }

            // 3. Inspect if LLM produced tool calls
            List<ToolCall> toolCalls = assistantMessage.toolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                conversation.add(assistantMessage);

                boolean handoffOccurred = false;
                for (ToolCall tc : toolCalls) {
                    String funcName = (tc.function() != null && tc.function().name() != null) ? tc.function().name() : "";
                    Map<String, Object> arguments = (tc.function() != null && tc.function().arguments() != null)
                            ? tc.function().arguments() : Collections.emptyMap();

                    if (funcName.startsWith(HANDOFF_TOOL_PREFIX)) {
                        String targetRole = funcName.substring(HANDOFF_TOOL_PREFIX.length());
                        log.info("Agent [{}] handing off to [{}] with arguments: {}", currentRoleName, targetRole, arguments);

                        // Update shared context with handoff parameters
                        effectiveContext.putAll(arguments);

                        String toolResult = "Successfully transferred to " + targetRole;
                        conversation.add(ChatMessage.tool(tc.id(), toolResult));

                        currentRoleName = targetRole;
                        handoffOccurred = true;
                        break; // One handoff per turn
                    } else {
                        // Regular tool execution
                        ToolHandler handler = currentRole.getHandler(funcName);
                        String toolResult;
                        if (handler != null) {
                            try {
                                toolResult = handler.execute(arguments);
                            } catch (Exception e) {
                                log.error("Error executing tool {} in agent {}", funcName, currentRoleName, e);
                                toolResult = "Error: " + e.getMessage();
                            }
                        } else {
                            toolResult = "Tool " + funcName + " completed.";
                        }
                        conversation.add(ChatMessage.tool(tc.id(), toolResult));
                    }
                }

                // If handoff occurred or tools executed, continue loop for next agent or current agent reflection
                continue;
            }

            // 4. Normal text completion without tool calls -> Task finished!
            return response;
        }

        // Exceeded max turns or ended
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

    private void ensureSystemPrompt(List<ChatMessage> conversation, String systemInstructions) {
        if (conversation.isEmpty()) {
            conversation.add(ChatMessage.system(systemInstructions));
            return;
        }
        if ("system".equalsIgnoreCase(conversation.get(0).role())) {
            conversation.set(0, ChatMessage.system(systemInstructions));
        } else {
            conversation.add(0, ChatMessage.system(systemInstructions));
        }
    }
}
