package com.enterprise.llm.cluster.discovery;

import com.enterprise.llm.cluster.UpstreamNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.HashSet;
import java.util.List;

/**
 * Model catalog discovery provider that probes upstream OpenAI-compatible /models endpoints
 * to discover registered models and verify cluster node health.
 */
public class ModelCatalogDiscoveryProvider implements ClusterDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogDiscoveryProvider.class);
    public static final String PROVIDER_TYPE = "CATALOG";

    @FunctionalInterface
    public interface HttpRequestSender {
        HttpResponse<String> send(HttpRequest request) throws Exception;
    }

    private final HttpClient httpClient;
    private final HttpRequestSender requestSender;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public ModelCatalogDiscoveryProvider() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    public ModelCatalogDiscoveryProvider(HttpClient httpClient) {
        this(httpClient, new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false), Duration.ofSeconds(5));
    }

    public ModelCatalogDiscoveryProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this(httpClient, objectMapper, Duration.ofSeconds(5));
    }

    public ModelCatalogDiscoveryProvider(HttpClient httpClient, ObjectMapper objectMapper, Duration requestTimeout) {
        this.httpClient = httpClient != null ? httpClient : HttpClient.newHttpClient();
        this.requestSender = req -> this.httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(5);
    }

    public ModelCatalogDiscoveryProvider(HttpRequestSender requestSender) {
        this(requestSender, new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false), Duration.ofSeconds(5));
    }

    public ModelCatalogDiscoveryProvider(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        this(requestSender, objectMapper, Duration.ofSeconds(5));
    }

    public ModelCatalogDiscoveryProvider(HttpRequestSender requestSender, ObjectMapper objectMapper, Duration requestTimeout) {
        this.httpClient = null;
        this.requestSender = requestSender != null ? requestSender : (req -> null);
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(5);
    }

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public List<UpstreamNode> discoverNodes(String target) {
        return discoverNodes(target, "");
    }

    public List<UpstreamNode> discoverNodes(String target, String apiKey) {
        if (target == null || target.isBlank()) {
            return Collections.emptyList();
        }

        String[] parts = target.split(",");
        List<UpstreamNode> nodes = new ArrayList<>();

        for (String raw : parts) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String normalizedUrl = normalizeBaseUrl(raw.trim());
            String id = extractNodeId(normalizedUrl);

            UpstreamNode node = new UpstreamNode(id, normalizedUrl, apiKey, 10);
            List<String> models = discoverModels(normalizedUrl, apiKey);
            if (!models.isEmpty()) {
                node.setSupportedModels(new HashSet<>(models));
                node.setHealthy(true);
            } else {
                boolean healthy = checkHealth(normalizedUrl, apiKey);
                node.setHealthy(healthy);
            }
            nodes.add(node);
        }

        return Collections.unmodifiableList(nodes);
    }

    @Override
    public List<String> discoverModels(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Collections.emptyList();
        }
        String normalized = normalizeBaseUrl(baseUrl);

        // Try primary models endpoint
        String primaryUrl = buildModelsUrl(normalized);
        List<String> models = fetchModelsFromUrl(primaryUrl, apiKey);
        if (!models.isEmpty()) {
            return models;
        }

        // Try alternate models endpoint
        String alternateUrl = buildAlternateModelsUrl(normalized);
        if (alternateUrl != null && !alternateUrl.equalsIgnoreCase(primaryUrl)) {
            models = fetchModelsFromUrl(alternateUrl, apiKey);
            if (!models.isEmpty()) {
                return models;
            }
        }

        return Collections.emptyList();
    }

    /**
     * Checks /health or /v1/models (status 200) to confirm node health.
     *
     * @param baseUrl base URL of the node
     * @return true if node responds with status 200 on /health or /v1/models (or /models)
     */
    public boolean checkHealth(String baseUrl) {
        return checkHealth(baseUrl, "");
    }

    /**
     * Checks /health or /v1/models (status 200) to confirm node health with optional API key.
     *
     * @param baseUrl base URL of the node
     * @param apiKey  API key for authorization
     * @return true if node responds with status 200 on /health or /v1/models (or /models)
     */
    public boolean checkHealth(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String normalized = normalizeBaseUrl(baseUrl);

        // 1. Probe /health
        String healthUrl = buildHealthUrl(normalized);
        if (probeStatus200(healthUrl, apiKey)) {
            return true;
        }

        // Also check if health is under subpath
        if (normalized.endsWith("/v1")) {
            String v1Health = normalized + "/health";
            if (probeStatus200(v1Health, apiKey)) {
                return true;
            }
        }

        // 2. Probe /v1/models or /models
        String primaryModels = buildModelsUrl(normalized);
        if (probeStatus200(primaryModels, apiKey)) {
            return true;
        }

        String alternateModels = buildAlternateModelsUrl(normalized);
        if (alternateModels != null && !alternateModels.equalsIgnoreCase(primaryModels)) {
            if (probeStatus200(alternateModels, apiKey)) {
                return true;
            }
        }

        return false;
    }

    private boolean probeStatus200(String url, String apiKey) {
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = requestSender.send(reqBuilder.build());
            return response != null && response.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Health probe failed for URL '{}': {}", url, e.getMessage());
            return false;
        }
    }

    private List<String> fetchModelsFromUrl(String url, String apiKey) {
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(requestTimeout)
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = requestSender.send(reqBuilder.build());
            if (response == null || response.statusCode() != 200) {
                return Collections.emptyList();
            }
            return extractModelIds(response.body());
        } catch (Exception e) {
            log.warn("Failed to fetch models from '{}': {}", url, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Extracts model ids from OpenAI-compatible JSON { "data": [ {"id": "qwen2.5:72b"}, {"id": "deepseek-r1"} ] }.
     *
     * @param json response body JSON
     * @return list of model identifiers
     */
    public List<String> extractModelIds(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<String> modelIds = new ArrayList<>();
            JsonNode dataNode = root.get("data");
            if (dataNode != null && dataNode.isArray()) {
                for (JsonNode item : dataNode) {
                    addModelId(item, modelIds);
                }
            } else if (root.isArray()) {
                for (JsonNode item : root) {
                    addModelId(item, modelIds);
                }
            }
            return Collections.unmodifiableList(modelIds);
        } catch (Exception e) {
            log.warn("Failed to parse models JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void addModelId(JsonNode item, List<String> modelIds) {
        if (item.isObject() && item.has("id")) {
            String id = item.get("id").asText();
            if (id != null && !id.isBlank()) {
                modelIds.add(id.trim());
            }
        } else if (item.isTextual()) {
            String id = item.asText();
            if (id != null && !id.isBlank()) {
                modelIds.add(id.trim());
            }
        }
    }

    private static String normalizeBaseUrl(String url) {
        String cleaned = url.trim();
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            cleaned = "http://" + cleaned;
        }
        return cleaned;
    }

    private static String buildModelsUrl(String normalized) {
        if (normalized.endsWith("/v1")) {
            return normalized + "/models";
        }
        return normalized + "/v1/models";
    }

    private static String buildAlternateModelsUrl(String normalized) {
        if (normalized.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - 3) + "/models";
        }
        return normalized + "/models";
    }

    private static String buildHealthUrl(String normalized) {
        if (normalized.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - 3) + "/health";
        }
        return normalized + "/health";
    }

    private static String extractNodeId(String normalizedUrl) {
        try {
            URI uri = URI.create(normalizedUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host != null && !host.isBlank()) {
                return "catalog-" + host + (port > 0 ? ":" + port : "");
            }
        } catch (IllegalArgumentException e) {
            // 配置里可能出现占位符或非标准 URL，解析失败时退化为按字符串净化生成稳定 ID
        }
        return "catalog-" + normalizedUrl.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}
