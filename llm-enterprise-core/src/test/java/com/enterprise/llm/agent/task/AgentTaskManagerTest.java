package com.enterprise.llm.agent.task;

import com.enterprise.llm.agent.AgentToolOrchestrator;
import com.enterprise.llm.agent.ToolCall;
import com.enterprise.llm.agent.ToolRegistry;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.guard.ToolActionGatekeeper;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentTaskManagerTest {

    private AgentTaskStorageProvider agentTaskRepository;
    private ToolRegistry toolRegistry;
    private AgentToolOrchestrator orchestrator;
    private AgentTaskManager taskManager;
    private LlmClient mockLlmClient;

    @BeforeEach
    void setUp() {
        agentTaskRepository = new InMemoryAgentTaskStorageProvider();
        agentTaskRepository.deleteAll();
        toolRegistry = new ToolRegistry();
        orchestrator = new AgentToolOrchestrator(toolRegistry, new ToolActionGatekeeper());
        mockLlmClient = mock(LlmClient.class);
        taskManager = new AgentTaskManager(agentTaskRepository, orchestrator, mockLlmClient);
    }

    @Test
    @DisplayName("Should successfully suspend task on tool failure, and resume from checkpoint to complete")
    void shouldSuspendAndResumeAgentTask() {
        AtomicInteger toolCallCount = new AtomicInteger(0);

        // Tool fails on first attempt, succeeds on second attempt
        toolRegistry.registerTool("fetch_remote_data", "Fetch remote API data", Map.of(), args -> {
            int attempt = toolCallCount.incrementAndGet();
            if (attempt == 1) {
                throw new RuntimeException("Upstream connection refused");
            }
            return "Remote data fetched successfully: 42";
        });

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("Fetch the data")),
                false,
                0.7,
                null,
                toolRegistry.getAllDefinitions().stream().toList(),
                "auto"
        );

        // Turn 1: Model requests tool call
        ToolCall toolCall = new ToolCall("call_1", "fetch_remote_data", Map.of("key", "val"));
        ChatCompletionResponse turn1Response = new ChatCompletionResponse(
                "resp-1", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools(null, List.of(toolCall)), "tool_calls")),
                new Usage(10, 10, 20)
        );

        // Turn 2: Model outputs final answer after receiving tool result
        ChatCompletionResponse turn2Response = new ChatCompletionResponse(
                "resp-2", "chat.completion", 1001L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("The remote result is 42."), "stop")),
                new Usage(20, 10, 30)
        );

        when(mockLlmClient.chatCompletion(any()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        // 1. Create task
        AgentTaskEntity task = taskManager.createTask(request, "tenant-test");
        String taskId = task.getTaskId();
        assertThat(task.getStatus()).isEqualTo(AgentTaskStatus.PENDING);

        // 2. Execute task: turn 1 tool fails
        // In this case, the tool handler caught exception and returned an error result into conversation,
        // then next turn model receives error or if client throws exception, it suspends.
        // Let's test client exception simulation for turn 2 to simulate mid-turn network interrupt:
        when(mockLlmClient.chatCompletion(any()))
                .thenReturn(turn1Response)
                .thenThrow(new RuntimeException("Simulated network drop during Turn 2"));

        assertThatThrownBy(() -> taskManager.executeTask(taskId, mockLlmClient))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated network drop");

        AgentTaskEntity suspendedTask = agentTaskRepository.findById(taskId).orElseThrow();
        assertThat(suspendedTask.getStatus()).isEqualTo(AgentTaskStatus.SUSPENDED);
        assertThat(suspendedTask.getCurrentIteration()).isEqualTo(1);
        assertThat(suspendedTask.getHistoryJson()).contains("call_1"); // Checkpoint preserved!

        // 3. Resume task: network restored, model successfully finishes turn 2
        org.mockito.Mockito.reset(mockLlmClient);
        when(mockLlmClient.chatCompletion(any()))
                .thenReturn(turn2Response);

        ChatCompletionResponse finalResponse = taskManager.resumeTask(taskId, mockLlmClient);
        assertThat(finalResponse).isNotNull();
        assertThat(finalResponse.choices().get(0).message().content()).isEqualTo("The remote result is 42.");

        AgentTaskEntity completedTask = agentTaskRepository.findById(taskId).orElseThrow();
        assertThat(completedTask.getStatus()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(completedTask.getRetryCount()).isEqualTo(1);
    }
}
