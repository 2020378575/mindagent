package com.mindbridge.agent.service.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型返回里取出决策 JSON。模型常把 JSON 包在 Markdown 里，或在 token 上限处截断。
 */
public final class DecisionResponses {

    private static final Pattern RECOMMENDATION = Pattern.compile(
            "\"recommendation\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private DecisionResponses() {
    }

    public static String extractObject(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("```json", "").replace("```", "");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return loosen(text.substring(start, end + 1));
        }
        return loosen(text);
    }

    /**
     * 即使 JSON 被截断，也尽量读出 recommendation 字段。
     */
    public static String recommendation(String raw) {
        Matcher matcher = RECOMMENDATION.matcher(extractObject(raw));
        if (!matcher.find()) {
            return null;
        }
        String value = unescape(matcher.group(1)).trim();
        return value.isEmpty() ? null : value;
    }

    private static String loosen(String json) {
        return json.replaceAll(",\\s*([}\\]])", "$1");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }
}
