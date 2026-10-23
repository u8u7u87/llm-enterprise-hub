package com.enterprise.llm.service;

import com.enterprise.llm.agent.AgentToolOrchestrator;
import com.enterprise.llm.cache.CacheEngine;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.context.ContextManager;
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
import com.enterprise.llm.trace.LlmSpan;
import com.enterprise.llm.trace.RequestAuditLogger;
import com.enterprise.llm.trace.TraceCollector;
import com.enterprise.llm.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final ContextManager contextManager;
    private final TraceCollector traceCollector;
    private final com.enterprise.llm.session.SessionManager sessionManager;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChatExecutionService(
            FailoverRoutingService failoverRoutingService,
            TenantQuotaService tenantQuotaService,
            TokenEstimator tokenEstimator,
            CacheEngine cacheEngine,
            @Autowired(required = false) AgentToolOrchestrator agentToolOrchestrator,
            @Autowired(required = false) ContextManager contextManager,
            @Autowired(required = false) TraceCollector traceCollector,
            @Autowired(required = false) com.enterprise.llm.session.SessionManager sessionManager
    ) {
        this.failoverRoutingService = failoverRoutingService;
        this.tenantQuotaService = tenantQuotaService;
        this.tokenEstimator = tokenEstimator;
        this.cacheEngine = cacheEngine;
        this.agentToolOrchestrator = agentToolOrchestrator;
        this.contextManager = contextManager;
        this.traceCollector = traceCollector;
        this.sessionManager = sessionManager;
        this.objectMapper = new ObjectMapper();
    }

    public ChatExecutionService(
            FailoverRoutingService failoverRoutingService,
            TenantQuotaService tenantQuotaService,
            TokenEstimator tokenEstimator,
            CacheEngine cacheEngine,
            AgentToolOrchestrator agentToolOrchestrator,
            ContextManager contextManager,
            TraceCollector traceCollector
    ) {
        this(failoverRoutingService, tenantQuotaService, tokenEstimator, cacheEngine, agentToolOrchestrator, contextManager, traceCollector, null);
    }

    public ChatExecutionService(
            FailoverRoutingService failoverRoutingService,
            TenantQuotaService tenantQuotaService,
            TokenEstimator tokenEstimator,
            CacheEngine cacheEngine,
            AgentToolOrchestrator agentToolOrchestrator
    ) {
        this(failoverRoutingService, tenantQuotaService, tokenEstimator, cacheEngine, agentToolOrchestrator, null, null, null);
    }

    public ChatExecutionService(
            FailoverRoutingService failoverRoutingService,
            TenantQuotaService tenantQuotaService,
            TokenEstimator tokenEstimator,
            CacheEngine cacheEngine
    ) {
        this(failoverRoutingService, tenantQuotaService, tokenEstimator, cacheEngine, null, null, null, null);
    }

    public ChatExecutionService(
            FailoverRoutingService failoverRoutingService,
            TenantQuotaService tenantQuotaService,
            TokenEstimator tokenEstimator
    ) {
        this(failoverRoutingService, tenantQuotaService, tokenEstimator, null, null, null, null, null);
    }

    public ChatExecutionService(FailoverRoutingService failoverRoutingService) {
        this(failoverRoutingService, null, new TokenEstimator(), null, null, null, null, null);
    }

    public ChatCompletionResponse executeSync(ChatCompletionRequest request) {
        return executeSync(request, null);
    }

    public ChatCompletionResponse executeSync(ChatCompletionRequest request, QuotaReservation reservation) {
        String tenantId = (reservation != null && reservation.tenantId() != null)
                ? reservation.tenantId()
                : (TraceContext.getCurrent() != null && TraceContext.getCurrent().tenantId() != null
                    ? TraceContext.getCurrent().tenantId() : "default");

        ChatCompletionRequest sessionRequest = (sessionManager != null)
                ? sessionManager.mergeSessionHistory(request, tenantId)
                : request;
        ChatCompletionRequest effectiveRequest = (contextManager != null)
                ? contextManager.manageContext(sessionRequest)
                : sessionRequest;

        long startTimeMs = System.currentTimeMillis();
        TraceContext traceCtx = TraceContext.getCurrent();
        LlmSpan span = new LlmSpan("chat_completion_sync", traceCtx, effectiveRequest.model());
        span.start(startTimeMs);
        span.setStream(false);
        if (effectiveRequest.tools() != null && !effectiveRequest.tools().isEmpty()) {
            effectiveRequest.tools().forEach(t -> span.addToolInvoked(t.name()));
        }

        try {
            ChatCompletionResponse response;
            if (effectiveRequest.tools() != null && !effectiveRequest.tools().isEmpty() && agentToolOrchestrator != null) {
                response = agentToolOrchestrator.runLoop(failoverRoutingService.getPrimaryClient(), effectiveRequest, tenantId);
            } else {
                response = failoverRoutingService.chatCompletionWithFailover(effectiveRequest);
            }

            int actualTokens = 0;
            int promptTokens = 0;
            int completionTokens = 0;
            if (response != null && response.usage() != null && response.usage().totalTokens() != null) {
                actualTokens = response.usage().totalTokens();
                promptTokens = response.usage().promptTokens() != null ? response.usage().promptTokens() : 0;
                completionTokens = response.usage().completionTokens() != null ? response.usage().completionTokens() : 0;
            } else if (tokenEstimator != null && response != null) {
                promptTokens = tokenEstimator.estimatePromptTokens(effectiveRequest.messages());
                completionTokens = (response.choices() != null && !response.choices().isEmpty() && response.choices().get(0).message() != null)
                        ? tokenEstimator.countTokens(response.choices().get(0).message().content()) : 0;
                actualTokens = promptTokens + completionTokens;
            }

            if (tenantQuotaService != null && reservation != null) {
                tenantQuotaService.settle(reservation, actualTokens);
            }
            if (cacheEngine != null && response != null) {
                cacheEngine.put(effectiveRequest, response);
            }

            if (sessionManager != null && request.sessionId() != null && !request.sessionId().isBlank()) {
                List<ChatMessage> turnToPersist = new ArrayList<>();
                if (request.messages() != null) {
                    for (ChatMessage m : request.messages()) {
                        if (!"system".equalsIgnoreCase(m.role())) {
                            turnToPersist.add(m);
                        }
                    }
                }
                if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                    ChatMessage asst = response.choices().get(0).message();
                    if (asst != null) {
                        turnToPersist.add(asst);
                    }
                }
                if (!turnToPersist.isEmpty()) {
                    sessionManager.appendMessages(tenantId, request.sessionId(), turnToPersist);
                }
            }

            long endTimeMs = System.currentTimeMillis();
            span.finish(endTimeMs, promptTokens, completionTokens);
            if (traceCollector != null) {
                traceCollector.collect(span);
            }
            RequestAuditLogger.logAudit(span, 200);

            return response;
        } catch (Exception e) {
            long endTimeMs = System.currentTimeMillis();
            span.finish(endTimeMs, 0, 0);
            if (traceCollector != null) {
                traceCollector.collect(span);
            }
            RequestAuditLogger.logAudit(span, 500, e.getMessage());

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
        String tenantId = (reservation != null && reservation.tenantId() != null)
                ? reservation.tenantId()
                : (TraceContext.getCurrent() != null && TraceContext.getCurrent().tenantId() != null
                    ? TraceContext.getCurrent().tenantId() : "default");

        ChatCompletionRequest sessionRequest = (sessionManager != null)
                ? sessionManager.mergeSessionHistory(request, tenantId)
                : request;
        ChatCompletionRequest effectiveRequest = (contextManager != null)
                ? contextManager.manageContext(sessionRequest)
                : sessionRequest;

        long startTimeMs = System.currentTimeMillis();
        TraceContext traceCtx = TraceContext.getCurrent();
        LlmSpan span = new LlmSpan("chat_completion_stream", traceCtx, effectiveRequest.model());
        span.start(startTimeMs);
        span.setStream(true);
        final AtomicBoolean firstChunkRecorded = new AtomicBoolean(false);

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
                effectiveRequest,
                chunk -> {
                    try {
                        if (firstChunkRecorded.compareAndSet(false, true)) {
                            span.recordFirstToken(System.currentTimeMillis());
                        }
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
                        int promptTokens = tokenEstimator != null ? tokenEstimator.estimatePromptTokens(effectiveRequest.messages()) : 0;
                        int totalTokens = generatedTokens.get() > 0 ? (promptTokens + generatedTokens.get()) : promptTokens;
                        if (tenantQuotaService != null && reservation != null) {
                            tenantQuotaService.settle(reservation, totalTokens);
                        }
                        if (cacheEngine != null && streamContentAcc.get().length() > 0) {
                            ChatCompletionResponse streamResponse = new ChatCompletionResponse(
                                    "stream-cache-" + System.currentTimeMillis(),
                                    "chat.completion",
                                    System.currentTimeMillis() / 1000,
                                    effectiveRequest.model(),
                                    List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", streamContentAcc.get().toString()), "stop")),
                                    new Usage(promptTokens, generatedTokens.get(), totalTokens)
                            );
                            cacheEngine.put(effectiveRequest, streamResponse);
                        }

                        if (sessionManager != null && request.sessionId() != null && !request.sessionId().isBlank()) {
                            List<ChatMessage> turnToPersist = new ArrayList<>();
                            if (request.messages() != null) {
                                for (ChatMessage m : request.messages()) {
                                    if (!"system".equalsIgnoreCase(m.role())) {
                                        turnToPersist.add(m);
                                    }
                                }
                            }
                            if (streamContentAcc.get().length() > 0) {
                                turnToPersist.add(ChatMessage.assistant(streamContentAcc.get().toString()));
                            }
                            if (!turnToPersist.isEmpty()) {
                                sessionManager.appendMessages(tenantId, request.sessionId(), turnToPersist);
                            }
                        }

                        long endTimeMs = System.currentTimeMillis();
                        span.finish(endTimeMs, promptTokens, generatedTokens.get());
                        if (traceCollector != null) {
                            traceCollector.collect(span);
                        }
                        RequestAuditLogger.logAudit(span, 200);

                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                ex -> {
                    int promptTokens = tokenEstimator != null ? tokenEstimator.estimatePromptTokens(effectiveRequest.messages()) : 0;
                    int totalTokens = generatedTokens.get() > 0 ? (promptTokens + generatedTokens.get()) : 0;
                    if (tenantQuotaService != null && reservation != null) {
                        if (totalTokens > 0) {
                            tenantQuotaService.settle(reservation, totalTokens);
                        } else {
                            tenantQuotaService.release(reservation);
                        }
                    }
                    long endTimeMs = System.currentTimeMillis();
                    span.finish(endTimeMs, promptTokens, generatedTokens.get());
                    if (traceCollector != null) {
                        traceCollector.collect(span);
                    }
                    RequestAuditLogger.logAudit(span, 500, ex.getMessage());

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
