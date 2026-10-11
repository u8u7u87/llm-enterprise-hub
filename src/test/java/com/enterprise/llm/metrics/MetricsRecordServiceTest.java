package com.enterprise.llm.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MetricsRecordServiceTest {

    private JdbcTemplate mockJdbcTemplate;
    private MetricsRecordService metricsRecordService;

    @BeforeEach
    void setUp() {
        mockJdbcTemplate = mock(JdbcTemplate.class);
        metricsRecordService = new MetricsRecordService(mockJdbcTemplate);
    }

    @Test
    @DisplayName("Should aggregate metrics into current minute bucket in memory and retrieve via getTimeline")
    void shouldAggregateMetricsInMemory() {
        // Record 1: Success sync request
        metricsRecordService.record(true, 50, 100, 0, false, false, false);

        // Record 2: L1 cache hit with saved tokens
        metricsRecordService.record(true, 2, 0, 80, true, false, false);

        // Record 3: Security violation
        metricsRecordService.record(false, 10, 0, 0, false, false, true);

        List<SystemMetricPoint> points = metricsRecordService.getTimeline(10);
        assertThat(points).hasSize(1);

        SystemMetricPoint point = points.get(0);
        assertThat(point.totalRequests()).isEqualTo(3);
        assertThat(point.successfulRequests()).isEqualTo(2);
        assertThat(point.failedRequests()).isEqualTo(1);
        assertThat(point.totalTokens()).isEqualTo(100);
        assertThat(point.savedTokens()).isEqualTo(80);
        assertThat(point.l1Hits()).isEqualTo(1);
        assertThat(point.violationsCount()).isEqualTo(1);
        assertThat(point.avgLatencyMs()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Should trigger SQLite flush after 5 records")
    void shouldTriggerFlushToDatabase() {
        when(mockJdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);

        for (int i = 0; i < 5; i++) {
            metricsRecordService.record(true, 20, 10, 0, false, false, false);
        }

        // Verify flush was called on 5th request
        verify(mockJdbcTemplate, atLeastOnce()).queryForObject(anyString(), eq(Integer.class), anyString());
    }
}
