package com.enterprise.llm.controller;

import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.service.ChatExecutionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1/chat")
public class ChatCompletionController {

    private final ChatExecutionService chatExecutionService;
    private final com.enterprise.llm.guard.PromptGuardrailService promptGuardrailService;

    public ChatCompletionController(
            ChatExecutionService chatExecutionService,
            com.enterprise.llm.guard.PromptGuardrailService promptGuardrailService
    ) {
        this.chatExecutionService = chatExecutionService;
        this.promptGuardrailService = promptGuardrailService;
    }

    @PostMapping("/completions")
    public Object createChatCompletion(@RequestBody ChatCompletionRequest request) {
        // Validate prompt safety against injections
        if (request.messages() != null) {
            for (var msg : request.messages()) {
                if (!promptGuardrailService.isSafe(msg.content())) {
                    return ResponseEntity.badRequest()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of("error", "Security violation: Potential prompt injection detected"));
                }
            }
        }

        if (Boolean.TRUE.equals(request.stream())) {
            return chatExecutionService.executeStream(request);
        } else {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(chatExecutionService.executeSync(request));
        }
    }
}
