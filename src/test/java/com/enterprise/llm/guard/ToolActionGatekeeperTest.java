package com.enterprise.llm.guard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolActionGatekeeperTest {

    private ToolActionGatekeeper gatekeeper;

    @BeforeEach
    void setUp() {
        gatekeeper = new ToolActionGatekeeper();
    }

    @Test
    void shouldAllowSafeReadOnlyAction() {
        ToolAction safeAction = new ToolAction(
                "query_customer_orders",
                Map.of("tenant_id", "tenant-alpha", "order_id", "ord-123")
        );

        boolean allowed = gatekeeper.isAllowed(safeAction, "tenant-alpha");
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldBlockCrossTenantDataAccessException() {
        // Model was manipulated to query tenant-bravo data while authenticated as tenant-alpha
        ToolAction crossTenantAction = new ToolAction(
                "get_invoice_details",
                Map.of("tenant_id", "tenant-bravo", "invoice_id", "inv-999")
        );

        assertThatThrownBy(() -> gatekeeper.validateAction(crossTenantAction, "tenant-alpha"))
                .isInstanceOf(CrossTenantViolationException.class)
                .hasMessageContaining("Cross-tenant access violation");
    }

    @Test
    void shouldBlockDangerousRmCommand() {
        ToolAction dangerousAction = new ToolAction(
                "bash_exec",
                Map.of("command", "rm -rf /var/data/tenants")
        );

        assertThatThrownBy(() -> gatekeeper.validateAction(dangerousAction, "tenant-alpha"))
                .isInstanceOf(SecurityViolationException.class)
                .hasMessageContaining("Dangerous tool execution attempt blocked");
    }

    @Test
    void shouldBlockDestructiveSqlDropTable() {
        ToolAction dangerousAction = new ToolAction(
                "execute_sql",
                Map.of("query", "DROP TABLE users;")
        );

        assertThatThrownBy(() -> gatekeeper.validateAction(dangerousAction, "tenant-alpha"))
                .isInstanceOf(SecurityViolationException.class)
                .hasMessageContaining("Dangerous tool execution attempt blocked");
    }

    @Test
    void shouldBlockTruncateTable() {
        ToolAction dangerousAction = new ToolAction(
                "execute_sql",
                Map.of("query", "TRUNCATE TABLE billing_records")
        );

        assertThatThrownBy(() -> gatekeeper.validateAction(dangerousAction, "tenant-alpha"))
                .isInstanceOf(SecurityViolationException.class)
                .hasMessageContaining("Dangerous tool execution attempt blocked");
    }

    @Test
    void shouldBlockDynamicallyAddedDangerPattern() {
        gatekeeper.addDangerPattern("(?i)python\\s+-c\\s+.*socket");

        ToolAction dynamicDangerous = new ToolAction(
                "run_script",
                Map.of("cmd", "python -c 'import socket; s=socket.socket()'")
        );

        assertThatThrownBy(() -> gatekeeper.validateAction(dynamicDangerous, "tenant-alpha"))
                .isInstanceOf(SecurityViolationException.class)
                .hasMessageContaining("Dangerous tool execution attempt blocked by dynamic rule");
    }
}
