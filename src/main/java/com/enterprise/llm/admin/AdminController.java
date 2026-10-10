package com.enterprise.llm.admin;

import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    private final AdminProperties adminProperties;
    private final TenantRepository tenantRepository;
    private final AnalyticsService analyticsService;

    public AdminController(
            AdminProperties adminProperties,
            TenantRepository tenantRepository,
            AnalyticsService analyticsService
    ) {
        this.adminProperties = adminProperties;
        this.tenantRepository = tenantRepository;
        this.analyticsService = analyticsService;
    }

    private ResponseEntity<?> verifyAdminKey(String adminKey) {
        if (adminKey == null || !adminProperties.getSecretKey().equals(adminKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", 401, "error", "Unauthorized: Invalid or missing admin key"));
        }
        return null;
    }

    @GetMapping("/tenants")
    public Object listTenants(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        var authCheck = verifyAdminKey(adminKey);
        if (authCheck != null) return authCheck;

        List<TenantEntity> tenants = tenantRepository.findAll();
        return ResponseEntity.ok(tenants);
    }

    @PostMapping("/tenants")
    public Object createOrUpdateTenant(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @RequestBody TenantEntity tenant
    ) {
        var authCheck = verifyAdminKey(adminKey);
        if (authCheck != null) return authCheck;

        tenantRepository.save(tenant);
        return ResponseEntity.ok(tenant);
    }

    @PutMapping("/tenants/{id}/quota")
    public Object updateQuota(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> body
    ) {
        var authCheck = verifyAdminKey(adminKey);
        if (authCheck != null) return authCheck;

        long quota = body.containsKey("daily_token_quota") ? ((Number) body.get("daily_token_quota")).longValue() : 1000000L;
        Long usedTokens = body.containsKey("used_tokens") ? ((Number) body.get("used_tokens")).longValue() : null;

        tenantRepository.updateQuota(id, quota, usedTokens);
        return ResponseEntity.ok(Map.of("status", "success", "id", id, "daily_token_quota", quota));
    }

    @PatchMapping("/tenants/{id}/status")
    public Object updateStatus(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @PathVariable("id") String id,
            @RequestBody Map<String, Boolean> body
    ) {
        var authCheck = verifyAdminKey(adminKey);
        if (authCheck != null) return authCheck;

        boolean enabled = body.getOrDefault("enabled", true);
        tenantRepository.updateEnabled(id, enabled);
        return ResponseEntity.ok(Map.of("status", "success", "id", id, "enabled", enabled));
    }

    @GetMapping("/analytics/overview")
    public Object getAnalyticsOverview(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        var authCheck = verifyAdminKey(adminKey);
        if (authCheck != null) return authCheck;

        return ResponseEntity.ok(analyticsService.getOverview());
    }
}
