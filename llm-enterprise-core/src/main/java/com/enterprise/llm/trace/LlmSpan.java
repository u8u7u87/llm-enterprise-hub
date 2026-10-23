package com.enterprise.llm.trace;

public class LlmSpan {

    private final String name;
    private final TraceContext context;
    private final String model;

    private long startTimeMs;
    private long firstTokenTimeMs;
    private long endTimeMs;

    private int promptTokens;
    private int completionTokens;

    public LlmSpan(String name, TraceContext context, String model) {
        this.name = name;
        this.context = context;
        this.model = model;
    }

    public void start(long startTimeMs) {
        this.startTimeMs = startTimeMs;
    }

    public void recordFirstToken(long tokenTimeMs) {
        this.firstTokenTimeMs = tokenTimeMs;
    }

    public void finish(long endTimeMs, int promptTokens, int completionTokens) {
        this.endTimeMs = endTimeMs;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
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
}
