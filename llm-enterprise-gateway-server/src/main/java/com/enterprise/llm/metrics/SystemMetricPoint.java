package com.enterprise.llm.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemMetricPoint(
        String minuteBucket,
        long totalRequests,
        long successfulRequests,
        long failedRequests,
        long totalTokens,
        long savedTokens,
        long l1Hits,
        long l2Hits,
        double avgLatencyMs,
        long violationsCount
) {
}
