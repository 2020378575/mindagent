package com.mindbridge.agent.service.knowledge;

import com.mindbridge.agent.service.document.ParsedDocument;
import com.mindbridge.agent.service.document.ParsedSection;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文本切块器。
 *
 * <p>项目资料按 section 切块，不跨页或跨标题合并。全局知识库仍按纯文本切分。</p>
 */
public class KnowledgeChunker {

    public record PositionedChunk(
            int sourceIndex,
            Integer pageNumber,
            String heading,
            int startOffset,
            int endOffset,
            String content
    ) {
    }

    public List<PositionedChunk> chunk(ParsedDocument document, int chunkSize, int overlap) {
        if (document == null || document.sections() == null || document.sections().isEmpty()) {
            return List.of();
        }
        List<PositionedChunk> chunks = new ArrayList<>();
        int sourceIndex = 0;
        for (ParsedSection section : document.sections()) {
            sourceIndex = chunkSection(section, chunkSize, overlap, chunks, sourceIndex);
        }
        return List.copyOf(chunks);
    }

    public List<String> chunk(String content, int chunkSize, int overlap) {
        return split(content, chunkSize, overlap).stream()
                .map(TextSlice::text)
                .toList();
    }

    private int chunkSection(
            ParsedSection section,
            int chunkSize,
            int overlap,
            List<PositionedChunk> chunks,
            int sourceIndex
    ) {
        if (section == null || section.content() == null || section.content().isBlank()) {
            return sourceIndex;
        }
        String text = section.content();
        int sectionStart = Math.max(0, section.startOffset());
        for (TextSlice slice : split(text, chunkSize, overlap)) {
            int startOffset = sectionStart + slice.start();
            int endOffset = sectionStart + slice.end();
            if (endOffset <= startOffset) {
                endOffset = startOffset + slice.text().length();
            }
            chunks.add(new PositionedChunk(
                    sourceIndex++,
                    section.pageNumber(),
                    section.heading(),
                    startOffset,
                    endOffset,
                    slice.text()
            ));
        }
        return sourceIndex;
    }

    private List<TextSlice> split(String content, int chunkSize, int overlap) {
        String text = content == null ? "" : content.replace("\r\n", "\n");
        if (text.isBlank()) {
            return List.of();
        }
        List<TextSlice> chunks = new ArrayList<>();
        int safeSize = Math.max(120, chunkSize);
        int safeOverlap = Math.max(0, Math.min(overlap, safeSize / 2));
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(text.length(), index + safeSize);
            if (end < text.length()) {
                int boundary = Math.max(
                        Math.max(text.lastIndexOf("\n", end), text.lastIndexOf("。", end)),
                        Math.max(text.lastIndexOf(".", end), text.lastIndexOf("?", end)));
                if (boundary > index + safeSize / 2) {
                    end = boundary + 1;
                }
            }
            String piece = text.substring(index, end).trim();
            if (!piece.isBlank()) {
                chunks.add(new TextSlice(index, end, piece));
            }
            if (end >= text.length()) {
                break;
            }
            index = Math.max(index + 1, end - safeOverlap);
        }
        return chunks;
    }

    private record TextSlice(int start, int end, String text) {
    }
}
