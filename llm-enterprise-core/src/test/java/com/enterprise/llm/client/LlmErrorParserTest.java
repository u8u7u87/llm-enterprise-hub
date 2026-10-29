package com.enterprise.llm.client;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import com.enterprise.llm.exception.LlmAuthenticationException;
import com.enterprise.llm.exception.LlmContentFilterException;
import com.enterprise.llm.exception.LlmContextLengthExceededException;
import com.enterprise.llm.exception.LlmException;
import com.enterprise.llm.exception.LlmRateLimitException;
import com.enterprise.llm.exception.LlmServerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LlmErrorParserTest {

    @Test
    @DisplayName("Should parse 429 rate limit with Retry-After header")
    void shouldParseRateLimitException() {
        String json = """
                {
                  "error": {
                    "message": "Rate limit exceeded: please slow down",
                    "type": "rate_limit_error",
                    "code": "rate_limit_exceeded"
                  }
                }
                """;
        HttpHeaders headers = HttpHeaders.of(Map.of("retry-after", List.of("5")), (k, v) -> true);

        LlmException ex = LlmErrorParser.parse(429, json, headers);
        assertThat(ex).isInstanceOf(LlmRateLimitException.class);
        LlmRateLimitException rateEx = (LlmRateLimitException) ex;
        assertThat(rateEx.getStatusCode()).isEqualTo(429);
        assertThat(rateEx.getErrorCode()).isEqualTo("rate_limit_exceeded");
        assertThat(rateEx.getRetryAfterMs()).isEqualTo(5000L);
        assertThat(rateEx.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("Should parse 400 context_length_exceeded error")
    void shouldParseContextLengthExceeded() {
        String json = """
                {
                  "error": {
                    "message": "This model's maximum context length is 8192 tokens. However, your messages resulted in 8540 tokens.",
                    "type": "invalid_request_error",
                    "code": "context_length_exceeded"
                  }
                }
                """;

        LlmException ex = LlmErrorParser.parse(400, json, null);
        assertThat(ex).isInstanceOf(LlmContextLengthExceededException.class);
        assertThat(ex.getStatusCode()).isEqualTo(400);
        assertThat(ex.getErrorCode()).isEqualTo("context_length_exceeded");
        assertThat(ex.getMessage()).contains("8192 tokens");
    }

    @Test
    @DisplayName("Should parse 400 content_filter violation")
    void shouldParseContentFilter() {
        String json = """
                {
                  "error": {
                    "message": "The response was filtered due to the prompt triggering Azure OpenAI's content management policy.",
                    "type": "invalid_request_error",
                    "code": "content_filter"
                  }
                }
                """;

        LlmException ex = LlmErrorParser.parse(400, json, null);
        assertThat(ex).isInstanceOf(LlmContentFilterException.class);
        assertThat(ex.getStatusCode()).isEqualTo(400);
        assertThat(ex.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("Should parse 401 authentication failure")
    void shouldParseAuthenticationFailure() {
        String json = """
                {
                  "error": {
                    "message": "Incorrect API key provided: sk-invalid",
                    "type": "invalid_request_error",
                    "code": "invalid_api_key"
                  }
                }
                """;

        LlmException ex = LlmErrorParser.parse(401, json, null);
        assertThat(ex).isInstanceOf(LlmAuthenticationException.class);
        assertThat(ex.getStatusCode()).isEqualTo(401);
        assertThat(ex.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("Should parse 503 server error as retryable")
    void shouldParseServerException() {
        String json = """
                {
                  "error": {
                    "message": "The server had an error processing your request.",
                    "type": "server_error"
                  }
                }
                """;

        LlmException ex = LlmErrorParser.parse(503, json, null);
        assertThat(ex).isInstanceOf(LlmServerException.class);
        assertThat(ex.getStatusCode()).isEqualTo(503);
        assertThat(ex.isRetryable()).isTrue();
    }
}
