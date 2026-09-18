package com.mindbridge.agent.service.document;

import com.mindbridge.agent.domain.SourceType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
/**
 * 按 Markdown 标题层级切段，保留 heading 作为后续引用定位。
 */
public class MarkdownDocumentParser implements DocumentParser {

    static final String EMPTY_MARKDOWN_MESSAGE = "Markdown source has no usable text";
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)$", Pattern.MULTILINE);

    @Override
    public boolean supports(String filename, String contentType) {
        String name = DocumentNames.lower(filename);
        String type = DocumentNames.lower(contentType);
        return name.endsWith(".md") || name.endsWith(".markdown") || type.contains("markdown");
    }

    @Override
    public SourceType sourceType() {
        return SourceType.MARKDOWN;
    }

    @Override
    public ParsedDocument parse(String filename, byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<ParsedSection> sections = new ArrayList<>();
        Matcher matcher = HEADING.matcher(text);
        int order = 0;
        boolean seenHeading = false;
        String currentHeading = null;
        int currentStart = 0;
        while (matcher.find()) {
            if (!seenHeading && matcher.start() > 0) {
                order = addSection(sections, order, null, 0, matcher.start(), text);
            } else if (seenHeading) {
                order = addSection(sections, order, currentHeading, currentStart, matcher.start(), text);
            }
            seenHeading = true;
            currentHeading = matcher.group(2).trim();
            currentStart = matcher.start();
        }
        if (seenHeading) {
            order = addSection(sections, order, currentHeading, currentStart, text.length(), text);
        } else {
            addSection(sections, order, null, 0, text.length(), text);
        }
        if (sections.isEmpty()) {
            throw new DocumentParseException(EMPTY_MARKDOWN_MESSAGE);
        }
        String title = sections.stream()
                .map(ParsedSection::heading)
                .filter(heading -> heading != null && !heading.isBlank())
                .findFirst()
                .orElseGet(() -> DocumentNames.titleFromFilename(filename));
        return new ParsedDocument(title, SourceType.MARKDOWN, List.copyOf(sections));
    }

    private int addSection(
            List<ParsedSection> sections,
            int order,
            String heading,
            int start,
            int end,
            String text
    ) {
        if (end <= start) {
            return order;
        }
        String body = text.substring(start, end).trim();
        if (body.isBlank()) {
            return order;
        }
        sections.add(new ParsedSection(order, null, heading, start, end, body));
        return order + 1;
    }
}
