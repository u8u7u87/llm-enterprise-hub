package com.enterprise.llm.router;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.enterprise.llm.backend.InferenceBackendAdapter;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.cluster.RemoteClusterManager;
import com.enterprise.llm.cluster.UpstreamNode;
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

@Service
public class FailoverRoutingService implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FailoverRoutingService.class);

    private final LlmClient primaryClient;
    private final LlmClient fallbackClient;
    private final String fallbackModel;
    private final CircuitBreaker circuitBreaker;

    @Autowired(required = false)
    private ContextManager contextManager;

    @Autowired(required = false)
    private RemoteClusterManager remoteClusterManager;

    @Autowired(required = false)
    private ModelAliasRegistry modelAliasRegistry;

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
            @Autowired(required = false) CircuitBreaker circuitBreaker,
            @Autowired(required = false) RemoteClusterManager remoteClusterManager,
            @Autowired(required = false) ModelAliasRegistry modelAliasRegistry
    ) {
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
        this.fallbackModel = fallbackModel;
        this.contextManager = contextManager;
        this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new CircuitBreaker("primary-llm");
        this.remoteClusterManager = remoteClusterManager;
        this.modelAliasRegistry = modelAliasRegistry;
    }

    public FailoverRoutingService(
            @Qualifier("primaryLlmClient") LlmClient primaryClient,
            @Qualifier("fallbackLlmClient") LlmClient fallbackClient,
            @Value("${llm.fallback.model:qwen2.5:0.5b}") String fallbackModel,
            @Autowired(required = false) ContextManager contextManager,
            @Autowired(required = false) CircuitBreaker circuitBreaker,
            @Autowired(required = false) RemoteClusterManager remoteClusterManager
    ) {
        this(primaryClient, fallbackClient, fallbackModel, contextManager, circuitBreaker, remoteClusterManager, null);
    }

    public FailoverRoutingService(
            @Qualifier("primaryLlmClient") LlmClient primaryClient,
            @Qualifier("fallbackLlmClient") LlmClient fallbackClient,
            @Value("${llm.fallback.model:qwen2.5:0.5b}") String fallbackModel,
            @Autowired(required = false) ContextManager contextManager,
            @Autowired(required = false) CircuitBreaker circuitBreaker
    ) {
        this(primaryClient, fallbackClient, fallbackModel, contextManager, circuitBreaker, null);
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

    public RemoteClusterManager getRemoteClusterManager() {
        return remoteClusterManager;
    }

    public void setRemoteClusterManager(RemoteClusterManager remoteClusterManager) {
        this.remoteClusterManager = remoteClusterManager;
    }

    public ModelAliasRegistry getModelAliasRegistry() {
        return modelAliasRegistry;
    }


    public void setModelAliasRegistry(ModelAliasRegistry modelAliasRegistry) {
        this.modelAliasRegistry = modelAliasRegistry;
    }

    public ChatCompletionResponse chatCompletionWithFailover(ChatCompletionRequest request) {
        if (modelAliasRegistry != null && modelAliasRegistry.isAlias(request.model())) {
            List<String> candidates = modelAliasRegistry.resolveCandidates(request.model());
            for (int i = 0; i < candidates.size(); i++) {
                String candidate = candidates.get(i);
                ChatCompletionRequest candidateReq = request.withModel(candidate);
                boolean isLastCandidate = (i == candidates.size() - 1);
                try {
                    return executeSingleModel(candidateReq, isLastCandidate);
                } catch (LlmContentFilterException | LlmAuthenticationException | LlmInvalidRequestException fatalEx) {
                    throw fatalEx;
                } catch (Exception ex) {
                    log.warn("Candidate model [{}] in alias [{}] failed: {}. Cascading...",
                            candidate, request.model(), ex.getMessage());
                    if (isLastCandidate) {
                        return executeFallbackSync(request);
                    }
                }
            }
        }
        return executeSingleModel(request, true);
    }

    private ChatCompletionResponse executeSingleModel(ChatCompletionRequest request, boolean allowFallback) {
        // 1. Try Remote Compute Cluster first if discovered and available
        if (remoteClusterManager != null && remoteClusterManager.hasActiveNodes()) {
            UpstreamNode node = remoteClusterManager.selectNode(request);
            if (node != null) {
                LlmClient nodeClient = remoteClusterManager.getClientForNode(node);
                if (nodeClient != null) {
                    node.incrementActiveRequests();
                    try {
                        ChatCompletionRequest effectiveNodeReq = request;
                        if (remoteClusterManager.getBackendRegistry() != null) {
                            InferenceBackendAdapter adapter = remoteClusterManager.getBackendRegistry().getAdapter(node.getEngineType());
                            if (adapter != null) {
                                effectiveNodeReq = adapter.adaptRequest(request, node);
                            }
                        }
                        ChatCompletionResponse response = nodeClient.chatCompletion(effectiveNodeReq);
                        node.recordSuccess();
                        return response;
                    } catch (LlmContentFilterException | LlmAuthenticationException | LlmInvalidRequestException fatalEx) {
                        log.warn("Non-retryable fatal LLM exception from remote cluster node [{}]: status={}, msg={}",
                                node.getId(), fatalEx.getStatusCode(), fatalEx.getMessage());
                        throw fatalEx;
                    } catch (Exception ex) {
                        node.recordFailure();
                        log.warn("Remote cluster node [{}] failed: {}", node.getId(), ex.getMessage());
                        if (allowFallback) {
                            log.warn("Falling back to local fallback model {}: {}", fallbackModel, ex.getMessage());
                            return executeFallbackSync(request);
                        } else {
                            throw (ex instanceof RuntimeException re) ? re : new RuntimeException(ex);
                        }
                    } finally {
                        node.decrementActiveRequests();
                    }
                }
            }
        }

        // 2. Default/Safety Net routing with CircuitBreaker and Retry
        if (isCircuitOpen()) {
            if (allowFallback) {
                log.warn("Circuit Breaker OPEN: Bypassing primary and executing fallback directly");
                return executeFallbackSync(request);
            } else {
                throw new RuntimeException("Circuit breaker OPEN for candidate: " + request.model());
            }
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
                log.warn("Rate limit retries exhausted: {}", rateEx.getMessage());
                if (allowFallback) {
                    log.warn("Attempting failover to local model {}: {}", fallbackModel, rateEx.getMessage());
                    return executeFallbackSync(currentRequest);
                } else {
                    throw rateEx;
                }
            } catch (Exception primaryEx) {
                recordFailure();
                log.warn("Primary LLM call failed: {}", primaryEx.getMessage());
                if (allowFallback) {
                    log.warn("Attempting failover to local model {}: {}", fallbackModel, primaryEx.getMessage());
                    return executeFallbackSync(currentRequest);
                } else {
                    throw (primaryEx instanceof RuntimeException re) ? re : new RuntimeException(primaryEx);
                }
            }
        }
    }


    public StreamCancellationHandle streamChatCompletionWithFailover(
            ChatCompletionRequest request,
            Consumer<ChatCompletionChunk> onChunk,
            Runnable onComplete,
            Consumer<Throwable> onError
    ) {
        if (modelAliasRegistry != null && modelAliasRegistry.isAlias(request.model())) {
            List<String> candidates = modelAliasRegistry.resolveCandidates(request.model());
            if (!candidates.isEmpty()) {
                ChatCompletionRequest candidateReq = request.withModel(candidates.get(0));
                return executeSingleModelStream(candidateReq, onChunk, onComplete, onError);
            }
        }
        return executeSingleModelStream(request, onChunk, onComplete, onError);
    }

    private StreamCancellationHandle executeSingleModelStream(
            ChatCompletionRequest request,
            Consumer<ChatCompletionChunk> onChunk,
            Runnable onComplete,
            Consumer<Throwable> onError
    ) {
        final StreamCancellationHandle[] activeHandle = new StreamCancellationHandle[1];
        final AtomicBoolean firstChunkReceived = new AtomicBoolean(false);

        // 1. Try Remote Compute Cluster stream first if discovered and available
        if (remoteClusterManager != null && remoteClusterManager.hasActiveNodes()) {
            UpstreamNode node = remoteClusterManager.selectNode(request);
            if (node != null) {
                LlmClient nodeClient = remoteClusterManager.getClientForNode(node);
                if (nodeClient != null) {
                    node.incrementActiveRequests();
                    final StreamCancellationHandle[] remoteHandle = new StreamCancellationHandle[1];
                    final AtomicBoolean remoteChunkReceived = new AtomicBoolean(false);
                    ChatCompletionRequest effectiveStreamReq = request;
                    if (remoteClusterManager.getBackendRegistry() != null) {
                        InferenceBackendAdapter adapter = remoteClusterManager.getBackendRegistry().getAdapter(node.getEngineType());
                        if (adapter != null) {
                            effectiveStreamReq = adapter.adaptRequest(request, node);
                        }
                    }
                    remoteHandle[0] = nodeClient.streamChatCompletion(
                            effectiveStreamReq,
                            chunk -> {
                                remoteChunkReceived.set(true);
                                node.recordSuccess();
                                onChunk.accept(chunk);
                            },
                            () -> {
                                node.decrementActiveRequests();
                                onComplete.run();
                            },
                            error -> {
                                node.decrementActiveRequests();
                                if (!remoteChunkReceived.get()) {
                                    if (error instanceof LlmContentFilterException
                                            || error instanceof LlmAuthenticationException
                                            || error instanceof LlmInvalidRequestException) {
                                        onError.accept(error);
                                        return;
                                    }
                                    node.recordFailure();
                                    log.warn("Remote cluster node [{}] stream failed before first chunk, falling back to local {}: {}",
                                            node.getId(), fallbackModel, error.getMessage());
                                    activeHandle[0] = executeFallbackStream(request, onChunk, onComplete, onError);
                                } else {
                                    onError.accept(error);
                                }
                            }
                    );
                    return new StreamCancellationHandle(() -> {
                        node.decrementActiveRequests();
                        if (remoteHandle[0] != null) {
                            remoteHandle[0].cancel();
                        }
                    });
                }
            }
        }

        // 2. Default/Safety Net streaming with CircuitBreaker and Retry
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

    @Override
    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
        return chatCompletionWithFailover(request);
    }

    @Override
    public StreamCancellationHandle streamChatCompletion(
            ChatCompletionRequest request,
            Consumer<ChatCompletionChunk> onChunk,
            Runnable onComplete,
            Consumer<Throwable> onError
    ) {
        return streamChatCompletionWithFailover(request, onChunk, onComplete, onError);
    }
}
