package com.enterprise.llm.backend.adapter;

import com.enterprise.llm.backend.EngineType;
import com.enterprise.llm.backend.InferenceBackendAdapter;
import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.cluster.lb.ClusterLoadBalancer;
import com.enterprise.llm.cluster.lb.LeastRequestsLoadBalancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Standard frameworkless / direct inference adapter.
 * Suitable for Ollama, local llama.cpp, commercial cloud OpenAI-compatible APIs.
 */
public class DirectInferenceAdapter implements InferenceBackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(DirectInferenceAdapter.class);

    @Override
    public String getEngineType() {
        return EngineType.DIRECT.getCode();
    }

    @Override
    public ClusterLoadBalancer createDefaultLoadBalancer() {
        return new LeastRequestsLoadBalancer();
    }

    @Override
    public boolean checkHealth(UpstreamNode node, HttpClient httpClient) {
        if (node == null || node.getBaseUrl() == null || node.getBaseUrl().isBlank()) {
            return false;
        }
        HttpClient client = httpClient != null ? httpClient : HttpClient.newHttpClient();
        String probeUrl = buildProbeUrl(node.getBaseUrl());

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(probeUrl))
                    .timeout(Duration.ofSeconds(4))
                    .GET();

            String apiKey = node.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            log.debug("Direct health probe failed for node [{}]: {}", node.getId(), e.getMessage());
            return false;
        }
    }

    private String buildProbeUrl(String baseUrl) {
        String clean = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (clean.endsWith("/v1")) {
            return clean + "/models";
        }
        return clean + "/v1/models";
    }
}
