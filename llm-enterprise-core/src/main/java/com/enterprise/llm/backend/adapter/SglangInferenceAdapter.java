package com.enterprise.llm.backend.adapter;

import com.enterprise.llm.backend.EngineType;
import com.enterprise.llm.backend.InferenceBackendAdapter;
import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.cluster.lb.ClusterLoadBalancer;
import com.enterprise.llm.cluster.lb.PrefixHashLoadBalancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SGLang specialized inference backend adapter.
 * Optimized for RadixAttention tree-structured KV cache reuse
 * and structured output decoding.
 */
public class SglangInferenceAdapter implements InferenceBackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(SglangInferenceAdapter.class);

    private static final Pattern SGLANG_WAITING_PATTERN = Pattern.compile(
            "(?:sglang[:_])?num_waiting_reqs(?:\\{[^}]*\\})?\\s+([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SGLANG_CACHE_PATTERN = Pattern.compile(
            "(?:sglang[:_])?(?:gpu_cache_usage|cache_hit_rate)(?:\\{[^}]*\\})?\\s+([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE
    );

    @Override
    public String getEngineType() {
        return EngineType.SGLANG.getCode();
    }

    @Override
    public ClusterLoadBalancer createDefaultLoadBalancer() {
        // RadixAttention tree cache benefits strongly from deterministic prefix routing
        return new PrefixHashLoadBalancer();
    }

    @Override
    public boolean checkHealth(UpstreamNode node, HttpClient httpClient) {
        if (node == null || node.getBaseUrl() == null || node.getBaseUrl().isBlank()) {
            return false;
        }
        HttpClient client = httpClient != null ? httpClient : HttpClient.newHttpClient();
        String rootUrl = getRootUrl(node.getBaseUrl());
        String probeUrl = rootUrl + "/health";

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

            // Fallback probe
            String fallbackUrl = rootUrl + "/v1/models";
            HttpRequest fallbackReq = HttpRequest.newBuilder()
                    .uri(URI.create(fallbackUrl))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<Void> fallbackResp = client.send(fallbackReq, HttpResponse.BodyHandlers.discarding());
            return fallbackResp.statusCode() >= 200 && fallbackResp.statusCode() < 300;
        } catch (Exception e) {
            log.debug("SGLang health probe failed for node [{}]: {}", node.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    public void extractMetrics(UpstreamNode node, String prometheusText) {
        if (node == null || prometheusText == null || prometheusText.isBlank()) {
            return;
        }

        Matcher waitMatcher = SGLANG_WAITING_PATTERN.matcher(prometheusText);
        if (waitMatcher.find()) {
            try {
                int waiting = (int) Double.parseDouble(waitMatcher.group(1));
                node.setWaitingRequests(waiting);
            } catch (Exception ignored) {}
        }

        Matcher cacheMatcher = SGLANG_CACHE_PATTERN.matcher(prometheusText);
        if (cacheMatcher.find()) {
            try {
                double usage = Double.parseDouble(cacheMatcher.group(1));
                node.setGpuCacheUsage(usage);
            } catch (Exception ignored) {}
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
