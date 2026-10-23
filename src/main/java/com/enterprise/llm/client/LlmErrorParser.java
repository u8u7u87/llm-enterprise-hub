package com.enterprise.llm.client;

import com.enterprise.llm.exception.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpHeaders;
import java.util.Optional;

public class LlmErrorParser {

    private static final Logger log = LoggerFactory.getLogger(LlmErrorParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            return 1000L; // default 1s
        }
        Optional<String> retryMsOpt = headers.firstValue("retry-after-ms");
        if (retryMsOpt.isPresent()) {
            try {
                return Long.parseLong(retryMsOpt.get());
            } catch (NumberFormatException ignored) {}
        }
        Optional<String> retrySecOpt = headers.firstValue("retry-after");
        if (retrySecOpt.isPresent()) {
            try {
                return Long.parseLong(retrySecOpt.get()) * 1000L;
            } catch (NumberFormatException ignored) {}
        }
        return 1000L;
    }
}
