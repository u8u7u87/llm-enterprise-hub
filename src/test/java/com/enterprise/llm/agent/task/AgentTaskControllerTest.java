package com.enterprise.llm.agent.task;

import com.enterprise.llm.admin.AdminProperties;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentTaskController.class)
class AgentTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentTaskManager taskManager;

    @MockBean
    private AdminProperties adminProperties;

    @MockBean
    private LlmClient primaryLlmClient;

    @MockBean
    private com.enterprise.llm.tenant.TenantService tenantService;

    @MockBean
    private com.enterprise.llm.ratelimit.TenantRateLimiter tenantRateLimiter;

    @BeforeEach
    void setUp() {
        when(adminProperties.getSecretKey()).thenReturn("admin-secret-key");
    }

    @Test
    @DisplayName("Should reject task list query without valid X-Admin-Key")
    void shouldRejectWithoutAdminKey() throws Exception {
        mockMvc.perform(get("/v1/agent/tasks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should list agent tasks with valid X-Admin-Key")
    void shouldListAgentTasks() throws Exception {
        AgentTaskEntity task = new AgentTaskEntity(
                "task-123", "tenant-a", "qwen2.5:0.5b",
                AgentTaskStatus.SUSPENDED, 1, 5,
                "{}", "[]", "Simulated error", 0
        );
        when(taskManager.listTasks()).thenReturn(List.of(task));

        mockMvc.perform(get("/v1/agent/tasks")
                        .header("X-Admin-Key", "admin-secret-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].task_id").value("task-123"))
                .andExpect(jsonPath("$[0].status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("Should resume agent task via POST /v1/agent/tasks/{id}/resume")
    void shouldResumeAgentTask() throws Exception {
        ChatCompletionResponse resumeResponse = new ChatCompletionResponse(
                "resp-resume", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Task resumed and completed!"), "stop")),
                new Usage(10, 10, 20)
        );
        when(taskManager.resumeTask(eq("task-123"), any())).thenReturn(resumeResponse);

        mockMvc.perform(post("/v1/agent/tasks/task-123/resume")
                        .header("X-Admin-Key", "admin-secret-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("Task resumed and completed!"));
    }
}
