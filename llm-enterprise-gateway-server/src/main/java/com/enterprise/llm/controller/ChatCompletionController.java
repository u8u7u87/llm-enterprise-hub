package com.enterprise.llm.controller;

import com.enterprise.llm.auth.TenantSecurityContext;
import com.enterprise.llm.cache.CacheEngine;
import com.enterprise.llm.guard.OutputGuardrailService;
import com.enterprise.llm.guard.PromptGuardrailService;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.quota.QuotaExceededException;
import com.enterprise.llm.quota.QuotaReservation;
import com.enterprise.llm.quota.TenantQuotaService;
import com.enterprise.llm.service.ChatExecutionService;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.token.TokenEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.enterprise.llm.exception.LlmException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/chat")
public class ChatCompletionController {

    private final ChatExecutionService chatExecutionService;
    private final PromptGuardrailService promptGuardrailService;
    private final OutputGuardrailService outputGuardrailService;
    private final TokenEstimator tokenEstimator;
    private final TenantQuotaService tenantQuotaService;
    private final CacheEngine cacheEngine;
    private final com.enterprise.llm.metrics.MetricsRecordService metricsRecordService;
    private final com.enterprise.llm.rag.RagPipelineService ragPipelineService;
    private final com.enterprise.llm.trace.TraceCollector traceCollector;
    private final com.enterprise.llm.ratelimit.TenantRateLimiter tenantRateLimiter;

    @Autowired
    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService,
            OutputGuardrailService outputGuardrailService,
            TokenEstimator tokenEstimator,
            TenantQuotaService tenantQuotaService,
            CacheEngine cacheEngine,
            @Autowired(required = false) com.enterprise.llm.metrics.MetricsRecordService metricsRecordService,
            @Autowired(required = false) com.enterprise.llm.rag.RagPipelineService ragPipelineService,
            @Autowired(required = false) com.enterprise.llm.trace.TraceCollector traceCollector,
            @Autowired(required = false) com.enterprise.llm.ratelimit.TenantRateLimiter tenantRateLimiter
    ) {
        this.chatExecutionService = chatExecutionService;
        this.promptGuardrailService = promptGuardrailService;
        this.outputGuardrailService = outputGuardrailService;
        this.tokenEstimator = tokenEstimator;
        this.tenantQuotaService = tenantQuotaService;
        this.cacheEngine = cacheEngine;
        this.metricsRecordService = metricsRecordService;
        this.ragPipelineService = ragPipelineService;
        this.traceCollector = traceCollector;
        this.tenantRateLimiter = tenantRateLimiter;
    }

    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService,
            OutputGuardrailService outputGuardrailService,
            TokenEstimator tokenEstimator,
            TenantQuotaService tenantQuotaService,
            CacheEngine cacheEngine,
            com.enterprise.llm.metrics.MetricsRecordService metricsRecordService
    ) {
        this(chatExecutionService, promptGuardrailService, outputGuardrailService, tokenEstimator, tenantQuotaService, cacheEngine, metricsRecordService, null, null, null);
    }

    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService,
            OutputGuardrailService outputGuardrailService,
            TokenEstimator tokenEstimator,
            TenantQuotaService tenantQuotaService,
            CacheEngine cacheEngine
    ) {
        this(chatExecutionService, promptGuardrailService, outputGuardrailService, tokenEstimator, tenantQuotaService, cacheEngine, null, null, null, null);
    }

    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService
    ) {
        this(chatExecutionService, promptGuardrailService, new OutputGuardrailService(), new TokenEstimator(), null, null, null, null, null, null);
    }

    @PostMapping("/completions")
    public Object createChatCompletion(@RequestBody(required = false) ChatCompletionRequest request) {
        long startMs = System.currentTimeMillis();

        // 0. Boundary & Schema Validation
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "Invalid request: messages must not be empty"));
        }

        // 1. Validate prompt safety against injections
        for (var msg : request.messages()) {
            if (!promptGuardrailService.isSafe(msg.content())) {
                if (metricsRecordService != null) {
                    metricsRecordService.record(false, System.currentTimeMillis() - startMs, 0, 0, false, false, true);
                }
                com.enterprise.llm.trace.TraceContext traceCtx = com.enterprise.llm.trace.TraceContext.getCurrent();
                com.enterprise.llm.trace.LlmSpan span = new com.enterprise.llm.trace.LlmSpan("chat_completion_guard_blocked", traceCtx, request.model());
                span.start(startMs);
                span.finish(System.currentTimeMillis(), 0, 0);
                if (traceCollector != null) {
                    traceCollector.collect(span);
                }
                com.enterprise.llm.trace.RequestAuditLogger.logAudit(span, 400, "Security violation: Potential prompt injection detected");
                return ResponseEntity.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("error", "Security violation: Potential prompt injection detected"));
            }
        }

        // 2. Pre-inference RAG Retrieval Pipeline (Tenant-isolated search + Context Augmentation)
        TenantEntity tenant = TenantSecurityContext.getCurrentTenant();
        String currentTenantId = tenant != null ? tenant.getId() : "default";
        ChatCompletionRequest effectiveRequest = request;
        if (ragPipelineService != null) {
            effectiveRequest = ragPipelineService.processAndAugment(request, currentTenantId);
        }

        // 3. Check Two-Level Cache (L1/L2)
        if (cacheEngine != null) {
            var cached = cacheEngine.lookup(effectiveRequest);
            if (cached.isPresent()) {
                long latency = System.currentTimeMillis() - startMs;
                int saved = (cached.get().usage() != null && cached.get().usage().totalTokens() != null) ? cached.get().usage().totalTokens() : 0;
                if (metricsRecordService != null) {
                    metricsRecordService.record(true, latency, 0, saved, true, false, false);
                }
                com.enterprise.llm.trace.TraceContext traceCtx = com.enterprise.llm.trace.TraceContext.getCurrent();
                com.enterprise.llm.trace.LlmSpan span = new com.enterprise.llm.trace.LlmSpan("chat_completion_cache", traceCtx, effectiveRequest.model());
                span.start(startMs);
                span.setCacheHit(true);
                span.finish(System.currentTimeMillis(), 0, 0);
                if (traceCollector != null) {
                    traceCollector.collect(span);
                }
                com.enterprise.llm.trace.RequestAuditLogger.logAudit(span, 200);

                if (Boolean.TRUE.equals(effectiveRequest.stream())) {
                    return chatExecutionService.emitCachedStream(cached.get());
                } else {
                    return ResponseEntity.ok()
                            .header("X-Cache-Lookup", "HIT")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(cached.get());
                }
            }
        }

        // 4. Pre-flight Token Quota Reservation & TPM Rate Limit Check
        QuotaReservation reservation = null;
        int estimatedTokens = 0;
        if (tenant != null) {
            estimatedTokens = tokenEstimator.estimateTotalReservationTokens(effectiveRequest.messages(), effectiveRequest.maxTokens());
            if (tenantRateLimiter != null) {
                com.enterprise.llm.ratelimit.RateLimitResult tpmCheck = tenantRateLimiter.tryAcquireTpm(tenant, estimatedTokens);
                if (tpmCheck != null && !tpmCheck.allowed()) {
                    if (metricsRecordService != null) {
                        metricsRecordService.record(false, System.currentTimeMillis() - startMs, 0, 0, false, false, true);
                    }
                    com.enterprise.llm.trace.TraceContext traceCtx = com.enterprise.llm.trace.TraceContext.getCurrent();
                    com.enterprise.llm.trace.LlmSpan span = new com.enterprise.llm.trace.LlmSpan("chat_completion_tpm_exceeded", traceCtx, effectiveRequest.model());
                    span.start(startMs);
                    span.finish(System.currentTimeMillis(), 0, 0);
                    if (traceCollector != null) {
                        traceCollector.collect(span);
                    }
                    com.enterprise.llm.trace.RequestAuditLogger.logAudit(span, 429, "Rate limit or daily token quota exceeded: " + tpmCheck.rejectionReason());
                    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Retry-After", String.valueOf(Math.max(1, tpmCheck.retryAfterMs() / 1000)))
                            .body(Map.of("status", 429, "error", "Rate limit or daily token quota exceeded: " + tpmCheck.rejectionReason()));
                }
            }

            if (tenantQuotaService != null) {
                try {
                    reservation = tenantQuotaService.tryReserve(tenant.getId(), estimatedTokens);
                } catch (QuotaExceededException e) {
                    if (metricsRecordService != null) {
                        metricsRecordService.record(false, System.currentTimeMillis() - startMs, 0, 0, false, false, true);
                    }
                    com.enterprise.llm.trace.TraceContext traceCtx = com.enterprise.llm.trace.TraceContext.getCurrent();
                    com.enterprise.llm.trace.LlmSpan span = new com.enterprise.llm.trace.LlmSpan("chat_completion_quota_exceeded", traceCtx, effectiveRequest.model());
                    span.start(startMs);
                    span.finish(System.currentTimeMillis(), 0, 0);
                    if (traceCollector != null) {
                        traceCollector.collect(span);
                    }
                    com.enterprise.llm.trace.RequestAuditLogger.logAudit(span, 429, "Rate limit or daily token quota exceeded");
                    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("status", 429, "error", "Rate limit or daily token quota exceeded"));
                }
            }
        }

        // 5. Execution
        if (Boolean.TRUE.equals(effectiveRequest.stream())) {
            return chatExecutionService.executeStream(effectiveRequest, reservation);
        } else {
            try {
                ChatCompletionResponse response = chatExecutionService.executeSync(effectiveRequest, reservation);
                long latency = System.currentTimeMillis() - startMs;
                int usedTokens = (response != null && response.usage() != null && response.usage().totalTokens() != null) ? response.usage().totalTokens() : 0;
                if (tenant != null && tenantRateLimiter != null) {
                    tenantRateLimiter.recordUsage(tenant, usedTokens, estimatedTokens);
                }
                if (metricsRecordService != null) {
                    metricsRecordService.record(true, latency, usedTokens, 0, false, false, false);
                }
                if (response != null && response.choices() != null && outputGuardrailService != null) {
                    List<ChatCompletionResponse.Choice> sanitizedChoices = response.choices().stream()
                            .map(choice -> {
                                if (choice.message() != null && choice.message().content() != null) {
                                    String clean = outputGuardrailService.sanitize(choice.message().content());
                                    return new ChatCompletionResponse.Choice(
                                            choice.index(),
                                            new ChatMessage(choice.message().role(), clean),
                                            choice.finishReason()
                                    );
                                }
                                return choice;
                            })
                            .toList();
                    response = new ChatCompletionResponse(
                            response.id(), response.object(), response.created(),
                            response.model(), sanitizedChoices, response.usage()
                    );
                }
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(response);
            } catch (LlmException ex) {
                if (metricsRecordService != null) {
                    metricsRecordService.record(false, System.currentTimeMillis() - startMs, 0, 0, false, false, false);
                }
                return handleLlmException(ex);
            } catch (Exception ex) {
                if (metricsRecordService != null) {
                    metricsRecordService.record(false, System.currentTimeMillis() - startMs, 0, 0, false, false, false);
                }
                throw ex;
            }
        }
    }

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Map<String, Object>> handleLlmException(LlmException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        Map<String, Object> errorMap = new java.util.LinkedHashMap<>();
        errorMap.put("message", ex.getMessage() != null ? ex.getMessage() : "Upstream LLM error");
        errorMap.put("type", ex.getErrorType() != null ? ex.getErrorType() : "llm_error");
        errorMap.put("code", ex.getErrorCode() != null ? ex.getErrorCode() : "upstream_error");
        errorMap.put("param", null);

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", errorMap));
    }
}
