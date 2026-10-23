package com.enterprise.llm.token;

import com.enterprise.llm.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private TokenEstimator tokenEstimator;

    @BeforeEach
    void setUp() {
        tokenEstimator = new TokenEstimator();
    }

    @Test
    void shouldEstimateTokensForSimpleText() {
        String text = "Hello world! This is a test prompt.";
        int count = tokenEstimator.countTokens(text);
        assertThat(count).isGreaterThan(0);
        assertThat(count).isLessThan(20);
    }

    @Test
    void shouldReturnZeroForNullOrEmptyText() {
        assertThat(tokenEstimator.countTokens(null)).isEqualTo(0);
        assertThat(tokenEstimator.countTokens("")).isEqualTo(0);
        assertThat(tokenEstimator.countTokens("   ")).isEqualTo(0);
    }

    @Test
    void shouldEstimateTokensForListOfMessages() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", "You are a helpful AI assistant."),
                new ChatMessage("user", "What is the capital of France?")
        );

        int count = tokenEstimator.estimatePromptTokens(messages);
        assertThat(count).isGreaterThan(10);
    }

    @Test
    void shouldEstimateTotalTokensWithMaxTokens() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("user", "Hello")
        );

        int estimatedTotal = tokenEstimator.estimateTotalReservationTokens(messages, 256);
        assertThat(estimatedTotal).isGreaterThanOrEqualTo(257);
    }

    @Test
    void shouldUseDefaultCompletionEstimateWhenMaxTokensIsNull() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("user", "Hello")
        );

        int estimatedTotal = tokenEstimator.estimateTotalReservationTokens(messages, null);
        // Default estimate is 512
        assertThat(estimatedTotal).isGreaterThanOrEqualTo(513);
    }
}
