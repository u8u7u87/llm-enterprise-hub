package com.enterprise.llm.agent.task;

import java.util.Map;
import com.enterprise.llm.admin.AdminProperties;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.model.ChatCompletionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/agent/tasks")
public class AgentTaskController {

    private final AgentTaskManager taskManager;
    private final AdminProperties adminProperties;
    private final LlmClient primaryLlmClient;

    @Autowired
    public AgentTaskController(
            AgentTaskManager taskManager,
            AdminProperties adminProperties,
            @Autowired(required = false) LlmClient primaryLlmClient
    ) {
        this.taskManager = taskManager;
        this.adminProperties = adminProperties;
        this.primaryLlmClient = primaryLlmClient;
    }

    private ResponseEntity<?> verifyAdminKey(String adminKey) {
        if (adminKey == null || !adminProperties.getSecretKey().equals(adminKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", 401, "error", "Unauthorized: Invalid or missing admin key"));
        }
        return null;
    }

    @GetMapping
    public Object listTasks(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        var authCheck = verifyAdminKey(adminKey);
        if (authCheck != null) return authCheck;

        return ResponseEntity.ok(taskManager.listTasks());
    }

    @GetMapping("/{id}")
    public Object getTask(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @PathVariable("id") String taskId
    ) {
        var authCheck = verifyAdminKey(adminKey);
        if (authCheck != null) return authCheck;

        return taskManager.getTask(taskId)
                .map(t -> (Object) ResponseEntity.ok(t))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", 404, "error", "Task not found: " + taskId)));
    }

    @PostMapping("/{id}/resume")
    public Object resumeTask(
            @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
            @PathVariable("id") String taskId
    ) {
        var authCheck = verifyAdminKey(adminKey);
        if (authCheck != null) return authCheck;

        try {
            ChatCompletionResponse response = taskManager.resumeTask(taskId, primaryLlmClient);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", 400, "error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", 500, "error", "Failed to resume task: " + e.getMessage()));
        }
    }
}
