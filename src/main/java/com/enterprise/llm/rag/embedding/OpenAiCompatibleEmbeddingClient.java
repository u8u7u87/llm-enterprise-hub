package com.enterprise.llm.rag.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleEmbeddingClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey != null ? apiKey : "";
        this.model = model != null && !model.isBlank() ? model : "BAAI/bge-m3";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        List<float[]> results = embedBatch(List.of(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", this.model,
                    "input", texts
            );
            String jsonPayload = objectMapper.writeValueAsString(requestBody);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8));

            if (!apiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Upstream Embedding API error: HTTP " + response.statusCode() + " body: " + response.body());
            }

            EmbeddingApiResponse apiResponse = objectMapper.readValue(response.body(), EmbeddingApiResponse.class);
            if (apiResponse.data == null || apiResponse.data.isEmpty()) {
                return Collections.emptyList();
            }

            // Ensure order by index
            List<EmbeddingData> sortedData = new ArrayList<>(apiResponse.data);
            sortedData.sort(Comparator.comparingInt(a -> a.index));

            List<float[]> embeddingsList = new ArrayList<>();
            for (EmbeddingData item : sortedData) {
                if (item.embedding != null) {
                    float[] vec = new float[item.embedding.size()];
                    for (int i = 0; i < item.embedding.size(); i++) {
                        vec[i] = item.embedding.get(i).floatValue();
                    }
                    embeddingsList.add(vec);
                }
            }
            return embeddingsList;
        } catch (Exception e) {
            log.error("Failed to generate embeddings: {}", e.getMessage(), e);
            throw new RuntimeException("Embedding generation failed: " + e.getMessage(), e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingApiResponse {
        @JsonProperty("data")
        public List<EmbeddingData> data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingData {
        @JsonProperty("index")
        public int index;
        @JsonProperty("embedding")
        public List<Double> embedding;
    }
}
