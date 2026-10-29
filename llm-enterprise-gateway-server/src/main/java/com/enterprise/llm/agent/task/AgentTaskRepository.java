package com.enterprise.llm.agent.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AgentTaskRepository implements AgentTaskStorageProvider {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public AgentTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AgentTaskEntity> ROW_MAPPER = (rs, rowNum) -> new AgentTaskEntity(
            rs.getString("task_id"),
            rs.getString("tenant_id"),
            rs.getString("model"),
            AgentTaskStatus.valueOf(rs.getString("status")),
            rs.getInt("current_iteration"),
            rs.getInt("max_iterations"),
            rs.getString("request_json"),
            rs.getString("history_json"),
            rs.getString("error_message"),
            rs.getInt("retry_count")
    );

    public void save(AgentTaskEntity task) {
        String sql = """
                INSERT INTO agent_tasks (
                    task_id, tenant_id, model, status, current_iteration, max_iterations,
                    request_json, history_json, error_message, retry_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET
                    status = excluded.status,
                    current_iteration = excluded.current_iteration,
                    request_json = excluded.request_json,
                    history_json = excluded.history_json,
                    error_message = excluded.error_message,
                    retry_count = excluded.retry_count,
                    updated_at = CURRENT_TIMESTAMP
                """;
        jdbcTemplate.update(sql,
                task.getTaskId(),
                task.getTenantId(),
                task.getModel(),
                task.getStatus().name(),
                task.getCurrentIteration(),
                task.getMaxIterations(),
                task.getRequestJson(),
                task.getHistoryJson(),
                task.getErrorMessage(),
                task.getRetryCount()
        );
    }

    public Optional<AgentTaskEntity> findById(String taskId) {
        String sql = "SELECT * FROM agent_tasks WHERE task_id = ?";
        List<AgentTaskEntity> list = jdbcTemplate.query(sql, ROW_MAPPER, taskId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<AgentTaskEntity> findAll() {
        String sql = "SELECT * FROM agent_tasks ORDER BY created_at DESC LIMIT 100";
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }

    public List<AgentTaskEntity> findByTenantId(String tenantId) {
        String sql = "SELECT * FROM agent_tasks WHERE tenant_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, tenantId);
    }

    public void updateStatus(String taskId, AgentTaskStatus status, String errorMessage) {
        String sql = "UPDATE agent_tasks SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP WHERE task_id = ?";
        jdbcTemplate.update(sql, status.name(), errorMessage, taskId);
    }

    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM agent_tasks");
    }
}
