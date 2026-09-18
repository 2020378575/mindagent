package com.mindbridge.agent.service.document;

import java.util.Locale;

/**
 * 文件名展示与标题推导。不得把用户提交的文件名拼进存储路径。
 */
final class DocumentNames {

    static final String UNTITLED_SOURCE = "Untitled source";

    private DocumentNames() {
    }

    static String displayFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        String value = filename.trim().replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash < value.length() - 1) {
            value = value.substring(slash + 1);
        } else if (slash >= 0) {
            value = "unnamed";
        }
        return value.length() > 180 ? value.substring(value.length() - 180) : value;
    }

    static String titleFromFilename(String filename) {
        String display = displayFilename(filename);
        int dot = display.lastIndexOf('.');
        String title = dot > 0 ? display.substring(0, dot) : display;
        return title.isBlank() || "unnamed".equals(title) ? UNTITLED_SOURCE : title;
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
