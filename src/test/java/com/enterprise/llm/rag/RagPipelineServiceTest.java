package com.enterprise.llm.rag;

import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.rag.embedding.EmbeddingClient;
import com.enterprise.llm.rag.model.SearchResult;
import com.enterprise.llm.rag.store.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagPipelineServiceTest {

    private EmbeddingClient mockEmbeddingClient;
    private VectorStore mockVectorStore;
    private RagPipelineService ragPipelineService;

    @BeforeEach
    void setUp() {
        mockEmbeddingClient = mock(EmbeddingClient.class);
        mockVectorStore = mock(VectorStore.class);
        ragPipelineService = new RagPipelineService(mockEmbeddingClient, mockVectorStore, true, 3, 0.5);
    }

    @Test
    @DisplayName("Should augment messages with retrieved reference context when query matches")
    void shouldAugmentWhenMatchesFound() {
        when(mockEmbeddingClient.embed(eq("What is our Q3 revenue?")))
                .thenReturn(new float[]{0.1f, 0.2f});

        SearchResult hit = new SearchResult(
                "chunk-1", "tenant-fin", "q3_report.md", 0,
                "Q3 revenue was 15 million USD.", 0.95, Collections.emptyMap()
        );
        when(mockVectorStore.search(eq("tenant-fin"), any(), eq(3), eq(0.5)))
                .thenReturn(List.of(hit));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("What is our Q3 revenue?")),
                false,
                0.7
        );

        ChatCompletionRequest augmented = ragPipelineService.processAndAugment(request, "tenant-fin");

        assertThat(augmented).isNotNull();
        assertThat(augmented.messages()).hasSize(2);
        assertThat(augmented.messages().get(0).role()).isEqualTo("system");
        assertThat(augmented.messages().get(0).content()).contains("Q3 revenue was 15 million USD.");
        assertThat(augmented.messages().get(1).content()).isEqualTo("What is our Q3 revenue?");
    }

    @Test
    @DisplayName("Should return original request when no hits found")
    void shouldNotAugmentWhenNoHits() {
        when(mockEmbeddingClient.embed(anyString())).thenReturn(new float[]{0.1f});
        when(mockVectorStore.search(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(Collections.emptyList());

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("Non-existent knowledge query")),
                false,
                0.7
        );

        ChatCompletionRequest result = ragPipelineService.processAndAugment(request, "tenant-fin");
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).role()).isEqualTo("user");
    }
}
