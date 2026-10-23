package com.enterprise.llm.auth;

import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiKeyAuthenticationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @BeforeEach
    void setUp() {
        tenantService.registerTenant(new TenantEntity(
                "tenant-auth-1",
                "Auth Test Active",
                "sk-auth-valid-key",
                10,
                100000,
                0,
                true
        ));
        tenantService.registerTenant(new TenantEntity(
                "tenant-auth-2",
                "Auth Test Disabled",
                "sk-auth-disabled-key",
                10,
                100000,
                0,
                false
        ));
    }

    @Test
    void shouldRejectWhenApiKeyMissing() throws Exception {
        String json = """
                {"model":"qwen2.5:0.5b","messages":[{"role":"user","content":"hi"}]}
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing API Key (Provide Authorization or X-API-Key header)"));
    }

    @Test
    void shouldRejectWhenApiKeyInvalid() throws Exception {
        String json = """
                {"model":"qwen2.5:0.5b","messages":[{"role":"user","content":"hi"}]}
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "sk-invalid-non-existent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid API Key"));
    }

    @Test
    void shouldRejectWhenTenantIsDisabled() throws Exception {
        String json = """
                {"model":"qwen2.5:0.5b","messages":[{"role":"user","content":"hi"}]}
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "sk-auth-disabled-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Tenant account is disabled"));
    }

    @Test
    void shouldAuthenticateWhenApiKeyValid() throws Exception {
        String json = """
                {"model":"qwen2.5:0.5b","messages":[{"role":"user","content":"hi"}],"stream":false}
                """;

        // Bearer token support
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-auth-valid-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }
}
