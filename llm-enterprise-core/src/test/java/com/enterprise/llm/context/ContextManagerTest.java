package com.enterprise.llm.context;

import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.token.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextManagerTest {

    @Test
    @DisplayName("Should leave request untouched when within budget")
    void shouldNotCompressWhenWithinBudget() {
        ContextBudgetProperties props = new ContextBudgetProperties();
        props.setMaxContextTokens(8192);
        ContextManager manager = new ContextManager(props, new TokenEstimator(), new HeuristicContextDistiller());

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(
                        ChatMessage.system("You are a helpful assistant."),
                        ChatMessage.user("Hello, what is 2+2?")
                ),
                false, 0.7
        );

        ChatCompletionRequest result = manager.manageContext(request);
        assertThat(result.messages()).hasSize(2);
        assertThat(result.messages().get(0).content()).isEqualTo("You are a helpful assistant.");
    }

    @Test
    @DisplayName("Should prune old voluminous tool outputs when exceeding threshold")
    void shouldPruneOldToolMessages() {
        ContextBudgetProperties props = new ContextBudgetProperties();
        props.setMaxContextTokens(100); // Low threshold to force pruning
        props.setReservedResponseTokens(10);
        props.setCompressionTriggerRatio(0.5);
        props.setToolPruningThresholdChars(100);

        ContextManager manager = new ContextManager(props, new TokenEstimator(), new HeuristicContextDistiller());

        String hugeJson = "{\"data\":\"" + "A".repeat(300) + "\"}";
        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(
                        ChatMessage.system("System instruction"),
                        ChatMessage.user("Fetch data"),
                        ChatMessage.assistant("Calling tool"),
                        ChatMessage.tool("call_1", hugeJson), // Old tool output (index 3 out of 6)
                        ChatMessage.user("Next question"),
                        ChatMessage.assistant("Latest answer")
                ),
                false, 0.7
        );

        ChatCompletionRequest result = manager.manageContext(request);
        // The old tool call should be pruned
        boolean toolPruned = result.messages().stream()
                .anyMatch(m -> "tool".equals(m.role()) && m.content().contains("Earlier tool output pruned"));
        assertThat(toolPruned).isTrue();
    }

    @Test
    @DisplayName("Should apply sliding window, retain System message and generate summary distillation")
    void shouldSlideWindowAndDistillHistory() {
        ContextBudgetProperties props = new ContextBudgetProperties();
        props.setMaxContextTokens(80); // Strict budget
        props.setReservedResponseTokens(10);
        props.setCompressionTriggerRatio(0.5);
        props.setKeepLastMessages(2);

        ContextManager manager = new ContextManager(props, new TokenEstimator(), new HeuristicContextDistiller());

        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system("Preserved system instructions."));
        for (int i = 1; i <= 8; i++) {
            conversation.add(ChatMessage.user("User question number " + i + " with some detailed words"));
            conversation.add(ChatMessage.assistant("Assistant answer number " + i + " providing answers"));
        }

        ChatCompletionRequest request = new ChatCompletionRequest("qwen2.5:0.5b", conversation, false, 0.7);

        ChatCompletionRequest result = manager.manageContext(request);

        // 1. System prompt preserved
        assertThat(result.messages().get(0).content()).isEqualTo("Preserved system instructions.");

        // 2. Contains distillation summary message
        boolean hasSummary = result.messages().stream()
                .anyMatch(m -> m.content().contains("【前序会话历史提炼摘要与背景备忘】"));
        assertThat(hasSummary).isTrue();

        // 3. Retains the latest messages
        ChatMessage lastMessage = result.messages().get(result.messages().size() - 1);
        assertThat(lastMessage.content()).contains("Assistant answer number 8");
    }

    @Test
    @DisplayName("Emergency compaction should aggressively halve context and inject summary")
    void shouldPerformEmergencyCompaction() {
        ContextBudgetProperties props = new ContextBudgetProperties();
        props.setKeepLastMessages(2);
        ContextManager manager = new ContextManager(props, new TokenEstimator(), new HeuristicContextDistiller());

        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system("Core system role."));
        for (int i = 1; i <= 6; i++) {
            conversation.add(ChatMessage.user("Query " + i));
            conversation.add(ChatMessage.assistant("Reply " + i));
        }

        ChatCompletionRequest request = new ChatCompletionRequest("qwen2.5:0.5b", conversation, false, 0.7);

        ChatCompletionRequest emergency = manager.forceEmergencyCompaction(request);
        assertThat(emergency.messages().size()).isLessThan(conversation.size());
        assertThat(emergency.messages().get(0).content()).isEqualTo("Core system role.");
        assertThat(emergency.messages().stream().anyMatch(m -> m.content().contains("【前序会话历史提炼摘要与背景备忘】"))).isTrue();
    }
}
