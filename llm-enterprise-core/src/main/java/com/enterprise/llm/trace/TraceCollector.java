package com.enterprise.llm.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class TraceCollector {

    private static final Logger log = LoggerFactory.getLogger(TraceCollector.class);
    private final ConcurrentLinkedQueue<LlmSpan> completedSpans = new ConcurrentLinkedQueue<>();

    public void collect(LlmSpan span) {
        completedSpans.add(span);
        log.info("Collected LLM Span [{}]: model={}, ttft={}ms, totalLatency={}ms, tokens={}, tps={}",
                span.getName(),
                span.getModel(),
                span.getTtftMs(),
                span.getTotalLatencyMs(),
                span.getTotalTokens(),
                String.format("%.2f", span.getTokensPerSecond())
        );
    }

    public ConcurrentLinkedQueue<LlmSpan> getCompletedSpans() {
        return completedSpans;
    }
}
