package com.enterprise.llm.controller;

import com.enterprise.llm.auth.TenantSecurityContext;
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

    @Autowired
    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService,
            OutputGuardrailService outputGuardrailService,
            TokenEstimator tokenEstimator,
            TenantQuotaService tenantQuotaService
    ) {
        this.chatExecutionService = chatExecutionService;
        this.promptGuardrailService = promptGuardrailService;
        this.outputGuardrailService = outputGuardrailService;
        this.tokenEstimator = tokenEstimator;
        this.tenantQuotaService = tenantQuotaService;
    }

    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            PromptGuardrailService promptGuardrailService
    ) {
        this(chatExecutionService, promptGuardrailService, new OutputGuardrailService(), new TokenEstimator(), null);
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
            ChatCompletionResponse response = chatExecutionService.executeSync(request, reservation);
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
        }
    }
}
