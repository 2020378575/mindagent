package com.mindbridge.agent.service.knowledge;

import com.mindbridge.agent.service.document.DocumentParseException;
import com.mindbridge.agent.service.document.DocumentParsingService;
import com.mindbridge.agent.service.document.ParsedDocument;
import com.mindbridge.agent.service.document.ParsedSection;
import com.mindbridge.agent.service.document.ResearchSourceService;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
/**
 * 管理员文件上传知识库服务。
 *
 * <p>负责文件大小校验，文本抽取交给位置感知解析器，抽取后的文本交给 KnowledgeService 处理。</p>
 */
public class KnowledgeFileService {

    private final KnowledgeService knowledgeService;
    private final DocumentParsingService documentParsingService;

    public KnowledgeFileService(
            KnowledgeService knowledgeService,
            DocumentParsingService documentParsingService
    ) {
        this.knowledgeService = knowledgeService;
        this.documentParsingService = documentParsingService;
    }

    public int ingest(String filename, byte[] bytes) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (bytes.length > ResearchSourceService.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件不能超过 10MB");
        }
        ParsedDocument parsed;
        try {
            parsed = documentParsingService.parse(filename, null, bytes);
        } catch (IllegalArgumentException | DocumentParseException exception) {
            throw new IllegalArgumentException(exception.getMessage());
        }
        String text = parsed.sections().stream()
                .map(ParsedSection::content)
                .collect(Collectors.joining("\n\n"));
        if (text.isBlank()) {
            throw new IllegalArgumentException("没有从文件中解析出可用文本");
        }
        return knowledgeService.ingest(sanitizeSource(filename), text);
    }

    private String sanitizeSource(String filename) {
        String source = filename == null || filename.isBlank() ? "uploaded-knowledge" : filename.trim();
        source = source.replaceAll("[\\\\/]+", "-");
        return source.length() > 180 ? source.substring(source.length() - 180) : source;
    }
}
