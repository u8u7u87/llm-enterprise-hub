package com.enterprise.llm.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import com.enterprise.llm.cluster.ApiKeyEntry;
import com.enterprise.llm.cluster.ApiKeyPool;
import com.enterprise.llm.exception.LlmException;
import com.enterprise.llm.exception.LlmRateLimitException;
import com.enterprise.llm.exception.LlmServerException;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.trace.TraceContext;
import com.enterprise.llm.trace.TraceContextPropagator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final String DONE_TOKEN = "[DONE]";

    /** 上游未给出可用 Retry-After 时的默认密钥冷却时长（秒）。 */
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 60L;

    private final String baseUrl;
    private final String apiKey;
    private final ApiKeyPool apiKeyPool;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService virtualThreadExecutor;

    public OpenAiCompatibleLlmClient(String baseUrl, ApiKeyPool apiKeyPool) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKeyPool = apiKeyPool != null ? apiKeyPool : new ApiKeyPool(Collections.singletonList(""));
        this.apiKey = this.apiKeyPool.acquireKey().getKey();
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .executor(this.virtualThreadExecutor)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public OpenAiCompatibleLlmClient(String baseUrl, String apiKey) {
        this(baseUrl, ApiKeyPool.of(apiKey));
    }

    @Override
    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
        int maxAttempts = Math.min(Math.max(1, apiKeyPool.size()), 3);
        int attempt = 0;

        while (attempt < maxAttempts) {
            attempt++;
            ApiKeyEntry keyEntry = apiKeyPool.acquireKey();
            String currentKey = keyEntry.getKey();

            try {
                String requestBody = objectMapper.writeValueAsString(request);
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + currentKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

                // Inject W3C Distributed TraceContext & X-Request-ID
                TraceContextPropagator.inject(reqBuilder, TraceContext.getCurrent());

                HttpRequest httpRequest = reqBuilder.build();
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() == 429) {
                    long retryAfterSec = DEFAULT_RETRY_AFTER_SECONDS;
                    var header = response.headers().firstValue("Retry-After");
                    if (header.isPresent()) {
                        try {
                            retryAfterSec = Long.parseLong(header.get().trim());
                        } catch (NumberFormatException e) {
                            // Retry-After 允许是 HTTP-date，此时无法换算成秒，沿用默认冷却时长
                            log.debug("Unparsable Retry-After header [{}], falling back to {}s",
                                    header.get(), DEFAULT_RETRY_AFTER_SECONDS);
                        }
                    }
                    apiKeyPool.recordRateLimit(currentKey, retryAfterSec);
                    if (attempt < maxAttempts && apiKeyPool.getAvailableCount() > 0) {
                        log.warn("API key rate-limited (429). Rotating to next available key (attempt {}/{})", attempt, maxAttempts);
                        continue;
                    }
                    throw LlmErrorParser.parse(response.statusCode(), response.body(), response.headers());
                }

                if (response.statusCode() >= 400) {
                    throw LlmErrorParser.parse(response.statusCode(), response.body(), response.headers());
                }

                apiKeyPool.recordSuccess(currentKey);
                return objectMapper.readValue(response.body(), ChatCompletionResponse.class);
            } catch (LlmRateLimitException rle) {
                if (attempt < maxAttempts && apiKeyPool.getAvailableCount() > 0) {
                    continue;
                }
                throw rle;
            } catch (LlmException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to execute sync chatCompletion", e);
                throw new LlmServerException("Chat completion failed: " + e.getMessage(), e, 500);
            }
        }

        throw new LlmRateLimitException(60, "All API keys in pool exhausted with rate-limits");
    }

    @Override
    public StreamCancellationHandle streamChatCompletion(
            ChatCompletionRequest request,
            Consumer<ChatCompletionChunk> onChunk,
            Runnable onComplete,
            Consumer<Throwable> onError
    ) {
        CompletableFuture<HttpResponse<InputStream>> futureResponse;
        InputStream[] streamHolder = new InputStream[1];

        StreamCancellationHandle handle = new StreamCancellationHandle(() -> {
            log.info("Cancelling upstream SSE stream request");
            try {
                if (streamHolder[0] != null) {
                    streamHolder[0].close();
                }
            } catch (IOException e) {
                // 关闭失败说明连接已经断开或已被上游回收，取消动作本身已经生效
                log.debug("Failed to close upstream stream during cancellation: {}", e.getMessage());
            }
        });

        virtualThreadExecutor.submit(() -> {
            ApiKeyEntry keyEntry = apiKeyPool.acquireKey();
            String currentKey = keyEntry.getKey();

            try {
                ChatCompletionRequest streamRequest = new ChatCompletionRequest(
                        request.model(),
                        request.messages(),
                        true,
                        request.temperature()
                );
                String requestBody = objectMapper.writeValueAsString(streamRequest);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .header("Authorization", "Bearer " + currentKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

                // Inject W3C Distributed TraceContext & X-Request-ID
                TraceContextPropagator.inject(reqBuilder, TraceContext.getCurrent());

                HttpRequest httpRequest = reqBuilder.build();
                HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 400) {
                    if (response.statusCode() == 429) {
                        apiKeyPool.recordRateLimit(currentKey, DEFAULT_RETRY_AFTER_SECONDS);
                    }
                    String errBody = "";
                    try (InputStream is = response.body()) {
                        errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        // 读取错误体失败不影响错误分类：仍按状态码抛出，只是少了上游原始错误详情
                        log.debug("Failed to read error body (HTTP {}): {}", response.statusCode(), e.getMessage());
                    }
                    onError.accept(LlmErrorParser.parse(response.statusCode(), errBody, response.headers()));
                    return;
                }

                InputStream bodyStream = response.body();
                streamHolder[0] = bodyStream;

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {
                    String line;
                    while (!handle.isCancelled() && (line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith(":")) {
                            continue;
                        }

                        if (line.startsWith("data:")) {
                            String dataPayload = line.substring(5).trim();
                            if (DONE_TOKEN.equals(dataPayload)) {
                                break;
                            }
                            try {
                                ChatCompletionChunk chunk = objectMapper.readValue(dataPayload, ChatCompletionChunk.class);
                                onChunk.accept(chunk);
                            } catch (Exception parseEx) {
                                log.warn("Failed to parse SSE chunk payload: {}", dataPayload, parseEx);
                            }
                        }
                    }
                }

                if (!handle.isCancelled()) {
                    onComplete.run();
                }
            } catch (Exception e) {
                if (handle.isCancelled()) {
                    log.debug("Stream aborted by client handle.");
                } else {
                    log.error("Error reading LLM SSE stream", e);
                    onError.accept(e);
                }
            }
        });

        return handle;
    }
}
