package com.mindbridge.agent.service.document;

import com.mindbridge.agent.domain.SourceType;
import java.util.List;

/**
 * 一份研究资料的解析结果。Task 3 会按 section 切块并写入项目级索引。
 */
public record ParsedDocument(
        String title,
        SourceType sourceType,
        List<ParsedSection> sections
) {
}
