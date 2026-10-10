package com.enterprise.llm.router;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class FailoverRoutingService {

    private static final Logger log = LoggerFactory.getLogger(FailoverRoutingService.class);

    private final LlmClient primaryClient;
    private final LlmClient fallbackClient;
    private final String fallbackModel;

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final int circuitBreakerThreshold = 3;

    public FailoverRoutingService(
            @Qualifier("primaryLlmClient") LlmClient primaryClient,
            @Qualifier("fallbackLlmClient") LlmClient fallbackClient,
            @Value("${llm.fallback.model:qwen2.5:0.5b}") String fallbackModel
    ) {
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
        this.fallbackModel = fallbackModel;
    }

    public ChatCompletionResponse chatCompletionWithFailover(ChatCompletionRequest request) {
        if (isCircuitOpen()) {
            log.warn("Circuit Breaker OPEN: Bypassing primary and executing fallback directly");
            return executeFallbackSync(request);
        }

        try {
            ChatCompletionResponse response = primaryClient.chatCompletion(request);
            recordSuccess();
            return response;
        } catch (Exception primaryEx) {
            recordFailure();
            log.warn("Primary LLM call failed, attempting failover to local model {}: {}", fallbackModel, primaryEx.getMessage());
            return executeFallbackSync(request);
        }
    }

    public StreamCancellationHandle streamChatCompletionWithFailover(
            ChatCompletionRequest request,
            Consumer<ChatCompletionChunk> onChunk,
            Runnable onComplete,
            Consumer<Throwable> onError
    ) {
        final StreamCancellationHandle[] activeHandle = new StreamCancellationHandle[1];
        final AtomicBoolean firstChunkReceived = new AtomicBoolean(false);

        if (isCircuitOpen()) {
            log.warn("Circuit Breaker OPEN: Starting stream directly on fallback");
            return executeFallbackStream(request, onChunk, onComplete, onError);
        }

        activeHandle[0] = primaryClient.streamChatCompletion(
                request,
                chunk -> {
                    firstChunkReceived.set(true);
                    recordSuccess();
                    onChunk.accept(chunk);
                },
                onComplete,
                primaryError -> {
                    if (!firstChunkReceived.get()) {
                        recordFailure();
                        log.warn("Primary stream initiation failed, failing over to local Ollama {}: {}", fallbackModel, primaryError.getMessage());
                        activeHandle[0] = executeFallbackStream(request, onChunk, onComplete, onError);
                    } else {
                        onError.accept(primaryError);
                    }
                }
        );

        return new StreamCancellationHandle(() -> {
            if (activeHandle[0] != null) {
                activeHandle[0].cancel();
            }
        });
    }

    private ChatCompletionResponse executeFallbackSync(ChatCompletionRequest request) {
        ChatCompletionRequest fallbackRequest = new ChatCompletionRequest(
                fallbackModel,
                request.messages(),
                false,
                request.temperature()
        );
        return fallbackClient.chatCompletion(fallbackRequest);
    }

    private StreamCancellationHandle executeFallbackStream(
            ChatCompletionRequest request,
            Consumer<ChatCompletionChunk> onChunk,
            Runnable onComplete,
            Consumer<Throwable> onError
    ) {
        ChatCompletionRequest fallbackRequest = new ChatCompletionRequest(
                fallbackModel,
                request.messages(),
                true,
                request.temperature()
        );
        return fallbackClient.streamChatCompletion(fallbackRequest, onChunk, onComplete, onError);
    }

    private boolean isCircuitOpen() {
        return failureCount.get() >= circuitBreakerThreshold;
    }

    private void recordSuccess() {
        failureCount.set(0);
    }

    private void recordFailure() {
        failureCount.incrementAndGet();
    }
}
