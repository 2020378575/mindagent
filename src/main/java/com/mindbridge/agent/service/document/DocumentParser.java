package com.mindbridge.agent.service.document;

import com.mindbridge.agent.domain.SourceType;

/**
 * 单一资料格式的解析策略。由 {@link DocumentParsingService} 按文件名和内容类型选择。
 */
public interface DocumentParser {

    boolean supports(String filename, String contentType);

    SourceType sourceType();

    ParsedDocument parse(String filename, byte[] content);
}
