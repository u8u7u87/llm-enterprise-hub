package com.enterprise.llm.model;

import com.enterprise.llm.agent.ToolDefinition;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Boolean stream,
        Double temperature,
        Integer maxTokens,
        List<ToolDefinition> tools,
        Object toolChoice
) {
    public ChatCompletionRequest {
        if (stream == null) {
            stream = false;
        }
    }

    public ChatCompletionRequest(String model, List<ChatMessage> messages, Boolean stream, Double temperature) {
        this(model, messages, stream, temperature, null, null, null);
    }

    public ChatCompletionRequest(String model, List<ChatMessage> messages, Boolean stream, Double temperature, Integer maxTokens) {
        this(model, messages, stream, temperature, maxTokens, null, null);
    }
}

