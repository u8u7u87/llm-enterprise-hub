package com.enterprise.llm.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

@Service
public class MetricsRecordService {

    private static final Logger log = LoggerFactory.getLogger(MetricsRecordService.class);
    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final JdbcTemplate jdbcTemplate;

    // In-memory sliding window of the last 60 minutes for sub-second dashboard rendering
    private final ConcurrentHashMap<String, MinuteAggregator> minuteAggregators = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> orderedMinutes = new ConcurrentLinkedDeque<>();

    private static class MinuteAggregator {
        final AtomicLong totalRequests = new AtomicLong(0);
        final AtomicLong successfulRequests = new AtomicLong(0);
        final AtomicLong failedRequests = new AtomicLong(0);
        final AtomicLong totalTokens = new AtomicLong(0);
        final AtomicLong savedTokens = new AtomicLong(0);
        final AtomicLong l1Hits = new AtomicLong(0);
        final AtomicLong l2Hits = new AtomicLong(0);
        final AtomicLong violationsCount = new AtomicLong(0);
        final DoubleAdder totalLatencyMs = new DoubleAdder();
    }

    @Autowired
    public MetricsRecordService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public MetricsRecordService() {
        this(null);
    }

    public void record(
            boolean success,
            long latencyMs,
            long tokensUsed,
            long savedTokens,
            boolean isL1Hit,
            boolean isL2Hit,
            boolean isViolation
    ) {
        String currentMinute = MINUTE_FORMATTER.format(Instant.now());
        MinuteAggregator agg = minuteAggregators.computeIfAbsent(currentMinute, k -> {
            orderedMinutes.addLast(k);
            // Evict if older than 120 minutes in memory
            while (orderedMinutes.size() > 120) {
                String oldest = orderedMinutes.pollFirst();
                if (oldest != null) {
                    minuteAggregators.remove(oldest);
                }
            }
            return new MinuteAggregator();
        });

        agg.totalRequests.incrementAndGet();
        if (success) {
            agg.successfulRequests.incrementAndGet();
        } else {
            agg.failedRequests.incrementAndGet();
        }
        agg.totalTokens.addAndGet(tokensUsed);
        agg.savedTokens.addAndGet(savedTokens);
        if (isL1Hit) {
            agg.l1Hits.incrementAndGet();
        }
        if (isL2Hit) {
            agg.l2Hits.incrementAndGet();
        }
        if (isViolation) {
            agg.violationsCount.incrementAndGet();
        }
        agg.totalLatencyMs.add(latencyMs);

        // Periodically sync/persist to SQLite if jdbcTemplate is present
        if (jdbcTemplate != null && agg.totalRequests.get() % 5 == 0) {
            flushMinuteToDb(currentMinute, agg);
        }
    }

    public synchronized void flushMinuteToDb(String minuteBucket, MinuteAggregator agg) {
        if (jdbcTemplate == null || agg == null) {
            return;
        }
        try {
            long total = agg.totalRequests.get();
            double avgLatency = total > 0 ? (agg.totalLatencyMs.sum() / total) : 0.0;
            String checkSql = "SELECT COUNT(*) FROM system_metrics WHERE minute_bucket = ?";
            Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, minuteBucket);
            if (count != null && count > 0) {
                String updateSql = """
                        UPDATE system_metrics SET
                            total_requests = ?, successful_requests = ?, failed_requests = ?,
                            total_tokens = ?, saved_tokens = ?, l1_hits = ?, l2_hits = ?,
                            avg_latency_ms = ?, violations_count = ?
                        WHERE minute_bucket = ?
                        """;
                jdbcTemplate.update(updateSql,
                        total, agg.successfulRequests.get(), agg.failedRequests.get(),
                        agg.totalTokens.get(), agg.savedTokens.get(), agg.l1Hits.get(), agg.l2Hits.get(),
                        avgLatency, agg.violationsCount.get(), minuteBucket);
            } else {
                String insertSql = """
                        INSERT INTO system_metrics (
                            minute_bucket, total_requests, successful_requests, failed_requests,
                            total_tokens, saved_tokens, l1_hits, l2_hits, avg_latency_ms, violations_count
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;
                jdbcTemplate.update(insertSql,
                        minuteBucket, total, agg.successfulRequests.get(), agg.failedRequests.get(),
                        agg.totalTokens.get(), agg.savedTokens.get(), agg.l1Hits.get(), agg.l2Hits.get(),
                        avgLatency, agg.violationsCount.get());
            }
        } catch (Exception e) {
            log.warn("Failed to flush metrics to SQLite for bucket {}", minuteBucket, e);
        }
    }

    public List<SystemMetricPoint> getTimeline(int limitMinutes) {
        List<SystemMetricPoint> points = new ArrayList<>();
        int count = 0;
        for (String minute : orderedMinutes) {
            MinuteAggregator agg = minuteAggregators.get(minute);
            if (agg != null) {
                long total = agg.totalRequests.get();
                double avgLatency = total > 0 ? (agg.totalLatencyMs.sum() / total) : 0.0;
                points.add(new SystemMetricPoint(
                        minute,
                        total,
                        agg.successfulRequests.get(),
                        agg.failedRequests.get(),
                        agg.totalTokens.get(),
                        agg.savedTokens.get(),
                        agg.l1Hits.get(),
                        agg.l2Hits.get(),
                        Math.round(avgLatency * 10.0) / 10.0,
                        agg.violationsCount.get()
                ));
                count++;
                if (count >= limitMinutes) {
                    break;
                }
            }
        }
        return points;
    }
}
