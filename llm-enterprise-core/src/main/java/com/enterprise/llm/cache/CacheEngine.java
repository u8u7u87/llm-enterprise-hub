package com.enterprise.llm.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import com.enterprise.llm.auth.TenantSecurityContext;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.rag.embedding.EmbeddingClient;
import com.enterprise.llm.tenant.TenantEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CacheEngine {

    private static final Logger log = LoggerFactory.getLogger(CacheEngine.class);

    private final CacheProperties properties;
    private final EmbeddingClient embeddingClient;
    private final ConcurrentHashMap<String, CacheEntry> l1Map = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<CacheEntry> semanticEntries = new CopyOnWriteArrayList<>();

    private final AtomicLong l1Hits = new AtomicLong(0);
    private final AtomicLong l2Hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong savedTokens = new AtomicLong(0);

    @Autowired
    public CacheEngine(
            CacheProperties properties,
            @Autowired(required = false)
            EmbeddingClient embeddingClient
    ) {
        this.properties = properties != null ? properties : new CacheProperties();
        this.embeddingClient = embeddingClient;
    }

    public CacheEngine(CacheProperties properties) {
        this(properties, null);
    }

    public CacheEngine() {
        this(new CacheProperties(), null);
    }


    public Optional<ChatCompletionResponse> lookup(ChatCompletionRequest request) {
        TenantEntity tenant = TenantSecurityContext.getCurrentTenant();
        String tenantId = tenant != null ? tenant.getId() : "default";
        return lookup(request, tenantId);
    }

    public Optional<ChatCompletionResponse> lookup(ChatCompletionRequest request, String tenantId) {
        if (!properties.isEnabled() || request == null) {
            return Optional.empty();
        }

        String effectiveTenantId = tenantId != null ? tenantId : "default";
        String key = computeKey(request, effectiveTenantId);

        // 1. L1 Exact Hash Lookup
        CacheEntry l1Entry = l1Map.get(key);
        if (l1Entry != null) {
            if (l1Entry.isExpired()) {
                l1Map.remove(key);
                semanticEntries.remove(l1Entry);
            } else {
                l1Hits.incrementAndGet();
                recordSavedTokens(l1Entry.response());
                log.debug("L1 Cache HIT for request key: {}", key);
                return Optional.of(l1Entry.response());
            }
        }

        // 2. L2 Semantic Similarity Lookup (strictly scoped by tenantId)
        String prompt = extractPromptText(request);
        if (!prompt.isBlank() && !semanticEntries.isEmpty()) {
            CacheEntry bestMatch = null;
            double bestScore = 0.0;

            float[] queryVector = null;
            if (embeddingClient != null) {
                try {
                    queryVector = embeddingClient.embed(prompt);
                } catch (Exception e) {
                    log.debug("Embedding generation failed for semantic cache lookup, falling back to Jaccard: {}", e.getMessage());
                }
            }

            if (queryVector != null && queryVector.length > 0) {
                // Vector Cosine Similarity
                for (CacheEntry entry : semanticEntries) {
                    if (entry.isExpired()) {
                        semanticEntries.remove(entry);
                        l1Map.remove(entry.key());
                        continue;
                    }
                    if (!Objects.equals(entry.tenantId(), effectiveTenantId)) {
                        continue;
                    }
                    if (entry.vector() != null) {
                        double cos = computeCosineSimilarity(queryVector, entry.vector());
                        if (cos > bestScore) {
                            bestScore = cos;
                            bestMatch = entry;
                        }
                    }
                }
            } else {
                // Lexical Jaccard Fallback
                Set<String> queryWords = extractWordTokens(prompt);
                for (CacheEntry entry : semanticEntries) {
                    if (entry.isExpired()) {
                        semanticEntries.remove(entry);
                        l1Map.remove(entry.key());
                        continue;
                    }
                    if (!Objects.equals(entry.tenantId(), effectiveTenantId)) {
                        continue;
                    }
                    Set<String> targetWords = extractWordTokens(entry.promptText());
                    double score = computeJaccardSimilarity(queryWords, targetWords);
                    if (score > bestScore) {
                        bestScore = score;
                        bestMatch = entry;
                    }
                }
            }

            if (bestMatch != null && bestScore >= properties.getSemanticThreshold()) {
                l2Hits.incrementAndGet();
                recordSavedTokens(bestMatch.response());
                log.debug("L2 Semantic Cache HIT (similarity={}) for prompt: {}", bestScore, prompt);
                return Optional.of(bestMatch.response());
            }
        }

        misses.incrementAndGet();
        return Optional.empty();
    }

    public void put(ChatCompletionRequest request, ChatCompletionResponse response) {
        TenantEntity tenant = TenantSecurityContext.getCurrentTenant();
        String tenantId = tenant != null ? tenant.getId() : "default";
        put(request, response, tenantId);
    }

    public void put(ChatCompletionRequest request, ChatCompletionResponse response, String tenantId) {
        if (!properties.isEnabled() || request == null || response == null) {
            return;
        }

        if (l1Map.size() >= properties.getMaxCapacity()) {
            // Simple eviction: clear oldest
            l1Map.clear();
            semanticEntries.clear();
        }

        String effectiveTenantId = tenantId != null ? tenantId : "default";
        String key = computeKey(request, effectiveTenantId);
        String prompt = extractPromptText(request);
        long now = System.currentTimeMillis();
        long expireAt = now + (properties.getTtlSeconds() * 1000L);

        float[] vector = null;
        if (embeddingClient != null && !prompt.isBlank()) {
            try {
                vector = embeddingClient.embed(prompt);
            } catch (Exception e) {
                log.debug("Failed to generate embedding for cache entry: {}", e.getMessage());
            }
        }

        CacheEntry entry = new CacheEntry(key, effectiveTenantId, prompt, response, now, expireAt, vector);
        l1Map.put(key, entry);
        semanticEntries.add(entry);
    }

    public void clear() {
        l1Map.clear();
        semanticEntries.clear();
    }

    public long getL1Hits() {
        return l1Hits.get();
    }

    public long getL2Hits() {
        return l2Hits.get();
    }

    public long getMisses() {
        return misses.get();
    }

    public long getSavedTokens() {
        return savedTokens.get();
    }

    private void recordSavedTokens(ChatCompletionResponse response) {
        if (response != null && response.usage() != null && response.usage().totalTokens() != null) {
            savedTokens.addAndGet(response.usage().totalTokens());
        }
    }

    private String computeKey(ChatCompletionRequest request, String tenantId) {
        StringBuilder sb = new StringBuilder();
        sb.append(tenantId != null ? tenantId : "default").append("|");
        sb.append(request.model()).append("|");
        sb.append(request.temperature()).append("|");
        if (request.messages() != null) {
            for (ChatMessage msg : request.messages()) {
                sb.append(msg.role()).append(":").append(msg.content()).append(";");
            }
        }
        return sha256(sb.toString());
    }

    private String extractPromptText(ChatCompletionRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : request.messages()) {
            if ("user".equalsIgnoreCase(msg.role()) && msg.content() != null) {
                sb.append(msg.content()).append(" ");
            }
        }
        return sb.toString().trim();
    }

    private Set<String> extractWordTokens(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }
        String clean = text.toLowerCase().replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", " ");
        String[] parts = clean.split("\\s+");
        Set<String> set = new HashSet<>();
        for (String p : parts) {
            if (!p.isBlank()) {
                set.add(p);
            }
        }
        return set;
    }

    private double computeJaccardSimilarity(Set<String> setA, Set<String> setB) {
        if (setA.isEmpty() || setB.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        for (String item : setA) {
            if (setB.contains(item)) {
                intersection++;
            }
        }
        int union = setA.size() + setB.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / (double) union;
    }

    private double computeCosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length || v1.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }
}
