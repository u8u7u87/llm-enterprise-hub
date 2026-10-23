package com.enterprise.llm.agent.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentTaskEntity {

    private String taskId;
    private String tenantId;
    private String model;
    private AgentTaskStatus status;
    private int currentIteration;
    private int maxIterations;
    private String requestJson;
    private String historyJson;
    private String errorMessage;
    private int retryCount;

    public AgentTaskEntity() {
    }

    public AgentTaskEntity(
            String taskId,
            String tenantId,
            String model,
            AgentTaskStatus status,
            int currentIteration,
            int maxIterations,
            String requestJson,
            String historyJson,
            String errorMessage,
            int retryCount
    ) {
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.model = model;
        this.status = status;
        this.currentIteration = currentIteration;
        this.maxIterations = maxIterations;
        this.requestJson = requestJson;
        this.historyJson = historyJson;
        this.errorMessage = errorMessage;
        this.retryCount = retryCount;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public AgentTaskStatus getStatus() {
        return status;
    }

    public void setStatus(AgentTaskStatus status) {
        this.status = status;
    }

    public int getCurrentIteration() {
        return currentIteration;
    }

    public void setCurrentIteration(int currentIteration) {
        this.currentIteration = currentIteration;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public void setRequestJson(String requestJson) {
        this.requestJson = requestJson;
    }

    public String getHistoryJson() {
        return historyJson;
    }

    public void setHistoryJson(String historyJson) {
        this.historyJson = historyJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
}
