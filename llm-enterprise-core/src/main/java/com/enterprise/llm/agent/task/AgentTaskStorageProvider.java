package com.enterprise.llm.agent.task;

import java.util.List;
import java.util.Optional;

/**
 * Storage SPI for Agent task state machines and execution checkpoints.
 * Implementations may target SQLite, JPA, Redis or distributed message queues.
 */
public interface AgentTaskStorageProvider {

    void save(AgentTaskEntity task);

    Optional<AgentTaskEntity> findById(String taskId);

    List<AgentTaskEntity> findAll();

    List<AgentTaskEntity> findByTenantId(String tenantId);

    void updateStatus(String taskId, AgentTaskStatus status, String errorMessage);

    void deleteAll();
}
