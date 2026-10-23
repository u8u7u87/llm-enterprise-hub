package com.enterprise.llm.agent.task;

import com.enterprise.llm.agent.AgentToolOrchestrator;
import com.enterprise.llm.agent.ToolRegistry;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AgentTaskManager {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskManager.class);

    private final AgentTaskStorageProvider taskRepository;
    private final AgentToolOrchestrator agentToolOrchestrator;
    private final LlmClient primaryLlmClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentTaskManager(
            AgentTaskStorageProvider taskRepository,
            AgentToolOrchestrator agentToolOrchestrator,
            @Autowired(required = false) LlmClient primaryLlmClient
    ) {
        this.taskRepository = taskRepository;
        this.agentToolOrchestrator = agentToolOrchestrator;
        this.primaryLlmClient = primaryLlmClient;
        this.objectMapper = new ObjectMapper();
    }

    public AgentTaskEntity createTask(ChatCompletionRequest request, String tenantId) {
        String taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);
        String requestJson = null;
        String historyJson = null;
        try {
            requestJson = objectMapper.writeValueAsString(request);
            historyJson = objectMapper.writeValueAsString(request.messages());
        } catch (Exception e) {
            log.warn("Failed to serialize request/messages for task {}", taskId, e);
        }

        AgentTaskEntity task = new AgentTaskEntity(
                taskId,
                tenantId != null ? tenantId : "default",
                request.model(),
                AgentTaskStatus.PENDING,
                0,
                AgentToolOrchestrator.DEFAULT_MAX_ITERATIONS,
                requestJson,
                historyJson,
                null,
                0
        );
        taskRepository.save(task);
        return task;
    }

    public ChatCompletionResponse executeTask(String taskId, LlmClient client) {
        AgentTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent task not found: " + taskId));

        LlmClient targetClient = client != null ? client : primaryLlmClient;
        if (targetClient == null) {
            throw new IllegalStateException("No LlmClient available to execute task " + taskId);
        }

        task.setStatus(AgentTaskStatus.RUNNING);
        taskRepository.save(task);

        try {
            ChatCompletionRequest request = objectMapper.readValue(task.getRequestJson(), ChatCompletionRequest.class);
            List<ChatMessage> history = new ArrayList<>();
            if (task.getHistoryJson() != null && !task.getHistoryJson().isBlank()) {
                history = objectMapper.readValue(task.getHistoryJson(), new TypeReference<List<ChatMessage>>() {});
            }

            ChatCompletionResponse response = agentToolOrchestrator.runLoopWithCheckpoint(
                    targetClient,
                    request,
                    history,
                    task.getTenantId(),
                    task.getCurrentIteration(),
                    task.getMaxIterations(),
                    (currentIter, updatedHistory) -> {
                        task.setCurrentIteration(currentIter);
                        try {
                            task.setHistoryJson(objectMapper.writeValueAsString(updatedHistory));
                        } catch (Exception ignored) {
                        }
                        taskRepository.save(task);
                    }
            );

            task.setStatus(AgentTaskStatus.COMPLETED);
            task.setErrorMessage(null);
            taskRepository.save(task);
            return response;
        } catch (Exception e) {
            log.error("Agent task {} execution interrupted or failed: {}", taskId, e.getMessage(), e);
            task.setStatus(AgentTaskStatus.SUSPENDED);
            task.setErrorMessage(e.getMessage());
            taskRepository.save(task);
            throw new RuntimeException("Task execution interrupted: " + e.getMessage(), e);
        }
    }

    public ChatCompletionResponse resumeTask(String taskId, LlmClient client) {
        AgentTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Agent task not found: " + taskId));

        if (task.getStatus() == AgentTaskStatus.COMPLETED) {
            throw new IllegalStateException("Task " + taskId + " has already completed and cannot be resumed");
        }

        task.setRetryCount(task.getRetryCount() + 1);
        task.setStatus(AgentTaskStatus.RUNNING);
        taskRepository.save(task);

        return executeTask(taskId, client);
    }

    public List<AgentTaskEntity> listTasks() {
        return taskRepository.findAll();
    }

    public Optional<AgentTaskEntity> getTask(String taskId) {
        return taskRepository.findById(taskId);
    }
}
