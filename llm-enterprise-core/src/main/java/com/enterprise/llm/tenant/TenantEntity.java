package com.enterprise.llm.tenant;

public class TenantEntity {

    private String id;
    private String name;
    private String apiKey;
    private int qpsLimit;
    private int rpmLimit;
    private long tpmLimit;
    private long dailyTokenQuota;
    private long usedTokens;
    private boolean enabled;

    public TenantEntity() {
    }

    public TenantEntity(String id, String name, String apiKey, int qpsLimit, long dailyTokenQuota, long usedTokens, boolean enabled) {
        this(id, name, apiKey, qpsLimit, 0, 0L, dailyTokenQuota, usedTokens, enabled);
    }

    public TenantEntity(String id, String name, String apiKey, int qpsLimit, int rpmLimit, long tpmLimit, long dailyTokenQuota, long usedTokens, boolean enabled) {
        this.id = id;
        this.name = name;
        this.apiKey = apiKey;
        this.qpsLimit = qpsLimit;
        this.rpmLimit = rpmLimit;
        this.tpmLimit = tpmLimit;
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

    public int getRpmLimit() {
        return rpmLimit;
    }

    public void setRpmLimit(int rpmLimit) {
        this.rpmLimit = rpmLimit;
    }

    public long getTpmLimit() {
        return tpmLimit;
    }

    public void setTpmLimit(long tpmLimit) {
        this.tpmLimit = tpmLimit;
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
