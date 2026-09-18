package com.mindbridge.agent.service.document;

import com.mindbridge.agent.domain.SourceType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
/**
 * 按行记录 TXT/日志的字符偏移，引用时用 startOffset/endOffset 回到原文。
 */
public class PlainTextDocumentParser implements DocumentParser {

    static final String EMPTY_TEXT_MESSAGE = "Plain text source has no usable text";
    static final String PLAIN_TEXT_CONTENT_TYPE = "text/plain";

    @Override
    public boolean supports(String filename, String contentType) {
        String name = DocumentNames.lower(filename);
        String type = DocumentNames.lower(contentType);
        return name.endsWith(".txt") || name.endsWith(".log") || PLAIN_TEXT_CONTENT_TYPE.equals(type);
    }

    @Override
    public SourceType sourceType() {
        return SourceType.TXT;
    }

    @Override
    public ParsedDocument parse(String filename, byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<ParsedSection> sections = new ArrayList<>();
        int order = 0;
        int index = 0;
        while (index < text.length()) {
            int newline = text.indexOf('\n', index);
            int rawEnd = newline < 0 ? text.length() : newline;
            int contentEnd = rawEnd;
            if (contentEnd > index && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            if (contentEnd > index) {
                String line = text.substring(index, contentEnd);
                if (!line.isBlank()) {
                    sections.add(new ParsedSection(order++, null, null, index, contentEnd, line));
                }
            }
            index = newline < 0 ? text.length() : newline + 1;
        }
        if (sections.isEmpty()) {
            throw new DocumentParseException(EMPTY_TEXT_MESSAGE);
        }
        return new ParsedDocument(DocumentNames.titleFromFilename(filename), SourceType.TXT, List.copyOf(sections));
    }
}
