package com.enterprise.llm.rag.rerank;

import com.enterprise.llm.rag.model.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridSearchRerankerTest {

    private HybridSearchReranker reranker;

    @BeforeEach
    void setUp() {
        reranker = new HybridSearchReranker(0.6, 0.4);
    }

    @Test
    void shouldBoostExactEntityMatchAheadOfGenericSemanticMatch() {
        String query = "系统抛出 ERR-50201 应该如何处理？";

        SearchResult hitGeneric = new SearchResult(
                "chunk-1", "general.md", 0,
                "系统一般错误处理规范：遇到通用异常请先重试网络请求，检查超时时间设置。",
                0.85 // High generic vector similarity
        );

        SearchResult hitExactCode = new SearchResult(
                "chunk-2", "errors.md", 1,
                "错误码 ERR-50201 表示显存分配失败(CUDA OOM)，请降低并发批处理量或切换降级备用节点。",
                0.75 // Slightly lower vector similarity
        );

        List<SearchResult> candidateHits = List.of(hitGeneric, hitExactCode);
        List<SearchResult> reranked = reranker.rerank(query, candidateHits, 2, 0.5);

        assertThat(reranked).hasSize(2);
        // The exact match with ERR-50201 should be boosted to first position!
        assertThat(reranked.get(0).id()).isEqualTo("chunk-2");
        assertThat(reranked.get(0).score()).isGreaterThan(reranked.get(1).score());
    }

    @Test
    void shouldFilterOutHitsBelowThreshold() {
        String query = "年金提取流程";

        SearchResult hitRelevant = new SearchResult("chunk-1", "doc.md", 0, "企业年金提取需满足退休或离职条件，前往人事处申请。", 0.80);
        SearchResult hitIrrelevant = new SearchResult("chunk-2", "doc.md", 1, "办公区禁止大声喧哗，保持会议室整洁。", 0.20);

        List<SearchResult> candidateHits = List.of(hitRelevant, hitIrrelevant);
        List<SearchResult> reranked = reranker.rerank(query, candidateHits, 5, 0.5);

        assertThat(reranked).hasSize(1);
        assertThat(reranked.get(0).id()).isEqualTo("chunk-1");
    }
}
