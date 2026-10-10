package com.enterprise.llm.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(
        String id,
        String type,
        FunctionCall function
) {
    public ToolCall {
        if (type == null) {
            type = "function";
        }
    }

    public ToolCall(String id, String functionName, Map<String, Object> arguments) {
        this(id, "function", new FunctionCall(functionName, arguments));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionCall(
            String name,
            Map<String, Object> arguments
    ) {
    }
}
