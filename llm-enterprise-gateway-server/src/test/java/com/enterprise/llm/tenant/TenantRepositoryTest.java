package com.enterprise.llm.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TenantRepositoryTest {

    @Autowired
    private TenantRepository tenantRepository;

    @BeforeEach
    void setUp() {
        tenantRepository.deleteAll();
    }

    @Test
    void shouldSaveAndFindByApiKey() {
        TenantEntity tenant = new TenantEntity(
                "tenant-001",
                "Finance Department",
                "sk-hub-fin-123456",
                20,
                500000,
                0,
                true
        );

        tenantRepository.save(tenant);

        Optional<TenantEntity> found = tenantRepository.findByApiKey("sk-hub-fin-123456");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("tenant-001");
        assertThat(found.get().getName()).isEqualTo("Finance Department");
        assertThat(found.get().getQpsLimit()).isEqualTo(20);
        assertThat(found.get().getDailyTokenQuota()).isEqualTo(500000);
        assertThat(found.get().isEnabled()).isTrue();
    }

    @Test
    void shouldIncrementUsedTokens() {
        TenantEntity tenant = new TenantEntity(
                "tenant-002",
                "R&D Department",
                "sk-hub-rd-888888",
                50,
                1000000,
                100,
                true
        );
        tenantRepository.save(tenant);

        tenantRepository.incrementUsedTokens("tenant-002", 450);

        Optional<TenantEntity> updated = tenantRepository.findById("tenant-002");
        assertThat(updated).isPresent();
        assertThat(updated.get().getUsedTokens()).isEqualTo(550);
    }
}
