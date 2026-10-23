package com.enterprise.llm.memory;

import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingMemoryManagerTest {

    private WorkingMemoryManager memoryManager;

    @BeforeEach
    void setUp() {
        memoryManager = new WorkingMemoryManager();
    }

    @Test
    @DisplayName("Should offload raw payload and retrieve by memoryId")
    void testOffloadAndGet() {
        String sessionId = "sess-test-wm";
        String rawContent = "{\"orders\": [{\"id\": 9901, \"item\": \"MacBook Pro\", \"amount\": 2499}]}";
        String stub = memoryManager.offload(sessionId, rawContent, "tool", "MacBook order record");

        assertThat(stub).startsWith("[WorkingMemoryRef: id=\"mem-");
        assertThat(stub).contains("summary=\"MacBook order record\"");

        String memId = stub.substring(stub.indexOf("id=\"") + 4, stub.indexOf("\" summary="));
        Optional<WorkingMemoryItem> item = memoryManager.get(sessionId, memId);
        assertThat(item).isPresent();
        assertThat(item.get().rawContent()).isEqualTo(rawContent);
        assertThat(item.get().role()).isEqualTo("tool");
    }

    @Test
    @DisplayName("Should compute relevance via lexical token matching when embedding client is absent")
    void testComputeRelevanceLexical() {
        String sessionId = "sess-test-wm";
        String rawContent = "Server database query result: customer 4001 has overdue balance 350 USD";
        String stub = memoryManager.offload(sessionId, rawContent, "tool", "Customer 4001 overdue balance");

        String memId = stub.substring(stub.indexOf("id=\"") + 4, stub.indexOf("\" summary="));
        WorkingMemoryItem item = memoryManager.get(sessionId, memId).orElseThrow();

        // High relevance: mentions customer and balance
        double scoreRelevant = memoryManager.computeRelevance("What is the overdue balance for customer 4001?", item);
        assertThat(scoreRelevant).isGreaterThanOrEqualTo(0.5);

        // Low relevance: completely unrelated topic
        double scoreIrrelevant = memoryManager.computeRelevance("How is the weather in Tokyo tomorrow?", item);
        assertThat(scoreIrrelevant).isLessThan(0.1);
    }

    @Test
    @DisplayName("Should rehydrate [WorkingMemoryRef: ...] stub when user query is semantically relevant")
    void testRecallAndRehydrate() {
        String sessionId = "sess-test-wm";
        String rawOrders = "Order #1001: 2x iPhone 16 Pro Max, Total $2398; Order #1002: 1x iPad Air, Total $599";
        String stub = memoryManager.offload(sessionId, rawOrders, "tool", "Order listing for customer Alice");

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(
                        ChatMessage.user("Show me my recent orders"),
                        ChatMessage.tool("call_1", stub),
                        ChatMessage.assistant("You have 2 recent orders: an iPhone 16 Pro Max and an iPad Air."),
                        ChatMessage.user("How much did the iPhone order cost in my recent orders?")
                ),
                false,
                0.7,
                100,
                null,
                null,
                sessionId,
                "user-alice"
        );

        ChatCompletionRequest rehydrated = memoryManager.recallAndRehydrate(request, 0.2);

        // Verify the tool message stub was replaced with rawOrders
        assertThat(rehydrated.messages().get(1).content()).isEqualTo(rawOrders);
    }

    @Test
    @DisplayName("Should dehydrate oversized tool outputs in request messages")
    void testDehydrateOversizedMessages() {
        String sessionId = "sess-test-wm";
        StringBuilder largeData = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            largeData.append("Row ").append(i).append(": User log audit telemetry payload data;\n");
        }

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(
                        ChatMessage.user("Fetch audit logs"),
                        ChatMessage.tool("call_audit_1", largeData.toString()),
                        ChatMessage.assistant("Logs fetched."),
                        ChatMessage.user("Summarize errors")
                ),
                false,
                0.7,
                100,
                null,
                null,
                sessionId,
                "user-admin"
        );

        ChatCompletionRequest dehydrated = memoryManager.dehydrate(request, 200);

        ChatMessage toolMsg = dehydrated.messages().get(1);
        assertThat(toolMsg.content()).startsWith("[WorkingMemoryRef: id=\"mem-");
        assertThat(toolMsg.content().length()).isLessThan(200);
    }
}
