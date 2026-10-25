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
 * Ray Serve distributed serving backend adapter.
 * Optimized for Ray Serve HTTP Ingress routing and /-/healthz health probes.
 */
public class RayServeInferenceAdapter implements InferenceBackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(RayServeInferenceAdapter.class);

    @Override
    public String getEngineType() {
        return EngineType.RAY.getCode();
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
        String rootUrl = getRootUrl(node.getBaseUrl());
        String probeUrl = rootUrl + "/-/healthz";

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
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return true;
            }

            // Fallback probe to /v1/models
            String fallbackUrl = rootUrl + "/v1/models";
            HttpRequest fallbackReq = HttpRequest.newBuilder()
                    .uri(URI.create(fallbackUrl))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<Void> fallbackResp = client.send(fallbackReq, HttpResponse.BodyHandlers.discarding());
            return fallbackResp.statusCode() >= 200 && fallbackResp.statusCode() < 300;
        } catch (Exception e) {
            log.debug("Ray Serve health probe failed for node [{}]: {}", node.getId(), e.getMessage());
            return false;
        }
    }

    private String getRootUrl(String baseUrl) {
        String clean = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (clean.endsWith("/v1")) {
            return clean.substring(0, clean.length() - 3);
        }
        return clean;
    }
}
