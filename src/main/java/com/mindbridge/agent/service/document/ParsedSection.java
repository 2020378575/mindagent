package com.mindbridge.agent.service.document;

/**
 * 带稳定来源位置的解析片段。PDF 用页码，Markdown 用标题，TXT 用字符偏移。
 */
public record ParsedSection(
        int order,
        Integer pageNumber,
        String heading,
        int startOffset,
        int endOffset,
        String content
) {
}
