package com.enterprise.llm.rag;

import com.enterprise.llm.rag.embedding.EmbeddingClient;
import com.enterprise.llm.rag.model.SearchResult;
import com.enterprise.llm.rag.store.VectorStore;
import com.enterprise.llm.ratelimit.TenantRateLimiter;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KnowledgeBaseController.class)
class KnowledgeBaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private VectorStore vectorStore;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private TenantRateLimiter tenantRateLimiter;

    @BeforeEach
    void setUp() {
        TenantEntity tenant = new TenantEntity("tenant-1", "Test Tenant", "sk-test-key", 10, 10000, 0, true);
        when(tenantService.authenticateApiKey("sk-test-key")).thenReturn(Optional.of(tenant));
        when(tenantRateLimiter.tryAcquire(any())).thenReturn(true);
    }

    @Test
    @DisplayName("Should ingest document chunks into vector store under current tenant")
    void shouldIngestDocument() throws Exception {
        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(new float[]{0.1f, 0.2f}));

        String payload = """
                {
                  "docName": "company_handbook.md",
                  "content": "Our core company values are integrity, innovation, and customer-first commitment."
                }
                """;

        mockMvc.perform(post("/v1/knowledge/documents")
                        .header("X-API-Key", "sk-test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.tenant_id").value("tenant-1"))
                .andExpect(jsonPath("$.doc_name").value("company_handbook.md"))
                .andExpect(jsonPath("$.chunk_count").value(1));

        verify(vectorStore).insert(anyList());
    }

    @Test
    @DisplayName("Should search knowledge base and return hits for current tenant")
    void shouldSearchKnowledge() throws Exception {
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        SearchResult hit = new SearchResult(
                "chunk-1", "tenant-1", "company_handbook.md", 0,
                "Our core company values are integrity.", 0.92, Collections.emptyMap()
        );
        when(vectorStore.search(eq("tenant-1"), any(), eq(3), eq(0.5)))
                .thenReturn(List.of(hit));

        String payload = """
                {
                  "query": "What are our values?",
                  "topK": 3,
                  "minScore": 0.5
                }
                """;

        mockMvc.perform(post("/v1/knowledge/search")
                        .header("X-API-Key", "sk-test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].docName").value("company_handbook.md"))
                .andExpect(jsonPath("$[0].score").value(0.92));
    }

    @Test
    @DisplayName("Should delete document chunks by docName")
    void shouldDeleteDocument() throws Exception {
        mockMvc.perform(delete("/v1/knowledge/documents/company_handbook.md")
                        .header("X-API-Key", "sk-test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(vectorStore).deleteByDocName("tenant-1", "company_handbook.md");
    }
}
