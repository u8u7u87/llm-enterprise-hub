package com.enterprise.llm.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.enterprise.llm.agent.AgentToolOrchestrator;
import com.enterprise.llm.agent.StreamingAgentOrchestrator;
import com.enterprise.llm.cache.CacheEngine;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.context.ContextManager;
import com.enterprise.llm.cost.CostCalculator;
import com.enterprise.llm.cost.TenantBudgetManager;
import com.enterprise.llm.guard.StreamingGuardrailException;
import com.enterprise.llm.guard.StreamingOutputGuardrail;
import com.enterprise.llm.guard.privacy.PrivacyDeMaskingTransformer;
import com.enterprise.llm.guard.privacy.PrivacyVault;
import com.enterprise.llm.metrics.LlmMetricsCollector;
import com.enterprise.llm.metrics.NoOpMetricsCollector;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import com.enterprise.llm.quota.QuotaReservation;
import com.enterprise.llm.quota.TenantQuotaService;
import com.enterprise.llm.router.FailoverRoutingService;
import com.enterprise.llm.router.shadow.ShadowTrafficMirror;
import com.enterprise.llm.session.SessionManager;
import com.enterprise.llm.token.TokenEstimator;
import com.enterprise.llm.trace.LlmSpan;
import com.enterprise.llm.trace.RequestAuditLogger;
import com.enterprise.llm.trace.TraceCollector;
import com.enterprise.llm.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ChatExecutionService.class);

    private final FailoverRoutingService failoverRoutingService;
    private final TenantQuotaService tenantQuotaService;
    private final TokenEstimator tokenEstimator;
    private final CacheEngine cacheEngine;
    private final AgentToolOrchestrator agentToolOrchestrator;
    private final StreamingAgentOrchestrator streamingAgentOrchestrator;
    private final ContextManager contextManager;
    private final TraceCollector traceCollector;
    private final SessionManager sessionManager;
    private final LlmMetricsCollector metricsCollector;
    private final CostCalculator costCalculator;
    private final TenantBudgetManager tenantBudgetManager;
    private final StreamingOutputGuardrail streamingOutputGuardrail;
    private final ShadowTrafficMirror shadowTrafficMirror;
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
            @Autowired(required = false) SessionManager sessionManager,
            @Autowired(required = false) LlmMetricsCollector metricsCollector,
            @Autowired(required = false) StreamingAgentOrchestrator streamingAgentOrchestrator,
            @Autowired(required = false) CostCalculator costCalculator,
            @Autowired(required = false) TenantBudgetManager tenantBudgetManager,
            @Autowired(required = false) StreamingOutputGuardrail streamingOutputGuardrail,
            @Autowired(required = false) ShadowTrafficMirror shadowTrafficMirror
    ) {
        this.failoverRoutingService = failoverRoutingService;
        this.tenantQuotaService = tenantQuotaService;
        this.tokenEstimator = tokenEstimator;
        this.cacheEngine = cacheEngine;
        this.agentToolOrchestrator = agentToolOrchestrator;
        this.contextManager = contextManager;
        this.traceCollector = traceCollector;
        this.sessionManager = sessionManager;
        this.metricsCollector = metricsCollector != null ? metricsCollector : NoOpMetricsCollector.INSTANCE;
        this.streamingAgentOrchestrator = streamingAgentOrchestrator != null
                ? streamingAgentOrchestrator
                : (agentToolOrchestrator != null ? new StreamingAgentOrchestrator(agentToolOrchestrator) : null);
        this.costCalculator = costCalculator;
        this.tenantBudgetManager = tenantBudgetManager;
        this.streamingOutputGuardrail = streamingOutputGuardrail;
        this.shadowTrafficMirror = shadowTrafficMirror;
        this.objectMapper = new ObjectMapper();
    }

    public ChatExecutionService(
            FailoverRoutingService failoverRoutingService,
            TenantQuotaService tenantQuotaService,
            TokenEstimator tokenEstimator,
            CacheEngine cacheEngine
    ) {
        this(failoverRoutingService, tenantQuotaService, tokenEstimator, cacheEngine,
                null, null, null, null, null, null, null, null, null, null);
    }

    public ChatExecutionService(
            FailoverRoutingService failoverRoutingService,
            TenantQuotaService tenantQuotaService,
            TokenEstimator tokenEstimator
    ) {
        this(failoverRoutingService, tenantQuotaService, tokenEstimator, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    public ChatCompletionResponse executeSync(ChatCompletionRequest request) {
        return executeSync(request, null, null);
    }

    public ChatCompletionResponse executeSync(ChatCompletionRequest request, QuotaReservation reservation) {
        return executeSync(request, reservation, null);
    }

    public ChatCompletionResponse executeSync(ChatCompletionRequest request, QuotaReservation reservation,
                                              PrivacyVault privacyVault) {
        boolean piiPresent = privacyVault != null && !privacyVault.isEmpty();
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
        if (metricsCollector != null) {
            span.setMetricsCollector(metricsCollector);
        }
        span.start(startTimeMs);
        span.setStream(false);
        if (effectiveRequest.tools() != null && !effectiveRequest.tools().isEmpty()) {
            effectiveRequest.tools().forEach(t -> span.addToolInvoked(t.name()));
        }

        if (tenantBudgetManager != null && tenantId != null) {
            tenantBudgetManager.checkBudget(tenantId);
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
            if (cacheEngine != null && response != null && !piiPresent) {
                cacheEngine.put(effectiveRequest, response, tenantId);
            }

            if (sessionManager != null && request.sessionId() != null && !request.sessionId().isBlank()) {
                List<ChatMessage> turnToPersist = new ArrayList<>();
                if (request.messages() != null) {
                    for (ChatMessage m : request.messages()) {
                        if (!"system".equalsIgnoreCase(m.role())) {
                            // Persist what the user actually wrote, not the placeholders.
                            turnToPersist.add(restoreMessage(m, privacyVault));
                        }
                    }
                }
                if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                    ChatMessage asst = response.choices().get(0).message();
                    if (asst != null) {
                        turnToPersist.add(restoreMessage(asst, privacyVault));
                    }
                }
                if (!turnToPersist.isEmpty()) {
                    sessionManager.appendMessages(tenantId, request.sessionId(), turnToPersist);
                }
            }

            if (costCalculator != null) {
                double cost = costCalculator.calculateCostUsd(effectiveRequest.model(), promptTokens, completionTokens);
                span.setCostUsd(cost);
                if (tenantBudgetManager != null && tenantId != null) {
                    tenantBudgetManager.recordSpend(tenantId, cost);
                }
            }

            long endTimeMs = System.currentTimeMillis();
            span.finish(endTimeMs, promptTokens, completionTokens);
            if (traceCollector != null) {
                traceCollector.collect(span);
            }
            RequestAuditLogger.logAudit(span, 200);

            // Shadow traffic: fire-and-forget mirror to an experimental model for offline
            // comparison. Deliberately uses the bare primary client instead of the failover router,
            // because the router shares one circuit breaker with the live path — a shadow model
            // outage must never trip it. Only the synchronous path is mirrored (a streamed answer
            // has no complete response body to compare), and the shadow call bypasses quota
            // accounting and metrics.
            if (shadowTrafficMirror != null) {
                shadowTrafficMirror.maybeMirrorAsync(
                        effectiveRequest,
                        response,
                        System.currentTimeMillis() - startTimeMs,
                        tenantId,
                        traceCtx != null ? traceCtx.traceId() : null,
                        failoverRoutingService.getPrimaryClient());
            }

            return response;
        } catch (Exception e) {
            span.recordError(e);
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
        return executeStream(request, null, null);
    }

    public SseEmitter executeStream(ChatCompletionRequest request, QuotaReservation reservation) {
        return executeStream(request, reservation, null);
    }

    public SseEmitter executeStream(ChatCompletionRequest request, QuotaReservation reservation,
                                    PrivacyVault privacyVault) {
        boolean piiPresent = privacyVault != null && !privacyVault.isEmpty();
        String tenantId = (reservation != null && reservation.tenantId() != null)
                ? reservation.tenantId()
                : (TraceContext.getCurrent() != null && TraceContext.getCurrent().tenantId() != null
                    ? TraceContext.getCurrent().tenantId() : "default");

        if (tenantBudgetManager != null && tenantId != null) {
            tenantBudgetManager.checkBudget(tenantId);
        }


        ChatCompletionRequest sessionRequest = (sessionManager != null)
                ? sessionManager.mergeSessionHistory(request, tenantId)
                : request;
        ChatCompletionRequest effectiveRequest = (contextManager != null)
                ? contextManager.manageContext(sessionRequest)
                : sessionRequest;

        long startTimeMs = System.currentTimeMillis();
        TraceContext traceCtx = TraceContext.getCurrent();
        LlmSpan span = new LlmSpan("chat_completion_stream", traceCtx, effectiveRequest.model());
        if (metricsCollector != null) {
            span.setMetricsCollector(metricsCollector);
        }
        span.start(startTimeMs);
        span.setStream(true);
        final AtomicBoolean firstChunkRecorded = new AtomicBoolean(false);

        final StreamCancellationHandle[] handleHolder = new StreamCancellationHandle[1];
        final AtomicInteger generatedTokens = new AtomicInteger(0);
        final AtomicReference<StringBuilder> streamContentAcc = new AtomicReference<>(new StringBuilder());
        final StreamingOutputGuardrail.StreamSession guardSession =
                (streamingOutputGuardrail != null) ? streamingOutputGuardrail.newSession() : null;
        // Outbound order is fixed: DLP inspects the masked text first (a placeholder cannot match a
        // PII pattern), then the vault puts the user's own data back.
        final PrivacyDeMaskingTransformer.StreamDeMasker deMasker =
                (privacyVault != null && !privacyVault.isEmpty())
                        ? new PrivacyDeMaskingTransformer.StreamDeMasker(privacyVault)
                        : null;

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

        Consumer<ChatCompletionChunk> chunkConsumer = chunk -> {
            try {
                if (firstChunkRecorded.compareAndSet(false, true)) {
                    span.recordFirstToken(System.currentTimeMillis());
                }
                ChatCompletionChunk outbound = chunk;
                if (chunk != null) {
                    if (chunk.choices() != null) {
                        List<ChatCompletionChunk.ChoiceChunk> rewritten = new ArrayList<>(chunk.choices().size());
                        for (var choice : chunk.choices()) {
                            ChatCompletionChunk.Delta delta = choice.delta();
                            String content = (delta != null) ? delta.content() : null;
                            if (content == null || content.isEmpty()) {
                                rewritten.add(choice);
                                continue;
                            }
                            // Billing and audit always use the raw upstream text, never the masked one.
                            if (tokenEstimator != null) {
                                generatedTokens.addAndGet(tokenEstimator.countTokens(content));
                            }
                            String emit = content;
                            if (guardSession != null) {
                                var result = guardSession.inspect(content);
                                if (result.blocked()) {
                                    throw new StreamingGuardrailException(result.violationType(), content);
                                }
                                // Hold-back semantics: only the safe prefix may leave the gateway.
                                emit = result.sanitizedText();
                            }
                            if (deMasker != null) {
                                emit = deMasker.transformChunk(emit);
                            }
                            if (!emit.isEmpty()) {
                                streamContentAcc.get().append(emit);
                            }
                            // A null content keeps the delta non-empty without emitting an empty
                            // text block; the withheld tail is flushed before [DONE].
                            rewritten.add(new ChatCompletionChunk.ChoiceChunk(
                                    choice.index(),
                                    new ChatCompletionChunk.Delta(delta.role(), emit.isEmpty() ? null : emit, delta.toolCalls()),
                                    choice.finishReason()));
                        }
                        outbound = new ChatCompletionChunk(chunk.id(), chunk.object(), chunk.created(),
                                chunk.model(), rewritten, chunk.usage());
                        if (isContentSuppressed(rewritten, chunk.usage())) {
                            return;
                        }
                    }
                    if (chunk.usage() != null && chunk.usage().totalTokens() != null) {
                        generatedTokens.set(chunk.usage().totalTokens());
                    }
                }
                String jsonChunk = objectMapper.writeValueAsString(outbound);
                emitter.send(SseEmitter.event().data(jsonChunk));
            } catch (Exception e) {
                log.warn("Failed or blocked during streaming output, aborting", e);
                emitter.completeWithError(e);
            }
        };

        Runnable completeRunnable = () -> {
            try {
                // Release whatever the output guardrail still holds back, so the client receives the
                // complete (masked) answer before the terminal event.
                if (guardSession != null || deMasker != null) {
                    String pending = (guardSession != null) ? guardSession.flush() : "";
                    if (deMasker != null) {
                        // The guardrail's tail must pass through the de-masker, and the de-masker may
                        // itself be sitting on a half-written placeholder.
                        pending = deMasker.transformChunk(pending) + deMasker.flush();
                    }
                    if (pending != null && !pending.isEmpty()) {
                        streamContentAcc.get().append(pending);
                        ChatCompletionChunk tailChunk = new ChatCompletionChunk(
                                "chatcmpl-flush-" + System.currentTimeMillis(),
                                "chat.completion.chunk",
                                System.currentTimeMillis() / 1000,
                                effectiveRequest.model(),
                                List.of(new ChatCompletionChunk.ChoiceChunk(
                                        0, new ChatCompletionChunk.Delta("assistant", pending), null)),
                                null);
                        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(tailChunk)));
                    }
                }

                int promptTokens = tokenEstimator != null ? tokenEstimator.estimatePromptTokens(effectiveRequest.messages()) : 0;
                int totalTokens = generatedTokens.get() > 0 ? (promptTokens + generatedTokens.get()) : promptTokens;
                if (tenantQuotaService != null && reservation != null) {
                    tenantQuotaService.settle(reservation, totalTokens);
                }
                if (cacheEngine != null && streamContentAcc.get().length() > 0 && !piiPresent) {
                    ChatCompletionResponse streamResponse = new ChatCompletionResponse(
                            "stream-cache-" + System.currentTimeMillis(),
                            "chat.completion",
                            System.currentTimeMillis() / 1000,
                            effectiveRequest.model(),
                            List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", streamContentAcc.get().toString()), "stop")),
                            new Usage(promptTokens, generatedTokens.get(), totalTokens)
                    );
                    // The SSE callbacks run on an OkHttp thread where TenantSecurityContext has
                    // already been cleared, so the tenant must be passed explicitly.
                    cacheEngine.put(effectiveRequest, streamResponse, tenantId);
                }

                if (sessionManager != null && request.sessionId() != null && !request.sessionId().isBlank()) {
                    List<ChatMessage> turnToPersist = new ArrayList<>();
                    if (request.messages() != null) {
                        for (ChatMessage m : request.messages()) {
                            if (!"system".equalsIgnoreCase(m.role())) {
                                turnToPersist.add(restoreMessage(m, privacyVault));
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

                if (costCalculator != null) {
                    double cost = costCalculator.calculateCostUsd(effectiveRequest.model(), promptTokens, generatedTokens.get());
                    span.setCostUsd(cost);
                    if (tenantBudgetManager != null && tenantId != null) {
                        tenantBudgetManager.recordSpend(tenantId, cost);
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
        };

        Consumer<Throwable> errorConsumer = ex -> {
            span.recordError(ex);
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
        };

        StreamCancellationHandle handle;
        if (effectiveRequest.tools() != null && !effectiveRequest.tools().isEmpty() && streamingAgentOrchestrator != null) {
            handle = streamingAgentOrchestrator.runStreamingLoop(
                    failoverRoutingService,
                    effectiveRequest,
                    tenantId,
                    5,
                    chunkConsumer,
                    agentEvent -> {
                        try {
                            String eventPayload = objectMapper.writeValueAsString(agentEvent);
                            emitter.send(SseEmitter.event().name(agentEvent.eventType()).data(eventPayload));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    },
                    completeRunnable,
                    errorConsumer
            );
        } else {
            handle = failoverRoutingService.streamChatCompletionWithFailover(
                    effectiveRequest,
                    chunkConsumer,
                    completeRunnable,
                    errorConsumer
            );
        }
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

    /**
     * Persists history in the user's own words: placeholders are meaningful only inside the request
     * that created them, so a stored {@code <PHONE_1>} would be unexplainable on the next turn.
     */
    private static ChatMessage restoreMessage(ChatMessage message,
                                              PrivacyVault vault) {
        if (message == null || vault == null || vault.isEmpty() || message.content() == null) {
            return message;
        }
        return new ChatMessage(message.role(), vault.restore(message.content()),
                message.toolCalls(), message.toolCallId());
    }

    /**
     * A chunk is dropped only when it carries nothing but content that the guardrail held back.
     * Chunks carrying a role, tool calls, a finish reason or usage must always reach the client.
     */
    private static boolean isContentSuppressed(List<ChatCompletionChunk.ChoiceChunk> choices, Usage usage) {
        if (usage != null || choices == null || choices.isEmpty()) {
            return false;
        }
        for (ChatCompletionChunk.ChoiceChunk choice : choices) {
            if (choice.finishReason() != null) {
                return false;
            }
            ChatCompletionChunk.Delta delta = choice.delta();
            if (delta == null) {
                continue;
            }
            if (delta.role() != null || delta.content() != null || delta.toolCalls() != null) {
                return false;
            }
        }
        return true;
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
