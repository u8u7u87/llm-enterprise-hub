package com.enterprise.llm.rag.guard;

/**
 * Result of RAG Faithfulness / Groundedness Evaluation.
 */
public record FaithfulnessResult(
        double groundednessScore,
        FaithfulnessAction action,
        String reasoning,
        String safeAnswer,
        int totalClaimsEvaluated,
        int supportedClaimsCount
) {
    public static final String DEFAULT_WARNING_APPEND = "\n\n> ⚠️ [系统提示]：以上回答部分内容可能超出知识库记载，请审慎核实。";
    public static final String DEFAULT_FALLBACK_ANSWER = "抱歉，根据当前企业知识库资料，未能找到足以支持该结论的知识库事实依据。请提供更多背景信息或咨询相关业务部门。";

    public static FaithfulnessResult pass(double score, String answer, int total, int supported, String reasoning) {
        return new FaithfulnessResult(score, FaithfulnessAction.PASS, reasoning, answer, total, supported);
    }

    public static FaithfulnessResult warn(double score, String answer, int total, int supported, String reasoning) {
        String adjusted = answer + DEFAULT_WARNING_APPEND;
        return new FaithfulnessResult(score, FaithfulnessAction.WARN_APPEND, reasoning, adjusted, total, supported);
    }

    public static FaithfulnessResult fallback(double score, int total, int supported, String reasoning) {
        return new FaithfulnessResult(score, FaithfulnessAction.REJECT_FALLBACK, reasoning, DEFAULT_FALLBACK_ANSWER, total, supported);
    }
}
