package com.enterprise.llm.router.shadow;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Enterprise Production Shadow Traffic Mirroring Engine.
 * Asynchronously duplicates live production requests to experimental/canary models
 * without impacting client latency or reliability, enabling risk-free A/B and performance evaluation.
 */
@Component
public class ShadowTrafficMirror implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ShadowTrafficMirror.class);

    private final Map<String, ShadowTrafficRule> rules = new ConcurrentHashMap<>();
    private final ExecutorService shadowExecutor;
    private volatile Consumer<ShadowComparisonRecord> comparisonListener;

    public ShadowTrafficMirror() {
        this.shadowExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public ShadowTrafficMirror(ExecutorService customExecutor) {
        this.shadowExecutor = customExecutor != null ? customExecutor : Executors.newVirtualThreadPerTaskExecutor();
    }

    public void setRule(ShadowTrafficRule rule) {
        if (rule != null) {
            rules.put(rule.sourceModel(), rule);
            log.info("Registered shadow traffic rule: {} -> {} (sampling={})",
                    rule.sourceModel(), rule.shadowModel(), rule.sampleRatio());
        }
    }

    public void removeRule(String sourceModel) {
        if (sourceModel != null) {
            rules.remove(sourceModel);
            log.info("Removed shadow traffic rule for source model: {}", sourceModel);
        }
    }

    public void setComparisonListener(Consumer<ShadowComparisonRecord> comparisonListener) {
        this.comparisonListener = comparisonListener;
    }

    /**
     * Inspects primary request and response, and asynchronously dispatches shadow evaluation
     * if a matching rule exists and sampling passes.
     */
    public void maybeMirrorAsync(
            ChatCompletionRequest primaryRequest,
            ChatCompletionResponse primaryResponse,
            long primaryLatencyMs,
            String tenantId,
            String traceId,
            LlmClient shadowClient
    ) {
        if (primaryRequest == null || shadowClient == null) {
            return;
        }

        ShadowTrafficRule rule = rules.get(primaryRequest.model());
        if (rule == null || rule.sampleRatio() <= 0.0) {
            return;
        }

        if (rule.sampleRatio() < 1.0 && ThreadLocalRandom.current().nextDouble() >= rule.sampleRatio()) {
            return; // Dropped by sampling
        }

        // Fire-and-forget async execution via virtual thread
        shadowExecutor.submit(() -> executeShadowCall(
                primaryRequest, primaryResponse, primaryLatencyMs, tenantId, traceId, rule, shadowClient
        ));
    }

    private void executeShadowCall(
            ChatCompletionRequest primaryRequest,
            ChatCompletionResponse primaryResponse,
            long primaryLatencyMs,
            String tenantId,
            String traceId,
            ShadowTrafficRule rule,
            LlmClient shadowClient
    ) {
        long startTime = System.currentTimeMillis();
        boolean success = false;
        String errorMessage = null;
        int shadowTokens = 0;
        String shadowOutput = "";

        try {
            ChatCompletionRequest shadowRequest = primaryRequest.withModel(rule.shadowModel());
            ChatCompletionResponse shadowResp = shadowClient.chatCompletion(shadowRequest);
            success = true;

            if (shadowResp != null) {
                if (shadowResp.usage() != null) {
                    shadowTokens = shadowResp.usage().totalTokens();
                }
                if (shadowResp.choices() != null && !shadowResp.choices().isEmpty()) {
                    var choice = shadowResp.choices().get(0);
                    if (choice.message() != null && choice.message().content() != null) {
                        shadowOutput = truncateSnippet(choice.message().content(), 200);
                    }
                }
            }
        } catch (Throwable t) {
            success = false;
            errorMessage = t.getMessage();
            log.warn("Shadow execution failed for model {}: {}", rule.shadowModel(), errorMessage);
        }

        long shadowLatency = System.currentTimeMillis() - startTime;

        int primaryTokens = 0;
        String primaryOutput = "";
        if (primaryResponse != null) {
            if (primaryResponse.usage() != null) {
                primaryTokens = primaryResponse.usage().totalTokens();
            }
            if (primaryResponse.choices() != null && !primaryResponse.choices().isEmpty()) {
                var choice = primaryResponse.choices().get(0);
                if (choice.message() != null && choice.message().content() != null) {
                    primaryOutput = truncateSnippet(choice.message().content(), 200);
                }
            }
        }

        ShadowComparisonRecord record = new ShadowComparisonRecord(
                traceId,
                tenantId,
                rule.sourceModel(),
                rule.shadowModel(),
                primaryLatencyMs,
                shadowLatency,
                primaryTokens,
                shadowTokens,
                primaryOutput,
                shadowOutput,
                success,
                errorMessage
        );

        log.info("SHADOW_MIRROR_AUDIT traceId={} tenantId={} primaryModel={} shadowModel={} primaryMs={} shadowMs={} shadowOk={}",
                traceId, tenantId, rule.sourceModel(), rule.shadowModel(),
                primaryLatencyMs, shadowLatency, success);

        if (comparisonListener != null) {
            try {
                comparisonListener.accept(record);
            } catch (Exception e) {
                log.error("Error invoking shadow comparison listener: {}", e.getMessage(), e);
            }
        }
    }

    private String truncateSnippet(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    @Override
    @PreDestroy
    public void close() {
        shadowExecutor.shutdown();
        try {
            if (!shadowExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                shadowExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shadowExecutor.shutdownNow();
        }
    }
}
