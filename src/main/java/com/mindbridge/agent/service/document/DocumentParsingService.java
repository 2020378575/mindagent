package com.mindbridge.agent.service.document;

import com.mindbridge.agent.domain.SourceType;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
/**
 * 按注入的解析策略选择 PDF / Markdown / TXT 实现，不负责落库或切块。
 */
public class DocumentParsingService {

    public static final String UNSUPPORTED_FILE_MESSAGE = "Unsupported research source format";

    private final List<DocumentParser> parsers;

    public DocumentParsingService(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public ParsedDocument parse(String filename, String contentType, byte[] content) {
        return resolveParser(filename, contentType).parse(filename, content);
    }

    public SourceType resolveSourceType(String filename, String contentType) {
        return resolveParser(filename, contentType).sourceType();
    }

    private DocumentParser resolveParser(String filename, String contentType) {
        return parsers.stream()
                .filter(parser -> parser.supports(filename, contentType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(UNSUPPORTED_FILE_MESSAGE));
    }
}
