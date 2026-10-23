package com.enterprise.llm.context;

import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.token.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final ContextBudgetProperties properties;
    private final TokenEstimator tokenEstimator;
    private final ContextDistiller contextDistiller;
    private final com.enterprise.llm.memory.WorkingMemoryManager workingMemoryManager;

    @Autowired
    public ContextManager(
            ContextBudgetProperties properties,
            TokenEstimator tokenEstimator,
            ContextDistiller contextDistiller,
            @Autowired(required = false) com.enterprise.llm.memory.WorkingMemoryManager workingMemoryManager
    ) {
        this.properties = properties != null ? properties : new ContextBudgetProperties();
        this.tokenEstimator = tokenEstimator != null ? tokenEstimator : new TokenEstimator();
        this.contextDistiller = contextDistiller != null ? contextDistiller : new HeuristicContextDistiller();
        this.workingMemoryManager = workingMemoryManager;
    }

    public ContextManager(
            ContextBudgetProperties properties,
            TokenEstimator tokenEstimator,
            ContextDistiller contextDistiller
    ) {
        this(properties, tokenEstimator, contextDistiller, null);
    }

    public ContextManager() {
        this(new ContextBudgetProperties(), new TokenEstimator(), new HeuristicContextDistiller(), null);
    }

    public ChatCompletionRequest manageContext(ChatCompletionRequest request, int targetMaxTokens) {
        if (!properties.isEnabled() || request == null || request.messages() == null || request.messages().isEmpty()) {
            return request;
        }

        // Pre-step: Rehydrate working memory if user query is semantically relevant
        if (workingMemoryManager != null) {
            request = workingMemoryManager.recallAndRehydrate(request, 0.4);
        }

        int maxTokens = targetMaxTokens > 0 ? targetMaxTokens : properties.getMaxContextTokens();
        int triggerThreshold = (int) (maxTokens * properties.getCompressionTriggerRatio());
        int currentTokens = tokenEstimator.estimatePromptTokens(request.messages());

        if (currentTokens + properties.getReservedResponseTokens() <= triggerThreshold) {
            return request; // Within safe budget
        }

        log.info("Context tokens ({}) + reserved ({}) exceeded trigger threshold ({}). Starting compaction...",
                currentTokens, properties.getReservedResponseTokens(), triggerThreshold);

        List<ChatMessage> messages = new ArrayList<>(request.messages());

        // Phase 1: Tool Message Pruning
        if (properties.isToolPruningEnabled()) {
            messages = pruneOldToolMessages(messages, request.sessionId());
            currentTokens = tokenEstimator.estimatePromptTokens(messages);
            if (currentTokens + properties.getReservedResponseTokens() <= triggerThreshold) {
                log.info("Tool pruning successfully reduced tokens to {}. Target reached.", currentTokens);
                ChatCompletionRequest prunedReq = copyWithMessages(request, messages);
                return (workingMemoryManager != null) ? workingMemoryManager.recallAndRehydrate(prunedReq, 0.3) : prunedReq;
            }
        }

        // Phase 2 & 3: Sliding window + Semantic Distillation
        messages = applySlidingWindowAndDistill(messages);
        int finalTokens = tokenEstimator.estimatePromptTokens(messages);
        log.info("Context distillation complete. Tokens reduced from {} to {}.", currentTokens, finalTokens);

        ChatCompletionRequest compacted = copyWithMessages(request, messages);
        return (workingMemoryManager != null) ? workingMemoryManager.recallAndRehydrate(compacted, 0.3) : compacted;
    }

    public ChatCompletionRequest manageContext(ChatCompletionRequest request) {
        return manageContext(request, properties.getMaxContextTokens());
    }

    public ChatCompletionRequest forceEmergencyCompaction(ChatCompletionRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return request;
        }
        log.warn("Emergency compaction triggered! Aggressively reducing context...");
        List<ChatMessage> pruned = pruneOldToolMessages(new ArrayList<>(request.messages()), request.sessionId());
        List<ChatMessage> distilled = applySlidingWindowAndDistill(pruned);
        return copyWithMessages(request, distilled);
    }

    private List<ChatMessage> pruneOldToolMessages(List<ChatMessage> messages) {
        return pruneOldToolMessages(messages, null);
    }

    private List<ChatMessage> pruneOldToolMessages(List<ChatMessage> messages, String sessionId) {
        List<ChatMessage> result = new ArrayList<>();
        int size = messages.size();
        for (int i = 0; i < size; i++) {
            ChatMessage msg = messages.get(i);
            // Only prune tool messages that are older than the last 2 turns
            if ("tool".equalsIgnoreCase(msg.role()) && (i < size - 2)) {
                if (msg.content() != null && msg.content().length() > properties.getToolPruningThresholdChars()) {
                    String collapsed;
                    if (workingMemoryManager != null) {
                        collapsed = workingMemoryManager.offload(
                                sessionId,
                                msg.content(),
                                "tool",
                                "[Earlier tool output pruned for context token efficiency, length: " + msg.content().length() + " chars]"
                        );
                    } else {
                        collapsed = "{\"status\":\"success\",\"summary\":\"[Earlier tool output pruned for context token efficiency, length: "
                                + msg.content().length() + " chars]\"}";
                    }
                    result.add(ChatMessage.tool(msg.toolCallId(), collapsed));
                    continue;
                }
            }
            result.add(msg);
        }
        return result;
    }

    private List<ChatMessage> applySlidingWindowAndDistill(List<ChatMessage> messages) {
        if (messages.size() <= properties.getKeepLastMessages() + 1) {
            return messages; // Not enough messages to slice
        }

        // 1. Separate System messages (always preserve)
        List<ChatMessage> systemMessages = new ArrayList<>();
        List<ChatMessage> nonSystemMessages = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if ("system".equalsIgnoreCase(msg.role())) {
                systemMessages.add(msg);
            } else {
                nonSystemMessages.add(msg);
            }
        }

        int keepCount = Math.min(properties.getKeepLastMessages(), nonSystemMessages.size());
        int splitIndex = nonSystemMessages.size() - keepCount;

        if (splitIndex <= 0) {
            return messages;
        }

        List<ChatMessage> expiredMessages = nonSystemMessages.subList(0, splitIndex);
        List<ChatMessage> retainedRecentMessages = nonSystemMessages.subList(splitIndex, nonSystemMessages.size());

        // 2. Distill expired messages into summary
        String summaryText = contextDistiller.distill(expiredMessages);

        // 3. Assemble final compacted messages
        List<ChatMessage> finalMessages = new ArrayList<>(systemMessages);
        if (!summaryText.isBlank()) {
            finalMessages.add(ChatMessage.system(summaryText));
        }
        finalMessages.addAll(retainedRecentMessages);

        return finalMessages;
    }

    private ChatCompletionRequest copyWithMessages(ChatCompletionRequest original, List<ChatMessage> newMessages) {
        return new ChatCompletionRequest(
                original.model(),
                newMessages,
                original.stream(),
                original.temperature(),
                original.maxTokens(),
                original.tools(),
                original.toolChoice(),
                original.sessionId(),
                original.user()
        );
    }
}
