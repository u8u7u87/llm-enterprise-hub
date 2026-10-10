package com.enterprise.llm.auth;

import com.enterprise.llm.tenant.TenantEntity;

public class TenantSecurityContext {

    private static final ThreadLocal<TenantEntity> CURRENT_TENANT = new ThreadLocal<>();

    public static void setTenant(TenantEntity tenant) {
        CURRENT_TENANT.set(tenant);
    }

    public static void setCurrentTenant(TenantEntity tenant) {
        setTenant(tenant);
    }

    public static TenantEntity getTenant() {
        return CURRENT_TENANT.get();
    }

    public static TenantEntity getCurrentTenant() {
        return getTenant();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
