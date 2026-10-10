package com.enterprise.llm.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminAnalyticsOverview(
        long totalTenants,
        long activeTenants,
        long totalTokensUsed,
        long cacheSavedTokens,
        long cacheHitsL1,
        long cacheHitsL2,
        long cacheMisses,
        double cacheHitRate
) {}
