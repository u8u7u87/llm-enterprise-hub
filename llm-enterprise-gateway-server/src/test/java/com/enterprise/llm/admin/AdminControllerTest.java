package com.enterprise.llm.admin;

import java.util.List;
import com.enterprise.llm.metrics.MetricsRecordService;
import com.enterprise.llm.ratelimit.TenantRateLimiter;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantRepository;
import com.enterprise.llm.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantRepository tenantRepository;

    @MockBean
    private AnalyticsService analyticsService;

    @MockBean
    private AdminProperties adminProperties;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private TenantRateLimiter tenantRateLimiter;

    @MockBean
    private MetricsRecordService metricsRecordService;

    @BeforeEach
    void setUp() {
        when(adminProperties.getSecretKey()).thenReturn("admin-secret-key");
    }

    @Test
    void shouldRejectAdminRequestWithoutValidKey() throws Exception {
        mockMvc.perform(get("/v1/admin/tenants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldListTenantsWithValidKey() throws Exception {
        when(tenantRepository.findAll()).thenReturn(List.of(
                new TenantEntity("t-1", "Corp 1", "key-1", 10, 1000, 100, true)
        ));

        mockMvc.perform(get("/v1/admin/tenants")
                        .header("X-Admin-Key", "admin-secret-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t-1"))
                .andExpect(jsonPath("$[0].name").value("Corp 1"));
    }

    @Test
    void shouldCreateNewTenant() throws Exception {
        mockMvc.perform(post("/v1/admin/tenants")
                        .header("X-Admin-Key", "admin-secret-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "t-new",
                                  "name": "New Tenant",
                                  "api_key": "sk-new-123",
                                  "qps_limit": 20,
                                  "daily_token_quota": 500000,
                                  "used_tokens": 0,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t-new"));

        verify(tenantRepository).save(any(TenantEntity.class));
    }

    @Test
    void shouldUpdateTenantQuota() throws Exception {
        mockMvc.perform(put("/v1/admin/tenants/t-1/quota")
                        .header("X-Admin-Key", "admin-secret-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "daily_token_quota": 2000000,
                                  "used_tokens": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(tenantRepository).updateQuota("t-1", 2000000L, 0L);
    }

    @Test
    void shouldUpdateTenantStatus() throws Exception {
        mockMvc.perform(patch("/v1/admin/tenants/t-1/status")
                        .header("X-Admin-Key", "admin-secret-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(tenantRepository).updateEnabled("t-1", false);
    }

    @Test
    void shouldReturnAnalyticsOverview() throws Exception {
        when(analyticsService.getOverview()).thenReturn(
                new AdminAnalyticsOverview(10, 8, 50000, 12000, 15, 5, 10, 66.7)
        );

        mockMvc.perform(get("/v1/admin/analytics/overview")
                        .header("X-Admin-Key", "admin-secret-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_tenants").value(10))
                .andExpect(jsonPath("$.cache_saved_tokens").value(12000));
    }
}
