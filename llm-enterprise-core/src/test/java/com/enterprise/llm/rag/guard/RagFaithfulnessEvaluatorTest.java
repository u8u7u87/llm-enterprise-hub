package com.enterprise.llm.rag.guard;

import com.enterprise.llm.rag.model.SearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagFaithfulnessEvaluatorTest {

    private final RagFaithfulnessEvaluator evaluator = new RagFaithfulnessEvaluator(0.65, 0.40);

    @Test
    @DisplayName("Verify high faithfulness response is PASS with high groundedness score")
    void testHighFaithfulnessPass() {
        List<SearchResult> chunks = List.of(
                new SearchResult("1", "企业员工报销政策：差旅住宿标准为北京、上海每天500元，其他城市每天350元。餐饮补贴每天100元。", 0.90),
                new SearchResult("2", "报销发票必须在费用发生后30个工作日内提交财务系统审批。", 0.85)
        );

        String answer = "根据企业差旅报销政策，北京和上海的住宿标准为每天500元，其他城市为350元。餐饮补贴每天100元，发票需在30个工作日内提交。";

        FaithfulnessResult result = evaluator.evaluate(chunks, answer);

        assertThat(result.groundednessScore()).isGreaterThanOrEqualTo(0.80);
        assertThat(result.action()).isEqualTo(FaithfulnessAction.PASS);
        assertThat(result.safeAnswer()).isEqualTo(answer);
    }

    @Test
    @DisplayName("Verify partial hallucination triggers WARN_APPEND with compliance disclaimer")
    void testPartialHallucinationWarnAppend() {
        List<SearchResult> chunks = List.of(
                new SearchResult("1", "企业年假政策：入职满1年享受5天带薪年假，满10年享受10天。", 0.88)
        );

        // Contains real context (5天带薪年假) plus fabricated ungrounded claims (年度免费体检与海外团建)
        String answer = "入职满1年享受5天带薪年假。此外公司还为全体员工提供年度免费海外豪华邮轮团建和每月5000元子女教育金。";

        FaithfulnessResult result = evaluator.evaluate(chunks, answer);

        assertThat(result.groundednessScore()).isBetween(0.35, 0.65);
        assertThat(result.action()).isEqualTo(FaithfulnessAction.WARN_APPEND);
        assertThat(result.safeAnswer()).contains("以上回答部分内容可能超出知识库记载");
    }

    @Test
    @DisplayName("Verify severe hallucination (0 facts matched) triggers REJECT_FALLBACK")
    void testSevereHallucinationRejectFallback() {
        List<SearchResult> chunks = List.of(
                new SearchResult("1", "办公网络使用规范：公司内网禁止使用未授权的代理工具，违者通报批评。", 0.80)
        );

        // Completely hallucinated, totally unrelated to IT network policy
        String answer = "火星基地殖民计划预计于2035年启动核聚变火箭推进舱装配。";

        FaithfulnessResult result = evaluator.evaluate(chunks, answer);

        assertThat(result.groundednessScore()).isLessThan(0.40);
        assertThat(result.action()).isEqualTo(FaithfulnessAction.REJECT_FALLBACK);
        assertThat(result.safeAnswer()).contains("未能找到足以支持该结论的知识库事实依据");
    }

    @Test
    @DisplayName("Verify empty chunks gracefully handles empty context")
    void testEmptyChunksHandling() {
        FaithfulnessResult result = evaluator.evaluate(Collections.emptyList(), "任何回答");
        assertThat(result.groundednessScore()).isEqualTo(0.0);
        assertThat(result.action()).isEqualTo(FaithfulnessAction.PASS); // If no chunks were provided, no RAG constraint
    }
}
