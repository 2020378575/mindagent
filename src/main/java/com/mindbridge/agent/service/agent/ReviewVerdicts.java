package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.ReviewVerdict;
import java.util.Locale;

/**
 * 从复核推荐文本解析结构化裁决。没有明确信号时记为证据不足，不再靠默认猜「支持」。
 */
public final class ReviewVerdicts {

    private ReviewVerdicts() {
    }

    public static ReviewVerdict fromRecommendation(String recommendation) {
        if (recommendation == null || recommendation.isBlank()) {
            return ReviewVerdict.INCONCLUSIVE;
        }
        String text = recommendation.toLowerCase(Locale.ROOT);
        if (text.contains("refut") || text.contains("证伪") || text.contains("否定") || text.contains("不支持")) {
            return ReviewVerdict.REFUTED;
        }
        if (text.contains("partial") || text.contains("部分")) {
            return ReviewVerdict.PARTIALLY_SUPPORTED;
        }
        if (text.contains("inconclusive") || text.contains("不确定") || text.contains("不足")) {
            return ReviewVerdict.INCONCLUSIVE;
        }
        if (text.contains("support") || text.contains("验证") || text.contains("支持")) {
            return ReviewVerdict.SUPPORTED;
        }
        return ReviewVerdict.INCONCLUSIVE;
    }
}
