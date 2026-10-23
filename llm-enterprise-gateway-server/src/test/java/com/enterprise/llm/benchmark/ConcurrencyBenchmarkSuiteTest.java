package com.enterprise.llm.benchmark;

import com.enterprise.llm.agent.AgentToolOrchestrator;
import com.enterprise.llm.agent.ToolCall;
import com.enterprise.llm.agent.ToolDefinition;
import com.enterprise.llm.agent.ToolRegistry;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.metrics.LlmMetricsCollector;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.session.ChatSession;
import com.enterprise.llm.session.SqliteSessionStorageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:sqlite:target/benchmark-test.db?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.connection-timeout=15000",
        "milvus.endpoint=disabled"
})
class ConcurrencyBenchmarkSuiteTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyBenchmarkSuiteTest.class);

    @Autowired
    private SqliteSessionStorageProvider sqliteStorageProvider;

    @Autowired(required = false)
    private LlmMetricsCollector metricsCollector;

    @Test
    @DisplayName("Benchmark 1: SQLite WAL mode multi-tenant concurrent session persistence under virtual thread load")
    void benchmarkSqliteConcurrentWrites() throws InterruptedException {
        int totalSessions = 100;
        CountDownLatch latch = new CountDownLatch(totalSessions);
        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger failureCounter = new AtomicInteger(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        long startWallTime = System.currentTimeMillis();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < totalSessions; i++) {
                final int idx = i;
                executor.submit(() -> {
                    long opStart = System.currentTimeMillis();
                    try {
                        String tenantId = "tenant-" + (idx % 10);
                        String sessionId = "bench-sess-" + idx;

                        ChatSession session = new ChatSession(
                                sessionId,
                                tenantId,
                                "user-" + idx,
                                "Benchmark Session " + idx,
                                Map.of("loadTest", "true", "idx", String.valueOf(idx)),
                                Instant.now(),
                                Instant.now(),
                                Collections.emptyList()
                        );
                        sqliteStorageProvider.saveSession(session);

                        List<ChatMessage> messages = List.of(
                                ChatMessage.user("Hello system " + idx),
                                ChatMessage.assistant("Response from system " + idx)
                        );
                        sqliteStorageProvider.appendMessages(tenantId, sessionId, messages);

                        latencies.add(System.currentTimeMillis() - opStart);
                        successCounter.incrementAndGet();
                    } catch (Exception e) {
                        failureCounter.incrementAndGet();
                        log.error("Failed writing session {}", idx, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(30, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
        }

        long totalDurationMs = Math.max(1, System.currentTimeMillis() - startWallTime);
        double throughput = (successCounter.get() * 1000.0) / totalDurationMs;

        List<Long> latencyList = new ArrayList<>(latencies);
        Collections.sort(latencyList);
        long p50 = latencyList.isEmpty() ? 0 : latencyList.get((int) (latencyList.size() * 0.5));
        long p99 = latencyList.isEmpty() ? 0 : latencyList.get((int) (latencyList.size() * 0.99));

        log.info("""
                ============================================================
                [BENCHMARK REPORT] SQLite WAL Concurrent Session Persistence
                ============================================================
                Total Requests : {}
                Success Count  : {} (100% expected)
                Failure Count  : {}
                Total Time     : {} ms
                Throughput     : {} ops/sec
                P50 Latency    : {} ms
                P99 Latency    : {} ms
                ============================================================""",
                totalSessions, successCounter.get(), failureCounter.get(), totalDurationMs,
                String.format("%.2f", throughput), p50, p99
        );

        assertThat(failureCounter.get()).isZero();
        assertThat(successCounter.get()).isEqualTo(totalSessions);
        assertThat(throughput).isGreaterThan(50.0);
    }

    @Test
    @DisplayName("Benchmark 2: Tool parallel acceleration ratio via Java 21 Virtual Threads (Serial vs Parallel)")
    void benchmarkVirtualThreadToolParallelism() throws Exception {
        int toolCount = 8;
        int toolSleepMs = 40;

        ToolRegistry registry = new ToolRegistry();
        List<ToolDefinition> toolDefinitions = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();

        for (int i = 0; i < toolCount; i++) {
            String name = "io_mock_tool_" + i;
            ToolDefinition def = new ToolDefinition(name, "Simulates remote I/O", Map.of());
            registry.registerTool(def, args -> {
                try {
                    Thread.sleep(toolSleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "{\"status\":\"ok\"}";
            });
            toolDefinitions.add(def);
            toolCalls.add(new ToolCall(
                    "call_" + i,
                    "function",
                    new ToolCall.FunctionCall(name, Map.of())
            ));
        }

        // Measure Serial Execution
        long serialStart = System.currentTimeMillis();
        for (var tc : toolCalls) {
            registry.getTool(tc.function().name()).get().handler().execute(Map.of());
        }
        long serialDuration = System.currentTimeMillis() - serialStart;

        // Measure Orchestrator Concurrent Execution via Virtual Threads
        AgentToolOrchestrator orchestrator = new AgentToolOrchestrator(registry);
        if (metricsCollector != null) {
            orchestrator.setMetricsCollector(metricsCollector);
        }

        // Mock LLM Client that returns toolCalls on first call, and text response on second call
        AtomicInteger step = new AtomicInteger(0);
        LlmClient mockLlmClient = new LlmClient() {
            @Override
            public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
                if (step.getAndIncrement() == 0) {
                    return new ChatCompletionResponse(
                            "resp-1",
                            "chat.completion",
                            System.currentTimeMillis() / 1000,
                            "mock-model",
                            List.of(new ChatCompletionResponse.Choice(
                                    0,
                                    ChatMessage.assistantWithTools("", toolCalls),
                                    "tool_calls"
                            )),
                            null
                    );
                }
                return new ChatCompletionResponse(
                        "resp-2",
                        "chat.completion",
                        System.currentTimeMillis() / 1000,
                        "mock-model",
                        List.of(new ChatCompletionResponse.Choice(
                                0,
                                ChatMessage.assistant("Final answered after all tools completed."),
                                "stop"
                        )),
                        null
                );
            }

            @Override
            public StreamCancellationHandle streamChatCompletion(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
                return new StreamCancellationHandle(() -> {});
            }
        };

        ChatCompletionRequest request = new ChatCompletionRequest(
                "mock-model",
                List.of(ChatMessage.user("Execute all 8 tools concurrently")),
                false,
                0.7,
                100,
                toolDefinitions,
                null
        );

        long parallelStart = System.currentTimeMillis();
        ChatCompletionResponse finalResponse = orchestrator.runLoop(mockLlmClient, request, "tenant-bench", 3);
        long parallelDuration = System.currentTimeMillis() - parallelStart;

        double speedup = (double) serialDuration / Math.max(1, parallelDuration);

        log.info("""
                ============================================================
                [BENCHMARK REPORT] Virtual Thread Tool Parallelism
                ============================================================
                Tools Count       : {} (Each sleep {} ms)
                Serial Duration   : {} ms
                Parallel Duration : {} ms
                Speedup Ratio     : {}x
                ============================================================""",
                toolCount, toolSleepMs, serialDuration, parallelDuration, String.format("%.2f", speedup)
        );

        assertThat(finalResponse).isNotNull();
        assertThat(finalResponse.choices().get(0).message().content())
                .contains("Final answered after all tools completed");
        // Speedup must exceed 2.0x (theoretically close to 4x~6x for 8 parallel tasks)
        assertThat(speedup).isGreaterThanOrEqualTo(2.0);
    }
}
