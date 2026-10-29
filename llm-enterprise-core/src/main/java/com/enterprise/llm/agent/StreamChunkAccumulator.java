package com.enterprise.llm.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accumulates streaming chunks from an SSE completion to reconstruct complete
 * assistant message content and tool calls.
 */
public class StreamChunkAccumulator {

    private static final Logger log = LoggerFactory.getLogger(StreamChunkAccumulator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StringBuilder textAccumulator = new StringBuilder();
    private final Map<Integer, PartialToolCall> toolCallBuilders = new TreeMap<>();
    private volatile String finishReason;

    public void append(ChatCompletionChunk chunk) {
        if (chunk == null || chunk.choices() == null) {
            return;
        }

        for (ChatCompletionChunk.ChoiceChunk choice : chunk.choices()) {
            if (choice.finishReason() != null) {
                this.finishReason = choice.finishReason();
            }

            ChatCompletionChunk.Delta delta = choice.delta();
            if (delta == null) {
                continue;
            }

            if (delta.content() != null) {
                textAccumulator.append(delta.content());
            }

            if (delta.toolCalls() != null) {
                for (ChatCompletionChunk.DeltaToolCall dt : delta.toolCalls()) {
                    int idx = dt.index() != null ? dt.index() : 0;
                    PartialToolCall pt = toolCallBuilders.computeIfAbsent(idx, PartialToolCall::new);

                    if (dt.id() != null && !dt.id().isBlank()) {
                        pt.id = dt.id();
                    }
                    if (dt.type() != null) {
                        pt.type = dt.type();
                    }
                    if (dt.function() != null) {
                        if (dt.function().name() != null) {
                            pt.name = dt.function().name();
                        }
                        if (dt.function().arguments() != null) {
                            pt.argumentsBuilder.append(dt.function().arguments());
                        }
                    }
                }
            }
        }
    }

    public boolean hasToolCalls() {
        return !toolCallBuilders.isEmpty();
    }

    public String getAccumulatedContent() {
        return textAccumulator.toString();
    }

    public String getFinishReason() {
        return finishReason;
    }

    public List<ToolCall> getAccumulatedToolCalls() {
        List<ToolCall> result = new ArrayList<>();
        for (PartialToolCall pt : toolCallBuilders.values()) {
            Map<String, Object> parsedArgs = parseArguments(pt.argumentsBuilder.toString());
            result.add(new ToolCall(
                    pt.id != null ? pt.id : UUID.randomUUID().toString(),
                    pt.type != null ? pt.type : "function",
                    new ToolCall.FunctionCall(pt.name != null ? pt.name : "unknown", parsedArgs)
            ));
        }
        return result;
    }

    private Map<String, Object> parseArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse accumulated tool call arguments JSON: '{}', error: {}", raw, e.getMessage());
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("raw", raw);
            return fallback;
        }
    }

    private static class PartialToolCall {
        final int index;
        String id;
        String type = "function";
        String name;
        final StringBuilder argumentsBuilder = new StringBuilder();

        PartialToolCall(int index) {
            this.index = index;
        }
    }
}
