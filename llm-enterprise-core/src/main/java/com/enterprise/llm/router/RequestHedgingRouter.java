package com.enterprise.llm.router;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 投机对冲竞速路由引擎 (Request Hedging / Speculative Racing)
 * 当主模型在 hedgingDelayMs 内未返回响应或首字时，自动在虚拟线程中并行触发备用模型对冲，先出结果者胜出并取消另一路。
 */
public class RequestHedgingRouter {

    private static final Logger log = LoggerFactory.getLogger(RequestHedgingRouter.class);

    private final LlmClient primaryClient;
    private final LlmClient fallbackClient;
    private final String fallbackModel;
    private final long hedgingDelayMs;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hedging-scheduler");
        t.setDaemon(true);
        return t;
    });

    public RequestHedgingRouter(
            LlmClient primaryClient,
            LlmClient fallbackClient,
            String fallbackModel,
            long hedgingDelayMs
    ) {
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
        this.fallbackModel = fallbackModel;
        this.hedgingDelayMs = hedgingDelayMs;
    }

    public RequestHedgingRouter(LlmClient primaryClient, LlmClient fallbackClient, String fallbackModel) {
        this(primaryClient, fallbackClient, fallbackModel, 1500L);
    }

    public ChatCompletionResponse chatCompletionWithHedging(ChatCompletionRequest request) {
        CompletableFuture<ChatCompletionResponse> resultFuture = new CompletableFuture<>();
        ExecutorService vExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 1. 主模型在虚拟线程中执行
        Future<?> primaryTask = vExecutor.submit(() -> {
            try {
                ChatCompletionResponse resp = primaryClient.chatCompletion(request);
                resultFuture.complete(resp);
            } catch (Throwable t) {
                // 如果主模型直接失败且尚未有结果，立即拉起备用模型
                if (!resultFuture.isDone()) {
                    try {
                        ChatCompletionRequest fbReq = createFallbackRequest(request, false);
                        ChatCompletionResponse fbResp = fallbackClient.chatCompletion(fbReq);
                        resultFuture.complete(fbResp);
                    } catch (Throwable fallbackErr) {
                        resultFuture.completeExceptionally(fallbackErr);
                    }
                }
            }
        });

        // 2. 调度对冲任务
        ScheduledFuture<?> hedgeTask = scheduler.schedule(() -> {
            if (!resultFuture.isDone()) {
                log.warn("Hedging delay elapsed ({}ms). Dispatching speculative hedge request to fallback model {}",
                        hedgingDelayMs, fallbackModel);
                vExecutor.submit(() -> {
                    try {
                        ChatCompletionRequest fbReq = createFallbackRequest(request, false);
                        ChatCompletionResponse resp = fallbackClient.chatCompletion(fbReq);
                        resultFuture.complete(resp);
                    } catch (Throwable t) {
                        if (!resultFuture.isDone()) {
                            resultFuture.completeExceptionally(t);
                        }
                    }
                });
            }
        }, hedgingDelayMs, TimeUnit.MILLISECONDS);

        try {
            ChatCompletionResponse response = resultFuture.get(60, TimeUnit.SECONDS);
            hedgeTask.cancel(true);
            primaryTask.cancel(true);
            return response;
        } catch (Exception e) {
            throw new RuntimeException("Hedging execution failed: " + e.getMessage(), e);
        } finally {
            vExecutor.shutdown();
        }
    }

    public StreamCancellationHandle streamChatCompletionWithHedging(
            ChatCompletionRequest request,
            Consumer<ChatCompletionChunk> onChunk,
            Runnable onComplete,
            Consumer<Throwable> onError
    ) {
        AtomicBoolean won = new AtomicBoolean(false);
        AtomicReference<String> winner = new AtomicReference<>(null);
        StreamCancellationHandle[] primaryHandle = new StreamCancellationHandle[1];
        StreamCancellationHandle[] fallbackHandle = new StreamCancellationHandle[1];

        // 1. 发起主模型流式调用
        primaryHandle[0] = primaryClient.streamChatCompletion(
                request,
                chunk -> {
                    if (won.compareAndSet(false, true)) {
                        winner.set("PRIMARY");
                        log.info("Primary stream won TTFT race");
                        if (fallbackHandle[0] != null) {
                            fallbackHandle[0].cancel();
                        }
                    }
                    if ("PRIMARY".equals(winner.get())) {
                        onChunk.accept(chunk);
                    }
                },
                () -> {
                    if ("PRIMARY".equals(winner.get())) {
                        onComplete.run();
                    }
                },
                err -> {
                    if ("PRIMARY".equals(winner.get())) {
                        onError.accept(err);
                    }
                }
        );

        // 2. 调度延时投机对冲
        ScheduledFuture<?> hedgeFuture = scheduler.schedule(() -> {
            if (!won.get()) {
                log.warn("Stream TTFT threshold exceeded ({}ms). Launching speculative fallback stream", hedgingDelayMs);
                ChatCompletionRequest fbReq = createFallbackRequest(request, true);
                fallbackHandle[0] = fallbackClient.streamChatCompletion(
                        fbReq,
                        chunk -> {
                            if (won.compareAndSet(false, true)) {
                                winner.set("FALLBACK");
                                log.warn("Fallback stream won TTFT race! Cancelling primary stream");
                                if (primaryHandle[0] != null) {
                                    primaryHandle[0].cancel();
                                }
                            }
                            if ("FALLBACK".equals(winner.get())) {
                                onChunk.accept(chunk);
                            }
                        },
                        () -> {
                            if ("FALLBACK".equals(winner.get())) {
                                onComplete.run();
                            }
                        },
                        err -> {
                            if ("FALLBACK".equals(winner.get())) {
                                onError.accept(err);
                            }
                        }
                );
            }
        }, hedgingDelayMs, TimeUnit.MILLISECONDS);

        return new StreamCancellationHandle(() -> {
            hedgeFuture.cancel(true);
            if (primaryHandle[0] != null) {
                primaryHandle[0].cancel();
            }
            if (fallbackHandle[0] != null) {
                fallbackHandle[0].cancel();
            }
        });
    }

    private ChatCompletionRequest createFallbackRequest(ChatCompletionRequest request, boolean stream) {
        return new ChatCompletionRequest(
                fallbackModel,
                request.messages(),
                stream,
                request.temperature(),
                request.maxTokens(),
                request.tools(),
                request.toolChoice()
        );
    }
}
