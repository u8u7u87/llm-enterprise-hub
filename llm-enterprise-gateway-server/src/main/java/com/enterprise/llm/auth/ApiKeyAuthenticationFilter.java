package com.enterprise.llm.auth;

import java.io.IOException;
import java.util.Optional;
import com.enterprise.llm.ratelimit.RateLimitResult;
import com.enterprise.llm.ratelimit.TenantRateLimiter;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantService;
import com.enterprise.llm.trace.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(1)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final TenantService tenantService;
    private final TenantRateLimiter rateLimiter;

    public ApiKeyAuthenticationFilter(TenantService tenantService, TenantRateLimiter rateLimiter) {
        this.tenantService = tenantService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Allow health checks, admin management, or root/static endpoints without tenant auth
        return path.equals("/") || path.startsWith("/index.html") || path.startsWith("/static") ||
               path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css") ||
               path.startsWith("/actuator") || path.equals("/error") || path.startsWith("/v1/admin") ||
               path.startsWith("/v1/agent/tasks");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String apiKey = extractApiKey(request);

        if (apiKey == null || apiKey.isBlank()) {
            sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing API Key (Provide Authorization or X-API-Key header)");
            return;
        }

        Optional<TenantEntity> tenantOpt = tenantService.authenticateApiKey(apiKey);
        if (tenantOpt.isEmpty()) {
            sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid API Key");
            return;
        }

        TenantEntity tenant = tenantOpt.get();
        TraceContext currentTrace = TraceContext.getCurrent();
        if (currentTrace != null) {
            TraceContext enriched = new TraceContext(
                    currentTrace.traceId(),
                    currentTrace.spanId(),
                    currentTrace.parentSpanId(),
                    tenant.getId(),
                    tenant.getId(),
                    currentTrace.sessionId()
            );
            TraceContext.setCurrent(enriched);
        }

        if (!tenant.isEnabled()) {
            sendJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Tenant account is disabled");
            return;
        }

        RateLimitResult rateResult = rateLimiter.tryAcquireDetailed(tenant, 0);
        if (rateResult != null) {
            if (!rateResult.allowed()) {
                response.setHeader("Retry-After", String.valueOf(Math.max(1, rateResult.retryAfterMs() / 1000)));
                sendJsonError(response, 429, "Rate limit or daily token quota exceeded: " + rateResult.rejectionReason());
                return;
            }
        } else {
            if (!rateLimiter.tryAcquire(tenant)) {
                sendJsonError(response, 429, "Rate limit or daily token quota exceeded");
                return;
            }
        }

        try {
            TenantSecurityContext.setTenant(tenant);
            filterChain.doFilter(request, response);
        } finally {
            TenantSecurityContext.clear();
        }
    }

    private String extractApiKey(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        return request.getHeader("X-API-Key");
    }

    private void sendJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + message + "\"}");
    }
}
