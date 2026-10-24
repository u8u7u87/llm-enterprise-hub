package com.enterprise.llm.rag.query;

import com.enterprise.llm.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextualQueryRewriterTest {

    private ContextualQueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        rewriter = new ContextualQueryRewriter();
    }

    @Test
    void shouldKeepSingleTurnQueryUnchanged() {
        List<ChatMessage> history = List.of(new ChatMessage("user", "什么是企业年金？"));
        String rewritten = rewriter.rewriteQuery(history, "什么是企业年金？");
        assertThat(rewritten).isEqualTo("什么是企业年金？");
    }

    @Test
    void shouldDisambiguatePronounInMultiTurnDialogue() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "请介绍一下公司的商业补充医疗险"),
                new ChatMessage("assistant", "商业补充医疗险是企业为员工提供的额外医疗报销福利。"),
                new ChatMessage("user", "它报销门诊费用吗？")
        );

        String rewritten = rewriter.rewriteQuery(history, "它报销门诊费用吗？");
        assertThat(rewritten).contains("商业补充医疗险");
        assertThat(rewritten).contains("它报销门诊费用吗？");
    }

    @Test
    void shouldExtractQuotedEntityFromHistory() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "我想咨询《企业年金管理规定》的相关内容"),
                new ChatMessage("assistant", "该规定明确了企业年金的缴费比例与归属权益。"),
                new ChatMessage("user", "需要满足什么条件？")
        );

        String rewritten = rewriter.rewriteQuery(history, "需要满足什么条件？");
        assertThat(rewritten).isEqualTo("[企业年金管理规定] 需要满足什么条件？");
    }

    @Test
    void shouldNotDuplicatePrefixIfQueryAlreadyContainsEntity() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "请问商业补充医疗怎么申请"),
                new ChatMessage("assistant", "可以在 OA 系统福利专区申请。"),
                new ChatMessage("user", "商业补充医疗包括家属吗？")
        );

        String rewritten = rewriter.rewriteQuery(history, "商业补充医疗包括家属吗？");
        assertThat(rewritten).isEqualTo("商业补充医疗包括家属吗？");
    }
}
