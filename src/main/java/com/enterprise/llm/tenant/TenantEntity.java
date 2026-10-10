package com.enterprise.llm.tenant;

public class TenantEntity {

    private String id;
    private String name;
    private String apiKey;
    private int qpsLimit;
    private long dailyTokenQuota;
    private long usedTokens;
    private boolean enabled;

    public TenantEntity() {
    }

    public TenantEntity(String id, String name, String apiKey, int qpsLimit, long dailyTokenQuota, long usedTokens, boolean enabled) {
        this.id = id;
        this.name = name;
        this.apiKey = apiKey;
        this.qpsLimit = qpsLimit;
        this.dailyTokenQuota = dailyTokenQuota;
        this.usedTokens = usedTokens;
        this.enabled = enabled;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getQpsLimit() {
        return qpsLimit;
    }

    public void setQpsLimit(int qpsLimit) {
        this.qpsLimit = qpsLimit;
    }

    public long getDailyTokenQuota() {
        return dailyTokenQuota;
    }

    public void setDailyTokenQuota(long dailyTokenQuota) {
        this.dailyTokenQuota = dailyTokenQuota;
    }

    public long getUsedTokens() {
        return usedTokens;
    }

    public void setUsedTokens(long usedTokens) {
        this.usedTokens = usedTokens;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
