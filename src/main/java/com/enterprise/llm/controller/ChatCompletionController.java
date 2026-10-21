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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @Autowired
    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService,
            OutputGuardrailService outputGuardrailService,
            TokenEstimator tokenEstimator,
            TenantQuotaService tenantQuotaService,
            CacheEngine cacheEngine,
            @Autowired(required = false) com.enterprise.llm.metrics.MetricsRecordService metricsRecordService,
            @Autowired(required = false) com.enterprise.llm.rag.RagPipelineService ragPipelineService
    ) {
        this.chatExecutionService = chatExecutionService;
        this.promptGuardrailService = promptGuardrailService;
        this.outputGuardrailService = outputGuardrailService;
        this.tokenEstimator = tokenEstimator;
        this.tenantQuotaService = tenantQuotaService;
        this.cacheEngine = cacheEngine;
        this.metricsRecordService = metricsRecordService;
        this.ragPipelineService = ragPipelineService;
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
        this(chatExecutionService, promptGuardrailService, outputGuardrailService, tokenEstimator, tenantQuotaService, cacheEngine, metricsRecordService, null);
    }

    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService,
            OutputGuardrailService outputGuardrailService,
            TokenEstimator tokenEstimator,
            TenantQuotaService tenantQuotaService,
            CacheEngine cacheEngine
    ) {
        this(chatExecutionService, promptGuardrailService, outputGuardrailService, tokenEstimator, tenantQuotaService, cacheEngine, null, null);
    }

    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService
    ) {
        this(chatExecutionService, promptGuardrailService, new OutputGuardrailService(), new TokenEstimator(), null, null, null, null);
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

        // 4. Pre-flight Token Quota Reservation (Accurately covers augmented RAG tokens)
        QuotaReservation reservation = null;
        if (tenant != null && tenantQuotaService != null) {
            int estimated = tokenEstimator.estimateTotalReservationTokens(effectiveRequest.messages(), effectiveRequest.maxTokens());
            try {
                reservation = tenantQuotaService.tryReserve(tenant.getId(), estimated);
            } catch (QuotaExceededException e) {
                if (metricsRecordService != null) {
                    metricsRecordService.record(false, System.currentTimeMillis() - startMs, 0, 0, false, false, true);
                }
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("status", 429, "error", "Rate limit or daily token quota exceeded"));
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
            } catch (Exception ex) {
                if (metricsRecordService != null) {
                    metricsRecordService.record(false, System.currentTimeMillis() - startMs, 0, 0, false, false, false);
                }
                throw ex;
            }
        }
    }
}
