package com.enterprise.llm.agent;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.AgentStreamEvent;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Event-Driven Streaming ReAct Agent Orchestrator.
 * Coordinates multi-turn interactive tool calling over streaming SSE connections:
 * - Detects and accumulates partial tool call deltas;
 * - Dispatches intermediate lifecycle events (tool_call_start, tool_call_result, agent_thought);
 * - Executes tools securely via AgentToolOrchestrator;
 * - Loops multi-turn completion until final typewriter text streaming and completion.
 */
@Service
public class StreamingAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StreamingAgentOrchestrator.class);
    public static final int DEFAULT_MAX_ITERATIONS = 5;

    private final AgentToolOrchestrator agentToolOrchestrator;
    private final ObjectMapper objectMapper;

    @Autowired
    public StreamingAgentOrchestrator(AgentToolOrchestrator agentToolOrchestrator) {
        this.agentToolOrchestrator = agentToolOrchestrator;
        this.objectMapper = new ObjectMapper();
    }

    public StreamingAgentOrchestrator() {
        this(new AgentToolOrchestrator());
    }

    public StreamCancellationHandle runStreamingLoop(
            LlmClient llmClient,
            ChatCompletionRequest initialRequest,
            String tenantId,
            int maxIterations,
            Consumer<ChatCompletionChunk> onChunk,
            Consumer<AgentStreamEvent> onAgentEvent,
            Runnable onComplete,
            Consumer<Throwable> onError
    ) {
        if (llmClient == null) {
            throw new IllegalArgumentException("LlmClient must not be null");
        }
        if (initialRequest == null) {
            throw new IllegalArgumentException("ChatCompletionRequest must not be null");
        }

        List<ChatMessage> conversationHistory = new ArrayList<>(
                initialRequest.messages() != null ? initialRequest.messages() : Collections.emptyList()
        );

        AtomicInteger iteration = new AtomicInteger(0);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        StreamCancellationHandle[] activeHandleHolder = new StreamCancellationHandle[1];

        StreamCancellationHandle masterHandle = new StreamCancellationHandle(() -> {
            cancelled.set(true);
            if (activeHandleHolder[0] != null) {
                activeHandleHolder[0].cancel();
            }
        });

        Runnable turnExecutor = new Runnable() {
            @Override
            public void run() {
                if (cancelled.get()) {
                    return;
                }

                int currentIteration = iteration.incrementAndGet();
                if (currentIteration > maxIterations) {
                    log.warn("Streaming agent reached maximum iterations ({}), completing stream", maxIterations);
                    if (onComplete != null) onComplete.run();
                    return;
                }

                log.debug("Streaming agent turn {}/{}", currentIteration, maxIterations);

                ChatCompletionRequest turnRequest = new ChatCompletionRequest(
                        initialRequest.model(),
                        new ArrayList<>(conversationHistory),
                        true,
                        initialRequest.temperature(),
                        initialRequest.maxTokens(),
                        initialRequest.tools(),
                        initialRequest.toolChoice()
                );

                StreamChunkAccumulator accumulator = new StreamChunkAccumulator();

                activeHandleHolder[0] = llmClient.streamChatCompletion(
                        turnRequest,
                        chunk -> {
                            if (cancelled.get()) return;
                            accumulator.append(chunk);

                            // If chunk contains text content, stream directly to client
                            if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                                var choice = chunk.choices().get(0);
                                if (choice.delta() != null && choice.delta().content() != null && !choice.delta().content().isEmpty()) {
                                    if (onChunk != null) {
                                        onChunk.accept(chunk);
                                    }
                                }
                            }
                        },
                        () -> {
                            if (cancelled.get()) return;

                            if (!accumulator.hasToolCalls()) {
                                // No tool calls: final text response turn completed
                                if (onComplete != null) {
                                    onComplete.run();
                                }
                            } else {
                                // Tool calls emitted by LLM!
                                List<ToolCall> toolCalls = accumulator.getAccumulatedToolCalls();
                                log.info("Streaming agent iteration {} captured {} tool calls", currentIteration, toolCalls.size());

                                ChatMessage assistantMsg = ChatMessage.assistantWithTools(
                                        accumulator.getAccumulatedContent(), toolCalls
                                );
                                conversationHistory.add(assistantMsg);

                                for (ToolCall tc : toolCalls) {
                                    if (cancelled.get()) return;

                                    String callId = tc.id();
                                    String toolName = (tc.function() != null && tc.function().name() != null) ? tc.function().name() : "";
                                    String argsStr = "";
                                    try {
                                        argsStr = objectMapper.writeValueAsString(
                                                tc.function() != null && tc.function().arguments() != null ? tc.function().arguments() : Map.of()
                                        );
                                    } catch (JsonProcessingException e) {
                                        // 工具入参只是事件回显用的展示字段，序列化失败不应阻断工具执行
                                        log.debug("Failed to serialize arguments of tool [{}] for event stream: {}",
                                                toolName, e.getMessage());
                                    }

                                    if (onAgentEvent != null) {
                                        onAgentEvent.accept(AgentStreamEvent.toolStart(callId, toolName, argsStr));
                                    }

                                    long startTime = System.currentTimeMillis();
                                    ChatMessage toolResultMsg = agentToolOrchestrator.executeToolCall(tc, tenantId);
                                    long duration = System.currentTimeMillis() - startTime;

                                    if (onAgentEvent != null) {
                                        onAgentEvent.accept(AgentStreamEvent.toolResult(callId, toolName, toolResultMsg.content(), duration));
                                    }

                                    conversationHistory.add(toolResultMsg);
                                }

                                // Trigger next streaming turn
                                run();
                            }
                        },
                        ex -> {
                            if (!cancelled.get() && onError != null) {
                                onError.accept(ex);
                            }
                        }
                );
            }
        };

        turnExecutor.run();
        return masterHandle;
    }
}
