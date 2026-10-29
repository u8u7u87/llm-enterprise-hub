package com.enterprise.llm.client;

import java.net.http.HttpHeaders;
import java.util.Optional;
import com.enterprise.llm.exception.LlmAuthenticationException;
import com.enterprise.llm.exception.LlmContentFilterException;
import com.enterprise.llm.exception.LlmContextLengthExceededException;
import com.enterprise.llm.exception.LlmException;
import com.enterprise.llm.exception.LlmInvalidRequestException;
import com.enterprise.llm.exception.LlmRateLimitException;
import com.enterprise.llm.exception.LlmServerException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LlmErrorParser {

    private static final Logger log = LoggerFactory.getLogger(LlmErrorParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 上游未给出可用退避提示时的默认重试间隔。 */
    private static final long DEFAULT_RETRY_AFTER_MS = 1000L;

    public static LlmException parse(int statusCode, String responseBody, HttpHeaders headers) {
        String message = "Upstream error HTTP " + statusCode;
        String errorCode = null;
        String errorType = null;

        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode root = MAPPER.readTree(responseBody);
                if (root.has("error")) {
                    JsonNode errorNode = root.get("error");
                    if (errorNode.isObject()) {
                        if (errorNode.has("message")) {
                            message = errorNode.get("message").asText();
                        }
                        if (errorNode.has("code") && !errorNode.get("code").isNull()) {
                            errorCode = errorNode.get("code").asText();
                        }
                        if (errorNode.has("type") && !errorNode.get("type").isNull()) {
                            errorType = errorNode.get("type").asText();
                        }
                    } else if (errorNode.isTextual()) {
                        message = errorNode.asText();
                    }
                } else if (root.has("message")) {
                    message = root.get("message").asText();
                }
            } catch (Exception parseEx) {
                log.debug("Failed to parse JSON error body: {}", responseBody);
                message = "HTTP " + statusCode + " - " + responseBody;
            }
        }

        // 1. Context length overflow detection
        String lowerMsg = message.toLowerCase();
        if ("context_length_exceeded".equalsIgnoreCase(errorCode)
                || lowerMsg.contains("context length")
                || lowerMsg.contains("maximum context length")
                || lowerMsg.contains("token limit")
                || lowerMsg.contains("tokens. however, your messages resulted in")) {
            return new LlmContextLengthExceededException(message, errorCode != null ? errorCode : "context_length_exceeded");
        }

        // 2. Content filter detection
        if ("content_filter".equalsIgnoreCase(errorCode)
                || lowerMsg.contains("content management policy")
                || lowerMsg.contains("safety policy")
                || lowerMsg.contains("responsible ai")) {
            return new LlmContentFilterException(message, errorCode != null ? errorCode : "content_filter");
        }

        // 3. HTTP status-based categorization
        if (statusCode == 429) {
            long retryAfterMs = parseRetryAfter(headers);
            return new LlmRateLimitException(message, errorCode, retryAfterMs);
        }

        if (statusCode == 401 || statusCode == 403) {
            return new LlmAuthenticationException(message, statusCode, errorCode);
        }

        if (statusCode >= 500) {
            return new LlmServerException(message, statusCode, errorCode);
        }

        return new LlmInvalidRequestException(message, statusCode, errorCode);
    }

    private static long parseRetryAfter(HttpHeaders headers) {
        if (headers == null) {
            return DEFAULT_RETRY_AFTER_MS;
        }
        // 毫秒级头优先，其次秒级头，都不可用时回落到默认退避
        Optional<Long> millis = parseNumericHeader(headers.firstValue("retry-after-ms"));
        if (millis.isPresent()) {
            return millis.get();
        }
        Optional<Long> seconds = parseNumericHeader(headers.firstValue("retry-after"));
        if (seconds.isPresent()) {
            return seconds.get() * 1000L;
        }
        return DEFAULT_RETRY_AFTER_MS;
    }

    /**
     * 解析数值型响应头。
     *
     * <p>上游对 {@code retry-after} 的实现并不统一（有的返回 HTTP-date，有的返回空串），
     * 解析失败一律按"该头不存在"处理，由调用方继续尝试下一级兜底，而不是中断错误解析流程。
     */
    private static Optional<Long> parseNumericHeader(Optional<String> rawValue) {
        if (rawValue.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(rawValue.get()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
