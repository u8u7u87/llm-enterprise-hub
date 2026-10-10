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

    public ChatCompletionController(ChatExecutionService chatExecutionService) {
        this.chatExecutionService = chatExecutionService;
    }

    @PostMapping("/completions")
    public Object createChatCompletion(@RequestBody ChatCompletionRequest request) {
        if (Boolean.TRUE.equals(request.stream())) {
            return chatExecutionService.executeStream(request);
        } else {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(chatExecutionService.executeSync(request));
        }
    }
}
