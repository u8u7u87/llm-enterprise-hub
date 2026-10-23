package com.enterprise.llm.memory;

import com.enterprise.llm.context.ContextBudgetProperties;
import com.enterprise.llm.context.ContextManager;
import com.enterprise.llm.context.HeuristicContextDistiller;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.token.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingMemoryContextCompactionIntegrationTest {

    @Test
    @DisplayName("Verify ContextManager offloads pruned tool messages into WorkingMemory and rehydrates on demand")
    void testPruneOffloadAndRehydration() {
        WorkingMemoryManager memoryManager = new WorkingMemoryManager();
        ContextBudgetProperties props = new ContextBudgetProperties();
        props.setEnabled(true);
        props.setMaxContextTokens(300);
        props.setCompressionTriggerRatio(0.5); // trigger at 150 tokens
        props.setReservedResponseTokens(50);
        props.setToolPruningThresholdChars(100);

        ContextManager contextManager = new ContextManager(props, new TokenEstimator(), new HeuristicContextDistiller(), memoryManager);

        String sessionId = "sess-compaction-wm";
        String rawToolResult = "Database query returned 10 items: Product SKU-9912 is Wireless Noise Cancelling Headphones priced at $299 with 15 units remaining in warehouse A.";

        // Turn 1 & 2 in history
        List<ChatMessage> turnHistory = new ArrayList<>();
        turnHistory.add(ChatMessage.user("Query inventory for SKU-9912"));
        turnHistory.add(ChatMessage.tool("call_inv_1", rawToolResult));
        turnHistory.add(ChatMessage.assistant("Found SKU-9912 in warehouse A."));
        // Extra turns to push length older than last 2 turns
        turnHistory.add(ChatMessage.user("What are warehouse opening hours?"));
        turnHistory.add(ChatMessage.assistant("Warehouse A opens from 8 AM to 6 PM."));
        // Turn 3: User now asks a question that exceeds budget
        turnHistory.add(ChatMessage.user("Can you tell me the price and remaining units of SKU-9912 Headphones from the earlier database query?"));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                turnHistory,
                false,
                0.7,
                100,
                null,
                null,
                sessionId,
                "user-manager"
        );

        // Step 1: Manage context (causes pruning of tool message into working memory)
        ChatCompletionRequest managed = contextManager.manageContext(request);

        // The pruned tool message now contains [WorkingMemoryRef: id="mem-..."]
        // AND because the latest user prompt specifically asks for "price and remaining units of SKU-9912 Headphones",
        // the rehydration step rehydrates the details!
        assertThat(managed.messages()).isNotEmpty();
        String allContent = managed.messages().stream().map(ChatMessage::content).reduce("", (a, b) -> a + " " + b);
        assertThat(allContent).contains("SKU-9912");
        assertThat(allContent).contains("Wireless Noise Cancelling Headphones");
    }
}
