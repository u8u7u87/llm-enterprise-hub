package com.enterprise.llm.controller;

import com.enterprise.llm.auth.TenantSecurityContext;
import com.enterprise.llm.guard.PromptGuardrailService;
import com.enterprise.llm.model.ChatCompletionRequest;
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

import java.util.Map;

@RestController
@RequestMapping("/v1/chat")
public class ChatCompletionController {

    private final ChatExecutionService chatExecutionService;
    private final PromptGuardrailService promptGuardrailService;
    private final TokenEstimator tokenEstimator;
    private final TenantQuotaService tenantQuotaService;

    @Autowired
    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService,
            TokenEstimator tokenEstimator,
            TenantQuotaService tenantQuotaService
    ) {
        this.chatExecutionService = chatExecutionService;
        this.promptGuardrailService = promptGuardrailService;
        this.tokenEstimator = tokenEstimator;
        this.tenantQuotaService = tenantQuotaService;
    }

    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService
    ) {
        this(chatExecutionService, promptGuardrailService, new TokenEstimator(), null);
    }

    @PostMapping("/completions")
    public Object createChatCompletion(@RequestBody ChatCompletionRequest request) {
        // 1. Validate prompt safety against injections
        if (request.messages() != null) {
            for (var msg : request.messages()) {
                if (!promptGuardrailService.isSafe(msg.content())) {
                    return ResponseEntity.badRequest()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("error", "Security violation: Potential prompt injection detected"));
                }
            }
        }

        // 2. Pre-flight Token Quota Reservation
        TenantEntity tenant = TenantSecurityContext.getCurrentTenant();
        QuotaReservation reservation = null;
        if (tenant != null && tenantQuotaService != null) {
            int estimated = tokenEstimator.estimateTotalReservationTokens(request.messages(), request.maxTokens());
            try {
                reservation = tenantQuotaService.tryReserve(tenant.getId(), estimated);
            } catch (QuotaExceededException e) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("status", 429, "error", "Rate limit or daily token quota exceeded"));
            }
        }

        // 3. Execution
        if (Boolean.TRUE.equals(request.stream())) {
            return chatExecutionService.executeStream(request, reservation);
        } else {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(chatExecutionService.executeSync(request, reservation));
        }
    }
}
