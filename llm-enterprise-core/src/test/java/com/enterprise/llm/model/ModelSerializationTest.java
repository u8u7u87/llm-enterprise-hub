package com.enterprise.llm.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeChatCompletionRequestCorrectly() throws Exception {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o",
                List.of(
                        new ChatMessage("system", "You are an AI assistant."),
                        new ChatMessage("user", "Hello world")
                ),
                true,
                0.7
        );

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"model\":\"gpt-4o\"");
        assertThat(json).contains("\"stream\":true");
        assertThat(json).contains("\"temperature\":0.7");
        assertThat(json).contains("\"role\":\"system\"");
        assertThat(json).contains("\"content\":\"Hello world\"");
    }

    @Test
    void shouldDeserializeChatCompletionChunkCorrectly() throws Exception {
        String chunkJson = """
                {
                  "id": "chatcmpl-123",
                  "object": "chat.completion.chunk",
                  "created": 1728550000,
                  "model": "gpt-4o",
                  "choices": [
                    {
                      "index": 0,
                      "delta": {
                        "role": "assistant",
                        "content": "Hello"
                      },
                      "finish_reason": null
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 1,
                    "total_tokens": 11
                  }
                }
                """;

        ChatCompletionChunk chunk = objectMapper.readValue(chunkJson, ChatCompletionChunk.class);

        assertThat(chunk.id()).isEqualTo("chatcmpl-123");
        assertThat(chunk.model()).isEqualTo("gpt-4o");
        assertThat(chunk.choices()).hasSize(1);
        assertThat(chunk.choices().get(0).delta().role()).isEqualTo("assistant");
        assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hello");
        assertThat(chunk.choices().get(0).finishReason()).isNull();
        assertThat(chunk.usage()).isNotNull();
        assertThat(chunk.usage().promptTokens()).isEqualTo(10);
        assertThat(chunk.usage().completionTokens()).isEqualTo(1);
        assertThat(chunk.usage().totalTokens()).isEqualTo(11);
    }

    @Test
    void shouldDeserializeChatCompletionResponseCorrectly() throws Exception {
        String responseJson = """
                {
                  "id": "chatcmpl-999",
                  "object": "chat.completion",
                  "created": 1728550000,
                  "model": "gpt-4o",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Complete reply"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 20,
                    "completion_tokens": 30,
                    "total_tokens": 50
                  }
                }
                """;

        ChatCompletionResponse response = objectMapper.readValue(responseJson, ChatCompletionResponse.class);

        assertThat(response.id()).isEqualTo("chatcmpl-999");
        assertThat(response.model()).isEqualTo("gpt-4o");
        assertThat(response.choices()).hasSize(1);
        assertThat(response.choices().get(0).message().content()).isEqualTo("Complete reply");
        assertThat(response.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(response.usage().totalTokens()).isEqualTo(50);
    }
}
