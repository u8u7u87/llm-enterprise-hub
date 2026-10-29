package com.enterprise.llm.rag.chunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.enterprise.llm.rag.model.DocumentChunk;

/**
 * Markdown-aware structural chunker that preserves document hierarchy (H1/H2/H3 breadcrumbs),
 * markdown tables, and code blocks, preventing context loss in deep sub-sections.
 */
public class MarkdownStructureChunker {

    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile("^\\|.*\\|$");

    private final int maxChunkSize;
    private final int chunkOverlap;

    public MarkdownStructureChunker(int maxChunkSize, int chunkOverlap) {
        this.maxChunkSize = Math.max(200, maxChunkSize);
        this.chunkOverlap = Math.max(0, Math.min(chunkOverlap, maxChunkSize / 2));
    }

    public MarkdownStructureChunker() {
        this(600, 50);
    }

    public List<DocumentChunk> chunk(String text, String tenantId, String docName) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");

        String[] headerHierarchy = new String[7]; // levels 1 to 6
        StringBuilder currentSection = new StringBuilder();
        List<String> currentTableLines = new ArrayList<>();
        boolean insideCodeBlock = false;
        StringBuilder currentCodeBlock = new StringBuilder();

        int chunkIndex = 0;

        for (String line : lines) {
            String trimmedLine = line.trim();

            // 1. Code Block Tracking
            if (trimmedLine.startsWith("```")) {
                if (insideCodeBlock) {
                    // Closing code block
                    currentCodeBlock.append(line).append("\n");
                    insideCodeBlock = false;
                    currentSection.append(currentCodeBlock);
                    currentCodeBlock.setLength(0);
                } else {
                    // Opening code block
                    insideCodeBlock = true;
                    currentCodeBlock.append(line).append("\n");
                }
                continue;
            }

            if (insideCodeBlock) {
                currentCodeBlock.append(line).append("\n");
                continue;
            }

            // 2. Table Tracking
            if (TABLE_ROW_PATTERN.matcher(trimmedLine).matches()) {
                currentTableLines.add(line);
                continue;
            } else if (!currentTableLines.isEmpty()) {
                // Table ended, flush table into currentSection
                flushTable(currentTableLines, currentSection, chunks, tenantId, docName, headerHierarchy, chunkIndex);
                chunkIndex = chunks.size();
                currentTableLines.clear();
            }

            // 3. Header Detection
            Matcher headerMatcher = HEADER_PATTERN.matcher(trimmedLine);
            if (headerMatcher.matches()) {
                int level = headerMatcher.group(1).length();
                String title = headerMatcher.group(2).trim();

                // If current section has accumulated content, flush it as chunk(s)
                if (currentSection.length() > 0) {
                    chunkIndex = flushSection(currentSection.toString(), chunks, tenantId, docName, headerHierarchy, chunkIndex);
                    currentSection.setLength(0);
                }

                // Update breadcrumb hierarchy
                headerHierarchy[level] = title;
                // Clear deeper levels
                for (int i = level + 1; i < headerHierarchy.length; i++) {
                    headerHierarchy[i] = null;
                }
                continue;
            }

            // Regular content line
            currentSection.append(line).append("\n");

            // If current section exceeds maxChunkSize, split and flush
            if (currentSection.length() >= maxChunkSize) {
                chunkIndex = flushSection(currentSection.toString(), chunks, tenantId, docName, headerHierarchy, chunkIndex);
                currentSection.setLength(0);
            }
        }

        // Flush remaining table if present
        if (!currentTableLines.isEmpty()) {
            flushTable(currentTableLines, currentSection, chunks, tenantId, docName, headerHierarchy, chunkIndex);
            currentTableLines.clear();
        }

        // Flush remaining section content
        if (currentSection.length() > 0) {
            flushSection(currentSection.toString(), chunks, tenantId, docName, headerHierarchy, chunks.size());
        }

        return chunks;
    }

    private void flushTable(
            List<String> tableLines,
            StringBuilder currentSection,
            List<DocumentChunk> chunks,
            String tenantId,
            String docName,
            String[] headers,
            int startIndex
    ) {
        String breadcrumb = buildBreadcrumb(headers);
        String tableText = String.join("\n", tableLines);

        String chunkContent = breadcrumb.isEmpty() ? tableText : breadcrumb + "\n" + tableText;
        String chunkId = UUID.randomUUID().toString();
        chunks.add(new DocumentChunk(chunkId, tenantId, docName, chunks.size(), chunkContent));
    }

    private int flushSection(
            String content,
            List<DocumentChunk> chunks,
            String tenantId,
            String docName,
            String[] headers,
            int startIndex
    ) {
        String cleanContent = content.trim();
        if (cleanContent.isEmpty()) {
            return startIndex;
        }

        String breadcrumb = buildBreadcrumb(headers);
        String prefix = breadcrumb.isEmpty() ? "" : breadcrumb + "\n";

        if (cleanContent.length() <= maxChunkSize) {
            String fullChunk = prefix + cleanContent;
            chunks.add(new DocumentChunk(UUID.randomUUID().toString(), tenantId, docName, chunks.size(), fullChunk));
        } else {
            // Split long text by paragraphs
            int start = 0;
            int length = cleanContent.length();
            while (start < length) {
                int end = Math.min(start + maxChunkSize, length);
                if (end < length) {
                    int newline = cleanContent.lastIndexOf('\n', end);
                    if (newline > start + (maxChunkSize / 2)) {
                        end = newline;
                    }
                }
                String subText = cleanContent.substring(start, end).trim();
                if (!subText.isEmpty()) {
                    String fullChunk = prefix + subText;
                    chunks.add(new DocumentChunk(UUID.randomUUID().toString(), tenantId, docName, chunks.size(), fullChunk));
                }
                if (end >= length) {
                    break;
                }
                start = Math.max(0, end - chunkOverlap);
            }
        }
        return chunks.size();
    }

    private String buildBreadcrumb(String[] headers) {
        List<String> parts = new ArrayList<>();
        for (int i = 1; i < headers.length; i++) {
            if (headers[i] != null && !headers[i].isBlank()) {
                parts.add(headers[i]);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "[章节: " + String.join(" > ", parts) + "]";
    }
}
