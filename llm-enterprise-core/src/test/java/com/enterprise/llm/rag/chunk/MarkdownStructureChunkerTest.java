package com.enterprise.llm.rag.chunk;

import com.enterprise.llm.rag.model.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownStructureChunkerTest {

    private MarkdownStructureChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new MarkdownStructureChunker(400, 40);
    }

    @Test
    void shouldPreserveHeaderBreadcrumbAcrossSubSections() {
        String doc = """
                # 第一章 员工福利制度
                
                这里是福利总则说明。
                
                ## 1.1 补充医疗保险
                
                补充医疗覆盖所有正式员工门诊与住院报销。
                
                ### 报销比例
                
                门诊自付部分报销 85%，住院报销 95%。
                """;

        List<DocumentChunk> chunks = chunker.chunk(doc, "tenant-a", "handbook.md");
        assertThat(chunks).isNotEmpty();

        DocumentChunk lastChunk = chunks.get(chunks.size() - 1);
        assertThat(lastChunk.text()).contains("[章节: 第一章 员工福利制度 > 1.1 补充医疗保险 > 报销比例]");
        assertThat(lastChunk.text()).contains("门诊自付部分报销 85%");
    }

    @Test
    void shouldKeepMarkdownTableIntactWithHeaders() {
        String doc = """
                # 财务报销标准
                
                | 费用类型 | 报销上限 | 审批人 |
                | :--- | :--- | :--- |
                | 差旅住宿 | 500元/天 | 部门总监 |
                | 团队建设 | 150元/人 | HRBP |
                | 交通补贴 | 实报实销 | 直属主管 |
                """;

        List<DocumentChunk> chunks = chunker.chunk(doc, "tenant-a", "finance.md");
        assertThat(chunks).isNotEmpty();

        DocumentChunk tableChunk = chunks.stream()
                .filter(c -> c.text().contains("| 费用类型 | 报销上限 | 审批人 |"))
                .findFirst()
                .orElse(null);

        assertThat(tableChunk).isNotNull();
        assertThat(tableChunk.text()).contains("| 差旅住宿 | 500元/天 | 部门总监 |");
        assertThat(tableChunk.text()).contains("| 团队建设 | 150元/人 | HRBP |");
    }

    @Test
    void shouldKeepCodeBlockIntact() {
        String doc = """
                # 部署脚本指南
                
                ```bash
                docker run -d --name vllm-worker \\
                  -p 8000:8000 \\
                  vllm/vllm-openai:latest
                ```
                """;

        List<DocumentChunk> chunks = chunker.chunk(doc, "tenant-a", "deploy.md");
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).text()).contains("```bash");
        assertThat(chunks.get(0).text()).contains("docker run -d");
    }
}
