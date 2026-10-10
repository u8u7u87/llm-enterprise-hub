package com.enterprise.llm.service;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.router.FailoverRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Service
public class ChatExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ChatExecutionService.class);

    private final FailoverRoutingService failoverRoutingService;
    private final ObjectMapper objectMapper;

    public ChatExecutionService(FailoverRoutingService failoverRoutingService) {
        this.failoverRoutingService = failoverRoutingService;
        this.objectMapper = new ObjectMapper();
    }

    public ChatCompletionResponse executeSync(ChatCompletionRequest request) {
        return failoverRoutingService.chatCompletionWithFailover(request);
    }

    public SseEmitter executeStream(ChatCompletionRequest request) {
        // SSE timeout: 2 minutes
        final StreamCancellationHandle[] handleHolder = new StreamCancellationHandle[1];

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
                        String jsonChunk = objectMapper.writeValueAsString(chunk);
                        emitter.send(SseEmitter.event().data(jsonChunk));
                    } catch (IOException e) {
                        log.warn("Failed to push chunk to client emitter, aborting", e);
                        emitter.completeWithError(e);
                    }
                },
                () -> {
                    try {
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError
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
}
