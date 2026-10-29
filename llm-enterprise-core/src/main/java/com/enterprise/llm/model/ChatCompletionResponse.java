package com.enterprise.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionResponse(
        String id,
        String object,
        Long created,
        String model,
        List<Choice> choices,
        Usage usage
) {
    /**
     * 复制本响应但替换 usage。
     *
     * <p>多轮 Agent 循环里最后一次上游响应只带单轮用量，需要把累计用量盖上去；
     * 这个转换在两处编排器里重复出现过，收敛到这里。
     */
    public ChatCompletionResponse withUsage(Usage usage) {
        return new ChatCompletionResponse(id, object, created, model, choices, usage);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(
            Integer index,
            ChatMessage message,
            String finishReason
    ) {
    }
}
