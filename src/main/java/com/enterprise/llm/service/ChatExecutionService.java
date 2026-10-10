package com.enterprise.llm.service;

import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.quota.QuotaReservation;
import com.enterprise.llm.quota.TenantQuotaService;
import com.enterprise.llm.router.FailoverRoutingService;
import com.enterprise.llm.token.TokenEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ChatExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ChatExecutionService.class);

    private final FailoverRoutingService failoverRoutingService;
    private final TenantQuotaService tenantQuotaService;
    private final TokenEstimator tokenEstimator;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChatExecutionService(
            FailoverRoutingService failoverRoutingService,
            TenantQuotaService tenantQuotaService,
            TokenEstimator tokenEstimator
    ) {
        this.failoverRoutingService = failoverRoutingService;
        this.tenantQuotaService = tenantQuotaService;
        this.tokenEstimator = tokenEstimator;
        this.objectMapper = new ObjectMapper();
    }

    public ChatExecutionService(FailoverRoutingService failoverRoutingService) {
        this(failoverRoutingService, null, new TokenEstimator());
    }

    public ChatCompletionResponse executeSync(ChatCompletionRequest request) {
        return executeSync(request, null);
    }

    public ChatCompletionResponse executeSync(ChatCompletionRequest request, QuotaReservation reservation) {
        try {
            ChatCompletionResponse response = failoverRoutingService.chatCompletionWithFailover(request);
            if (tenantQuotaService != null && reservation != null) {
                int actualTokens = 0;
                if (response != null && response.usage() != null && response.usage().totalTokens() != null) {
                    actualTokens = response.usage().totalTokens();
                } else if (tokenEstimator != null && response != null) {
                    int promptTokens = tokenEstimator.estimatePromptTokens(request.messages());
                    int completionTokens = (response.choices() != null && !response.choices().isEmpty() && response.choices().get(0).message() != null)
                            ? tokenEstimator.countTokens(response.choices().get(0).message().content()) : 0;
                    actualTokens = promptTokens + completionTokens;
                }
                tenantQuotaService.settle(reservation, actualTokens);
            }
            return response;
        } catch (Exception e) {
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
        final StreamCancellationHandle[] handleHolder = new StreamCancellationHandle[1];
        final AtomicInteger generatedTokens = new AtomicInteger(0);

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
                        if (chunk != null) {
                            if (chunk.usage() != null && chunk.usage().totalTokens() != null) {
                                generatedTokens.set(chunk.usage().totalTokens());
                            } else if (tokenEstimator != null && chunk.choices() != null) {
                                for (var choice : chunk.choices()) {
                                    if (choice.delta() != null && choice.delta().content() != null) {
                                        generatedTokens.addAndGet(tokenEstimator.countTokens(choice.delta().content()));
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
                        if (tenantQuotaService != null && reservation != null) {
                            int promptTokens = tokenEstimator != null ? tokenEstimator.estimatePromptTokens(request.messages()) : 0;
                            int totalTokens = generatedTokens.get() > 0 ? (promptTokens + generatedTokens.get()) : promptTokens;
                            tenantQuotaService.settle(reservation, totalTokens);
                        }
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                ex -> {
                    if (tenantQuotaService != null && reservation != null) {
                        int promptTokens = tokenEstimator != null ? tokenEstimator.estimatePromptTokens(request.messages()) : 0;
                        int totalTokens = generatedTokens.get() > 0 ? (promptTokens + generatedTokens.get()) : 0;
                        if (totalTokens > 0) {
                            tenantQuotaService.settle(reservation, totalTokens);
                        } else {
                            tenantQuotaService.release(reservation);
                        }
                    }
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
}
