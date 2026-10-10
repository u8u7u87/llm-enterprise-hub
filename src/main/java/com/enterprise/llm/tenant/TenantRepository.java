package com.enterprise.llm.tenant;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class TenantRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<TenantEntity> rowMapper = (rs, rowNum) -> new TenantEntity(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("api_key"),
            rs.getInt("qps_limit"),
            rs.getLong("daily_token_quota"),
            rs.getLong("used_tokens"),
            rs.getInt("enabled") == 1
    );

    public TenantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(TenantEntity tenant) {
        String sql = """
                INSERT INTO tenants (id, name, api_key, qps_limit, daily_token_quota, used_tokens, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    api_key = excluded.api_key,
                    qps_limit = excluded.qps_limit,
                    daily_token_quota = excluded.daily_token_quota,
                    used_tokens = excluded.used_tokens,
                    enabled = excluded.enabled
                """;
        jdbcTemplate.update(
                sql,
                tenant.getId(),
                tenant.getName(),
                tenant.getApiKey(),
                tenant.getQpsLimit(),
                tenant.getDailyTokenQuota(),
                tenant.getUsedTokens(),
                tenant.isEnabled() ? 1 : 0
        );
    }

    public Optional<TenantEntity> findById(String id) {
        try {
            String sql = "SELECT * FROM tenants WHERE id = ?";
            TenantEntity entity = jdbcTemplate.queryForObject(sql, rowMapper, id);
            return Optional.ofNullable(entity);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<TenantEntity> findByApiKey(String apiKey) {
        try {
            String sql = "SELECT * FROM tenants WHERE api_key = ?";
            TenantEntity entity = jdbcTemplate.queryForObject(sql, rowMapper, apiKey);
            return Optional.ofNullable(entity);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void incrementUsedTokens(String id, long tokens) {
        String sql = "UPDATE tenants SET used_tokens = used_tokens + ? WHERE id = ?";
        jdbcTemplate.update(sql, tokens, id);
    }

    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM tenants");
    }
}
