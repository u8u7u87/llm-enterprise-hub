package com.enterprise.llm.router;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.context.ContextManager;
import com.enterprise.llm.exception.LlmAuthenticationException;
import com.enterprise.llm.exception.LlmContentFilterException;
import com.enterprise.llm.exception.LlmContextLengthExceededException;
import com.enterprise.llm.exception.LlmInvalidRequestException;
import com.enterprise.llm.exception.LlmRateLimitException;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.resilience.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class FailoverRoutingService {

    private static final Logger log = LoggerFactory.getLogger(FailoverRoutingService.class);

    private final LlmClient primaryClient;
    private final LlmClient fallbackClient;
    private final String fallbackModel;
    private final CircuitBreaker circuitBreaker;

    @Autowired(required = false)
    private ContextManager contextManager;

    @Value("${llm.resilience.max-rate-limit-retries:2}")
    private int maxRateLimitRetries = 2;

    @Value("${llm.resilience.backoff-base-ms:100}")
    private long backoffBaseMs = 100L;

    @Autowired
    public FailoverRoutingService(
            @Qualifier("primaryLlmClient") LlmClient primaryClient,
            @Qualifier("fallbackLlmClient") LlmClient fallbackClient,
            @Value("${llm.fallback.model:qwen2.5:0.5b}") String fallbackModel,
            @Autowired(required = false) ContextManager contextManager,
            @Autowired(required = false) CircuitBreaker circuitBreaker
    ) {
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
        this.fallbackModel = fallbackModel;
        this.contextManager = contextManager;
        this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new CircuitBreaker("primary-llm");
    }

    public FailoverRoutingService(
            LlmClient primaryClient,
            LlmClient fallbackClient,
            String fallbackModel,
            ContextManager contextManager
    ) {
        this(primaryClient, fallbackClient, fallbackModel, contextManager, null);
    }

    public FailoverRoutingService(
            LlmClient primaryClient,
            LlmClient fallbackClient,
            String fallbackModel
    ) {
        this(primaryClient, fallbackClient, fallbackModel, null, null);
    }

    public LlmClient getPrimaryClient() {
        return primaryClient;
    }

    public LlmClient getFallbackClient() {
        return fallbackClient;
    }

    public void setContextManager(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    public void setMaxRateLimitRetries(int maxRateLimitRetries) {
        this.maxRateLimitRetries = maxRateLimitRetries;
    }

    public void setBackoffBaseMs(long backoffBaseMs) {
        this.backoffBaseMs = backoffBaseMs;
    }

    public ChatCompletionResponse chatCompletionWithFailover(ChatCompletionRequest request) {
        if (isCircuitOpen()) {
            log.warn("Circuit Breaker OPEN: Bypassing primary and executing fallback directly");
            return executeFallbackSync(request);
        }

        ChatCompletionRequest currentRequest = request;
        int rateLimitAttempts = 0;
        int compactionAttempts = 0;

        while (true) {
            try {
                ChatCompletionResponse response = primaryClient.chatCompletion(currentRequest);
                recordSuccess();
                return response;
            } catch (LlmContentFilterException | LlmAuthenticationException | LlmInvalidRequestException fatalEx) {
                log.warn("Non-retryable fatal LLM exception: status={}, code={}, msg={}",
                        fatalEx.getStatusCode(), fatalEx.getErrorCode(), fatalEx.getMessage());
                throw fatalEx;
            } catch (LlmContextLengthExceededException ctxEx) {
                if (contextManager != null && compactionAttempts < 2) {
                    compactionAttempts++;
                    log.warn("Context length exceeded (attempt {}). Triggering emergency compaction and in-place retry...", compactionAttempts);
                    currentRequest = contextManager.forceEmergencyCompaction(currentRequest);
                    continue;
                }
                log.error("Context length exceeded and cannot be compacted further: {}", ctxEx.getMessage());
                throw ctxEx;
            } catch (LlmRateLimitException rateEx) {
                if (rateLimitAttempts < maxRateLimitRetries) {
                    rateLimitAttempts++;
                    long delayMs = rateEx.getRetryAfterMs() > 0 ? rateEx.getRetryAfterMs()
                            : (long) (backoffBaseMs * Math.pow(2, rateLimitAttempts - 1) + Math.random() * 50);
                    delayMs = Math.min(delayMs, 5000L);
                    log.warn("Rate limited (429), backing off for {}ms before retry attempt {}/{}", delayMs, rateLimitAttempts, maxRateLimitRetries);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw rateEx;
                    }
                    continue;
                }
                recordFailure();
                log.warn("Rate limit retries exhausted, attempting failover to local model {}: {}", fallbackModel, rateEx.getMessage());
                return executeFallbackSync(currentRequest);
            } catch (Exception primaryEx) {
                recordFailure();
                log.warn("Primary LLM call failed, attempting failover to local model {}: {}", fallbackModel, primaryEx.getMessage());
                return executeFallbackSync(currentRequest);
            }
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
                        if (primaryError instanceof LlmContentFilterException
                                || primaryError instanceof LlmAuthenticationException
                                || primaryError instanceof LlmInvalidRequestException) {
                            log.warn("Primary stream fatal non-retryable error: {}", primaryError.getMessage());
                            onError.accept(primaryError);
                            return;
                        }

                        if (primaryError instanceof LlmContextLengthExceededException && contextManager != null) {
                            log.warn("Primary stream context exceeded. Attempting emergency compaction and re-streaming...");
                            try {
                                ChatCompletionRequest compacted = contextManager.forceEmergencyCompaction(request);
                                activeHandle[0] = primaryClient.streamChatCompletion(
                                        compacted,
                                        chunk -> {
                                            firstChunkReceived.set(true);
                                            recordSuccess();
                                            onChunk.accept(chunk);
                                        },
                                        onComplete,
                                        retryError -> {
                                            if (!firstChunkReceived.get()) {
                                                recordFailure();
                                                log.warn("Stream retry after compaction failed, failing over to {}: {}", fallbackModel, retryError.getMessage());
                                                activeHandle[0] = executeFallbackStream(compacted, onChunk, onComplete, onError);
                                            } else {
                                                onError.accept(retryError);
                                            }
                                        }
                                );
                                return;
                            } catch (Exception e) {
                                log.error("Stream emergency compaction failed: {}", e.getMessage());
                            }
                        }

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
                request.temperature(),
                request.maxTokens(),
                request.tools(),
                request.toolChoice()
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
                request.temperature(),
                request.maxTokens(),
                request.tools(),
                request.toolChoice()
        );
        return fallbackClient.streamChatCompletion(fallbackRequest, onChunk, onComplete, onError);
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    private boolean isCircuitOpen() {
        return !circuitBreaker.tryAcquirePermission();
    }

    private void recordSuccess() {
        circuitBreaker.recordSuccess();
    }

    private void recordFailure() {
        circuitBreaker.recordFailure();
    }
}
