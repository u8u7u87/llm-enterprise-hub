package com.enterprise.llm.agent.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fallback implementation of {@link AgentTaskStorageProvider}.
 */
public class InMemoryAgentTaskStorageProvider implements AgentTaskStorageProvider {

    private final Map<String, AgentTaskEntity> storage = new ConcurrentHashMap<>();

    @Override
    public void save(AgentTaskEntity task) {
        if (task != null && task.getTaskId() != null) {
            storage.put(task.getTaskId(), task);
        }
    }

    @Override
    public Optional<AgentTaskEntity> findById(String taskId) {
        return taskId != null ? Optional.ofNullable(storage.get(taskId)) : Optional.empty();
    }

    @Override
    public List<AgentTaskEntity> findAll() {
        return new ArrayList<>(storage.values());
    }

    @Override
    public List<AgentTaskEntity> findByTenantId(String tenantId) {
        if (tenantId == null) {
            return List.of();
        }
        return storage.values().stream()
                .filter(t -> tenantId.equals(t.getTenantId()))
                .toList();
    }

    @Override
    public void updateStatus(String taskId, AgentTaskStatus status, String errorMessage) {
        AgentTaskEntity task = storage.get(taskId);
        if (task != null) {
            task.setStatus(status);
            task.setErrorMessage(errorMessage);
        }
    }

    @Override
    public void deleteAll() {
        storage.clear();
    }
}
