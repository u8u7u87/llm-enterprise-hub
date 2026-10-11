package com.enterprise.llm.agent.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true"})
class AgentTaskRepositoryTest {

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @BeforeEach
    void setUp() {
        agentTaskRepository.deleteAll();
    }

    @Test
    @DisplayName("Should save, find, and update AgentTaskEntity in SQLite")
    void shouldSaveAndQueryAgentTask() {
        AgentTaskEntity task = new AgentTaskEntity(
                "task-test-001",
                "tenant-a",
                "qwen2.5:0.5b",
                AgentTaskStatus.RUNNING,
                1,
                5,
                "{\"prompt\":\"test\"}",
                "[{\"role\":\"user\",\"content\":\"hi\"}]",
                null,
                0
        );

        agentTaskRepository.save(task);

        Optional<AgentTaskEntity> found = agentTaskRepository.findById("task-test-001");
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(AgentTaskStatus.RUNNING);
        assertThat(found.get().getCurrentIteration()).isEqualTo(1);

        // Update status to SUSPENDED due to interruption
        agentTaskRepository.updateStatus("task-test-001", AgentTaskStatus.SUSPENDED, "Connection timeout during tool execution");

        AgentTaskEntity updated = agentTaskRepository.findById("task-test-001").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AgentTaskStatus.SUSPENDED);
        assertThat(updated.getErrorMessage()).contains("Connection timeout");

        List<AgentTaskEntity> all = agentTaskRepository.findAll();
        assertThat(all).hasSize(1);
    }
}
