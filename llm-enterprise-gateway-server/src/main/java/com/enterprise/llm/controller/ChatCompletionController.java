package com.enterprise.llm.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.enterprise.llm.auth.TenantSecurityContext;
import com.enterprise.llm.cache.CacheEngine;
import com.enterprise.llm.exception.LlmException;
import com.enterprise.llm.guard.OutputGuardrailService;
import com.enterprise.llm.guard.PromptGuardrailService;
import com.enterprise.llm.guard.privacy.PrivacyDeMaskingTransformer;
import com.enterprise.llm.guard.privacy.PrivacyMaskingService;
import com.enterprise.llm.guard.privacy.PrivacyVault;
import com.enterprise.llm.metrics.MetricsRecordService;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.quota.QuotaExceededException;
import com.enterprise.llm.quota.QuotaReservation;
import com.enterprise.llm.quota.TenantQuotaService;
import com.enterprise.llm.rag.RagPipelineService;
import com.enterprise.llm.ratelimit.RateLimitResult;
import com.enterprise.llm.ratelimit.TenantRateLimiter;
import com.enterprise.llm.router.canary.CanaryRoutingService;
import com.enterprise.llm.service.ChatExecutionService;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.token.TokenEstimator;
import com.enterprise.llm.trace.LlmSpan;
import com.enterprise.llm.trace.RequestAuditLogger;
import com.enterprise.llm.trace.TraceCollector;
import com.enterprise.llm.trace.TraceContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/chat")
public class ChatCompletionController {

    private final ChatExecutionService chatExecutionService;
    private final PromptGuardrailService promptGuardrailService;
    private final OutputGuardrailService outputGuardrailService;
    private final TokenEstimator tokenEstimator;
    private final TenantQuotaService tenantQuotaService;
    private final CacheEngine cacheEngine;
    private final MetricsRecordService metricsRecordService;
    private final RagPipelineService ragPipelineService;
    private final TraceCollector traceCollector;
    private final TenantRateLimiter tenantRateLimiter;
    private final CanaryRoutingService canaryRoutingService;
    private final boolean privacyEnabled;
    private final PrivacyMaskingService privacyMaskingService;
    private final PrivacyDeMaskingTransformer privacyDeMaskingTransformer;

    @Autowired
    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService,
            OutputGuardrailService outputGuardrailService,
            TokenEstimator tokenEstimator,
            TenantQuotaService tenantQuotaService,
            CacheEngine cacheEngine,
            @Autowired(required = false) MetricsRecordService metricsRecordService,
            @Autowired(required = false) RagPipelineService ragPipelineService,
            @Autowired(required = false) TraceCollector traceCollector,
            @Autowired(required = false) TenantRateLimiter tenantRateLimiter,
            @Autowired(required = false) CanaryRoutingService canaryRoutingService,
            @Value("${llm.privacy.enabled:false}") boolean privacyEnabled,
            @Autowired(required = false) PrivacyMaskingService privacyMaskingService,
            @Autowired(required = false) PrivacyDeMaskingTransformer privacyDeMaskingTransformer
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
        this.canaryRoutingService = canaryRoutingService;
        this.privacyEnabled = privacyEnabled;
        this.privacyMaskingService = privacyMaskingService;
        this.privacyDeMaskingTransformer = privacyDeMaskingTransformer;
    }

    @PostMapping("/completions")
    public Object createChatCompletion(@RequestBody(required = false) ChatCompletionRequest request,
                                       HttpServletResponse httpResponse) {
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
                TraceContext traceCtx = TraceContext.getCurrent();
                LlmSpan span = new LlmSpan("chat_completion_guard_blocked", traceCtx, request.model());
                span.start(startMs);
                span.finish(System.currentTimeMillis(), 0, 0);
                if (traceCollector != null) {
                    traceCollector.collect(span);
                }
                RequestAuditLogger.logAudit(span, 400, "Security violation: Potential prompt injection detected");
                return ResponseEntity.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("error", "Security violation: Potential prompt injection detected"));
            }
        }

        // 2. Tenant resolution and inbound pseudonymisation. Prompt-injection checks above run on
        //    the original text on purpose: placeholders such as <PHONE_1> would blunt the rules.
        TenantEntity tenant = TenantSecurityContext.getCurrentTenant();
        String currentTenantId = tenant != null ? tenant.getId() : "default";
        ChatCompletionRequest effectiveRequest = request;
        PrivacyVault privacyVault = null;
        boolean piiPresent = false;
        if (privacyEnabled && privacyMaskingService != null) {
            privacyVault = new PrivacyVault();
            effectiveRequest = privacyMaskingService.maskRequest(effectiveRequest, privacyVault);
            piiPresent = !privacyVault.isEmpty();
        }

        // 3. Canary model resolution. This MUST run before the cache lookup and before RAG, because
        //    the cache key is derived from the model name: resolving after the lookup would let a
        //    request for model A be answered from an entry generated by model B.
        if (canaryRoutingService != null) {
            String routedModel = canaryRoutingService.resolveModel(
                    effectiveRequest.model(), currentTenantId, effectiveRequest.sessionId());
            if (routedModel != null && !routedModel.equals(effectiveRequest.model())) {
                effectiveRequest = effectiveRequest.withModel(routedModel);
                if (httpResponse != null) {
                    httpResponse.setHeader("X-Hub-Routed-Model", routedModel);
                }
            }
        }

        // 4. Pre-inference RAG Retrieval Pipeline (Tenant-isolated search + Context Augmentation)
        if (ragPipelineService != null) {
            effectiveRequest = ragPipelineService.processAndAugment(effectiveRequest, currentTenantId);
        }

        // 5. Check Two-Level Cache (L1/L2). Skipped whenever the prompt was pseudonymised: the cache
        //    key is derived from the (masked) prompt and a cached answer still carries placeholders
        //    that belong to a different request's vault, so a hit would leak "<PHONE_1>" verbatim.
        if (cacheEngine != null && !piiPresent) {
            var cached = cacheEngine.lookup(effectiveRequest, currentTenantId);
            if (cached.isPresent()) {
                long latency = System.currentTimeMillis() - startMs;
                int saved = (cached.get().usage() != null && cached.get().usage().totalTokens() != null) ? cached.get().usage().totalTokens() : 0;
                if (metricsRecordService != null) {
                    metricsRecordService.record(true, latency, 0, saved, true, false, false);
                }
                TraceContext traceCtx = TraceContext.getCurrent();
                LlmSpan span = new LlmSpan("chat_completion_cache", traceCtx, effectiveRequest.model());
                span.start(startMs);
                span.setCacheHit(true);
                span.finish(System.currentTimeMillis(), 0, 0);
                if (traceCollector != null) {
                    traceCollector.collect(span);
                }
                RequestAuditLogger.logAudit(span, 200);

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

        // 6. Pre-flight Token Quota Reservation & TPM Rate Limit Check
        QuotaReservation reservation = null;
        int estimatedTokens = 0;
        if (tenant != null) {
            estimatedTokens = tokenEstimator.estimateTotalReservationTokens(effectiveRequest.messages(), effectiveRequest.maxTokens());
            if (tenantRateLimiter != null) {
                RateLimitResult tpmCheck = tenantRateLimiter.tryAcquireTpm(tenant, estimatedTokens);
                if (tpmCheck != null && !tpmCheck.allowed()) {
                    if (metricsRecordService != null) {
                        metricsRecordService.record(false, System.currentTimeMillis() - startMs, 0, 0, false, false, true);
                    }
                    TraceContext traceCtx = TraceContext.getCurrent();
                    LlmSpan span = new LlmSpan("chat_completion_tpm_exceeded", traceCtx, effectiveRequest.model());
                    span.start(startMs);
                    span.finish(System.currentTimeMillis(), 0, 0);
                    if (traceCollector != null) {
                        traceCollector.collect(span);
                    }
                    RequestAuditLogger.logAudit(span, 429, "Rate limit or daily token quota exceeded: " + tpmCheck.rejectionReason());
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
                    TraceContext traceCtx = TraceContext.getCurrent();
                    LlmSpan span = new LlmSpan("chat_completion_quota_exceeded", traceCtx, effectiveRequest.model());
                    span.start(startMs);
                    span.finish(System.currentTimeMillis(), 0, 0);
                    if (traceCollector != null) {
                        traceCollector.collect(span);
                    }
                    RequestAuditLogger.logAudit(span, 429, "Rate limit or daily token quota exceeded");
                    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("status", 429, "error", "Rate limit or daily token quota exceeded"));
                }
            }
        }

        // 7. Execution
        if (Boolean.TRUE.equals(effectiveRequest.stream())) {
            return chatExecutionService.executeStream(effectiveRequest, reservation, privacyVault);
        } else {
            try {
                ChatCompletionResponse response = chatExecutionService.executeSync(effectiveRequest, reservation, privacyVault);
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
                // Reverse the pseudonymisation last: DLP must inspect the masked text (a placeholder
                // cannot match a PII pattern), then the user's own data is put back so the vault is
                // transparent to them.
                if (piiPresent && privacyDeMaskingTransformer != null) {
                    response = restoreResponse(response, privacyVault);
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

    private ChatCompletionResponse restoreResponse(ChatCompletionResponse response,
                                                   PrivacyVault vault) {
        if (response == null || response.choices() == null || vault == null) {
            return response;
        }
        List<ChatCompletionResponse.Choice> restored = response.choices().stream()
                .map(choice -> {
                    if (choice.message() != null && choice.message().content() != null) {
                        return new ChatCompletionResponse.Choice(
                                choice.index(),
                                new ChatMessage(choice.message().role(),
                                        privacyDeMaskingTransformer.demask(choice.message().content(), vault)),
                                choice.finishReason());
                    }
                    return choice;
                })
                .toList();
        return new ChatCompletionResponse(
                response.id(), response.object(), response.created(),
                response.model(), restored, response.usage());
    }

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Map<String, Object>> handleLlmException(LlmException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        Map<String, Object> errorMap = new LinkedHashMap<>();
        errorMap.put("message", ex.getMessage() != null ? ex.getMessage() : "Upstream LLM error");
        errorMap.put("type", ex.getErrorType() != null ? ex.getErrorType() : "llm_error");
        errorMap.put("code", ex.getErrorCode() != null ? ex.getErrorCode() : "upstream_error");
        errorMap.put("param", null);

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", errorMap));
    }
}
