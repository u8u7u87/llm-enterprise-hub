package com.enterprise.llm.agent;

import com.enterprise.llm.model.ChatCompletionChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamChunkAccumulatorTest {

    @Test
    @DisplayName("Verify accumulator handles regular text stream chunks")
    void testAccumulateText() {
        StreamChunkAccumulator accumulator = new StreamChunkAccumulator();

        accumulator.append(new ChatCompletionChunk(
                "chunk-1", "chat.completion.chunk", 1000L, "gpt-4",
                List.of(new ChatCompletionChunk.ChoiceChunk(0, new ChatCompletionChunk.Delta("assistant", "Hello "), null)), null
        ));
        accumulator.append(new ChatCompletionChunk(
                "chunk-2", "chat.completion.chunk", 1001L, "gpt-4",
                List.of(new ChatCompletionChunk.ChoiceChunk(0, new ChatCompletionChunk.Delta("assistant", "World!"), "stop")), null
        ));

        assertThat(accumulator.hasToolCalls()).isFalse();
        assertThat(accumulator.getAccumulatedContent()).isEqualTo("Hello World!");
        assertThat(accumulator.getFinishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("Verify accumulator concatenates partial tool call chunks correctly")
    void testAccumulateToolCalls() {
        StreamChunkAccumulator accumulator = new StreamChunkAccumulator();

        // First chunk with tool call id and function name
        accumulator.append(new ChatCompletionChunk(
                "chunk-1", "chat.completion.chunk", 1000L, "gpt-4",
                List.of(new ChatCompletionChunk.ChoiceChunk(
                        0,
                        new ChatCompletionChunk.Delta("assistant", null, List.of(
                                new ChatCompletionChunk.DeltaToolCall(0, "call_999", "function",
                                        new ChatCompletionChunk.DeltaToolCall.DeltaFunctionCall("get_weather", "{\"city\":"))
                        )),
                        null
                )), null
        ));

        // Second chunk streaming remaining arguments
        accumulator.append(new ChatCompletionChunk(
                "chunk-2", "chat.completion.chunk", 1001L, "gpt-4",
                List.of(new ChatCompletionChunk.ChoiceChunk(
                        0,
                        new ChatCompletionChunk.Delta(null, null, List.of(
                                new ChatCompletionChunk.DeltaToolCall(0, null, null,
                                        new ChatCompletionChunk.DeltaToolCall.DeltaFunctionCall(null, " \"Beijing\"}"))
                        )),
                        "tool_calls"
                )), null
        ));

        assertThat(accumulator.hasToolCalls()).isTrue();
        assertThat(accumulator.getFinishReason()).isEqualTo("tool_calls");

        List<ToolCall> toolCalls = accumulator.getAccumulatedToolCalls();
        assertThat(toolCalls).hasSize(1);
        ToolCall tc = toolCalls.get(0);
        assertThat(tc.id()).isEqualTo("call_999");
        assertThat(tc.function().name()).isEqualTo("get_weather");
        assertThat(tc.function().arguments()).containsEntry("city", "Beijing");
    }
}
