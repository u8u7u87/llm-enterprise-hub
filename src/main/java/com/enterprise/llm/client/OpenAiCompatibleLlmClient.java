package com.enterprise.llm.client;

import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final String DONE_TOKEN = "[DONE]";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService virtualThreadExecutor;

    public OpenAiCompatibleLlmClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .executor(this.virtualThreadExecutor)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
        try {
            String requestBody = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw LlmErrorParser.parse(response.statusCode(), response.body(), response.headers());
            }

            return objectMapper.readValue(response.body(), ChatCompletionResponse.class);
        } catch (com.enterprise.llm.exception.LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to execute sync chatCompletion", e);
            throw new com.enterprise.llm.exception.LlmServerException("Chat completion failed: " + e.getMessage(), e, 500);
        }
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
            } catch (Exception ignored) {
            }
        });

        virtualThreadExecutor.submit(() -> {
            try {
                ChatCompletionRequest streamRequest = new ChatCompletionRequest(
                        request.model(),
                        request.messages(),
                        true,
                        request.temperature()
                );
                String requestBody = objectMapper.writeValueAsString(streamRequest);

                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 400) {
                    String errBody = "";
                    try (InputStream is = response.body()) {
                        errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (Exception ignored) {}
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
