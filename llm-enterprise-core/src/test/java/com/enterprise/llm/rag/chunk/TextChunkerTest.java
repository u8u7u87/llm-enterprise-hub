package com.enterprise.llm.rag.chunk;

import com.enterprise.llm.rag.model.DocumentChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    @Test
    @DisplayName("Should return empty list for null or blank text")
    void shouldHandleBlankText() {
        TextChunker chunker = new TextChunker(100, 20);
        assertThat(chunker.chunk("", "tenant-1", "doc1.txt")).isEmpty();
        assertThat(chunker.chunk(null, "tenant-1", "doc1.txt")).isEmpty();
    }

    @Test
    @DisplayName("Should split long text into overlapping chunks")
    void shouldSplitIntoOverlappingChunks() {
        TextChunker chunker = new TextChunker(30, 10);
        String text = "第一句话包含一些信息。第二句话提供了更多的细节内容。第三句话给出了最后的结论和总结。第四句话展示了更多的说明。";

        List<DocumentChunk> chunks = chunker.chunk(text, "tenant-a", "notice.md");

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).tenantId()).isEqualTo("tenant-a");
        assertThat(chunks.get(0).docName()).isEqualTo("notice.md");
        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);
        assertThat(chunks.get(1).chunkIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should validate chunkSize and chunkOverlap arguments")
    void shouldValidateArguments() {
        assertThatThrownBy(() -> new TextChunker(0, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new TextChunker(50, 60))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
