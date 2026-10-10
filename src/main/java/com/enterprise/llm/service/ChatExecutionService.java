package com.enterprise.llm.service;

import com.enterprise.llm.agent.AgentToolOrchestrator;
import com.enterprise.llm.cache.CacheEngine;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import com.enterprise.llm.quota.QuotaReservation;
import com.enterprise.llm.quota.TenantQuotaService;
import com.enterprise.llm.router.FailoverRoutingService;
import com.enterprise.llm.token.TokenEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChatExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ChatExecutionService.class);

    private final FailoverRoutingService failoverRoutingService;
    private final TenantQuotaService tenantQuotaService;
    private final TokenEstimator tokenEstimator;
    private final CacheEngine cacheEngine;
    private final AgentToolOrchestrator agentToolOrchestrator;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChatExecutionService(
            FailoverRoutingService failoverRoutingService,
            TenantQuotaService tenantQuotaService,
            TokenEstimator tokenEstimator,
            CacheEngine cacheEngine,
            @Autowired(required = false) AgentToolOrchestrator agentToolOrchestrator
    ) {
        this.failoverRoutingService = failoverRoutingService;
        this.tenantQuotaService = tenantQuotaService;
        this.tokenEstimator = tokenEstimator;
        this.cacheEngine = cacheEngine;
        this.agentToolOrchestrator = agentToolOrchestrator;
        this.objectMapper = new ObjectMapper();
    }

    public ChatExecutionService(
            FailoverRoutingService failoverRoutingService,
            TenantQuotaService tenantQuotaService,
            TokenEstimator tokenEstimator,
            CacheEngine cacheEngine
    ) {
        this(failoverRoutingService, tenantQuotaService, tokenEstimator, cacheEngine, null);
    }

    public ChatExecutionService(
            FailoverRoutingService failoverRoutingService,
            TenantQuotaService tenantQuotaService,
            TokenEstimator tokenEstimator
    ) {
        this(failoverRoutingService, tenantQuotaService, tokenEstimator, null, null);
    }

    public ChatExecutionService(FailoverRoutingService failoverRoutingService) {
        this(failoverRoutingService, null, new TokenEstimator(), null, null);
    }

    public ChatCompletionResponse executeSync(ChatCompletionRequest request) {
        return executeSync(request, null);
    }

    public ChatCompletionResponse executeSync(ChatCompletionRequest request, QuotaReservation reservation) {
        try {
            ChatCompletionResponse response;
            if (request.tools() != null && !request.tools().isEmpty() && agentToolOrchestrator != null) {
                String tenantId = reservation != null ? reservation.tenantId() : "default";
                response = agentToolOrchestrator.runLoop(failoverRoutingService.getPrimaryClient(), request, tenantId);
            } else {
                response = failoverRoutingService.chatCompletionWithFailover(request);
            }
            if (tenantQuotaService != null && reservation != null) {
                int actualTokens = 0;
                if (response != null && response.usage() != null && response.usage().totalTokens() != null) {
                    actualTokens = response.usage().totalTokens();
                } else if (tokenEstimator != null && response != null) {
                    int promptTokens = tokenEstimator.estimatePromptTokens(request.messages());
                    int completionTokens = (response.choices() != null && !response.choices().isEmpty() && response.choices().get(0).message() != null)
                            ? tokenEstimator.countTokens(response.choices().get(0).message().content()) : 0;
                    actualTokens = promptTokens + completionTokens;
                }
                tenantQuotaService.settle(reservation, actualTokens);
            }
            if (cacheEngine != null && response != null) {
                cacheEngine.put(request, response);
            }
            return response;
        } catch (Exception e) {
            if (tenantQuotaService != null && reservation != null) {
                tenantQuotaService.release(reservation);
            }
            throw e;
        }
    }

    public SseEmitter executeStream(ChatCompletionRequest request) {
        return executeStream(request, null);
    }

    public SseEmitter executeStream(ChatCompletionRequest request, QuotaReservation reservation) {
        final StreamCancellationHandle[] handleHolder = new StreamCancellationHandle[1];
        final AtomicInteger generatedTokens = new AtomicInteger(0);
        final AtomicReference<StringBuilder> streamContentAcc = new AtomicReference<>(new StringBuilder());

        SseEmitter emitter = new SseEmitter(120_000L) {
            @Override
            public synchronized void complete() {
                if (handleHolder[0] != null) {
                    handleHolder[0].cancel();
                }
                super.complete();
            }

            @Override
            public synchronized void completeWithError(Throwable ex) {
                if (handleHolder[0] != null) {
                    handleHolder[0].cancel();
                }
                super.completeWithError(ex);
            }
        };

        StreamCancellationHandle handle = failoverRoutingService.streamChatCompletionWithFailover(
                request,
                chunk -> {
                    try {
                        if (chunk != null) {
                            if (chunk.usage() != null && chunk.usage().totalTokens() != null) {
                                generatedTokens.set(chunk.usage().totalTokens());
                            } else if (tokenEstimator != null && chunk.choices() != null) {
                                for (var choice : chunk.choices()) {
                                    if (choice.delta() != null && choice.delta().content() != null) {
                                        generatedTokens.addAndGet(tokenEstimator.countTokens(choice.delta().content()));
                                        streamContentAcc.get().append(choice.delta().content());
                                    }
                                }
                            }
                        }
                        String jsonChunk = objectMapper.writeValueAsString(chunk);
                        emitter.send(SseEmitter.event().data(jsonChunk));
                    } catch (IOException e) {
                        log.warn("Failed to push chunk to client emitter, aborting", e);
                        emitter.completeWithError(e);
                    }
                },
                () -> {
                    try {
                        int promptTokens = tokenEstimator != null ? tokenEstimator.estimatePromptTokens(request.messages()) : 0;
                        int totalTokens = generatedTokens.get() > 0 ? (promptTokens + generatedTokens.get()) : promptTokens;
                        if (tenantQuotaService != null && reservation != null) {
                            tenantQuotaService.settle(reservation, totalTokens);
                        }
                        if (cacheEngine != null && streamContentAcc.get().length() > 0) {
                            ChatCompletionResponse streamResponse = new ChatCompletionResponse(
                                    "stream-cache-" + System.currentTimeMillis(),
                                    "chat.completion",
                                    System.currentTimeMillis() / 1000,
                                    request.model(),
                                    List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", streamContentAcc.get().toString()), "stop")),
                                    new Usage(promptTokens, generatedTokens.get(), totalTokens)
                            );
                            cacheEngine.put(request, streamResponse);
                        }
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                ex -> {
                    if (tenantQuotaService != null && reservation != null) {
                        int promptTokens = tokenEstimator != null ? tokenEstimator.estimatePromptTokens(request.messages()) : 0;
                        int totalTokens = generatedTokens.get() > 0 ? (promptTokens + generatedTokens.get()) : 0;
                        if (totalTokens > 0) {
                            tenantQuotaService.settle(reservation, totalTokens);
                        } else {
                            tenantQuotaService.release(reservation);
                        }
                    }
                    emitter.completeWithError(ex);
                }
        );
        handleHolder[0] = handle;

        emitter.onCompletion(() -> {
            log.debug("SSE emitter completed");
            if (handleHolder[0] != null) {
                handleHolder[0].cancel();
            }
        });
        emitter.onTimeout(() -> {
            log.warn("SSE emitter timed out");
            if (handleHolder[0] != null) {
                handleHolder[0].cancel();
            }
        });
        emitter.onError(throwable -> {
            log.warn("SSE emitter error: {}", throwable.getMessage());
            if (handleHolder[0] != null) {
                handleHolder[0].cancel();
            }
        });

        return emitter;
    }

    public SseEmitter emitCachedStream(ChatCompletionResponse cachedResponse) {
        SseEmitter emitter = new SseEmitter(60_000L);
        Thread.ofVirtual().start(() -> {
            try {
                if (cachedResponse != null && cachedResponse.choices() != null && !cachedResponse.choices().isEmpty()) {
                    String fullContent = cachedResponse.choices().get(0).message().content();
                    if (fullContent != null && !fullContent.isEmpty()) {
                        int chunkSize = Math.max(1, fullContent.length() / 5);
                        for (int i = 0; i < fullContent.length(); i += chunkSize) {
                            int end = Math.min(fullContent.length(), i + chunkSize);
                            String slice = fullContent.substring(i, end);
                            ChatCompletionChunk chunk = new ChatCompletionChunk(
                                    cachedResponse.id(),
                                    "chat.completion.chunk",
                                    cachedResponse.created(),
                                    cachedResponse.model(),
                                    List.of(new ChatCompletionChunk.ChoiceChunk(0, new ChatCompletionChunk.Delta("assistant", slice), null)),
                                    null
                            );
                            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(chunk)));
                            Thread.sleep(10);
                        }
                    }
                }
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Error emitting cached stream", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
