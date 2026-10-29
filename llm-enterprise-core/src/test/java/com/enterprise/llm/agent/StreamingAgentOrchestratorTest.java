package com.enterprise.llm.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.guard.ToolActionGatekeeper;
import com.enterprise.llm.model.AgentStreamEvent;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StreamingAgentOrchestratorTest {

    private ToolRegistry toolRegistry;
    private AgentToolOrchestrator agentToolOrchestrator;
    private StreamingAgentOrchestrator streamingAgentOrchestrator;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        toolRegistry.registerTool(
                new ToolDefinition("get_weather", "Get weather for a city", Collections.emptyMap()),
                args -> "Sunny, 24°C"
        );
        agentToolOrchestrator = new AgentToolOrchestrator(toolRegistry, new ToolActionGatekeeper());
        streamingAgentOrchestrator = new StreamingAgentOrchestrator(agentToolOrchestrator);
    }

    @Test
    @DisplayName("Verify streaming without tool calls passes chunks directly to onChunk")
    void testStreamingWithoutToolCalls() throws InterruptedException {
        List<String> receivedChunks = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        LlmClient mockClient = new LlmClient() {
            @Override
            public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
                return null;
            }

            @Override
            public StreamCancellationHandle streamChatCompletion(
                    ChatCompletionRequest request,
                    Consumer<ChatCompletionChunk> onChunk,
                    Runnable onComplete,
                    Consumer<Throwable> onError
            ) {
                onChunk.accept(new ChatCompletionChunk(
                        "c-1", "chat.completion.chunk", 1000L, "gpt-4",
                        List.of(new ChatCompletionChunk.ChoiceChunk(0, new ChatCompletionChunk.Delta("assistant", "Hello!"), null)), null
                ));
                onChunk.accept(new ChatCompletionChunk(
                        "c-2", "chat.completion.chunk", 1001L, "gpt-4",
                        List.of(new ChatCompletionChunk.ChoiceChunk(0, new ChatCompletionChunk.Delta("assistant", " How can I help?"), "stop")), null
                ));
                onComplete.run();
                return new StreamCancellationHandle(() -> {});
            }
        };

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4", List.of(ChatMessage.user("Hi")), true, 0.7);

        streamingAgentOrchestrator.runStreamingLoop(
                mockClient,
                request,
                "tenant-1",
                3,
                chunk -> {
                    if (chunk.choices() != null && !chunk.choices().isEmpty() && chunk.choices().get(0).delta() != null) {
                        receivedChunks.add(chunk.choices().get(0).delta().content());
                    }
                },
                null,
                () -> {
                    completed.set(true);
                    latch.countDown();
                },
                ex -> latch.countDown()
        );

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(completed.get()).isTrue();
        assertThat(receivedChunks).containsExactly("Hello!", " How can I help?");
    }

    @Test
    @DisplayName("Verify streaming with tool call executes tool, emits lifecycle events, and streams follow-up response")
    void testStreamingWithToolCallLoop() throws InterruptedException {
        List<AgentStreamEvent> events = new ArrayList<>();
        List<String> finalChunks = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);

        LlmClient mockClient = new LlmClient() {
            private int turn = 0;

            @Override
            public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
                return null;
            }

            @Override
            public StreamCancellationHandle streamChatCompletion(
                    ChatCompletionRequest request,
                    Consumer<ChatCompletionChunk> onChunk,
                    Runnable onComplete,
                    Consumer<Throwable> onError
            ) {
                turn++;
                if (turn == 1) {
                    // Turn 1: Emit tool_calls delta
                    onChunk.accept(new ChatCompletionChunk(
                            "c-1", "chat.completion.chunk", 1000L, "gpt-4",
                            List.of(new ChatCompletionChunk.ChoiceChunk(
                                    0,
                                    new ChatCompletionChunk.Delta("assistant", null, List.of(
                                            new ChatCompletionChunk.DeltaToolCall(0, "call_weather_1", "function",
                                                    new ChatCompletionChunk.DeltaToolCall.DeltaFunctionCall("get_weather", "{\"city\":\"Beijing\"}"))
                                    )),
                                    "tool_calls"
                            )), null
                    ));
                    onComplete.run();
                } else {
                    // Turn 2: Emit final text answer
                    onChunk.accept(new ChatCompletionChunk(
                            "c-2", "chat.completion.chunk", 1001L, "gpt-4",
                            List.of(new ChatCompletionChunk.ChoiceChunk(0, new ChatCompletionChunk.Delta("assistant", "The weather in Beijing is Sunny, 24°C."), "stop")), null
                    ));
                    onComplete.run();
                }
                return new StreamCancellationHandle(() -> {});
            }
        };

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4", List.of(ChatMessage.user("Weather in Beijing?")), true, 0.7);

        streamingAgentOrchestrator.runStreamingLoop(
                mockClient,
                request,
                "tenant-1",
                3,
                chunk -> {
                    if (chunk.choices() != null && !chunk.choices().isEmpty() && chunk.choices().get(0).delta() != null) {
                        String content = chunk.choices().get(0).delta().content();
                        if (content != null) {
                            finalChunks.add(content);
                        }
                    }
                },
                events::add,
                () -> {
                    completed.set(true);
                    latch.countDown();
                },
                ex -> latch.countDown()
        );

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(completed.get()).isTrue();

        // Verify lifecycle events
        assertThat(events).hasSize(2);
        assertThat(events.get(0).eventType()).isEqualTo(AgentStreamEvent.EVENT_TOOL_CALL_START);
        assertThat(events.get(0).toolName()).isEqualTo("get_weather");
        assertThat(events.get(0).toolCallId()).isEqualTo("call_weather_1");

        assertThat(events.get(1).eventType()).isEqualTo(AgentStreamEvent.EVENT_TOOL_CALL_RESULT);
        assertThat(events.get(1).toolName()).isEqualTo("get_weather");
        assertThat(events.get(1).result()).isEqualTo("Sunny, 24°C");

        // Verify final chunk received
        assertThat(finalChunks).containsExactly("The weather in Beijing is Sunny, 24°C.");
    }
}
