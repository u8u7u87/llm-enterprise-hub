package com.enterprise.llm.auth;

import java.util.List;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantService;
import com.enterprise.llm.trace.LlmSpan;
import com.enterprise.llm.trace.TraceCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TraceFilterAndMdcIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TraceCollector traceCollector;

    @Autowired
    private TenantService tenantService;

    @MockBean(name = "primaryLlmClient")
    private LlmClient primaryLlmClient;

    @BeforeEach
    void setUp() {
        tenantService.registerTenant(new TenantEntity(
                "tenant-alpha",
                "Tenant Alpha",
                "sk-alpha-secret",
                100,
                1000000,
                0,
                true
        ));
    }

    @Test
    @DisplayName("Verify TraceFilter echoes traceId, enriches tenantId, and records span into TraceCollector")
    void testTraceFilterEchoAndSpanCollection() throws Exception {
        ChatCompletionResponse mockResp = new ChatCompletionResponse(
                "resp-mock-1", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Trace verified!"), "stop")),
                new Usage(10, 5, 15)
        );
        Mockito.when(primaryLlmClient.chatCompletion(any())).thenReturn(mockResp);

        String jsonBody = """
                {
                  "model": "qwen2.5:0.5b",
                  "messages": [
                    {"role": "user", "content": "hello trace test"}
                  ],
                  "stream": false
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-alpha-secret")
                        .header("X-Trace-Id", "trace-custom-888")
                        .header("X-Session-Id", "sess-custom-777")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "trace-custom-888"));

        // Verify span was recorded in TraceCollector
        List<LlmSpan> spans = traceCollector.getCompletedSpans().stream()
                .filter(s -> s.getContext() != null && "trace-custom-888".equals(s.getContext().traceId()))
                .toList();

        assertThat(spans).isNotEmpty();
        LlmSpan span = spans.get(0);
        assertThat(span.getContext().tenantId()).isEqualTo("tenant-alpha");
        assertThat(span.getContext().sessionId()).isEqualTo("sess-custom-777");
        assertThat(span.getTotalTokens()).isEqualTo(15);
    }
}
