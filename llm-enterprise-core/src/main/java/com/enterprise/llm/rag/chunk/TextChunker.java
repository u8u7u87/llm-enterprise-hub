package com.enterprise.llm.rag.chunk;

import com.enterprise.llm.rag.model.DocumentChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class TextChunker {

    private final int chunkSize;
    private final int chunkOverlap;

    public TextChunker(int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be greater than 0");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be >= 0 and < chunkSize");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    public TextChunker() {
        this(500, 50);
    }

    public List<DocumentChunk> chunk(String text, String tenantId, String docName) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        String cleanedText = text.trim();
        List<DocumentChunk> chunks = new ArrayList<>();
        int length = cleanedText.length();
        int start = 0;
        int chunkIndex = 0;

        while (start < length) {
            int end = Math.min(start + chunkSize, length);

            // Attempt to break at natural paragraph or sentence endings if not at the very end
            if (end < length) {
                int naturalBreak = findNaturalBreak(cleanedText, start, end);
                if (naturalBreak > start + (chunkSize / 2)) {
                    end = naturalBreak;
                }
            }

            String chunkContent = cleanedText.substring(start, end).trim();
            if (!chunkContent.isEmpty()) {
                String id = UUID.randomUUID().toString();
                chunks.add(new DocumentChunk(id, tenantId, docName, chunkIndex++, chunkContent));
            }

            if (end >= length) {
                break;
            }

            start = end - chunkOverlap;
            if (start < 0) {
                start = 0;
            }
        }

        return chunks;
    }

    private int findNaturalBreak(String text, int start, int end) {
        for (int i = end; i > start; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return i;
            }
        }
        return end;
    }
}
