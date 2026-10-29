package com.enterprise.llm.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Composite {@link LlmMetricsCollector} that broadcasts events to multiple underlying collectors.
 *
 * <p>单个采集器抛异常时只丢弃该次事件并记录告警，既不影响业务主链路，
 * 也不中断对其余采集器的广播。
 */
public class CompositeMetricsCollector implements LlmMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(CompositeMetricsCollector.class);

    private final List<LlmMetricsCollector> collectors = new CopyOnWriteArrayList<>();

    public CompositeMetricsCollector() {
    }

    public CompositeMetricsCollector(List<LlmMetricsCollector> collectors) {
        if (collectors != null) {
            this.collectors.addAll(collectors);
        }
    }

    public void addCollector(LlmMetricsCollector collector) {
        if (collector != null && !(collector instanceof NoOpMetricsCollector)) {
            this.collectors.add(collector);
        }
    }

    public void removeCollector(LlmMetricsCollector collector) {
        this.collectors.remove(collector);
    }

    public List<LlmMetricsCollector> getCollectors() {
        return List.copyOf(collectors);
    }

    @Override
    public void recordRequest(String model,
                             String tenantId,
                             String status,
                             long durationMs,
                             Long ttftMs,
                             Double tps,
                             int promptTokens,
                             int completionTokens) {
        broadcast(collector ->
                collector.recordRequest(model, tenantId, status, durationMs, ttftMs, tps, promptTokens, completionTokens));
    }

    @Override
    public void recordToolExecution(String toolName,
                                    String tenantId,
                                    String status,
                                    long durationMs) {
        broadcast(collector -> collector.recordToolExecution(toolName, tenantId, status, durationMs));
    }

    @Override
    public void recordActiveRequest(String model, int delta) {
        broadcast(collector -> collector.recordActiveRequest(model, delta));
    }

    /**
     * 向所有采集器广播事件，逐个隔离故障。
     *
     * <p>采集属于旁路能力，任何实现抛出的异常都不能冒泡到业务链路，
     * 否则一个写坏的自定义采集器就能让整个网关的推理请求失败。
     */
    private void broadcast(Consumer<LlmMetricsCollector> event) {
        for (LlmMetricsCollector collector : collectors) {
            try {
                event.accept(collector);
            } catch (RuntimeException | Error e) {
                log.warn("Metrics collector [{}] failed, event dropped: {}",
                        collector.getClass().getSimpleName(), e.toString());
            }
        }
    }
}
