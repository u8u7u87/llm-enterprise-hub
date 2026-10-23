package com.enterprise.llm.rag.store;

import com.enterprise.llm.rag.model.DocumentChunk;
import com.enterprise.llm.rag.model.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MilvusVectorStoreTest {

    private MilvusVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        // Pointing to a dummy endpoint to test the resilient multi-tenant storage logic
        vectorStore = new MilvusVectorStore("http://127.0.0.1:59999", "test_collection", "test-key");
    }

    @Test
    @DisplayName("Should strictly isolate vector search results across different tenants")
    void shouldIsolateTenants() {
        // Tenant A chunk
        DocumentChunk chunkA = new DocumentChunk(
                "chunk-a-1", "tenant-a", "sales_report.md", 0,
                "Sales increased by 30% in Q3."
        ).withVector(new float[]{1.0f, 0.0f, 0.0f});

        // Tenant B chunk
        DocumentChunk chunkB = new DocumentChunk(
                "chunk-b-1", "tenant-b", "secret_project.md", 0,
                "Project Apollo budget is 5 million dollars."
        ).withVector(new float[]{1.0f, 0.0f, 0.0f});

        vectorStore.insert(List.of(chunkA, chunkB));

        // Search as Tenant A
        float[] query = new float[]{1.0f, 0.0f, 0.0f};
        List<SearchResult> hitsA = vectorStore.search("tenant-a", query, 5, 0.5);

        assertThat(hitsA).hasSize(1);
        assertThat(hitsA.get(0).tenantId()).isEqualTo("tenant-a");
        assertThat(hitsA.get(0).text()).contains("Sales increased");

        // Search as Tenant B
        List<SearchResult> hitsB = vectorStore.search("tenant-b", query, 5, 0.5);
        assertThat(hitsB).hasSize(1);
        assertThat(hitsB.get(0).tenantId()).isEqualTo("tenant-b");
        assertThat(hitsB.get(0).text()).contains("Project Apollo");

        // Tenant C (no data)
        List<SearchResult> hitsC = vectorStore.search("tenant-c", query, 5, 0.5);
        assertThat(hitsC).isEmpty();
    }

    @Test
    @DisplayName("Should rank results by similarity score")
    void shouldRankBySimilarity() {
        DocumentChunk c1 = new DocumentChunk("c1", "t1", "d1", 0, "Partial match")
                .withVector(new float[]{0.5f, 0.5f, 0.0f});

        DocumentChunk c2 = new DocumentChunk("c2", "t1", "d1", 1, "Exact match")
                .withVector(new float[]{1.0f, 0.0f, 0.0f});

        vectorStore.insert(List.of(c1, c2));

        float[] query = new float[]{1.0f, 0.0f, 0.0f};
        List<SearchResult> results = vectorStore.search("t1", query, 5, 0.1);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo("c2");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    @DisplayName("Should delete chunks by document name")
    void shouldDeleteByDocName() {
        DocumentChunk c1 = new DocumentChunk("c1", "t1", "doc_to_delete.txt", 0, "delete me")
                .withVector(new float[]{1.0f, 0.0f});
        DocumentChunk c2 = new DocumentChunk("c2", "t1", "doc_to_keep.txt", 0, "keep me")
                .withVector(new float[]{1.0f, 0.0f});

        vectorStore.insert(List.of(c1, c2));
        vectorStore.deleteByDocName("t1", "doc_to_delete.txt");

        List<SearchResult> remaining = vectorStore.search("t1", new float[]{1.0f, 0.0f}, 10, 0.1);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).docName()).isEqualTo("doc_to_keep.txt");
    }
}
