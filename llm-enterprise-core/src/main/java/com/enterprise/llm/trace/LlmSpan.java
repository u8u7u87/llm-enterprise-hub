package com.enterprise.llm.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.enterprise.llm.metrics.LlmMetricsCollector;
import com.enterprise.llm.metrics.NoOpMetricsCollector;

public class LlmSpan {

    private final String name;
    private final TraceContext context;
    private final String model;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    private long startTimeMs;
    private long firstTokenTimeMs;
    private long endTimeMs;

    private int promptTokens;
    private int completionTokens;
    private double costUsd;
    private LlmMetricsCollector metricsCollector = NoOpMetricsCollector.INSTANCE;

    public LlmSpan(String name, TraceContext context, String model) {
        this.name = name;
        this.context = context;
        this.model = model;
    }

    public LlmSpan setMetricsCollector(LlmMetricsCollector metricsCollector) {
        if (metricsCollector != null) {
            this.metricsCollector = metricsCollector;
        }
        return this;
    }

    public LlmMetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public void start(long startTimeMs) {
        this.startTimeMs = startTimeMs;
        if (metricsCollector != null) {
            reportMetric(() -> metricsCollector.recordActiveRequest(model, 1));
        }
    }

    public void recordFirstToken(long tokenTimeMs) {
        this.firstTokenTimeMs = tokenTimeMs;
    }

    public void recordError(Throwable error) {
        setAttribute("status", "error");
        if (error != null) {
            setAttribute("error_message", error.getMessage());
        }
    }

    public void finish(long endTimeMs, int promptTokens, int completionTokens, double costUsd) {
        this.costUsd = costUsd;
        finish(endTimeMs, promptTokens, completionTokens);
    }

    public void finish(long endTimeMs, int promptTokens, int completionTokens) {
        this.endTimeMs = endTimeMs;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;

        if (metricsCollector != null) {
            reportMetric(() -> {
                metricsCollector.recordActiveRequest(model, -1);
                String tenantId = (context != null) ? context.tenantId() : null;
                String status = "success";
                Object statusAttr = attributes.get("status");
                if (statusAttr != null && "error".equalsIgnoreCase(String.valueOf(statusAttr))) {
                    status = "error";
                }
                Long ttft = (firstTokenTimeMs > 0 && startTimeMs > 0) ? (firstTokenTimeMs - startTimeMs) : null;
                Double tps = (completionTokens > 0 && getTotalLatencyMs() > 0) ? getTokensPerSecond() : null;
                metricsCollector.recordRequest(model, tenantId, status, getTotalLatencyMs(), ttft, tps,
                        promptTokens, completionTokens);
            });
        }
    }

    /**
     * 指标上报属于旁路能力，埋点失败不应改变业务语义，因此统一在这里静默降级。
     *
     * <p>采集器自身的故障由 {@link com.enterprise.llm.metrics.CompositeMetricsCollector} 负责告警，
     * 这里不重复打日志，避免在每次请求的埋点路径上产生日志噪音。
     */
    private void reportMetric(Runnable report) {
        try {
            report.run();
        } catch (RuntimeException | Error e) {
            // 有意忽略：见方法注释
        }
    }

    public double getCostUsd() {
        return costUsd;
    }

    public LlmSpan setCostUsd(double costUsd) {
        this.costUsd = costUsd;
        return this;
    }

    public long getTtftMs() {
        if (firstTokenTimeMs <= 0 || startTimeMs <= 0) {
            return 0;
        }
        return firstTokenTimeMs - startTimeMs;
    }

    public long getTotalLatencyMs() {
        if (endTimeMs <= 0 || startTimeMs <= 0) {
            return 0;
        }
        return endTimeMs - startTimeMs;
    }

    public double getTokensPerSecond() {
        long duration = getTotalLatencyMs();
        if (duration <= 0) {
            return 0.0;
        }
        return (completionTokens / (double) duration) * 1000.0;
    }

    public String getName() {
        return name;
    }

    public TraceContext getContext() {
        return context;
    }

    public String getModel() {
        return model;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return promptTokens + completionTokens;
    }

    public LlmSpan setAttribute(String key, Object value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
        return this;
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public boolean isCacheHit() {
        return Boolean.TRUE.equals(attributes.get("cache_hit"));
    }

    public LlmSpan setCacheHit(boolean cacheHit) {
        attributes.put("cache_hit", cacheHit);
        return this;
    }

    public boolean isStream() {
        return Boolean.TRUE.equals(attributes.get("stream"));
    }

    public LlmSpan setStream(boolean stream) {
        attributes.put("stream", stream);
        return this;
    }

    @SuppressWarnings("unchecked")
    public List<String> getToolsInvoked() {
        Object val = attributes.get("tools_invoked");
        if (val instanceof List<?>) {
            return (List<String>) val;
        }
        return Collections.emptyList();
    }

    public LlmSpan addToolInvoked(String toolName) {
        if (toolName != null) {
            attributes.compute("tools_invoked", (k, v) -> {
                List<String> list = (v instanceof List<?>) ? new ArrayList<>((List<String>) v) : new ArrayList<>();
                list.add(toolName);
                return list;
            });
        }
        return this;
    }
}
