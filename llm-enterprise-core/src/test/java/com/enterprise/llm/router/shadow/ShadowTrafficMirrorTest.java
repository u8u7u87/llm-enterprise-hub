package com.enterprise.llm.router.shadow;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShadowTrafficMirrorTest {

    @Mock
    private LlmClient shadowClient;

    private ShadowTrafficMirror mirror;

    @BeforeEach
    void setUp() {
        mirror = new ShadowTrafficMirror();
    }

    @Test
    @DisplayName("Should asynchronously mirror request to shadow model and record comparison")
    void shouldMirrorRequestAsyncAndCompare() throws InterruptedException {
        // Register shadow rule: gpt-4o -> deepseek-r1 (100% sampling)
        mirror.setRule(new ShadowTrafficRule("gpt-4o", "deepseek-r1", 1.0));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o",
                List.of(ChatMessage.user("Explain quantum computing")),
                false, 0.7
        );

        ChatCompletionResponse primaryResponse = new ChatCompletionResponse(
                "resp-1", "chat.completion", 1000L, "gpt-4o",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Primary response text"), "stop")),
                new Usage(10, 20, 30)
        );

        ChatCompletionResponse shadowResponse = new ChatCompletionResponse(
                "resp-shadow", "chat.completion", 1000L, "deepseek-r1",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Shadow candidate response"), "stop")),
                new Usage(10, 25, 35)
        );

        when(shadowClient.chatCompletion(argThat(req -> "deepseek-r1".equals(req.model()))))
                .thenReturn(shadowResponse);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ShadowComparisonRecord> capturedRecord = new AtomicReference<>();
        mirror.setComparisonListener(record -> {
            capturedRecord.set(record);
            latch.countDown();
        });

        mirror.maybeMirrorAsync(request, primaryResponse, 50, "tenant-test", "trace-123", shadowClient);

        boolean completed = latch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        ShadowComparisonRecord record = capturedRecord.get();
        assertThat(record).isNotNull();
        assertThat(record.sourceModel()).isEqualTo("gpt-4o");
        assertThat(record.shadowModel()).isEqualTo("deepseek-r1");
        assertThat(record.primaryTokens()).isEqualTo(30);
        assertThat(record.shadowTokens()).isEqualTo(35);
        assertThat(record.primaryOutputSnippet()).contains("Primary response");
        assertThat(record.shadowOutputSnippet()).contains("Shadow candidate");
        assertThat(record.shadowSuccess()).isTrue();
    }

    @Test
    @DisplayName("Shadow failure should be isolated and recorded without throwing exceptions")
    void shouldIsolateShadowFailures() throws InterruptedException {
        mirror.setRule(new ShadowTrafficRule("gpt-4o", "broken-model", 1.0));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o", List.of(ChatMessage.user("Hello")), false, 0.7
        );
        ChatCompletionResponse primaryResponse = new ChatCompletionResponse(
                "resp-1", "chat.completion", 1000L, "gpt-4o",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("OK"), "stop")),
                new Usage(5, 5, 10)
        );

        when(shadowClient.chatCompletion(any()))
                .thenThrow(new RuntimeException("Shadow model 500 internal server error"));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ShadowComparisonRecord> capturedRecord = new AtomicReference<>();
        mirror.setComparisonListener(record -> {
            capturedRecord.set(record);
            latch.countDown();
        });

        // Must not throw exception
        mirror.maybeMirrorAsync(request, primaryResponse, 20, "tenant-1", "trace-err", shadowClient);

        boolean completed = latch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        ShadowComparisonRecord record = capturedRecord.get();
        assertThat(record).isNotNull();
        assertThat(record.shadowSuccess()).isFalse();
        assertThat(record.shadowErrorMessage()).contains("500 internal server error");
    }

    @Test
    @DisplayName("Should skip mirroring when no rule matches or sampling ratio is 0")
    void shouldSkipWhenNoRuleOrZeroSampling() {
        mirror.setRule(new ShadowTrafficRule("gpt-4o", "shadow-model", 0.0));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o", List.of(ChatMessage.user("Hello")), false, 0.7
        );
        ChatCompletionResponse response = new ChatCompletionResponse(
                "resp-1", "chat.completion", 1000L, "gpt-4o", List.of(), new Usage(0, 0, 0)
        );

        mirror.maybeMirrorAsync(request, response, 10, "tenant-1", "trace-skip", shadowClient);
        verifyNoInteractions(shadowClient);
    }
}
